package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.EditSnapshot;
import io.earthplatform.core.FileEditStore;
import io.earthplatform.core.WorldIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent adversaries for the explicit adjacent authority; no production helpers choose expected overlap. */
class AdjacentWorkspaceAuthorityIndependentReviewTest {
    private static final CubePos A = new CubePos(0, 8, 0), B = new CubePos(1, 8, 0);
    private static final WorldIdentity WORLD = new WorldIdentity(7, "analytic", "v1", "fixture", "palette");
    private static final String STORE = "98e296d8-68d8-44cd-8fd4-d314bca95c72";
    private static final String ADJACENT_INDEX = "replacement-adjacent-regions-v1.index";
    @TempDir Path temporary;

    @Test void explicitAdjacentModeCannotAliasLegacyDisjointPairOrExpandItsSet() {
        var adjacent = ReplacementWorkspaces.adjacent(A);
        var legacy = ReplacementWorkspaces.two(A);
        assertEquals(List.of(A, B), adjacent.positions());
        assertTrue(adjacent.isAdjacent()); assertFalse(legacy.isAdjacent());
        assertNotEquals(legacy, adjacent); assertNotEquals(legacy.identity(), adjacent.identity());
        assertEquals(adjacent, ReplacementWorkspaces.canonical(adjacent.positions()));
        assertThrows(IllegalArgumentException.class, () -> ReplacementWorkspaces.canonical(ReplacementWorkspaces.Mode.DISJOINT, adjacent.positions()));
        assertThrows(IllegalArgumentException.class, () -> ReplacementWorkspaces.canonical(ReplacementWorkspaces.Mode.ADJACENT, legacy.positions()));
        for (var invalid : List.of(List.of(A), List.of(A, A), List.of(B, A), List.of(A, A.offset(2, 0, 0)), List.of(A, B, B)))
            assertThrows(IllegalArgumentException.class, () -> ReplacementWorkspaces.canonical(ReplacementWorkspaces.Mode.ADJACENT, invalid));
        assertEquals(36, adjacent.positions().stream().flatMap(p -> adjacent.dependencies(p).stream()).distinct().count());
        var common = new HashSet<>(adjacent.dependencies(A)); common.retainAll(adjacent.dependencies(B));
        assertEquals(18, common.size());
        assertEquals(213, RollingCubeActor.MAX_RESIDENT_CUBES);
        assertEquals(55, RollingCubePlan.MAX_STAGED_MESH_GENERATIONS);
    }

    @Test void bothHaloContainmentProofUsesActualCommitted125AtEveryNearbyCenter() {
        var pair = ReplacementWorkspaces.adjacent(A);
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) {
            CubePos center = A.offset(dx, dy, dz);
            var current = new HashSet<>(CubeTickets.box(center, 2, 2, 125));
            for (var focus : pair.positions())
                assertEquals(current.containsAll(pair.dependencies(focus)), pair.insideWindow(focus, center), focus + " / " + center);
        }
    }

    @Test void sharedReadCellIsExactlyX14Through17AndOverlayPreservesBothImmutableParents() {
        var a = new NativeReplacementCapture(A, new byte[8000]);
        var b = new NativeReplacementCapture(B, new byte[8000]);
        String aid = a.contentIdentity(), bid = b.contentIdentity();
        long shared = 0;
        for (long y = 126; y < 146; y++) for (long z = -2; z < 18; z++) for (long x = -2; x < 34; x++) {
            boolean expectedA = x < 18, expectedB = x >= 14;
            assertEquals(expectedA, a.containsReadCell(x, y, z));
            assertEquals(expectedB, b.containsReadCell(x, y, z));
            if (expectedA && expectedB) shared++;
        }
        assertEquals(1600, shared);
        for (long x : List.of(14L, 15L, 16L, 17L)) for (long y : List.of(128L, 143L)) for (long z : List.of(0L, 15L)) {
            var changedA = a.withReadMaterial(x, y, z, NativeReplacementCapture.DIORITE);
            var changedB = b.withReadMaterial(x, y, z, NativeReplacementCapture.DIORITE);
            assertEquals(NativeReplacementCapture.DIORITE, changedA.materialAt(x, y, z));
            assertEquals(NativeReplacementCapture.DIORITE, changedB.materialAt(x, y, z));
            assertEquals(NativeReplacementCapture.AIR, a.materialAt(x, y, z));
            assertEquals(NativeReplacementCapture.AIR, b.materialAt(x, y, z));
            var compactA = changedA.compact(); var compactB = changedB.compact();
            assertEquals(changedA.contentIdentity(), compactA.contentIdentity());
            assertEquals(changedB.contentIdentity(), compactB.contentIdentity());
            assertArrayEquals(changedA.clonedBytes(), compactA.clonedBytes());
            assertArrayEquals(changedB.clonedBytes(), compactB.clonedBytes());
        }
        assertEquals(aid, a.contentIdentity()); assertEquals(bid, b.contentIdentity());
        assertThrows(IllegalArgumentException.class, () -> a.withMaterial(16, 128, 0, NativeReplacementCapture.STONE));
        assertThrows(IllegalArgumentException.class, () -> b.withMaterial(15, 128, 0, NativeReplacementCapture.STONE));
        assertThrows(RuntimeException.class, () -> a.withReadMaterial(18, 128, 0, NativeReplacementCapture.STONE));
        assertThrows(RuntimeException.class, () -> b.withReadMaterial(13, 128, 0, NativeReplacementCapture.STONE));
        assertThrows(RuntimeException.class, () -> a.withReadMaterial(Long.MAX_VALUE, 128, 0, NativeReplacementCapture.STONE));
        assertThrows(IllegalArgumentException.class, () -> a.withReadMaterial(15, 128, 0, 3));
    }

    private static ReplacementSetCertificate.Region region(CubePos position, long firstLease, Map<CubePos, Long> revisions) {
        var versions = new ArrayList<RollingCubeActor.CubeVersion>();
        for (var dependency : CubeTickets.box(position, 1, 1, 27).stream().sorted().toList())
            versions.add(new RollingCubeActor.CubeVersion(dependency, firstLease++, revisions.getOrDefault(dependency, 0L)));
        return new ReplacementSetCertificate.Region(position, versions, (position.equals(A) ? "c" : "d").repeat(64));
    }
    private static ReplacementSetCertificate set(ReplacementSetCertificate.Region a, ReplacementSetCertificate.Region b) {
        return new ReplacementSetCertificate(WORLD, 10, 2, STORE, "a".repeat(64), "analytic-provider-v1", "b".repeat(64),
                "air-stone-diorite", "exact-unit", 100, List.of(a, b));
    }

    @Test void serialCaptureLeasesMayDifferButEverySharedDurableRevisionMustAgree() {
        var revision = Map.of(A, 3L, B, 5L);
        var a = region(A, 1, revision); var b = region(B, 28, revision);
        var valid = assertDoesNotThrow(() -> set(a, b));
        assertEquals(3, valid.region(A).revision()); assertEquals(5, valid.region(B).revision());
        var shared = new HashSet<>(ReplacementWorkspaces.adjacent(A).dependencies(A));
        shared.retainAll(ReplacementWorkspaces.adjacent(A).dependencies(B));
        for (CubePos changed : shared) {
            var conflicting = new HashMap<>(revision); conflicting.put(changed, revision.getOrDefault(changed, 0L) + 1);
            assertThrows(IllegalArgumentException.class, () -> set(a, region(B, 28, conflicting)), changed.toString());
        }
        var aba = set(region(A, 101, revision), region(B, 128, revision));
        assertNotEquals(valid.identity(), aba.identity()); assertEquals(valid.durableIdentity(), aba.durableIdentity());
        var advanced = set(region(A, 101, Map.of(A, 4L, B, 5L)), region(B, 128, Map.of(A, 4L, B, 5L)));
        assertNotEquals(valid.durableIdentity(), advanced.durableIdentity());
    }

    @Test void sameSharedLeaseMayRepeatOnlyForTheSameCubeAndNeverAliasOtherCoordinates() {
        var a = region(A, 1, Map.of()); var b = region(B, 28, Map.of());
        var shared = a.dependencies().stream().filter(v -> b.dependencies().stream().anyMatch(w -> w.position().equals(v.position()))).findFirst().orElseThrow();
        var coherent = new ArrayList<>(b.dependencies());
        for (int i = 0; i < coherent.size(); i++) if (coherent.get(i).position().equals(shared.position())) {
            var old = coherent.get(i); coherent.set(i, new RollingCubeActor.CubeVersion(old.position(), shared.leaseGeneration(), old.revision()));
        }
        assertDoesNotThrow(() -> set(a, new ReplacementSetCertificate.Region(B, coherent, b.captureIdentity())));
        var foreign = new ArrayList<>(b.dependencies());
        var target = foreign.getLast();
        assertNotEquals(shared.position(), target.position());
        foreign.set(foreign.size() - 1, new RollingCubeActor.CubeVersion(target.position(), shared.leaseGeneration(), 0));
        assertThrows(IllegalArgumentException.class, () -> set(a, new ReplacementSetCertificate.Region(B, foreign, b.captureIdentity())));
    }

    @Test void explicitStoreModesRefuseCrossOpenWithoutRewritingOrAddingIndexes() throws Exception {
        for (boolean adjacentFirst : List.of(false, true)) {
            Path directory = Files.createDirectory(temporary.resolve(adjacentFirst ? "adjacent-first" : "disjoint-first"));
            var first = adjacentFirst ? ReplacementWorkspaces.adjacent(A) : ReplacementWorkspaces.two(A);
            var other = adjacentFirst ? ReplacementWorkspaces.two(A) : ReplacementWorkspaces.adjacent(A);
            var identity = ReplacementStoreIdentity.openTwo(directory, first);
            Path index = directory.resolve(adjacentFirst ? ADJACENT_INDEX : ReplacementStoreIdentity.TWO_FILE);
            byte[] before = Files.readAllBytes(index);
            assertThrows(IOException.class, () -> ReplacementStoreIdentity.openTwo(directory, other));
            assertThrows(IOException.class, () -> ReplacementStoreIdentity.open(directory, A));
            assertEquals(identity, ReplacementStoreIdentity.openTwo(directory, first));
            assertArrayEquals(before, Files.readAllBytes(index));
            try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        }
    }

    @Test void adjacentJournalCrashCutsAndRestartNeverTreatMissingEditedCubeAsClean() throws Exception {
        var workspaces = ReplacementWorkspaces.adjacent(A);
        for (CubePos target : workspaces.positions()) for (int cut = 0; cut <= 3; cut++) {
            Path directory = temporary.resolve("cut-" + target.x() + "-" + cut);
            try (var store = new FileEditStore(directory, WORLD)) {
                var ledger = ReplacementRevisionLedger.open(directory, workspaces);
                if (cut >= 1) ledger.begin(target, 0);
                if (cut >= 2) store.save(new EditSnapshot(target, 1, Map.of(0, 1)));
                if (cut >= 3) ledger.confirm(target, 1);
            }
            Path index = directory.resolve(ADJACENT_INDEX); byte[] before = Files.readAllBytes(index);
            try (var store = new FileEditStore(directory, WORLD)) {
                if (cut == 1 || cut == 2) assertThrows(IOException.class, () -> ReplacementRevisionLedger.open(directory, workspaces));
                else {
                    var ledger = ReplacementRevisionLedger.open(directory, workspaces);
                    for (var position : workspaces.positions())
                        ledger.verifyObserved(position, store.load(position).snapshot().revision());
                    assertEquals(cut == 3 ? 1 : 0, ledger.expectedRevision(target));
                    assertEquals(0, ledger.expectedRevision(target.equals(A) ? B : A));
                    if (cut == 3) assertThrows(IOException.class, () -> ledger.verifyObserved(target, 0));
                }
            }
            assertArrayEquals(before, Files.readAllBytes(index));
        }
    }

    @Test void missingAdjacentIndexRefusesExistingPrimaryBackupAndUnknownCubeData() throws Exception {
        for (String filename : List.of("0_8_0.cube", "1_8_0.cube.bak", "unknown.cube")) {
            Path directory = Files.createDirectory(temporary.resolve(filename.replace('.', '_')));
            Path cubes = Files.createDirectory(directory.resolve("cubes"));
            byte[] evidence = {1, 2, 3, 4}; Files.write(cubes.resolve(filename), evidence);
            assertThrows(IOException.class, () -> ReplacementStoreIdentity.openTwo(directory, ReplacementWorkspaces.adjacent(A)));
            assertFalse(Files.exists(directory.resolve(ADJACENT_INDEX)));
            assertArrayEquals(evidence, Files.readAllBytes(cubes.resolve(filename)));
        }
    }

    @Test void wholeSetCompletionStaysRevokedAfterIncoherentOrIncompleteAdjacentCapture() throws Exception {
        var workspaces = ReplacementWorkspaces.adjacent(A);
        try (var store = new FileEditStore(temporary, WORLD)) {
            var ledger = ReplacementRevisionLedger.open(temporary, workspaces);
            var policy = new RollingCubeActor.ReplacementPolicy("analytic-provider-v1", "air-stone-diorite", "exact-unit", 2, RollingEditPolicy.FULL_CUBE_V1);
            var authority = new NativeReplacementSetAuthority(WORLD, 10, workspaces, ledger.identity().storeIdentity(),
                    ledger.identity().indexIdentity(), "b".repeat(64), policy);
            var captures = List.of(new NativeReplacementCapture(A, new byte[8000]), new NativeReplacementCapture(B, new byte[8000]));
            long firstLease = 1;
            for (int i = 0; i < 2; i++) {
                var dependencies = region(workspaces.positions().get(i), firstLease, Map.of()).dependencies(); firstLease += 27;
                dependencies.forEach(authority::observed);
                assertTrue(authority.captured(new ReplacementSetCertificate.Region(workspaces.positions().get(i), dependencies, captures.get(i).contentIdentity())));
                dependencies.forEach(v -> authority.retire(v.position()));
            }
            authority.sealBootstrap(ledger); var prior = authority.prepare(); assertTrue(authority.commit(prior.identity()));
            authority.beforeEdit(A); assertFalse(authority.current(prior.identity())); assertNull(authority.prepare());
            ledger.begin(A, 0); store.save(new EditSnapshot(A, 1, Map.of(CubePos.index(15, 0, 0), 1))); ledger.confirm(A, 1);
            var changed = new RollingCubeActor.CubeVersion(A, 55, 1);
            var a = captures.get(0).withReadMaterial(15, 128, 0, NativeReplacementCapture.STONE);
            var b = captures.get(1).withReadMaterial(15, 128, 0, NativeReplacementCapture.STONE);
            assertThrows(IllegalArgumentException.class, () -> authority.completeEdit(changed, List.of(a, captures.get(1)), ledger));
            assertFalse(authority.canCommit(prior.identity())); assertNull(authority.prepare());
            assertThrows(IllegalArgumentException.class, () -> authority.completeEdit(changed, List.of(a), ledger));
            assertFalse(authority.canCommit(prior.identity())); assertNull(authority.prepare());
            assertDoesNotThrow(() -> authority.completeEdit(changed, List.of(a, b), ledger));
            var complete = authority.prepare(); assertNotNull(complete); complete.verifyCaptures(List.of(a,b));
            assertEquals(1, complete.region(A).revision()); assertEquals(0, complete.region(B).revision());
            assertFalse(authority.current(complete.identity())); assertFalse(authority.canCommit(prior.identity()));
            assertTrue(authority.commit(complete.identity())); assertTrue(authority.current(complete.identity()));
            // Historical serial capture leases are valid durable witnesses even with no other halo resident.
            assertEquals(1, authority.observedDependencyCount());
            for (var r : complete.regions()) assertEquals(1, r.dependencies().stream().filter(v -> v.position().equals(A)).findFirst().orElseThrow().revision());
        }
    }

}
