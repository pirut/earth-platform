package io.earthplatform.minecraft.cubic.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-only bridge; the static gate survives world changes for the entire JVM session. */
final class FarTerrainNotices {
    private static final FarTerrainNotice NOTICE = new FarTerrainNotice();

    private FarTerrainNotices() {}

    static void request(FarTerrainNotice.Reason reason) { NOTICE.request(reason); }

    /** Called by the shared client tick on both loaders, including before joining a world. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        FarTerrainNotice.Reason reason = NOTICE.take(mc.gui != null && mc.player != null);
        if (reason != null) mc.gui.getChat().addMessage(Component.translatable(reason.translationKey).withStyle(ChatFormatting.YELLOW));
    }
}
