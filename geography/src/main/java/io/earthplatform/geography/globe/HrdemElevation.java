package io.earthplatform.geography.globe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Canada's lidar from Natural Resources Canada's High Resolution Digital Elevation Model (HRDEM)
 * 2 m mosaic, laid over the Terrarium composite, dataset id {@value #DATASET_ID}. The composite
 * carries the older Canadian DEM there, about 20 m and coarser in the north; the HRDEM terrain model
 * is bare earth from airborne lidar, flown over most of the populated south, the Rockies' valleys,
 * and much of British Columbia, Quebec and the Maritimes, in heights above mean sea level (CGVD2013).
 *
 * <p>It comes as 56 cloud-optimised GeoTIFFs of 500 km squares on Canada Atlas Lambert (EPSG:3979)
 * from NRCan's AWS bucket, read with {@link FloatCogs}. Their full 2 m level is never read: its block
 * table alone is megabytes a file, and the ground a player stands on, in tiles of zoom 15, has
 * pixels of about 3 m there. To keep first visits quick, tiles read one level coarser than
 * their pixels: zoom 15 reads the 8 m level and zoom 14 the 16 m. Coarser tiles keep the composite: at their pixel size the lidar adds little to it, and
 * its blocks, about 1 MB each, would more than double a first visit's download. Where
 * there is no lidar the tile keeps its own values, blended over a few pixels.
 */
public final class HrdemElevation implements ElevationLayer, AutoCloseable {
    public static final String DATASET_ID = "hrdem-2m-v1";
    public static final String ATTRIBUTION = "Canada: High Resolution Digital Elevation Model (HRDEM), Natural Resources Canada; "
            + "contains information licensed under the Open Government Licence - Canada";
    public static final URI AWS_HRDEM = URI.create("https://canelevation-dem.s3.ca-central-1.amazonaws.com/hrdem-mosaic-2m/");
    public static final int MIN_ZOOM = 14;
    /** Metres per pixel of the full level, which is never read. */
    private static final double RESOLUTION = 2;
    private static final int SKIPPED = 1, MAX_OVERVIEW = 8;
    /**
     * Levels coarser than the tile's pixels that a tile reads. The lidar's blocks barely compress, about
     * 1 MB each however fine the level: read at its pixel size, a first visit downloaded four times the
     * composite's tiles; one level coarser, a quarter of that.
     */
    private static final int COARSER = 1;
    /** No lidar reaches this far south or north. */
    private static final double SOUTH = 41, NORTH = 84;
    private static final List<Square> SQUARES = squares();

    /** One file: its name and the EPSG:3979 metres it spans. */
    private record Square(String name, double west, double north, double width, double height) {
        boolean holds(double x, double y) { return x >= west && x < west + width && y <= north && y > north - height; }
    }

    // EPSG:3979, Lambert conformal conic on GRS 80: standard parallels 49° and 77° N, origin 49° N, 95° W.
    private static final double A = 6_378_137.0, E = Math.sqrt(2 / 298.257222101 - 1 / (298.257222101 * 298.257222101));
    private static final double N, AF, RHO0, LAMBDA0 = Math.toRadians(-95);
    static {
        double phi1 = Math.toRadians(49), phi2 = Math.toRadians(77);
        double m1 = m(phi1), m2 = m(phi2), t1 = t(phi1), t2 = t(phi2);
        N = (Math.log(m1) - Math.log(m2)) / (Math.log(t1) - Math.log(t2));
        AF = A * m1 / (N * Math.pow(t1, N));
        RHO0 = AF * Math.pow(t(phi1), N);
    }

    private final FloatCogs files;

    /**
     * @param base              URI prefix the files are named under
     * @param cacheRoot         optional disk cache directory, or null for memory only
     * @param allowNetwork      false reads only the disk cache
     * @param maxBlocksInMemory decoded blocks kept (1 MiB each)
     */
    public HrdemElevation(URI base, Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        files = new FloatCogs("HRDEM elevation", DATASET_ID, "earth-hrdem-io", base, file -> SQUARES.get(file).name + "-mosaic-2m-dtm.tif", -32767f,
                cacheRoot, allowNetwork, maxBlocksInMemory, maxConcurrentTransfers, timeout, SKIPPED);
    }

    @Override public String id() { return DATASET_ID; }

    @Override public void refine(int zoom, int x, int y, float[] values) {
        if (zoom < MIN_ZOOM) return;
        int size = TerrariumTileSource.TILE_SIZE;
        double world = (double) size * (1L << zoom);
        double north = MercatorTileLayer.latitude(world, (double) y * size), south = MercatorTileLayer.latitude(world, (double) (y + 1) * size);
        double west = (double) x * size / world * 360 - 180, east = (double) (x + 1) * size / world * 360 - 180;
        if (south > NORTH || north < SOUTH || east < -142 || west > -52) return;
        int pixels = size * size;
        double[] px = new double[pixels], py = new double[pixels];
        int[] squares = new int[pixels];
        List<Integer> touched = new ArrayList<>();
        int last = -1;
        double[] xy = new double[2];
        for (int j = 0; j < size; j++) {
            double lat = MercatorTileLayer.latitude(world, (double) y * size + j + 0.5);
            for (int i = 0; i < size; i++) {
                int at = j * size + i;
                project(lat, ((double) x * size + i + 0.5) / world * 360 - 180, xy);
                px[at] = xy[0];
                py[at] = xy[1];
                if (last < 0 || !SQUARES.get(last).holds(xy[0], xy[1])) {
                    last = -1;
                    for (int s = 0; s < SQUARES.size(); s++) if (SQUARES.get(s).holds(xy[0], xy[1])) { last = s; break; }
                    if (last >= 0 && !touched.contains(last)) touched.add(last);
                }
                squares[at] = last;
            }
        }
        if (touched.isEmpty()) return;
        // Read the levels at about the tile's pixel size, measured north-south at its centre, rounded to the coarser.
        double centre = MercatorTileLayer.latitude(world, (y + 0.5) * size);
        double pixel = 40_075_016.685578488 / world * Math.cos(Math.toRadians(centre));
        int overview = Math.max(SKIPPED, Math.min(MAX_OVERVIEW, (int) Math.ceil(Math.log(pixel / RESOLUTION) / Math.log(2) - 1e-9) + COARSER));
        int edge = Math.min(MAX_OVERVIEW, overview + 2);
        // As UsgsElevation reads 3DEP: the files' headers, then the coarse level that gives each pixel's
        // share of data, then the level read where that share is enough, each pass loaded all at once.
        files.headers(touched);
        FloatCogs.Reader read = files.reader();
        FloatCogs.Grid[] grids = new FloatCogs.Grid[pixels];
        for (int at = 0; at < pixels; at++) {
            if (squares[at] < 0) continue;
            FloatCogs.Grid around = read.grid(squares[at], edge, px[at], py[at]);
            if (around == null) continue;
            grids[at] = around;
            read.wantCoverage(around, px[at], py[at]);
        }
        read.load();
        double[] shares = new double[pixels];
        for (int at = 0; at < pixels; at++) {
            if (grids[at] == null) continue;
            double share = read.coverage(grids[at], px[at], py[at]);
            shares[at] = share;
            grids[at] = null;
            if (share <= 0.5) continue;
            FloatCogs.Grid grid = read.grid(squares[at], overview, px[at], py[at]);
            grids[at] = grid;
            if (grid != null) read.wantMetres(grid, px[at], py[at]);
        }
        read.load();
        for (int at = 0; at < pixels; at++) {
            double share = shares[at];
            if (share <= 0.5) continue;
            float own = values[at];
            double metres = grids[at] == null ? Double.NaN : read.metres(grids[at], px[at], py[at]);
            // A gap at the finer level takes the coarser data around it.
            for (int level = overview + 1; Double.isNaN(metres) && level <= MAX_OVERVIEW; level++) {
                FloatCogs.Grid grid = read.grid(squares[at], level, px[at], py[at]);
                if (grid != null) metres = read.metres(grid, px[at], py[at]);
            }
            if (Double.isNaN(metres)) continue;
            // Lidar has no bathymetry; the composite's stays.
            if (metres <= 0 && own < 0) continue;
            // Exactly 0 is open sea to the generator, as in UsgsElevation.
            if (own == 0 && (share < 1 - 1e-9 || metres < MercatorTileLayer.VOID_ABOVE_SEA)) continue;
            double s = Math.min(1, (share - 0.5) * 2), weight = s * s * (3 - 2 * s);
            float out = (float) (own + weight * (metres - own));
            values[at] = out == 0 ? Float.MIN_VALUE : out;
        }
    }

    /** Canada Atlas Lambert (EPSG:3979) metres of a latitude and longitude, into {@code out} as {x, y}. */
    static void project(double latitude, double longitude, double[] out) {
        double rho = AF * Math.pow(t(Math.toRadians(latitude)), N), theta = N * (Math.toRadians(longitude) - LAMBDA0);
        out[0] = rho * Math.sin(theta);
        out[1] = RHO0 - rho * Math.cos(theta);
    }

    private static double m(double phi) { double s = E * Math.sin(phi); return Math.cos(phi) / Math.sqrt(1 - s * s); }

    private static double t(double phi) {
        double s = E * Math.sin(phi);
        return Math.tan(Math.PI / 4 - phi / 2) / Math.pow((1 - s) / (1 + s), E / 2);
    }

    private static List<Square> squares() {
        List<Square> squares = new ArrayList<>();
        try (InputStream raw = HrdemElevation.class.getResourceAsStream("hrdem-2m-v1.txt")) {
            if (raw == null) throw new IOException("Missing hrdem-2m-v1.txt");
            BufferedReader in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.US_ASCII));
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split(" ");
                squares.add(new Square(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]), Double.parseDouble(parts[4])));
            }
        } catch (IOException | RuntimeException failure) {
            throw new UncheckedIOException("Could not read the HRDEM file list", failure instanceof IOException io ? io : new IOException(failure));
        }
        return List.copyOf(squares);
    }

    @Override public void close() { files.close(); }
}
