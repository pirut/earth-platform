package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.lod.DhAnalyticFlatPolicy;
import io.earthplatform.minecraft.lod.DhLodLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Adversarial value-level checks independent of the012 issuer implementation. */
class PagedAttestationIndependentReviewTest {
    private static final CubePos A = new CubePos(-2, 8, -3), B = new CubePos(-1, 8, -3);
    private static final WorldIdentity WORLD = new WorldIdentity(-91, "analytic", "v1", "fixture", "palette");
    private static final String STORE = "98e296d8-68d8-44cd-8fd4-d314bca95c72";
    private static final String INDEX = "a".repeat(64), POLICY = "b".repeat(64), ROOT = "c".repeat(64);
    private static final long MIN = (long) Integer.MIN_VALUE * 16;
    private static final long MAX = ((long) Integer.MAX_VALUE + 1) * 16;

    @Test void realLong4096LayoutIdentityFitsTheBoundedAttestation() {
        assertTrue(DhLodLayout.LONG_4096.identity().length() > 128);
        var value = assertDoesNotThrow(() -> attestation(regions(1, 0), 1, ROOT,
                MIN, MIN, MIN, MAX, MAX, MAX, DhLodLayout.LONG_4096.identity(), POLICY));
        assertTrue(value.complete()); assertTrue(value.completeCatalogue());
        assertTrue(value.covers(-32, 128, -48, 0, 144, -32));
        assertFalse(value.covers(0, 0, 0, 0, 1, 1));
    }

    @Test void exactResultCannotBeReplacedByWellShapedHashForMissingOrDifferentSummary() {
        var regions = regions(1, 0);
        var good = attestation(regions, 1, ROOT, MIN, MIN, MIN, MAX, MAX, MAX, "layout", POLICY);
        assertDoesNotThrow(() -> certificate(regions, good, 1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> certificate(regions,
                new ReplacementQueryAttestation(STORE, INDEX, 1, ROOT, MIN, MIN, MIN, MAX, MAX, MAX,
                        "layout", POLICY, "f".repeat(64), 2), 1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> good.verifyRegions(regions.subList(0, 1)));
        var changed = new ArrayList<>(regions);
        changed.set(1, new ReplacementSetCertificate.Region(B, regions.get(1).dependencies(), "e".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> certificate(changed, good, 1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> certificate(regions(1, 1), good, 1, 1, 10));
    }

    @Test void snapshotGenerationRootQueryLayoutAndPolicyAreAllInCompleteCertificateIdentity() {
        var regions = regions(1, 0);
        var original = attestation(regions, 1, ROOT, MIN, MIN, MIN, MAX, MAX, MAX, "layout", POLICY);
        var certificate = certificate(regions, original, 1, 1, 10);
        var variants = List.of(
                attestation(regions, 2, ROOT, MIN, MIN, MIN, MAX, MAX, MAX, "layout", POLICY),
                attestation(regions, 1, "d".repeat(64), MIN, MIN, MIN, MAX, MAX, MAX, "layout", POLICY),
                attestation(regions, 1, ROOT, MIN + 1, MIN, MIN, MAX, MAX, MAX, "layout", POLICY),
                attestation(regions, 1, ROOT, MIN, MIN + 1, MIN, MAX, MAX, MAX, "layout", POLICY),
                attestation(regions, 1, ROOT, MIN, MIN, MIN + 1, MAX, MAX, MAX, "layout", POLICY),
                attestation(regions, 1, ROOT, MIN, MIN, MIN, MAX - 1, MAX, MAX, "layout", POLICY),
                attestation(regions, 1, ROOT, MIN, MIN, MIN, MAX, MAX - 1, MAX, "layout", POLICY),
                attestation(regions, 1, ROOT, MIN, MIN, MIN, MAX, MAX, MAX - 1, "layout", POLICY),
                attestation(regions, 1, ROOT, MIN, MIN, MIN, MAX, MAX, MAX, "other-layout", POLICY),
                attestation(regions, 1, ROOT, MIN, MIN, MIN, MAX, MAX, MAX, "layout", "e".repeat(64)));
        for (var variant : variants) {
            var changed = certificate(regions, variant, 1, 1, 10);
            assertNotEquals(original.identity(), variant.identity());
            assertEquals(certificate.indexIdentity(), changed.indexIdentity(), "Stable index identity cannot encode a commit generation");
            assertNotEquals(certificate.identity(), changed.identity());
            assertNotEquals(certificate.durableIdentity(), changed.durableIdentity());
        }
        assertNotEquals(certificate.identity(), certificate(regions, null, 1, 1, 10).identity());
        assertNotEquals(certificate.durableIdentity(), certificate(regions, null, 1, 1, 10).durableIdentity());
    }

    @Test void coldReconstructionChangesRuntimeIdentityButPreservesExactDurableIndexedHash() {
        var beforeRegions = regions(1, 3); var afterRegions = regions(901, 3);
        var query = attestation(beforeRegions, 7, ROOT, MIN, MIN, MIN, MAX, MAX, MAX, "layout", POLICY);
        var before = certificate(beforeRegions, query, 1, 1, 10);
        var after = certificate(afterRegions, query, 2, 2, 11);
        assertNotEquals(before.identity(), after.identity());
        assertEquals(before.durableIdentity(), after.durableIdentity());
        assertEquals(before.queryAttestation().identity(), after.queryAttestation().identity());
    }

    @Test void malformedExtentCountStoreIndexAndGenerationNeverBecomeCompleteCertificateValues() {
        var regions = regions(1, 0);
        var good = attestation(regions, 1, ROOT, MIN, MIN, MIN, MAX, MAX, MAX, "layout", POLICY);
        assertThrows(IllegalArgumentException.class, () -> new ReplacementQueryAttestation(STORE, INDEX, -1, ROOT,
                MIN, MIN, MIN, MAX, MAX, MAX, "layout", POLICY, good.resultDigest(), 2));
        for (int count : List.of(-1, 0, 1, 3, Integer.MAX_VALUE))
            assertThrows(IllegalArgumentException.class, () -> new ReplacementQueryAttestation(STORE, INDEX, 1, ROOT,
                    MIN, MIN, MIN, MAX, MAX, MAX, "layout", POLICY, good.resultDigest(), count));
        assertThrows(IllegalArgumentException.class, () -> attestation(regions, 1, ROOT, Long.MIN_VALUE, MIN, MIN, MAX, MAX, MAX, "layout", POLICY));
        assertThrows(IllegalArgumentException.class, () -> attestation(regions, 1, ROOT, MIN, MIN, MIN, Long.MAX_VALUE, MAX, MAX, "layout", POLICY));
        assertThrows(IllegalArgumentException.class, () -> attestation(regions, 1, ROOT, 0, 0, 0, 0, 1, 1, "layout", POLICY));
        var outside = attestation(regions, 1, ROOT, 0, 0, 0, 16, 16, 16, "layout", POLICY);
        assertThrows(IllegalArgumentException.class, () -> certificate(regions, outside, 1, 1, 10));
        for (boolean store : List.of(false, true)) {
            var foreign = new ReplacementQueryAttestation(store ? "c4108548-9ff4-4f18-a8e1-2c3c0fd2e3a0" : STORE,
                    store ? INDEX : "e".repeat(64), 1, ROOT, MIN, MIN, MIN, MAX, MAX, MAX, "layout", POLICY, good.resultDigest(), 2);
            assertThrows(IllegalArgumentException.class, () -> certificate(regions, foreign, 1, 1, 10));
        }
    }

    @Test void durablePolicyBindsWorldProviderPaletteMaterialDetailEditPolicyAndWorkspaceButNotResources() {
        String source = DhAnalyticFlatPolicy.expectedDataset(128);
        var world = new WorldIdentity(0, "earthplatform:analytic-native-cube", "analytic-native-cube-v1/rolling-interior-v1", source, "palette");
        var flat = new DhAnalyticFlatPolicy(source, world, 128, 0xff808080);
        var policy = new RollingCubeActor.ReplacementPolicy("provider", "material", "detail", 1, RollingEditPolicy.FULL_CUBE_V1);
        var pair = ReplacementWorkspaces.adjacent(A);
        String original = ReplacementQueryAttestation.policyIdentity(world, INDEX, policy, flat, pair);
        assertEquals(original, ReplacementQueryAttestation.policyIdentity(world, INDEX,
                new RollingCubeActor.ReplacementPolicy("provider", "material", "detail", 99, RollingEditPolicy.FULL_CUBE_V1), flat, pair));
        for (var change : List.of(
                new RollingCubeActor.ReplacementPolicy("other", "material", "detail", 1, RollingEditPolicy.FULL_CUBE_V1),
                new RollingCubeActor.ReplacementPolicy("provider", "other", "detail", 1, RollingEditPolicy.FULL_CUBE_V1),
                new RollingCubeActor.ReplacementPolicy("provider", "material", "other", 1, RollingEditPolicy.FULL_CUBE_V1),
                new RollingCubeActor.ReplacementPolicy("provider", "material", "detail", 1, RollingEditPolicy.INTERIOR_V1)))
            assertNotEquals(original, ReplacementQueryAttestation.policyIdentity(world, INDEX, change, flat, pair));
        assertNotEquals(original, ReplacementQueryAttestation.policyIdentity(world, ROOT, policy, flat, pair));
        assertNotEquals(original, ReplacementQueryAttestation.policyIdentity(new WorldIdentity(1, world.generatorId(), world.generatorVersion(), source, "palette"), INDEX, policy, flat, pair));
        assertNotEquals(original, ReplacementQueryAttestation.policyIdentity(world, INDEX, policy, flat, ReplacementWorkspaces.adjacent(A.offset(1, 0, 0))));
    }

    private static ReplacementQueryAttestation attestation(List<ReplacementSetCertificate.Region> regions, long generation,
            String root, long x0, long y0, long z0, long x1, long y1, long z1, String layout, String policy) {
        var entries = regions.stream().map(region -> new PagedReplacementIndex.Entry(region.position(), region.revision(), policy, region.captureIdentity())).toList();
        return new ReplacementQueryAttestation(STORE, INDEX, generation, root, x0, y0, z0, x1, y1, z1,
                layout, policy, PagedReplacementIndex.resultIdentity(entries), 2);
    }
    private static ReplacementSetCertificate certificate(List<ReplacementSetCertificate.Region> regions,
            ReplacementQueryAttestation query, long epoch, long resources, long generation) {
        return new ReplacementSetCertificate(WORLD, epoch, resources, STORE, INDEX, "provider", "d".repeat(64),
                "material", "detail", generation, regions, query);
    }
    private static List<ReplacementSetCertificate.Region> regions(long lease, long revision) {
        var result = new ArrayList<ReplacementSetCertificate.Region>();
        for (var center : List.of(A, B)) {
            var dependencies = new ArrayList<RollingCubeActor.CubeVersion>();
            for (int x = center.x() - 1; x <= center.x() + 1; x++)
                for (int y = center.y() - 1; y <= center.y() + 1; y++)
                    for (int z = center.z() - 1; z <= center.z() + 1; z++) {
                        var position = new CubePos(x, y, z);
                        dependencies.add(new RollingCubeActor.CubeVersion(position, lease++, position.equals(A) ? revision : 0));
                    }
            result.add(new ReplacementSetCertificate.Region(center, dependencies, (center.equals(A) ? "e" : "f").repeat(64)));
        }
        return List.copyOf(result);
    }
}
