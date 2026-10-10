package io.earthplatform.minecraft;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class EarthPlatformClient {
    @SubscribeEvent public static void drawWindowLabel(RenderGuiEvent.Post event) {
        Minecraft game = Minecraft.getInstance();
        var state = WindowStatePayload.latest;
        if (game.player == null || game.level == null || state == null || game.options.hideGui
                || game.getDebugOverlay().showDebugScreen() || !game.level.dimension().location().equals(state.dimension())) return;
        var gui = event.getGuiGraphics();
        var label = EarthWindowHud.layout(gui.guiWidth(), gui.guiHeight(), game.font.lineHeight,
                game.font::width, (long) Math.floor(game.player.getY()), state.originY(), state.realTerrain(), state.terrainDataset());
        gui.fill(label.x(), label.y(), label.x() + label.width(), label.y() + label.height(), 0xD9101923);
        gui.enableScissor(label.x(), label.y(), label.x() + label.width(), label.y() + label.height());
        gui.drawString(game.font, label.coordinates(), label.x() + 5, label.y() + 5, 0xFFFFFF);
        gui.drawString(game.font, label.source(), label.x() + 5, label.y() + 8 + label.lineHeight(), 0xF2C46D);
        gui.disableScissor();
    }
}
