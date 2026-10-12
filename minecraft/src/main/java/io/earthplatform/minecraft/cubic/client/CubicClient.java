package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.neoforged.neoforge.event.level.LevelEvent;

/** Client hooks: attach the cube store, compile on ticks and draw at the vanilla terrain stages. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class CubicClient {
    private CubicClient() {}

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
        if (world != null) world.renderer().tick();
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world != null) world.renderer().beginFrame();
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null) return;
        CubeRenderer renderer = world.renderer();
        var stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS
                || (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES && !renderer.drewOpaqueThisFrame())) {
            renderer.drawOpaque(event.getCamera(), event.getFrustum(), event.getModelViewMatrix(), event.getProjectionMatrix());
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            renderer.drawBlockEntities(event.getCamera(), event.getFrustum(), event.getPoseStack(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false));
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            renderer.drawTranslucent(event.getCamera(), event.getFrustum(), event.getModelViewMatrix(), event.getProjectionMatrix());
        }
    }

    @SubscribeEvent public static void debugText(CustomizeGuiOverlayEvent.DebugText event) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null) return;
        event.getLeft().add("Cubic: " + world.loadedCubes() + " cubes, " + world.renderer().meshCount() + " meshes, "
                + world.renderer().pendingCount() + " to compile");
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
