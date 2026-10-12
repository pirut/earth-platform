package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.PagedReplacementIndex;
import io.earthplatform.minecraft.enginebridge.ReplacementQueryAttestation;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import io.earthplatform.minecraft.enginebridge.ReplacementWorkspaces;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Client-side COMPLETE-query transitions must agree with the durable two-entry transaction. */
class PagedMirrorIndependentReviewTest {
    private static final CubePos A = new CubePos(0, 8, 0), B = new CubePos(1, 8, 0);
    private static final WorldIdentity WORLD = new WorldIdentity(1, "fixture", "v1", "source", "palette");
    private static final String STORE = "98e296d8-68d8-44cd-8fd4-d314bca95c72";
    private static final String INDEX = "a".repeat(64), POLICY = "b".repeat(64), PALETTE = "c".repeat(64);

    @Test void advancedCubeRevisionCannotClaimThePreviouslyCommittedSnapshotRoot() {
        var mirror = mirror(); var old = set(1, 1, "d".repeat(64), 0);
        mirror.propose(old); mirror.commit(old.identity()); mirror.beginEdit(A);
        var forged = set(2, 1, "d".repeat(64), 1);
        assertThrows(IllegalArgumentException.class, () -> mirror.propose(forged));
        assertFalse(mirror.isCurrent(forged.identity())); assertTrue(mirror.canPresent(old.identity()));
    }

    @Test void indexCannotAdvanceOrChangeItsRootWithoutAnAdmittedDurableEdit() {
        for (boolean editing : List.of(false, true)) {
            var mirror = mirror(); var old = set(1, 1, "d".repeat(64), 0);
            mirror.propose(old); mirror.commit(old.identity()); if (editing) mirror.beginEdit(A);
            var forged = set(2, 2, "e".repeat(64), 0);
            assertThrows(IllegalArgumentException.class, () -> mirror.propose(forged));
            assertFalse(mirror.isCurrent(forged.identity()));
        }
    }

    @Test void singleCubeEditCannotSkipAnIndexGenerationOrReuseTheOldRoot() {
        for (var forged : List.of(set(2, 3, "e".repeat(64), 1), set(2, 2, "d".repeat(64), 1))) {
            var mirror = mirror(); var old = set(1, 1, "d".repeat(64), 0);
            mirror.propose(old); mirror.commit(old.identity()); mirror.beginEdit(A);
            assertThrows(IllegalArgumentException.class, () -> mirror.propose(forged));
        }
    }

    @Test void cancellationKeepsNewIndexFloorButFreshPublicationOfExactSameDurableQueryCanSucceed() {
        var mirror = mirror(); var old = set(1, 1, "d".repeat(64), 0);
        mirror.propose(old); mirror.commit(old.identity()); mirror.beginEdit(A);
        var next = set(2, 2, "e".repeat(64), 1); mirror.propose(next); mirror.cancelProposal();
        assertThrows(IllegalArgumentException.class, () -> mirror.propose(old));
        var refresh = set(3, 2, "e".repeat(64), 1);
        assertDoesNotThrow(() -> mirror.propose(refresh)); mirror.commit(refresh.identity());
        assertFalse(mirror.canPresent(old.identity())); assertTrue(mirror.isCurrent(refresh.identity()));
    }

    @Test void sameGenerationDifferentRootIsAnAbaEvenWithUnchangedSummaryResults() {
        var mirror = mirror(); var old = set(1, 1, "d".repeat(64), 0);
        mirror.propose(old); mirror.commit(old.identity());
        assertThrows(IllegalArgumentException.class, () -> mirror.propose(set(2, 1, "e".repeat(64), 0)));
        assertTrue(mirror.isCurrent(old.identity()));
    }

    private static RollingReplacementSetAuthority mirror() {
        return new RollingReplacementSetAuthority(WORLD, 1, 1, ReplacementWorkspaces.adjacent(A),
                "provider", PALETTE, "material", "detail", POLICY, "layout");
    }
    private static ReplacementSetCertificate set(long publication, long indexGeneration, String root, long revisionA) {
        var regions = new ArrayList<ReplacementSetCertificate.Region>(); long lease = publication * 100;
        for (var center : List.of(A, B)) {
            var dependencies = new ArrayList<RollingCubeActor.CubeVersion>();
            for (int x = center.x() - 1; x <= center.x() + 1; x++) for (int y = center.y() - 1; y <= center.y() + 1; y++) for (int z = center.z() - 1; z <= center.z() + 1; z++) {
                var position = new CubePos(x, y, z);
                dependencies.add(new RollingCubeActor.CubeVersion(position, ++lease, position.equals(A) ? revisionA : 0));
            }
            regions.add(new ReplacementSetCertificate.Region(center, dependencies, (revisionA == 0 ? "f" : "e").repeat(64)));
        }
        var entries = regions.stream().map(region -> new PagedReplacementIndex.Entry(region.position(), region.revision(), POLICY, region.captureIdentity())).toList();
        long min = (long) Integer.MIN_VALUE * 16, max = ((long) Integer.MAX_VALUE + 1) * 16;
        var query = new ReplacementQueryAttestation(STORE, INDEX, indexGeneration, root, min, min, min, max, max, max,
                "layout", POLICY, PagedReplacementIndex.resultIdentity(entries), 2);
        return new ReplacementSetCertificate(WORLD, 1, 1, STORE, INDEX, "provider", PALETTE, "material", "detail", publication, regions, query);
    }
}
