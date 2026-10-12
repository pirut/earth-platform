package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Golden values calculated from the accepted011 byte contract before the012 overlay. */
class PagedLegacyEncodingIndependentReviewTest {
    private static final CubePos A = new CubePos(-2, 8, -3);
    private static final WorldIdentity WORLD = new WorldIdentity(-91, "analytic", "v1", "fixture", "palette");

    @Test void optionalIndexModeCannotChangeAdjacent011PublicationOrDurableBytes() {
        var certificate = legacy(1);
        assertEquals("f047963f46b5de4b0a1942a11bcf89e8d5402536a1a1f484605504b7c69acdc1", certificate.identity());
        assertEquals("5f15b812d7c88cbb3128a11607301f17206dba46b260d1ac2b4fa5fd1416fcc8", certificate.durableIdentity());
        assertEquals(certificate.durableIdentity(), certificate.dependencyIdentity());
    }

    @Test void optionalIndexModeCannotChangeDisjoint010PublicationOrDurableBytes() {
        var certificate = legacy(4);
        assertEquals("1033a8a29ef3815b9002d3e9416c473c2633ede2842f16bb7da84eb6c9d741d1", certificate.identity());
        assertEquals("ece11f120bc968c25b21894cf1b28e0a4c27d8d8bb0aa12497553b0022147bb9", certificate.durableIdentity());
    }

    private static ReplacementSetCertificate legacy(int offset) {
        CubePos b = new CubePos(A.x() + offset, A.y(), A.z());
        var regions = new ArrayList<ReplacementSetCertificate.Region>();
        long lease = 1;
        for (CubePos center : List.of(A, b)) {
            var dependencies = new ArrayList<RollingCubeActor.CubeVersion>();
            // Independent x/y/z canonical enumeration; no production ordering helper.
            for (int x = center.x() - 1; x <= center.x() + 1; x++)
                for (int y = center.y() - 1; y <= center.y() + 1; y++)
                    for (int z = center.z() - 1; z <= center.z() + 1; z++) {
                        var position = new CubePos(x, y, z);
                        dependencies.add(new RollingCubeActor.CubeVersion(position, lease++,
                                position.equals(A) ? 3 : position.equals(b) ? 5 : 0));
                    }
            regions.add(new ReplacementSetCertificate.Region(center, dependencies,
                    (center.equals(A) ? "c" : "d").repeat(64)));
        }
        return new ReplacementSetCertificate(WORLD, 11, 2, "98e296d8-68d8-44cd-8fd4-d314bca95c72",
                "a".repeat(64), "analytic-provider-v1", "b".repeat(64), "air-stone-diorite", "exact-unit", 101, regions);
    }
}
