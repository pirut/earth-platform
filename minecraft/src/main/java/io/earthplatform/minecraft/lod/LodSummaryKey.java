package io.earthplatform.minecraft.lod;

import io.earthplatform.core.WorldIdentity;
import java.util.Objects;

/**
 * Canonical CPU content identity, independent of renderer pages and graphics resources.
 * The provider identity must pin every provider/appearance input not already in the world,
 * including the world/edit-store namespace when multiple stores share generation parameters.
 * The authoritative edit revision and variant (for example an exact mask/refinement digest)
 * must describe the input actually read by the source, not a local request counter.
 */
public record LodSummaryKey(WorldIdentity world, String providerIdentity, Volume volume,
                            int detailLevel, long editRevision, String variantId) {
    public LodSummaryKey {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(volume, "volume");
        validateIdentity(providerIdentity);
        validateIdentity(variantId);
        if (detailLevel < 0 || editRevision < 0) throw new IllegalArgumentException("Negative detail/revision");
    }

    private static void validateIdentity(String value) {
        Objects.requireNonNull(value, "identity");
        if (value.isBlank() || value.length() > 1024) throw new IllegalArgumentException("Invalid LOD identity");
    }

    /** Native three-dimensional, half-open bounds. No vertical column or renderer-page assumption. */
    public record Volume(long minX, long minY, long minZ,
                         long maxXExclusive, long maxYExclusive, long maxZExclusive) {
        public Volume {
            if (minX >= maxXExclusive || minY >= maxYExclusive || minZ >= maxZExclusive)
                throw new IllegalArgumentException("Empty or inverted LOD volume");
            Math.subtractExact(maxXExclusive, minX);
            Math.subtractExact(maxYExclusive, minY);
            Math.subtractExact(maxZExclusive, minZ);
        }
    }
}
