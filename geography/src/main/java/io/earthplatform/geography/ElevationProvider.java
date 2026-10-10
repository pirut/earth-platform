package io.earthplatform.geography;

import io.earthplatform.core.CancellationToken;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Ordered batch of terrain/seafloor elevations in metres in the dataset's declared vertical datum. */
public interface ElevationProvider {
    CompletableFuture<List<ElevationSample>> sample(List<GeoPoint> points, CancellationToken cancellation);
    record ElevationSample(double metres,DatasetProvenance provenance) {
        public ElevationSample { if (!Double.isFinite(metres) || provenance==null) throw new IllegalArgumentException("Invalid elevation sample"); }
    }
}
