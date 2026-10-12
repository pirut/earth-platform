package io.earthplatform.minecraft.survival.world;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSurvivalWorldTest {
    private static SurvivalPalette palette;
    private static WorldIdentity identity;
    private static final CubeCell HIGH = CubeCell.of(-19344520, 7314, -4297490);
    private static final UUID ACTOR = new UUID(3, 9);

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        var context = EnginePaletteContext.create(registry.freeze());
        palette = new SurvivalPalette(context);
        identity = new WorldIdentity(39, "bounded-native-survival", "first039", "marin-test",
                NativeCubeCodec.paletteIdentity(context));
    }
    private static NativeSurvivalWorld world() {
        return world(new NativeSurvivalWorld.Limits(32, 64, 8, 16, 4096, 128, 16, 65536, 16, 64));
    }
    private static NativeSurvivalWorld world(NativeSurvivalWorld.Limits limits) {
        return new NativeSurvivalWorld(identity, palette,
                new NativeSurvivalWorld.Bounds(new CubePos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                        new CubePos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)), limits);
    }
    private static ImmutableCube uniform(BlockState state) { return ImmutableCube.uniform(palette.id(state)); }
    private static void air(NativeSurvivalWorld owner, CubePos position) { owner.admitGenerated(position, uniform(Blocks.AIR.defaultBlockState())); }
    private static NativeSurvivalWorld.ActionTransaction begin(NativeSurvivalWorld owner, long sequence, CubePos... cubes) {
        Map<CubePos, NativeSurvivalWorld.CubeStamp> expected = new HashMap<>();
        for (CubePos cube : cubes) expected.put(cube, owner.stamp(cube));
        return owner.begin(owner.actionIdentity(ACTOR, sequence), expected);
    }
    private static NativeSurvivalWorld.CommitReceipt set(NativeSurvivalWorld owner, long sequence, CubeCell cell, BlockState state) {
        try (var tx = begin(owner, sequence, cell.cube())) { tx.set(cell, state); return tx.commit(); }
    }

    @Test void nextSequenceIsReadOnlyIncludesAbortAndRefusesExhaustion() throws Exception {
        var owner = world(); air(owner, HIGH.cube());
        assertEquals(1, owner.nextActionSequence(ACTOR));
        assertEquals(1, owner.nextActionSequence(ACTOR)); assertEquals(0, owner.actorCount());
        try (var ignored = begin(owner, 7, HIGH.cube())) { assertEquals(8, owner.nextActionSequence(ACTOR)); }
        assertEquals(8, owner.nextActionSequence(ACTOR)); assertEquals(0, owner.commitSequence());
        try (var ignored = begin(owner, Long.MAX_VALUE, HIGH.cube())) { }
        assertThrows(SurvivalRefusal.class, () -> owner.nextActionSequence(ACTOR));
        var error = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> { try { owner.nextActionSequence(ACTOR); } catch (Throwable caught) { error.set(caught); } });
        thread.start(); thread.join(); assertInstanceOf(SurvivalRefusal.class, error.get());
    }

    @Test void highYAndEvery2048And4096AliasRemainDistinct() {
        var owner = world();
        List<CubeCell> addresses = new ArrayList<>();
        addresses.add(HIGH);
        for (long offset : new long[]{-4096, -2048, 2048, 4096}) {
            addresses.add(CubeCell.of(HIGH.x(), HIGH.y() + offset, HIGH.z()));
            addresses.add(CubeCell.of(HIGH.x() + offset, HIGH.y(), HIGH.z()));
            addresses.add(CubeCell.of(HIGH.x(), HIGH.y(), HIGH.z() + offset));
        }
        for (CubeCell cell : addresses) air(owner, cell.cube());
        var initial = owner.snapshot(HIGH.cube());
        var receipt = set(owner, 1, HIGH, Blocks.CRAFTING_TABLE.defaultBlockState());
        assertSame(Blocks.CRAFTING_TABLE.defaultBlockState(), owner.read(HIGH));
        for (CubeCell alias : addresses.subList(1, addresses.size())) {
            assertSame(Blocks.AIR.defaultBlockState(), owner.read(alias));
            assertEquals(0, owner.snapshot(alias.cube()).simulationRevision());
        }
        assertEquals(7314, HIGH.y());
        assertEquals(1, receipt.changedCubes().size());
        assertEquals(0, initial.simulationRevision());
        assertEquals(palette.id(Blocks.AIR.defaultBlockState()), initial.blocks().get(HIGH.localIndex()));
        assertEquals(1, owner.snapshot(HIGH.cube()).simulationRevision());
        assertEquals(0, owner.snapshot(HIGH.cube()).durableRevision());
        assertTrue(owner.snapshot(HIGH.cube()).dirty());
    }

    @Test void negativeCubeBordersAndFullSignedCubeCoordinatesRoundTrip() {
        for (long x : new long[]{-17, -16, -1, 0, 15, 16, (long) Integer.MIN_VALUE * 16,
                (long) Integer.MAX_VALUE * 16 + 15}) {
            for (long y : new long[]{-4097, -2048, -17, -16, -1, 7314, (long) Integer.MIN_VALUE * 16,
                    (long) Integer.MAX_VALUE * 16 + 15}) {
                CubeCell cell = CubeCell.of(x, y, -17);
                assertEquals(x, cell.x()); assertEquals(y, cell.y()); assertEquals(-17, cell.z());
                assertEquals(CubePos.indexOfBlock(x, y, -17), cell.localIndex());
            }
        }
        var owner = world();
        CubeCell left = CubeCell.of(-17, -17, -17), right = CubeCell.of(-16, -16, -16);
        air(owner, left.cube()); air(owner, right.cube());
        try (var tx = begin(owner, 1, left.cube(), right.cube())) {
            tx.set(left, Blocks.DIRT.defaultBlockState()); tx.set(right, Blocks.COBBLESTONE.defaultBlockState()); tx.commit();
        }
        assertSame(Blocks.DIRT.defaultBlockState(), owner.read(left));
        assertSame(Blocks.COBBLESTONE.defaultBlockState(), owner.read(right));
        assertThrows(ArithmeticException.class, () -> CubeCell.of(Long.MAX_VALUE, 0, 0));
    }

    @Test void transactionReadsOwnWritesAndNativeNeighborReadsJoinCompleteReadSet() {
        var owner = world(); air(owner, HIGH.cube());
        CubeCell next = CubeCell.of(HIGH.x() + 1, HIGH.y(), HIGH.z());
        var before = owner.snapshot(HIGH.cube());
        var effectBytes = new byte[]{1, 2, 3};
        var effect = new NativeActionEffect("inventory_slot_v1", effectBytes);
        effectBytes[0] = 9;
        NativeSurvivalWorld.CommitReceipt receipt;
        try (var tx = begin(owner, 1, HIGH.cube())) {
            assertSame(Blocks.AIR.defaultBlockState(), tx.set(HIGH, Blocks.OAK_LOG.defaultBlockState()));
            assertSame(Blocks.OAK_LOG.defaultBlockState(), tx.read(HIGH));
            assertSame(Blocks.OAK_LOG.defaultBlockState(), owner.read(HIGH));
            assertSame(Blocks.AIR.defaultBlockState(), owner.read(next));
            assertEquals(Set.of(HIGH, next), tx.readSet());
            assertThrows(SurvivalRefusal.class, () -> owner.snapshot(HIGH.cube()));
            tx.stage(effect);
            assertEquals(1, tx.writeCount()); assertEquals(1, tx.effectCount());
            assertEquals(0, before.simulationRevision());
            receipt = tx.commit();
            assertThrows(SurvivalRefusal.class, tx::commit);
            assertThrows(SurvivalRefusal.class, () -> tx.set(HIGH, Blocks.STONE.defaultBlockState()));
        }
        assertEquals(1, receipt.commitSequence());
        assertEquals(owner.ownerEpoch(), receipt.ownerEpoch());
        assertEquals(identity, receipt.world());
        assertArrayEquals(new byte[]{1, 2, 3}, receipt.effects().getFirst().payload());
        byte[] exposed = receipt.effects().getFirst().payload(); exposed[0] = 8;
        assertEquals(1, receipt.effects().getFirst().payload()[0]);
        assertThrows(UnsupportedOperationException.class, () -> receipt.effects().clear());
        assertThrows(UnsupportedOperationException.class, () -> receipt.changedCubes().clear());
        assertEquals(palette.id(Blocks.AIR.defaultBlockState()), before.blocks().get(HIGH.localIndex()));
    }

    @Test void closeAbortAndUnsupportedNativeStateDiscardAllWritesAndEffects() {
        var owner = world(); air(owner, HIGH.cube());
        var original = owner.snapshot(HIGH.cube());
        try (var tx = begin(owner, 1, HIGH.cube())) {
            tx.set(HIGH, Blocks.DIRT.defaultBlockState());
            tx.stage(new NativeActionEffect("item_spawn_v1", new byte[]{1}));
        }
        assertEquals(original, owner.snapshot(HIGH.cube()));
        assertThrows(SurvivalRefusal.class, () -> begin(owner, 1, HIGH.cube()));
        try (var tx = begin(owner, 2, HIGH.cube())) {
            tx.set(HIGH, Blocks.STONE.defaultBlockState());
            tx.stage(new NativeActionEffect("inventory_slot_v1", new byte[]{2}));
            assertThrows(SurvivalRefusal.class, () -> tx.set(HIGH, Blocks.WATER.defaultBlockState()));
            assertEquals(NativeSurvivalWorld.TransactionState.ABORTED, tx.state());
            assertThrows(SurvivalRefusal.class, tx::commit);
        }
        assertEquals(original, owner.snapshot(HIGH.cube())); assertEquals(0, owner.commitSequence());
        assertFalse(owner.hasActiveTransaction());
    }

    @Test void unknownRetiredAndUndeclaredNeighborsRefuseRatherThanInventAir() {
        var owner = world(); air(owner, HIGH.cube());
        CubeCell neighbor = new CubeCell(HIGH.cube().offset(1, 0, 0), 0);
        assertThrows(SurvivalRefusal.class, () -> owner.read(neighbor));
        air(owner, neighbor.cube());
        try (var tx = begin(owner, 1, HIGH.cube())) {
            tx.set(HIGH, Blocks.DIRT.defaultBlockState());
            assertThrows(SurvivalRefusal.class, () -> owner.read(neighbor));
            assertEquals(NativeSurvivalWorld.TransactionState.ABORTED, tx.state());
        }
        assertSame(Blocks.AIR.defaultBlockState(), owner.read(HIGH));
        owner.retire(neighbor.cube());
        assertEquals(NativeSurvivalWorld.Presence.RETIRED_OWNED, owner.presence(neighbor.cube()));
        assertThrows(SurvivalRefusal.class, () -> owner.read(neighbor));
        assertThrows(SurvivalRefusal.class, () -> begin(owner, 2, neighbor.cube()));
        owner.reload(neighbor.cube()); assertSame(Blocks.AIR.defaultBlockState(), owner.read(neighbor));
    }

    @Test void staleRevisionReincarnationForeignOwnerAndRepeatedIdentityCannotCommit() {
        var owner = world(); air(owner, HIGH.cube());
        var old = owner.stamp(HIGH.cube());
        set(owner, 1, HIGH, Blocks.STONE.defaultBlockState());
        assertThrows(SurvivalRefusal.class, () -> owner.begin(owner.actionIdentity(ACTOR, 2), Map.of(HIGH.cube(), old)));
        var ticket = owner.freezeCheckpoint(Set.of(HIGH.cube())); owner.acknowledgeCheckpoint(ticket);
        var saved = owner.snapshot(HIGH.cube()); owner.releaseClean(HIGH.cube());
        owner.admitRecovered(HIGH.cube(), saved.blocks(), saved.simulationRevision(), saved.durableRevision());
        assertNotEquals(saved.incarnation(), owner.snapshot(HIGH.cube()).incarnation());
        assertThrows(SurvivalRefusal.class, () -> owner.begin(owner.actionIdentity(ACTOR, 2), Map.of(HIGH.cube(), saved.stamp())));
        assertThrows(SurvivalRefusal.class, () -> begin(owner, 1, HIGH.cube()));
        var other = world(); air(other, HIGH.cube());
        assertThrows(SurvivalRefusal.class, () -> other.begin(owner.actionIdentity(ACTOR, 1), Map.of(HIGH.cube(), old)));
        assertEquals(0, other.actorCount());
    }

    @Test void reentrantActionsAndCrossThreadMutationRefuse() throws Exception {
        var owner = world(); air(owner, HIGH.cube());
        Map<CubePos, NativeSurvivalWorld.CubeStamp> expected = Map.of(HIGH.cube(), owner.stamp(HIGH.cube()));
        try (var tx = owner.begin(owner.actionIdentity(ACTOR, 1), expected)) {
            assertThrows(SurvivalRefusal.class, () -> owner.begin(owner.actionIdentity(ACTOR, 2), expected));
            assertThrows(SurvivalRefusal.class, () -> owner.retire(HIGH.cube()));
            assertThrows(SurvivalRefusal.class, () -> owner.freezeCheckpoint(Set.of(HIGH.cube())));
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread thread = new Thread(() -> { try { tx.set(HIGH, Blocks.STONE.defaultBlockState()); } catch (Throwable error) { thrown.set(error); } });
            thread.start(); thread.join(); assertInstanceOf(SurvivalRefusal.class, thrown.get());
            assertEquals(NativeSurvivalWorld.TransactionState.OPEN, tx.state()); tx.abort();
        }
        assertEquals(0, owner.commitSequence());
    }

    @Test void statePalettePreservesOakAxesAndLeafPropertiesAndRefusesUnsupportedStates() {
        var owner = world(); air(owner, HIGH.cube()); long sequence = 1;
        for (Direction.Axis axis : Direction.Axis.values()) {
            BlockState state = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
            set(owner, sequence++, HIGH, state); assertSame(state, owner.read(HIGH));
        }
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.PERSISTENT, true).setValue(BlockStateProperties.DISTANCE, 3);
        set(owner, sequence, HIGH, leaves); assertSame(leaves, owner.read(HIGH));
        for (BlockState unsupported : new BlockState[]{Blocks.CHEST.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState(),
                Blocks.CAVE_AIR.defaultBlockState(), leaves.setValue(BlockStateProperties.WATERLOGGED, true)})
            assertThrows(SurvivalRefusal.class, () -> palette.id(unsupported));
        assertThrows(SurvivalRefusal.class, () -> palette.state(Block.BLOCK_STATE_REGISTRY.size()));
        assertThrows(SurvivalRefusal.class, () -> palette.state(-1));
        assertThrows(IllegalArgumentException.class, () -> new NativeSurvivalWorld(
                new WorldIdentity(39, "generator", "1", "data", "wrong-palette"), palette, owner.bounds(), owner.limits()));
    }

    @Test void readWriteEffectAndActorLimitsFailClosedWithoutPartialMutation() {
        var limits = new NativeSurvivalWorld.Limits(2, 2, 1, 2, 2, 1, 1, 2, 1, 2);
        var owner = world(limits); air(owner, HIGH.cube()); var before = owner.snapshot(HIGH.cube());
        CubeCell next = new CubeCell(HIGH.cube(), (HIGH.localIndex() + 1) % 4096);
        try (var tx = begin(owner, 1, HIGH.cube())) {
            tx.set(HIGH, Blocks.DIRT.defaultBlockState());
            assertThrows(SurvivalRefusal.class, () -> tx.set(next, Blocks.STONE.defaultBlockState()));
        }
        assertEquals(before, owner.snapshot(HIGH.cube()));
        try (var tx = begin(owner, 2, HIGH.cube())) {
            tx.set(HIGH, Blocks.STONE.defaultBlockState()); tx.stage(new NativeActionEffect("sound_v1", new byte[]{1}));
            assertThrows(SurvivalRefusal.class, () -> tx.stage(new NativeActionEffect("sound_v1", new byte[]{1})));
        }
        try (var tx = begin(owner, 3, HIGH.cube())) {
            assertThrows(SurvivalRefusal.class, () -> tx.stage(new NativeActionEffect("sound_v1", new byte[]{1, 2, 3})));
        }
        try (var tx = begin(owner, 4, HIGH.cube())) {
            tx.read(HIGH); tx.read(next);
            assertThrows(SurvivalRefusal.class, () -> tx.read(new CubeCell(HIGH.cube(), (HIGH.localIndex() + 2) % 4096)));
        }
        assertThrows(SurvivalRefusal.class, () -> owner.begin(owner.actionIdentity(new UUID(6, 7), 1), Map.of()));
        assertEquals(before, owner.snapshot(HIGH.cube())); assertEquals(0, owner.commitSequence()); assertEquals(1, owner.actorCount());
        assertThrows(IllegalArgumentException.class, () -> new NativeActionEffect("sound_v1", new byte[65537]));
    }

    @Test void boundedAdmissionRetiredMetadataAndPinsCannotLeakOwnership() {
        var owner = world(new NativeSurvivalWorld.Limits(1, 2, 1, 1, 1, 1, 1, 1, 1, 2));
        CubePos second = HIGH.cube().offset(1, 0, 0), third = HIGH.cube().offset(2, 0, 0);
        air(owner, HIGH.cube());
        assertThrows(SurvivalRefusal.class, () -> air(owner, second));
        owner.retire(HIGH.cube()); air(owner, second); owner.retire(second);
        assertThrows(SurvivalRefusal.class, () -> air(owner, third));
        assertEquals(2, owner.ownedCount()); assertEquals(0, owner.residentCount());
        try (var pin = owner.pin(HIGH.cube())) {
            assertEquals(1, owner.pinCount());
            assertThrows(SurvivalRefusal.class, () -> owner.pin(second));
            assertThrows(SurvivalRefusal.class, () -> owner.releaseClean(HIGH.cube()));
        }
        owner.releaseClean(HIGH.cube()); air(owner, third); assertEquals(2, owner.ownedCount());
        var bounded = new NativeSurvivalWorld(identity, palette, new NativeSurvivalWorld.Bounds(HIGH.cube(), HIGH.cube()), owner.limits());
        assertThrows(SurvivalRefusal.class, () -> air(bounded, second)); assertEquals(0, bounded.ownedCount());
    }

    @Test void failedCheckpointRetainsExactSnapshotOwnershipAndNewerEditsStayDirty() {
        var owner = world(); air(owner, HIGH.cube());
        set(owner, 1, HIGH, Blocks.STONE.defaultBlockState());
        var first = owner.freezeCheckpoint(Set.of(HIGH.cube()));
        assertEquals(1, first.cubes().get(HIGH.cube()).nextDurableRevision());
        assertEquals(1, first.commitSequence()); assertEquals(1L, first.actorSequences().get(ACTOR));
        owner.failCheckpoint(first); assertEquals(NativeSurvivalWorld.CheckpointState.FAILED, owner.checkpointState(first));
        assertThrows(SurvivalRefusal.class, () -> owner.acknowledgeCheckpoint(first));
        assertThrows(SurvivalRefusal.class, () -> owner.freezeCheckpoint(Set.of(HIGH.cube())));
        assertThrows(SurvivalRefusal.class, () -> owner.releaseClean(HIGH.cube()));
        owner.retire(HIGH.cube()); assertEquals(1, owner.ownedCount()); owner.reload(HIGH.cube());
        set(owner, 2, HIGH, Blocks.DIRT.defaultBlockState());
        assertEquals(palette.id(Blocks.STONE.defaultBlockState()), first.cubes().get(HIGH.cube()).snapshot().blocks().get(HIGH.localIndex()));
        assertSame(first, owner.retryCheckpoint(first)); owner.acknowledgeCheckpoint(first);
        var current = owner.snapshot(HIGH.cube());
        assertEquals(2, current.simulationRevision()); assertEquals(1, current.durableRevision());
        assertEquals(1, current.durableSimulationRevision()); assertTrue(current.dirty());
        assertThrows(SurvivalRefusal.class, () -> owner.acknowledgeCheckpoint(first));
        var second = owner.freezeCheckpoint(Set.of(HIGH.cube()));
        assertEquals(2, second.cubes().get(HIGH.cube()).nextDurableRevision());
        owner.acknowledgeCheckpoint(second); assertFalse(owner.snapshot(HIGH.cube()).dirty());
        owner.releaseClean(HIGH.cube()); assertEquals(0, owner.ownedCount());
    }

    @Test void wrongWorldOrRepeatedCheckpointTokenCannotAdvanceDurability() {
        var owner = world(); var other = world(); air(owner, HIGH.cube()); air(other, HIGH.cube());
        var first = owner.freezeCheckpoint(Set.of(HIGH.cube())); var foreign = other.freezeCheckpoint(Set.of(HIGH.cube()));
        assertThrows(SurvivalRefusal.class, () -> owner.acknowledgeCheckpoint(foreign));
        assertEquals(0, owner.snapshot(HIGH.cube()).durableRevision());
        assertThrows(SurvivalRefusal.class, () -> owner.releaseClean(HIGH.cube()));
        owner.acknowledgeCheckpoint(first); assertEquals(1, owner.snapshot(HIGH.cube()).durableRevision());
        assertThrows(SurvivalRefusal.class, () -> owner.acknowledgeCheckpoint(first));
        assertEquals(0, other.snapshot(HIGH.cube()).durableRevision());
    }

    @Test void simulationOverflowAndDurableOverflowRejectBeforeAnyCubeChanges() {
        var owner = world(); CubePos second = HIGH.cube().offset(-1, 0, 0); air(owner, second);
        owner.admitRecovered(HIGH.cube(), uniform(Blocks.AIR.defaultBlockState()), Long.MAX_VALUE, 1);
        try (var tx = begin(owner, 1, HIGH.cube(), second)) {
            tx.set(new CubeCell(second, 0), Blocks.DIRT.defaultBlockState()); tx.set(HIGH, Blocks.STONE.defaultBlockState());
            tx.stage(new NativeActionEffect("inventory_slot_v1", new byte[]{1}));
            assertThrows(SurvivalRefusal.class, tx::commit);
        }
        assertSame(Blocks.AIR.defaultBlockState(), owner.read(new CubeCell(second, 0)));
        assertSame(Blocks.AIR.defaultBlockState(), owner.read(HIGH)); assertEquals(0, owner.commitSequence());
        var durable = world(); durable.admitRecovered(HIGH.cube(), uniform(Blocks.DIRT.defaultBlockState()), 3, Long.MAX_VALUE);
        assertThrows(SurvivalRefusal.class, () -> durable.freezeCheckpoint(Set.of(HIGH.cube())));
        assertEquals(Long.MAX_VALUE, durable.snapshot(HIGH.cube()).durableRevision());
        durable.releaseClean(HIGH.cube()); assertEquals(0, durable.ownedCount());
    }

    @Test void unchangedAndRestoredWithinActionDoNotInventCubeRevisions() {
        var owner = world(); air(owner, HIGH.cube());
        try (var tx = begin(owner, 1, HIGH.cube())) {
            tx.set(HIGH, Blocks.STONE.defaultBlockState()); tx.set(HIGH, Blocks.AIR.defaultBlockState());
            var receipt = tx.commit(); assertTrue(receipt.changedCubes().isEmpty());
        }
        assertEquals(0, owner.snapshot(HIGH.cube()).simulationRevision()); assertFalse(owner.isDirty(HIGH.cube()));
        assertEquals(1, owner.commitSequence());
    }

    @Test void effectOnlyActionsCarryEpochAndStayDirtyUntilCoherentEmptyCubeCheckpoint() {
        var owner = world();
        var action = owner.actionIdentity(ACTOR, 1);
        try (var tx = owner.begin(action, Map.of())) {
            tx.stage(new NativeActionEffect("inventory_slot_v1", new byte[]{7, 9})); tx.commit();
        }
        assertTrue(owner.dirtyPositions().isEmpty()); assertTrue(owner.hasUncheckpointedActions());
        assertEquals(0, owner.durableCommitSequence());
        var checkpoint = owner.freezeCheckpoint(Set.of());
        assertTrue(checkpoint.cubes().isEmpty()); assertEquals(1, checkpoint.commitSequence());
        try (var tx = owner.begin(owner.actionIdentity(ACTOR, 2), Map.of())) {
            tx.stage(new NativeActionEffect("item_spawn_v1", new byte[]{5})); tx.commit();
        }
        owner.acknowledgeCheckpoint(checkpoint);
        assertEquals(1, owner.durableCommitSequence()); assertTrue(owner.hasUncheckpointedActions());
        owner.acknowledgeCheckpoint(owner.freezeCheckpoint(Set.of())); assertFalse(owner.hasUncheckpointedActions());
        var replacement = world();
        assertThrows(SurvivalRefusal.class, () -> replacement.begin(action, Map.of()));
        assertEquals(0, replacement.actorCount()); assertEquals(0, replacement.commitSequence());
    }

    @Test void checkpointCannotSkipDirtyCubesAndDirtyBudgetPreventsAnUnfreezableAction() {
        var owner = world(new NativeSurvivalWorld.Limits(2, 2, 1, 2, 4, 4, 1, 8, 1, 1));
        CubeCell neighbor = new CubeCell(HIGH.cube().offset(1, 0, 0), 0);
        air(owner, HIGH.cube()); air(owner, neighbor.cube());
        set(owner, 1, HIGH, Blocks.DIRT.defaultBlockState());
        assertThrows(SurvivalRefusal.class, () -> owner.freezeCheckpoint(Set.of(neighbor.cube())));
        assertThrows(SurvivalRefusal.class, () -> owner.freezeCheckpoint(Set.of()));
        try (var tx = begin(owner, 2, neighbor.cube())) {
            tx.set(neighbor, Blocks.STONE.defaultBlockState()); assertThrows(SurvivalRefusal.class, tx::commit);
        }
        assertSame(Blocks.AIR.defaultBlockState(), owner.read(neighbor)); assertEquals(1, owner.commitSequence());
        var checkpoint = owner.freezeCheckpoint(Set.of(HIGH.cube()));
        var usage = owner.usage();
        assertEquals(2, usage.ownedCubes()); assertEquals(1, usage.dirtyCubes()); assertEquals(1, usage.checkpointCubes());
        assertEquals(4096L * 4 + 4, usage.currentCubePayloadBytes());
        assertEquals(4096L * 4, usage.checkpointPayloadBytes());
        owner.acknowledgeCheckpoint(checkpoint);
        set(owner, 3, neighbor, Blocks.STONE.defaultBlockState()); assertEquals(1, owner.usage().dirtyCubes());
    }

    @Test void repeatedSameCellReadsAndWritesStillConsumeOperationBudgets() {
        var owner = world(new NativeSurvivalWorld.Limits(1, 1, 1, 1, 2, 1, 1, 8, 1, 1));
        air(owner, HIGH.cube());
        try (var tx = begin(owner, 1, HIGH.cube())) {
            tx.set(HIGH, Blocks.STONE.defaultBlockState());
            assertThrows(SurvivalRefusal.class, () -> tx.set(HIGH, Blocks.AIR.defaultBlockState()));
        }
        try (var tx = begin(owner, 2, HIGH.cube())) {
            tx.read(HIGH); tx.read(HIGH);
            assertThrows(SurvivalRefusal.class, () -> tx.read(HIGH));
        }
        assertSame(Blocks.AIR.defaultBlockState(), owner.read(HIGH)); assertEquals(0, owner.commitSequence());
    }
    @Test void zeroCubeCheckpointRetainsLifecycleOwnershipThroughFailureAndRetry() {
        var owner = world(); assertFalse(owner.hasPendingCheckpoint());
        try (var tx = owner.begin(owner.actionIdentity(ACTOR, 1), Map.of())) {
            tx.stage(new NativeActionEffect("inventory_slot_v1", new byte[]{1})); tx.commit();
        }
        var ticket = owner.freezeCheckpoint(Set.of()); assertTrue(owner.hasPendingCheckpoint());
        assertEquals(0, owner.usage().checkpointCubes()); owner.failCheckpoint(ticket);
        assertTrue(owner.hasPendingCheckpoint()); owner.retryCheckpoint(ticket);
        assertTrue(owner.hasPendingCheckpoint()); owner.acknowledgeCheckpoint(ticket);
        assertFalse(owner.hasPendingCheckpoint()); assertFalse(owner.hasUncheckpointedActions());
    }
}
