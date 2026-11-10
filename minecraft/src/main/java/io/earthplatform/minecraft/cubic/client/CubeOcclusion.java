package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

/**
 * Occlusion culling of near cubes in two phases, as Horizon (a Rust rewrite of the game) culls its sections, with
 * OpenGL 3.3's occlusion queries and conditional rendering, so it runs on a Mac's OpenGL 4.1 too.
 *
 * <p>Phase one draws, as ever, the cubes whose box the last test saw (and cubes never tested). Then, against the depth
 * they left, the box of each other cube in view is drawn under a query, with no colour or depth written, and that
 * cube's layers are drawn on condition that some of its box passed the depth test: the GPU decides, in the same frame,
 * so nothing pops in a frame late. The boxes are the cubes' grown by a block (the farthest a block's model reaches out
 * of its cell), and only their sides facing the camera are drawn: those lie in front of everything in the box, and
 * the camera is never inside a box tested, so a cube is left out only where every fragment of it would have failed the
 * depth test against depth that the rest of the frame only brings nearer: the picture is the one without culling.
 * Cubes phase one drew are tested now and then too (a share each frame), to notice when they go out of sight.
 *
 * <p>Only in a frame's first pass of the level, not in the shadow map: a shadow sees what the camera doesn't.
 * Off by default (see {@link #ON}); {@code -Dearthplatform.cubes.occlusion=true} turns it on.
 */
final class CubeOcclusion {
    /**
     * Off unless {@code -Dearthplatform.cubes.occlusion=true}: on a 2080 Ti under Photon the tests cost more than they
     * saved (valley floor 64 fps with them, 73 without), so the cave search alone culls near cubes by default.
     */
    static final boolean ON = "true".equals(System.getProperty("earthplatform.cubes.occlusion"));
    /** One in this many of the cubes phase one draws is tested each frame. */
    static final int RECHECK_EVERY = 4;
    /** Blocks the boxes are grown by. */
    static final float GROW = 1.0F;
    /** Test only the box's sides facing the camera, half the fill; {@code -Dearthplatform.cubes.occlusionFront=false} draws both. */
    static final boolean FRONT_ONLY = !"false".equals(System.getProperty("earthplatform.cubes.occlusionFront"));

    private static final String VERTEX = """
            #version 150
            uniform mat4 earth_ViewProjection;
            uniform vec3 earth_Low;
            uniform vec3 earth_High;
            // A box's twelve triangles, by corner: bit 0 is x, bit 1 y, bit 2 z.
            const int CORNERS[36] = int[36](0, 2, 6, 0, 6, 4, 1, 5, 7, 1, 7, 3, 0, 4, 5, 0, 5, 1,
                                            2, 3, 7, 2, 7, 6, 0, 1, 3, 0, 3, 2, 4, 6, 7, 4, 7, 5);
            void main() {
                int corner = CORNERS[gl_VertexID];
                vec3 at = vec3((corner & 1) != 0 ? earth_High.x : earth_Low.x, (corner & 2) != 0 ? earth_High.y : earth_Low.y,
                               (corner & 4) != 0 ? earth_High.z : earth_Low.z);
                gl_Position = earth_ViewProjection * vec4(at, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            out vec4 earth_Colour;
            void main() { earth_Colour = vec4(0.0); }
            """;

    private int program, array, viewProjection, low, high;
    private boolean failed;

    /** Whether this GL has occlusion queries and conditional rendering (OpenGL 3.3) and the box program links. */
    boolean ready() {
        if (program != 0) return true;
        if (failed) return false;
        if (!GL.getCapabilities().OpenGL33) { failed = true; return false; }
        try {
            program = link();
            array = GL30C.glGenVertexArrays();
            viewProjection = GL20C.glGetUniformLocation(program, "earth_ViewProjection");
            low = GL20C.glGetUniformLocation(program, "earth_Low");
            high = GL20C.glGetUniformLocation(program, "earth_High");
            return true;
        } catch (IllegalStateException failure) {
            failed = true;
            EarthPlatformMod.LOGGER.warn("Near cubes can't be occlusion culled; every cube in view is drawn", failure);
            return false;
        }
    }

    /** Sets up drawing boxes: no colour, no depth written, sides facing the camera, the depth test as terrain has it. */
    State begin(Matrix4f modelView, Matrix4f projection) {
        State state = new State();
        GlStateManager._colorMask(false, false, false, false);
        GlStateManager._depthMask(false);
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11C.GL_LEQUAL);
        if (FRONT_ONLY) {
            // The table's triangles wind counter-clockwise seen from inside the box: culling those leaves the sides
            // facing the camera, which lie in front of everything in the box, so the test is as safe with half the fill.
            GlStateManager._enableCull();
            GL11C.glCullFace(GL11C.GL_FRONT);
        } else {
            GlStateManager._disableCull();
        }
        GlStateManager._disableBlend();
        GlStateManager._glUseProgram(program);
        GL30C.glBindVertexArray(array);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            GL20C.glUniformMatrix4fv(viewProjection, false, new Matrix4f(projection).mul(modelView).get(matrix));
        }
        return state;
    }

    /** Draws one box, relative to the camera, under the query {@code query}. */
    void test(int query, float x0, float y0, float z0, float x1, float y1, float z1) {
        GL20C.glUniform3f(low, x0 - GROW, y0 - GROW, z0 - GROW);
        GL20C.glUniform3f(high, x1 + GROW, y1 + GROW, z1 + GROW);
        GL15C.glBeginQuery(GL33C.GL_ANY_SAMPLES_PASSED, query);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 36);
        GL15C.glEndQuery(GL33C.GL_ANY_SAMPLES_PASSED);
    }

    /** Whether a query's answer has come; {@link #seen} may be asked then without waiting. */
    static boolean answered(int query) { return GL15C.glGetQueryObjecti(query, GL15C.GL_QUERY_RESULT_AVAILABLE) != 0; }

    /** Whether some of the box the query tested passed the depth test. */
    static boolean seen(int query) { return GL15C.glGetQueryObjecti(query, GL15C.GL_QUERY_RESULT) != 0; }

    static int newQuery() { return GL15C.glGenQueries(); }

    static void deleteQuery(int query) { if (query != 0) GL15C.glDeleteQueries(query); }

    /** The GL state {@link #begin} changes, put back by {@link #end}. */
    static final class State {
        private final int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), array = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        private final boolean[] colour = new boolean[4];
        private final boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK), depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE), blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        private final int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC), cullFace = GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE);

        State() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var bytes = stack.malloc(4);
                GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, bytes);
                for (int i = 0; i < 4; i++) colour[i] = bytes.get(i) != 0;
            }
        }

        void end() {
            GL30C.glBindVertexArray(array);
            GlStateManager._glUseProgram(program);
            GlStateManager._colorMask(colour[0], colour[1], colour[2], colour[3]);
            GlStateManager._depthMask(depthMask);
            if (depthTest) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
            GlStateManager._depthFunc(depthFunc);
            GL11C.glCullFace(cullFace);
            if (cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
            if (blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
        }
    }

    /** Draws only where the query's box was seen; the GPU waits for the answer, the CPU doesn't. */
    static void beginConditional(int query) { GL30C.glBeginConditionalRender(query, GL30C.GL_QUERY_WAIT); }

    static void endConditional() { GL30C.glEndConditionalRender(); }

    private static int link() {
        int vertex = shader(GL20C.GL_VERTEX_SHADER, VERTEX), fragment = shader(GL20C.GL_FRAGMENT_SHADER, FRAGMENT);
        int linked = GL20C.glCreateProgram();
        GL20C.glAttachShader(linked, vertex);
        GL20C.glAttachShader(linked, fragment);
        GL20C.glLinkProgram(linked);
        GL20C.glDetachShader(linked, vertex);
        GL20C.glDetachShader(linked, fragment);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(linked);
            GL20C.glDeleteProgram(linked);
            throw new IllegalStateException("Could not link the near cube box test: " + log);
        }
        return linked;
    }

    private static int shader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile the near cube box test: " + log);
        }
        return shader;
    }

    void close() {
        RenderSystem.assertOnRenderThread();
        if (program != 0) GL20C.glDeleteProgram(program);
        if (array != 0) GL30C.glDeleteVertexArrays(array);
        program = array = 0;
    }
}
