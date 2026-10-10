package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.FileEditStore;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

class RollingCubeActorTest {
    private static EnginePaletteContext palette;
    private static AnalyticNativeCubeProvider provider;
    private static WorldIdentity world;
    private static int air, diorite;
    private static final CubePos CENTER = new CubePos(0, 8, 0);
    @TempDir Path temporary;
    private final List<RollingCubeActor> actors = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final List<CompletableFuture<ImmutableCube>> pendingSources = new ArrayList<>();
    private final Map<RollingCubeActor, ArrayDeque<Event>> received = new HashMap<>();

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        provider = new AnalyticNativeCubeProvider(42, AnalyticNativeCubeProvider.Config.flat(128), palette);
        world = provider.identity();
        air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        diorite = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
    }
    @AfterEach void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        pendingSources.forEach(source -> source.completeExceptionally(new IOException("Test cleanup")));
        actors.forEach(RollingCubeActor::close);
        for (RollingCubeActor actor : actors) actor.closed().get(10, TimeUnit.SECONDS);
    }
    private RollingCubeActor open() { return open(temporary.resolve("world"), provider, 7); }
    private RollingCubeActor open(Path path, CubeProvider source, long epoch) {
        RollingCubeActor actor = RollingCubeActor.open(path, world, source, palette, epoch, CENTER);
        actors.add(actor); received.put(actor, new ArrayDeque<>()); return actor;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for rolling actor");
            Thread.sleep(1);
        }
    }
    private Event next(RollingCubeActor actor) throws Exception {
        ArrayDeque<Event> queue = received.get(actor);
        await(() -> {
            if (queue.isEmpty()) {
                List<Event> batch = actor.pollEvents(Integer.MAX_VALUE);
                assertTrue(batch.size() <= MAX_EVENTS_PER_POLL); queue.addAll(batch);
            }
            return !queue.isEmpty();
        });
        Event event = queue.removeFirst();
        if (event instanceof Failure failed) fail(failed.detail());
        return event;
    }
    private record Prepared(StageMetadata metadata, List<Snapshot> snapshots, StageReady ready) {
        private CubeVersion version(CubePos position) {
            return ready.versions().stream().filter(version -> version.position().equals(position)).findFirst().orElseThrow();
        }
        private EngineCubeSnapshot snapshot(CubePos position) {
            return snapshots.stream().map(Snapshot::snapshot).filter(snapshot -> snapshot.position().equals(position)).findFirst().orElseThrow();
        }
    }
    private Prepared prepare(RollingCubeActor actor) throws Exception {
        StageMetadata metadata = assertInstanceOf(StageMetadata.class, next(actor));
        List<Snapshot> snapshots = new ArrayList<>();
        Event event;
        while ((event = next(actor)) instanceof Snapshot snapshot) {
            assertEquals(metadata.generation(), snapshot.generation()); snapshots.add(snapshot);
        }
        StageReady ready = assertInstanceOf(StageReady.class, event);
        assertEquals(metadata.generation(), ready.generation());
        assertEquals(metadata.candidateCenter(), ready.candidateCenter());
        assertEquals(metadata.newPositions(), snapshots.stream().map(snapshot -> snapshot.snapshot().position()).toList());
        assertEquals(metadata.candidateLeases().stream().map(CubeLease::position).toList(),
                ready.versions().stream().map(CubeVersion::position).toList());
        assertBounds(actor);
        return new Prepared(metadata, List.copyOf(snapshots), ready);
    }
    private void commit(RollingCubeActor actor, Prepared prepared) throws Exception {
        assertEquals(Admission.QUEUED, actor.commitPrepared(prepared.ready().generation()));
        Committed committed = assertInstanceOf(Committed.class, next(actor));
        assertEquals(prepared.ready().generation(), committed.generation());
        assertEquals(prepared.ready().candidateCenter(), committed.center());
    }
    private static void assertBounds(RollingCubeActor actor) {
        Diagnostics d = actor.diagnostics();
        assertTrue(d.residentCubes() <= MAX_RESIDENT_CUBES);
        assertTrue(d.encodedSnapshotCaches() <= MAX_RESIDENT_CUBES);
        assertTrue(d.retainedSnapshotReferenceSlots() <= MAX_SNAPSHOT_REFERENCE_SLOTS);
        assertTrue(d.retainedVersionSlots() <= MAX_VERSION_SLOTS);
        assertTrue(d.queuedEvents() <= MAX_RESULT_EVENTS);
        assertTrue(d.queuedCommands() <= MAX_PENDING_EDITS + MAX_PENDING_COMMITS + MAX_PENDING_GOALS);
        assertTrue(d.physicalProviders() <= MAX_GENERATION_WORKERS);
        assertTrue(d.queuedGeneration() <= MAX_RESIDENT_CUBES);
        assertTrue(d.tickets() <= MAX_TICKETS);
    }
    private static EditRequest request(CubeVersion version, int state) {
        return new EditRequest(world, 7, version.position(), version.leaseGeneration(), version.revision(), 4, 129, 4, state);
    }
    private EditAck ack(RollingCubeActor actor, List<Event> events) throws Exception {
        Event event;
        do { event = next(actor); events.add(event); } while (!(event instanceof EditAck));
        return (EditAck) event;
    }
    private void reject(RollingCubeActor actor, EditRequest request, EditStatus expected) throws Exception {
        assertEquals(Admission.QUEUED, actor.tryEdit(request));
        List<Event> events = new ArrayList<>();
        assertEquals(expected, ack(actor, events).status()); assertEquals(1, events.size());
    }

    @Test void startupHasImmutableExactMetadataAndRequiresExplicitDeliveredReadyCommit() throws Exception {
        RollingCubeActor actor = open();
        await(() -> actor.diagnostics().queuedEvents() == MAX_RESULT_EVENTS
                && actor.diagnostics().publishedSnapshots() == 3);
        assertEquals(Admission.NOT_READY, actor.commitPrepared(1));
        assertEquals(Admission.STALE_GENERATION, actor.commitPrepared(2));
        assertEquals(3, actor.diagnostics().publishedSnapshots());
        Thread.sleep(30);
        assertEquals(3, actor.diagnostics().publishedSnapshots(), "Backpressure prevents encoding without a result slot");
        assertTrue(actor.pollEvents(0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> actor.pollEvents(-1));
        Prepared initial = prepare(actor);
        assertTrue(initial.metadata().initial()); assertEquals(CENTER, initial.metadata().pinCenter());
        assertTrue(initial.metadata().currentLeases().isEmpty()); assertEquals(125, initial.snapshots().size());
        assertEquals(27, initial.metadata().renderTargets().size());
        assertEquals(125, initial.ready().versions().size());
        for (int i = 0; i < initial.ready().versions().size(); i++) {
            assertEquals(i + 1L, initial.ready().versions().get(i).leaseGeneration());
            assertEquals(0, initial.ready().versions().get(i).revision());
        }
        assertThrows(UnsupportedOperationException.class, () -> initial.metadata().candidateLeases().clear());
        assertThrows(UnsupportedOperationException.class, () -> initial.metadata().newPositions().clear());
        assertThrows(UnsupportedOperationException.class, () -> initial.ready().versions().clear());
        assertEquals(Admission.NOT_READY, actor.tryEdit(request(initial.version(CENTER), diorite)));
        Thread.sleep(25); assertEquals(0, actor.diagnostics().committedGeneration());
        commit(actor, initial);
        await(() -> actor.diagnostics().committedGeneration() == 1);
        assertEquals(125, actor.diagnostics().residentCubes());
        assertEquals(Admission.STALE_GENERATION, actor.commitPrepared(1));
    }

    @Test void adjacentStageRetainsOldTicketUntilAckAndOnlyStreamsNewCoordinates() throws Exception {
        RollingCubeActor actor = open(); Prepared initial = prepare(actor); commit(actor, initial);
        CubePos candidate = new CubePos(1, 8, 0);
        assertEquals(Admission.QUEUED, actor.requestCenter(candidate));
        Prepared moved = prepare(actor);
        assertFalse(moved.metadata().initial()); assertEquals(candidate, moved.ready().candidateCenter());
        assertEquals(25, moved.snapshots().size()); assertEquals(25, moved.metadata().retiredPositions().size());
        assertEquals(initial.version(CENTER).leaseGeneration(), moved.version(CENTER).leaseGeneration());
        await(() -> actor.diagnostics().residentCubes() == 150);
        Thread.sleep(25); assertEquals(150, actor.diagnostics().residentCubes());
        assertEquals(CENTER, actor.diagnostics().currentCenter());
        assertEquals(Admission.STALE_GENERATION, actor.commitPrepared(initial.ready().generation()));
        assertEquals(Admission.NOT_READY, actor.tryEdit(request(initial.version(CENTER), diorite)));
        commit(actor, moved);
        await(() -> actor.diagnostics().stagedGeneration() == 0);
        assertEquals(125, actor.diagnostics().residentCubes()); assertEquals(2, actor.diagnostics().tickets());
    }

    @Test void goalFloodCoalescesWithoutReplacingOrSpawningASecondStage() throws Exception {
        RollingCubeActor actor = open(); Prepared initial = prepare(actor); commit(actor, initial);
        actor.requestCenter(new CubePos(50, 8, 0)); Prepared first = prepare(actor);
        assertEquals(new CubePos(1, 8, 0), first.ready().candidateCenter());
        for (int i = 0; i < 2000; i++) actor.requestCenter(new CubePos(i % 2 == 0 ? -100 : 100, 8, 0));
        actor.requestCenter(CENTER);
        Thread.sleep(30);
        assertEquals(first.ready().generation(), actor.diagnostics().stagedGeneration());
        assertEquals(1, actor.diagnostics().queuedGoals()); assertEquals(0, actor.diagnostics().queuedEvents());
        assertEquals(150, actor.diagnostics().residentCubes()); assertBounds(actor);
        commit(actor, first); Prepared reversed = prepare(actor);
        assertEquals(CENTER, reversed.ready().candidateCenter());
        commit(actor, reversed);
        await(() -> actor.diagnostics().queuedGoals() == 0);
        assertEquals(0, actor.diagnostics().stagedGeneration());
    }

    @Test void reentryGetsFreshLeaseButContinuouslyPinnedAndOverlappingCubesKeepTheirs() throws Exception {
        RollingCubeActor actor = open(); Prepared initial = prepare(actor); commit(actor, initial);
        CubePos retired = new CubePos(-2, 8, 0);
        actor.requestCenter(new CubePos(1, 8, 0)); Prepared next = prepare(actor); commit(actor, next);
        actor.requestCenter(CENTER); Prepared returned = prepare(actor);
        assertTrue(returned.version(retired).leaseGeneration() > initial.version(retired).leaseGeneration());
        assertTrue(returned.version(retired).leaseGeneration() > 150);
        assertEquals(initial.version(CENTER), returned.version(CENTER));
        assertEquals(25, returned.snapshots().size()); commit(actor, returned);
    }

    @Test void diagonalRoamingHits213DistinctCoordinatesAndKeepsFixedPinAndFiniteCopies() throws Exception {
        RollingCubeActor actor = open(); Prepared initial = prepare(actor); commit(actor, initial);
        CubePos goal = new CubePos(8, 16, 8); actor.requestCenter(goal);
        int maxResidents = 0;
        for (int step = 1; step <= 8; step++) {
            Prepared next = prepare(actor);
            assertEquals(new CubePos(step, 8 + step, step), next.ready().candidateCenter());
            assertTrue(next.snapshots().size() <= 61); assertTrue(next.ready().versions().size() <= 152);
            assertEquals(initial.version(CENTER), next.version(CENTER));
            maxResidents = Math.max(maxResidents, actor.diagnostics().residentCubes());
            assertBounds(actor); commit(actor, next);
        }
        await(() -> actor.diagnostics().queuedGoals() == 0);
        assertEquals(213, maxResidents); assertEquals(152, actor.diagnostics().residentCubes());
        assertEquals(152, actor.diagnostics().encodedSnapshotCaches());
    }

    @Test void initialCommitCannotBeSkippedByGoalsAndAllInvalidRequestsAreFenced() throws Exception {
        RollingCubeActor actor = open(); actor.requestCenter(new CubePos(50, 8, 0));
        Prepared initial = prepare(actor);
        assertEquals(CENTER, initial.ready().candidateCenter());
        assertThrows(IllegalArgumentException.class, () -> actor.requestCenter(new CubePos(Integer.MAX_VALUE, 8, 0)));
        assertThrows(IllegalArgumentException.class, () -> actor.requestCenter(new CubePos(0, 10000, 0)));
        assertEquals(Admission.STALE_GENERATION, actor.commitPrepared(-1));
        assertEquals(Admission.STALE_GENERATION, actor.commitPrepared(Long.MAX_VALUE));
        commit(actor, initial); Prepared first = prepare(actor);
        assertEquals(new CubePos(1, 8, 0), first.ready().candidateCenter());
    }

    @Test void durableEditSnapshotPrecedesAckAndBlocksNewStageUntilAckIsConsumed() throws Exception {
        RollingCubeActor actor = open(); Prepared initial = prepare(actor); commit(actor, initial);
        EditRequest request = request(initial.version(CENTER), diorite);
        assertEquals(Admission.QUEUED, actor.tryEdit(request));
        assertEquals(Admission.BUSY, actor.tryEdit(request));
        actor.requestCenter(new CubePos(1, 8, 0));
        await(() -> actor.diagnostics().queuedEvents() == 2);
        assertTrue(actor.diagnostics().pendingEdit()); assertEquals(0, actor.diagnostics().stagedGeneration());
        Thread.sleep(25); assertEquals(0, actor.diagnostics().stagedGeneration());
        List<Event> events = new ArrayList<>(); EditAck saved = ack(actor, events);
        assertEquals(EditStatus.APPLIED, saved.status()); assertEquals(1, saved.revision());
        Snapshot changed = assertInstanceOf(Snapshot.class, events.getFirst());
        assertEquals(1, changed.generation()); assertEquals(1, changed.snapshot().revision());
        assertSame(Blocks.DIORITE.defaultBlockState(), changed.snapshot().materialize(palette).get(4, 1, 4));
        Prepared next = prepare(actor); assertEquals(1, next.version(CENTER).revision());
        assertFalse(next.metadata().newPositions().contains(CENTER)); commit(actor, next);
        actor.close(); assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        try (FileEditStore store = new FileEditStore(temporary.resolve("world"), world)) {
            assertEquals(1, store.load(CENTER).snapshot().revision());
        }
    }

    @Test void staleIdentityLeaseRevisionAndImmutableShellCannotReachDurableStore() throws Exception {
        RollingCubeActor actor = open(); Prepared initial = prepare(actor); commit(actor, initial);
        CubeVersion version = initial.version(CENTER); long lease = version.leaseGeneration();
        WorldIdentity other = new WorldIdentity(99, world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion());
        reject(actor, new EditRequest(other, 7, CENTER, lease, 0, 4, 129, 4, diorite), EditStatus.WORLD_MISMATCH);
        reject(actor, new EditRequest(world, 8, CENTER, lease, 0, 4, 129, 4, diorite), EditStatus.STALE_EPOCH);
        reject(actor, new EditRequest(world, 7, new CubePos(1, 8, 0), lease, 0, 20, 129, 4, diorite), EditStatus.UNKNOWN_CUBE);
        reject(actor, new EditRequest(world, 7, CENTER, lease + 1, 0, 4, 129, 4, diorite), EditStatus.STALE_LEASE);
        reject(actor, new EditRequest(world, 7, CENTER, lease, 1, 4, 129, 4, diorite), EditStatus.STALE_REVISION);
        reject(actor, new EditRequest(world, 7, CENTER, lease, 0, 20, 129, 4, diorite), EditStatus.POSITION_MISMATCH);
        reject(actor, new EditRequest(world, 7, CENTER, lease, 0, Long.MAX_VALUE, 129, 4, diorite), EditStatus.POSITION_MISMATCH);
        reject(actor, new EditRequest(world, 7, CENTER, lease, 0, 4, 129, 4, -1), EditStatus.INVALID_STATE);
        long[][] shell = {{0,129,4},{15,129,4},{4,128,4},{4,143,4},{4,129,0},{4,129,15}};
        for (long[] p : shell) reject(actor, new EditRequest(world, 7, CENTER, lease, 0, p[0], p[1], p[2], diorite), EditStatus.IMMUTABLE_BOUNDARY);
        assertEquals(0, actor.diagnostics().admittedEdits());
        assertFalse(Files.exists(temporary.resolve("world/cubes/0_8_0.cube")));
    }

    @Test void closeDuringRealSaveDrainsWithoutMoreTicksAndCannotBeExternallyCompleted() throws Exception {
        RollingCubeActor actor = open(); Prepared initial = prepare(actor); commit(actor, initial);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); gates.add(release);
        Object host = field(actor, "host"); Object core = field(host, "world");
        ThreadPoolExecutor io = (ThreadPoolExecutor) field(core, "io");
        io.execute(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException error) { throw new AssertionError(error); } });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertEquals(Admission.QUEUED, actor.tryEdit(request(initial.version(CENTER), diorite)));
        await(() -> actor.diagnostics().admittedEdits() == 1);
        actor.close(); assertSame(actor.closed(), actor.closed());
        assertFalse(actor.closed().cancel(false)); assertFalse(actor.closed().complete(new Closed(true, "forbidden")));
        assertFalse(actor.closed().completeExceptionally(new IOException("forbidden")));
        assertThrows(UnsupportedOperationException.class, () -> actor.closed().obtrudeValue(null));
        assertThrows(UnsupportedOperationException.class, () -> actor.closed().orTimeout(1, TimeUnit.MILLISECONDS));
        assertThrows(UnsupportedOperationException.class, () -> actor.closed().completeAsync(() -> null));
        assertTrue(actor.closed().copy().cancel(false)); assertFalse(actor.closed().isDone());
        assertEquals(Admission.CLOSING, actor.commitPrepared(1));
        assertEquals(Admission.CLOSING, actor.requestCenter(CENTER));
        assertEquals(Admission.CLOSING, actor.tryEdit(request(initial.version(CENTER), diorite)));
        release.countDown(); assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        assertInstanceOf(Snapshot.class, next(actor));
        assertEquals(EditStatus.APPLIED, assertInstanceOf(EditAck.class, next(actor)).status());
        assertInstanceOf(Closed.class, next(actor)); assertBounds(actor);
    }

    @Test void blockedStageKeepsPhysicalProviderOwnershipAndCloseWaitsForSettlement() throws Exception {
        CompletableFuture<ImmutableCube> pending = new CompletableFuture<>(); pendingSources.add(pending);
        AtomicReference<CancellationToken> token = new AtomicReference<>(); CountDownLatch called = new CountDownLatch(1);
        CubeProvider slow = (position, cancellation) -> {
            if (position.x() <= 2) return provider.load(position, cancellation);
            token.set(cancellation); called.countDown(); return pending;
        };
        RollingCubeActor actor = open(temporary.resolve("world"), slow, 7);
        Prepared initial = prepare(actor); commit(actor, initial);
        actor.requestCenter(new CubePos(100, 8, 0));
        assertTrue(called.await(10, TimeUnit.SECONDS));
        StageMetadata staged = assertInstanceOf(StageMetadata.class, next(actor));
        for (int i = 0; i < 100; i++) actor.requestCenter(new CubePos(-i, 8, 0));
        await(() -> actor.diagnostics().stagedGeneration() == staged.generation());
        assertEquals(150, actor.diagnostics().residentCubes()); assertTrue(actor.diagnostics().physicalProviders() <= 2);
        actor.close(); await(() -> token.get().isCancelled());
        assertFalse(actor.closed().isDone(), "Cancellation is not physical settlement");
        assertTrue(actor.diagnostics().physicalProviders() > 0);
        RollingCubeActor conflict = open(temporary.resolve("world"), provider, 8);
        assertFalse(conflict.closed().get(10, TimeUnit.SECONDS).successful());
        pending.complete(ImmutableCube.uniform(air));
        assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        RollingCubeActor reopened = open(temporary.resolve("world"), provider, 9);
        assertEquals(9, prepare(reopened).ready().epoch());
    }

    @Test void fullOutputAndCloseBeforeBootstrapNeverAbandonDirectoryOwnership() throws Exception {
        RollingCubeActor actor = open();
        await(() -> actor.diagnostics().queuedEvents() == MAX_RESULT_EVENTS);
        actor.close(); assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        assertInstanceOf(Closed.class, next(actor));
        RollingCubeActor reopen = open(temporary.resolve("world"), provider, 8);
        reopen.close(); assertTrue(reopen.closed().get(10, TimeUnit.SECONDS).successful());
        try (FileEditStore store = new FileEditStore(temporary.resolve("world"), world)) { assertEquals(world, store.identity()); }
    }

    @Test void failedProviderClosesExplicitlyAndDurableFailureEmitsNoOptimisticReplacement() throws Exception {
        RollingCubeActor broken = open(temporary.resolve("broken"), (position, token) -> CompletableFuture.failedFuture(new IOException("provider failure")), 7);
        assertFalse(broken.closed().get(10, TimeUnit.SECONDS).successful());
        List<Event> terminal = new ArrayList<>(broken.pollEvents(2)); terminal.addAll(broken.pollEvents(2));
        assertTrue(terminal.stream().anyMatch(Failure.class::isInstance)); assertInstanceOf(Closed.class, terminal.getLast());
        RollingCubeActor actor = open(); Prepared initial = prepare(actor); commit(actor, initial);
        Files.write(temporary.resolve("world/cubes/0_8_0.cube"), new byte[]{1});
        assertEquals(Admission.QUEUED, actor.tryEdit(request(initial.version(CENTER), diorite)));
        List<Event> events = new ArrayList<>(); assertEquals(EditStatus.SAVE_FAILED, ack(actor, events).status());
        assertEquals(1, events.size()); assertEquals(125, actor.diagnostics().publishedSnapshots());
    }

    @Test void callerCannotTouchNativeHostAndMetadataListsHaveHardAllocationBounds() throws Exception {
        RollingCubeActor actor = open(); Prepared initial = prepare(actor);
        NativeCubicHost host = (NativeCubicHost) field(actor, "host");
        assertThrows(IllegalStateException.class, host::stats);
        assertThrows(IllegalStateException.class, () -> host.pollSnapshot(CENTER, 7, initial.version(CENTER).leaseGeneration()));
        List<CubeLease> oversized = java.util.Collections.nCopies(MAX_COMMITTED_CUBES + 1, new CubeLease(CENTER, 1));
        assertThrows(IllegalArgumentException.class, () -> new StageMetadata(world, 7, 1, true, CENTER,
                CENTER, CENTER, List.of(), oversized, List.of(), List.of(), List.of()));
        List<CubeVersion> versions = java.util.Collections.nCopies(MAX_COMMITTED_CUBES + 1, new CubeVersion(CENTER, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new StageReady(world, 7, 1, CENTER, versions));
        commit(actor, initial);
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
}
