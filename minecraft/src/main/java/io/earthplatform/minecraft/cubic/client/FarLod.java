package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.FloatBuffer;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
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
 * buffer holding the nearer of the game's depth and the far terrain's with its water floors laid in
 * (in the game's projection),
 * and {@link #endWater} draws the water's surfaces once more into the game's depth, where far
 * terrain's own depth shows them. (In the game's depth a far lake's surface and its bed fall on the
 * same value, so telling where the water drew by comparing the two missed every other row, and
 * Photon then left clouds over far water out on those rows.) Packs judge
 * how deep water is from far terrain's depth, so {@link #beginDeepen} first gives far water a
 * floor at least {@link #WATER_DEPTH} blocks below its surface there, and further at kilometres
 * off, so the game's coarse depth there still puts the surface in front of it.
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
    private static int faces, scratch, waterFramebuffer, restoreFramebuffer, vao;
    private static int shadows, shadowFramebuffer;
    @Nullable private static Program merge, surface, smooth, deepen, sun;
    /** The water depth pass under way, which {@link #offset} sets the next column's place on. */
    @Nullable private static Program drawing;
    private static boolean failed;
    private static int drawnFrom, gameDepth, waterFrom, waterDepth, previousActive;
    private static final State saved = new State();
    /** The least depth, in blocks along the view, packs are given for far water: enough to read as water from afar. */
    private static final float WATER_DEPTH = 24.0F;
    private static final int[] previous2d = new int[8];

    private static final Matrix4f projection = new Matrix4f(), inverse = new Matrix4f(), previous = new Matrix4f(), vanilla = new Matrix4f();
    /**
     * Each pass of a frame's last projection, for the pack's history (see {@link LevelPasses}): a VR
     * mod draws one pass per eye, and each eye's history is its own one a frame ago, not the other eye's.
     */
    private static final Matrix4f[] history = new Matrix4f[LevelPasses.TRACKED];
    /** The projection {@link #shadows} were drawn with, which the next pass looks them up through. */
    private static final Matrix4f shadowProjection = new Matrix4f();
    private static boolean projected;

    /** How much light far terrain in its own hills' shadow keeps, as a share of its colour. */
    private static final float SHADE = 0.65F;
    /** How much of the sky's light far terrain loses where its own hills close in all round. */
    private static final float OCCLUSION = 0.8F;
    /** How much sky light far terrain loses for the block-scale nooks it is too far off to show (see FarShaders). */
    private static final float DETAIL = 0.5F;
    private static final Matrix4f modelView = new Matrix4f(), shadowFrame = new Matrix4f(), lastView = new Matrix4f();
    private static final Vector3f sunView = new Vector3f(), sunWorld = new Vector3f(), upView = new Vector3f();
    private static double cameraX, cameraY, cameraZ, lastX, lastY, lastZ;
    /** Whether {@link #shadows} holds last frame's far shadows, which this frame's far terrain reads. */
    private static boolean shadowsReady, sunUp;

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

    /**
     * Far terrain's reach in chunks, which packs fade it out by: almost to the coarsest level's
     * nearest edge (half the depth range). Not the haze over the lowlands, which a pack such as
     * Photon draws itself, thinning with height, so distant ranges still stand clear of it.
     */
    static int renderDistance() {
        if (ClientCubeWorld.current() == null) return Minecraft.getInstance().options.getEffectiveRenderDistance();
        return (int) Math.ceil(0.95F * 0.5F * FarRenderer.DEPTH_FAR / 16.0F);
    }

    /** How far out from the camera, in blocks, the game's blocks reach before far terrain takes over. */
    static float nearReach() {
        return Math.min(io.earthplatform.minecraft.cubic.Cubic.MAX_HORIZONTAL_RADIUS, Minecraft.getInstance().options.getEffectiveRenderDistance()) * 16.0F;
    }

    // Each frame ---------------------------------------------------------------------------------

    /**
     * Clears far terrain's depth each time the level is drawn and keeps it the size of the game's
     * targets: per pass, not per frame, as a VR mod's second eye otherwise drew over the first's depth.
     */
    static void beginPass() {
        projected = false;
        if (!defined || depth == 0) return;
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (target.width != width || target.height != height) resize(target.width, target.height);
        int bound = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        clear();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, bound);
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
        vanilla.set(game);
        float near = NEAR, far = FarRenderer.DEPTH_FAR;
        projection.set(game).m22((far + near) / (near - far)).m32(2.0F * far * near / (near - far));
        projection.invert(inverse);
        Matrix4f last = history[LevelPasses.inFrame()];
        if (!projected) {
            // A zero "previous" projection would reproject the pack's history to NaN, which never clears.
            previous.set(last != null ? last : projection);
            projected = true;
        }
        if (last == null) history[LevelPasses.inFrame()] = new Matrix4f(projection);
        else last.set(projection);
        return new Matrix4f(projection);
    }

    /**
     * Points far terrain's draw at the pack's first terrain target and at far terrain's own depth,
     * after the pack's terrain program has bound its buffers. The game's depth is bound on a unit
     * the far shader reads, so far terrain stays behind whatever the game drew.
     */
    static void beginDraw(Matrix4fc view, double x, double y, double z) {
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
        bind(SCRATCH_UNIT, shadows);
        GlStateManager._activeTexture(previousActive);
        // Last frame's shadows are looked up from where this frame's camera now stands.
        shadowFrame.set(shadowProjection).mul(lastView).translate((float) (x - lastX), (float) (y - lastY), (float) (z - lastZ));
        modelView.set(view);
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        sun();
    }

    /** The far terrain shader's uniforms for its sun shadows; {@code location} finds one in the bound program. */
    static void shadowUniforms(ToIntFunction<String> location) {
        GL20C.glUniform1i(location.applyAsInt("earth_SunShadows"), SCRATCH_UNIT);
        GL20C.glUniform1f(location.applyAsInt("earth_ShadowShade"), shadowsReady && sunUp ? 1.0F - SHADE : 0.0F);
        GL20C.glUniform1f(location.applyAsInt("earth_Occlusion"), shadowsReady ? OCCLUSION : 0.0F);
        GL20C.glUniform1f(location.applyAsInt("earth_Detail"), DETAIL);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20C.glUniformMatrix4fv(location.applyAsInt("earth_ShadowFrame"), false, shadowFrame.get(stack.mallocFloat(16)));
        }
    }

    /** The sun's direction this frame, as the pack places it (its sun path tilt included). */
    private static void sun() {
        var level = Minecraft.getInstance().level;
        if (level == null) { sunUp = false; return; }
        float day = level.getTimeOfDay(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        float tilt = ShaderBlocks.IRIS ? FarLodIris.sunPathRotation() : 0.0F;
        // The sky turns about the east-west axis; a pack can tilt that path about the north-south one.
        sunWorld.set(0.0F, 1.0F, 0.0F).rotateX((float) Math.toRadians(day * 360.0F))
                .rotateZ((float) Math.toRadians(tilt)).rotateY((float) Math.toRadians(-90.0F));
        sunUp = sunWorld.y > 0.02F;
        modelView.transformDirection(sunWorld, sunView).normalize();
        modelView.transformDirection(upView.set(0.0F, 1.0F, 0.0F)).normalize();
    }

    static int vanillaUnit() { return VANILLA_UNIT; }

    static void endDraw() {
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
        unbind(SCRATCH_UNIT);
        unbind(VANILLA_UNIT);
        smooth();
        shadows();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawnFrom);
        GlStateManager._activeTexture(previousActive);
    }

    /**
     * Far terrain's shadows from its own hills, for the next frame's far terrain to darken by: from
     * each far pixel, steps towards the sun through far terrain's depth, out to kilometres. Packs'
     * own screen-space shadows only look a few blocks deep, which at the game's coarse depth caught
     * far walls by chance (as stripes) and at this fine one catches almost nothing.
     */
    private static void shadows() {
        lastView.set(modelView);
        shadowProjection.set(projection);
        lastX = cameraX;
        lastY = cameraY;
        lastZ = cameraZ;
        if (!programs()) { shadowsReady = false; return; }
        saved.save();
        boolean[] mask = colourMask();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, shadowFramebuffer);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glColorMask(true, true, true, true);
        boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_BLEND);
        bind(VANILLA_UNIT, depth);
        GL20C.glUseProgram(sun.id);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(sun.id, "earth_Projection"), false, projection.get(matrix));
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(sun.id, "earth_FarInverse"), false, inverse.get(matrix));
        }
        GL20C.glUniform3f(GL20C.glGetUniformLocation(sun.id, "earth_Sun"), sunView.x, sunView.y, sunView.z);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(sun.id, "earth_SunUp"), sunUp ? 1 : 0);
        GL20C.glUniform3f(GL20C.glGetUniformLocation(sun.id, "earth_Up"), upView.x, upView.y, upView.z);
        GL20C.glUniform2f(GL20C.glGetUniformLocation(sun.id, "earth_Focal"), projection.m00() * 0.5F * width, projection.m11() * 0.5F * height);
        fullScreen();
        unbind(VANILLA_UNIT);
        if (blend) GL11C.glEnable(GL11C.GL_BLEND);
        GL11C.glColorMask(mask[0], mask[1], mask[2], mask[3]);
        saved.restore();
        shadowsReady = true;
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
        GL20C.glUniform1f(GL20C.glGetUniformLocation(smooth.id, "earth_Focal"), projection.m11() * 0.5F * height);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(smooth.id, "earth_NearReach"), nearReach());
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
     * {@link #offset} per column, then calls {@link #endDeepen}.
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
        drawing = deepen;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(deepen.id, "earth_ModelView"), false, modelView.get(matrix));
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(deepen.id, "earth_Projection"), false, projection.get(matrix));
        }
        GL20C.glUniform1f(GL20C.glGetUniformLocation(deepen.id, "earth_Least"), WATER_DEPTH);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(deepen.id, "earth_GameNear"), vanilla.m32() / (vanilla.m22() - 1.0F));
        GL20C.glUniform2f(GL20C.glGetUniformLocation(deepen.id, "earth_NearFar"), NEAR, FarRenderer.DEPTH_FAR);
        return true;
    }

    /** Where the next column's water sits relative to the camera, for the water depth pass under way. */
    static void offset(float x, float y, float z) {
        GL20C.glUniform3f(GL20C.glGetUniformLocation(drawing.id, "earth_Offset"), x, y, z);
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
        // Far terrain's depth with the water floors laid in, so far water always lies in front.
        bind(SCRATCH_UNIT, depth);
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

    /**
     * After far water: gives the pack its depth buffer back, then has {@code water} draw the far
     * water once more (setting {@link #offset} per column) into it where far terrain shows it, so
     * the pack finds the water's surface in front of what lies behind it.
     */
    static void endWater(Matrix4fc modelView, Runnable water) {
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, waterFrom);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, waterDepth, 0);
        saved.save();
        boolean[] colourMask = colourMask();

        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, restoreFramebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, waterDepth, 0);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LESS);
        GL11C.glDepthMask(true);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glColorMask(false, false, false, false);
        bind(SCRATCH_UNIT, depth);
        GL20C.glUseProgram(surface.id);
        drawing = surface;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(surface.id, "earth_ModelView"), false, modelView.get(matrix));
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(surface.id, "earth_Projection"), false, vanilla.get(matrix));
        }
        GL20C.glUniform2f(GL20C.glGetUniformLocation(surface.id, "earth_NearFar"), NEAR, FarRenderer.DEPTH_FAR);
        water.run();
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, 0, 0);

        unbind(SCRATCH_UNIT);
        saved.restore();
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
        if (faces == 0) faces = GL11C.glGenTextures();
        if (shadows == 0) shadows = GL11C.glGenTextures();
        storage(depth, GL30C.GL_DEPTH_COMPONENT32F, GL11C.GL_DEPTH_COMPONENT);
        storage(scratch, GL30C.GL_DEPTH_COMPONENT32F, GL11C.GL_DEPTH_COMPONENT);
        storage(faces, GL30C.GL_R32F, GL30C.GL_RED);
        storage(shadows, GL30C.GL_RG8, GL30C.GL_RG);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        shadowsReady = false;
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bound2d);
        GlStateManager._activeTexture(active);

        int bound = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0) framebuffer = GL30C.glGenFramebuffers();
        if (waterFramebuffer == 0) waterFramebuffer = GL30C.glGenFramebuffers();
        if (restoreFramebuffer == 0) restoreFramebuffer = GL30C.glGenFramebuffers();
        if (shadowFramebuffer == 0) shadowFramebuffer = GL30C.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, shadowFramebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, shadows, 0);
        GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1, GL11C.GL_TEXTURE_2D, faces, 0);
        clear();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, waterFramebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, scratch, 0);
        GL20C.glDrawBuffers(GL11C.GL_NONE);
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
            merge = Program.link(FarLodShaders.FULL_SCREEN, FarLodShaders.MERGE, "far water depth");
            surface = Program.link(FarLodShaders.DEEPEN_VERTEX, FarLodShaders.SURFACE, "far water surface depth");
            smooth = Program.link(FarLodShaders.FULL_SCREEN, FarLodShaders.SMOOTH, "far terrain depth smoothing");
            deepen = Program.link(FarLodShaders.DEEPEN_VERTEX, FarLodShaders.DEEPEN, "far water depth floor");
            sun = Program.link(FarLodShaders.FULL_SCREEN, FarLodShaders.SUN, "far terrain sun shadows");
            return true;
        } catch (IllegalStateException failure) {
            failed = true;
            EarthPlatformMod.LOGGER.error("Far water can't be hidden behind far terrain under shaders; far water may show through far hills", failure);
            return false;
        }
    }

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
            return new Program(GlShaders.link(vertexSource, fragmentSource, name));
        }

        void close() { GL20C.glDeleteProgram(id); }
    }
}
