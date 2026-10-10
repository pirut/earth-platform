package io.earthplatform.geography;

import io.earthplatform.core.*;
import java.util.Objects;

/** Opt-in public adapter to the unchanged bounded surface-fill contract. No sampling or implicit caches. */
public final class SurfaceHeightCubes {
    private SurfaceHeightCubes() {}
    public static ImmutableCube fill(CubePos position, long[] surfaceHeights, MaterialPalette palette, CancellationToken token) {
        Objects.requireNonNull(position); Objects.requireNonNull(surfaceHeights);
        Objects.requireNonNull(palette); Objects.requireNonNull(token);
        if (surfaceHeights.length != 256) throw new IllegalArgumentException("Exactly 256 surface columns required");
        return GeographyCubeProvider.fromHeights(position, surfaceHeights, palette, token);
    }
}
