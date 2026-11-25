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
import org.lwjgl.opengl.GL13C;
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

    /** A quad of four corners in the projection's own space (so far terrain's near plane clips it as in the game), drawn by corner index. */
    private static final String QUAD = "#version 330 core\nuniform mat4 earth_Matrix; uniform vec3 earth_Corners[4];\n"
            + "void main() { const int order[6] = int[6](0, 1, 2, 0, 2, 3); gl_Position = earth_Matrix * vec4(earth_Corners[order[gl_VertexID]], 1.0); }";
    /** Far terrain's fragment, as FarShaders has it: the production decision against the game's blocks, then a colour. */
    private static final String FAR = "#version 330 core\n" + FarShaders.BEHIND_BLOCKS
            + "uniform vec4 earth_Colour; out vec4 earth_fragColor; void main() { earth_behindBlocks(); earth_fragColor = earth_Colour; }";
    private static final String BLOCK = "#version 330 core\nuniform vec4 earth_Colour; out vec4 earth_fragColor; void main() { earth_fragColor = earth_Colour; }";

    private static final float[] RED = {1, 0, 0, 1}, GREEN = {0, 1, 0, 1}, GREY = {.2f, .4f, .6f, 1};

    /** A whole-screen quad (or the left half of it) a distance out along the view's axis, wide enough to cover the view at that depth. */
    private static float[] plane(double depth, boolean leftHalf, double halfWidth, double halfHeight) {
        float z = (float) -depth, x = (float) halfWidth, y = (float) halfHeight;
        return new float[] {-x, -y, z, leftHalf ? 0 : x, -y, z, leftHalf ? 0 : x, y, z, -x, y, z};
    }

    private static void drawQuad(int program, Matrix4f matrix, float[] corners, float[] colour, int vao) {
        GL20C.glUseProgram(program);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "earth_Matrix"), false, matrix.get(new float[16]));
        GL20C.glUniform3fv(GL20C.glGetUniformLocation(program, "earth_Corners"), corners);
        GL20C.glUniform4fv(GL20C.glGetUniformLocation(program, "earth_Colour"), colour);
        GL30C.glBindVertexArray(vao);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 6);
    }

    /** A framebuffer of this size with a colour and a 24-bit depth texture, as the game's main target is. */
    private record Target(int framebuffer, int colour, int depth) {
        static Target make(int width, int height) {
            Target target = new Target(GL30C.glGenFramebuffers(), GL11C.glGenTextures(), GL11C.glGenTextures());
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target.framebuffer);
            texture(target.colour, GL11C.GL_RGBA8, GL11C.GL_RGBA, width, height);
            texture(target.depth, GL30C.GL_DEPTH_COMPONENT24, GL11C.GL_DEPTH_COMPONENT, width, height);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, target.colour, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, target.depth, 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));
            GL11C.glViewport(0, 0, width, height);
            return target;
        }

        void clear() {
            GL11C.glDepthMask(true);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, GREY);
            GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {1});
        }

        void delete() {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL30C.glDeleteFramebuffers(framebuffer);
            GL11C.glDeleteTextures(colour);
            GL11C.glDeleteTextures(depth);
        }
    }

    private static float[] pixel(int x, int y) {
        float[] colour = new float[4];
        GL11C.glReadPixels(x, y, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, colour);
        return colour;
    }

    private static long storedDepth(int x, int y) {
        float[] depth = new float[1];
        GL11C.glReadPixels(x, y, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, depth);
        return Math.round(depth[0] * ((1 << 24) - 1));
    }

    private static boolean is(float[] pixel, float[] colour) {
        for (int i = 0; i < 3; i++) if (Math.abs(pixel[i] - colour[i]) > .02f) return false;
        return true;
    }

    /** Binds what far terrain's own fragments test themselves against, as FarGpu does for its draw. */
    private static void bindBlocksFor(int program, FarPlainDepth own) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, FarBlockDepth.texture());
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GL20C.glUseProgram(program);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "earth_Blocks"), 1);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "earth_GameProjection"), false, own.gameProjection().get(new float[16]));
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "earth_FarInverse"), false, own.inverse().get(new float[16]));
        GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "earth_GameSteps"), FarBlockDepth.steps());
    }

    /**
     * Far terrain's own depth, through its production entry: the colour drawn stays the game's, the nearer depth is merged
     * in the game's projection, the game's blocks keep theirs, the draw state comes back, and all of it again after a resize.
     * Far terrain is drawn for real: a quad 10 km out through far terrain's own projection and the production fragment test.
     */
    @Test void ownDepthMergesKeepsTheGamesColourAndRestoresTheDrawAcrossResizes() {
        FarPlainDepth own = new FarPlainDepth();
        Matrix4f game = new Matrix4f().perspective(1, 1, 0.05f, FarRenderer.depthFar());
        int vao = GL30C.glGenVertexArrays(), quad = GlShaders.link(QUAD, "#version 330 core\nuniform vec4 earth_Colour; out vec4 c; void main() { c = earth_Colour; }", "block quad");
        int far = GlShaders.link(QUAD, FAR, "far quad");
        try {
            for (int size : new int[] {4, 8}) {
                Target target = Target.make(size, size);
                try {
                    target.clear();
                    GL11C.glEnable(GL11C.GL_DEPTH_TEST);
                    GL11C.glDepthFunc(GL11C.GL_LESS);
                    // The game's blocks on the left half, 40 blocks out, drawn in the game's own projection.
                    drawQuad(quad, game, plane(40, true, 100, 100), RED, vao);
                    long blocks = storedDepth(0, 0);
                    assertTrue(blocks < (1 << 24) - 1);
                    assertTrue(own.begin(game, 192), "the production entry takes the target at its own size");
                    assertNotEquals(target.framebuffer, GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING));
                    // Far terrain 10 km off, everywhere, in its own depth.
                    bindBlocksFor(far, own);
                    GL11C.glDepthMask(true);
                    drawQuad(far, own.projection(), plane(10_000, false, 20_000, 20_000), GREEN, vao);
                    GL11C.glDepthFunc(GL11C.GL_LESS);
                    GL11C.glDepthMask(false);
                    GL11C.glEnable(GL11C.GL_CULL_FACE);
                    own.end();
                    assertEquals(target.framebuffer, GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING));
                    assertEquals(GL11C.GL_LESS, GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC));
                    assertFalse(GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK));
                    assertTrue(GL11C.glIsEnabled(GL11C.GL_CULL_FACE));
                    assertTrue(is(pixel(0, 0), RED), "the game's blocks keep their colour: far terrain 10 km out is behind them");
                    assertEquals(blocks, storedDepth(0, 0), "and their depth");
                    assertTrue(is(pixel(size - 1, 0), GREEN), "far terrain draws where the game's blocks are not");
                    Vector4f expected = game.transform(new Vector4f(0, 0, -10000, 1));
                    assertEquals(Math.round((expected.z / expected.w * .5f + .5f) * ((1 << 24) - 1)), storedDepth(size - 1, 0), 2, "far terrain's depth merged in the game's projection");
                    assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
                } finally {
                    GL11C.glDepthMask(true);
                    GL11C.glDisable(GL11C.GL_CULL_FACE);
                    GL11C.glDisable(GL11C.GL_DEPTH_TEST);
                    target.delete();
                }
            }
        } finally {
            own.close();
            GL20C.glUseProgram(0);
            GL30C.glBindVertexArray(0);
            GL30C.glDeleteVertexArrays(vao);
            GL20C.glDeleteProgram(quad);
            GL20C.glDeleteProgram(far);
        }
    }

    /**
     * A stand-in 1.6 blocks from the camera, drawn in the game's projection before far terrain's own depth begins, as
     * FarRenderer draws the columns inside the reach: it keeps both its colour and its depth through the copy, far terrain's
     * draw and the merge, and the far terrain behind it, drawn for real, does not overwrite its colour.
     */
    @Test void standInsDrawnFirstInTheGamesDepthKeepTheirColourAndDepthThroughFarTerrainsDraw() {
        int size = 8;
        FarPlainDepth own = new FarPlainDepth();
        Matrix4f game = new Matrix4f().perspective(1.2f, 1, 0.05f, FarRenderer.depthFar());
        int vao = GL30C.glGenVertexArrays(), quad = GlShaders.link(QUAD, BLOCK, "stand-in"), far = GlShaders.link(QUAD, FAR, "far quad");
        Target target = Target.make(size, size);
        try {
            target.clear();
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_LESS);
            GL11C.glDepthMask(true);
            drawQuad(quad, game, plane(1.6, true, 10, 10), RED, vao);
            long standIn = storedDepth(0, 0);
            assertTrue(own.begin(game, 192));
            bindBlocksFor(far, own);
            // Far terrain right behind it and far behind it, nearest first: neither may show over the stand-in.
            for (double depth : new double[] {1.7, 5, 300, 10_000}) drawQuad(far, own.projection(), plane(depth, false, 10_000, 10_000), GREEN, vao);
            own.end();
            assertTrue(is(pixel(0, 0), RED), "the stand-in's colour");
            assertEquals(standIn, storedDepth(0, 0), "the stand-in's depth");
            assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        } finally {
            own.close();
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL20C.glUseProgram(0);
            GL30C.glBindVertexArray(0);
            GL30C.glDeleteVertexArrays(vao);
            GL20C.glDeleteProgram(quad);
            GL20C.glDeleteProgram(far);
            target.delete();
        }
    }

    /**
     * Far terrain against the game's blocks at the seam: far terrain a few hundred blocks out, swept from clearly in front of
     * a block to clearly behind it through the depth step. Wherever a fragment's colour replaces the block's, the merged depth
     * is the fragment's, and wherever the block's colour stays, so does its depth; never the one without the other.
     */
    @Test void farTerrainsColourAndTheMergedDepthAgreeAtTheSeamWithTheGamesBlocks() {
        int size = 4;
        FarPlainDepth own = new FarPlainDepth();
        Matrix4f game = new Matrix4f().perspective(1.2f, 1, 0.05f, FarRenderer.depthFar());
        int vao = GL30C.glGenVertexArrays(), quad = GlShaders.link(QUAD, BLOCK, "block"), far = GlShaders.link(QUAD, FAR, "far quad");
        Target target = Target.make(size, size);
        int wins = 0, loses = 0;
        try {
            // The game's step at 300 blocks is about 0.1 block, so a sweep of 0.8 either side crosses a few of them.
            for (double blockDepth : new double[] {300, 300.037, 1200, 40}) for (int i = -40; i <= 40; i++) {
                double offset = i * (blockDepth * blockDepth / 0.05 / (1 << 24)) / 10;
                target.clear();
                GL11C.glEnable(GL11C.GL_DEPTH_TEST);
                GL11C.glDepthFunc(GL11C.GL_LESS);
                GL11C.glDepthMask(true);
                drawQuad(quad, game, plane(blockDepth, true, 2000, 2000), RED, vao);
                long blocks = storedDepth(0, 0);
                assertTrue(own.begin(game, 192));
                bindBlocksFor(far, own);
                drawQuad(far, own.projection(), plane(blockDepth + offset, true, 2000, 2000), GREEN, vao);
                own.end();
                boolean farColour = is(pixel(0, 0), GREEN);
                assertTrue(farColour || is(pixel(0, 0), RED), "a colour of the two");
                long merged = storedDepth(0, 0);
                if (farColour) {
                    wins++;
                    assertTrue(merged < blocks, "far terrain's colour is only drawn where the merged depth is nearer than the block's: offset " + offset + " at " + blockDepth
                            + ", block " + blocks + ", merged " + merged);
                } else {
                    loses++;
                    assertEquals(blocks, merged, "the block keeps its colour and its depth: offset " + offset + " at " + blockDepth);
                }
                assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
            }
            assertTrue(wins > 30 && loses > 30, "the sweep crosses the seam: far wins " + wins + ", loses " + loses);
        } finally {
            own.close();
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL20C.glUseProgram(0);
            GL30C.glBindVertexArray(0);
            GL30C.glDeleteVertexArrays(vao);
            GL20C.glDeleteProgram(quad);
            GL20C.glDeleteProgram(far);
            target.delete();
        }
    }

    /**
     * The production near plane keeps far terrain on screen in the corners of a very wide view: a plane 40 blocks deep, whose
     * edges are 200 blocks off sideways at 110 degrees on 32:9, is drawn through far terrain's own projection and fills the
     * screen. The first near plane (0.3 of the reach, 57.6) clipped its edges away.
     */
    @Test void aWideViewsCornersKeepTheFarTerrainThatIsNearInDepth() {
        int width = 64, height = 18;
        FarPlainDepth own = new FarPlainDepth();
        Matrix4f game = new Matrix4f().perspective((float) Math.toRadians(110), width / (float) height, 0.05f, FarRenderer.depthFar());
        int vao = GL30C.glGenVertexArrays(), far = GlShaders.link(QUAD, FAR, "far quad");
        Target target = Target.make(width, height);
        try {
            target.clear();
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_LESS);
            GL11C.glDepthMask(true);
            assertTrue(own.begin(game, 192));
            assertTrue(own.projection().m32() / (own.projection().m22() - 1) < 40, "the plane is nearer than the plane's own depth: " + own.projection().m32() / (own.projection().m22() - 1));
            bindBlocksFor(far, own);
            drawQuad(far, own.projection(), plane(40, false, 40 * 5.5, 40 * 1.5), GREEN, vao);
            own.end();
            for (int[] at : new int[][] {{0, 0}, {width - 1, 0}, {0, height - 1}, {width - 1, height - 1}, {width / 2, height / 2}})
                assertTrue(is(pixel(at[0], at[1]), GREEN), "far terrain at " + at[0] + ", " + at[1]);
        } finally {
            own.close();
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL20C.glUseProgram(0);
            GL30C.glBindVertexArray(0);
            GL30C.glDeleteVertexArrays(vao);
            GL20C.glDeleteProgram(far);
            target.delete();
        }
    }

    /** A framebuffer far terrain cannot copy and draw into at its own size or sampling is left alone, with nothing changed and no error. */
    @Test void aTargetItCannotUseIsLeftAloneWithoutStaleDepth() {
        FarPlainDepth own = new FarPlainDepth();
        Matrix4f game = new Matrix4f().perspective(1.2f, 1, 0.05f, FarRenderer.depthFar());
        int colour = GL30C.glGenRenderbuffers(), depth = GL30C.glGenRenderbuffers(), target = GL30C.glGenFramebuffers();
        int colourTexture = GL11C.glGenTextures(), depthTexture = GL11C.glGenTextures();
        try {
            // A multisampled world target, as some mods draw the world into.
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, colour);
            GL30C.glRenderbufferStorageMultisample(GL30C.GL_RENDERBUFFER, 4, GL11C.GL_RGBA8, 16, 16);
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, depth);
            GL30C.glRenderbufferStorageMultisample(GL30C.GL_RENDERBUFFER, 4, GL30C.GL_DEPTH_COMPONENT32F, 16, 16);
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
            GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, colour);
            GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_RENDERBUFFER, depth);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));
            assertFalse(own.begin(game, 192), "a multisampled target stays in the game's depth");
            assertEquals(target, GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING));
            assertFalse(FarBlockDepth.copy(), "and its depth is not copied");
            // A colour and a depth that are not the same size.
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
            texture(colourTexture, GL11C.GL_RGBA8, GL11C.GL_RGBA, 16, 16);
            texture(depthTexture, GL30C.GL_DEPTH_COMPONENT24, GL11C.GL_DEPTH_COMPONENT, 8, 8);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colourTexture, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depthTexture, 0);
            assertFalse(own.begin(game, 192), "mismatched attachments stay in the game's depth");
            assertFalse(FarBlockDepth.copy());
            // The same target made whole is taken, at its own 16 by 16 size, whatever the game's main target is.
            texture(depthTexture, GL30C.GL_DEPTH_COMPONENT24, GL11C.GL_DEPTH_COMPONENT, 16, 16);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depthTexture, 0);
            assertTrue(own.begin(game, 192));
            own.end();
            assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        } finally {
            own.close();
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL30C.glDeleteFramebuffers(target);
            GL30C.glDeleteRenderbuffers(colour);
            GL30C.glDeleteRenderbuffers(depth);
            GL11C.glDeleteTextures(colourTexture);
            GL11C.glDeleteTextures(depthTexture);
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
                assertTrue(own.begin(game, 192));
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
