package io.earthplatform.minecraft.cubic.net;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;

/**
 * Paces terrain to a player on a real network connection, so cubes and far columns never queue
 * up ahead of movement, chat and block changes. A player in the same process (singleplayer, or
 * the host of a LAN world) is never held back.
 */
public final class Throttle {
    /** Terrain bytes a remote player may be sent per tick, about 5 MB/s; -Dearthplatform.cubic.bytesPerTick overrides it. */
    private static final int BYTES_PER_TICK = Integer.getInteger("earthplatform.cubic.bytesPerTick", 256 * 1024);

    private Throttle() {}

    /** Bytes of terrain that may go to this player this tick: none while its connection is still sending earlier ones. */
    public static int allowance(ServerPlayer player) {
        Connection connection = player.connection == null ? null : player.connection.getConnection();
        if (connection == null || connection.isMemoryConnection()) return Integer.MAX_VALUE;
        return connection.channel() != null && connection.channel().isWritable() ? BYTES_PER_TICK : 0;
    }
}
