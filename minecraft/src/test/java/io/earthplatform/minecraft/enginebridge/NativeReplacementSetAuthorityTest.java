package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeReplacementSetAuthorityTest {
    private static final CubePos A = new CubePos(0, 8, 0), B = A.offset(4, 0, 0);
    private static final ReplacementWorkspaces WORKSPACES = ReplacementWorkspaces.two(A);
    private static final WorldIdentity WORLD = new WorldIdentity(42, "analytic", "v1", "flat", "palette");
    private static final String STORE = UUID.randomUUID().toString();
    private static final RollingCubeActor.ReplacementPolicy POLICY =
            new RollingCubeActor.ReplacementPolicy("analytic-flat-v1", "opaque-unit-v1", "exact-v1", 1);
    private static NativeReplacementSetAuthority authority() {
        return new NativeReplacementSetAuthority(WORLD, 7, WORKSPACES, STORE, "a".repeat(64), "b".repeat(64), POLICY);
    }
    private static ReplacementSetCertificate.Region region(CubePos position, long leaseBase, long revision) {
        var positions = WORKSPACES.dependencies(position);
        var versions = java.util.stream.IntStream.range(0, positions.size())
                .mapToObj(i -> new RollingCubeActor.CubeVersion(positions.get(i), leaseBase + i + 1,
                        positions.get(i).equals(position) ? revision : 0)).toList();
        return new ReplacementSetCertificate.Region(position, versions, (revision % 2 == 0 ? "c" : "d").repeat(64));
    }
    private static void observeAndCapture(NativeReplacementSetAuthority authority, ReplacementSetCertificate.Region region) {
        region.dependencies().forEach(authority::observed); assertTrue(authority.captured(region));
    }
    private static ReplacementSetCertificate prepared(NativeReplacementSetAuthority authority) {
        observeAndCapture(authority, region(A, 0, 1)); observeAndCapture(authority, region(B, 100, 2));
        return assertDoesNotThrow(() -> java.util.Objects.requireNonNull(authority.prepare()));
    }

    @Test void completeSetCannotOmitAnUnobservedOrCleanRegion() {
        var authority = authority(); observeAndCapture(authority, region(A, 0, 1)); assertNull(authority.prepare());
        observeAndCapture(authority, region(B, 100, 0)); var certificate = authority.prepare();
        assertNotNull(certificate); assertEquals(2, certificate.regions().size());
        assertEquals(1, certificate.editedRegions().size()); assertEquals(0, certificate.region(B).revision());
        assertEquals(54, authority.observedDependencyCount());
    }

    @Test void serialBootstrapRetainsOnlyMetadataAndRequiresCompleteSetBeforePublication(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var ledger = ReplacementRevisionLedger.open(directory, WORKSPACES);
        ledger.begin(A, 0); ledger.confirm(A, 1); ledger.begin(B, 0); ledger.confirm(B, 1); ledger.begin(B, 1); ledger.confirm(B, 2);
        var authority = new NativeReplacementSetAuthority(WORLD, 7, WORKSPACES,
                ledger.identity().storeIdentity(), ledger.identity().indexIdentity(), "b".repeat(64), POLICY);
        var first = region(A, 0, 1); observeAndCapture(authority, first);
        first.dependencies().forEach(v -> authority.retire(v.position()));
        assertEquals(0, authority.observedDependencyCount()); assertEquals(1, authority.retainedRegionCount());
        assertNull(authority.prepare()); assertThrows(IllegalStateException.class, () -> authority.sealBootstrap(ledger));
        var second = region(B, 100, 2); observeAndCapture(authority, second);
        second.dependencies().forEach(v -> authority.retire(v.position()));
        assertNull(authority.prepare()); authority.sealBootstrap(ledger);
        assertThrows(IllegalStateException.class, () -> authority.sealBootstrap(ledger));
        var complete = authority.prepare(); assertNotNull(complete); assertEquals(0, authority.observedDependencyCount());
        assertTrue(authority.commit(complete.identity()));
        assertThrows(IllegalStateException.class, () -> authority.sealBootstrap(ledger));
    }

    @Test void editingBRevokesOldSetWithoutForgettingDurableA() {
        var authority = authority(); var old = prepared(authority); assertTrue(authority.commit(old.identity()));
        old.region(A).dependencies().forEach(v -> authority.retire(v.position()));
        authority.beforeEdit(B); assertFalse(authority.current(old.identity())); assertFalse(authority.canCommit(old.identity()));
        assertEquals(1, authority.retainedRegionCount()); assertNull(authority.prepare());
        var changed = region(B, 100, 3);
        authority.durableEdit(changed.dependencies().stream().filter(v -> v.position().equals(B)).findFirst().orElseThrow());
        assertTrue(authority.captured(changed)); var next = authority.prepare(); assertNotNull(next);
        assertEquals(old.region(A), next.region(A)); assertEquals(3, next.region(B).revision());
        assertNotEquals(old.identity(), next.identity()); assertTrue(authority.commit(next.identity()));
    }

    @Test void failedSaveCannotBeRepairedByUnchangedOrReacquiredSnapshots() {
        var authority = authority(); var old = prepared(authority); assertTrue(authority.commit(old.identity()));
        authority.beforeEdit(B); var stale = region(B, 1000, 2); stale.dependencies().forEach(authority::observed);
        assertFalse(authority.captured(stale)); assertNull(authority.prepare());
        assertFalse(authority.canCommit(old.identity()));
    }

    @Test void everyRegionDependencyRevisionRevokesTheWholeSetButRetainsOtherRegion() {
        for (CubePos position : WORKSPACES.positions()) for (CubePos dependency : WORKSPACES.dependencies(position)) {
            var authority = authority(); var old = prepared(authority); assertTrue(authority.commit(old.identity()));
            var previous = old.region(position).dependencies().stream().filter(v -> v.position().equals(dependency)).findFirst().orElseThrow();
            authority.retire(dependency);
            assertThrows(IllegalStateException.class, () -> authority.observed(new RollingCubeActor.CubeVersion(
                    dependency, previous.leaseGeneration() + 1000, previous.revision() + 1)));
            assertFalse(authority.current(old.identity())); assertNull(authority.prepare()); assertEquals(1, authority.retainedRegionCount());
        }
    }

    @Test void uncommittedLeaseAbaCannotCommitAndPublishedRetirementCan() {
        var authority = authority(); var old = prepared(authority); authority.retire(B);
        assertFalse(authority.canCommit(old.identity()));
        observeAndCapture(authority, region(B, 1000, 2)); var fresh = authority.prepare();
        assertNotEquals(old.identity(), fresh.identity()); assertEquals(old.durableIdentity(), fresh.durableIdentity());
        assertTrue(authority.commit(fresh.identity()));
        for (var r : fresh.regions()) for (var v : r.dependencies()) authority.retire(v.position());
        assertTrue(authority.canCommit(fresh.identity())); assertSame(fresh, authority.prepare());
    }

    @Test void restartCannotAuthorizeOldRuntimeSetEvenAtIdenticalEpochAndLeases() {
        var first = authority(); var old = prepared(first); assertTrue(first.commit(old.identity()));
        var restarted = authority(); assertFalse(restarted.canCommit(old.identity())); assertNull(restarted.prepare());
        var fresh = prepared(restarted); assertNotEquals(old.identity(), fresh.identity());
        assertEquals(old.durableIdentity(), fresh.durableIdentity()); assertFalse(restarted.canCommit(old.identity()));
    }
}
