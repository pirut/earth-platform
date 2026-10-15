package io.earthplatform.geography.globe;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * France from IGN's RGE ALTI, laid over the Terrarium composite, dataset id {@value #DATASET_ID}.
 * The composite carries the 25 m EU-DEM there; RGE ALTI is a 1 to 5 m bare-earth model of
 * metropolitan France, Corsica and the overseas departments, from lidar in the mountains, the
 * forests and along the coasts and rivers, in heights above mean sea level (NGF-IGN69 and the
 * overseas datums).
 *
 * <p>Read from IGN's Geoplateforme raster map service as 32-bit floats. The service keeps it on
 * latitude and longitude grids of 180 / 2^level degrees a pixel, finest at level {@value #FINEST}
 * (about 5 m north to south), and answers other grids by taking the nearest pixel, which on our Web
 * Mercator rows repeats or skips a row every few and shows as stripes across every slope. So each
 * tile asks for a block of the service's own grid, at the level as fine as the tile's columns,
 * and samples it bilinearly itself. Only tiles of zoom 12 and finer read it; far terrain keeps the
 * composite. Pixels with no data (the sea, past the border) keep the composite.
 */
public final class IgnElevation extends MercatorTileLayer {
    public static final String DATASET_ID = "ign-rgealti-v1";
    public static final String ATTRIBUTION = "France: IGN RGE ALTI (Licence Ouverte / Etalab Open Licence 2.0)";
    public static final URI IGN_WMS = URI.create("https://data.geopf.fr/wms-r/wms");
    static final String LAYER = "ELEVATION.ELEVATIONGRIDCOVERAGE.HIGHRES";
    public static final int MIN_ZOOM = 12;
    /** IGN's no-data value; anything this low is no data. */
    private static final float NO_DATA_BELOW = -1000;
    /** The service's finest grid: asked for finer, it repeats pixels. */
    static final int FINEST = 22;
    private static final TileCoverage COVERAGE = TileCoverage.load("ign-rgealti-v1.txt");

    private final URI service;

    /**
     * @param service          the map service's URI, without a query
     * @param cacheRoot        optional disk cache directory, or null for memory only
     * @param allowNetwork     false reads only the disk cache
     * @param maxTilesInMemory decoded source tiles kept (256 KiB each)
     */
    public IgnElevation(URI service, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        super(DATASET_ID, "IGN elevation", "earth-ign-io", COVERAGE, MIN_ZOOM, cacheRoot, allowNetwork, maxTilesInMemory, maxConcurrentTransfers, timeout);
        this.service = service;
    }

    @Override List<Piece> pieces(int zoom, int x, int y) {
        Block block = block(zoom, x, y);
        double r = block.degrees();
        String box = String.format(Locale.ROOT, "%.12f,%.12f,%.12f,%.12f",
                block.south() * r, block.west() * r, (block.south() + block.rows()) * r, (block.west() + block.columns()) * r);
        URI uri = URI.create(service + "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=" + LAYER + "&STYLES=&CRS=EPSG:4326&BBOX=" + box
                + "&WIDTH=" + block.columns() + "&HEIGHT=" + block.rows() + "&FORMAT=image/x-bil;bits=32");
        return List.of(new Piece("rgealti-grid", zoom, x, y, uri));
    }

    /**
     * The service's pixels a tile reads: its level, and the first column and row (from the west and the
     * south, in pixels of that level from 0° and 0°) and the count of each, one more each side than the
     * tile spans so every pixel centre has four around it.
     */
    record Block(int level, long west, long south, int columns, int rows) {
        double degrees() { return 180.0 / (1L << level); }
    }

    static Block block(int zoom, int x, int y) {
        double world = (double) SIZE * (1L << zoom);
        double north = latitude(world, (double) y * SIZE), south = latitude(world, (double) (y + 1) * SIZE);
        double west = (double) x * SIZE / world * 360 - 180, east = (double) (x + 1) * SIZE / world * 360 - 180;
        // Pixels as wide as the tile's: its rows are then 1 / cos(latitude) apart, coarser than the tile's,
        // but the next level finer is four times the download, which more than doubled a first visit's.
        int level = Math.min(FINEST, zoom + 7);
        double r = 180.0 / (1L << level);
        long w = (long) Math.floor(west / r) - 1, s = (long) Math.floor(south / r) - 1;
        long e = (long) Math.ceil(east / r) + 1, n = (long) Math.ceil(north / r) + 1;
        return new Block(level, w, s, (int) (e - w), (int) (n - s));
    }

    /** Raw floats are large and compress well; they are kept on disk gzipped. */
    @Override boolean gzip() { return true; }

    @Override float[] decode(Piece piece, byte[] bytes) throws IOException { return decode(block(piece.zoom(), piece.x(), piece.y()), piece.zoom(), piece.x(), piece.y(), bytes); }

    /**
     * The service's BIL, the block's rows from the north of little-endian floats, sampled bilinearly at
     * each of the tile's pixel centres over the pixels around it with data.
     */
    static float[] decode(Block block, int zoom, int x, int y, byte[] bil) throws IOException {
        int columns = block.columns(), rows = block.rows();
        if (bil.length != columns * rows * 4)
            throw new IOException("IGN elevation answered " + bil.length + " bytes, not " + columns + " by " + rows + " floats: "
                    + new String(bil, 0, Math.min(200, bil.length), java.nio.charset.StandardCharsets.UTF_8));
        float[] read = new float[columns * rows];
        ByteBuffer.wrap(bil).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(read);
        double world = (double) SIZE * (1L << zoom), r = block.degrees();
        float[] metres = new float[SIZE * SIZE];
        for (int j = 0; j < SIZE; j++) {
            double lat = latitude(world, (double) y * SIZE + j + 0.5);
            // Rows from the block's north edge, to pixel centres.
            double fy = (block.south() + rows) - lat / r - 0.5;
            int r0 = (int) Math.floor(fy);
            double ty = fy - r0;
            for (int i = 0; i < SIZE; i++) {
                double lon = ((double) x * SIZE + i + 0.5) / world * 360 - 180;
                double fx = lon / r - block.west() - 0.5;
                int c0 = (int) Math.floor(fx);
                double tx = fx - c0, sum = 0, weights = 0;
                for (int dr = 0; dr < 2; dr++) for (int dc = 0; dc < 2; dc++) {
                    int row = Math.clamp(r0 + dr, 0, rows - 1), column = Math.clamp(c0 + dc, 0, columns - 1);
                    float v = read[row * columns + column];
                    if (!(v > NO_DATA_BELOW)) continue;
                    double w = (dr == 0 ? 1 - ty : ty) * (dc == 0 ? 1 - tx : tx);
                    sum += w * v;
                    weights += w;
                }
                metres[j * SIZE + i] = weights > 0.5 ? (float) (sum / weights) : Float.NaN;
            }
        }
        return metres;
    }
}
