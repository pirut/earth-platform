package io.earthplatform.minecraft.neoforge;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.client.CubicClient;
import io.earthplatform.minecraft.cubic.client.Flawless;
import io.earthplatform.minecraft.cubic.client.PerfExportCommand;
import io.earthplatform.minecraft.cubic.client.PerformanceDiagnostics;
import io.earthplatform.minecraft.cubic.compat.CubicCompat;
import io.earthplatform.minecraft.cubic.jet.JetHud;
import io.earthplatform.minecraft.cubic.play.EarthWorlds;
import io.earthplatform.minecraft.cubic.play.client.EarthCompassHud;
import io.earthplatform.minecraft.cubic.play.client.EarthFlight;
import io.earthplatform.minecraft.cubic.play.client.EarthPlayClient;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.level.LevelEvent;

/** NeoForge's client events, handed to the game code. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientEvents {
    private NeoForgeClientEvents() {}

    /** Client setup and registration, on the mod's own event bus. */
    @EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            ModList.get().getModContainerById(EarthPlatformMod.MOD_ID).orElseThrow()
                    .registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory) ConfigurationScreen::new);
            event.enqueueWork(Flawless::offer);
        }

        @SubscribeEvent public static void presetEditors(RegisterPresetEditorsEvent event) {
            event.register(EarthWorlds.PRESET, (parent, context) -> EarthPlayClient.presetEditor(parent));
        }

        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(EarthFlight.KEY); }
    }

    @SubscribeEvent public static void load(LevelEvent.Load event) { CubicClient.load(event.getLevel()); }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) { CubicClient.unload(event.getLevel()); }

    @SubscribeEvent public static void tickStart(ClientTickEvent.Pre event) { CubicClient.tickStart(); }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        CubicClient.tick();
        CubicCompat.tick();
        EarthFlight.clientTick();
        EarthCompassHud.tick();
        JetHud.tick();
    }

    @SubscribeEvent public static void respawn(ClientPlayerNetworkEvent.Clone event) { CubicClient.respawn(); }

    @SubscribeEvent public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        EarthPlayClient.loggedOut();
        EarthCompassHud.loggedOut();
        CubicCompat.logout();
    }

    @SubscribeEvent public static void frameStart(RenderFrameEvent.Pre event) { CubicClient.frame(); }
    @SubscribeEvent public static void frameEnd(RenderFrameEvent.Post event) { PerformanceDiagnostics.frame(); }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        CubicClient.Stage stage = stage(event.getStage());
        if (stage != null) CubicClient.render(stage, event.getCamera(), event.getFrustum(), event.getModelViewMatrix(), event.getProjectionMatrix(),
                event.getPoseStack(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    private static CubicClient.Stage stage(RenderLevelStageEvent.Stage stage) {
        if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) return CubicClient.Stage.AFTER_CUTOUT_BLOCKS;
        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) return CubicClient.Stage.AFTER_ENTITIES;
        if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return CubicClient.Stage.AFTER_BLOCK_ENTITIES;
        if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return CubicClient.Stage.AFTER_TRANSLUCENT_BLOCKS;
        if (stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) return CubicClient.Stage.AFTER_PARTICLES;
        return null;
    }

    @SubscribeEvent public static void debugText(CustomizeGuiOverlayEvent.DebugText event) { CubicClient.debugText(event.getLeft()); }

    @SubscribeEvent public static void fogColour(ViewportEvent.ComputeFogColor event) {
        float light = CubicClient.waterFogLight(event.getCamera());
        if (light == 1.0F) return;
        event.setRed(event.getRed() * light);
        event.setGreen(event.getGreen() * light);
        event.setBlue(event.getBlue() * light);
    }

    @SubscribeEvent public static void fog(ViewportEvent.RenderFog event) {
        float[] fog = CubicClient.fog(event.getMode(), event.getType(), event.getCamera(), event.getFarPlaneDistance());
        if (fog == null) return;
        event.setNearPlaneDistance(fog[0]);
        event.setFarPlaneDistance(fog[1]);
        event.setCanceled(true);
    }

    @SubscribeEvent public static void hud(RenderGuiEvent.Post event) {
        CubicClient.waiting(event.getGuiGraphics());
        EarthPlayClient.hud(event.getGuiGraphics());
    }

    @SubscribeEvent public static void screenOpened(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        EarthPlayClient.createWorld(screen);
        EarthPlayClient.pauseMenu(screen, event.getListenersList(), event::addListener);
    }

    @SubscribeEvent public static void clientCommands(RegisterClientCommandsEvent event) { PerfExportCommand.register(event.getDispatcher()); }
}
