package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Warns once per game launch when Java was given too little memory for Earth: far terrain, the
 * cube cache and the downloads then fight the garbage collector, and the game stutters or runs
 * out. A dedicated server says so in its log at startup; a singleplayer game also tells its owner
 * in chat.
 */
public final class EarthMemory {
    /** Below this the game runs short; the install guide asks for 4 GB, and launchers round 4 GB a little down. */
    private static final long LEAST_MIB = 3584;
    private static final String HELP = "https://github.com/pirut/earth-platform/blob/main/docs/INSTALL.md";

    private static final AtomicBoolean warned = new AtomicBoolean();

    private EarthMemory() {}

    public static void started(MinecraftServer server) {
        if (server.isDedicatedServer() && EarthWorlds.isEarth(server) && low() && warned.compareAndSet(false, true)) log();
    }

    public static void loggedIn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || server.isDedicatedServer() || !server.isSingleplayerOwner(player.getGameProfile())) return;
        if (!EarthWorlds.isEarth(server) || !low() || !warned.compareAndSet(false, true)) return;
        log();
        player.sendSystemMessage(Component.literal("Earth Platform has only " + gb() + " GB of memory, so it may stutter or crash. Give the game 4 GB, or 6 GB with shaders. ")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("[How]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, HELP))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(HELP))))));
    }

    private static boolean low() { return mib() < LEAST_MIB; }

    private static long mib() { return Runtime.getRuntime().maxMemory() >> 20; }

    private static String gb() { return String.format(Locale.ROOT, "%.1f", mib() / 1024.0); }

    private static void log() {
        EarthPlatformMod.LOGGER.warn("Only {} MB of memory (-Xmx) for Earth Platform; give the game at least 4 GB, or 6 GB with shaders ({})", mib(), HELP);
    }
}
