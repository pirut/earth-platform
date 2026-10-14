package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import io.earthplatform.minecraft.cubic.play.EarthStatus;
import io.earthplatform.minecraft.cubic.play.EarthWorlds;
import java.lang.ref.WeakReference;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** The Earth's screens: world setup, the arrival wait, download notes and the config screen. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class EarthPlayClient {
    private static volatile EarthStatus.Payload status;
    private static volatile long statusAt;
    private static WeakReference<CreateWorldScreen> preselected = new WeakReference<>(null);

    private EarthPlayClient() {}

    @EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            ModList.get().getModContainerById(EarthPlatformMod.MOD_ID).orElseThrow()
                    .registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory) ConfigurationScreen::new);
        }

        @SubscribeEvent public static void presetEditors(RegisterPresetEditorsEvent event) {
            event.register(EarthWorlds.PRESET, (parent, context) -> new EarthSetupScreen(parent));
        }
    }

    /** A status from the server; opens or closes the arrival wait. */
    public static void status(EarthStatus.Payload payload) {
        status = payload;
        statusAt = Util.getMillis();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (payload.phase() == EarthStatus.PREPARING) {
                if (minecraft.screen instanceof EarthArrivalScreen waiting) waiting.update(payload);
                else if (minecraft.player != null) minecraft.setScreen(new EarthArrivalScreen(payload));
            } else if (minecraft.screen instanceof EarthArrivalScreen) {
                minecraft.setScreen(null);
            }
        });
    }

    @SubscribeEvent public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) { status = null; }

    /** Opens Create World on the Earth world type, unless the player is re-creating a world. */
    @SubscribeEvent public static void createWorld(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen) || preselected.get() == screen || !EarthConfig.PRESELECT_EARTH.get()) return;
        preselected = new WeakReference<>(screen);
        WorldCreationUiState state = screen.getUiState();
        var current = state.getWorldType().preset();
        if (current == null || !current.is(WorldPresets.NORMAL) || !state.getSeed().isEmpty()) return;
        for (WorldCreationUiState.WorldTypeEntry entry : state.getNormalPresetList())
            if (entry.preset() != null && entry.preset().is(EarthWorlds.PRESET)) { state.setWorldType(entry); break; }
    }

    /**
     * Far terrain sharpness on the pause menu in Earth worlds: NeoForge's full-width Mods button
     * gives up its right half to a button that cycles through the choices, in effect at once.
     */
    @SubscribeEvent public static void pauseMenu(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen) || ClientCubeWorld.current() == null) return;
        for (var listener : event.getListenersList()) {
            if (!(listener instanceof Button mods) || !(mods.getMessage().getContents() instanceof TranslatableContents text)
                    || !text.getKey().equals("fml.menu.mods")) continue;
            int half = (mods.getWidth() - 4) / 2;
            mods.setWidth(half);
            event.addListener(CycleButton.<EarthConfig.Sharpness>builder(EarthConfig.Sharpness::getTranslatedName)
                    .withValues(EarthConfig.Sharpness.values())
                    .withInitialValue(EarthConfig.FAR_SHARPNESS.get())
                    .withTooltip(value -> Tooltip.create(Component.translatable("earthplatform.pause.sharpness.tooltip")))
                    .create(mods.getX() + mods.getWidth() - half, mods.getY(), half, mods.getHeight(),
                            Component.translatable("earthplatform.pause.sharpness"), (button, value) -> EarthConfig.setSharpness(value)));
            return;
        }
    }

    /** A quiet note while Earth data downloads, and a clear one when it can't be reached. */
    @SubscribeEvent public static void hud(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        EarthStatus.Payload now = status;
        if (now == null || now.phase() != EarthStatus.PLAYING || minecraft.options.hideGui || !EarthConfig.SHOW_DOWNLOADS.get()) return;
        if (Util.getMillis() - statusAt > 12_000) return;
        GuiGraphics gui = event.getGuiGraphics();
        var font = minecraft.font;
        if (now.problem() == EarthStatus.UNREACHABLE) {
            Component line = Component.translatable("earthplatform.status.unreachable");
            Component hint = Component.translatable("earthplatform.status.unreachable.hint");
            int width = Math.max(font.width(line), font.width(hint)) + 12, x = (gui.guiWidth() - width) / 2;
            gui.fill(x, 4, x + width, 30, 0xA0000000);
            gui.drawCenteredString(font, line, gui.guiWidth() / 2, 8, 0xFFFFCC55);
            gui.drawCenteredString(font, hint, gui.guiWidth() / 2, 19, 0xFFCCCCCC);
        } else if (now.downloading() > 0 || now.recent() > 0) {
            Component line = Component.translatable("earthplatform.status.downloading", ".".repeat((int) (Util.getMillis() / 400 % 4)));
            // Top left: toasts take the top right, and the debug screen hides this anyway.
            if (!minecraft.getDebugOverlay().showDebugScreen()) gui.drawString(font, line, 6, 6, 0xFFBBBBBB);
        }
    }
}
