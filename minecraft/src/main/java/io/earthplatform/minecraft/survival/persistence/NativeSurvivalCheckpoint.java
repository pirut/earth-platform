package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;

/** Immutable complete checkpoint authority. Owner epochs are capture provenance, not persistent generations. */
public final class NativeSurvivalCheckpoint {
    public record Limits(int cubes, int items, int scheduledTicks, int actors, int payloadBytes) {
        public Limits {
            if (cubes < 1 || cubes > 4096 || items < 0 || items > 4096 || scheduledTicks < 0 || scheduledTicks > 4096
                    || actors < 1 || actors > 65536 || payloadBytes < 4096 || payloadBytes > 64 * 1024 * 1024)
                throw new IllegalArgumentException("Explicit bounded checkpoint admission required");
        }
    }
    /** saveId distinguishes two saves with otherwise identical seeds and generation inputs. */
    public record Binding(UUID saveId, String profileId, WorldIdentity world, String sourceIdentity,
            String registryIdentity, String dataPackIdentity, UUID player, ResourceLocation dimension) {
        public Binding {
            Objects.requireNonNull(saveId); Objects.requireNonNull(world); Objects.requireNonNull(player); Objects.requireNonNull(dimension);
            if (dimension.toString().length() > 1024) throw new IllegalArgumentException("Bounded checkpoint dimension required");
            for (String text : List.of(profileId, sourceIdentity, registryIdentity, dataPackIdentity))
                if (text.isBlank() || text.length() > 1024) throw new IllegalArgumentException("Bounded exact checkpoint identity required");
        }
    }
    public enum SessionPhase { ENTERING, ACTIVE, RETURNING, RETURNED }
    /**
     * Ordinary inventory, food and pose are explicit immutable escrow for this session.
     * RETURNED is a tombstone; its escrow must never overwrite subsequent ordinary state.
     */
    public record Session(UUID id, SessionPhase phase, NativeInventorySnapshot ordinaryEscrow, NativeFoodSnapshot ordinaryFood, NativePlayerPoseSnapshot ordinaryPose) {
        public Session { Objects.requireNonNull(id); Objects.requireNonNull(phase); Objects.requireNonNull(ordinaryEscrow); Objects.requireNonNull(ordinaryFood); Objects.requireNonNull(ordinaryPose); }
    }
    public record Cube(long simulationRevision, long durableRevision, ImmutableCube blocks) {
        public Cube {
            if (simulationRevision < 0 || durableRevision < 1) throw new IllegalArgumentException("Invalid recovered cube revisions");
            Objects.requireNonNull(blocks);
        }
    }
    public record Boundary(long gameTime, NativeInventorySnapshot inventory, NativeFoodSnapshot food, NativePlayerPoseSnapshot pose, List<NativeItemEntitySnapshot> items,
            List<NativeEffectCodec.ScheduledBlockTick> scheduledTicks) {
        public Boundary {
            Objects.requireNonNull(inventory); Objects.requireNonNull(food); Objects.requireNonNull(pose); Objects.requireNonNull(items); Objects.requireNonNull(scheduledTicks);
            if (gameTime < 0 || items.size() > 4096 || scheduledTicks.size() > 4096)
                throw new IllegalArgumentException("Bounded native checkpoint boundary required");
            // Admit cumulative immutable payload ownership before copying either collection.
            checkedBoundaryBytes(inventory, food, pose, items, scheduledTicks, 64 * 1024 * 1024);
            items = List.copyOf(items); scheduledTicks = List.copyOf(scheduledTicks);
            Set<UUID> ids = new HashSet<>();
            for (var item : items) if (!ids.add(item.id())) throw new IllegalArgumentException("Duplicate native item UUID");
            Set<TickKey> keys = new HashSet<>(); Set<Long> orders = new HashSet<>();
            for (var tick : scheduledTicks) {
                if (!keys.add(new TickKey(tick.cell(), tick.block()))) throw new IllegalArgumentException("Duplicate scheduled block tick");
                if (!orders.add(tick.subTickOrder()) || tick.subTickOrder() == Long.MAX_VALUE)
                    throw new IllegalArgumentException("Duplicate or exhausted scheduled tick order");
            }
        }
    }
    public static int checkedBoundaryBytes(NativeInventorySnapshot inventory, NativeFoodSnapshot food, NativePlayerPoseSnapshot pose, Collection<NativeItemEntitySnapshot> items,
            Collection<NativeEffectCodec.ScheduledBlockTick> ticks, int maximumBytes) {
        long bytes = 16L + Objects.requireNonNull(food).encodedBytes() + Objects.requireNonNull(pose).encodedBytes() + inventory.encodedBytes(); // game clock and both count fields
        for (var item : items) bytes += 4L + item.encodedBytes();
        for (var tick : ticks) bytes += tickBytes(tick);
        if (maximumBytes < 0 || bytes > maximumBytes) throw new SurvivalRefusal("Cumulative native checkpoint payload exceeds admission");
        return Math.toIntExact(bytes);
    }
    private static int tickBytes(NativeEffectCodec.ScheduledBlockTick tick) {
        return Math.addExact(46, tick.block().toString().length()); // ResourceLocation is restricted ASCII.
    }
    /** Preserve exact due-time offsets when the host restores into a different native game clock. */
    public List<NativeEffectCodec.ScheduledBlockTick> scheduledTicksAt(long targetGameTime) {
        if (targetGameTime < 0) throw new IllegalArgumentException("Nonnegative target game time required");
        long offset = Math.subtractExact(targetGameTime, boundary.gameTime());
        return boundary.scheduledTicks().stream().map(tick -> new NativeEffectCodec.ScheduledBlockTick(
                tick.cell(), tick.block(), Math.addExact(tick.triggerTick(), offset), tick.priority(), tick.subTickOrder())).toList();
    }
    public long nextScheduledTickOrder() {
        long maximum = -1;
        for (var tick : boundary.scheduledTicks()) maximum = Math.max(maximum, tick.subTickOrder());
        return Math.incrementExact(maximum);
    }
    private record TickKey(io.earthplatform.minecraft.survival.world.CubeCell cell, ResourceLocation block) {}
    private final Binding binding;
    private final UUID captureOwner;
    private final long generation, commitSequence;
    private final Map<UUID, Long> actorSequences;
    private final SortedMap<CubePos, Cube> cubes;
    private final Boundary boundary;
    private final Session session;
    public NativeSurvivalCheckpoint(Limits limits, Binding binding, UUID captureOwner, long generation, long commitSequence,
            Map<UUID, Long> actorSequences, Map<CubePos, Cube> cubes, Boundary boundary, Session session) {
        Objects.requireNonNull(limits); this.binding = Objects.requireNonNull(binding); this.captureOwner = Objects.requireNonNull(captureOwner);
        Objects.requireNonNull(actorSequences); Objects.requireNonNull(cubes); this.boundary = Objects.requireNonNull(boundary);
        this.session = Objects.requireNonNull(session);
        if (!binding.dimension().equals(boundary.pose().dimension())) throw new IllegalArgumentException("Earth pose must name the exact checkpoint carrier dimension");
        if (generation < 1 || commitSequence < 0 || actorSequences.size() > limits.actors() || cubes.size() > limits.cubes()
                || boundary.items().size() > limits.items() || boundary.scheduledTicks().size() > limits.scheduledTicks())
            throw new IllegalArgumentException("Checkpoint count/revision exceeds admission");
        for (var sequence : actorSequences.entrySet())
            if (sequence.getKey() == null || sequence.getValue() == null || sequence.getValue() < 1)
                throw new IllegalArgumentException("Invalid action high-water metadata");
        this.generation = generation; this.commitSequence = commitSequence;
        this.actorSequences = Map.copyOf(actorSequences);
        TreeMap<CubePos, Cube> copy = new TreeMap<>();
        cubes.forEach((position, value) -> copy.put(Objects.requireNonNull(position), Objects.requireNonNull(value)));
        this.cubes = Collections.unmodifiableSortedMap(copy);
    }
    public Binding binding() { return binding; }
    public UUID captureOwner() { return captureOwner; }
    public long generation() { return generation; }
    public long commitSequence() { return commitSequence; }
    public Map<UUID, Long> actorSequences() { return actorSequences; }
    public SortedMap<CubePos, Cube> cubes() { return cubes; }
    public Boundary boundary() { return boundary; }
    public Session session() { return session; }

    /**
     * Caller provides the complete admitted item/tick ownership sets, never a spatial query subset.
     * No menu close/drop callback occurs here; resolve those under the host's hold before capture.
     */
    public static Boundary captureBoundary(NativeSurvivalWorld world, Binding binding, Limits limits, ServerPlayer player,
            Collection<ItemEntity> admittedItems, Collection<NativeEffectCodec.ScheduledBlockTick> admittedTicks) {
        return captureBoundary(world, binding, limits, player, admittedItems, admittedTicks, limits.payloadBytes());
    }
    public static Boundary captureBoundary(NativeSurvivalWorld world, Binding binding, Limits limits, ServerPlayer player,
            Collection<ItemEntity> admittedItems, Collection<NativeEffectCodec.ScheduledBlockTick> admittedTicks, int maximumPayloadBytes) {
        world.checkOwner();
        if (maximumPayloadBytes < 1 || maximumPayloadBytes > limits.payloadBytes())
            throw new SurvivalRefusal("Invalid remaining boundary payload admission");
        if (!player.server.isSameThread() || !binding.world().equals(world.world()) || !binding.player().equals(player.getUUID())
                || !binding.dimension().equals(player.level().dimension().location()))
            throw new SurvivalRefusal("Checkpoint player/world/server binding mismatch");
        if (admittedItems.size() > limits.items() || admittedTicks.size() > limits.scheduledTicks())
            throw new SurvivalRefusal("Native boundary exceeds checkpoint count admission");
        long gameTime = player.level().getGameTime();
        NativeInventorySnapshot inventory = NativeInventorySnapshot.capture(player, maximumPayloadBytes);
        NativeFoodSnapshot food = NativeFoodSnapshot.capture(player.getFoodData());
        NativePlayerPoseSnapshot pose = NativePlayerPoseSnapshot.capture(player);
        long used = 16L + NativeFoodSnapshot.ENCODED_BYTES + pose.encodedBytes() + inventory.encodedBytes();
        for (var tick : admittedTicks) used += tickBytes(tick);
        if (used > maximumPayloadBytes) throw new SurvivalRefusal("Inventory/tick payload exceeds remaining checkpoint admission");
        List<ItemEntity> liveItems = List.copyOf(admittedItems);
        List<NativeEffectCodec.ScheduledBlockTick> frozenTicks = List.copyOf(admittedTicks);
        List<NativeItemEntitySnapshot> items = new ArrayList<>(admittedItems.size());
        for (ItemEntity item : liveItems) {
            if (item.level() != player.level()) throw new SurvivalRefusal("Foreign native item Level");
            var position = io.earthplatform.minecraft.survival.world.CubeCell.of(
                    (long) Math.floor(item.getX()), (long) Math.floor(item.getY()), (long) Math.floor(item.getZ()));
            if (!world.bounds().contains(position.cube())) throw new SurvivalRefusal("Native item outside admitted cubic world");
            long remaining = maximumPayloadBytes - used - Integer.BYTES;
            if (remaining < 1) throw new SurvivalRefusal("No remaining native item checkpoint payload budget");
            var snapshot = NativeItemEntitySnapshot.capture(item, (int) Math.min(NativeItemEntitySnapshot.MAX_BYTES, remaining));
            used += Integer.BYTES + snapshot.encodedBytes(); items.add(snapshot);
        }
        for (var tick : frozenTicks) {
            if (!world.bounds().contains(tick.cell().cube())) throw new SurvivalRefusal("Scheduled tick outside admitted cubic world");
        }
        if (!inventory.equals(NativeInventorySnapshot.capture(player, inventory.encodedBytes()))
                || !food.equals(NativeFoodSnapshot.capture(player.getFoodData()))
                || !pose.equals(NativePlayerPoseSnapshot.capture(player)) || gameTime != player.level().getGameTime()
                || !liveItems.equals(List.copyOf(admittedItems)) || !frozenTicks.equals(List.copyOf(admittedTicks)))
            throw new SurvivalRefusal("Live inventory, food, pose, clock or admitted roster changed during checkpoint capture");
        for (int i = 0; i < liveItems.size(); i++)
            if (!items.get(i).equals(NativeItemEntitySnapshot.capture(liveItems.get(i), items.get(i).encodedBytes())))
                throw new SurvivalRefusal("Live item changed during checkpoint capture");
        if (!inventory.equals(NativeInventorySnapshot.capture(player, inventory.encodedBytes()))
                || !food.equals(NativeFoodSnapshot.capture(player.getFoodData()))
                || !pose.equals(NativePlayerPoseSnapshot.capture(player)) || gameTime != player.level().getGameTime())
            throw new SurvivalRefusal("Live inventory, food, pose or clock changed during final checkpoint validation");
        return new Boundary(gameTime, inventory, food, pose, items, frozenTicks);
    }
}
