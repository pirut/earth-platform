package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client side of other mods' support in the cubic world: map snapshots, and pausing Distant Horizons there. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class CubicCompat {
    private static final boolean DH = CubicMaps.loaded("distanthorizons");
    private static boolean inCubic, dhSettled = true, toldAboutDh;

    private CubicCompat() {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean now = minecraft.level != null && Cubic.isCubic(minecraft.level.dimension());
        if (now != inCubic) {
            inCubic = now;
            dhSettled = !DH;
            if (!now) CubicMaps.leave();
        }
        if (!dhSettled) {
            dhSettled = DhCompat.update(now);
            if (dhSettled && now && !toldAboutDh && minecraft.player != null) {
                toldAboutDh = true;
                minecraft.player.displayClientMessage(Component.literal(DhCompat.paused()
                        ? "Distant Horizons is paused in the Earth world, which draws its own distant terrain. It runs as usual in other dimensions."
                        : "This Distant Horizons version can't be paused automatically. Turn its rendering off while you're in the Earth world, which draws its own distant terrain.")
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world != null) CubicMaps.tick(world);
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        inCubic = false;
        CubicMaps.leave();
        if (DH) dhSettled = DhCompat.update(false);
    }
}
