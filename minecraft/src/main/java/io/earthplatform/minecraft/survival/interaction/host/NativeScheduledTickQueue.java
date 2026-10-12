package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

/**
 * The cubic session's bounded queue of genuine native ScheduledTick values. Uses vanilla drain
 * ordering and (block identity, full BlockPos) first-schedule-wins uniqueness. Carrier chunk tick
 * containers remain empty. This queue does not run callbacks, advance time, or substitute drops.
 * Tick execution/dequeue requires a separately admitted whole-action dispatcher, not poll().
 */
public final class NativeScheduledTickQueue {
    private record Key(CubeCell cell, Block block) {}
    private final NativeSurvivalWorld world;
    private final int capacity;
    private final Map<Key, ScheduledTick<Block>> ticks = new LinkedHashMap<>();
    private long revision;

    public NativeScheduledTickQueue(NativeSurvivalWorld world, int capacity) {
        this.world = java.util.Objects.requireNonNull(world); world.checkOwner();
        if (capacity < 1 || capacity > 4096) throw new IllegalArgumentException("Bounded native tick capacity required");
        this.capacity = capacity;
    }
    public int size() { world.checkOwner(); return ticks.size(); }

    /** No reservation, dequeue or clock mutation: preparation owns its local staged offsets. */
    public long nextAvailableScheduledOrder() {
        world.checkOwner();
        long maximum = -1;
        for (var tick : ticks.values()) maximum = Math.max(maximum, tick.subTickOrder());
        if (maximum >= Long.MAX_VALUE - 1)
            throw new SurvivalRefusal("Native scheduled tick order is exhausted before checkpoint");
        return maximum + 1;
    }

    public Prepared prepare(List<NativeEffectCodec.ScheduledBlockTick> effects, long gameTime, NativeSurvivalWorld.ActionIdentity identity) {
        world.checkOwner();
        if (identity == null || !identity.ownerEpoch().equals(world.ownerEpoch()) || !world.hasActiveTransaction()
                || gameTime < 0 || effects.size() > world.limits().effects() || revision == Long.MAX_VALUE)
            throw new SurvivalRefusal("Native tick preparation count/clock/revision is unavailable");
        Map<Key, ScheduledTick<Block>> added = new LinkedHashMap<>();
        java.util.Set<Long> orders = new java.util.HashSet<>();
        for (var tick : ticks.values()) orders.add(tick.subTickOrder());
        for (var effect : effects) {
            Block block = BuiltInRegistries.BLOCK.getOptional(effect.block())
                    .orElseThrow(() -> new SurvivalRefusal("Unknown native scheduled block"));
            if (block != Blocks.OAK_LEAVES) throw new SurvivalRefusal("Only native oak-leaf scheduled ticks are admitted");
            world.palette().id(block.defaultBlockState());
            // Match LevelChunkTicks.schedule: an existing position/type keeps its first tick.
            Key key = new Key(effect.cell(), block);
            if (ticks.containsKey(key) || added.containsKey(key)) continue;
            if (effect.subTickOrder() == Long.MAX_VALUE || !orders.add(effect.subTickOrder()))
                throw new SurvivalRefusal("Native tick order is duplicate or exhausted before checkpoint");
            if (effect.triggerTick() < gameTime) throw new SurvivalRefusal("New native tick trigger is already past");
            BlockPos pos = nativePosition(effect.cell());
            NativeEffectDeliveryHost.requireCapacity(ticks.size(), added.size() + 1, capacity, "native scheduled ticks");
            added.put(key, new ScheduledTick<>(block, pos, effect.triggerTick(),
                    TickPriority.byValue(effect.priority()), effect.subTickOrder()));
        }
        return new Prepared(identity, world.commitSequence(), revision, added);
    }

    public final class Prepared {
        private final NativeSurvivalWorld.ActionIdentity identity;
        private final long beforeCommit, beforeRevision;
        private final Map<Key, ScheduledTick<Block>> added;
        private boolean attempted;
        private Prepared(NativeSurvivalWorld.ActionIdentity identity, long beforeCommit, long beforeRevision, Map<Key, ScheduledTick<Block>> added) {
            this.identity = identity; this.beforeCommit = beforeCommit; this.beforeRevision = beforeRevision;
            this.added = new LinkedHashMap<>(added);
        }
        public int newTickCount() { return added.size(); }
        public List<NativeEffectCodec.ScheduledBlockTick> projectedSnapshot() {
            world.checkOwner();
            if (attempted || revision != beforeRevision) throw new SurvivalRefusal("Native tick projection is no longer current");
            var combined = new LinkedHashMap<Key, ScheduledTick<Block>>(ticks); combined.putAll(added);
            return snapshotOf(combined.values());
        }
        public int pendingTickCount() {
            world.checkOwner(); int result = 0;
            for (Key key : added.keySet()) if (!ticks.containsKey(key)) result++;
            return result;
        }
        public void apply(NativeSurvivalWorld.CommitReceipt receipt) {
            world.checkOwner();
            if (attempted || receipt == null || !receipt.action().equals(identity) || beforeRevision != revision || receipt.commitSequence() != beforeCommit + 1
                    || receipt.commitSequence() != world.commitSequence() || !receipt.world().equals(world.world())
                    || !receipt.ownerEpoch().equals(world.ownerEpoch()))
                throw new SurvivalRefusal("Native scheduled-tick batch is stale/foreign/already attempted");
            attempted = true;
            ticks.putAll(added); revision++;
        }
    }

    public List<NativeEffectCodec.ScheduledBlockTick> snapshot() {
        world.checkOwner();
        return snapshotOf(ticks.values());
    }
    private static List<NativeEffectCodec.ScheduledBlockTick> snapshotOf(java.util.Collection<ScheduledTick<Block>> values) {
        PriorityQueue<ScheduledTick<Block>> ordered = new PriorityQueue<>(ScheduledTick.DRAIN_ORDER);
        ordered.addAll(values); var result = new ArrayList<NativeEffectCodec.ScheduledBlockTick>();
        while (!ordered.isEmpty()) result.add(effect(ordered.remove()));
        return List.copyOf(result);
    }
    /** Read-only due selection. Never remove a tick before its whole action succeeds. */
    public NativeEffectCodec.ScheduledBlockTick nextDue(long gameTime) {
        world.checkOwner(); if (gameTime < 0) throw new IllegalArgumentException("Nonnegative native game time required");
        ScheduledTick<Block> first = null;
        for (var tick : ticks.values())
            if (first == null || ScheduledTick.DRAIN_ORDER.compare(tick, first) < 0) first = tick;
        return first == null || first.triggerTick() > gameTime ? null : effect(first);
    }
    private static NativeEffectCodec.ScheduledBlockTick effect(ScheduledTick<Block> tick) {
        return new NativeEffectCodec.ScheduledBlockTick(CubeCell.of(tick.pos().getX(), tick.pos().getY(), tick.pos().getZ()),
                BuiltInRegistries.BLOCK.getKey(tick.type()), tick.triggerTick(), tick.priority().getValue(), tick.subTickOrder());
    }
    static BlockPos nativePosition(CubeCell cell) {
        try { return new BlockPos(Math.toIntExact(cell.x()), Math.toIntExact(cell.y()), Math.toIntExact(cell.z())); }
        catch (ArithmeticException invalid) { throw new SurvivalRefusal("Native scheduled tick exceeds signed BlockPos coordinates"); }
    }
}
