package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.FloatBuffer;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

/**
 * Far terrain's own depth buffer under a shader pack, offered to the pack the way Voxy offers its
 * level-of-detail terrain: the pack is compiled with {@code VOXY} defined, reads the depth as
 * {@code vxDepthTexOpaque} and {@code vxDepthTexTrans} with the projection {@code vxProj}, and
 * leaves pixels its own terrain covers to that terrain. Far terrain then keeps its depth in a
 * 32-bit float buffer with a near plane of {@link #NEAR} blocks rather than in the game's 24-bit
 * one whose near plane is 1/20 block, which at a few kilometres can only tell depths about ten
 * blocks apart; screen-space effects such as Photon's shadows marched that coarse depth and drew
 * contour bands across far hillsides.
 *
 * <p>Far water still goes through the pack's own water shader and the game's depth buffer. So
 * that far hills hide the water behind them, {@link #beginWater} gives that draw a scratch depth
 * buffer holding the nearer of the game's depth and the far terrain's (in the game's projection),
 * and {@link #endWater} copies into the game's depth only the pixels the water drew. Packs judge
 * how deep water is from far terrain's depth, so {@link #beginDeepen} first gives far water a
 * floor at least {@link #WATER_DEPTH} blocks below its surface there.
 *
 * <p>Everything here runs on the render thread; Iris reads it through {@link FarLodIris}.
 */
public final class FarLod {
    /** Off with {@code -Dearthplatform.far.lodDepth=false}: far terrain then shares the game's depth buffer. */
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("earthplatform.far.lodDepth", "true"));
    /** Far terrain's near plane, in blocks; closer far voxels are only ever ones whose cubes are still loading. */
    static final float NEAR = 8.0F;
    private static final int VANILLA_UNIT = 6, SCRATCH_UNIT = 7;

    /** Whether the pack in use was compiled with {@code VOXY} defined by us. */
    private static volatile boolean defined;

    private static int width, height;
    private static int depth, framebuffer;
    private static int faces, scratch, before, waterFramebuffer, restoreFramebuffer, vao;
    @Nullable private static Program merge, restore, smooth, deepen;
    private static boolean failed;
    private static int drawnFrom, gameDepth, waterFrom, waterDepth, previousActive;
    private static final State saved = new State();
    /** The least depth, in blocks along the view, packs are given for far water: enough to read as water from afar. */
    private static final float WATER_DEPTH = 24.0F;
    private static final int[] previous2d = new int[8];

    private static final Matrix4f projection = new Matrix4f(), inverse = new Matrix4f(), previous = new Matrix4f(), vanilla = new Matrix4f();
    private static boolean projected, started;

    private FarLod() {}

    // The pack's side ---------------------------------------------------------------------------

    /**
     * Whether a pack about to be compiled should get {@code VOXY}: not when Distant Horizons is
     * installed, as packs give it its own path and refuse both at once. Remembers the answer.
     */
    public static boolean defineVoxy() {
        // Iris loads its pack before the mod list is up, so ask the loader.
        defined = ENABLED && LoadingModList.get().getModFileById("distanthorizons") == null;
        return defined;
    }

    static boolean voxyDefined() { return defined; }

    /** True when far terrain draws into its own depth: a pack with Voxy support, compiled with {@code VOXY}. */
    static boolean active() {
        return defined && ShaderBlocks.packInUse() && ShaderBlocks.voxyPatch() != null;
    }

    /** True when the pack fades and fogs far terrain by {@link #renderDistance} rather than the {@code far} uniform. */
    public static boolean replacesFar() { return active(); }

    /** The depth texture, created on first use; all far (1.0) until far terrain draws into it. */
    static int depthTexture() {
        if (depth == 0) resize(1, 1);
        return depth;
    }

    static Matrix4fc projection() { return projection; }
    static Matrix4fc projectionInverse() { return inverse; }
    static Matrix4fc previousProjection() { return previous; }

    /** Far terrain's reach in chunks, which packs fade it out by. */
    static int renderDistance() {
        if (ClientCubeWorld.current() == null) return Minecraft.getInstance().options.getEffectiveRenderDistance();
        return (int) Math.ceil(0.8F * FarRenderer.FOG_END / 16.0F);
    }

    // Each frame ---------------------------------------------------------------------------------

    /** Clears far terrain's depth at the start of a frame and keeps it the size of the game's targets. */
    public static void beginFrame() {
        if (!defined || depth == 0) return;
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (target.width != width || target.height != height) resize(target.width, target.height);
        int bound = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        clear();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, bound);
        projected = false;
    }

    /** Clears far terrain's depth and face depth to far; its framebuffer must be bound. */
    private static void clear() {
        GL20C.glDrawBuffers(new int[] {GL11C.GL_NONE, GL30C.GL_COLOR_ATTACHMENT1});
        boolean[] mask = colourMask();
        GL11C.glColorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {1.0F});
        GL30C.glClearBufferfv(GL11C.GL_COLOR, 1, new float[] {1.0F, 1.0F, 1.0F, 1.0F});
        GL11C.glColorMask(mask[0], mask[1], mask[2], mask[3]);
    }

    private static boolean[] colourMask() {
        boolean[] colourMask = new boolean[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) colourMask[i] = mask.get(i) != 0;
        }
        return colourMask;
    }

    /** Projects from the game's projection if far terrain hasn't this frame, so the pack always gets a current one. */
    static void projectIfNeeded(Matrix4fc game) {
        if (!projected) project(new Matrix4f(game));
    }

    /** Far terrain's projection for this frame: the game's, with far terrain's own near and far planes. */
    static Matrix4f project(Matrix4f game) {
        if (!projected) {
            previous.set(projection);
            projected = true;
        }
        vanilla.set(game);
        float near = NEAR, far = FarRenderer.DEPTH_FAR;
        projection.set(game).m22((far + near) / (near - far)).m32(2.0F * far * near / (near - far));
        projection.invert(inverse);
        // A zero "previous" projection would reproject the pack's history to NaN, which never clears.
        if (!started) {
            previous.set(projection);
            started = true;
        }
        return new Matrix4f(projection);
    }

    /**
     * Points far terrain's draw at the pack's first terrain target and at far terrain's own depth,
     * after the pack's terrain program has bound its buffers. The game's depth is bound on a unit
     * the far shader reads, so far terrain stays behind whatever the game drew.
     */
    static void beginDraw() {
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (depth == 0 || target.width != width || target.height != height) resize(target.width, target.height);
        drawnFrom = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int colour = attached(GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0));
        gameDepth = attached(GL30C.GL_DEPTH_ATTACHMENT);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
        GL20C.glDrawBuffers(new int[] {GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
        previousActive = GlStateManager._getActiveTexture();
        bind(VANILLA_UNIT, gameDepth);
        GlStateManager._activeTexture(previousActive);
    }

    static int vanillaUnit() { return VANILLA_UNIT; }

    static void endDraw() {
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        unbind(VANILLA_UNIT);
        smooth();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawnFrom);
        GlStateManager._activeTexture(previousActive);
    }

    /**
     * Blurs far terrain's depth a few pixels each way, across voxel steps but not across real
     * edges (where depth jumps by more than a few percent), so a pack's screen-space shadows
     * march over a hillside rather than a staircase whose every step shades the next.
     */
    private static void smooth() {
        if (!programs()) return;
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, restoreFramebuffer);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_ALWAYS);
        GL11C.glDepthMask(true);
        GL20C.glUseProgram(smooth.id);
        GL20C.glUniform2f(GL20C.glGetUniformLocation(smooth.id, "earth_NearFar"), NEAR, FarRenderer.DEPTH_FAR);
        int step = GL20C.glGetUniformLocation(smooth.id, "earth_Step");
        for (int pass = 0; pass < 2; pass++) {
            int from = pass == 0 ? depth : scratch, to = pass == 0 ? scratch : depth;
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, to, 0);
            bind(VANILLA_UNIT, from);
            GL20C.glUniform2i(step, pass == 0 ? 1 : 0, pass == 0 ? 0 : 1);
            fullScreen();
            unbind(VANILLA_UNIT);
        }
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, 0, 0);
        GL20C.glUseProgram(program);
        if (depthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(depthFunc);
        GL11C.glDepthMask(depthMask);
    }

    /**
     * After far terrain and before the pack lights it, sets up drawing far water's surfaces into
     * far terrain's depth as a floor at least {@link #WATER_DEPTH} blocks below them along the
     * view. Far river beds lie about a block under the water, and a pack measures water's depth
     * from this buffer: at the game's coarse depth far rivers read deep by chance, and at this
     * fine one they turned clear and showed only their sand. The caller draws the water, setting
     * {@link #deepenOffset} per column, then calls {@link #endDeepen}.
     */
    static boolean beginDeepen(Matrix4fc modelView) {
        if (!programs()) return false;
        saved.save();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, restoreFramebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_ALWAYS);
        GL11C.glDepthMask(true);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        bind(VANILLA_UNIT, gameDepth);
        bind(SCRATCH_UNIT, faces);
        GL20C.glUseProgram(deepen.id);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(deepen.id, "earth_ModelView"), false, modelView.get(matrix));
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(deepen.id, "earth_Projection"), false, projection.get(matrix));
        }
        GL20C.glUniform1f(GL20C.glGetUniformLocation(deepen.id, "earth_Least"), WATER_DEPTH);
        GL20C.glUniform2f(GL20C.glGetUniformLocation(deepen.id, "earth_NearFar"), NEAR, FarRenderer.DEPTH_FAR);
        return true;
    }

    /** Where the next column's water sits relative to the camera. */
    static void deepenOffset(float x, float y, float z) {
        GL20C.glUniform3f(GL20C.glGetUniformLocation(deepen.id, "earth_Offset"), x, y, z);
    }

    static void endDeepen() {
        unbind(SCRATCH_UNIT);
        unbind(VANILLA_UNIT);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, 0, 0);
        saved.restore();
    }

    /**
     * Before far water: after the pack's water program has bound its buffers, swaps their depth for
     * a scratch buffer holding the nearer of the game's depth and far terrain's. Returns false (and
     * changes nothing) when that can't be set up, and the water then draws as before.
     */
    static boolean beginWater() {
        if (!programs()) return false;
        waterFrom = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        waterDepth = attached(GL30C.GL_DEPTH_ATTACHMENT);
        if (waterFrom == 0 || waterDepth == 0) return false;
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        previousActive = GlStateManager._getActiveTexture();
        boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);

        // Scratch depth = min(game depth, far terrain's depth seen through the game's projection).
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, waterFramebuffer);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_ALWAYS);
        GL11C.glDepthMask(true);
        bind(VANILLA_UNIT, waterDepth);
        bind(SCRATCH_UNIT, faces);
        GL20C.glUseProgram(merge.id);
        merge.matrices();
        fullScreen();

        // The pack's water then tests and writes that instead of the game's depth.
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, waterFrom);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, scratch, 0);
        GL20C.glUseProgram(program);
        unbind(SCRATCH_UNIT);
        unbind(VANILLA_UNIT);
        GlStateManager._activeTexture(previousActive);
        if (depthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(depthFunc);
        GL11C.glDepthMask(depthMask);
        return true;
    }

    /** After far water: gives the pack its depth buffer back, with the water's depth where it drew. */
    static void endWater() {
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, waterFrom);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, waterDepth, 0);
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        previousActive = GlStateManager._getActiveTexture();
        boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        int depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        boolean[] colourMask = colourMask();

        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, restoreFramebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, waterDepth, 0);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_ALWAYS);
        GL11C.glDepthMask(true);
        GL11C.glColorMask(false, false, false, false);
        bind(VANILLA_UNIT, before);
        bind(SCRATCH_UNIT, scratch);
        GL20C.glUseProgram(restore.id);
        fullScreen();
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, 0, 0);

        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, waterFrom);
        GL20C.glUseProgram(program);
        unbind(SCRATCH_UNIT);
        unbind(VANILLA_UNIT);
        GlStateManager._activeTexture(previousActive);
        if (depthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(depthFunc);
        GL11C.glDepthMask(depthMask);
        GL11C.glColorMask(colourMask[0], colourMask[1], colourMask[2], colourMask[3]);
    }

    // GL -----------------------------------------------------------------------------------------

    private static int attached(int attachment) {
        if (attachment == GL11C.GL_NONE) return 0;
        int type = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type != GL11C.GL_TEXTURE) return 0;
        return GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }

    private static void bind(int unit, int texture) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        previous2d[unit] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        if (ShaderBlocks.IRIS) FarLodIris.unbindSampler(unit);
    }

    private static void unbind(int unit) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous2d[unit]);
    }

    private static void resize(int newWidth, int newHeight) {
        RenderSystem.assertOnRenderThread();
        width = newWidth;
        height = newHeight;
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        int bound2d = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        if (depth == 0) depth = GL11C.glGenTextures();
        if (scratch == 0) scratch = GL11C.glGenTextures();
        if (before == 0) before = GL11C.glGenTextures();
        if (faces == 0) faces = GL11C.glGenTextures();
        storage(depth, GL30C.GL_DEPTH_COMPONENT32F, GL11C.GL_DEPTH_COMPONENT);
        storage(scratch, GL30C.GL_DEPTH_COMPONENT32F, GL11C.GL_DEPTH_COMPONENT);
        storage(before, GL30C.GL_R32F, GL30C.GL_RED);
        storage(faces, GL30C.GL_R32F, GL30C.GL_RED);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bound2d);
        GlStateManager._activeTexture(active);

        int bound = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0) framebuffer = GL30C.glGenFramebuffers();
        if (waterFramebuffer == 0) waterFramebuffer = GL30C.glGenFramebuffers();
        if (restoreFramebuffer == 0) restoreFramebuffer = GL30C.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1, GL11C.GL_TEXTURE_2D, faces, 0);
        clear();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, waterFramebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, before, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, scratch, 0);
        GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, restoreFramebuffer);
        GL20C.glDrawBuffers(GL11C.GL_NONE);
        int read = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, restoreFramebuffer);
        GL11C.glReadBuffer(GL11C.GL_NONE);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, bound);
    }

    private static void storage(int texture, int format, int pixelFormat) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, format, width, height, 0, pixelFormat, GL11C.GL_FLOAT, (FloatBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL13C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL13C.GL_CLAMP_TO_EDGE);
        if (pixelFormat == GL11C.GL_DEPTH_COMPONENT) GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
    }

    private static void fullScreen() {
        int boundVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        if (vao == 0) vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(vao);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        GL30C.glBindVertexArray(boundVao);
    }

    private static boolean programs() {
        if (merge != null) return true;
        if (failed) return false;
        try {
            merge = Program.link(FULL_SCREEN, MERGE, "far water depth");
            restore = Program.link(FULL_SCREEN, RESTORE, "far water depth copy");
            smooth = Program.link(FULL_SCREEN, SMOOTH, "far terrain depth smoothing");
            deepen = Program.link(DEEPEN_VERTEX, DEEPEN, "far water depth floor");
            return true;
        } catch (IllegalStateException failure) {
            failed = true;
            EarthPlatformMod.LOGGER.error("Far water can't be hidden behind far terrain under shaders; far water may show through far hills", failure);
            return false;
        }
    }

    private static final String FULL_SCREEN = """
            #version 330 core
            out vec2 earth_uv;
            void main() {
                vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                earth_uv = corner;
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /**
     * The nearer of the game's depth and far terrain's, in the game's projection; also kept as a
     * colour to compare with later. Far terrain's faces' own depth is used rather than its
     * smoothed slopes', which can lie over a narrow river.
     */
    private static final String MERGE = """
            #version 330 core
            uniform sampler2D earth_Game;
            uniform sampler2D earth_Far;
            uniform mat4 earth_FarInverse;
            uniform mat4 earth_GameProjection;
            in vec2 earth_uv;
            out float earth_before;
            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                float game = texelFetch(earth_Game, texel, 0).r;
                float far = texelFetch(earth_Far, texel, 0).r;
                float merged = game;
                if (far < 1.0) {
                    vec4 view = earth_FarInverse * vec4(vec3(earth_uv, far) * 2.0 - 1.0, 1.0);
                    vec4 clip = earth_GameProjection * vec4(view.xyz / view.w, 1.0);
                    merged = min(game, clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0));
                }
                gl_FragDepth = merged;
                earth_before = merged;
            }
            """;

    /** Where far water drew (its depth is nearer than before it drew), that depth into the game's buffer. */
    private static final String RESTORE = """
            #version 330 core
            uniform sampler2D earth_Game;
            uniform sampler2D earth_Far;
            in vec2 earth_uv;
            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                float before = texelFetch(earth_Game, texel, 0).r;
                float water = texelFetch(earth_Far, texel, 0).r;
                if (!(water < before)) discard;
                gl_FragDepth = water;
            }
            """;

    /** One direction of {@link #smooth}: a Gaussian over nine pixels of linear depth, leaving out pixels on the far side of an edge. */
    private static final String SMOOTH = """
            #version 330 core
            uniform sampler2D earth_Game;
            uniform ivec2 earth_Step;
            uniform vec2 earth_NearFar;
            in vec2 earth_uv;

            float earth_linear(float depth) {
                float n = earth_NearFar.x, f = earth_NearFar.y;
                return 2.0 * n * f / (f + n - (depth * 2.0 - 1.0) * (f - n));
            }

            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                float centre = texelFetch(earth_Game, texel, 0).r;
                if (centre >= 1.0) {
                    gl_FragDepth = centre;
                    return;
                }
                float z = earth_linear(centre), sum = z, weights = 1.0;
                ivec2 size = textureSize(earth_Game, 0);
                for (int i = 1; i <= 4; i++) {
                    float weight = exp(-float(i * i) / 8.0);
                    for (int sign = -1; sign <= 1; sign += 2) {
                        ivec2 at = clamp(texel + earth_Step * i * sign, ivec2(0), size - 1);
                        float other = texelFetch(earth_Game, at, 0).r;
                        if (other >= 1.0) continue;
                        float zo = earth_linear(other);
                        if (abs(zo - z) > 0.03 * z) continue;
                        sum += zo * weight;
                        weights += weight;
                    }
                }
                float n = earth_NearFar.x, f = earth_NearFar.y, smoothed = sum / weights;
                gl_FragDepth = clamp(((f + n - 2.0 * n * f / smoothed) / (f - n)) * 0.5 + 0.5, 0.0, 1.0);
            }
            """;

    private static final String DEEPEN_VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 Position;
            uniform mat4 earth_ModelView;
            uniform mat4 earth_Projection;
            uniform vec3 earth_Offset;
            out vec3 earth_view;
            void main() {
                vec4 view = earth_ModelView * vec4(Position + earth_Offset, 1.0);
                earth_view = view.xyz;
                gl_Position = earth_Projection * view;
            }
            """;

    /**
     * Far water's floor, where the water shows over far terrain: its bed's own depth, or the least
     * depth if that is nearer. It replaces the slope depth there, which across a narrow channel
     * can lie well off the bed.
     */
    private static final String DEEPEN = """
            #version 330 core
            uniform sampler2D earth_Game;
            uniform sampler2D earth_Far;
            uniform mat4 earth_Projection;
            uniform float earth_Least;
            uniform vec2 earth_NearFar;
            in vec3 earth_view;
            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                float bed = texelFetch(earth_Far, texel, 0).r;
                if (bed >= 1.0 || texelFetch(earth_Game, texel, 0).r < 1.0) discard;
                // Far water lies level with the top of its bed's voxel, so it may sit a little behind it.
                float n = earth_NearFar.x, f = earth_NearFar.y;
                float behind = 2.0 * n * f / (f + n - (bed * 2.0 - 1.0) * (f - n));
                if (-earth_view.z > behind + 2.0) discard;
                float distance = length(earth_view);
                vec4 clip = earth_Projection * vec4(earth_view * ((distance + earth_Least) / distance), 1.0);
                gl_FragDepth = max(clamp(clip.z / clip.w * 0.5 + 0.5, 0.0, 1.0), bed);
            }
            """;

    /** The GL state a pass here changes, kept to put back afterwards. */
    private static final class State {
        private int program, framebuffer, active, depthFunc;
        private boolean depthTest, depthMask, cull;

        void save() {
            program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            framebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            active = GlStateManager._getActiveTexture();
            depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
            depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
            depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
            cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        }

        void restore() {
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL20C.glUseProgram(program);
            GlStateManager._activeTexture(active);
            if (depthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(depthFunc);
            GL11C.glDepthMask(depthMask);
            if (cull) GL11C.glEnable(GL11C.GL_CULL_FACE); else GL11C.glDisable(GL11C.GL_CULL_FACE);
        }
    }

    /** A full-screen program reading the game's unit and the scratch unit. */
    private static final class Program {
        final int id;
        private final int inverse, game;

        private Program(int id) {
            this.id = id;
            inverse = GL20C.glGetUniformLocation(id, "earth_FarInverse");
            game = GL20C.glGetUniformLocation(id, "earth_GameProjection");
            int previous = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            GL20C.glUseProgram(id);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(id, "earth_Game"), VANILLA_UNIT);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(id, "earth_Far"), SCRATCH_UNIT);
            GL20C.glUseProgram(previous);
        }

        void matrices() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer matrix = stack.mallocFloat(16);
                GL20C.glUniformMatrix4fv(inverse, false, FarLod.inverse.get(matrix));
                GL20C.glUniformMatrix4fv(game, false, vanilla.get(matrix));
            }
        }

        static Program link(String vertexSource, String fragmentSource, String name) {
            int vertex = compile(GL20C.GL_VERTEX_SHADER, vertexSource, name);
            int fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentSource, name);
            int program = GL20C.glCreateProgram();
            GL20C.glAttachShader(program, vertex);
            GL20C.glAttachShader(program, fragment);
            GL20C.glLinkProgram(program);
            GL20C.glDeleteShader(vertex);
            GL20C.glDeleteShader(fragment);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                String log = GL20C.glGetProgramInfoLog(program);
                GL20C.glDeleteProgram(program);
                throw new IllegalStateException("Could not link " + name + ": " + log);
            }
            return new Program(program);
        }

        private static int compile(int type, String source, String name) {
            int shader = GL20C.glCreateShader(type);
            GL20C.glShaderSource(shader, source);
            GL20C.glCompileShader(shader);
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
                String log = GL20C.glGetShaderInfoLog(shader);
                GL20C.glDeleteShader(shader);
                throw new IllegalStateException("Could not compile " + name + ": " + log);
            }
            return shader;
        }

        void close() { GL20C.glDeleteProgram(id); }
    }
}
