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
 * remembers its most recent tile to avoid lock traffic on the shared cache during column sampling, and a 4x4 kernel
 * that lies inside one tile reads it once.
 */
public final class GlobalElevation {
    public static final int BATHYMETRY_ZOOM = 10;
    private final MercatorGrid grid;
    private final TerrariumTileSource tiles;
    private final int zoom;
    private final int tilesAcross;
    private final GlobalElevation bathymetry;
    private final ThreadLocal<Last> last = ThreadLocal.withInitial(Last::new);

    /** A thread's most recent tile, and room for one kernel's pixels. */
    private static final class Last {
        long key = -1;
        float[] tile;
        final float[] kernel = new float[16];
    }

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
        float[] raw = kernel(ix, iy);
        double result = 0;
        for (int j = -1; j <= 2; j++) {
            double row = 0;
            for (int i = -1; i <= 2; i++) row += pixel(raw[(j + 1) * 4 + i + 1], ix + i, iy + j) * weight(i - fx);
            result += row * weight(j - fy);
        }
        return result;
    }

    /** Elevation at the centre of an integer block column. */
    public double metres(long blockX, long blockZ) { return metres(blockX + 0.5, blockZ + 0.5); }

    /**
     * Elevation in metres at a block-space position, with the same interpolation as
     * {@link #metres(double, double)}. Also writes the slope in metres per block along X and Z
     * to {@code out[0]} and {@code out[1]}, and to {@code out[2]} the interpolation weight of
     * source pixels that are open sea (about 1 offshore, 0 inland, 0.5 on the coastline). Only
     * zooms above {@link #BATHYMETRY_ZOOM} mark open sea; below that {@code out[2]} is 0.
     */
    public double sample(double blockX, double blockZ, double[] out) {
        double px = grid.pixelX(blockX, zoom) - 0.5, py = grid.pixelY(blockZ, zoom) - 0.5;
        long ix = (long) Math.floor(px), iy = (long) Math.floor(py);
        double fx = px - ix, fy = py - iy;
        double value = 0, dx = 0, dy = 0, sea = 0;
        float[] kernel = kernel(ix, iy);
        for (int j = -1; j <= 2; j++) {
            double wy = weight(j - fy), dwy = -slope(j - fy);
            for (int i = -1; i <= 2; i++) {
                double wx = weight(i - fx), dwx = -slope(i - fx);
                float raw = kernel[(j + 1) * 4 + i + 1];
                double p = raw;
                if (raw == 0f && bathymetry != null) {
                    p = fill(ix + i, iy + j);
                    sea += wx * wy;
                }
                value += p * wx * wy;
                dx += p * dwx * wy;
                dy += p * wx * dwy;
            }
        }
        double pixelsPerBlock = 1.0 / grid.blocksPerPixel(zoom);
        out[0] = dx * pixelsPerBlock;
        out[1] = dy * pixelsPerBlock;
        out[2] = sea;
        return value;
    }

    /** Source pixel with open-sea zeros replaced by coarser bathymetry. */
    double pixel(long gx, long gy) { return pixel(rawPixel(gx, gy), gx, gy); }

    private double pixel(float raw, long gx, long gy) {
        if (raw != 0f || bathymetry == null) return raw;
        return fill(gx, gy);
    }

    /** Depth for an open-sea pixel from the bathymetry zoom; never above sea level. */
    private double fill(long gx, long gy) {
        long size = (long) tilesAcross * TerrariumTileSource.TILE_SIZE;
        double scale = (double) (1L << BATHYMETRY_ZOOM) / tilesAcross;
        double bx = (Math.floorMod(gx, size) + 0.5) * scale - 0.5, by = (Math.max(0, Math.min(size - 1, gy)) + 0.5) * scale - 0.5;
        return Math.min(0, bathymetry.atPixel(bx, by));
    }

    private double atPixel(double px, double py) {
        long ix = (long) Math.floor(px), iy = (long) Math.floor(py);
        double fx = px - ix, fy = py - iy, result = 0;
        float[] raw = kernel(ix, iy);
        for (int j = -1; j <= 2; j++) {
            double row = 0;
            for (int i = -1; i <= 2; i++) row += raw[(j + 1) * 4 + i + 1] * weight(i - fx);
            result += row * weight(j - fy);
        }
        return result;
    }

    /** Single source pixel, wrapping X around the globe and clamping Y at the coverage edge. */
    public float rawPixel(long gx, long gy) {
        long size = (long) tilesAcross * TerrariumTileSource.TILE_SIZE;
        gx = Math.floorMod(gx, size);
        gy = Math.max(0, Math.min(size - 1, gy));
        return tile(last.get(), (int) (gx >> 8), (int) (gy >> 8))[(int) ((gy & 255) * 256 + (gx & 255))];
    }

    private float[] tile(Last cached, int tx, int ty) {
        long key = ((long) tx << 32) | ty;
        if (cached.key != key) {
            cached.tile = tiles.tile(zoom, tx, ty);
            cached.key = key;
        }
        return cached.tile;
    }

    /**
     * Raw pixels (ix - 1 .. ix + 2, iy - 1 .. iy + 2), row by row, in this thread's scratch array,
     * which the next kernel on this thread reuses. A kernel inside one tile reads it once.
     */
    private float[] kernel(long ix, long iy) {
        Last cached = last.get();
        float[] out = cached.kernel;
        long size = (long) tilesAcross * TerrariumTileSource.TILE_SIZE;
        if (ix >= 1 && iy >= 1 && ix + 2 < size && iy + 2 < size && (ix - 1) >> 8 == (ix + 2) >> 8 && (iy - 1) >> 8 == (iy + 2) >> 8) {
            float[] tile = tile(cached, (int) (ix >> 8), (int) (iy >> 8));
            int at = (int) (((iy - 1) & 255) * 256 + ((ix - 1) & 255));
            for (int j = 0; j < 4; j++, at += 256) {
                out[j * 4] = tile[at]; out[j * 4 + 1] = tile[at + 1]; out[j * 4 + 2] = tile[at + 2]; out[j * 4 + 3] = tile[at + 3];
            }
            return out;
        }
        for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) out[j * 4 + i] = rawPixel(ix + i - 1, iy + j - 1);
        return out;
    }

    /** Catmull-Rom kernel; interpolates exactly through pixel centres and has continuous first derivatives. */
    static double weight(double t) {
        t = Math.abs(t);
        if (t < 1) return 1.5 * t * t * t - 2.5 * t * t + 1;
        if (t < 2) return -0.5 * t * t * t + 2.5 * t * t - 4 * t + 2;
        return 0;
    }

    /** Derivative of {@link #weight} with respect to t. */
    static double slope(double t) {
        double a = Math.abs(t), sign = Math.signum(t);
        if (a < 1) return sign * (4.5 * a * a - 5 * a);
        if (a < 2) return sign * (-1.5 * a * a + 5 * a - 4);
        return 0;
    }
}
