package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import io.earthplatform.minecraft.enginebridge.ReplacementWorkspaces;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent publication adversaries; prior presentation must never become stale current authority. */
class TwoWorkspacePublicationIndependentReviewTest {
    private static final CubePos A = new CubePos(0, 8, 0), B = A.offset(4, 0, 0);
    private static final WorldIdentity WORLD = new WorldIdentity(1, "fixture", "v1", "source", "palette");
    private static final String STORE = "ebd24c87-9e0d-45ed-9e94-e17c177d77e3", INDEX = "a".repeat(64), PALETTE = "b".repeat(64);
    @TempDir Path temporary;

    private record Content(ReplacementSetCertificate certificate, List<NativeReplacementCapture> captures) { }
    private static Content content(long generation, long aRevision, long bRevision, long firstLease) {
        var regions = new ArrayList<ReplacementSetCertificate.Region>();
        var captures = new ArrayList<NativeReplacementCapture>();
        for (CubePos position : List.of(A, B)) {
            long revision = position.equals(A) ? aRevision : bRevision;
            var capture = new NativeReplacementCapture(position, new byte[8000]);
            if (revision > 0) capture = capture.withMaterial(position.minBlockX(), position.minBlockY(), position.minBlockZ(),
                    revision == 1 ? NativeReplacementCapture.STONE : NativeReplacementCapture.DIORITE);
            captures.add(capture);
            var versions = new ArrayList<RollingCubeActor.CubeVersion>();
            for (CubePos dependency : CubeTickets.box(position, 1, 1, 27).stream().sorted().toList())
                versions.add(new RollingCubeActor.CubeVersion(dependency, firstLease++, dependency.equals(position) ? revision : 0));
            regions.add(new ReplacementSetCertificate.Region(position, versions, capture.contentIdentity()));
        }
        return new Content(new ReplacementSetCertificate(WORLD, 5, 1, STORE, INDEX, "provider", PALETTE,
                "material", "detail", generation, regions), List.copyOf(captures));
    }
    private static RollingReplacementSetAuthority mirror() {
        return new RollingReplacementSetAuthority(WORLD, 5, 1, ReplacementWorkspaces.two(A), "provider", PALETTE, "material", "detail");
    }

    @Test void historicalFastPathCannotOverwriteAnAlreadyPreparedNewDurableRevision() {
        var authority = mirror();
        var old = content(1, 1, 0, 1).certificate();
        var next = content(2, 2, 0, 101).certificate();
        authority.propose(old); authority.commit(old.identity()); authority.beginEdit(A); authority.propose(next);
        assertThrows(RuntimeException.class, () -> authority.propose(old));
        assertTrue(authority.isCurrent(next.identity()));
        assertFalse(authority.isCurrent(old.identity()));
        assertTrue(authority.canPresent(old.identity()));
        authority.commit(next.identity());
        assertFalse(authority.canPresent(old.identity()));
    }

    @Test void cancellingNewPreparationCannotMakeOldDurableRevisionCurrentAgain() {
        var authority = mirror();
        var old = content(1, 1, 0, 1).certificate();
        var next = content(2, 2, 0, 101).certificate();
        authority.propose(old); authority.commit(old.identity()); authority.beginEdit(A); authority.propose(next);
        authority.cancelProposal();
        assertThrows(RuntimeException.class, () -> authority.propose(old));
        assertFalse(authority.isCurrent(old.identity()));
        assertFalse(authority.isCurrent(next.identity()));
        assertTrue(authority.canPresent(old.identity()));
        assertThrows(IllegalStateException.class, () -> authority.commit(old.identity()));
    }

    @Test void committedFastPathCannotReplaceAnotherPrivateCompleteSetProposal() {
        var authority = mirror();
        var old = content(1, 1, 0, 1).certificate();
        var refreshed = content(2, 1, 0, 101).certificate();
        authority.propose(old); authority.commit(old.identity()); authority.propose(refreshed);
        assertThrows(RuntimeException.class, () -> authority.propose(old));
        authority.commit(refreshed.identity());
        assertTrue(authority.isCurrent(refreshed.identity()));
        assertThrows(IllegalArgumentException.class, () -> authority.propose(old));
    }

    @Test void safeRefusalRepublishesOnlyExactPriorSetWhileFailedSaveCannotProduceReadiness() {
        var priorContent = content(1, 1, 0, 1);
        var versions = new ArrayList<RollingCubeActor.CubeVersion>();
        long lease = 55;
        for (CubePos position : CubeTickets.box(A, 2, 2, 125).stream().sorted().toList())
            versions.add(new RollingCubeActor.CubeVersion(position, lease++, position.equals(A) ? 1 : 0));
        var prior = new RollingCubeActor.StageReady(WORLD, 5, 1, A, versions, null, true, A,
                priorContent.certificate(), priorContent.captures());
        var focusVersion = versions.stream().filter(v -> v.position().equals(A)).findFirst().orElseThrow();
        var request = new RollingCubeActor.EditRequest(WORLD, 5, A, focusVersion.leaseGeneration(), 1, 0, 128, 0, 0);
        for (var status : List.of(RollingCubeActor.EditStatus.REPLACEMENT_BUDGET, RollingCubeActor.EditStatus.UNSUPPORTED_REPLACEMENT)) {
            var ack = new RollingCubeActor.EditAck(request, status, 1, "Refused before durable mutation");
            var recovery = RollingEditAcknowledgement.ready(prior, A, versions, ack);
            assertSame(prior.replacementSet(), recovery.replacementSet());
            assertEquals(prior.replacementCaptures(), recovery.replacementCaptures());
            assertEquals(prior.versions(), recovery.versions());
            assertEquals(prior.activeFocus(), recovery.activeFocus());
            var mirror = mirror(); mirror.propose(prior.replacementSet()); mirror.commit(prior.replacementSet().identity());
            mirror.beginEdit(A); mirror.propose(recovery.replacementSet()); mirror.commit(recovery.replacementSet().identity());
            assertTrue(mirror.isCurrent(prior.replacementSet().identity()));
            var mutated = new ArrayList<>(versions);
            var neighbor = mutated.getFirst();
            mutated.set(0, new RollingCubeActor.CubeVersion(neighbor.position(), neighbor.leaseGeneration(), neighbor.revision() + 1));
            assertThrows(IllegalArgumentException.class, () -> RollingEditAcknowledgement.ready(prior, A, mutated, ack));
        }
        for (var status : List.of(RollingCubeActor.EditStatus.SAVE_FAILED, RollingCubeActor.EditStatus.CLOSING)) {
            var ack = new RollingCubeActor.EditAck(request, status, -1, "Uncertain save");
            assertThrows(IllegalStateException.class, () -> RollingEditAcknowledgement.ready(prior, A, versions, ack));
        }
        var stale = new RollingCubeActor.EditRequest(WORLD, 5, A, focusVersion.leaseGeneration(), 0, 0, 128, 0, 0);
        var staleAck = new RollingCubeActor.EditAck(stale, RollingCubeActor.EditStatus.REPLACEMENT_BUDGET, 1, "Stale request");
        assertThrows(IllegalArgumentException.class, () -> RollingEditAcknowledgement.ready(prior, A, versions, staleAck));
    }

    private static RollingCubeActor.Event next(RollingCubeActor actor, ArrayDeque<RollingCubeActor.Event> events) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (events.isEmpty()) {
            events.addAll(actor.pollEvents(2));
            if (events.isEmpty()) { assertTrue(System.nanoTime() < deadline, actor.diagnostics().toString()); Thread.sleep(1); }
        }
        var event = events.removeFirst();
        if (event instanceof RollingCubeActor.Failure failure) fail(failure.detail());
        return event;
    }
    private static RollingCubeActor.StageReady receive(RollingCubeActor actor, ArrayDeque<RollingCubeActor.Event> events,
            RollingGateReplica replica) throws Exception {
        replica.acceptMetadata(assertInstanceOf(RollingCubeActor.StageMetadata.class, next(actor, events)));
        RollingCubeActor.Event event;
        while ((event = next(actor, events)) instanceof RollingCubeActor.Snapshot snapshot)
            replica.acceptSnapshot(snapshot.generation(), snapshot.snapshot());
        return assertInstanceOf(RollingCubeActor.StageReady.class, event);
    }
    private static void commit(RollingCubeActor actor, ArrayDeque<RollingCubeActor.Event> events,
            RollingGateReplica replica, RollingCubeActor.StageReady ready) throws Exception {
        replica.acceptReady(ready);
        assertEquals(RollingCubeActor.Admission.QUEUED, actor.commitPrepared(ready.generation(), ready.replacementSet().identity()));
        assertInstanceOf(RollingCubeActor.Committed.class, next(actor, events));
        replica.commitStage(ready.generation());
        assertEquals(RollingCubeActor.Admission.QUEUED, actor.authorizePinRetirement(ready.generation(), ready.replacementSet().identity()));
    }
    private static RollingCubeActor.StageMetadata forgedFocus(RollingCubeActor.StageReady current, CubePos center, boolean pinned) {
        var plan = RollingCubePlan.create(B, current.candidateCenter(), center, current.pinned(), pinned);
        var oldLeases = current.versions().stream().map(v -> new RollingCubeActor.CubeLease(v.position(), v.leaseGeneration())).toList();
        long next = current.versions().stream().mapToLong(RollingCubeActor.CubeVersion::leaseGeneration).max().orElseThrow();
        var newLeases = new ArrayList<RollingCubeActor.CubeLease>();
        for (CubePos position : plan.nextCommittedOwners()) {
            var old = current.versions().stream().filter(v -> v.position().equals(position)).findFirst();
            newLeases.add(new RollingCubeActor.CubeLease(position, old.isPresent() ? old.get().leaseGeneration() : ++next));
        }
        return new RollingCubeActor.StageMetadata(current.world(), current.epoch(), current.generation() + 1, false, B,
                current.candidateCenter(), center, oldLeases, newLeases, plan.addedOwners(), plan.retiredOwners(),
                plan.candidateTargets(), current.pinned(), pinned, A);
    }
    private static void assertMetadataRefusedWithoutMutation(RollingGateReplica replica, RollingCubeActor.StageMetadata forged) {
        var before = replica.admission().view();
        var positions = List.copyOf(replica.cubes().keySet());
        assertThrows(IllegalArgumentException.class, () -> replica.acceptMetadata(forged));
        assertEquals(before, replica.admission().view());
        assertEquals(positions, List.copyOf(replica.cubes().keySet()));
        assertNull(replica.stagedMetadata());
        assertEquals(A, replica.activeFocus());
    }
    private static void assertShapeRefusedBeforeReplica(RollingGateReplica replica, RollingCubeActor.StageReady current,
            CubePos center, boolean pinned) {
        var before = replica.admission().view();
        var positions = List.copyOf(replica.cubes().keySet());
        // These malformed shapes are rejected by the record constructor, before replica admission.
        assertThrows(IllegalArgumentException.class, () -> forgedFocus(current, center, pinned));
        assertEquals(before, replica.admission().view());
        assertEquals(positions, List.copyOf(replica.cubes().keySet()));
        assertNull(replica.stagedMetadata());
        assertEquals(A, replica.activeFocus());
    }

    @Test void replicaRejectsForgedBootstrapFloorAndUnprovedFocusShapesBeforeLeaseMutation() throws Exception {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        var palette = EnginePaletteContext.create(registry.freeze());
        var descriptor = RollingSessionDescriptor.analytic(CoreGateProfile.BASE, palette, RollingEditPolicy.FULL_CUBE_V1, true);
        var policy = new RollingCubeActor.ReplacementPolicy(descriptor.sourceFingerprint(), DhReplacementInput.ANALYTIC_MATERIAL_POLICY,
                RollingGateFixture.REPLACEMENT_DETAIL_POLICY, 1, RollingEditPolicy.FULL_CUBE_V1);
        var actor = RollingCubeActor.open(temporary, descriptor.world(), RollingGateFixture.provider(palette, CoreGateProfile.BASE),
                palette, 77, A, policy, descriptor.flatPolicy());
        var events = new ArrayDeque<RollingCubeActor.Event>();
        try (var replica = new RollingGateReplica(descriptor.world(), 77, palette, descriptor)) {
            var first = receive(actor, events, replica);
            var set = first.replacementSet();
            var shifted = set.regions().stream().map(r -> new ReplacementSetCertificate.Region(r.position(),
                    r.dependencies().stream().map(v -> new RollingCubeActor.CubeVersion(v.position(), v.leaseGeneration() + 1, v.revision())).toList(),
                    r.captureIdentity())).toList();
            var badFloor = new ReplacementSetCertificate(set.world(), set.epoch(), set.resourceGeneration(), set.storeIdentity(), set.indexIdentity(),
                    set.providerIdentity(), set.paletteIdentity(), set.materialPolicy(), set.detailPolicy(), set.generation(), shifted);
            var forgedReady = new RollingCubeActor.StageReady(first.world(), first.epoch(), first.generation(), first.candidateCenter(), first.versions(),
                    null, first.pinned(), first.activeFocus(), badFloor, first.replacementCaptures());
            assertThrows(IllegalArgumentException.class, () -> replica.acceptReady(forgedReady));
            assertNull(replica.candidateReady());
            commit(actor, events, replica, first);
            RollingCubeActor.StageReady current = first;
            for (int x = 1; x <= 2; x++) {
                assertEquals(RollingCubeActor.Admission.QUEUED, actor.requestCenter(A.offset(x, 0, 0)));
                current = receive(actor, events, replica); commit(actor, events, replica, current);
            }
            assertFalse(current.pinned());
            // B's x=5 dependency layer is not in the current x=0..4 window.
            assertShapeRefusedBeforeReplica(replica, current, current.candidateCenter(), true);
            assertEquals(RollingCubeActor.Admission.QUEUED, actor.requestCenter(A.offset(3, 0, 0)));
            current = receive(actor, events, replica); commit(actor, events, replica, current);
            // Having B's halo does not authorize combined movement/focus or an unpinned new focus.
            assertShapeRefusedBeforeReplica(replica, current, A.offset(4, 0, 0), true);
            assertShapeRefusedBeforeReplica(replica, current, current.candidateCenter(), false);
            // A structurally valid handoff still needs the replica's exact current generation/focus.
            var shape = forgedFocus(current, current.candidateCenter(), true);
            var staleGeneration = new RollingCubeActor.StageMetadata(shape.world(), shape.epoch(), current.generation(), false,
                    shape.pinCenter(), shape.currentCenter(), shape.candidateCenter(), shape.currentLeases(), shape.candidateLeases(),
                    shape.newPositions(), shape.retiredPositions(), shape.renderTargets(), shape.currentPinned(), shape.candidatePinned(), A);
            RollingGateWire.validateMetadata(staleGeneration);
            assertMetadataRefusedWithoutMutation(replica, staleGeneration);
            var wrongPriorFocus = new RollingCubeActor.StageMetadata(shape.world(), shape.epoch(), shape.generation(), false,
                    shape.pinCenter(), shape.currentCenter(), shape.candidateCenter(), shape.currentLeases(), shape.candidateLeases(),
                    shape.newPositions(), shape.retiredPositions(), shape.renderTargets(), shape.currentPinned(), shape.candidatePinned(), B);
            RollingGateWire.validateMetadata(wrongPriorFocus);
            assertMetadataRefusedWithoutMutation(replica, wrongPriorFocus);
            assertEquals(RollingCubeActor.Admission.QUEUED, actor.requestFocus(B, current.generation(), current.replacementSet().identity()));
            var validFocus = receive(actor, events, replica);
            assertEquals(A, replica.activeFocus());
            commit(actor, events, replica, validFocus);
            assertEquals(B, replica.activeFocus());
            assertEquals(125, replica.cubes().size());
        } finally {
            actor.close(); assertTrue(actor.closed().get(20, TimeUnit.SECONDS).successful());
        }
    }
}
