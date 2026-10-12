package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;

/** Native control data only. There is deliberately no public/live state installation API. */
public final class NativeMiningControlState {
    private NativeMiningControlState() {}

    /** Both flags and both targets are retained independently, including native int overflow. */
    public record Mode(boolean destroying, int started, CubeCell target, int ticks,
                boolean delayed, CubeCell delayedTarget, int delayedStarted, int lastSentStage) {
        public Mode {
            if (target == null || delayedTarget == null) throw new IllegalArgumentException("Native mining positions are never null");
        }
        static Mode capture(ServerPlayerGameMode mode) {
            requireMode(mode);
            return new Mode(read(mode,"isDestroyingBlock",boolean.class), read(mode,"destroyProgressStart",int.class),
                    cell(read(mode,"destroyPos",BlockPos.class)), read(mode,"gameTicks",int.class),
                    read(mode,"hasDelayedDestroy",boolean.class), cell(read(mode,"delayedDestroyPos",BlockPos.class)),
                    read(mode,"delayedTickStart",int.class), read(mode,"lastSentState",int.class));
        }
        /** Source-qualified constructor plus data hydration only; never replaces player.gameMode. */
        ServerPlayerGameMode createScratch(ServerPlayer player, ServerLevel level, GameType current, GameType previous) {
            NativeMiningControlPins.require(ServerPlayerGameMode.class);
            NativeMiningControlPins.require(ServerPlayer.class);
            if (player == null || player.getClass() != ServerPlayer.class || player.serverLevel() != level
                    || !player.server.isSameThread() || player.gameMode.getClass() != ServerPlayerGameMode.class)
                throw refused("Native mining scratch constructor lacks exact owner/player/level");
            // Pinned constructor assigns only final player and level=player.serverLevel(). It posts no events.
            var scratch = new ServerPlayerGameMode(player);
            if (scratch == player.gameMode || read(scratch,"player",ServerPlayer.class) != player
                    || read(scratch,"level",ServerLevel.class) != level)
                throw refused("Native mining scratch constructor changed its exact field graph");
            write(scratch,"gameModeForPlayer",GameType.class,current);
            write(scratch,"previousGameModeForPlayer",GameType.class,previous);
            write(scratch,"isDestroyingBlock",boolean.class,destroying);
            write(scratch,"destroyProgressStart",int.class,started);
            write(scratch,"destroyPos",BlockPos.class,pos(target));
            write(scratch,"gameTicks",int.class,ticks);
            write(scratch,"hasDelayedDestroy",boolean.class,delayed);
            write(scratch,"delayedDestroyPos",BlockPos.class,pos(delayedTarget));
            write(scratch,"delayedTickStart",int.class,delayedStarted);
            write(scratch,"lastSentState",int.class,lastSentStage);
            if (!equals(capture(scratch)) || scratch.getGameModeForPlayer() != current
                    || scratch.getPreviousGameModeForPlayer() != previous)
                throw refused("Native mining scratch hydration changed its captured state");
            return scratch;
        }
    }
    public record Prefix(int acknowledgement, long lastActionTime) {
        static Prefix capture(ServerPlayer player) {
            if (player == null || player.getClass() != ServerPlayer.class || player.connection == null
                    || player.connection.getClass() != ServerGamePacketListenerImpl.class || player.connection.player != player)
                throw refused("Native mining prefix receiver identity changed");
            return new Prefix(NativeSessionMenuBoundary.read(ServerGamePacketListenerImpl.class,player.connection,
                    "ackBlockChangesUpTo",int.class), NativeSessionMenuBoundary.read(ServerPlayer.class,player,"lastActionTime",long.class));
        }
    }
    /** Pure read-only settled predicate. It does not close or normalize native delayed progress. */
    static boolean settledForCheckpoint(Mode mode,Prefix prefix) {
        return !mode.destroying()&&!mode.delayed()&&prefix.acknowledgement()==-1;
    }
    static void requireMode(ServerPlayerGameMode mode) {
        if (mode == null || mode.getClass() != ServerPlayerGameMode.class)
            throw refused("Native mining requires exact original GameMode class");
        NativeMiningControlPins.require(ServerPlayerGameMode.class);
    }
    static <T> T read(ServerPlayerGameMode mode,String name,Class<T> type) {
        return NativeSessionMenuBoundary.read(ServerPlayerGameMode.class,mode,name,type);
    }
    private static void write(ServerPlayerGameMode mode,String name,Class<?> type,Object value) {
        NativeSessionMenuBoundary.write(ServerPlayerGameMode.class,mode,name,type,value);
    }
    static CubeCell cell(BlockPos pos) {
        if (pos == null || pos.getClass() != BlockPos.class) throw refused("Native mining position is not immutable exact BlockPos");
        return CubeCell.of(pos.getX(),pos.getY(),pos.getZ());
    }
    static BlockPos pos(CubeCell cell) {
        return new BlockPos(Math.toIntExact(cell.x()),Math.toIntExact(cell.y()),Math.toIntExact(cell.z()));
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
