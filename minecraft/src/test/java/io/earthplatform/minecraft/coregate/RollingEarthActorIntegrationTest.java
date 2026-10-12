package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.FileEditStore;
import io.earthplatform.minecraft.enginebridge.BoundedProviderPreparation;
import io.earthplatform.minecraft.enginebridge.NativeCollisionView;
import io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real offline Earth source, bounded bootstrap, durable actor and native collision, with no Level or GUI. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(60)
class RollingEarthActorIntegrationTest {
    private static final long EPOCH = 71;
    private static final String WORKER = "earth-actor-integration-provider";
    private static EnginePaletteContext palette;
    private static RollingEarthPreparation.Prepared earth;
    private static int air;
    @TempDir Path temporary;
    private final List<RollingCubeActor> actors = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final Map<RollingCubeActor, ArrayDeque<Event>> received = new HashMap<>();

    @BeforeAll static void prepareRealSourceOnBoundedWorker() throws Exception {
        // NeoForge unitTest owns native bootstrap; use the registered palette, never a fake registry of block IDs.
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(biomes.freeze());
        air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        Thread owner = Thread.currentThread();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var stamp = new BoundedProviderPreparation.Stamp(EPOCH, 1);
        try (var pool = new BoundedProviderPreparation.Pool(1, WORKER)) {
            try (var ticket = pool.submit(stamp, cancellation -> {
                assertNotSame(owner, Thread.currentThread());
                entered.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("Preparation gate timed out");
                return RollingEarthPreparation.prepare(palette, cancellation::throwIfCancelled);
            })) {
                assertTrue(entered.await(15, TimeUnit.SECONDS));
                assertEquals(BoundedProviderPreparation.State.PENDING, ticket.state());
                assertEquals(1, pool.diagnostics().running());
                assertEquals(1, pool.diagnostics().outstanding());
                assertEquals(0, pool.diagnostics().queued());
                assertTrue(ticket.takeReady(stamp).isEmpty());
                release.countDown();
                await(ticket::physicalSettled, "real Earth source preparation");
                assertEquals(BoundedProviderPreparation.State.READY, ticket.state(), () -> ticket.failure().toString());
                assertTrue(ticket.takeReady(new BoundedProviderPreparation.Stamp(EPOCH + 1, 1)).isEmpty());
                assertTrue(ticket.takeReady(new BoundedProviderPreparation.Stamp(EPOCH, 2)).isEmpty());
                assertEquals(1, pool.diagnostics().completedUnclaimed());
                earth = ticket.takeReady(stamp).orElseThrow();
                assertEquals(BoundedProviderPreparation.State.CLAIMED, ticket.state());
                assertTrue(ticket.takeReady(stamp).isEmpty());
                assertEquals(0, pool.diagnostics().running());
                assertEquals(0, pool.diagnostics().outstanding());
                assertEquals(1, pool.diagnostics().submitted());
                assertEquals(1, pool.diagnostics().settled());
                assertEquals(0, pool.diagnostics().failed());
            } finally { release.countDown(); }
        }
    }

    @AfterEach void drainActors() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        actors.forEach(RollingCubeActor::close);
        for (var actor : actors) assertTrue(actor.closed().get(15, TimeUnit.SECONDS).successful());
    }

    @Test void preparedSourcePinsExactMeasuredDescriptorAndCannotAdoptAnalyticStores() throws Exception {
        var descriptor = earth.descriptor();
        var nativeSource = assertInstanceOf(NativeEarthCubeProvider.class, earth.provider().delegate());
        assertTrue(earth.workerThread().startsWith(WORKER + "-"));
        assertTrue(earth.elapsedNanos() > 0);
        assertTrue(nativeSource.naturalRelief());
        assertEquals(nativeSource.identity().generatorVersion() + "/" + RollingGateFixture.VERSION,
                descriptor.world().generatorVersion());
        assertEquals(nativeSource.identity().datasetVersion(), descriptor.world().datasetVersion());
        assertEquals(earth.provider().identity(), descriptor.world());
        assertEquals(earth.sampler().identity(), descriptor.world().datasetVersion());
        assertTrue(earth.sampler().supportsConcurrentReads());
        assertEquals(nativeSource.sourceUrl(), earth.sourceUrl());
        assertEquals(nativeSource.verticalDatum(), earth.verticalDatum());
        assertFalse(earth.sourceUrl().isBlank()); assertFalse(earth.verticalDatum().isBlank());
        assertEquals(7506, descriptor.anchorY(), "The checked-in source must retain this measured interior anchor");
        assertEquals(7506, earth.sampler().surfaceY(RollingEarthPreparation.ANCHOR_X, RollingEarthPreparation.ANCHOR_Z));
        assertEquals(new CubePos(604683, 469, -194530), descriptor.pinCenter());
        assertEquals(descriptor.pinCenter(), descriptor.initialCenter());
        assertEquals(descriptor.pinCenter(), descriptor.target());
        assertEquals(9674936.5, descriptor.entry().x);
        assertEquals(7509.0, descriptor.entry().y);
        assertEquals(-3112471.5, descriptor.entry().z);
        assertEquals(9674936, descriptor.fixedFarCenterX());
        assertEquals(-3112472, descriptor.fixedFarCenterZ());
        assertEquals(512, descriptor.fixedFarRadius());
        assertTrue(descriptor.naturalRelief());
        assertTrue(descriptor.containsBody(descriptor.walkEntryBody()));
        String frozenV1StoreFingerprint = RollingLegacyIdentityIndependentReviewTest.frozenV1StoreFingerprint(descriptor, palette.fingerprint());
        assertEquals("earthplatform-earth-player-gate-v1/" + frozenV1StoreFingerprint, descriptor.saveDirectory());
        assertNotEquals(frozenV1StoreFingerprint, descriptor.fingerprint(), "The v2 session policy must retain the exact frozen v1 durable store");
        var wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            descriptor.write(wire);
            assertEquals(descriptor, RollingSessionDescriptor.read(wire, earth.provider().identity(), palette));
            assertFalse(wire.isReadable());
        } finally { wire.release(); }

        Path earthDirectory = temporary.resolve(descriptor.saveDirectory());
        try (var store = new FileEditStore(earthDirectory, descriptor.world())) {
            assertEquals(descriptor.world(), store.identity());
        }
        for (var profile : CoreGateProfile.values()) {
            var analytic = RollingSessionDescriptor.analytic(profile, palette);
            assertEquals(RollingGateFixture.provider(palette, profile).identity(), analytic.world());
            assertEquals(RollingGateFixture.directory(profile), analytic.saveDirectory());
            assertNotEquals(descriptor.world(), analytic.world());
            assertNotEquals(descriptor.fingerprint(), analytic.fingerprint());
            assertNotEquals(descriptor.saveDirectory(), analytic.saveDirectory());
            assertThrows(IllegalArgumentException.class, () -> descriptor.requirePreparedWorld(analytic.world()));
            assertThrows(IOException.class, () -> new FileEditStore(earthDirectory, analytic.world()));
            Path analyticDirectory = temporary.resolve(analytic.saveDirectory());
            try (var store = new FileEditStore(analyticDirectory, analytic.world())) {
                assertEquals(analytic.world(), store.identity());
            }
            assertThrows(IOException.class, () -> new FileEditStore(analyticDirectory, descriptor.world()));
        }
        // Failed mismatched opens release ownership and leave the accepted manifest unchanged.
        try (var store = new FileEditStore(earthDirectory, descriptor.world())) {
            assertEquals(0, store.load(descriptor.pinCenter()).snapshot().revision());
        }
    }

    @Test void real125CubeActorPublishesDurableEditRoamsIn3dRetainsPinAndReopensExactRevision() throws Exception {
        var descriptor = earth.descriptor();
        var pin = descriptor.pinCenter();
        var ground = ground();
        var snapshots = new HashMap<CubePos, EngineCubeSnapshot>();
        var actor = open(EPOCH);
        var initial = prepare(actor, snapshots, EPOCH);
        assertTrue(initial.metadata().initial());
        assertEquals(125, initial.metadata().newPositions().size());
        assertEquals(125, initial.ready().versions().size());
        assertEquals(27, initial.metadata().renderTargets().size());
        assertTrue(initial.metadata().currentLeases().isEmpty());
        assertTrue(initial.ready().versions().stream().allMatch(v -> v.revision() == 0));
        assertCollisionReady(initial, snapshots, EPOCH, false);
        commit(actor, initial);
        var original = snapshots.get(pin);
        assertFalse(original.materialize(palette).get(CubePos.local(ground.getX()), CubePos.local(ground.getY()),
                CubePos.local(ground.getZ())).isAir());

        var edit = removeGround(initial.version(pin), EPOCH);
        assertEquals(Admission.QUEUED, actor.tryEdit(edit));
        var replacement = assertInstanceOf(Snapshot.class, next(actor));
        assertEquals(initial.ready().generation(), replacement.generation());
        assertEquals(pin, replacement.snapshot().position());
        assertEquals(1, replacement.snapshot().revision());
        assertEquals(original.leaseGeneration(), replacement.snapshot().leaseGeneration());
        assertTrue(replacement.snapshot().materialize(palette).get(CubePos.local(ground.getX()),
                CubePos.local(ground.getY()), CubePos.local(ground.getZ())).isAir());
        var ack = assertInstanceOf(EditAck.class, next(actor));
        assertEquals(edit, ack.request()); assertEquals(EditStatus.APPLIED, ack.status()); assertEquals(1, ack.revision());
        snapshots.put(pin, replacement.snapshot());
        assertEquals(descriptor.anchorY(), earth.sampler().surfaceY(ground.getX(), ground.getZ()),
                "Optional source summary remains explicitly unedited");

        CubePos current = pin;
        var pinned = RollingCubePlan.stationary(pin, pin).pinnedOwners();
        var initialVersions = new HashMap<CubePos, CubeVersion>();
        initial.ready().versions().forEach(v -> initialVersions.put(v.position(), v));
        // Four adjacent diagonal steps make the moving125 and the fixed27 fully disjoint.
        for (int step = 1; step <= 4; step++) {
            var candidate = pin.offset(step, step, step);
            descriptor.requireAllowedTransition(current, candidate);
            var plan = RollingCubePlan.create(pin, current, candidate);
            assertEquals(Admission.QUEUED, actor.requestCenter(candidate));
            var moved = prepare(actor, snapshots, EPOCH);
            assertFalse(moved.metadata().initial());
            assertEquals(current, moved.metadata().currentCenter());
            assertEquals(candidate, moved.ready().candidateCenter());
            assertEquals(plan.addedOwners(), moved.metadata().newPositions());
            assertEquals(plan.retiredOwners(), moved.metadata().retiredPositions());
            assertEquals(plan.currentOwners().size(), 125);
            assertEquals(61, moved.metadata().newPositions().size());
            assertEquals(plan.stagedOwners().size(), snapshots.size());
            assertEquals(current, actor.diagnostics().currentCenter(), "Ready does not retire the old authority");
            for (var p : pinned) {
                assertEquals(initialVersions.get(p).leaseGeneration(), moved.version(p).leaseGeneration());
                assertEquals(p.equals(pin) ? 1 : 0, moved.version(p).revision());
            }
            assertCollisionReady(moved, snapshots, EPOCH, true);
            commit(actor, moved);
            snapshots.keySet().retainAll(plan.nextCommittedOwners());
            assertEquals(plan.nextCommittedOwners().size(), snapshots.size());
            current = candidate;
        }
        assertEquals(152, snapshots.size());
        assertEquals(1, snapshots.get(pin).revision());
        assertFalse(RollingCubePlan.stationary(pin, current).currentOwners().contains(pin));
        assertEquals(1, actor.diagnostics().admittedEdits());
        closeAndCheck(actor);
        assertSavedGround();

        snapshots.clear();
        var reopened = open(EPOCH + 1);
        var restored = prepare(reopened, snapshots, EPOCH + 1);
        assertEquals(pin, restored.metadata().pinCenter());
        assertEquals(pin, restored.ready().candidateCenter());
        assertEquals(125, restored.ready().versions().size());
        assertEquals(1, restored.version(pin).revision());
        assertEquals(1, snapshots.get(pin).revision());
        assertCollisionReady(restored, snapshots, EPOCH + 1, true);
        commit(reopened, restored);
        closeAndCheck(reopened);
        assertSavedGround();
    }

    @Test void closeDuringAdmittedEarthSaveWaitsForDurabilityThenReopensWithoutFurtherTicks() throws Exception {
        var actor = open(EPOCH);
        var snapshots = new HashMap<CubePos, EngineCubeSnapshot>();
        var initial = prepare(actor, snapshots, EPOCH); commit(actor, initial);
        // Same finite IO gate as RollingCubeActorTest: pause the real store worker, never replace it.
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); gates.add(release);
        var io = (ThreadPoolExecutor) field(field(field(actor, "host"), "world"), "io");
        io.execute(() -> {
            entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Save gate timed out");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        });
        assertTrue(entered.await(15, TimeUnit.SECONDS));
        var edit = removeGround(initial.version(earth.descriptor().pinCenter()), EPOCH);
        assertEquals(Admission.QUEUED, actor.tryEdit(edit));
        await(() -> actor.diagnostics().admittedEdits() == 1, "durable edit admission");
        actor.close();
        assertFalse(actor.closed().isDone(), "An admitted save still owns the directory until IO settles");
        assertEquals(Admission.CLOSING, actor.requestCenter(earth.descriptor().pinCenter()));
        release.countDown();
        assertTrue(actor.closed().get(15, TimeUnit.SECONDS).successful());
        assertEquals(1, assertInstanceOf(Snapshot.class, next(actor)).snapshot().revision());
        var saved = assertInstanceOf(EditAck.class, next(actor));
        assertEquals(edit, saved.request()); assertEquals(EditStatus.APPLIED, saved.status()); assertEquals(1, saved.revision());
        assertTrue(assertInstanceOf(Closed.class, next(actor)).successful());
        assertSavedGround();
        snapshots.clear();
        var reopened = open(EPOCH + 1);
        var restored = prepare(reopened, snapshots, EPOCH + 1);
        assertEquals(1, restored.version(earth.descriptor().pinCenter()).revision());
        assertCollisionReady(restored, snapshots, EPOCH + 1, true);
        commit(reopened, restored); closeAndCheck(reopened);
    }

    private RollingCubeActor open(long epoch) {
        var descriptor = earth.descriptor();
        descriptor.requirePreparedWorld(earth.provider().identity());
        descriptor.requireAllowedWindow(descriptor.initialCenter());
        var actor = RollingCubeActor.open(temporary.resolve(descriptor.saveDirectory()), descriptor.world(),
                earth.provider(), palette, epoch, descriptor.initialCenter());
        actors.add(actor); received.put(actor, new ArrayDeque<>()); return actor;
    }
    private record Prepared(StageMetadata metadata, StageReady ready) {
        CubeVersion version(CubePos position) {
            return ready.versions().stream().filter(v -> v.position().equals(position)).findFirst().orElseThrow();
        }
    }
    private Prepared prepare(RollingCubeActor actor, Map<CubePos, EngineCubeSnapshot> snapshots, long epoch) throws Exception {
        var metadata = assertInstanceOf(StageMetadata.class, next(actor));
        assertEquals(earth.descriptor().world(), metadata.world()); assertEquals(epoch, metadata.epoch());
        assertEquals(earth.descriptor().pinCenter(), metadata.pinCenter());
        earth.descriptor().requireAllowedTransition(metadata.currentCenter(), metadata.candidateCenter());
        var positions = new ArrayList<CubePos>();
        Event event;
        while ((event = next(actor)) instanceof Snapshot snapshot) {
            assertEquals(metadata.generation(), snapshot.generation());
            assertEquals(metadata.world(), snapshot.snapshot().world()); assertEquals(epoch, snapshot.snapshot().epoch());
            assertNull(snapshots.put(snapshot.snapshot().position(), snapshot.snapshot()), "Only newly admitted owners stream");
            positions.add(snapshot.snapshot().position()); assertBounds(actor);
        }
        var ready = assertInstanceOf(StageReady.class, event);
        assertEquals(metadata.generation(), ready.generation()); assertEquals(metadata.world(), ready.world());
        assertEquals(epoch, ready.epoch()); assertEquals(metadata.candidateCenter(), ready.candidateCenter());
        assertEquals(metadata.newPositions(), positions);
        assertEquals(metadata.candidateLeases().stream().map(CubeLease::position).toList(),
                ready.versions().stream().map(CubeVersion::position).toList());
        assertEquals(metadata.candidateLeases().stream().map(CubeLease::leaseGeneration).toList(),
                ready.versions().stream().map(CubeVersion::leaseGeneration).toList());
        var expected = RollingCubePlan.stationary(earth.descriptor().pinCenter(), ready.candidateCenter()).committedOwners();
        assertEquals(expected, ready.versions().stream().map(CubeVersion::position).toList());
        for (var version : ready.versions()) {
            var snapshot = snapshots.get(version.position()); assertNotNull(snapshot);
            assertEquals(ready.world(), snapshot.world()); assertEquals(epoch, snapshot.epoch());
            assertEquals(version.leaseGeneration(), snapshot.leaseGeneration()); assertEquals(version.revision(), snapshot.revision());
        }
        assertBounds(actor); return new Prepared(metadata, ready);
    }
    private static void assertCollisionReady(Prepared stage, Map<CubePos, EngineCubeSnapshot> snapshots,
                                             long epoch, boolean edited) {
        var window = new HashMap<CubePos, EngineCubeSnapshot>();
        var plan = RollingCubePlan.stationary(earth.descriptor().pinCenter(), stage.ready().candidateCenter());
        plan.currentOwners().forEach(p -> window.put(p, snapshots.get(p)));
        assertEquals(125, window.size());
        try (var collision = new NativeCollisionView(earth.descriptor().world(), epoch, palette, window, () -> true)) {
            assertEquals(125, collision.ownerCount()); assertTrue(collision.isCurrent());
            assertEquals(125, collision.dependencies().stamps().size());
            for (var position : window.keySet()) {
                var probe = new BlockPos(Math.toIntExact(position.minBlockX() + 8), Math.toIntExact(position.minBlockY() + 8),
                        Math.toIntExact(position.minBlockZ() + 8));
                assertTrue(collision.read(probe).known(), "Unknown collision in admitted Earth owner " + position);
            }
            if (window.containsKey(earth.descriptor().pinCenter())) {
                var ground = collision.read(ground()); assertTrue(ground.known()); assertEquals(edited, ground.state().isAir());
                assertTrue(collision.read(ground().above()).state().isAir());
            }
            assertTrue(collision.isCurrent()); assertEquals(0, collision.counters().boundaryReads());
        }
    }
    private void commit(RollingCubeActor actor, Prepared stage) throws Exception {
        assertEquals(Admission.QUEUED, actor.commitPrepared(stage.ready().generation()));
        var committed = assertInstanceOf(Committed.class, next(actor));
        assertEquals(stage.ready().world(), committed.world()); assertEquals(stage.ready().epoch(), committed.epoch());
        assertEquals(stage.ready().generation(), committed.generation()); assertEquals(stage.ready().candidateCenter(), committed.center());
        await(() -> actor.diagnostics().committedGeneration() == committed.generation()
                && actor.diagnostics().stagedGeneration() == 0, "committed actor accounting");
        assertEquals(stage.ready().versions().size(), actor.diagnostics().residentCubes()); assertBounds(actor);
    }
    private void closeAndCheck(RollingCubeActor actor) throws Exception {
        int ticketsAtClose = actor.diagnostics().tickets();
        assertEquals(2, ticketsAtClose, "Committed owner has one moving ticket and one fixed-pin ticket");
        actor.close(); assertTrue(actor.closed().get(15, TimeUnit.SECONDS).successful());
        assertTrue(assertInstanceOf(Closed.class, next(actor)).successful());
        // Completion is published just before the actor's final counters, so wait for that update.
        await(() -> actor.diagnostics().residentCubes() == 0 && actor.diagnostics().physicalProviders() == 0,
                "closed actor resident/provider drain; last counters=" + actor.diagnostics());
        var drained = actor.diagnostics();
        assertEquals(RollingCubeActor.Lifecycle.CLOSED, drained.lifecycle(), drained::toString);
        assertEquals(0, drained.readyCubes(), drained::toString);
        assertEquals(0, drained.encodedSnapshotCaches(), drained::toString);
        assertEquals(0, drained.retainedVersionSlots(), drained::toString);
        assertEquals(0, drained.queuedGeneration(), drained::toString);
        // CubicWorld.closeAsync clears residents, loader reservations and IO but preserves its
        // final CubeTickets bookkeeping. This count is not a live resource/ownership measure.
        assertEquals(ticketsAtClose, drained.tickets(), drained::toString);
    }
    private void assertSavedGround() throws IOException {
        try (var store = new FileEditStore(temporary.resolve(earth.descriptor().saveDirectory()), earth.descriptor().world())) {
            var saved = store.load(earth.descriptor().pinCenter()).snapshot();
            assertEquals(1, saved.revision());
            assertEquals(Map.of(CubePos.indexOfBlock(ground().getX(), ground().getY(), ground().getZ()), air), saved.blocks());
        }
    }
    private static EditRequest removeGround(CubeVersion version, long epoch) {
        var ground = ground();
        assertTrue(RollingCubePlan.isEditableInterior(version.position(), ground.getX(), ground.getY(), ground.getZ()));
        return new EditRequest(earth.descriptor().world(), epoch, version.position(), version.leaseGeneration(), version.revision(),
                ground.getX(), ground.getY(), ground.getZ(), air);
    }
    private static BlockPos ground() {
        return new BlockPos(Math.toIntExact(RollingEarthPreparation.ANCHOR_X), earth.descriptor().anchorY(),
                Math.toIntExact(RollingEarthPreparation.ANCHOR_Z));
    }
    private Event next(RollingCubeActor actor) throws Exception {
        var queue = received.get(actor);
        await(() -> {
            if (queue.isEmpty()) {
                var batch = actor.pollEvents(Integer.MAX_VALUE); assertTrue(batch.size() <= MAX_EVENTS_PER_POLL); queue.addAll(batch);
            }
            return !queue.isEmpty();
        }, "Earth actor event: " + actor.diagnostics());
        var event = queue.removeFirst(); if (event instanceof Failure failed) fail(failed.detail()); return event;
    }
    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (Thread.interrupted()) throw new InterruptedException(description);
            if (System.nanoTime() >= deadline) fail("Timed out waiting for " + description);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
    private static void assertBounds(RollingCubeActor actor) {
        var d = actor.diagnostics();
        assertTrue(d.residentCubes() <= MAX_RESIDENT_CUBES); assertTrue(d.encodedSnapshotCaches() <= MAX_RESIDENT_CUBES);
        assertTrue(d.retainedSnapshotReferenceSlots() <= MAX_SNAPSHOT_REFERENCE_SLOTS);
        assertTrue(d.retainedVersionSlots() <= MAX_VERSION_SLOTS); assertTrue(d.queuedEvents() <= MAX_RESULT_EVENTS);
        assertTrue(d.queuedCommands() <= MAX_PENDING_EDITS + MAX_PENDING_COMMITS + MAX_PENDING_GOALS);
        assertTrue(d.physicalProviders() <= MAX_GENERATION_WORKERS); assertTrue(d.queuedGeneration() <= MAX_RESIDENT_CUBES);
        assertTrue(d.tickets() <= MAX_TICKETS);
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}
