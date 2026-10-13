package io.earthplatform.geography.globe;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * The Reference Elevation Model of Antarctica (REMA) v2.0 mosaic, read straight from the Polar
 * Geospatial Center's public AWS Open Data bucket: heights in metres above the WGS 84 ellipsoid on
 * Antarctic Polar Stereographic (EPSG:3031) metres.
 *
 * <p>Two products are read. {@link #FINE} is the 2 m mosaic, one cloud-optimised GeoTIFF per 50 km
 * square, named {@code 2m/RR_CC/RR_CC_SR_SC_2m_v2.0_dem.tif}; {@link #COARSE} is the 32 m mosaic,
 * one file per 100 km square, {@code 32m/RR_CC/RR_CC_32m_v2.0_dem.tif}. Squares count from
 * (-3100 km, -3100 km); {@code SR}/{@code SC} pick the north/east half when 2. Each file overlaps its
 * neighbours by about 100 m, holds halving overviews and 512-pixel blocks of LZW-compressed floats
 * with the floating-point predictor. Squares with no land have no file.
 *
 * <p>Files are read with {@link FloatCogs}: only headers and the blocks a caller touches, kept in an
 * optional disk cache. Callers block.
 */
public final class RemaSource implements AutoCloseable {
    public static final String DATASET_ID = "rema-v2.0";
    public static final URI AWS_REMA = URI.create("https://pgc-opendata-dems.s3.us-west-2.amazonaws.com/rema/mosaics/v2.0/");
    public static final int FINE = 0, COARSE = 1;
    /** Metres per pixel of each product at full resolution. */
    static final double[] RESOLUTION = {2, 32};
    /** Overviews each product holds below full resolution. */
    static final int[] MAX_OVERVIEW = {6, 4};
    private static final double ORIGIN = -3_100_000, SQUARE = 100_000;

    private final FloatCogs files;

    /**
     * @param base              URI prefix the products are named under
     * @param cacheRoot         optional disk cache directory, or null for memory only
     * @param allowNetwork      false reads only the disk cache
     * @param maxBlocksInMemory decoded blocks kept (1 MiB each)
     */
    public RemaSource(URI base, Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        files = new FloatCogs("REMA elevation", DATASET_ID, "earth-rema-io", base, RemaSource::name, -9999f,
                cacheRoot, allowNetwork, maxBlocksInMemory, maxConcurrentTransfers, timeout);
    }

    /**
     * Height above the ellipsoid at polar stereographic {@code x, y}, bilinear over the valid pixels
     * of a product's overview (0 for full resolution); NaN where there is no data.
     */
    public double metres(int product, int overview, double x, double y) {
        FloatCogs.Grid grid = grid(product, overview, x, y);
        return grid == null ? Double.NaN : files.metres(grid, x, y);
    }

    /**
     * The share of a product's overview that holds data around {@code x, y}, bilinear between pixel
     * centres: 1 inside the data, 0 outside, a ramp one pixel wide at its edge.
     */
    public double coverage(int product, int overview, double x, double y) {
        FloatCogs.Grid grid = grid(product, overview, x, y);
        return grid == null ? 0 : files.coverage(grid, x, y);
    }

    /** The overview of the file whose own square holds {@code x, y}, or null where there is none. */
    private FloatCogs.Grid grid(int product, int overview, double x, double y) {
        if (product != FINE && product != COARSE) throw new IllegalArgumentException("Unknown REMA product " + product);
        if (overview < 0 || overview > MAX_OVERVIEW[product]) throw new IllegalArgumentException("Unsupported REMA overview " + overview);
        if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
        int column = (int) Math.floor((x - ORIGIN) / SQUARE), row = (int) Math.floor((y - ORIGIN) / SQUARE);
        if (column < 1 || row < 1 || column > 61 || row > 61) return null;
        int east = 0, north = 0;
        if (product == FINE) {
            east = x - ORIGIN - column * SQUARE >= SQUARE / 2 ? 1 : 0;
            north = y - ORIGIN - row * SQUARE >= SQUARE / 2 ? 1 : 0;
        }
        return files.grid(fileKey(product, row, column, north, east), overview, x, y);
    }

    private static int fileKey(int product, int row, int column, int north, int east) {
        return ((product * 64 + row) * 64 + column) * 4 + north * 2 + east;
    }

    static String name(int file) {
        int half = file & 3, column = (file >> 2) & 63, row = (file >> 8) & 63, product = file >> 14;
        String square = String.format(Locale.ROOT, "%02d_%02d", row, column);
        return product == FINE
                ? "2m/" + square + "/" + square + "_" + ((half >> 1) + 1) + "_" + ((half & 1) + 1) + "_2m_v2.0_dem.tif"
                : "32m/" + square + "/" + square + "_32m_v2.0_dem.tif";
    }

    public Stats stats() {
        FloatCogs.Stats stats = files.stats();
        return new Stats(stats.downloads(), stats.diskHits(), stats.failures(), stats.residentBlocks());
    }

    public record Stats(long downloads, long diskHits, long failures, int residentBlocks) {}

    @Override public void close() { files.close(); }
}
