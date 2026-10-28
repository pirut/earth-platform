package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.cubic.play.EarthConfig;
import io.earthplatform.minecraft.cubic.play.EarthStatus;
import io.earthplatform.minecraft.cubic.play.EarthWorlds;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** The Earth's screens: world setup, the arrival wait, download notes and the config screen. */
public final class EarthPlayClient {
    private static volatile EarthStatus.Payload status;
    private static volatile long statusAt;
    private static WeakReference<CreateWorldScreen> preselected = new WeakReference<>(null);

    private EarthPlayClient() {}

    /** The Earth world type's Customize button opens the place picker. */
    public static Screen presetEditor(Screen parent) { return new EarthSetupScreen(parent); }

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

    public static void loggedOut() { status = null; }

    /** Opens Create World on the Earth world type, unless the player is re-creating a world. */
    public static void createWorld(Screen opened) {
        if (!(opened instanceof CreateWorldScreen screen) || preselected.get() == screen || !EarthConfig.PRESELECT_EARTH.get()) return;
        preselected = new WeakReference<>(screen);
        WorldCreationUiState state = screen.getUiState();
        var current = state.getWorldType().preset();
        if (current == null || !current.is(WorldPresets.NORMAL) || !state.getSeed().isEmpty()) return;
        for (WorldCreationUiState.WorldTypeEntry entry : state.getNormalPresetList())
            if (entry.preset() != null && entry.preset().is(EarthWorlds.PRESET)) { state.setWorldType(entry); break; }
    }

    /**
     * Far terrain settings on the pause menu in Earth worlds: the full-width Mods button (NeoForge's,
     * or Mod Menu's on Fabric) gives up its right half to a button that cycles through the sharpness
     * choices, in effect at once, and a row under it holds the far distance slider, the buttons below
     * moving down for it.
     */
    public static void pauseMenu(Screen screen, List<? extends GuiEventListener> listeners, Consumer<AbstractWidget> add) {
        if (!(screen instanceof PauseScreen) || ClientCubeWorld.current() == null) return;
        for (var listener : listeners) {
            if (!(listener instanceof Button mods) || !(mods.getMessage().getContents() instanceof TranslatableContents text)
                    || !(text.getKey().equals("fml.menu.mods") || text.getKey().equals("modmenu.title"))) continue;
            int full = mods.getWidth(), half = (full - 4) / 2;
            mods.setWidth(half);
            add.accept(CycleButton.<EarthConfig.Sharpness>builder(EarthConfig.Sharpness::getTranslatedName)
                    .withValues(EarthConfig.Sharpness.values())
                    .withInitialValue(EarthConfig.FAR_SHARPNESS.get())
                    .withTooltip(value -> Tooltip.create(Component.translatable("earthplatform.pause.sharpness.tooltip")))
                    .create(mods.getX() + full - half, mods.getY(), half, mods.getHeight(),
                            Component.translatable("earthplatform.pause.sharpness"), (button, value) -> EarthConfig.setSharpness(value)));
            int row = mods.getHeight() + 4;
            for (var other : listeners)
                if (other instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.getY() > mods.getY()) widget.setY(widget.getY() + row);
            add.accept(new FarDistanceSlider(mods.getX(), mods.getY() + row, full, mods.getHeight()));
            return;
        }
    }

    /** A quiet note while Earth data downloads, and a clear one when it can't be reached. */
    public static void hud(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        EarthStatus.Payload now = status;
        if (now == null || now.phase() != EarthStatus.PLAYING || minecraft.options.hideGui || !EarthConfig.SHOW_DOWNLOADS.get()) return;
        if (Util.getMillis() - statusAt > 12_000) return;
        GuiGraphics gui = graphics;
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
