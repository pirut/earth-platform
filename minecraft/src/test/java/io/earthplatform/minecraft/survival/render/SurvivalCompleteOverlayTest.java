package io.earthplatform.minecraft.survival.render;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.persistence.*;
import io.earthplatform.minecraft.survival.source.MarinSurvivalResources;
import io.earthplatform.minecraft.survival.source.MarinVisualCubeSource;
import io.earthplatform.minecraft.survival.world.*;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/** Authority/immutable-input tests only: no GPU, DH, native-model or selected-distance runtime claim. */
class SurvivalCompleteOverlayTest {
    @TempDir static Path sourceDirectory;
    @TempDir Path temporary;
    static HolderLookup.Provider registries;
    static SurvivalPalette palette;
    static MarinSurvivalDefinition definition;
    static MarinVisualCubeSource source;
    static WorldIdentity identity;
    static NativeSurvivalWorld.Bounds bounds;
    static CubePos p;
    static Binding binding;
    static final UUID PLAYER = new UUID(101, 102), SESSION = new UUID(103, 104);
    static final NativeSurvivalCheckpoint.Limits CHECKPOINT = new NativeSurvivalCheckpoint.Limits(32, 0, 0, 8, 1 << 20);
    static final SurvivalCheckpointStore.Limits STORE = new SurvivalCheckpointStore.Limits(1, 2 << 20, 2 << 20, 4096);
    static final SurvivalCompleteOverlay.Limits CAPTURE = new SurvivalCompleteOverlay.Limits(64, 4 << 20, 1 << 20);
    static final SurvivalDistancePagePlan.Limits PLAN = new SurvivalDistancePagePlan.Limits(
            100000, 1000, 1000, 10000, 200000, 200000, 64, 2 << 20);

    @BeforeAll static void setup() throws Exception {
        registries = VanillaRegistries.createLookup();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = new SurvivalPalette(EnginePaletteContext.create(biomes.freeze()));
        definition = MarinSurvivalResources.installAndLoad(sourceDirectory.resolve("source"));
        source = new MarinVisualCubeSource(definition, new SurvivalRenderPalette(palette));
        identity = new WorldIdentity(39, "complete-overlay-test", "039", definition.identity(), palette.identity());
        var envelope = definition.envelope();
        bounds = new NativeSurvivalWorld.Bounds(new CubePos(envelope.minCenterX(), envelope.minCenterY(), envelope.minCenterZ()),
                new CubePos(envelope.maxCenterXExclusive() - 1, envelope.maxCenterYExclusive() - 1, envelope.maxCenterZExclusive() - 1));
        p = definition.anchor().center();
        binding = new Binding(new UUID(105, 106), "overlay-test", identity, definition.identity(), palette.identity(),
                "test-data-packs", PLAYER, ResourceLocation.parse("earthplatform:survival_carrier"));
    }
    static NativeSurvivalWorld world() {
        return new NativeSurvivalWorld(identity, palette, bounds,
                new NativeSurvivalWorld.Limits(16, 16, 8, 16, 16384, 8192, 64, 65536, 16, 16));
    }
    static NativeWholeAction.InventoryView emptyView() {
        return new NativeWholeAction.InventoryView() {
            public int size() { return 41; }
            public ItemStack getItem(int slot) { return ItemStack.EMPTY; }
            public int selectedSlot() { return 0; }
        };
    }
    static NativeInventorySnapshot inventory() {
        var slots = new ArrayList<NativeStackSnapshot>();
        for (int i = 0; i < 41; i++) slots.add(NativeStackSnapshot.capture(ItemStack.EMPTY, registries));
        return new NativeInventorySnapshot(slots, 0);
    }
    static NativeFoodSnapshot food() { return new NativeFoodSnapshot(20, 0, 5, 0); }
    static NativePlayerPoseSnapshot pose() {
        return new NativePlayerPoseSnapshot(binding.dimension(), p.minBlockX() + 0.5, p.minBlockY() + 1, p.minBlockZ() + 0.5,
                0, 0, 0, 0, 0, 0, true, GameType.SURVIVAL, null,
                new NativePlayerPoseSnapshot.AbilityState(false, false, false, false, false, 0.05F, 0.1F));
    }
    static Boundary boundary() { return new Boundary(1, inventory(), food(), pose(), List.of(), List.of()); }
    static Session session() { return new Session(SESSION, SessionPhase.ENTERING, inventory(), food(), pose()); }
    static SurvivalCheckpointCodec codec(Binding binding) { return new SurvivalCheckpointCodec(binding, CHECKPOINT, registries, palette, bounds); }
    static void admit(NativeSurvivalWorld world, CubePos at, BlockState state) { world.admitGenerated(at, ImmutableCube.uniform(palette.id(state))); }
    static CubeCell cell(CubePos at, int index) { return CubeCell.of(at.minBlockX() + (index & 15), at.minBlockY() + (index >>> 8), at.minBlockZ() + (index >>> 4 & 15)); }
    static void set(NativeSurvivalWorld world, BlockState state) {
        try (var action = world.begin(world.actionIdentity(PLAYER, world.nextActionSequence(PLAYER)), Map.of(p, world.stamp(p)))) {
            action.set(cell(p, 0), state); action.commit();
        }
    }
    static void effects(NativeSurvivalWorld world) {
        try (var action = world.begin(world.actionIdentity(PLAYER, world.nextActionSequence(PLAYER)), Map.of())) { action.commit(); }
    }
    static void complete(SurvivalCheckpointCoordinator coordinator) { coordinator.submit(Runnable::run); assertTrue(coordinator.poll()); }
    final class Fixture implements AutoCloseable {
        final NativeSurvivalWorld world = world();
        final NativeWholeAction.Owner actions;
        final SurvivalCheckpointStore store;
        final SurvivalCheckpointCoordinator checkpoints;
        final NativeDistanceCpuBudget budget;
        final SurvivalCompleteOverlay.CaptureOwner owner;
        Fixture(String name) throws Exception { this(name, receipt -> {}, new NativeDistanceCpuBudget(32 << 20), CAPTURE); }
        Fixture(String name, NativeWholeAction.PreparedEffects delivery, NativeDistanceCpuBudget budget,
                SurvivalCompleteOverlay.Limits limits) throws Exception {
            this.budget = budget;
            actions = new NativeWholeAction.Owner(world, registries, (action, effects) -> delivery);
            store = new SurvivalCheckpointStore(temporary.resolve(name), codec(binding).storageIdentity(), STORE);
            checkpoints = new SurvivalCheckpointCoordinator(actions, store, STORE, codec(binding),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            owner = new SurvivalCompleteOverlay.CaptureOwner(world, actions, checkpoints, source, budget, limits);
        }
        void save() { checkpoints.capture(session(), SurvivalCompleteOverlayTest::boundary); complete(checkpoints); }
        public void close() throws Exception { store.close(); }
    }

    @Test void terrainSequenceCountsAcceptedNetChangesOnly() throws Exception {
        try (var f = new Fixture("sequence")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            assertEquals(0, f.world.terrainContentSequence());
            effects(f.world);
            set(f.world, Blocks.STONE.defaultBlockState());
            try (var action = f.world.begin(f.world.actionIdentity(PLAYER, f.world.nextActionSequence(PLAYER)), Map.of(p, f.world.stamp(p)))) {
                action.set(cell(p, 0), Blocks.AIR.defaultBlockState());
                action.set(cell(p, 0), Blocks.STONE.defaultBlockState());
                assertTrue(action.commit().changedCubes().isEmpty());
            }
            assertEquals(3, f.world.commitSequence()); assertEquals(0, f.world.terrainContentSequence());
            set(f.world, Blocks.AIR.defaultBlockState());
            assertEquals(1, f.world.terrainContentSequence());
            f.save(); f.world.retire(p); f.world.reload(p);
            assertEquals(1, f.world.terrainContentSequence());
        }
    }

    @Test void unloadedAirTombstoneRemainsPresentAndAbsenceNeverMeansAir() throws Exception {
        try (var f = new Fixture("tombstone")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            try (var action = f.world.begin(f.world.actionIdentity(PLAYER, 1), Map.of(p, f.world.stamp(p)))) {
                for (int i = 0; i < CubePos.VOLUME; i++) action.set(cell(p, i), Blocks.AIR.defaultBlockState());
                action.commit();
            }
            f.save(); f.world.releaseClean(p);
            try (var content = f.owner.capture()) {
                assertEquals(1, content.overrideCount()); assertEquals(p, content.overridePosition(0));
                assertTrue(content.hasOverride(p));
                for (int i = 0; i < CubePos.VOLUME; i++) assertEquals(palette.id(Blocks.AIR.defaultBlockState()), content.overrideStateId(p, i));
                assertFalse(content.hasOverride(p.offset(1, 0, 0)));
                assertThrows(SurvivalRefusal.class, () -> content.overrideStateId(p.offset(1, 0, 0), 0));
                f.owner.validateCurrent(content);
            }
            assertEquals(0, f.budget.evidence().bytes());
        }
    }

    @Test void changedContentDuringCaptureRefusesAndDrainsWhileEffectOnlyProgressIsAllowed() throws Exception {
        try (var f = new Fixture("capture-change")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            assertThrows(SurvivalRefusal.class, () -> f.owner.capture(() -> set(f.world, Blocks.AIR.defaultBlockState())));
            assertEquals(0, f.budget.evidence().bytes());
            try (var content = f.owner.capture(() -> effects(f.world))) { f.owner.validateCurrent(content); }
            assertEquals(0, f.budget.evidence().bytes());
        }
    }

    @Test void effectsMatchingAcknowledgementAndResidencyDoNotInvalidateGeometry() throws Exception {
        try (var f = new Fixture("neutral")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            try (var before = f.owner.capture()) {
                effects(f.world); f.owner.validateCurrent(before);
                f.save(); f.world.retire(p); f.owner.validateCurrent(before); f.world.reload(p);
                try (var after = f.owner.capture()) {
                    assertEquals(before.contentIdentity(), after.contentIdentity());
                    assertEquals(before.terrainContentSequence(), after.terrainContentSequence());
                    assertNotEquals(before.actionSequence(), after.actionSequence());
                    assertNotEquals(before.captureSequence(), after.captureSequence());
                    f.owner.validateCurrent(before); f.owner.validateCurrent(after);
                }
            }
        }
    }

    @Test void acknowledgementOfOlderFrozenStatePreservesNewerDirtyOverride() throws Exception {
        try (var f = new Fixture("older-ack")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            set(f.world, Blocks.OAK_LOG.defaultBlockState());
            f.checkpoints.capture(session(), SurvivalCompleteOverlayTest::boundary);
            set(f.world, Blocks.COBBLESTONE.defaultBlockState());
            try (var before = f.owner.capture()) {
                complete(f.checkpoints); assertTrue(f.world.isDirty(p)); f.owner.validateCurrent(before);
                try (var after = f.owner.capture()) {
                    assertEquals(before.contentIdentity(), after.contentIdentity());
                    assertEquals(palette.id(Blocks.COBBLESTONE.defaultBlockState()), after.overrideStateId(p, 0));
                }
            }
        }
    }

    @Test void activeForeignOpeningAndFailedWholeActionsRefuse() throws Exception {
        try (var f = new Fixture("fenced", receipt -> { throw new IllegalStateException("failed delivery"); },
                new NativeDistanceCpuBudget(32 << 20), CAPTURE); var before = f.owner.capture()) {
            try (var action = f.actions.begin(f.world.actionIdentity(PLAYER, 1), Map.of(), emptyView())) {
                assertThrows(SurvivalRefusal.class, f.owner::capture);
                assertThrows(SurvivalRefusal.class, () -> f.owner.validateCurrent(before));
                assertThrows(SurvivalRefusal.class, action::commit);
            }
            assertThrows(SurvivalRefusal.class, f.owner::capture);
            assertThrows(SurvivalRefusal.class, () -> f.owner.validateCurrent(before));
        }
        try (var f = new Fixture("foreign")) {
            var foreignWorld = world();
            var foreign = new NativeWholeAction.Owner(foreignWorld, registries, (action, effects) -> receipt -> {});
            var observed = new AtomicBoolean();
            var opening = new NativeWholeAction.InventoryView() {
                public int size() { return 41; }
                public int selectedSlot() { return 0; }
                public ItemStack getItem(int slot) {
                    assertThrows(SurvivalRefusal.class, f.owner::capture); observed.set(true); return ItemStack.EMPTY;
                }
            };
            try (var action = foreign.begin(foreignWorld.actionIdentity(PLAYER, 1), Map.of(), opening)) {
                assertTrue(observed.get()); assertThrows(SurvivalRefusal.class, f.owner::capture);
            }
            try (var okay = f.owner.capture()) { f.owner.validateCurrent(okay); }
        }
    }

    @Test void releaseReadmitAbaAndMembershipChangeRefuseEvenWithUnchangedCounter() throws Exception {
        try (var f = new Fixture("aba")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            try (var original = f.owner.capture()) {
                f.world.releaseClean(p); admit(f.world, p, Blocks.STONE.defaultBlockState());
                assertEquals(original.terrainContentSequence(), f.world.terrainContentSequence());
                assertThrows(SurvivalRefusal.class, () -> f.owner.validateCurrent(original));
                try (var next = f.owner.capture()) { assertEquals(original.contentIdentity(), next.contentIdentity()); }
            }
            try (var prior = f.owner.capture()) {
                admit(f.world, p.offset(1, 0, 0), Blocks.AIR.defaultBlockState());
                assertEquals(prior.terrainContentSequence(), f.world.terrainContentSequence());
                assertThrows(SurvivalRefusal.class, () -> f.owner.validateCurrent(prior));
            }
        }
    }

    @Test void exactRestoredContentHasSameIdentityButOldOwnerCaptureCannotValidate() throws Exception {
        try (var f = new Fixture("recovery")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState()); set(f.world, Blocks.AIR.defaultBlockState()); f.save();
            try (var first = f.owner.capture()) {
                var recovered = world();
                var actions = new NativeWholeAction.Owner(recovered, registries, (action, effects) -> receipt -> {});
                var coordinator = new SurvivalCheckpointCoordinator(actions, f.store, STORE, codec(binding),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
                var saved = coordinator.completeDurableCubes().get(p);
                recovered.admitRecovered(p, saved.blocks(), saved.simulationRevision(), saved.durableRevision());
                var owner = new SurvivalCompleteOverlay.CaptureOwner(recovered, actions, coordinator, source, f.budget, CAPTURE);
                assertThrows(SurvivalRefusal.class, () -> owner.validateCurrent(first));
                try (var second = owner.capture()) {
                    assertEquals(first.contentIdentity(), second.contentIdentity());
                    assertNotEquals(first.ownerEpoch(), second.ownerEpoch());
                    assertNotEquals(first.terrainContentSequence(), second.terrainContentSequence());
                    owner.validateCurrent(second);
                }
            }
        }
    }

    @Test void staleGeneratedOrInventedRecoveredLineageCannotHideDurableOverride() throws Exception {
        try (var f = new Fixture("lineage")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState()); set(f.world, Blocks.AIR.defaultBlockState()); f.save();
            var saved = f.checkpoints.completeDurableCubes().get(p); f.world.releaseClean(p);
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            assertThrows(SurvivalRefusal.class, f.owner::capture); assertEquals(0, f.budget.evidence().bytes());
            f.world.releaseClean(p);
            f.world.admitRecovered(p, saved.blocks(), saved.simulationRevision() + 1, saved.durableRevision());
            assertThrows(SurvivalRefusal.class, f.owner::capture); assertEquals(0, f.budget.evidence().bytes());
        }
    }

    @Test void foreignCoordinatorAndSourceOrRegistryBindingsRefuse() throws Exception {
        try (var f = new Fixture("bindings")) {
            var substitute = new NativeWholeAction.Owner(f.world, registries, (action, effects) -> receipt -> {});
            assertThrows(SurvivalRefusal.class, () -> new SurvivalCompleteOverlay.CaptureOwner(
                    f.world, substitute, f.checkpoints, source, f.budget, CAPTURE));
            var foreignWorld = world();
            assertThrows(SurvivalRefusal.class, () -> new SurvivalCompleteOverlay.CaptureOwner(
                    foreignWorld, f.actions, f.checkpoints, source, f.budget, CAPTURE));
        }
        for (boolean wrongSource : new boolean[]{true, false}) {
            var wrong = new Binding(binding.saveId(), binding.profileId(), identity,
                    wrongSource ? "different-source" : definition.identity(),
                    wrongSource ? palette.identity() : "different-registry", binding.dataPackIdentity(), PLAYER, binding.dimension());
            try (var store = new SurvivalCheckpointStore(temporary.resolve("wrong-" + wrongSource), codec(wrong).storageIdentity(), STORE)) {
                var world = world();
                var actions = new NativeWholeAction.Owner(world, registries, (action, effects) -> receipt -> {});
                var coordinator = new SurvivalCheckpointCoordinator(actions, store, STORE, codec(wrong),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
                var budget = new NativeDistanceCpuBudget(32 << 20);
                assertThrows(SurvivalRefusal.class, () -> new SurvivalCompleteOverlay.CaptureOwner(
                        world, actions, coordinator, source, budget, CAPTURE));
                assertEquals(0, budget.evidence().bytes());
            }
        }
    }

    @Test void membershipMutationDuringCaptureRefusesWithoutAnyTerrainCommit() throws Exception {
        try (var f = new Fixture("capture-membership")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            assertThrows(SurvivalRefusal.class, () -> f.owner.capture(() -> admit(f.world, p.offset(1, 0, 0), Blocks.AIR.defaultBlockState())));
            assertEquals(0, f.world.terrainContentSequence()); assertEquals(0, f.budget.evidence().bytes());
        }
    }

    @Test void countAndPoolAdmissionPrecedeCopyHashAndCallbacks() throws Exception {
        var tiny = new SurvivalCompleteOverlay.Limits(0, 4 << 20, 1 << 20);
        try (var f = new Fixture("admission-count", receipt -> {}, new NativeDistanceCpuBudget(32 << 20), tiny)) {
            admit(f.world, p, Blocks.STONE.defaultBlockState()); var invoked = new AtomicBoolean();
            assertThrows(SurvivalRefusal.class, () -> f.owner.capture(() -> invoked.set(true)));
            assertFalse(invoked.get()); assertEquals(0, f.budget.evidence().peakBytes());
        }
        var small = new NativeDistanceCpuBudget(1000);
        try (var f = new Fixture("admission-pool", receipt -> {}, small, CAPTURE)) {
            var invoked = new AtomicBoolean();
            assertThrows(IllegalStateException.class, () -> f.owner.capture(() -> invoked.set(true)));
            assertFalse(invoked.get()); assertEquals(0, small.evidence().bytes());
        }
        var partial = new NativeDistanceCpuBudget(8192 + 1024);
        try (var f = new Fixture("partial-admission", receipt -> {}, partial, CAPTURE)) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            assertThrows(IllegalStateException.class, f.owner::capture);
            assertEquals(0, partial.evidence().bytes(), "SOURCE failure releases earlier OWNER_METADATA admission");
        }
    }

    @Test void retainedAliasesAndPlansKeepChargesUntilLastCloseAndClosedReadsRefuse() throws Exception {
        try (var f = new Fixture("lifetime")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            var first = f.owner.capture(); var worker = first.retain();
            long both = f.budget.evidence().bytes(); first.close(); first.close();
            assertTrue(f.budget.evidence().bytes() > 0); assertTrue(f.budget.evidence().bytes() < both);
            assertThrows(SurvivalRefusal.class, first::retain); assertThrows(SurvivalRefusal.class, first::overrideCount);
            assertEquals(palette.id(Blocks.STONE.defaultBlockState()), worker.overrideStateId(p, 0));
            var plan = worker.plan(p, new SurvivalDistancePagePlan.DistanceChoice(2, 2, 0), PLAN);
            var retainedPlan = plan.retain(); worker.close(); plan.close();
            assertThrows(SurvivalRefusal.class, plan::pageCount);
            assertTrue(retainedPlan.containsMember(p)); assertTrue(retainedPlan.pageCount() > 0);
            f.owner.validateCurrent(retainedPlan);
            assertTrue(f.budget.evidence().pools().get(NativeDistanceCpuBudget.Pool.PLAN) > 0);
            retainedPlan.close(); retainedPlan.close(); assertEquals(0, f.budget.evidence().bytes());
            assertThrows(SurvivalRefusal.class, retainedPlan::retainContent);
        }
    }

    @Test void immutableWorkerReadsNeedNoOwnerButValidationDoes() throws Exception {
        try (var f = new Fixture("thread")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            try (var content = f.owner.capture(); var worker = content.retain()) {
                var failure = new AtomicReference<Throwable>();
                var thread = new Thread(() -> {
                    try {
                        assertEquals(palette.id(Blocks.STONE.defaultBlockState()), worker.overrideStateId(p, 0));
                        assertThrows(SurvivalRefusal.class, () -> f.owner.validateCurrent(worker));
                    } catch (Throwable problem) { failure.set(problem); }
                });
                thread.start(); thread.join(); assertNull(failure.get());
                f.owner.validateCurrent(content);
            }
        }
    }

    @Test void semanticEditIndexExcludesCounterAndExactChangedBlocksStillInvalidate() throws Exception {
        var first = SurvivalDistancePagePlan.CompleteEditSet.copyOf(1, "a".repeat(64), Set.of(p), PLAN);
        var second = SurvivalDistancePagePlan.CompleteEditSet.copyOf(99, "a".repeat(64), Set.of(p), PLAN);
        assertEquals(first.indexIdentity(), second.indexIdentity());
        try (var f = new Fixture("restored-blocks")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            try (var original = f.owner.capture()) {
                set(f.world, Blocks.AIR.defaultBlockState()); set(f.world, Blocks.STONE.defaultBlockState());
                try (var restored = f.owner.capture()) { assertEquals(original.contentIdentity(), restored.contentIdentity()); }
                assertThrows(SurvivalRefusal.class, () -> f.owner.validateCurrent(original));
            }
        }
    }
}
