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

    /**
     * Starts loading what {@link #refine} will read for a tile, before the tile's own pixels are had,
     * so a first visit's downloads run at once rather than one after another. May block; a failure
     * here is left for refine to meet.
     */
    default void prefetch(int zoom, int x, int y) {}

    /**
     * Reads all that {@link #refine} needs of a tile but the tile's own pixels, while those are
     * still on their way: on a first visit a layer's downloads then run beside the tile's and the
     * other layers' rather than behind them. What it returns is handed to
     * {@link #refine(int, int, int, float[], Object)} for the same tile, which waits for it; null
     * leaves refine to read for itself. May block; a failure here is left for refine to meet.
     */
    default Object plan(int zoom, int x, int y) { return null; }

    /** {@link #refine(int, int, int, float[])} with what {@link #plan} read for the tile, or null: the values come out the same either way. */
    default void refine(int zoom, int x, int y, float[] values, Object plan) { refine(zoom, x, y, values); }

    /**
     * Whether {@link #refine(int, int, int, float[], Object)} with this plan sets every pixel of the tile from the
     * layer's own data alone, whatever the tile held. The layers before this one are then left out for the tile:
     * nothing they could do to it would show, and on a first visit their downloads were what it waited on.
     *
     * @param plan gives what {@link #plan} read for the tile, waiting for it if it is still on its way, or null
     */
    default boolean replaces(java.util.function.Supplier<Object> plan) { return false; }
}
