package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** COMPLETE ownership, stale ALLOW and whole-set serial publication adversaries. */
class PagedAuthorityIndependentReviewTest {
    private static final CubePos A = new CubePos(0, 8, 0), B = new CubePos(1, 8, 0);
    private static final WorldIdentity WORLD = new WorldIdentity(7, "analytic", "v1", "fixture", "palette");
    private static final String POLICY = "a".repeat(64), PALETTE = "b".repeat(64);
    private static final ReplacementWorkspaces PAIR = ReplacementWorkspaces.adjacent(A);
    private static final RollingCubeActor.ReplacementPolicy REPLACEMENT =
            new RollingCubeActor.ReplacementPolicy("provider", "material", "detail", 1, RollingEditPolicy.FULL_CUBE_V1);
    @TempDir Path temporary;

    @Test void completeCapturesWithoutACompleteCurrentIndexCannotIssueAnyCertificate() throws Exception {
        var ledger = ReplacementRevisionLedger.openIndexed(temporary, PAIR, POLICY);
        var authority = authority(ledger, 1); var captures = captures();
        observeSerial(authority, captures, 1, Map.of());
        assertNull(authority.prepare()); assertFalse(authority.canCommit("f".repeat(64)));
        ledger.sealCaptures(captures); authority.sealBootstrap(ledger);
        var issued = authority.prepare(); assertNotNull(issued); assertNotNull(issued.queryAttestation());
        assertTrue(issued.queryAttestation().completeCatalogue()); assertTrue(authority.canCommit(issued.identity()));
        var q = issued.queryAttestation();
        var forged = new ReplacementQueryAttestation(q.storeIdentity(), q.indexIdentity(), q.snapshotGeneration(),
                "f".repeat(64), q.minX(), q.minY(), q.minZ(), q.maxX(), q.maxY(), q.maxZ(),
                q.layoutIdentity(), q.policyIdentity(), q.resultDigest(), q.resultCount());
        var valueOnly = new ReplacementSetCertificate(issued.world(), issued.epoch(), issued.resourceGeneration(),
                issued.storeIdentity(), issued.indexIdentity(), issued.providerIdentity(), issued.paletteIdentity(),
                issued.materialPolicy(), issued.detailPolicy(), issued.generation(), issued.regions(), forged);
        assertFalse(authority.canCommit(valueOnly.identity()));
        assertTrue(authority.commit(issued.identity())); assertTrue(authority.current(issued.identity()));
    }

    @Test void rootTamperDiscoveredByFreshQueryThenByteExactRestoreCannotReviveAnIssuedAllow() throws Exception {
        var fixture = sealed(); var issued = fixture.authority().prepare();
        Path root = temporary.resolve(PagedReplacementIndex.ROOT_FILE); byte[] original = Files.readAllBytes(root);
        byte[] changed = original.clone(); changed[20] ^= 1; Files.write(root, changed);
        assertThrows(java.io.IOException.class, () -> fixture.ledger().begin(A, 0, fixture.captures()));
        assertFalse(fixture.authority().canCommit(issued.identity())); assertNull(fixture.authority().prepare());
        Files.write(root, original);
        assertFalse(fixture.authority().canCommit(issued.identity())); assertFalse(fixture.authority().commit(issued.identity()));
        assertNull(fixture.authority().prepare()); assertNull(fixture.ledger().attestation());
    }

    @Test void unexpectedIntentDiscoveredByFreshQueryRevokesPairAndRemovalCannotUnpoisonIt() throws Exception {
        var fixture = sealed(); var issued = fixture.authority().prepare(); assertTrue(fixture.authority().commit(issued.identity()));
        Path intent = temporary.resolve(PagedReplacementIndex.INTENT_FILE); Files.write(intent, new byte[]{1, 2, 3});
        assertThrows(java.io.IOException.class, () -> fixture.ledger().begin(A, 0, fixture.captures()));
        assertFalse(fixture.authority().current(issued.identity())); assertFalse(fixture.authority().canCommit(issued.identity()));
        Files.delete(intent);
        assertFalse(fixture.authority().current(issued.identity())); assertNull(fixture.authority().prepare());
        assertNull(fixture.ledger().attestation());
    }

    @Test void staleOrHalfRefreshedSummariesCannotPublishAndExactColdReconstructionMatches() throws Exception {
        var fixture = sealed(); var authority = fixture.authority(); var ledger = fixture.ledger();
        var before = authority.prepare(); assertTrue(authority.commit(before.identity()));
        var a = fixture.captures().getFirst().withReadMaterial(15, 128, 0, NativeReplacementCapture.DIORITE);
        var b = fixture.captures().getLast().withReadMaterial(15, 128, 0, NativeReplacementCapture.DIORITE);
        var changed = new RollingCubeActor.CubeVersion(A, 55, 1);
        authority.beforeEdit(A); ledger.begin(A, 0, List.of(a, b));
        assertFalse(authority.canCommit(before.identity())); assertNull(authority.prepare());
        // The matching cube-save barrier belongs to the host; here only index/issuer atomicity is exercised.
        ledger.confirm(A, 1);
        assertThrows(IllegalArgumentException.class, () -> authority.completeEdit(changed, List.of(a, fixture.captures().getLast()), ledger));
        assertThrows(RuntimeException.class, () -> authority.completeEdit(changed, fixture.captures(), ledger));
        assertNull(authority.prepare()); assertFalse(authority.canCommit(before.identity()));
        authority.completeEdit(changed, List.of(a, b), ledger);
        var after = authority.prepare(); assertNotNull(after); after.verifyCaptures(List.of(a, b));
        assertEquals(1, after.region(A).revision()); assertEquals(0, after.region(B).revision());
        assertNotEquals(before.region(B).captureIdentity(), after.region(B).captureIdentity());
        assertEquals(before.indexIdentity(), after.indexIdentity());
        assertEquals(before.queryAttestation().snapshotGeneration() + 1, after.queryAttestation().snapshotGeneration());
        assertNotEquals(before.queryAttestation().snapshotRootIdentity(), after.queryAttestation().snapshotRootIdentity());
        assertFalse(authority.canCommit(before.identity())); assertTrue(authority.commit(after.identity()));
        assertThrows(java.io.IOException.class, () -> ledger.verifyObserved(A, 0));
        var reopened = ReplacementRevisionLedger.openIndexed(temporary, PAIR, POLICY);
        var cold = authority(reopened, 2); var coldCaptures = List.of(a.compact(), b.compact());
        observeSerial(cold, coldCaptures, 501, Map.of(A, 1L)); reopened.sealCaptures(coldCaptures); cold.sealBootstrap(reopened);
        var restored = cold.prepare(); assertNotNull(restored);
        assertEquals(after.durableIdentity(), restored.durableIdentity()); assertNotEquals(after.identity(), restored.identity());
        assertEquals(after.queryAttestation(), restored.queryAttestation());
        assertFalse(cold.canCommit(after.identity())); assertTrue(cold.canCommit(restored.identity()));
    }

    private record Fixture(ReplacementRevisionLedger ledger, NativeReplacementSetAuthority authority, List<NativeReplacementCapture> captures) { }
    private Fixture sealed() throws Exception {
        var ledger = ReplacementRevisionLedger.openIndexed(temporary, PAIR, POLICY); var captures = captures();
        ledger.sealCaptures(captures); var authority = authority(ledger, 1);
        observeSerial(authority, captures, 1, Map.of()); authority.sealBootstrap(ledger);
        return new Fixture(ledger, authority, captures);
    }
    private static NativeReplacementSetAuthority authority(ReplacementRevisionLedger ledger, long epoch) {
        return new NativeReplacementSetAuthority(WORLD, epoch, PAIR, ledger.identity().storeIdentity(),
                ledger.identity().indexIdentity(), PALETTE, REPLACEMENT, ledger);
    }
    private static List<NativeReplacementCapture> captures() {
        return List.of(new NativeReplacementCapture(A, new byte[8000]), new NativeReplacementCapture(B, new byte[8000]));
    }
    private static void observeSerial(NativeReplacementSetAuthority authority, List<NativeReplacementCapture> captures,
            long lease, Map<CubePos, Long> revisions) {
        for (var capture : captures) {
            var p = capture.position(); var dependencies = new ArrayList<RollingCubeActor.CubeVersion>();
            for (int x = p.x() - 1; x <= p.x() + 1; x++) for (int y = p.y() - 1; y <= p.y() + 1; y++) for (int z = p.z() - 1; z <= p.z() + 1; z++) {
                var position = new CubePos(x, y, z); var version = new RollingCubeActor.CubeVersion(position, lease++, revisions.getOrDefault(position, 0L));
                dependencies.add(version); authority.observed(version);
            }
            assertTrue(authority.captured(new ReplacementSetCertificate.Region(p, dependencies, capture.contentIdentity())));
            dependencies.forEach(v -> authority.retire(v.position()));
        }
    }
}
