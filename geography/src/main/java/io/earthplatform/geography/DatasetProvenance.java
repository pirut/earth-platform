package io.earthplatform.geography;

import java.net.URI;
import java.util.Objects;

/** Source provenance describes input resolution, not voxel-scale measurement accuracy. */
public record DatasetProvenance(String id, String release, String verticalDatum, double resolutionArcSeconds,
                                String license, URI source, String quality, String attribution) {
    public DatasetProvenance {
        for (String value : new String[] {id, release, verticalDatum, license, quality, attribution})
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing dataset provenance");
        Objects.requireNonNull(source);
        if (!Double.isFinite(resolutionArcSeconds) || resolutionArcSeconds <= 0)
            throw new IllegalArgumentException("Invalid native angular resolution");
    }
}
