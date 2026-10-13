package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.Timings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Client hooks: attach the cube store, compile on ticks and draw at the vanilla terrain stages. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class CubicClient {
    private CubicClient() {}

    private static long lastFrame;
    private static int perfTicks;

    @SubscribeEvent public static void load(LevelEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level) ClientCubeWorld.attach(level);
    }

    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level && CubicLevel.of(level) instanceof ClientCubeWorld world) {
            world.close();
            ((CubicLevel) level).earthplatform$setCubes(null);
        }
    }

    /** A respawn keeps the level but starts a fresh stream of cubes around the new position. */
    @SubscribeEvent public static void respawn(ClientPlayerNetworkEvent.Clone event) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world != null) world.reset();
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world != null) { world.checkShaders(); world.renderer().tick(); world.far().tick(); }
        if (world != null && Timings.LOG && ++perfTicks % 100 == 0) {
            Runtime runtime = Runtime.getRuntime();
            EarthPlatformMod.LOGGER.info("cubic perf: {} fps, heap {} MB, {} cubes, {} meshes, {} to compile, far {}/{} columns {} MB, {} sent again; {}",
                    Minecraft.getInstance().getFps(), (runtime.totalMemory() - runtime.freeMemory()) >> 20, world.loadedCubes(),
                    world.renderer().meshCount(), world.renderer().pendingCount(), world.far().drawnCount(), world.far().columnCount(),
                    world.far().vertexBytes() >> 20, world.far().replacedCount(), timings(Timings.LOGGER));
        }
    }

    private static String timings(int reader) {
        return String.join(", ", Timings.FRAME.read(reader), Timings.CUBE_DRAW.read(reader), Timings.FAR_DRAW.read(reader),
                Timings.CUBE_UPLOAD.read(reader), Timings.FAR_UPLOAD.read(reader), Timings.CUBE_MESH.read(reader),
                Timings.FAR_MESH.read(reader), Timings.FAR_MAKE.read(reader),
                Timings.SERVER_TICK.read(reader), Timings.CUBE_UNLOAD.read(reader), Timings.CUBE_FLUSH.read(reader));
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world != null) { world.renderer().beginFrame(); world.far().beginFrame(); }
        long now = System.nanoTime();
        if (world != null && lastFrame != 0) Timings.FRAME.add(now - lastFrame);
        lastFrame = now;
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null) return;
        CubeRenderer renderer = world.renderer();
        var stage = event.getStage();
        if (ShaderBlocks.shadowPass()) {
            // A shader pack's shadow map: near terrain casts shadows; far terrain lies past the shadow distance.
            if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS)
                renderer.drawShadow(event.getCamera(), event.getModelViewMatrix(), event.getProjectionMatrix());
            return;
        }
        if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS
                || (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES && !renderer.drewOpaqueThisFrame())) {
            renderer.drawOpaque(event.getCamera(), event.getFrustum(), event.getModelViewMatrix(), event.getProjectionMatrix());
            world.far().draw(event.getCamera(), event.getFrustum(), event.getModelViewMatrix(), event.getProjectionMatrix());
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            renderer.drawBlockEntities(event.getCamera(), event.getFrustum(), event.getPoseStack(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false));
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            world.far().drawWater(event.getCamera(), event.getModelViewMatrix(), event.getProjectionMatrix());
            renderer.drawTranslucent(event.getCamera(), event.getFrustum(), event.getModelViewMatrix(), event.getProjectionMatrix());
        }
    }

    private static long timingsAt;
    private static String[] timingLines = new String[0];

    @SubscribeEvent public static void debugText(CustomizeGuiOverlayEvent.DebugText event) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null) return;
        // Near the top, where small windows still show it.
        int at = Math.min(3, event.getLeft().size());
        event.getLeft().add(at, "Cubic: " + world.loadedCubes() + " cubes, " + world.renderer().meshCount() + " meshes, "
                + world.renderer().pendingCount() + " to compile");
        event.getLeft().add(at + 1, "Far: " + world.far().drawnCount() + "/" + world.far().columnCount() + " columns, "
                + world.far().vertexBytes() / (1 << 20) + " MB");
        // The debug screen redraws every frame; read the timings about twice a second so they can be read.
        long now = System.nanoTime();
        if (now - timingsAt > 500_000_000L) { timingsAt = now; timingLines = timings(Timings.SCREEN).split(", "); }
        for (int i = 0; i < timingLines.length; i += 2) {
            event.getLeft().add(at + 2 + i / 2, i + 1 < timingLines.length ? timingLines[i] + ", " + timingLines[i + 1] : timingLines[i]);
        }
    }

    /** Pushes terrain fog out to the far terrain's reach; effect and fluid fog stay as they are. */
    @SubscribeEvent public static void fog(ViewportEvent.RenderFog event) {
        if (ClientCubeWorld.current() == null || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || event.getType() != FogType.NONE) return;
        if (event.getFarPlaneDistance() < Minecraft.getInstance().gameRenderer.getRenderDistance() - 1.0F) return;
        event.setNearPlaneDistance(FarRenderer.FOG_START);
        event.setFarPlaneDistance(FarRenderer.FOG_END);
        event.setCanceled(true);
    }

    @SubscribeEvent public static void waiting(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null || minecraft.player == null) return;
        var player = minecraft.player;
        if (world.cubeAtBlock(player.getBlockX(), player.getBlockY(), player.getBlockZ()) != null
                && world.cubeAtBlock(player.getBlockX(), player.getBlockY() - 2, player.getBlockZ()) != null) return;
        var gui = event.getGuiGraphics();
        Component text = Component.literal("Loading terrain at Y " + player.getBlockY() + "...");
        gui.drawCenteredString(minecraft.font, text, gui.guiWidth() / 2, gui.guiHeight() / 2 - 20, 0xFFFFFF);
    }
}
