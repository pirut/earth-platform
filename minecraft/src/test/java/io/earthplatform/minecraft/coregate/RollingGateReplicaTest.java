package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeLease;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeVersion;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageMetadata;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.nio.file.Path;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RollingGateReplicaTest {
    static final CubePos PIN = CoreGateProfile.BASE.center();
    static EnginePaletteContext palette;
    static WorldIdentity world;
    static NativeCubeCodec codec;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze()); world = CoreGateProfile.BASE.world(palette);
        codec = new NativeCubeCodec(world, palette);
    }

    static RollingGateReplica empty() { return new RollingGateReplica(world, 1, palette, CoreGateProfile.BASE); }
    static EngineCubeSnapshot snapshot(CubePos position, long epoch, long lease, long revision) {
        return codec.materialize(position, ImmutableCube.uniform(Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState())), epoch, lease, revision);
    }
    static EngineCubeSnapshot edited(long lease, long revision, int... indices) {
        int[] blocks = new int[4096];
        java.util.Arrays.fill(blocks, Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState()));
        for (int index : indices) blocks[index] = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
        return codec.materialize(PIN, ImmutableCube.copyOf(blocks), 1, lease, revision);
    }

    /** Mirrors only canonical server lease assignment; no actor/native-buffer implementation reused. */
    static final class Driver {
        List<CubeLease> current = List.of();
        CubePos center = PIN;
        long generation, nextLease = 1;
        final Map<CubePos, Long> revisions = new HashMap<>();
        StageMetadata stage(CubePos candidate) {
            boolean initial = generation == 0;
            var plan = initial ? RollingCubePlan.stationary(PIN, center) : RollingCubePlan.create(PIN, center, candidate);
            var positions = initial ? plan.committedOwners() : plan.nextCommittedOwners();
            var old = new HashMap<CubePos, Long>(); for (var lease : current) old.put(lease.position(), lease.leaseGeneration());
            var leases = new ArrayList<CubeLease>(); var added = new ArrayList<CubePos>();
            for (var position : positions) {
                Long lease = old.get(position);
                if (lease == null) { lease = nextLease++; added.add(position); }
                leases.add(new CubeLease(position, lease));
            }
            return new StageMetadata(world, 1, generation + 1, initial, PIN, center, candidate, current, leases, added,
                    initial ? List.of() : plan.retiredOwners(), initial ? plan.currentTargets() : plan.candidateTargets());
        }
        void fill(RollingGateReplica replica, StageMetadata metadata) {
            for (var lease : metadata.candidateLeases()) if (metadata.newPositions().contains(lease.position()))
                replica.acceptSnapshot(metadata.generation(), snapshot(lease.position(), 1, lease.leaseGeneration(), revisions.getOrDefault(lease.position(), 0L)));
        }
        StageReady ready(StageMetadata metadata) {
            return new StageReady(world, 1, metadata.generation(), metadata.candidateCenter(), metadata.candidateLeases().stream()
                    .map(lease -> new CubeVersion(lease.position(), lease.leaseGeneration(), revisions.getOrDefault(lease.position(), 0L))).toList());
        }
        StageReady currentReady() {
            return new StageReady(world, 1, generation, center, current.stream()
                    .map(lease -> new CubeVersion(lease.position(), lease.leaseGeneration(), revisions.getOrDefault(lease.position(), 0L))).toList());
        }
        void commit(RollingGateReplica replica, StageMetadata metadata) {
            replica.acceptMetadata(metadata); fill(replica, metadata); replica.acceptReady(ready(metadata)); replica.commitStage(metadata.generation());
            current = metadata.candidateLeases(); center = metadata.candidateCenter(); generation = metadata.generation();
        }
        void open(RollingGateReplica replica) { commit(replica, stage(PIN)); }
        long pinLease() { return current.stream().filter(l -> l.position().equals(PIN)).findFirst().orElseThrow().leaseGeneration(); }
    }

    @Test void unknownRemainsAbsentUntilAnExactSnapshotAndInitialReadyIsExplicit() {
        try (var replica = empty()) {
            var driver = new Driver(); var metadata = driver.stage(PIN);
            assertEquals(-1, replica.revision()); assertFalse(replica.cubes().containsKey(PIN));
            assertThrows(IllegalStateException.class, replica::beginEdit);
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, snapshot(PIN, 1, 1, 0)));
            replica.acceptMetadata(metadata); driver.fill(replica, metadata);
            assertEquals(125, replica.cubes().size()); assertFalse(replica.stageReady());
            assertThrows(IllegalStateException.class, () -> replica.commitStage(1));
            assertThrows(IllegalStateException.class, replica::candidateManifestDigest);
            replica.acceptReady(driver.ready(metadata)); assertTrue(replica.stageReady());
            assertEquals(64, replica.candidateManifestDigest().length());
            assertThrows(IllegalArgumentException.class, () -> replica.acceptReady(driver.ready(metadata)));
            replica.commitStage(1); assertEquals(1, replica.currentActorGeneration());
            assertEquals(0, replica.revision()); assertTrue(replica.cubes().get(PIN).state(1, 1, 1).isAir());
            assertThrows(UnsupportedOperationException.class, () -> replica.cubes().clear());
        }
    }

    @Test void metadataRejectsWrongIdentityCanonicalLeaseAndReplayBeforeMutatingAdmission() {
        try (var replica = empty()) {
            var driver = new Driver(); var m = driver.stage(PIN);
            var other = new WorldIdentity(42, world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion());
            assertThrows(IllegalArgumentException.class, () -> replica.acceptMetadata(new StageMetadata(other, 1, 1, true, PIN, PIN, PIN,
                    m.currentLeases(), m.candidateLeases(), m.newPositions(), m.retiredPositions(), m.renderTargets())));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptMetadata(new StageMetadata(world, 2, 1, true, PIN, PIN, PIN,
                    m.currentLeases(), m.candidateLeases(), m.newPositions(), m.retiredPositions(), m.renderTargets())));
            var leases = new ArrayList<>(m.candidateLeases()); leases.set(0, new CubeLease(leases.getFirst().position(), 900));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptMetadata(new StageMetadata(world, 1, 1, true, PIN, PIN, PIN,
                    m.currentLeases(), leases, m.newPositions(), m.retiredPositions(), m.renderTargets())));
            assertEquals(0, replica.admission().view().metrics().leasesCreated());
            driver.commit(replica, m);
            assertThrows(IllegalArgumentException.class, () -> replica.acceptMetadata(m));
            assertEquals(125, replica.admission().view().metrics().leasesCreated());
        }
    }

    @Test void snapshotsRejectWorldEpochLeaseXYZGenerationAndDuplicates() {
        try (var replica = empty()) {
            var driver = new Driver(); var m = driver.stage(PIN); replica.acceptMetadata(m);
            var lease = m.candidateLeases().getFirst(); var p = lease.position();
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(2, snapshot(p, 1, lease.leaseGeneration(), 0)));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, snapshot(p, 2, lease.leaseGeneration(), 0)));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, snapshot(p, 1, lease.leaseGeneration() + 1, 0)));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, snapshot(PIN.offset(9, 0, 9), 1, lease.leaseGeneration(), 0)));
            var other = new WorldIdentity(9, world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion());
            var wrong = new NativeCubeCodec(other, palette).materialize(p, ImmutableCube.uniform(0), 1, lease.leaseGeneration(), 0);
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, wrong)); assertTrue(replica.cubes().isEmpty());
            var correct = snapshot(p, 1, lease.leaseGeneration(), 0); replica.acceptSnapshot(1, correct);
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, correct));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, snapshot(p, 1, lease.leaseGeneration(), 1)));
            assertEquals(1, replica.cubes().size());
        }
    }

    @Test void readyRequiresEveryExactVersionAndRetainsFenceAfterBadManifests() {
        try (var replica = empty()) {
            var driver = new Driver(); var m = driver.stage(PIN); replica.acceptMetadata(m);
            assertThrows(IllegalArgumentException.class, () -> replica.acceptReady(driver.ready(m)));
            driver.fill(replica, m); var exact = driver.ready(m);
            var versions = new ArrayList<>(exact.versions()); var first = versions.getFirst();
            versions.set(0, new CubeVersion(first.position(), first.leaseGeneration(), 1));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptReady(new StageReady(world, 1, 1, PIN, versions)));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptReady(new StageReady(world, 1, 2, PIN, exact.versions())));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptReady(new StageReady(world, 1, 1, PIN.offset(1, 0, 0), exact.versions())));
            assertFalse(replica.stageReady()); replica.acceptReady(exact); assertTrue(replica.stageReady());
        }
    }

    @Test void stagingRetainsOldCubeObjectsAndAdmissionUntilExplicitGpuRetirementCommit() {
        try (var replica = empty()) {
            var driver = new Driver(); driver.open(replica); var admission = replica.admission();
            var old = new HashMap<>(replica.cubes()); var oldHeaders = admission.view().leases();
            var m = driver.stage(PIN.offset(1, 1, 1)); replica.acceptMetadata(m);
            assertEquals(186, admission.view().metrics().activeLeases()); assertEquals(PIN, replica.currentCenter());
            assertThrows(IllegalStateException.class, replica::beginEdit);
            assertThrows(IllegalStateException.class, () -> replica.acceptMetadata(m));
            driver.fill(replica, m); replica.acceptReady(driver.ready(m));
            for (var p : old.keySet()) assertSame(old.get(p), replica.cubes().get(p));
            for (var l : oldHeaders) assertTrue(admission.isCurrentLoaded(new CubeStreamAdmission.Header(world, 1, l.lease().position(), l.lease().generation(), 0)));
            assertEquals(125, replica.currentPositions125().size()); assertEquals(125, replica.candidatePositions125().size());
            assertEquals(186, replica.cubes().size()); assertThrows(IllegalStateException.class, () -> replica.commitStage(9));
            replica.commitStage(m.generation()); assertSame(admission, replica.admission());
            assertEquals(125, replica.cubes().size()); assertEquals(PIN.offset(1, 1, 1), replica.currentCenter());
            for (var p : m.retiredPositions()) assertFalse(replica.cubes().containsKey(p));
            for (var l : m.candidateLeases()) assertEquals(l.leaseGeneration(), replica.cubes().get(l.position()).snapshot().leaseGeneration());
        }
    }

    @Test void oldLeaseIsRejectedWhenCoordinateReentersWithFreshCanonicalLease() {
        try (var replica = empty()) {
            var driver = new Driver(); driver.open(replica);
            var original = replica.cubes().get(PIN.offset(-2, -2, -2)).snapshot();
            driver.commit(replica, driver.stage(PIN.offset(1, 1, 1)));
            var back = driver.stage(PIN); replica.acceptMetadata(back);
            assertTrue(back.newPositions().contains(original.position()));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(back.generation(), original));
            driver.fill(replica, back); replica.acceptReady(driver.ready(back)); replica.commitStage(back.generation());
            assertTrue(replica.cubes().get(original.position()).snapshot().leaseGeneration() > original.leaseGeneration());
        }
    }

    @Test void farPinAchievesExact213UnionAnd152CommittedWithoutPinNeighborhoodMeshes() {
        try (var replica = empty()) {
            var driver = new Driver(); driver.open(replica);
            for (int i = 1; i <= 5; i++) driver.commit(replica, driver.stage(PIN.offset(i, i, i)));
            assertEquals(152, replica.cubes().size()); assertEquals(28, replica.currentTargets().size());
            assertTrue(replica.currentTargets().contains(PIN)); assertFalse(replica.currentTargets().contains(PIN.offset(1, 0, 0)));
            var next = driver.stage(PIN.offset(6, 6, 6)); replica.acceptMetadata(next); driver.fill(replica, next);
            assertEquals(61, next.newPositions().size()); assertEquals(213, replica.cubes().size());
            assertEquals(213, replica.admission().view().metrics().activeLeases());
            replica.acceptReady(driver.ready(next)); replica.commitStage(next.generation());
            assertEquals(152, replica.cubes().size()); assertEquals(152, replica.admission().view().metrics().activeLeases());
            assertEquals(0, replica.revision());
        }
    }

    @Test void durableEditRequiresExactReplacementThenAckThenFreshCommittedReady() {
        try (var replica = empty()) {
            var driver = new Driver(); driver.open(replica); long lease = driver.pinLease(); var oldReady = driver.currentReady();
            String before = replica.currentManifestDigest();
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, edited(lease, 1, CubePos.index(4, 1, 4))));
            assertThrows(IllegalStateException.class, () -> replica.acknowledge("APPLIED", 1));
            replica.beginEdit(); assertThrows(IllegalStateException.class, replica::currentManifestDigest);
            assertThrows(IllegalStateException.class, () -> replica.acknowledge("APPLIED", 1));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, edited(lease, 0, CubePos.index(4, 1, 4))));
            replica.acceptSnapshot(1, edited(lease, 1, CubePos.index(4, 1, 4)));
            assertThrows(IllegalStateException.class, () -> replica.acceptReady(oldReady));
            assertThrows(IllegalStateException.class, () -> replica.acknowledge("STALE_REVISION", 0));
            replica.acknowledge("APPLIED", 1); assertTrue(replica.awaitingCommittedReady());
            assertThrows(IllegalStateException.class, replica::beginEdit);
            assertThrows(IllegalArgumentException.class, () -> replica.acceptReady(oldReady));
            driver.revisions.put(PIN, 1L); replica.acceptReady(driver.currentReady());
            assertFalse(replica.awaitingCommittedReady()); assertEquals(1, replica.acknowledgedRevision());
            assertNotEquals(before, replica.currentManifestDigest());
            assertThrows(IllegalStateException.class, () -> replica.acceptReady(driver.currentReady()));
            replica.beginEdit(); assertThrows(IllegalStateException.class, () -> replica.acknowledge("APPLIED", 1));
        }
    }

    @Test void immutableShellAndMultipleBlockChangeCancelAdmissionWithoutBurningRevision() {
        try (var replica = empty()) {
            var driver = new Driver(); driver.open(replica); replica.beginEdit(); long lease = driver.pinLease();
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, edited(lease, 1, CubePos.index(0, 1, 4))));
            assertThrows(IllegalArgumentException.class, () -> replica.acceptSnapshot(1, edited(lease, 1, CubePos.index(1, 1, 1), CubePos.index(2, 1, 1))));
            assertEquals(0, replica.revision()); assertEquals(0, replica.admission().view().metrics().pendingAdmissions());
            replica.acceptSnapshot(1, edited(lease, 1, CubePos.index(4, 1, 4))); replica.acknowledge("APPLIED", 1);
            assertEquals(1, replica.revision());
        }
    }

    @Test void failedDurabilityRemainsFencedAndRejectedEditNeedsUnchangedReady() {
        try (var replica = empty()) {
            var driver = new Driver(); driver.open(replica); replica.beginEdit();
            assertThrows(IllegalStateException.class, () -> replica.acknowledge("SAVE_FAILED", -1));
            assertTrue(replica.paused()); assertEquals(0, replica.revision());
            assertThrows(IllegalStateException.class, () -> replica.acceptReady(driver.currentReady()));
            assertThrows(IllegalStateException.class, () -> replica.acknowledge("NOT_READY", -1));
            assertThrows(IllegalStateException.class, () -> replica.acceptSnapshot(1, edited(driver.pinLease(), 1, CubePos.index(4, 1, 4))));
        }
        try (var replica = empty()) {
            var driver = new Driver(); driver.open(replica); replica.beginEdit();
            replica.acknowledge("IMMUTABLE_BOUNDARY", -1); assertFalse(replica.paused());
            assertTrue(replica.awaitingCommittedReady()); replica.acceptReady(driver.currentReady());
            assertEquals(0, replica.revision()); replica.beginEdit();
        }
    }

    @Test void closeDuringPartialStageRetiresTokensAndCannotBeReopened() {
        var replica = empty(); var driver = new Driver(); var m = driver.stage(PIN); replica.acceptMetadata(m);
        var lease = m.candidateLeases().getFirst(); var admission = replica.admission();
        var token = admission.admit(new CubeStreamAdmission.Header(world, 1, lease.position(), lease.leaseGeneration(), 0)).token();
        var view = replica.cubes(); replica.close(); replica.close();
        assertEquals(CubeStreamAdmission.State.CLOSED, admission.view().state()); assertTrue(view.isEmpty());
        assertEquals(CubeStreamAdmission.Rejection.NOT_OPEN, admission.commit(token, () -> fail("Closed token must never run installer")));
        assertThrows(IllegalStateException.class, () -> replica.acceptMetadata(m));
        assertThrows(IllegalStateException.class, () -> replica.acceptSnapshot(1, snapshot(lease.position(), 1, lease.leaseGeneration(), 0)));
    }

    @Test void ownerThreadIsRequiredEvenForReadOnlyViews() throws InterruptedException {
        try (var replica = empty()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> { try { replica.cubes(); } catch (Throwable t) { failure.set(t); } });
            thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, failure.get());
        }
    }

    @Test void realActorWireReplicaRoundTripDiagonalReverseAndDurableEdit(@TempDir Path directory) throws Exception {
        var actor = RollingCubeActor.open(directory.resolve("rolling"), world, CoreGateProfile.BASE.provider(palette), palette, 1, PIN);
        var inbox = new ArrayDeque<RollingCubeActor.Event>();
        try (var replica = empty()) {
            StageReady initial = deliverAndCommit(actor, inbox, replica);
            assertEquals(125, replica.cubes().size());
            CubePos corner = PIN.offset(-2, -2, -2);
            long firstLease = replica.cubes().get(corner).snapshot().leaseGeneration();
            assertEquals(RollingCubeActor.Admission.QUEUED, actor.requestCenter(PIN.offset(1, 1, 1)));
            StageReady moved = deliverAndCommit(actor, inbox, replica);
            assertEquals(2, moved.generation()); assertFalse(replica.cubes().containsKey(corner));
            assertEquals(RollingCubeActor.Admission.QUEUED, actor.requestCenter(PIN));
            StageReady returned = deliverAndCommit(actor, inbox, replica);
            assertEquals(3, returned.generation()); assertTrue(replica.cubes().get(corner).snapshot().leaseGeneration() > firstLease);
            var pin = returned.versions().stream().filter(v -> v.position().equals(PIN)).findFirst().orElseThrow();
            assertEquals(initial.versions().stream().filter(v -> v.position().equals(PIN)).findFirst().orElseThrow().leaseGeneration(), pin.leaseGeneration());
            replica.beginEdit();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (actor.diagnostics().lifecycle() != RollingCubeActor.Lifecycle.OPEN) {
                if (System.nanoTime() >= deadline) fail("Actor did not publish committed lifecycle");
                Thread.sleep(1);
            }
            var request = new RollingCubeActor.EditRequest(world, 1, PIN, pin.leaseGeneration(), pin.revision(),
                    4, 129, 4, Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState()));
            assertEquals(RollingCubeActor.Admission.QUEUED, actor.tryEdit(request));
            var replacement = assertInstanceOf(RollingCubeActor.Snapshot.class, nextEvent(actor, inbox));
            deliverSnapshot(replica, replacement);
            var acknowledgement = assertInstanceOf(RollingCubeActor.EditAck.class, nextEvent(actor, inbox));
            assertEquals(RollingCubeActor.EditStatus.APPLIED, acknowledgement.status());
            replica.acknowledge(acknowledgement.status().name(), acknowledgement.revision());
            var revised = returned.versions().stream().map(v -> v.position().equals(PIN)
                    ? new CubeVersion(PIN, v.leaseGeneration(), acknowledgement.revision()) : v).toList();
            var editedReady = roundTrip(new StageReady(world, 1, returned.generation(), PIN, revised));
            replica.acceptReady(editedReady);
            assertEquals(1, replica.revision()); assertFalse(replica.paused()); assertFalse(replica.awaitingCommittedReady());
            assertTrue(replica.cubes().get(PIN).state(4, 1, 4).is(Blocks.DIORITE));
            assertEquals(RollingGateWire.manifestDigest(editedReady), replica.currentManifestDigest());
        } finally {
            actor.close(); assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        }
    }

    private static StageReady deliverAndCommit(RollingCubeActor actor, ArrayDeque<RollingCubeActor.Event> inbox,
                                               RollingGateReplica replica) throws Exception {
        var supplied = assertInstanceOf(StageMetadata.class, nextEvent(actor, inbox));
        var metadataFrame = CoreGatePayload.create(b -> RollingGateWire.writeMetadata(b, supplied));
        var metadataBytes = metadataFrame.open();
        try {
            replica.acceptMetadata(RollingGateWire.readMetadata(metadataBytes, world, 1)); assertFalse(metadataBytes.isReadable());
        } finally { metadataBytes.release(); }
        int snapshots = 0; RollingCubeActor.Event event;
        while ((event = nextEvent(actor, inbox)) instanceof RollingCubeActor.Snapshot snapshot) {
            deliverSnapshot(replica, snapshot); snapshots++;
        }
        assertEquals(supplied.newPositions().size(), snapshots);
        assertEquals(supplied.initial() ? 125 : 61, snapshots);
        var ready = roundTrip(assertInstanceOf(StageReady.class, event)); replica.acceptReady(ready);
        assertEquals(RollingGateWire.manifestDigest(ready), replica.candidateManifestDigest());
        replica.commitStage(ready.generation());
        assertEquals(RollingCubeActor.Admission.QUEUED, actor.commitPrepared(ready.generation()));
        var committed = assertInstanceOf(RollingCubeActor.Committed.class, nextEvent(actor, inbox));
        assertEquals(ready.generation(), committed.generation()); assertEquals(replica.currentCenter(), committed.center());
        return ready;
    }

    private static void deliverSnapshot(RollingGateReplica replica, RollingCubeActor.Snapshot snapshot) {
        var frame = CoreGatePayload.create(b -> { b.writeLong(snapshot.generation()); snapshot.snapshot().write(b); });
        var bytes = frame.open();
        try { replica.acceptSnapshot(bytes.readLong(), EngineCubeSnapshot.readFrame(bytes, world, 1, palette)); }
        finally { bytes.release(); }
    }

    private static StageReady roundTrip(StageReady supplied) {
        var frame = CoreGatePayload.create(b -> RollingGateWire.writeReady(b, supplied)); var bytes = frame.open();
        try { var ready = RollingGateWire.readReady(bytes, world, 1); assertFalse(bytes.isReadable()); return ready; }
        finally { bytes.release(); }
    }

    private static RollingCubeActor.Event nextEvent(RollingCubeActor actor, ArrayDeque<RollingCubeActor.Event> inbox) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (inbox.isEmpty()) {
            inbox.addAll(actor.pollEvents(2));
            if (!inbox.isEmpty()) break;
            if (System.nanoTime() >= deadline) fail("Timed out awaiting bounded actor output");
            Thread.sleep(1);
        }
        var event = inbox.removeFirst();
        if (event instanceof RollingCubeActor.Failure failure) fail(failure.detail());
        return event;
    }
}
