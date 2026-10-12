package io.earthplatform.geography.globe;

/**
 * Continuous elevation over the whole Mercator grid, bicubic (Catmull-Rom) through Terrarium pixel centres.
 *
 * <p>Terrain Tiles carry ocean bathymetry only up to zoom {@value #BATHYMETRY_ZOOM}; deeper zooms encode open sea as
 * exactly 0 m. Every detail pixel that reads exactly 0 is therefore replaced by the bicubic bathymetry from zoom
 * {@value #BATHYMETRY_ZOOM} when that is below sea level, before the detail kernel runs, so coasts blend continuously.
 *
 * <p>Sampling happens in slippy-map pixel space, which is linear in block coordinates, so neighbouring tiles join
 * without seams and X wraps across the antimeridian. Rows are clamped at the poles of the coverage square. Each thread
 * remembers its most recent tile to avoid lock traffic on the shared cache during column sampling.
 */
public final class GlobalElevation {
    public static final int BATHYMETRY_ZOOM = 10;
    private final MercatorGrid grid;
    private final TerrariumTileSource tiles;
    private final int zoom;
    private final int tilesAcross;
    private final GlobalElevation bathymetry;
    private final ThreadLocal<long[]> lastKey = ThreadLocal.withInitial(() -> new long[]{-1});
    private final ThreadLocal<float[][]> lastTile = ThreadLocal.withInitial(() -> new float[1][]);

    public GlobalElevation(MercatorGrid grid, TerrariumTileSource tiles, int zoom) {
        this(grid, tiles, zoom, zoom > BATHYMETRY_ZOOM);
    }

    private GlobalElevation(MercatorGrid grid, TerrariumTileSource tiles, int zoom, boolean fillBathymetry) {
        if (zoom < 0 || zoom > TerrariumTileSource.MAX_ZOOM) throw new IllegalArgumentException("Unsupported zoom " + zoom);
        this.grid = grid;
        this.tiles = tiles;
        this.zoom = zoom;
        this.tilesAcross = 1 << zoom;
        this.bathymetry = fillBathymetry ? new GlobalElevation(grid, tiles, BATHYMETRY_ZOOM, false) : null;
    }

    public MercatorGrid grid() { return grid; }
    public int zoom() { return zoom; }

    /** Elevation in metres at a continuous block-space position. */
    public double metres(double blockX, double blockZ) {
        double px = grid.pixelX(blockX, zoom) - 0.5, py = grid.pixelY(blockZ, zoom) - 0.5;
        long ix = (long) Math.floor(px), iy = (long) Math.floor(py);
        double fx = px - ix, fy = py - iy;
        double result = 0;
        for (int j = -1; j <= 2; j++) {
            double row = 0;
            for (int i = -1; i <= 2; i++) row += pixel(ix + i, iy + j) * weight(i - fx);
            result += row * weight(j - fy);
        }
        return result;
    }

    /** Elevation at the centre of an integer block column. */
    public double metres(long blockX, long blockZ) { return metres(blockX + 0.5, blockZ + 0.5); }

    /** Source pixel with open-sea zeros replaced by coarser bathymetry. */
    double pixel(long gx, long gy) {
        float raw = rawPixel(gx, gy);
        if (raw != 0f || bathymetry == null) return raw;
        long size = (long) tilesAcross * TerrariumTileSource.TILE_SIZE;
        double scale = (double) (1L << BATHYMETRY_ZOOM) / tilesAcross;
        double bx = (Math.floorMod(gx, size) + 0.5) * scale - 0.5, by = (Math.max(0, Math.min(size - 1, gy)) + 0.5) * scale - 0.5;
        return Math.min(0, bathymetry.atPixel(bx, by));
    }

    private double atPixel(double px, double py) {
        long ix = (long) Math.floor(px), iy = (long) Math.floor(py);
        double fx = px - ix, fy = py - iy, result = 0;
        for (int j = -1; j <= 2; j++) {
            double row = 0;
            for (int i = -1; i <= 2; i++) row += rawPixel(ix + i, iy + j) * weight(i - fx);
            result += row * weight(j - fy);
        }
        return result;
    }

    /** Single source pixel, wrapping X around the globe and clamping Y at the coverage edge. */
    public float rawPixel(long gx, long gy) {
        long size = (long) tilesAcross * TerrariumTileSource.TILE_SIZE;
        gx = Math.floorMod(gx, size);
        gy = Math.max(0, Math.min(size - 1, gy));
        int tx = (int) (gx >> 8), ty = (int) (gy >> 8);
        long key = ((long) tx << 32) | ty;
        long[] last = lastKey.get();
        float[][] cached = lastTile.get();
        if (last[0] != key) {
            cached[0] = tiles.tile(zoom, tx, ty);
            last[0] = key;
        }
        return cached[0][(int) ((gy & 255) * 256 + (gx & 255))];
    }

    /** Catmull-Rom kernel; interpolates exactly through pixel centres and has continuous first derivatives. */
    static double weight(double t) {
        t = Math.abs(t);
        if (t < 1) return 1.5 * t * t * t - 2.5 * t * t + 1;
        if (t < 2) return -0.5 * t * t * t + 2.5 * t * t - 4 * t + 2;
        return 0;
    }
}
