package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.cubic.play.EarthStatus;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/** Shown while the server fetches the Earth around the world's home, before the first arrival. */
public final class EarthArrivalScreen extends Screen {
    private EarthStatus.Payload status;

    EarthArrivalScreen(EarthStatus.Payload status) {
        super(Component.translatable("earthplatform.arrival.title"));
        this.status = status;
    }

    void update(EarthStatus.Payload status) { this.status = status; }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("earthplatform.arrival.leave"), button -> leave())
                .bounds(width / 2 - 100, height - 40, 200, 20).build());
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        int y = height / 2 - 50;
        gui.drawCenteredString(font, title, width / 2, y, 0xFFFFFF);
        if (!status.place().isEmpty()) gui.drawCenteredString(font, Component.literal(status.place()), width / 2, y + 16, 0x9FD7FF);
        Component line, detail;
        int colour = 0xCCCCCC;
        switch (status.problem()) {
            case EarthStatus.UNREACHABLE -> {
                line = Component.translatable("earthplatform.arrival.unreachable");
                detail = Component.translatable("earthplatform.arrival.unreachable.hint");
                colour = 0xFFCC55;
            }
            case EarthStatus.OFFLINE_MODE -> {
                line = Component.translatable("earthplatform.arrival.offlineMode");
                detail = Component.translatable("earthplatform.arrival.offlineMode.hint");
                colour = 0xFFCC55;
            }
            default -> {
                line = Component.translatable("earthplatform.arrival.downloading", ".".repeat((int) (Util.getMillis() / 400 % 4)));
                detail = status.waitedSeconds() >= 20 ? Component.translatable("earthplatform.arrival.slow")
                        : Component.translatable("earthplatform.arrival.firstVisit");
            }
        }
        gui.drawCenteredString(font, line, width / 2, y + 40, colour);
        int lineY = y + 56;
        for (var text : font.split(detail, 300)) { gui.drawCenteredString(font, text, width / 2, lineY, 0xAAAAAA); lineY += 11; }
        if (status.waitedSeconds() > 0)
            gui.drawCenteredString(font, Component.translatable("earthplatform.arrival.waited", status.waitedSeconds()), width / 2, lineY + 8, 0x777777);
    }

    private void leave() {
        boolean local = minecraft.isLocalServer();
        var server = minecraft.getCurrentServer();
        if (minecraft.level != null) minecraft.level.disconnect();
        if (local) minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        else minecraft.disconnect();
        TitleScreen title = new TitleScreen();
        minecraft.setScreen(local || server == null ? title : new JoinMultiplayerScreen(title));
    }
}
