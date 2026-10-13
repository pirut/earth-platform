package io.earthplatform.geography.globe;

/**
 * A second elevation source laid over the Terrarium composite where it knows better. Layers run in
 * order in {@link TerrariumTileSource#tile} after the composite's own cleaning, each on the tile the
 * previous one left, so a later layer wins where both cover a place.
 *
 * <p>A layer must be deterministic for the same cached source bytes, blend its own coverage edge into
 * the values it is given so no seam shows, and leave pixels that read exactly 0 (open sea) as they
 * are. It may block on I/O; tiles are read on world-generation worker threads.
 */
public interface ElevationLayer {
    /** Stable id pinned in a world's settings, so a world keeps the sources it was made with. */
    String id();

    /**
     * Refines a {@link TerrariumTileSource#TILE_SIZE}-square Web Mercator tile in place: metres, row
     * by row from the north. Throws {@link io.earthplatform.geography.MissingTerrainException} when
     * the layer covers the tile but its data can't be had.
     */
    void refine(int zoom, int x, int y, float[] values);
}
