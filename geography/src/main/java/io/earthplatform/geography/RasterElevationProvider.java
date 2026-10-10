package io.earthplatform.geography;

import io.earthplatform.core.CancellationToken;
import java.util.*;
import java.util.concurrent.*;

/** Loads unique tiles once per batch, then samples locally. Manifest order is the explicit overlap precedence. */
public final class RasterElevationProvider implements ElevationProvider {
    private final WorldManifest manifest;
    private final TileStore store;
    public RasterElevationProvider(WorldManifest manifest, TileStore store) { this.manifest = Objects.requireNonNull(manifest); this.store = Objects.requireNonNull(store); }
    @Override public CompletableFuture<List<ElevationSample>> sample(List<GeoPoint> points,CancellationToken cancellation) {
        try {
            cancellation.throwIfCancelled();
            List<GeoPoint> positions = List.copyOf(points);
            if (positions.size()>65536) throw new IllegalArgumentException("Batch exceeds 65536 points");
            var selected = new ArrayList<TileSpec>();
            // Resolve complete coverage before scheduling I/O: an uncovered point creates no partial background transfer.
            for (GeoPoint p : positions) selected.add(select(manifest,p));
            var unique = new LinkedHashMap<TileSpec,CompletableFuture<RasterTile>>();
            for (TileSpec tile : selected) unique.computeIfAbsent(tile,store::load);
            return CompletableFuture.allOf(unique.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                cancellation.throwIfCancelled();
                var samples = new ArrayList<ElevationSample>(positions.size());
                for (int i=0;i<positions.size();i++) { cancellation.throwIfCancelled(); samples.add(new ElevationSample(unique.get(selected.get(i)).join().sample(positions.get(i)),manifest.provenance())); }
                return List.copyOf(samples);
            });
        } catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
    }
    static TileSpec select(WorldManifest manifest,GeoPoint point) {
        for (TileSpec tile : manifest.tiles()) if (tile.covers(point)) return tile;
        throw new MissingTerrainException("No pinned tile covers " + point.latitude() + "," + point.longitude());
    }
}
