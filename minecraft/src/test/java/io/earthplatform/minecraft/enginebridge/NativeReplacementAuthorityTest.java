package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeReplacementAuthorityTest {
    private static final CubePos ORIGINAL = new CubePos(-5, 8, -7);
    private static final WorldIdentity WORLD = new WorldIdentity(4, "provider", "v1", "dataset", "palette");
    private static final String PALETTE = "a".repeat(64);
    private static final String STORE = UUID.randomUUID().toString();
    private static final RollingCubeActor.ReplacementPolicy POLICY =
            new RollingCubeActor.ReplacementPolicy("source-v1", "opaque-unit-color-v1", "exact-face-v1", 1);
    @TempDir Path temporary;
    private static NativeReplacementAuthority authority() {
        return new NativeReplacementAuthority(WORLD, 7, ORIGINAL, STORE, PALETTE, POLICY);
    }
    private static List<RollingCubeActor.CubeVersion> vector(long leaseOffset, long originalRevision) {
        var positions = CubeTickets.box(ORIGINAL, 1, 1, 27).stream().sorted().toList();
        var result = new ArrayList<RollingCubeActor.CubeVersion>();
        for (int i = 0; i < positions.size(); i++) result.add(new RollingCubeActor.CubeVersion(positions.get(i),
                leaseOffset + i + 1, positions.get(i).equals(ORIGINAL) ? originalRevision : 0));
        return List.copyOf(result);
    }
    private static ReplacementCertificate prepare(NativeReplacementAuthority authority,
                                                   List<RollingCubeActor.CubeVersion> vector) {
        vector.forEach(authority::observed);
        return authority.prepare(vector);
    }
    @Test void sourceLeaseRetirementCannotDestroyCommittedDurableAuthority() {
        var a = authority(); var v = vector(0, 3); var certificate = prepare(a, v);
        assertTrue(a.commit(certificate.identity()));
        v.forEach(value -> a.retire(value.position()));
        assertTrue(a.current(certificate.identity()));
        assertSame(certificate, a.prepare(List.of()));
        assertTrue(a.canCommit(certificate.identity()));
        vector(100, 3).forEach(a::observed);
        assertSame(certificate, a.prepare(vector(100, 3)), "Lease ABA at the same durable revision does not remint committed authority");
    }
    @Test void leaseAbaBeforeCommitRejectsCapturedWorkAndRequiresNewCertificate() {
        var a = authority(); var v = vector(0, 3); var old = prepare(a, v);
        a.retire(ORIGINAL); assertFalse(a.canCommit(old.identity()));
        var newer = prepare(a, vector(100, 3));
        assertFalse(a.canCommit(old.identity())); assertTrue(a.canCommit(newer.identity()));
        assertNotEquals(old.identity(), newer.identity());
        assertEquals(old.dependencyIdentity(), newer.dependencyIdentity());
        assertTrue(newer.generation() > old.generation());
    }
    @Test void all27DependenciesFenceCommittedRevisionChangesIncludingNeighbors() {
        for (var dependency : vector(0, 3)) {
            var a = authority(); var certificate = prepare(a, vector(0, 3)); assertTrue(a.commit(certificate.identity()));
            a.retire(dependency.position());
            assertThrows(IllegalStateException.class, () -> a.observed(new RollingCubeActor.CubeVersion(
                    dependency.position(), dependency.leaseGeneration() + 100, dependency.revision() + 1)));
            assertNull(a.committed()); assertFalse(a.canCommit(certificate.identity()));
        }
    }
    @Test void failedOrUncertainSaveCannotCertifyEvenAnUnchangedOldSnapshot() {
        var a = authority(); var v = vector(0, 3); var old = prepare(a, v); assertTrue(a.commit(old.identity()));
        var original = v.stream().filter(value -> value.position().equals(ORIGINAL)).findFirst().orElseThrow();
        a.beforeEdit(ORIGINAL, original.leaseGeneration());
        assertFalse(a.current(old.identity())); assertFalse(a.canCommit(old.identity()));
        v.forEach(a::observed);
        assertNull(a.prepare(v), "Old in-memory READY state is not a durability repair after a failed save");
        a.durableEdit(new RollingCubeActor.CubeVersion(ORIGINAL, original.leaseGeneration(), 4));
        var revised = vector(0, 4); var replacement = a.prepare(revised);
        assertNotNull(replacement); assertEquals(4, replacement.originalRevision());
        assertFalse(a.canCommit(old.identity())); assertTrue(a.canCommit(replacement.identity()));
    }
    @Test void dependentEditRevokesBeforeItsSaveAndUnrelatedEditDoesNot() {
        var a = authority(); var certificate = prepare(a, vector(0, 2)); assertTrue(a.commit(certificate.identity()));
        a.beforeEdit(ORIGINAL.offset(10, 0, 0), 1000); assertTrue(a.current(certificate.identity()));
        a.beforeEdit(ORIGINAL.offset(1, 1, 1), 27); assertNull(a.committed()); assertNull(a.prepare(vector(0, 2)));
    }
    @Test void restartMustReconstructAllDurableDependenciesAndCannotReplayOldPublication() {
        var oldAuthority = authority(); var old = prepare(oldAuthority, vector(0, 2)); assertTrue(oldAuthority.commit(old.identity()));
        var restarted = authority(); assertFalse(restarted.canCommit(old.identity()));
        assertNull(restarted.prepare(vector(0, 2)), "Vector metadata without durable observed snapshots has no authority");
        var rebuilt = prepare(restarted, vector(0, 2));
        assertNotEquals(old.identity(), rebuilt.identity(), "Restart has a fresh issuer generation even for same caller epoch and leases");
        assertEquals(old.dependencyIdentity(), rebuilt.dependencyIdentity()); assertFalse(restarted.canCommit(old.identity()));
        assertTrue(restarted.commit(rebuilt.identity()));
    }
    @Test void missingUnknownDuplicateOrUneditedSourceCannotIssue() {
        var a = authority(); assertNull(prepare(a, vector(0, 0)));
        var v = vector(100, 1); v.subList(0, 26).forEach(a::observed);
        assertNull(a.prepare(v)); assertNull(a.prepare(v.subList(0, 26)));
        v.forEach(a::observed);
        var duplicated = new ArrayList<>(v); duplicated.add(v.getFirst());
        assertThrows(IllegalArgumentException.class, () -> a.prepare(duplicated));
        assertNotNull(a.prepare(v));
    }
    @Test void resourceEpochStoreProviderPaletteAndPoliciesAreIdentityBindings() {
        var a = authority(); var original = prepare(a, vector(0, 1));
        var others = List.of(
                new NativeReplacementAuthority(WORLD, 8, ORIGINAL, STORE, PALETTE, POLICY),
                new NativeReplacementAuthority(WORLD, 7, ORIGINAL, UUID.randomUUID().toString(), PALETTE, POLICY),
                new NativeReplacementAuthority(WORLD, 7, ORIGINAL, STORE, "b".repeat(64), POLICY),
                new NativeReplacementAuthority(WORLD, 7, ORIGINAL, STORE, PALETTE, new RollingCubeActor.ReplacementPolicy("source-v2", "opaque-unit-color-v1", "exact-face-v1", 1)),
                new NativeReplacementAuthority(WORLD, 7, ORIGINAL, STORE, PALETTE, new RollingCubeActor.ReplacementPolicy("source-v1", "changed", "exact-face-v1", 1)),
                new NativeReplacementAuthority(WORLD, 7, ORIGINAL, STORE, PALETTE, new RollingCubeActor.ReplacementPolicy("source-v1", "opaque-unit-color-v1", "changed", 1)),
                new NativeReplacementAuthority(WORLD, 7, ORIGINAL, STORE, PALETTE, new RollingCubeActor.ReplacementPolicy("source-v1", "opaque-unit-color-v1", "exact-face-v1", 2)));
        for (var other : others) assertNotEquals(original.dependencyIdentity(), prepare(other, vector(0, 1)).dependencyIdentity());
    }
    @Test void portableIndexIsBoundedAndCannotMoveOriginalEditRegion() throws Exception {
        String identity = ReplacementStoreIdentity.open(temporary, ORIGINAL);
        assertEquals(identity, ReplacementStoreIdentity.open(temporary, ORIGINAL));
        Path copy = Files.createDirectory(temporary.resolve("relocated"));
        Files.copy(temporary.resolve(ReplacementStoreIdentity.FILE), copy.resolve(ReplacementStoreIdentity.FILE));
        assertEquals(identity, ReplacementStoreIdentity.open(copy, ORIGINAL));
        assertThrows(java.io.IOException.class, () -> ReplacementStoreIdentity.open(copy, ORIGINAL.offset(1, 0, 0)));
        Files.writeString(copy.resolve(ReplacementStoreIdentity.FILE), "x".repeat(257));
        assertThrows(java.io.IOException.class, () -> ReplacementStoreIdentity.open(copy, ORIGINAL));
    }
}
