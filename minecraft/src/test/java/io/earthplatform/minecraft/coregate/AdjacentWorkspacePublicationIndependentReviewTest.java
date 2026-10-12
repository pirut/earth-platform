package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import io.earthplatform.minecraft.enginebridge.ReplacementWorkspaces;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent actor/wire/client adversaries for adjacent capture coherence and one native focus. */
class AdjacentWorkspacePublicationIndependentReviewTest {
    private static final CubePos A = new CubePos(0, 8, 0), B = new CubePos(1, 8, 0);
    private static final WorldIdentity WORLD = new WorldIdentity(1, "fixture", "v1", "source", "palette");
    private static final String STORE = "ebd24c87-9e0d-45ed-9e94-e17c177d77e3", INDEX = "a".repeat(64), PALETTE = "b".repeat(64);
    private static EnginePaletteContext palette;
    @TempDir Path temporary;
    static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }
    private record Content(ReplacementSetCertificate certificate, List<NativeReplacementCapture> captures) { }
    private static Content content(long generation, long aRevision, long bRevision, long firstLease) {
        var regions = new ArrayList<ReplacementSetCertificate.Region>();
        var captures = new ArrayList<NativeReplacementCapture>();
        for (var position : List.of(A, B)) {
            var capture = new NativeReplacementCapture(position, new byte[8000]);
            if (aRevision > 0) capture = capture.withReadMaterial(15, 128, 0,
                    aRevision == 1 ? NativeReplacementCapture.STONE : NativeReplacementCapture.DIORITE).compact();
            if (bRevision > 0) capture = capture.withReadMaterial(16, 128, 0,
                    bRevision == 1 ? NativeReplacementCapture.STONE : NativeReplacementCapture.DIORITE).compact();
            captures.add(capture);
            var versions = new ArrayList<CubeVersion>();
            for (var dependency : CubeTickets.box(position, 1, 1, 27).stream().sorted().toList())
                versions.add(new CubeVersion(dependency, firstLease++, dependency.equals(A) ? aRevision : dependency.equals(B) ? bRevision : 0));
            regions.add(new ReplacementSetCertificate.Region(position, versions, capture.contentIdentity()));
        }
        return new Content(new ReplacementSetCertificate(WORLD, 5, 1, STORE, INDEX, "provider", PALETTE,
                "material", "detail", generation, regions), List.copyOf(captures));
    }
    private static RollingReplacementSetAuthority mirror() {
        return new RollingReplacementSetAuthority(WORLD, 5, 1, ReplacementWorkspaces.adjacent(A), "provider", PALETTE, "material", "detail");
    }

    @Test void sharedCaptureRefreshAdvancesOnlyEditedCenterAndCannotRollBackAfterCancellation() {
        var authority = mirror(); var old = content(1, 1, 0, 1); var next = content(2, 2, 0, 101);
        assertNotEquals(old.captures().get(1).contentIdentity(), next.captures().get(1).contentIdentity());
        assertEquals(0, next.certificate().region(B).revision());
        authority.propose(old.certificate()); authority.commit(old.certificate().identity()); authority.beginEdit(A);
        assertDoesNotThrow(() -> authority.propose(next.certificate()));
        assertThrows(RuntimeException.class, () -> authority.propose(old.certificate()));
        authority.cancelProposal();
        assertThrows(RuntimeException.class, () -> authority.propose(old.certificate()));
        assertFalse(authority.isCurrent(old.certificate().identity())); assertTrue(authority.canPresent(old.certificate().identity()));
        var refreshed = content(3, 2, 0, 201);
        authority.propose(refreshed.certificate()); authority.commit(refreshed.certificate().identity());
        assertFalse(authority.canPresent(old.certificate().identity())); assertTrue(authority.isCurrent(refreshed.certificate().identity()));
    }

    @Test void editingOneCenterNeverAuthorizesOtherCenterRevisionOrUnchangedRevisionContentDrift() {
        var old = content(1, 1, 0, 1);
        for (var forged : List.of(content(2, 2, 1, 101).certificate(), content(2, 3, 0, 101).certificate())) {
            var authority = mirror(); authority.propose(old.certificate()); authority.commit(old.certificate().identity()); authority.beginEdit(A);
            assertThrows(IllegalArgumentException.class, () -> authority.propose(forged));
            assertTrue(authority.canPresent(old.certificate().identity())); assertFalse(authority.isCurrent(forged.identity()));
        }
        var source = old.certificate(); var regions = new ArrayList<>(source.regions()); var b = regions.get(1);
        regions.set(1, new ReplacementSetCertificate.Region(B, b.dependencies(), "f".repeat(64)));
        var changedOnlyContent = new ReplacementSetCertificate(source.world(), source.epoch(), source.resourceGeneration(), source.storeIdentity(),
                source.indexIdentity(), source.providerIdentity(), source.paletteIdentity(), source.materialPolicy(), source.detailPolicy(), 2, regions);
        var authority = mirror(); authority.propose(source); authority.commit(source.identity()); authority.beginEdit(A);
        assertThrows(IllegalArgumentException.class, () -> authority.propose(changedOnlyContent));
    }

    @Test void worldSourceEpochResourceStoreAndIndexAbaCannotReplaceAdjacentAuthority() {
        var source = content(1, 1, 0, 1).certificate();
        for (int change = 0; change < 8; change++) {
            var authority = mirror(); authority.propose(source); authority.commit(source.identity());
            var forged = new ReplacementSetCertificate(change == 0 ? new WorldIdentity(2, "fixture", "v1", "source", "palette") : source.world(),
                    change == 1 ? 6 : source.epoch(), change == 2 ? 2 : source.resourceGeneration(),
                    change == 3 ? "d5596d32-74aa-46b4-b100-1658ee23b3a3" : source.storeIdentity(),
                    change == 4 ? "e".repeat(64) : source.indexIdentity(), change == 5 ? "foreign" : source.providerIdentity(),
                    change == 6 ? "f".repeat(64) : source.paletteIdentity(), source.materialPolicy(),
                    change == 7 ? "foreign" : source.detailPolicy(), 2, source.regions());
            assertThrows(IllegalArgumentException.class, () -> authority.propose(forged), "binding " + change);
            assertTrue(authority.isCurrent(source.identity()));
        }
    }

    @Test void equalFlatSourceCannotAuthorizeDisjointSetInAdjacentMirror() {
        var source = content(1, 1, 0, 1).certificate();
        var disjointB = new CubePos(4, 8, 0); var dependencies = new ArrayList<CubeVersion>(); long lease = 28;
        for (var position : CubeTickets.box(disjointB, 1, 1, 27).stream().sorted().toList()) dependencies.add(new CubeVersion(position, lease++, 0));
        var disjoint = new ReplacementSetCertificate(source.world(), source.epoch(), source.resourceGeneration(), source.storeIdentity(),
                source.indexIdentity(), source.providerIdentity(), source.paletteIdentity(), source.materialPolicy(), source.detailPolicy(), 2,
                List.of(source.regions().getFirst(), new ReplacementSetCertificate.Region(disjointB, dependencies, "c".repeat(64))));
        var authority = mirror(); authority.propose(source); authority.commit(source.identity());
        assertThrows(IllegalArgumentException.class, () -> authority.propose(disjoint));
        assertTrue(authority.isCurrent(source.identity())); assertFalse(authority.isCurrent(disjoint.identity()));
    }

    @Test void individuallyValidCaptureHashesCannotHideDisagreeingSharedReadVoxels() {
        var source = content(1, 0, 0, 1);
        var forgedCapture = source.captures().get(1).withReadMaterial(15, 143, 15, NativeReplacementCapture.DIORITE).compact();
        var old = source.certificate(); var regions = new ArrayList<>(old.regions()); var b = regions.get(1);
        regions.set(1, new ReplacementSetCertificate.Region(B, b.dependencies(), forgedCapture.contentIdentity()));
        var forged = new ReplacementSetCertificate(old.world(), old.epoch(), old.resourceGeneration(), old.storeIdentity(), old.indexIdentity(),
                old.providerIdentity(), old.paletteIdentity(), old.materialPolicy(), old.detailPolicy(), old.generation(), regions);
        var captures = List.of(source.captures().get(0), forgedCapture);
        assertThrows(IllegalArgumentException.class, () -> new StageReady(WORLD, 5, 1, A, List.of(), null, true, A, forged, captures));
        var request = new EditRequest(WORLD, 5, A, 55, 0, 15, 143, 15, 1);
        var changed = content(2, 1, 0, 101); var changedRegions = new ArrayList<>(changed.certificate().regions());
        var changedB = changedRegions.get(1);
        changedRegions.set(1, new ReplacementSetCertificate.Region(B, changedB.dependencies(), forgedCapture.contentIdentity()));
        var badAckSet = new ReplacementSetCertificate(WORLD, 5, 1, STORE, INDEX, "provider", PALETTE, "material", "detail", 2, changedRegions);
        assertThrows(IllegalArgumentException.class, () -> new EditAck(request, EditStatus.APPLIED, 1, "forged", null, badAckSet,
                List.of(changed.captures().get(0), forgedCapture)));
    }

    @Test void realActorWireReplicaMaintainsSharedEdgeAndCornerEditsAThenB() throws Exception { exerciseSeam(false); }
    @Test void realActorWireReplicaMaintainsSharedEdgeAndCornerEditsBThenA() throws Exception { exerciseSeam(true); }

    private void exerciseSeam(boolean reverse) throws Exception {
        registries();
        var descriptor = RollingSessionDescriptor.analytic(CoreGateProfile.BASE, palette, RollingEditPolicy.FULL_CUBE_V1, true, true);
        var policy = new ReplacementPolicy(descriptor.sourceFingerprint(), DhReplacementInput.ANALYTIC_MATERIAL_POLICY,
                RollingGateFixture.REPLACEMENT_DETAIL_POLICY, 1, RollingEditPolicy.FULL_CUBE_V1);
        var actor = RollingCubeActor.open(temporary, descriptor.world(), RollingGateFixture.provider(palette, CoreGateProfile.BASE),
                palette, 77, A, policy, descriptor.flatPolicy(), descriptor.workspaces());
        var events = new ArrayDeque<Event>();
        var mirror = new RollingReplacementSetAuthority(descriptor.world(), 77, 1, descriptor.workspaces(), descriptor.sourceFingerprint(),
                palette.fingerprint(), policy.materialPolicy(), policy.detailPolicy());
        String durable;
        try (var replica = new RollingGateReplica(descriptor.world(), 77, palette, descriptor)) {
            var current = receive(actor, events, replica); commit(actor, events, replica, mirror, current);
            assertEquals(125, current.versions().size()); assertTrue(replica.cubes().containsKey(B));
            assertEquals(55, current.versions().getFirst().leaseGeneration());
            var revisions = new HashMap<CubePos, Long>(Map.of(A, 0L, B, 0L));
            for (int step = 0; step < 4; step++) {
                CubePos focus = (step % 2 == 0) == reverse ? B : A;
                if (!focus.equals(replica.activeFocus())) {
                    var ownersBefore = List.copyOf(replica.cubes().keySet()); var bindingsBefore = replica.admission().view();
                    assertEquals(Admission.QUEUED, actor.requestFocus(focus, current.generation(), current.replacementSet().identity()));
                    var metadata = assertInstanceOf(StageMetadata.class, next(actor, events));
                    assertTrue(metadata.currentPinned()); assertTrue(metadata.candidatePinned());
                    assertEquals(metadata.currentLeases(), metadata.candidateLeases()); assertTrue(metadata.newPositions().isEmpty());
                    assertTrue(metadata.retiredPositions().isEmpty()); assertEquals(A, metadata.candidateCenter());
                    replica.acceptMetadata(metadata);
                    current = roundTrip(assertInstanceOf(StageReady.class, next(actor, events))); replica.acceptReady(current);
                    commit(actor, events, replica, mirror, current);
                    assertEquals(ownersBefore, List.copyOf(replica.cubes().keySet())); assertEquals(bindingsBefore.leases(), replica.admission().view().leases());
                    assertEquals(bindingsBefore.metrics().leasesCreated(), replica.admission().view().metrics().leasesCreated());
                    assertEquals(bindingsBefore.metrics().leasesRetired(), replica.admission().view().metrics().leasesRetired());
                    assertEquals(focus, replica.activeFocus()); assertEquals(125, replica.cubes().size());
                }
                var prior = current; var priorOther = replica.cubes().get(focus.equals(A) ? B : A);
                long x = focus.equals(A) ? (step < 2 ? 15 : 14) : (step < 2 ? 16 : 17);
                long y = step < 2 ? 128 : 143, z = step < 2 ? 0 : 15;
                var nativeOld = replica.cubes().get(focus).snapshot();
                var request = new EditRequest(descriptor.world(), 77, focus, nativeOld.leaseGeneration(), nativeOld.revision(), x, y, z,
                        Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState()));
                replica.beginEdit(); mirror.beginEdit(focus);
                assertEquals(Admission.QUEUED, actor.tryEdit(request));
                var snapshot = assertInstanceOf(Snapshot.class, next(actor, events));
                assertEquals(focus, snapshot.snapshot().position()); replica.acceptSnapshot(snapshot.generation(), snapshot.snapshot());
                var ack = assertInstanceOf(EditAck.class, next(actor, events)); assertTrue(ack.applied(), ack.toString());
                replica.acknowledge(ack.status().name(), ack.revision()); revisions.compute(focus, (p, r) -> r + 1);
                for (var position : List.of(A, B)) assertEquals(revisions.get(position).longValue(), ack.replacementSet().region(position).revision());
                assertSame(priorOther, replica.cubes().get(focus.equals(A) ? B : A), "The unedited native cube must keep its exact snapshot");
                for (int i = 0; i < 2; i++) {
                    assertEquals(NativeReplacementCapture.DIORITE, ack.replacementCaptures().get(i).materialAt(x, y, z));
                    assertNotEquals(prior.replacementCaptures().get(i).contentIdentity(), ack.replacementCaptures().get(i).contentIdentity());
                }
                assertEquals(Blocks.DIORITE.defaultBlockState(), replica.cubes().get(focus).state(Math.floorMod((int)x, 16), Math.floorMod((int)y, 16), (int)z));
                assertSharedValues(ack.replacementCaptures()); assertSharedRevisions(ack.replacementSet());
                var versions = prior.versions().stream().map(v -> v.position().equals(focus) ? new CubeVersion(focus, v.leaseGeneration(), ack.revision()) : v).toList();
                current = roundTrip(RollingEditAcknowledgement.ready(prior, focus, versions, ack)); replica.acceptReady(current);
                mirror.propose(current.replacementSet());
                assertThrows(RuntimeException.class, () -> mirror.propose(prior.replacementSet()));
                mirror.commit(current.replacementSet().identity());
                assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(current.generation(), prior.replacementSet().identity()));
                assertEquals(Admission.QUEUED, actor.authorizePinRetirement(current.generation(), current.replacementSet().identity()));
                assertEquals(125, replica.currentPositions125().size()); assertEquals(27, replica.currentTargets().size());
                assertTrue(actor.diagnostics().residentCubes() <= 213); assertTrue(actor.diagnostics().tickets() <= 3);
            }
            durable = current.replacementSet().durableIdentity();
        } finally { mirror.close(); actor.close(); assertTrue(actor.closed().get(20, TimeUnit.SECONDS).successful()); }
        var reopened = RollingCubeActor.open(temporary, descriptor.world(), RollingGateFixture.provider(palette, CoreGateProfile.BASE),
                palette, 78, A, policy, descriptor.flatPolicy(), descriptor.workspaces());
        try (var replica = new RollingGateReplica(descriptor.world(), 78, palette, descriptor)) {
            var restored = receive(reopened, new ArrayDeque<>(), replica);
            assertEquals(durable, restored.replacementSet().durableIdentity());
            assertEquals(2, restored.replacementSet().region(A).revision()); assertEquals(2, restored.replacementSet().region(B).revision());
            assertSharedValues(restored.replacementCaptures()); assertSharedRevisions(restored.replacementSet());
        } finally { reopened.close(); assertTrue(reopened.closed().get(20, TimeUnit.SECONDS).successful()); }
    }
    private static void assertSharedValues(List<NativeReplacementCapture> captures) {
        for (long x = 14; x < 18; x++) for (long y = 126; y < 146; y++) for (long z = -2; z < 18; z++)
            assertEquals(captures.get(0).materialAt(x,y,z), captures.get(1).materialAt(x,y,z), x + "/" + y + "/" + z);
    }
    private static void assertSharedRevisions(ReplacementSetCertificate set) {
        for (var a : set.region(A).dependencies()) for (var b : set.region(B).dependencies())
            if (a.position().equals(b.position())) assertEquals(a.revision(), b.revision(), a.position().toString());
    }
    private static Event next(RollingCubeActor actor, ArrayDeque<Event> events) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (events.isEmpty()) { events.addAll(actor.pollEvents(2)); if (events.isEmpty()) { assertTrue(System.nanoTime() < end, actor.diagnostics().toString()); Thread.sleep(1); } }
        var event = events.removeFirst(); if (event instanceof Failure failed) fail(failed.detail()); return event;
    }
    private static StageReady receive(RollingCubeActor actor, ArrayDeque<Event> events, RollingGateReplica replica) throws Exception {
        replica.acceptMetadata(assertInstanceOf(StageMetadata.class, next(actor, events)));
        Event event; while ((event = next(actor, events)) instanceof Snapshot snapshot) replica.acceptSnapshot(snapshot.generation(), snapshot.snapshot());
        var ready = roundTrip(assertInstanceOf(StageReady.class, event)); replica.acceptReady(ready); return ready;
    }
    private static void commit(RollingCubeActor actor, ArrayDeque<Event> events, RollingGateReplica replica,
            RollingReplacementSetAuthority mirror, StageReady ready) throws Exception {
        mirror.propose(ready.replacementSet());
        assertEquals(Admission.QUEUED, actor.commitPrepared(ready.generation(), ready.replacementSet().identity()));
        assertInstanceOf(Committed.class, next(actor, events)); replica.commitStage(ready.generation()); mirror.commit(ready.replacementSet().identity());
    }
    private static StageReady roundTrip(StageReady source) {
        var bytes = new FriendlyByteBuf(Unpooled.buffer());
        try { RollingGateWire.writeReady(bytes, source); var result = RollingGateWire.readReady(bytes, source.world(), source.epoch());
            assertEquals(source.replacementSet(), result.replacementSet()); assertEquals(source.activeFocus(), result.activeFocus()); assertFalse(bytes.isReadable()); return result;
        } finally { bytes.release(); }
    }
}
