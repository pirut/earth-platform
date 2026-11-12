package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

/**
 * The horizon's GL parts on the real driver, in a hidden window, with {@code EARTH_GL_TESTS=1}:
 * every far terrain shader variant they add, far water's curve, and far terrain's own depth.
 */
@EnabledIfEnvironmentVariable(named = "EARTH_GL_TESTS", matches = "1")
class FarPlainDepthGlTest {
    private static long window;
    private static final Path CORE = Path.of(System.getProperty("earthplatform.test.projectDirectory"), "src/main/resources/assets/earthplatform/shaders/core");

    @BeforeAll static void open() {
        assertTrue(GLFW.glfwInit());
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(16, 16, "Earth horizon check", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
        System.out.println("HORIZON_GPU " + GL11C.glGetString(GL11C.GL_RENDERER) + " / " + GL11C.glGetString(GL11C.GL_VERSION));
    }

    @AfterAll static void close() {
        GL.setCapabilities(null);
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    /** Every vanilla variant, with the horizon's flags off and on: the defines go in front, as the flags put them. */
    @Test void everyFarTerrainVariantBuilds() {
        String curve = "#define EARTH_CURVATURE\nconst float earth_Flat = 2000.0;\n", air = "#define EARTH_ATMOSPHERE\n";
        String[] flags = {"", curve, air, curve + air, curve + air + "#define EARTH_BLOCK_TEXTURES\n"};
        for (String flag : flags) {
            for (int fade = 0; fade < 3; fade++)
                for (String mode : new String[] {"", FarShaders.GROUND, FarShaders.PRECISE_DEPTH}) {
                    String header = "#version 330 core\n" + flag + mode + FarShaders.fade(fade);
                    GL20C.glDeleteProgram(GlShaders.link(header + FarShaders.VERTEX, header + FarShaders.FRAGMENT, "variant " + flag + mode + fade));
                }
            String header = "#version 330 core\n" + flag;
            GL20C.glDeleteProgram(GlShaders.link(header + FarShaders.VERTEX, header + FarShaders.PACK_FALLBACK, "pack fallback " + flag));
        }
        assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
    }

    @Test void curvedWaterShaderBuilds() throws Exception {
        int program = GlShaders.link(Files.readString(CORE.resolve("earthplatform_water.vsh")), Files.readString(CORE.resolve("earthplatform_water.fsh")), "curved water");
        for (String uniform : new String[] {"EarthCurvature", "EarthFlat", "EarthDepthBias", "ChunkOffset", "ModelViewMat", "Sampler0", "Sampler2"})
            assertNotEquals(-1, GL20C.glGetUniformLocation(program, uniform), uniform);
        GL20C.glDeleteProgram(program);
        assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
    }

    /**
     * An ocean 70 km off under a 10° zoom: bent column by column on each one's own tangent, its
     * columns part in cracks; bent in the water shader, cut at {@link EarthView#WATER_GRID}, none.
     */
    @Test void curvedOceanKeepsItsColumnsJoined() throws Exception {
        int width = 960, height = 540;
        int target = GL30C.glGenFramebuffers(), colour = GL11C.glGenTextures(), vao = GL30C.glGenVertexArrays(), buffer = GL15C.glGenBuffers();
        String vertex = Files.readString(CORE.resolve("earthplatform_water.vsh"));
        int program = GlShaders.link(vertex, "#version 150\nout vec4 colour; void main() { colour = vec4(0.03, 0.2, 0.45, 1); }", "curved ocean");
        try {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
            texture(colour, GL11C.GL_RGBA8, GL11C.GL_RGBA, width, height);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));
            GL11C.glViewport(0, 0, width, height);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
            GlStateManager._glUseProgram(program);
            int position = GL20C.glGetAttribLocation(program, "Position");
            GL20C.glEnableVertexAttribArray(position);
            GL20C.glVertexAttribPointer(position, 3, GL11C.GL_FLOAT, false, 12, 0L);
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(10), width / (float) height, .05f, 190000);
            Matrix4f view = new Matrix4f().rotateX((float) Math.atan2(8300, 73728));
            float curve = EarthView.full(1);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "ProjMat"), false, projection.get(new float[16]));
            GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "EarthFlat"), (float) EarthView.FLAT);
            int[] cracks = new int[2];
            for (int pass = 0; pass < 2; pass++) {
                GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, new float[] {1, .4f, 0, 1});
                GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "EarthCurvature"), pass == 0 ? 0 : curve);
                GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "EarthDepthBias"), pass == 0 ? 0 : 2f / (1 << 24));
                for (int z = -86016; z < -65536; z += 4096) for (int x = -8192; x < 8192; x += 4096) {
                    Matrix4f matrix = view;
                    if (pass == 0) {
                        // Each column on the tangent plane at its middle, then shrunk toward the camera as flat far water is.
                        double distance = Math.sqrt((x + 2048.0) * (x + 2048) + (z + 2048.0) * (z + 2048) + 8000.0 * 8000);
                        matrix = tangent(view, x + 2048, z + 2048, curve).scale((float) Math.max(.8, 1 - distance * (2.0 / (1 << 24)) / .05));
                    }
                    GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "ModelViewMat"), false, matrix.get(new float[16]));
                    GL20C.glUniform3f(GL20C.glGetUniformLocation(program, "ChunkOffset"), x, -8000, z);
                    int step = pass == 0 ? 4096 : EarthView.WATER_GRID;
                    for (int dz = 0; dz < 4096; dz += step) for (int dx = 0; dx < 4096; dx += step) {
                        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, new float[] {dx, 0, dz, dx + step, 0, dz, dx + step, 0, dz + step,
                                dx, 0, dz, dx + step, 0, dz + step, dx, 0, dz + step}, GL15C.GL_STREAM_DRAW);
                        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 6);
                    }
                }
                float[] pixels = new float[width * height * 4];
                GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_FLOAT, pixels);
                int rows = 0;
                for (int y = 0; y < height; y++) {
                    int first = -1, last = -1;
                    for (int x = 0; x < width; x++) if (pixels[(y * width + x) * 4] < .1) { if (first < 0) first = x; last = x; }
                    if (last - first <= width / 2) continue;
                    rows++;
                    for (int x = first; x <= last; x++) if (pixels[(y * width + x) * 4] > .8) cracks[pass]++;
                }
                assertTrue(rows > 20, "the ocean must fill a good part of the view");
            }
            System.out.println("HORIZON_OCEAN cracked pixels: per-column tangents " + cracks[0] + ", water shader " + cracks[1]);
            assertTrue(cracks[0] > 10, "the per-column bend must show its cracks, or this test sees nothing");
            assertEquals(0, cracks[1]);
            assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        } finally {
            GlStateManager._glUseProgram(0);
            GL30C.glBindVertexArray(0);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL15C.glDeleteBuffers(buffer);
            GL30C.glDeleteVertexArrays(vao);
            GL20C.glDeleteProgram(program);
            GL11C.glDeleteTextures(colour);
            GL30C.glDeleteFramebuffers(target);
        }
    }

    /** A view bending a column on the curve's tangent at (x, z): right at its middle, apart from its neighbours at its edges. */
    private static Matrix4f tangent(Matrix4f view, double x, double z, float curvature) {
        double r = Math.hypot(x, z), outside = Math.max(0, r - EarthView.FLAT);
        double gradient = r > 0 ? 2 * curvature * outside / r : 0;
        float sx = (float) (gradient * x), sz = (float) (gradient * z);
        return new Matrix4f(view).mul(new Matrix4f().m01(-sx).m21(-sz).m31((float) (sx * x + sz * z - EarthView.drop(x, z, curvature))));
    }

    /**
     * Far terrain's own depth: the colour drawn stays the game's, the nearer depth is merged in, the
     * game's blocks keep theirs, the draw state comes back, and all of it again after a resize.
     */
    @Test void ownDepthMergesKeepsTheColourAndRestoresTheDrawAcrossResizes() {
        int colour = GL11C.glGenTextures(), depth = GL11C.glGenTextures(), target = GL30C.glGenFramebuffers();
        FarPlainDepth own = new FarPlainDepth();
        Matrix4f game = new Matrix4f().perspective(1, 1, 0.05f, FarRenderer.depthFar());
        try {
            for (int size : new int[] {4, 8}) {
                GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
                texture(colour, GL11C.GL_RGBA8, GL11C.GL_RGBA, size, size);
                texture(depth, GL30C.GL_DEPTH_COMPONENT24, GL11C.GL_DEPTH_COMPONENT, size, size);
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
                GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
                GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
                assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));
                GL11C.glViewport(0, 0, size, size);
                GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, new float[] {.2f, .4f, .6f, 1});
                GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {1});
                // The game's blocks on the left half.
                GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
                GL11C.glScissor(0, 0, size / 2, size);
                GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {.25f});
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                assertTrue(own.begin(game, size, size));
                assertNotEquals(target, GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING));
                // Far terrain 10 km off, everywhere, in its own depth.
                Vector4f clip = own.projection().transform(new Vector4f(0, 0, -10000, 1));
                GL11C.glDepthMask(true);
                GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {clip.z / clip.w * .5f + .5f});
                GL11C.glDepthFunc(GL11C.GL_LESS);
                GL11C.glDepthMask(false);
                GL11C.glEnable(GL11C.GL_CULL_FACE);
                own.end();
                assertEquals(target, GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING));
                assertEquals(GL11C.GL_LESS, GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC));
                assertFalse(GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK));
                assertTrue(GL11C.glIsEnabled(GL11C.GL_CULL_FACE));
                float[] pixel = new float[4];
                GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, pixel);
                assertArrayEquals(new float[] {.2f, .4f, .6f, 1}, pixel, .005f, "the game's colour is untouched");
                float[] stored = new float[1];
                GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, stored);
                assertEquals(.25f, stored[0], 2e-7, "the game's blocks keep their depth");
                GL11C.glReadPixels(size - 1, 0, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, stored);
                Vector4f expected = game.transform(new Vector4f(0, 0, -10000, 1));
                assertEquals(expected.z / expected.w * .5f + .5f, stored[0], 2e-7, "far terrain's depth merged in the game's projection");
                assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
                GL11C.glDepthMask(true);
                GL11C.glDisable(GL11C.GL_CULL_FACE);
            }
        } finally {
            own.close();
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL30C.glDeleteFramebuffers(target);
            GL11C.glDeleteTextures(colour);
            GL11C.glDeleteTextures(depth);
        }
    }

    /**
     * A stand-in 1.6 blocks from the camera, drawn in the game's projection before far terrain's own
     * depth begins, as FarRenderer draws the columns inside the reach: it is kept in the game's
     * depth through the copy and the merge, and covers the far terrain behind it.
     */
    @Test void standInsDrawnFirstInTheGamesDepthSurviveTheMerge() {
        int size = 8;
        int colour = GL11C.glGenTextures(), depth = GL11C.glGenTextures(), target = GL30C.glGenFramebuffers(), vao = GL30C.glGenVertexArrays();
        FarPlainDepth own = new FarPlainDepth();
        Matrix4f game = new Matrix4f().perspective(1.2f, 1, 0.05f, FarRenderer.depthFar());
        Vector4f near = game.transform(new Vector4f(0, 0, -1.6f, 1));
        int program = GlShaders.link("#version 330 core\nuniform float depth; void main() { vec2 c = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));"
                + " gl_Position = vec4(c * 2.0 - 1.0, depth, 1.0); }", "#version 330 core\nout vec4 colour; void main() { colour = vec4(1.0); }", "stand-in");
        try {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
            texture(colour, GL11C.GL_RGBA8, GL11C.GL_RGBA, size, size);
            texture(depth, GL30C.GL_DEPTH_COMPONENT24, GL11C.GL_DEPTH_COMPONENT, size, size);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            GL11C.glViewport(0, 0, size, size);
            GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {1});
            // The stand-in, in the game's projection.
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_LESS);
            GL11C.glDepthMask(true);
            GL20C.glUseProgram(program);
            GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "depth"), near.z / near.w);
            GL30C.glBindVertexArray(vao);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            GL20C.glUseProgram(0);
            assertTrue(own.begin(game, size, size));
            Vector4f clip = own.projection().transform(new Vector4f(0, 0, -10000, 1));
            GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {clip.z / clip.w * .5f + .5f});
            own.end();
            float[] stored = new float[1];
            GL11C.glReadPixels(size / 2, size / 2, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, stored);
            assertEquals(near.z / near.w * .5f + .5f, stored[0], 1e-6, "the stand-in keeps its depth in front of far terrain");
            assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        } finally {
            own.close();
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL30C.glBindVertexArray(0);
            GL30C.glDeleteVertexArrays(vao);
            GL20C.glDeleteProgram(program);
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL30C.glDeleteFramebuffers(target);
            GL11C.glDeleteTextures(colour);
            GL11C.glDeleteTextures(depth);
        }
    }

    /** What far terrain's own depth costs a frame at 1080p beyond far terrain's draw: the copy, the clear and the merge. */
    @Test void ownDepthsPassesAtFullHd() {
        int width = 1920, height = 1080;
        int colour = GL11C.glGenTextures(), depth = GL11C.glGenTextures(), target = GL30C.glGenFramebuffers(), query = GL15C.glGenQueries();
        FarPlainDepth own = new FarPlainDepth();
        Matrix4f game = new Matrix4f().perspective(1.2f, 16 / 9f, 0.05f, FarRenderer.depthFar());
        try {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
            texture(colour, GL11C.GL_RGBA8, GL11C.GL_RGBA, width, height);
            texture(depth, GL30C.GL_DEPTH_COMPONENT24, GL11C.GL_DEPTH_COMPONENT, width, height);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            GL11C.glViewport(0, 0, width, height);
            long best = Long.MAX_VALUE;
            for (int frame = 0; frame < 30; frame++) {
                GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, query);
                assertTrue(own.begin(game, width, height));
                own.end();
                GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                long nanos = GL33C.glGetQueryObjecti64(query, GL15C.GL_QUERY_RESULT);
                if (frame >= 5) best = Math.min(best, nanos);
            }
            System.out.println("HORIZON_OWN_DEPTH_COST 1920x1080 best of 25 frames: " + best / 1e6 + " ms");
            // In its own depth every far fragment may be dropped by hand, which can keep a GPU from
            // testing depth before shading: eight layers nearest first, shaded as heavily, with and without.
            String vertex = "#version 330 core\nuniform float depth; void main() { vec2 c = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));"
                    + " gl_Position = vec4(c * 2.0 - 1.0, depth, 1.0); }";
            String shading = "uniform sampler2D blocks; out vec4 colour; void main() {%s vec3 c = gl_FragCoord.xyz;"
                    + " for (int i = 0; i < 64; i++) c = fract(sin(c * 12.9898 + float(i)) * 43758.5453); colour = vec4(c, 1.0); }";
            int plain = GlShaders.link(vertex, "#version 330 core\n" + shading.formatted(""), "plain overdraw");
            int dropping = GlShaders.link(vertex, "#version 330 core\n" + shading.formatted(
                    " if (texelFetch(blocks, ivec2(gl_FragCoord.xy), 0).r < gl_FragCoord.z - 2.0) discard;"), "dropping overdraw");
            int vao = GL30C.glGenVertexArrays(), blocks = GL11C.glGenTextures();
            try {
                GL30C.glBindVertexArray(vao);
                texture(blocks, GL30C.GL_R32F, GL11C.GL_RED, width, height);
                GL11C.glEnable(GL11C.GL_DEPTH_TEST);
                GL11C.glDepthFunc(GL11C.GL_LESS);
                GL11C.glDepthMask(true);
                // Plain nearest first, plain farthest first (every layer shaded), and dropping by hand nearest first.
                double[] took = new double[3];
                for (int pass = 0; pass < 3; pass++) {
                    int program = pass < 2 ? plain : dropping;
                    GL20C.glUseProgram(program);
                    long most = Long.MAX_VALUE;
                    for (int frame = 0; frame < 20; frame++) {
                        GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {1});
                        GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, query);
                        for (int layer = 0; layer < 8; layer++) {
                            GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "depth"), (pass == 1 ? 7 - layer : layer) * 0.2f - 0.9f);
                            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
                        }
                        GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
                        long nanos = GL33C.glGetQueryObjecti64(query, GL15C.GL_QUERY_RESULT);
                        if (frame >= 5) most = Math.min(most, nanos);
                    }
                    took[pass] = most / 1e6;
                }
                System.out.println("HORIZON_DISCARD_COST 1920x1080, 8 layers: plain nearest first " + took[0] + " ms, plain farthest first "
                        + took[1] + " ms, dropping by hand nearest first " + took[2] + " ms");
            } finally {
                GL20C.glUseProgram(0);
                GL30C.glBindVertexArray(0);
                GL30C.glDeleteVertexArrays(vao);
                GL11C.glDeleteTextures(blocks);
                GL20C.glDeleteProgram(plain);
                GL20C.glDeleteProgram(dropping);
                GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            }
            assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        } finally {
            own.close();
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL15C.glDeleteQueries(query);
            GL30C.glDeleteFramebuffers(target);
            GL11C.glDeleteTextures(colour);
            GL11C.glDeleteTextures(depth);
        }
    }

    private static void texture(int id, int format, int external, int width, int height) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, format, width, height, 0, external, GL11C.GL_FLOAT, (FloatBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
    }
}
