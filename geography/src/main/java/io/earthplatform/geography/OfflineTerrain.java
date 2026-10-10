package io.earthplatform.geography;

import io.earthplatform.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Small offline-pack adapter. Load only during setup; sampling/generation performs no I/O or waiting. */
public final class OfflineTerrain {
    public static final long MAX_PACK_BYTES = 64L * 1024 * 1024;
    private final WorldManifest manifest;
    private final Map<TileSpec,RasterTile> tiles;
    private OfflineTerrain(WorldManifest manifest,Map<TileSpec,RasterTile> tiles) { this.manifest=manifest; this.tiles=Map.copyOf(tiles); }
    public static OfflineTerrain load(Path manifestFile) throws IOException {
        WorldManifest manifest = WorldManifest.load(manifestFile);
        if (!GeographyCubeProvider.GENERATOR_VERSION.equals(manifest.generatorVersion())) throw new IOException("Unsupported generator version " + manifest.generatorVersion());
        long total = manifest.tiles().stream().mapToLong(TileSpec::encodedBytes).sum();
        if (total > MAX_PACK_BYTES) throw new IOException("Offline preload pack exceeds 64 MiB; use asynchronous CachedTileStore");
        Path root = manifestFile.toAbsolutePath().getParent().toRealPath();
        var tiles = new LinkedHashMap<TileSpec,RasterTile>();
        for (TileSpec spec : manifest.tiles()) {
            byte[] bytes = CachedTileStore.readSource(root,spec,false);
            CachedTileStore.verify(spec,bytes); tiles.put(spec,RasterTile.decode(spec,bytes));
        }
        return new OfflineTerrain(manifest,tiles);
    }
    public WorldManifest manifest() { return manifest; }
    public double elevationMetres(GeoPoint point) { return tiles.get(RasterElevationProvider.select(manifest,point)).sample(point); }
    public double elevationMetres(long x,long z) { return elevationMetres(manifest.projection().blockCentre(x,z)); }
    public long surfaceBlockY(long x,long z) { return GeographyCubeProvider.surfaceY(elevationMetres(x,z)); }
    public ImmutableCube cube(CubePos position,MaterialPalette palette) {
        long[] heights = new long[256];
        for (int z=0;z<16;z++) for (int x=0;x<16;x++) heights[z*16+x]=surfaceBlockY(position.minBlockX()+x,position.minBlockZ()+z);
        return GeographyCubeProvider.fromHeights(position,heights,palette,new CancellationToken());
    }
}
