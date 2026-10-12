package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.survival.interaction.*;
import io.earthplatform.minecraft.survival.world.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpointTest.*;

class SurvivalCheckpointCoordinatorTest {
    @TempDir Path temporary;
    static final SurvivalCheckpointStore.Limits STORE_LIMITS = new SurvivalCheckpointStore.Limits(1, 4 * 1024 * 1024, 4 * 1024 * 1024, 4096);
    @BeforeAll static void init() { setup(); }
    static NativeSurvivalWorld world() {
        return new NativeSurvivalWorld(worldIdentity, palette, BOUNDS,
                new NativeSurvivalWorld.Limits(32, 32, 32, 16, 4096, 128, 32, 65536, 32, 32));
    }
    static NativeWholeAction.Owner actions(NativeSurvivalWorld world) {
        return new NativeWholeAction.Owner(world, registries, (action, effects) -> receipt -> {});
    }
    SurvivalCheckpointStore store(String name) throws IOException { return new SurvivalCheckpointStore(temporary.resolve(name), codec().storageIdentity(), STORE_LIMITS); }
    static void air(NativeSurvivalWorld world) { world.admitGenerated(HIGH.cube(), ImmutableCube.uniform(palette.id(Blocks.AIR.defaultBlockState()))); }
    static void edit(NativeSurvivalWorld world, long sequence) {
        try (var transaction = world.begin(world.actionIdentity(PLAYER, sequence), Map.of(HIGH.cube(), world.stamp(HIGH.cube())))) {
            transaction.set(HIGH, sequence % 2 == 1 ? Blocks.OAK_LOG.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState()); transaction.commit();
        }
    }
    static NativeWholeAction.InventoryView emptyView() {
        return new NativeWholeAction.InventoryView() {
            public int size() { return 41; } public ItemStack getItem(int slot) { return ItemStack.EMPTY; } public int selectedSlot() { return 0; }
        };
    }
    static final class QueuedExecutor implements Executor {
        Runnable queued;
        public void execute(Runnable operation) { if (queued != null) throw new IllegalStateException(); queued = operation; }
        void run() { var operation = queued; queued = null; operation.run(); }
    }
    static void complete(SurvivalCheckpointCoordinator coordinator) { coordinator.submit(Runnable::run); assertTrue(coordinator.poll()); }

    @Test void actualReceiptAcknowledgesOnlyFrozenRevisionsAndLaterActionsRemainDirty() throws Exception {
        var world = world(); air(world); edit(world, 1);
        try (var store = store("later")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary);
            var ticket = coordinator.pendingTicket().orElseThrow(); assertTrue(world.isDirty(HIGH.cube()));
            var executor = new QueuedExecutor(); coordinator.submit(executor);
            assertFalse(coordinator.poll()); assertEquals(0, world.durableCommitSequence());
            edit(world, 2); executor.run();
            assertEquals(0, world.durableCommitSequence(), "Writer completion alone is not acknowledgement");
            assertTrue(coordinator.poll()); assertEquals(ticket.commitSequence(), world.durableCommitSequence());
            assertTrue(world.isDirty(HIGH.cube())); assertTrue(world.hasUncheckpointedActions());
            assertEquals(palette.id(Blocks.OAK_LOG.defaultBlockState()), coordinator.durable().orElseThrow().cubes().get(HIGH.cube()).blocks().get(HIGH.localIndex()));
            assertSame(Blocks.COBBLESTONE.defaultBlockState(), world.read(HIGH));
            assertFalse(world.hasPendingCheckpoint()); assertEquals(1, coordinator.durableGeneration());
        }
    }
    @Test void everyInterruptedStoreBarrierRetainsExactTicketAndRetriesWithoutRecapture() throws Exception {
        for (SurvivalCheckpointStore.Phase phase : SurvivalCheckpointStore.Phase.values()) {
            var world = world(); air(world); edit(world, 1); AtomicBoolean injected = new AtomicBoolean(); AtomicInteger captures = new AtomicInteger();
            try (var store = new SurvivalCheckpointStore(temporary.resolve(phase.name()), codec().storageIdentity(), STORE_LIMITS,
                    current -> { if (current == phase && injected.compareAndSet(false, true)) throw new IOException("interrupted " + phase); })) {
                var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
                coordinator.capture(entering(), () -> { captures.incrementAndGet(); return boundary(); });
                var ticket = coordinator.pendingTicket().orElseThrow(); coordinator.submit(Runnable::run);
                assertFalse(coordinator.poll()); assertEquals(SurvivalCheckpointCoordinator.State.FAILED, coordinator.state());
                assertSame(ticket, coordinator.pendingTicket().orElseThrow()); assertTrue(world.hasPendingCheckpoint()); assertTrue(world.isDirty(HIGH.cube()));
                assertThrows(SurvivalRefusal.class, () -> coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary));
                edit(world, 2); coordinator.retry(); assertSame(ticket, coordinator.pendingTicket().orElseThrow()); complete(coordinator);
                assertEquals(1, captures.get()); assertEquals(1, coordinator.durableGeneration()); assertTrue(world.isDirty(HIGH.cube()));
                assertArrayEquals(codec().encode(coordinator.durable().orElseThrow()), store.load().orElseThrow().payload(SurvivalCheckpointCodec.PAYLOAD));
            }
        }
    }
    @Test void queueRejectionRetainsTheFrozenImageAndOwnerPins() throws Exception {
        var world = world(); air(world); edit(world, 1);
        try (var store = store("queue")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); var ticket = coordinator.pendingTicket().orElseThrow();
            assertThrows(RejectedExecutionException.class, () -> coordinator.submit(task -> { throw new RejectedExecutionException(); }));
            assertSame(ticket, coordinator.pendingTicket().orElseThrow()); assertTrue(world.hasPendingCheckpoint());
            assertThrows(SurvivalRefusal.class, () -> world.releaseClean(HIGH.cube()));
            // An arbitrary Executor may have queued before throwing. Without an authentic drain
            // there is no safe replay, even if this particular test executor did not keep its task.
            assertThrows(SurvivalRefusal.class,coordinator::retry);assertTrue(world.hasPendingCheckpoint());
        }
    }
    @Test void inventoryOnlyCheckpointKeepsReleasedDurableCubes() throws Exception {
        var world = world(); air(world); edit(world, 1);
        try (var store = store("released")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            var saved = coordinator.durable().orElseThrow().cubes().get(HIGH.cube()); world.releaseClean(HIGH.cube());
            try (var effectOnly = world.begin(world.actionIdentity(PLAYER, 2), Map.of())) { effectOnly.commit(); }
            coordinator.capture(new Session(SESSION, SessionPhase.ACTIVE, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary);
            assertTrue(coordinator.pendingTicket().orElseThrow().cubes().isEmpty()); complete(coordinator);
            assertEquals(saved, coordinator.durable().orElseThrow().cubes().get(HIGH.cube()));
            assertEquals(saved, codec().decode(store.load().orElseThrow()).cubes().get(HIGH.cube()));
            assertFalse(world.hasUncheckpointedActions()); assertEquals(2, coordinator.durableGeneration());
        }
    }
    @Test void restartedOwnerTicketOneProducesPersistentGenerationTwo() throws Exception {
        Path path = temporary.resolve("restart"); NativeSurvivalCheckpoint saved;
        try (var store = new SurvivalCheckpointStore(path, codec().storageIdentity(), STORE_LIMITS)) {
            var first = world(); air(first); edit(first, 1);
            var coordinator = new SurvivalCheckpointCoordinator(actions(first), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); complete(coordinator); saved = coordinator.durable().orElseThrow();
        }
        try (var store = new SurvivalCheckpointStore(path, codec().storageIdentity(), STORE_LIMITS)) {
            var second = world();
            var coordinator = new SurvivalCheckpointCoordinator(actions(second), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            // Recover from this owner's independently decoded durable graph, whose payload lease it retains.
            for (var entry : coordinator.durable().orElseThrow().cubes().entrySet()) second.admitRecovered(entry.getKey(), entry.getValue().blocks(), entry.getValue().simulationRevision(), entry.getValue().durableRevision());
            coordinator.capture(new Session(SESSION, SessionPhase.ACTIVE, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary);
            assertEquals(1, coordinator.pendingTicket().orElseThrow().number()); complete(coordinator);
            assertEquals(2, store.load().orElseThrow().generation()); assertEquals(2, coordinator.durableGeneration());
            assertNotEquals(saved.captureOwner(), coordinator.durable().orElseThrow().captureOwner());
        }
    }
    @Test void regeneratedBaselineCannotOverwriteRecoveredEditedLineage() throws Exception {
        try (var store = store("lineage")) {
            var first = world(); air(first); edit(first, 1);
            var coordinator = new SurvivalCheckpointCoordinator(actions(first), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            var replacement = world(); air(replacement);
            var second = new SurvivalCheckpointCoordinator(actions(replacement), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            assertThrows(SurvivalRefusal.class, () -> second.capture(entering(), NativeSurvivalCheckpointTest::boundary));
            assertFalse(replacement.hasPendingCheckpoint()); assertEquals(1, second.durableGeneration());
        }
    }
    @Test void currentAndFailedWholeActionsCannotBecomeDurableBoundaries() throws Exception {
        var world = world(); var actions = new NativeWholeAction.Owner(world, registries, (action, effects) -> receipt -> { throw new IllegalStateException("delivery failed"); });
        try (var store = store("fenced")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions, store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            try (var action = actions.begin(world.actionIdentity(PLAYER, 1), Map.of(), emptyView())) {
                assertThrows(SurvivalRefusal.class, () -> coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary));
                assertThrows(IllegalStateException.class, action::commit);
            }
            assertTrue(actions.fenced());
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary));
            assertFalse(world.hasPendingCheckpoint()); assertTrue(store.load().isEmpty());
        }
    }
    @Test void sourceCannotSneakAnActionIntoTheCaptureBoundary() throws Exception {
        var world = world(); air(world);
        try (var store = store("mutating-source")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(entering(), () -> { edit(world, 1); return boundary(); }));
            assertFalse(world.hasPendingCheckpoint()); assertTrue(world.isDirty(HIGH.cube())); assertTrue(store.load().isEmpty());
        }
    }
    @Test void sourceCannotReplaceRecoveredCubeAtSameAddressWithoutAnAction() throws Exception {
        var world = world(); air(world); edit(world, 1);
        try (var store = store("aba")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            long sequence = world.commitSequence();
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(entering(), () -> {
                world.releaseClean(HIGH.cube()); air(world); return boundary();
            }));
            assertEquals(sequence, world.commitSequence()); assertFalse(world.hasPendingCheckpoint());
            assertEquals(palette.id(Blocks.OAK_LOG.defaultBlockState()), coordinator.completeDurableCubes().get(HIGH.cube()).blocks().get(HIGH.localIndex()));
        }
    }
    @Test void encodingRefusalAfterFreezeRetainsItsExactTicketAndBoundary() throws Exception {
        var world = world();
        int[] dense = new int[CubePos.VOLUME];
        for (int i = 0; i < dense.length; i++) dense[i] = palette.id((i % 2 == 0 ? Blocks.AIR : Blocks.STONE).defaultBlockState());
        world.admitGenerated(HIGH.cube(), ImmutableCube.copyOf(dense)); edit(world, 1);
        var small = new SurvivalCheckpointCodec(binding, new Limits(32, 32, 32, 32, 4096), registries, palette, BOUNDS);
        try (var store = store("encoding")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, small,new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            assertThrows(IllegalArgumentException.class, () -> coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary));
            var ticket = coordinator.pendingTicket().orElseThrow(); assertEquals(SurvivalCheckpointCoordinator.State.FAILED, coordinator.state());
            assertThrows(IllegalArgumentException.class, coordinator::retry); assertSame(ticket, coordinator.pendingTicket().orElseThrow());
            assertTrue(world.hasPendingCheckpoint()); assertTrue(world.isDirty(HIGH.cube())); assertTrue(store.load().isEmpty());
        }
    }
    @Test void escrowPhasesAreConsecutiveAndReturnedCannotRestartTheSameSession() throws Exception {
        var world = world();
        try (var store = store("escrow")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(new Session(SESSION, SessionPhase.ACTIVE, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary));
            for (SessionPhase phase : SessionPhase.values()) {
                coordinator.capture(new Session(SESSION, phase, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            }
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary));
            coordinator.capture(new Session(UUID.randomUUID(), SessionPhase.ENTERING, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            assertEquals(5, coordinator.durableGeneration());
        }
    }
    @Test void cumulativeNativePayloadBudgetRefusesBeforeFreezingAndIncludesEscrow() throws Exception {
        var world = world();
        var small = new SurvivalCheckpointCodec(binding, new Limits(32, 32, 32, 32, 4096), registries, palette, BOUNDS);
        var bigTag = item().tagCopy(); bigTag.putString("NeoForgePayload", "x".repeat(8192));
        var bigItem = NativeItemEntitySnapshot.decode(NativeItemEntitySnapshot.write(bigTag), registries);
        try (var store = store("capture-budget")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, small,new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            AtomicInteger allowance = new AtomicInteger();
            assertThrows(SurvivalRefusal.class, () -> coordinator.captureBounded(entering(), available -> {
                allowance.set(available); return new Boundary(1, inventory(), food(), earthPose(), List.of(bigItem), List.of());
            }));
            assertEquals(4096 - inventory().encodedBytes() - NativeFoodSnapshot.ENCODED_BYTES - ordinaryPose().encodedBytes(), allowance.get()); assertFalse(world.hasPendingCheckpoint());
            assertEquals(SurvivalCheckpointCoordinator.State.IDLE, coordinator.state()); assertTrue(store.load().isEmpty());
        }
    }
    @Test void wrongThreadCannotCaptureOrAcknowledge() throws Exception {
        var world = world();
        try (var store = store("thread")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            AtomicReference<Throwable> caught = new AtomicReference<>();
            Thread other = new Thread(() -> { try { coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); } catch (Throwable failure) { caught.set(failure); } });
            other.start(); other.join(); assertInstanceOf(SurvivalRefusal.class, caught.get()); assertFalse(world.hasPendingCheckpoint());
        }
    }
    @Test void foodOnlyCheckpointPreservesWholeImageAndImmutableOrdinaryFood() throws Exception {
        var world = world(); air(world); edit(world, 1);
        try (var store = store("food-only")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            var changed = new NativeFoodSnapshot(16, 72, 1.5F, 3.75F);
            coordinator.capture(new Session(SESSION, SessionPhase.ACTIVE, inventory(), food(), ordinaryPose()),
                    () -> new Boundary(1234568, inventory(), changed, earthPose(), List.of(), List.of()));
            complete(coordinator);
            var recovered = codec().decode(store.load().orElseThrow());
            assertEquals(changed, recovered.boundary().food());
            assertEquals(food(), recovered.session().ordinaryFood());
            assertEquals(1, recovered.cubes().size()); assertEquals(2, recovered.generation());
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(
                    new Session(SESSION, SessionPhase.ACTIVE, inventory(), changed, ordinaryPose()), NativeSurvivalCheckpointTest::boundary));
        }
    }
    @Test void failedFoodCheckpointKeepsExactFrozenFoodForRetry() throws Exception {
        var world = world(); air(world); AtomicBoolean rejected = new AtomicBoolean();
        try (var store = new SurvivalCheckpointStore(temporary.resolve("food-retry"), codec().storageIdentity(), STORE_LIMITS,
                phase -> { if (phase == SurvivalCheckpointStore.Phase.MANIFEST_SYNCED && rejected.compareAndSet(false, true))
                    throw new IOException("food checkpoint barrier"); })) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            var food = new NativeFoodSnapshot(14, 17, 0, 2.75F);
            coordinator.capture(entering(), () -> new Boundary(1234567, inventory(), food, earthPose(), List.of(), List.of()));
            coordinator.submit(Runnable::run); assertFalse(coordinator.poll());
            coordinator.retry(); complete(coordinator);
            assertEquals(food, codec().decode(store.load().orElseThrow()).boundary().food());
        }
    }


    @Test void poseOnlyCheckpointPreservesWholeImageAndImmutableOrdinaryPose() throws Exception {
        var world = world(); air(world); edit(world, 1);
        var nativeBoundary = model().boundary();
        try (var store = store("pose-only")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), () -> nativeBoundary); complete(coordinator);
            var prior = coordinator.durable().orElseThrow();
            var changed = pose(binding.dimension(), earthPose().y() + 4096, GameType.ADVENTURE, GameType.SURVIVAL);
            coordinator.capture(new Session(SESSION, SessionPhase.ACTIVE, inventory(), food(), ordinaryPose()),
                    () -> new Boundary(nativeBoundary.gameTime() + 1, nativeBoundary.inventory(), nativeBoundary.food(), changed,
                            nativeBoundary.items(), nativeBoundary.scheduledTicks()));
            complete(coordinator);
            var recovered = codec().decode(store.load().orElseThrow());
            assertEquals(changed, recovered.boundary().pose()); assertEquals(ordinaryPose(), recovered.session().ordinaryPose());
            assertNextDurableCubeImage(prior, recovered); assertEquals(prior.actorSequences(), recovered.actorSequences());
            assertEquals(prior.commitSequence(), recovered.commitSequence()); assertEquals(2, recovered.generation());
            assertEquals(nativeBoundary.inventory(), recovered.boundary().inventory()); assertEquals(nativeBoundary.food(), recovered.boundary().food());
            assertEquals(nativeBoundary.items(), recovered.boundary().items()); assertEquals(nativeBoundary.scheduledTicks(), recovered.boundary().scheduledTicks());
            assertFalse(world.hasPendingCheckpoint()); assertFalse(world.hasUncheckpointedActions());
        }
    }
    @Test void ordinaryPoseCannotChangeInAnyDurablePhase() throws Exception {
        var world = world();
        var changed = pose(ordinaryPose().dimension(), ordinaryPose().y() + 4096, ordinaryPose().gameMode(), ordinaryPose().previousGameMode());
        try (var store = store("pose-immutable")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            for (SessionPhase phase : SessionPhase.values()) {
                coordinator.capture(new Session(SESSION, phase, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary);
                complete(coordinator); long generation = coordinator.durableGeneration(); AtomicInteger captures = new AtomicInteger();
                assertThrows(SurvivalRefusal.class, () -> coordinator.capture(new Session(SESSION, phase, inventory(), food(), changed),
                        () -> { captures.incrementAndGet(); return boundary(); }));
                assertEquals(0, captures.get()); assertEquals(generation, coordinator.durableGeneration());
                assertEquals(ordinaryPose(), coordinator.durable().orElseThrow().session().ordinaryPose());
                assertFalse(world.hasPendingCheckpoint()); assertEquals(SurvivalCheckpointCoordinator.State.IDLE, coordinator.state());
            }
        }
    }
    @Test void ordinaryDimensionModesAndAbilitiesCannotChangeWithinAnExistingSession() throws Exception {
        var original = ordinaryPose(); var abilities = original.abilities();
        var changedAbilities = new NativePlayerPoseSnapshot.AbilityState(abilities.invulnerable(), !abilities.flying(), abilities.mayFly(),
                abilities.instantBuild(), abilities.mayBuild(), abilities.flyingSpeed(), abilities.walkingSpeed());
        var variations = List.of(
                pose(ResourceLocation.parse("minecraft:overworld"), original.y(), original.gameMode(), original.previousGameMode()),
                pose(original.dimension(), original.y(), GameType.SURVIVAL, original.previousGameMode()),
                pose(original.dimension(), original.y(), original.gameMode(), GameType.ADVENTURE),
                new NativePlayerPoseSnapshot(original.dimension(), original.x(), original.y(), original.z(), original.yaw(), original.pitch(),
                        original.velocityX(), original.velocityY(), original.velocityZ(), original.fallDistance(), original.onGround(),
                        original.gameMode(), original.previousGameMode(), changedAbilities));
        var world = world();
        try (var store = store("pose-identity")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            for (var changed : variations) {
                assertThrows(SurvivalRefusal.class, () -> coordinator.capture(
                        new Session(SESSION, SessionPhase.ACTIVE, inventory(), food(), changed), NativeSurvivalCheckpointTest::boundary));
                assertFalse(world.hasPendingCheckpoint()); assertEquals(1, coordinator.durableGeneration());
            }
        }
    }
    @Test void freshVisitMayCaptureNewOrdinaryPoseOnlyAfterPriorReturnedTombstone() throws Exception {
        var world = world(); air(world); edit(world, 1);
        var freshPose = pose(ResourceLocation.parse("minecraft:overworld"), 134.125, GameType.ADVENTURE, GameType.SPECTATOR);
        var freshFood = new NativeFoodSnapshot(20, 0, 5, 0);
        var freshInventory = new NativeInventorySnapshot(inventory().slots(), 1);
        var fresh = new Session(new UUID(100, 101), SessionPhase.ENTERING, freshInventory, freshFood, freshPose);
        try (var store = store("pose-new-visit")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            for (SessionPhase phase : SessionPhase.values()) {
                coordinator.capture(new Session(SESSION, phase, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary);
                complete(coordinator);
                if (phase != SessionPhase.RETURNED) {
                    assertThrows(SurvivalRefusal.class, () -> coordinator.capture(fresh, NativeSurvivalCheckpointTest::boundary));
                    assertFalse(world.hasPendingCheckpoint());
                }
            }
            var returned = codec().decode(store.load().orElseThrow());
            assertEquals(SessionPhase.RETURNED, returned.session().phase()); assertEquals(ordinaryPose(), returned.session().ordinaryPose());
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(
                    new Session(SESSION, SessionPhase.ENTERING, freshInventory, freshFood, freshPose), NativeSurvivalCheckpointTest::boundary));
            coordinator.capture(fresh, NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            var recovered = codec().decode(store.load().orElseThrow());
            assertEquals(fresh, recovered.session()); assertEquals(earthPose(), recovered.boundary().pose());
            assertNextDurableCubeImage(returned, recovered); assertEquals(returned.actorSequences(), recovered.actorSequences());
            assertEquals(5, recovered.generation());
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary));
        }
    }
    @Test void poseEscrowCannotSkipOrReversePhasesOrReplaceAnUnfinishedSession() throws Exception {
        var world = world();
        try (var store = store("pose-phases")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            for (SessionPhase phase : List.of(SessionPhase.ACTIVE, SessionPhase.RETURNING, SessionPhase.RETURNED))
                assertThrows(SurvivalRefusal.class, () -> coordinator.capture(new Session(SESSION, phase, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            for (SessionPhase phase : List.of(SessionPhase.RETURNING, SessionPhase.RETURNED))
                assertThrows(SurvivalRefusal.class, () -> coordinator.capture(new Session(SESSION, phase, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary));
            coordinator.capture(new Session(SESSION, SessionPhase.ACTIVE, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary));
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(new Session(UUID.randomUUID(), SessionPhase.ENTERING, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary));
            assertEquals(2, coordinator.durableGeneration()); assertFalse(world.hasPendingCheckpoint());
        }
    }
    @Test void bothPoseChargesRefuseOneByteOverNativeBudgetBeforeFreezing() throws Exception {
        var world = world();
        var small = new SurvivalCheckpointCodec(binding, new Limits(32, 32, 32, 32, 4096), registries, palette, BOUNDS);
        var ordinary = entering();
        int allowance = 4096 - ordinary.ordinaryEscrow().encodedBytes() - ordinary.ordinaryFood().encodedBytes() - ordinary.ordinaryPose().encodedBytes();
        var tag = item().tagCopy(); tag.putString("NeoForgePayload", "");
        var baseItem = NativeItemEntitySnapshot.decode(NativeItemEntitySnapshot.write(tag), registries);
        int baseBytes = checkedBoundaryBytes(inventory(), food(), earthPose(), List.of(baseItem), List.of(), Integer.MAX_VALUE);
        int padding = allowance + 1 - baseBytes; assertTrue(padding >= 0, "Fixture leaves room for an exact one-byte overrun");
        tag.putString("NeoForgePayload", "x".repeat(padding));
        var filledItem = NativeItemEntitySnapshot.decode(NativeItemEntitySnapshot.write(tag), registries);
        var oversized = new Boundary(1, inventory(), food(), earthPose(), List.of(filledItem), List.of());
        assertEquals(allowance + 1, checkedBoundaryBytes(oversized.inventory(), oversized.food(), oversized.pose(), oversized.items(), oversized.scheduledTicks(), Integer.MAX_VALUE));
        try (var store = store("pose-budget")) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, small,new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            AtomicInteger provided = new AtomicInteger();
            assertThrows(SurvivalRefusal.class, () -> coordinator.captureBounded(ordinary, available -> { provided.set(available); return oversized; }));
            assertEquals(allowance, provided.get()); assertFalse(world.hasPendingCheckpoint());
            assertEquals(SurvivalCheckpointCoordinator.State.IDLE, coordinator.state()); assertTrue(store.load().isEmpty());
        }
    }
    @Test void failedPoseCheckpointKeepsExactFrozenBoundaryAndEscrowForRetry() throws Exception {
        var world = world(); air(world); AtomicBoolean rejected = new AtomicBoolean(); AtomicInteger captures = new AtomicInteger();
        var original = model().boundary(); AtomicReference<Boundary> supplied = new AtomicReference<>(original);
        try (var store = new SurvivalCheckpointStore(temporary.resolve("pose-retry"), codec().storageIdentity(), STORE_LIMITS,
                phase -> { if (phase == SurvivalCheckpointStore.Phase.MANIFEST_SYNCED && rejected.compareAndSet(false, true))
                    throw new IOException("pose checkpoint barrier"); })) {
            var coordinator = new SurvivalCheckpointCoordinator(actions(world), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), () -> { captures.incrementAndGet(); return supplied.get(); });
            var ticket = coordinator.pendingTicket().orElseThrow(); coordinator.submit(Runnable::run); assertFalse(coordinator.poll());
            var laterPose = pose(binding.dimension(), earthPose().y() + 4096, GameType.ADVENTURE, GameType.SURVIVAL);
            supplied.set(new Boundary(original.gameTime() + 1, inventory(), food(), laterPose, List.of(), List.of()));
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(entering(), supplied::get));
            coordinator.retry(); assertSame(ticket, coordinator.pendingTicket().orElseThrow()); complete(coordinator);
            var recovered = codec().decode(store.load().orElseThrow());
            assertEquals(1, captures.get()); assertEquals(original, recovered.boundary()); assertEquals(entering(), recovered.session());
            assertEquals(1, recovered.generation());
        }
    }
    @Test void restartedCoordinatorKeepsOrdinaryPoseAuthorityFromVerifiedStore() throws Exception {
        Path path = temporary.resolve("pose-restart");
        try (var store = new SurvivalCheckpointStore(path, codec().storageIdentity(), STORE_LIMITS)) {
            var first = world(); var coordinator = new SurvivalCheckpointCoordinator(actions(first), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            coordinator.capture(entering(), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
        }
        try (var store = new SurvivalCheckpointStore(path, codec().storageIdentity(), STORE_LIMITS)) {
            var second = world(); var coordinator = new SurvivalCheckpointCoordinator(actions(second), store, STORE_LIMITS, codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            assertEquals(ordinaryPose(), coordinator.durable().orElseThrow().session().ordinaryPose());
            var replacement = pose(ResourceLocation.parse("minecraft:overworld"), 64, GameType.SURVIVAL, null);
            assertThrows(SurvivalRefusal.class, () -> coordinator.capture(
                    new Session(SESSION, SessionPhase.ACTIVE, inventory(), food(), replacement), NativeSurvivalCheckpointTest::boundary));
            assertFalse(second.hasPendingCheckpoint());
            coordinator.capture(new Session(SESSION, SessionPhase.ACTIVE, inventory(), food(), ordinaryPose()), NativeSurvivalCheckpointTest::boundary); complete(coordinator);
            assertEquals(ordinaryPose(), codec().decode(store.load().orElseThrow()).session().ordinaryPose()); assertEquals(2, coordinator.durableGeneration());
        }
    }
    static void assertNextDurableCubeImage(NativeSurvivalCheckpoint before, NativeSurvivalCheckpoint after) {
        assertEquals(before.cubes().keySet(), after.cubes().keySet());
        for (var entry : before.cubes().entrySet()) {
            var next = after.cubes().get(entry.getKey());
            assertEquals(entry.getValue().simulationRevision(), next.simulationRevision());
            assertEquals(entry.getValue().blocks(), next.blocks());
            // These fixtures retain all owned cubes, so every frozen durable revision advances.
            assertEquals(entry.getValue().durableRevision() + 1, next.durableRevision());
        }
    }
    @Test void heldCaptureCanRepeatAfterActualStoreAckAndExactRetirement() throws Exception {
        var world=world();air(world);
        try(var store=store("held-repeat")){
            var c=new SurvivalCheckpointCoordinator(actions(world),store,STORE_LIMITS,codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            c.capture(entering(),NativeSurvivalCheckpointTest::boundary);complete(c);
            var initial=c.durable().orElseThrow().session();
            var active=new Session(initial.id(),SessionPhase.ACTIVE,initial.ordinaryEscrow(),initial.ordinaryFood(),initial.ordinaryPose());
            c.capture(active,NativeSurvivalCheckpointTest::boundary);complete(c);
            for(int n=1;n<=5;n++){
                edit(world,n);var before=c.durable().orElseThrow();
                c.captureHeldBounded(active,64*1024,available->boundary());
                var ticket=c.pendingTicket().orElseThrow();long retained=c.retainedPersistenceBytes();
                assertTrue(retained<=SurvivalCheckpointOwnership.CAPACITY);
                var writer=new QueuedExecutor();c.submit(writer);
                assertThrows(SurvivalRefusal.class,()->c.releaseHeldAcknowledgement(ticket,before));
                writer.run();assertTrue(c.poll());var now=c.durable().orElseThrow();
                assertEquals(before.generation()+1,now.generation());assertEquals(n,now.commitSequence());
                assertEquals(retained,c.retainedPersistenceBytes(),"Writer success/ack alone must not retire Host proof aliases");
                assertThrows(SurvivalRefusal.class,()->c.captureHeldBounded(active,64*1024,available->boundary()));
                c.releaseHeldAcknowledgement(ticket,now);assertTrue(c.retainedPersistenceBytes()<retained);
                assertThrows(SurvivalRefusal.class,()->c.releaseHeldAcknowledgement(ticket,now));
                assertArrayEquals(codec().encode(now),store.load().orElseThrow().payload(SurvivalCheckpointCodec.PAYLOAD));
            }
        }
    }
    @Test void queuedThenThrowingExecutorRetainsExactWriterUntilItReallyDrains() throws Exception {
        var world=world();air(world);edit(world,1);
        try(var store=store("uncertain-dispatch")){
            var c=new SurvivalCheckpointCoordinator(actions(world),store,STORE_LIMITS,codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            c.captureHeldBounded(entering(),64*1024,available->boundary());var ticket=c.pendingTicket().orElseThrow();
            AtomicReference<Runnable> held=new AtomicReference<>();
            assertThrows(RejectedExecutionException.class,()->c.submit(task->{held.set(task);throw new RejectedExecutionException("queued then threw");}));
            long charged=c.retainedPersistenceBytes();assertThrows(SurvivalRefusal.class,c::retry);
            held.get().run();assertFalse(c.poll(),"The rejected dispatch still requires exact retry, even if its native write finished");
            c.retry();assertSame(ticket,c.pendingTicket().orElseThrow());assertEquals(charged,c.retainedPersistenceBytes());
            complete(c);var saved=c.durable().orElseThrow();c.releaseHeldAcknowledgement(ticket,saved);
            held.get().run();assertEquals(saved,c.durable().orElseThrow(),"Replayed executor task is an inert tombstone");
        }
    }
    @Test void heldWriterRetryKeepsIdenticalFrozenBoundaryAndFailedReleaseCharge() throws Exception {
        var world=world();air(world);edit(world,1);AtomicBoolean fault=new AtomicBoolean();AtomicInteger captures=new AtomicInteger();
        try(var store=new SurvivalCheckpointStore(temporary.resolve("held-retry"),codec().storageIdentity(),STORE_LIMITS,
                phase->{if(phase==SurvivalCheckpointStore.Phase.CURRENT_REPLACED&&fault.compareAndSet(false,true))throw new IOException("uncertain durable rename");})){
            var c=new SurvivalCheckpointCoordinator(actions(world),store,STORE_LIMITS,codec(),new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20).reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));var frozen=boundary();
            c.captureHeldBounded(entering(),64*1024,available->{captures.incrementAndGet();return frozen;});
            var ticket=c.pendingTicket().orElseThrow();long charged=c.retainedPersistenceBytes();c.submit(Runnable::run);
            assertFalse(c.poll());assertEquals(charged,c.retainedPersistenceBytes());
            assertThrows(SurvivalRefusal.class,()->c.releaseHeldAcknowledgement(ticket,null));
            c.retry();complete(c);var saved=c.durable().orElseThrow();assertSame(frozen,saved.boundary());assertEquals(1,captures.get());
            assertEquals(charged,c.retainedPersistenceBytes());c.releaseHeldAcknowledgement(ticket,saved);
        }
    }

    @Test void actualMemoryReservationIsClaimedBeforeLoadAndCannotBeSpentTwice() throws Exception {
        var world=world();air(world);
        var memory=new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20)
                .reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20);
        try(var store=store("claimed-memory")){
            var c=new SurvivalCheckpointCoordinator(actions(world),store,STORE_LIMITS,codec(),memory);
            assertThrows(IllegalStateException.class,()->new SurvivalCheckpointCoordinator(actions(world),store,STORE_LIMITS,codec(),memory));
            assertThrows(IllegalStateException.class,memory::releaseAfterPhysicalDrain);
            assertThrows(SurvivalRefusal.class,c::requireGlobalPersistenceReservation,"An isolated model reservation is not installed authority");
            assertFalse(memory.released());
        }
    }
    @Test void abortedActionSequenceDriftDuringCaptureCannotFreezeDifferentActorCut() throws Exception {
        var world=world();air(world);
        try(var store=store("actor-cut")){
            var c=new SurvivalCheckpointCoordinator(actions(world),store,STORE_LIMITS,codec(),
                    new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20)
                            .reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            assertThrows(SurvivalRefusal.class,()->c.capture(entering(),()->{
                try(var action=world.begin(world.actionIdentity(PLAYER,1),Map.of(HIGH.cube(),world.stamp(HIGH.cube())))){}
                return boundary();
            }));
            assertEquals(0,world.commitSequence());assertEquals(2,world.nextActionSequence(PLAYER));assertFalse(world.hasPendingCheckpoint());
        }
    }

    @Test void pinnedOldCubeSurvivesAllGenerationsAndFailedCombinedCleanup() throws Exception {
        var world=world();air(world);
        try(var store=store("cube-pin")){
            var c=new SurvivalCheckpointCoordinator(actions(world),store,STORE_LIMITS,codec(),
                    new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20)
                            .reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            c.capture(entering(),NativeSurvivalCheckpointTest::boundary);complete(c);
            var pin=c.pinCurrentDurableCube(world.snapshot(HIGH.cube())).orElseThrow();long withPin=c.retainedPersistenceBytes();
            edit(world,1);
            var session=c.durable().orElseThrow().session();
            var active=new Session(session.id(),SessionPhase.ACTIVE,session.ordinaryEscrow(),session.ordinaryFood(),session.ordinaryPose());
            c.capture(active,NativeSurvivalCheckpointTest::boundary);complete(c); // Ordinary transition must not discard independent pin.
            assertTrue(c.retainedPersistenceBytes()>withPin);
            c.captureHeldBounded(active,64*1024,available->boundary());var ticket=c.pendingTicket().orElseThrow();complete(c);
            var saved=c.durable().orElseThrow();long retained=c.retainedPersistenceBytes();
            assertThrows(SurvivalRefusal.class,()->c.releaseHeldAcknowledgement(ticket,saved,List.of(pin,pin)));
            assertEquals(retained,c.retainedPersistenceBytes(),"Failed cleanup keeps old/frozen ownership intact");
            c.releaseHeldAcknowledgement(ticket,saved,List.of(pin));assertTrue(c.retainedPersistenceBytes()<retained);
            assertThrows(SurvivalRefusal.class,()->c.releaseDurableCubeLeases(List.of(pin)));
        }
    }
    @Test void constructorDecodePeakIncludesCanonicalReencodeAndSeparateItemStacks(){
        assertDoesNotThrow(()->SurvivalCheckpointCoordinator.requireDecodePeak(64*1024,LIMITS,12L<<20));
        assertThrows(SurvivalRefusal.class,()->SurvivalCheckpointCoordinator.requireDecodePeak(4L*1024*1024,LIMITS,12L<<20));
        assertThrows(SurvivalRefusal.class,()->SurvivalCheckpointCoordinator.requireDecodePeak(0,LIMITS,12L<<20));
    }

    @Test void twoPhaseTransitionRetainsBothGraphsUntilExactFinalRelease() throws Exception {
        var world=world();air(world);var purpose=new Object();var calls=new AtomicInteger();
        try(var store=store("transition-graphs")){
            var c=new SurvivalCheckpointCoordinator(actions(world),store,STORE_LIMITS,codec(),
                    new io.earthplatform.minecraft.visual.NativeDistanceCpuBudget(12L<<20)
                            .reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20));
            c.captureTransitionBounded(purpose,entering(),64*1024,available->boundary());
            var firstTicket=c.pendingTicket().orElseThrow();complete(c);var first=c.durable().orElseThrow();
            long firstGraphs=c.retainedPersistenceBytes();
            assertThrows(SurvivalRefusal.class,()->c.releaseTransitionAcknowledgements(purpose,firstTicket,first));
            assertEquals(firstGraphs,c.retainedPersistenceBytes());
            var initial=first.session();var active=new Session(initial.id(),SessionPhase.ACTIVE,initial.ordinaryEscrow(),initial.ordinaryFood(),initial.ordinaryPose());
            assertThrows(SurvivalRefusal.class,()->c.captureTransitionBounded(new Object(),active,64*1024,available->{calls.incrementAndGet();return boundary();}));
            assertEquals(0,calls.get());assertEquals(firstGraphs,c.retainedPersistenceBytes());
            c.captureTransitionBounded(purpose,active,64*1024,available->boundary());
            var lastTicket=c.pendingTicket().orElseThrow();assertTrue(c.retainedPersistenceBytes()>firstGraphs);
            assertTrue(c.retainedPersistenceBytes()<=SurvivalCheckpointOwnership.CAPACITY);complete(c);
            var last=c.durable().orElseThrow();long bothGraphs=c.retainedPersistenceBytes();assertTrue(bothGraphs>firstGraphs);
            assertThrows(SurvivalRefusal.class,()->c.captureTransitionBounded(purpose,active,64*1024,available->{calls.incrementAndGet();return boundary();}));
            assertThrows(SurvivalRefusal.class,()->c.releaseTransitionAcknowledgements(purpose,firstTicket,last));
            assertThrows(SurvivalRefusal.class,()->c.releaseTransitionAcknowledgements(new Object(),lastTicket,last));
            assertEquals(0,calls.get());assertEquals(bothGraphs,c.retainedPersistenceBytes());
            c.releaseTransitionAcknowledgements(purpose,lastTicket,last);assertTrue(c.retainedPersistenceBytes()<bothGraphs);
            assertThrows(SurvivalRefusal.class,()->c.releaseTransitionAcknowledgements(purpose,lastTicket,last));
            edit(world,1);c.captureHeldBounded(active,64*1024,available->boundary());var next=c.pendingTicket().orElseThrow();complete(c);
            c.releaseHeldAcknowledgement(next,c.durable().orElseThrow());
        }
    }

}
