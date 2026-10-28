package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.Timings;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.FogType;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/** Client hooks: attach the cube store, compile on ticks and draw at the vanilla terrain stages. */
public final class CubicClient {
    private CubicClient() {}

    private static long lastFrame;
    private static int perfTicks;
    /** Ticks between perf log lines, with {@code -Dearthplatform.cubic.perf=true}. */
    private static final int PERF_TICKS = Math.max(1, Integer.getInteger("earthplatform.cubic.perfTicks", 100));

    public static void load(LevelAccessor levelAccessor) {
        if (levelAccessor instanceof ClientLevel level) ClientCubeWorld.attach(level);
    }

    public static void unload(LevelAccessor levelAccessor) {
        if (levelAccessor instanceof ClientLevel level && CubicLevel.of(level) instanceof ClientCubeWorld world) {
            world.close();
            ((CubicLevel) level).earthplatform$setCubes(null);
        }
    }

    /** A respawn keeps the level but starts a fresh stream of cubes around the new position. */
    public static void respawn() {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world != null) world.reset();
    }

    /** Where a player waiting for the cubes around them is held, or null when they move freely. */
    private static net.minecraft.world.phys.Vec3 held;

    /** Whether the cubes at a player's feet and just below have not arrived yet. */
    private static boolean waitingForCubes(ClientCubeWorld world, Player player) {
        return world.cubeAtBlock(player.getBlockX(), player.getBlockY(), player.getBlockZ()) == null
                || world.cubeAtBlock(player.getBlockX(), player.getBlockY() - 2, player.getBlockZ()) == null;
    }

    /** Replay Mod (and its NeoForge ports) plays back through its own camera entity, which must never be held or captioned. */
    private static boolean inReplay(Player player) {
        return player.getClass().getName().startsWith("com.replaymod.");
    }

    /** Whether a replay is playing: no server is there to send terrain again. */
    public static boolean inReplay() {
        var player = Minecraft.getInstance().player;
        return player != null && inReplay(player);
    }

    /** Whether this frame began through {@link #frame}; a video renderer draws frames without it. */
    private static boolean framed;

    /**
     * The level is about to be drawn. A video renderer (Replay Mod's) draws each frame itself, without
     * the frame event that starts ours, so nothing new ever reached the GPU and its videos lost the far
     * terrain: there, the frame starts here. A flawless frame then finishes the terrain before drawing.
     */
    public static void levelPass() {
        boolean flawless = Flawless.active(), replay = inReplay();
        if (!framed && (flawless || replay)) startFrame();
        framed = false;
        ClientCubeWorld world = ClientCubeWorld.current();
        // A paused replay doesn't tick, so after a seek nothing was ever meshed: there the renderers tick from frames.
        if (replay && !flawless && world != null && System.nanoTime() - tickedAt > 100_000_000L) {
            tickedAt = System.nanoTime();
            world.renderer().tick();
            world.far().tick();
        }
        if (flawless && world != null) Flawless.settle(world);
    }

    /**
     * Holds a player in place until the cubes around them arrive, as vanilla does in an unloaded
     * chunk, so a teleport into unloaded sky does not fall through it. A player gliding or in
     * spectator mode keeps going: the jet stream outruns loading by design.
     */
    private static void hold() {
        var player = Minecraft.getInstance().player;
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null || player == null || player.isSpectator() || inReplay(player) || player.isFallFlying() || player.isPassenger()
                || io.earthplatform.minecraft.cubic.jet.JetStream.aboveTerrain(player) || !waitingForCubes(world, player)) {
            held = null;
            return;
        }
        if (held == null || held.distanceToSqr(player.position()) > 4.0) held = player.position();
        player.setPos(held);
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.resetFallDistance();
    }

    public static void tickStart() { hold(); }

    /** When the client last ticked; a paused replay stops ticking, and with it all meshing. */
    private static long tickedAt;

    public static void tick() {
        tickedAt = System.nanoTime();
        hold();
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world != null) { world.checkShaders(); world.renderer().tick(); world.far().tick(); }
        PhotonCloudsHook.tick();
        if (world != null && Timings.LOG && ++perfTicks % PERF_TICKS == 0) {
            Runtime runtime = Runtime.getRuntime();
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            int[] byView = world.renderer().meshesByView(camera.getPosition(), camera.getYRot());
            EarthPlatformMod.LOGGER.info("cubic perf: {} fps, heap {} MB, {} cubes, {} meshes ({} in view), {} to compile, far {}/{} columns {}% settled ({}% in view) {} MB {} draws, {} sent again; {}",
                    Minecraft.getInstance().getFps(), (runtime.totalMemory() - runtime.freeMemory()) >> 20, world.loadedCubes(),
                    world.renderer().meshCount(), byView[0], world.renderer().pendingCount(), world.far().drawnCount(), world.far().columnCount(),
                    world.far().settledPercent(), world.far().settledInViewPercent(), world.far().vertexBytes() >> 20, world.far().drawnRanges(), world.far().replacedCount(), timings(Timings.LOGGER));
        }
    }

    private static String timings(int reader) {
        return String.join(", ", Timings.FRAME.read(reader), Timings.CUBE_DRAW.read(reader), Timings.FAR_DRAW.read(reader), Timings.FAR_WALK.read(reader), Timings.FAR_WATER.read(reader),
                Timings.CUBE_UPLOAD.read(reader), Timings.FAR_UPLOAD.read(reader), Timings.CUBE_MESH.read(reader),
                Timings.FAR_MESH.read(reader), Timings.FAR_MAKE.read(reader),
                Timings.SERVER_TICK.read(reader), Timings.CUBE_UNLOAD.read(reader), Timings.CUBE_FLUSH.read(reader));
    }

    public static void frame() {
        startFrame();
        framed = true;
    }

    private static void startFrame() {
        LevelPasses.beginFrame();
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world != null) { world.renderer().beginFrame(); world.far().beginFrame(); }
        long now = System.nanoTime();
        if (world != null && lastFrame != 0) Timings.FRAME.add(now - lastFrame);
        lastFrame = now;
    }

    /** The points in drawing the level where the cubes and far terrain draw, as NeoForge names them. */
    public enum Stage { AFTER_CUTOUT_BLOCKS, AFTER_ENTITIES, AFTER_BLOCK_ENTITIES, AFTER_TRANSLUCENT_BLOCKS, AFTER_PARTICLES }

    /** Draws at one stage of the level (or of a shader pack's shadow map), with that pass's camera and matrices. */
    public static void render(Stage stage, Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection, PoseStack pose, float partialTick) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null) return;
        CubeRenderer renderer = world.renderer();
        if (ShaderBlocks.shadowPass()) {
            // A shader pack's shadow map: near terrain casts shadows; far terrain lies past the shadow distance.
            if (stage == Stage.AFTER_CUTOUT_BLOCKS)
                renderer.drawShadow(camera, modelView, projection);
            return;
        }
        if (stage == Stage.AFTER_CUTOUT_BLOCKS
                || (stage == Stage.AFTER_ENTITIES && !renderer.drewOpaqueThisPass())) {
            renderer.drawOpaque(camera, frustum, modelView, projection);
            world.far().draw(camera, frustum, modelView, projection);
        } else if (stage == Stage.AFTER_BLOCK_ENTITIES) {
            renderer.drawBlockEntities(camera, frustum, pose, partialTick);
        } else if (stage == Stage.AFTER_TRANSLUCENT_BLOCKS || stage == Stage.AFTER_PARTICLES) {
            world.far().drawWater(camera, modelView, projection);
            renderer.drawTranslucent(camera, frustum, modelView, projection);
        }
    }

    private static long timingsAt;
    private static String[] timingLines = new String[0];

    /** Adds the cube and far terrain lines to the left side of the debug screen. */
    public static void debugText(java.util.List<String> left) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null) return;
        // Near the top, where small windows still show it.
        int at = Math.min(3, left.size());
        left.add(at, "Cubic: " + world.loadedCubes() + " cubes, " + world.renderer().meshCount() + " meshes, "
                + world.renderer().pendingCount() + " to compile");
        left.add(at + 1, "Far: " + world.far().drawnCount() + "/" + world.far().columnCount() + " columns, "
                + world.far().settledPercent() + "% settled (" + world.far().settledInViewPercent() + "% in view), "
                + world.far().vertexBytes() / (1 << 20) + " MB, " + world.far().drawnRanges() + " draws");
        String zoom = world.far().zoomInfo();
        if (!zoom.isEmpty()) left.add(at + 2, zoom);
        // The debug screen redraws every frame; read the timings about twice a second so they can be read.
        long now = System.nanoTime();
        if (now - timingsAt > 500_000_000L) { timingsAt = now; timingLines = timings(Timings.SCREEN).split(", "); }
        for (int i = 0; i < timingLines.length; i += 2) {
            left.add(at + 2 + i / 2, i + 1 < timingLines.length ? timingLines[i] + ", " + timingLines[i + 1] : timingLines[i]);
        }
    }

    /** Metres of sea that stay the water's own colour, how fast the light fades below, and the darkest it gets. */
    private static final double LIT_DEPTH = 100, FADE_DEPTH = 700, DARKEST = 0.15;

    /**
     * Deep under the sea the water's colour fades toward a dark navy, as sunlight does. Vanilla
     * only darkens fog near the bottom of the world, and its underwater vision then brightens the
     * fog back to full strength, so the trench floors were a bright sky blue 10 km down.
     */
    public static float waterFogLight(Camera camera) {
        if (ClientCubeWorld.current() == null || camera.getFluidInCamera() != FogType.WATER) return 1.0F;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return 1.0F;
        double depth = level.getSeaLevel() - camera.getPosition().y;
        if (depth <= LIT_DEPTH) return 1.0F;
        return (float) Math.max(DARKEST, Math.exp(-(depth - LIT_DEPTH) / FADE_DEPTH));
    }

    /**
     * Pushes terrain fog out to the far terrain's reach, measured in ground metres; effect and
     * fluid fog stay as they are. Returns the fog's start and end, or null to leave vanilla's.
     */
    @javax.annotation.Nullable
    public static float[] fog(FogRenderer.FogMode mode, FogType type, Camera view, float farPlaneDistance) {
        if (ClientCubeWorld.current() == null || mode != FogRenderer.FogMode.FOG_TERRAIN || type != FogType.NONE) return null;
        if (farPlaneDistance < Minecraft.getInstance().gameRenderer.getRenderDistance() - 1.0F) return null;
        // High up, the ground below is as far away as the horizon is at ground level: start the fog
        // beyond it, so terrain under a player in the jet stream is clear rather than a pale haze.
        var camera = view.getPosition();
        ClientCubeWorld world = ClientCubeWorld.current();
        float height = (float) camera.y - world.skyTop(net.minecraft.util.Mth.floor(camera.x), net.minecraft.util.Mth.floor(camera.z));
        float scale = FarRenderer.HAZE_AT_EDGE ? 1.0F : world.far().hazeScale(camera.z), end = FarRenderer.FOG_END * scale;
        float start = Math.min(Math.max(FarRenderer.FOG_START * scale, height * 1.25F), 0.8F * end);
        return new float[] {start, end};
    }

    public static void waiting(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null || minecraft.player == null) return;
        var player = minecraft.player;
        // Spectators are never held, and a replay camera must stay clean for rendered videos.
        if (player.isSpectator() || inReplay(player) || io.earthplatform.minecraft.cubic.jet.JetStream.outrunsTerrain(player)) return;
        if (!waitingForCubes(world, player)) return;
        var gui = graphics;
        Component text = Component.literal("Loading terrain at Y " + player.getBlockY() + "...");
        gui.drawCenteredString(minecraft.font, text, gui.guiWidth() / 2, gui.guiHeight() / 2 - 20, 0xFFFFFF);
    }
}
