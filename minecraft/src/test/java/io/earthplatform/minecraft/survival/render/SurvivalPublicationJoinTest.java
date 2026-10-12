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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Current-content authority only. This does not test physical provenance or resource/native+DH publication. */
class SurvivalPublicationJoinTest {
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
        void save() { checkpoints.capture(session(), SurvivalPublicationJoinTest::boundary); complete(checkpoints); }
        public void close() throws Exception { owner.close(); store.close(); }
    }

    static SurvivalCompleteOverlay.PlanInput plan(SurvivalCompleteOverlay.Content content) {
        return content.plan(p, new SurvivalDistancePagePlan.DistanceChoice(2, 2, 0), PLAN);
    }
    static void rejected(SurvivalCompleteOverlay.CurrentLease lease, SurvivalCompleteOverlay.PlanInput plan) {
        assertThrows(SurvivalRefusal.class, () -> lease.latch(plan));
    }

    @Test void authorityRequiresExactPlanObjectAndAuthenticOwner() throws Exception {
        try (var f = new Fixture("exact"); var content = f.owner.capture(); var plan = plan(content);
                var equal = plan(content); var lease = f.owner.authorize(plan); var alias = plan.retain()) {
            assertEquals(plan.identity(), equal.identity());
            rejected(lease, equal);
            try (var frame = lease.latch(alias)) {
                assertEquals(plan.identity(), frame.planIdentity());
                assertEquals(plan.pageCount(), frame.pageCount());
                assertEquals(plan.page(0), frame.page(0));
            }
            var otherOwner = new SurvivalCompleteOverlay.CaptureOwner(f.world, f.actions, f.checkpoints, source, f.budget, CAPTURE);
            assertThrows(SurvivalRefusal.class, () -> otherOwner.authorize(plan));
            assertEquals(0, SurvivalCompleteOverlay.CurrentLease.class.getConstructors().length);
            assertEquals(0, SurvivalCompleteOverlay.FrameLatch.class.getConstructors().length);
        }
    }

    @Test void directMapCallbacksObservePreparationAndCannotMintAuthority() throws Exception {
        try (var f = new Fixture("map-callback")) {
            // A nonempty, already-owned entry prevents Map.copyOf's empty-map fast path from
            // legitimately skipping entrySet. Capture both geometry and stamp before begin.
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            var entry = Map.entry(p, f.world.stamp(p));
            try (var content = f.owner.capture(); var plan = plan(content); var original = f.owner.authorize(plan)) {
                var sizes = new AtomicInteger(); var entries = new AtomicInteger();
                Map<CubePos, NativeSurvivalWorld.CubeStamp> callerMap = new AbstractMap<>() {
                    private void check() {
                        assertTrue(f.world.hasActiveTransaction()); rejected(original, plan);
                        assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
                    }
                    public int size() { check(); sizes.incrementAndGet(); return 1; }
                    public Set<Entry<CubePos, NativeSurvivalWorld.CubeStamp>> entrySet() {
                        check(); entries.incrementAndGet(); return Set.of(entry);
                    }
                };
                try (var transaction = f.world.begin(f.world.actionIdentity(PLAYER, 1), callerMap)) {
                    assertTrue(sizes.get() > 0); assertTrue(entries.get() > 0);
                    rejected(original, plan);
                    assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
                    transaction.abort();
                }
                rejected(original, plan);
                try (var after = f.owner.authorize(plan); var frame = after.latch(plan)) {
                    assertEquals(plan.identity(), frame.planIdentity());
                }
            }
        }
    }

    @Test void effectsAbortNoopAndAcknowledgementRebindWithoutChangingGeometry() throws Exception {
        try (var f = new Fixture("neutral-authority")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            try (var content = f.owner.capture(); var plan = plan(content); var first = f.owner.authorize(plan)) {
                String identity = plan.identity();
                effects(f.world); rejected(first, plan);
                try (var second = f.owner.authorize(plan)) {
                    try (var frame = second.latch(plan)) { assertEquals(identity, frame.planIdentity()); }
                    f.save();
                    try (var frame = second.latch(plan)) { assertEquals(identity, frame.planIdentity()); }
                    try (var transaction = f.world.begin(f.world.actionIdentity(PLAYER, f.world.nextActionSequence(PLAYER)), Map.of())) {
                        rejected(second, plan);
                        assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
                        transaction.abort();
                    }
                    rejected(second, plan);
                }
                try (var afterAbort = f.owner.authorize(plan); var frame = afterAbort.latch(plan)) {
                    assertEquals(identity, frame.planIdentity());
                }
                set(f.world, Blocks.STONE.defaultBlockState());
                try (var noOp = f.owner.authorize(plan); var frame = noOp.latch(plan)) {
                    assertEquals(identity, frame.planIdentity());
                }
            }
            assertEquals(0, f.budget.evidence().bytes());
        }
    }

    @Test void everyOpeningInventoryCallbackSeesRevocationAndCannotMint() throws Exception {
        try (var f = new Fixture("opening"); var content = f.owner.capture(); var plan = plan(content);
                var lease = f.owner.authorize(plan)) {
            var sizes = new AtomicInteger(); var selections = new AtomicInteger(); var items = new AtomicInteger();
            var inventory = new NativeWholeAction.InventoryView() {
                void check() {
                    assertTrue(NativeWholeAction.hasCurrentOrOpeningAction());
                    rejected(lease, plan);
                    assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
                }
                public int size() { check(); sizes.incrementAndGet(); return 41; }
                public int selectedSlot() { check(); selections.incrementAndGet(); return 0; }
                public ItemStack getItem(int slot) { check(); items.incrementAndGet(); return ItemStack.EMPTY; }
            };
            try (var action = f.actions.begin(f.world.actionIdentity(PLAYER, 1), Map.of(), inventory)) {
                assertTrue(f.actions.owns(action));
                rejected(lease, plan);
                assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
            }
            assertTrue(sizes.get() > 0); assertTrue(selections.get() > 0); assertEquals(41, items.get());
            assertFalse(NativeWholeAction.hasCurrentOrOpeningAction());
            rejected(lease, plan);
            try (var rebound = f.owner.authorize(plan); var frame = rebound.latch(plan)) { assertEquals(plan.identity(), frame.planIdentity()); }
        }
    }

    @Test void failedOpeningUnwindsWithoutConsumingActionOrRevivingLease() throws Exception {
        try (var f = new Fixture("opening-failure"); var content = f.owner.capture(); var plan = plan(content);
                var lease = f.owner.authorize(plan)) {
            var inventory = new NativeWholeAction.InventoryView() {
                public int size() { rejected(lease, plan); throw new IllegalArgumentException("size callback failed"); }
                public int selectedSlot() { fail("selection should not run"); return 0; }
                public ItemStack getItem(int slot) { fail("snapshot should not run"); return ItemStack.EMPTY; }
            };
            assertThrows(IllegalArgumentException.class, () -> f.actions.begin(f.world.actionIdentity(PLAYER, 1), Map.of(), inventory));
            assertFalse(NativeWholeAction.hasCurrentOrOpeningAction());
            assertFalse(f.actions.hasCurrentAction()); assertFalse(f.world.hasActiveTransaction());
            assertEquals(1, f.world.nextActionSequence(PLAYER)); assertEquals(0, f.world.commitSequence());
            rejected(lease, plan);
            try (var next = f.owner.authorize(plan); var frame = next.latch(plan)) { assertEquals(plan.identity(), frame.planIdentity()); }
        }
    }

    @Test void prevalidationApplyingAndFailedDeliveryRemainFenced() throws Exception {
        var world = world();
        var ownerRef = new AtomicReference<SurvivalCompleteOverlay.CaptureOwner>();
        var planRef = new AtomicReference<SurvivalCompleteOverlay.PlanInput>();
        var leaseRef = new AtomicReference<SurvivalCompleteOverlay.CurrentLease>();
        var prevalidation = new AtomicBoolean(); var applying = new AtomicBoolean();
        var actions = new NativeWholeAction.Owner(world, registries, (identity, effects) -> {
            assertEquals(NativeWholeAction.State.PREVALIDATING, NativeWholeAction.current().state());
            rejected(leaseRef.get(), planRef.get());
            assertThrows(SurvivalRefusal.class, () -> ownerRef.get().authorize(planRef.get()));
            prevalidation.set(true);
            return receipt -> {
                assertEquals(NativeWholeAction.State.APPLYING, NativeWholeAction.current().state());
                rejected(leaseRef.get(), planRef.get());
                assertThrows(SurvivalRefusal.class, () -> ownerRef.get().authorize(planRef.get()));
                applying.set(true); throw new IllegalStateException("delivery failed after commit");
            };
        });
        var budget = new NativeDistanceCpuBudget(32 << 20);
        try (var store = new SurvivalCheckpointStore(temporary.resolve("delivery"), codec(binding).storageIdentity(), STORE)) {
            var checkpoints = new SurvivalCheckpointCoordinator(actions, store, STORE, codec(binding),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            try (var owner = new SurvivalCompleteOverlay.CaptureOwner(world, actions, checkpoints, source, budget, CAPTURE);
                    var content = owner.capture(); var plan = plan(content); var lease = owner.authorize(plan)) {
                ownerRef.set(owner); planRef.set(plan); leaseRef.set(lease);
                try (var action = actions.begin(world.actionIdentity(PLAYER, 1), Map.of(), emptyView())) {
                    assertThrows(SurvivalRefusal.class, action::commit);
                }
                assertTrue(prevalidation.get()); assertTrue(applying.get()); assertTrue(actions.fenced());
                assertEquals(1, world.commitSequence());
                rejected(lease, plan);
                assertThrows(SurvivalRefusal.class, () -> owner.authorize(plan));
            }
        }
        assertEquals(0, budget.evidence().bytes());
    }

    @Test void membershipResidencyAndIncarnationChangesRevokeBeforeReuse() throws Exception {
        try (var f = new Fixture("membership-authority")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            try (var content = f.owner.capture(); var plan = plan(content); var first = f.owner.authorize(plan)) {
                f.world.retire(p); rejected(first, plan);
                try (var retired = f.owner.authorize(plan)) {
                    f.world.reload(p); rejected(retired, plan);
                }
                try (var reloaded = f.owner.authorize(plan)) {
                    admit(f.world, p.offset(1, 0, 0), Blocks.AIR.defaultBlockState());
                    rejected(reloaded, plan);
                    assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
                }
            }
            try (var content = f.owner.capture(); var plan = plan(content); var lease = f.owner.authorize(plan)) {
                f.world.releaseClean(p); rejected(lease, plan);
                admit(f.world, p, Blocks.STONE.defaultBlockState());
                rejected(lease, plan);
                assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
            }
        }
    }

    @Test void terrainAbaAndRecoveredOwnerCannotReviveAuthority() throws Exception {
        try (var f = new Fixture("content-aba")) {
            admit(f.world, p, Blocks.STONE.defaultBlockState());
            try (var content = f.owner.capture(); var plan = plan(content); var lease = f.owner.authorize(plan)) {
                set(f.world, Blocks.AIR.defaultBlockState()); set(f.world, Blocks.STONE.defaultBlockState());
                rejected(lease, plan);
                try (var after = f.owner.capture()) { assertEquals(content.contentIdentity(), after.contentIdentity()); }
                assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
                f.save();
                var recovered = world();
                var actions = new NativeWholeAction.Owner(recovered, registries, (action, effects) -> receipt -> {});
                var checkpoints = new SurvivalCheckpointCoordinator(actions, f.store, STORE, codec(binding),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
                var durable = checkpoints.completeDurableCubes().get(p);
                recovered.admitRecovered(p, durable.blocks(), durable.simulationRevision(), durable.durableRevision());
                try (var owner = new SurvivalCompleteOverlay.CaptureOwner(recovered, actions, checkpoints, source, f.budget, CAPTURE)) {
                    assertThrows(SurvivalRefusal.class, () -> owner.authorize(plan));
                }
            }
        }
    }

    @Test void unloadedAirTombstonesAreInTheExactAuthorizedPlan() throws Exception {
        try (var f = new Fixture("air-authority")) {
            admit(f.world, p, Blocks.AIR.defaultBlockState()); f.save(); f.world.releaseClean(p);
            try (var content = f.owner.capture(); var plan = plan(content); var lease = f.owner.authorize(plan);
                    var frame = lease.latch(plan)) {
                assertTrue(content.hasOverride(p)); assertTrue(plan.containsMember(p));
                assertEquals(palette.id(Blocks.AIR.defaultBlockState()), content.overrideStateId(p, 0));
                assertEquals(plan.identity(), frame.planIdentity());
                admit(f.world, p.offset(1, 0, 0), Blocks.AIR.defaultBlockState());
                rejected(lease, plan);
            }
        }
    }

    @Test void serverMutationNeverWaitsForRetainedHistoricalFrame() throws Exception {
        try (var f = new Fixture("historical"); var content = f.owner.capture(); var plan = plan(content);
                var lease = f.owner.authorize(plan)) {
            var latched = new CountDownLatch(1); var mutated = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var render = new Thread(() -> {
                try (var frame = lease.latch(plan)) {
                    latched.countDown();
                    assertTrue(mutated.await(5, TimeUnit.SECONDS));
                    // Immutable historical content remains retained; it is not a current/draw lease.
                    assertEquals(plan.identity(), frame.planIdentity());
                    rejected(lease, plan);
                    assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
                } catch (Throwable problem) { failure.set(problem); latched.countDown(); }
            });
            render.start(); assertTrue(latched.await(5, TimeUnit.SECONDS));
            effects(f.world); mutated.countDown(); render.join(5000);
            assertFalse(render.isAlive()); assertNull(failure.get());
        }
    }

    @Test void retirementFromOtherThreadRevokesQueuedAndDuplicateLeases() throws Exception {
        try (var f = new Fixture("retirement"); var content = f.owner.capture(); var plan = plan(content);
                var first = f.owner.authorize(plan); var duplicate = f.owner.authorize(plan); var frame = first.latch(plan)) {
            var thread = new Thread(f.owner::requestInvalidation); thread.start(); thread.join(5000); assertFalse(thread.isAlive());
            rejected(first, plan); rejected(duplicate, plan);
            assertThrows(SurvivalRefusal.class, () -> f.owner.authorize(plan));
            assertThrows(SurvivalRefusal.class, f.owner::capture);
            assertEquals(plan.identity(), frame.planIdentity());
            f.owner.close(); f.owner.requestInvalidation();
        }
    }

    @Test void closedHandlesRefuseAndLastHistoricalAliasDrainsReservations() throws Exception {
        try (var f = new Fixture("drain")) {
            var content = f.owner.capture(); var plan = plan(content); var lease = f.owner.authorize(plan);
            var frame = lease.latch(plan); long all = f.budget.evidence().bytes();
            lease.close(); lease.close(); rejected(lease, plan); content.close(); plan.close();
            assertTrue(f.budget.evidence().bytes() > 0); assertTrue(f.budget.evidence().bytes() < all);
            assertTrue(frame.pageCount() > 0);
            var failure = new AtomicReference<Throwable>();
            var other = new Thread(() -> {
                try { assertThrows(SurvivalRefusal.class, frame::close); assertThrows(SurvivalRefusal.class, frame::pageCount); }
                catch (Throwable problem) { failure.set(problem); }
            });
            other.start(); other.join(5000); assertFalse(other.isAlive()); assertNull(failure.get());
            frame.close(); frame.close(); assertThrows(SurvivalRefusal.class, frame::planIdentity);
            assertEquals(0, f.budget.evidence().bytes());
        }
    }

    @Test void admissionFailureRetainsNoUnchargedLeaseOrFrame() throws Exception {
        try (var f = new Fixture("pool"); var content = f.owner.capture(); var plan = plan(content)) {
            long before = f.budget.evidence().bytes();
            var blocker = f.budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, (32L << 20) - before);
            assertThrows(IllegalStateException.class, () -> f.owner.authorize(plan));
            blocker.releaseAfterPhysicalDrain(); assertEquals(before, f.budget.evidence().bytes());
            try (var lease = f.owner.authorize(plan)) {
                long withLease = f.budget.evidence().bytes();
                var frameBlocker = f.budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, (32L << 20) - withLease);
                assertThrows(IllegalStateException.class, () -> lease.latch(plan));
                frameBlocker.releaseAfterPhysicalDrain(); assertEquals(withLease, f.budget.evidence().bytes());
            }
            assertEquals(before, f.budget.evidence().bytes());
        }
    }
}
