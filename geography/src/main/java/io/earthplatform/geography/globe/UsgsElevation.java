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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The United States from the USGS 3D Elevation Program's 1/3 arc-second (about 10 m) DEM, laid over
 * the Terrarium composite, dataset id {@value #DATASET_ID}. The composite carries an older release
 * of the same model, and in places it is badly wrong: above Niagara Falls it puts the upper river at
 * Lake Ontario's level, 90 m below its real surface. 3DEP is bare-earth (lidar where flown) in
 * NAVD 88 heights, the composite's own datum there.
 *
 * <p>The model comes in 1° cells, one cloud-optimised GeoTIFF each on the USGS National Map's AWS
 * bucket, read with {@link FloatCogs}. Each cell is pinned to its newest dated release as of this
 * dataset ({@code 3dep-13-v1.txt}), so a world reads the same bytes even after the USGS republishes a
 * cell; cells outside the list (outside the United States) are never asked for. Each tile pixel
 * takes 3DEP read at about its own size. Where 3DEP has no data, over the sea and past the border,
 * the tile keeps its own values, blended over a few of 3DEP's pixels. Open sea (exactly 0) is left
 * as it is, and so is the composite's bathymetry where 3DEP reads sea level or below.
 */
public final class UsgsElevation implements ElevationLayer, AutoCloseable {
    public static final String DATASET_ID = "3dep-13-v1";
    public static final String ATTRIBUTION = "United States: USGS 3D Elevation Program 1/3 arc-second DEM (public domain)";
    public static final URI AWS_3DEP = URI.create("https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/historical/");
    /** Coarser tiles cover so much ground that they are left to the composite. */
    public static final int MIN_ZOOM = 8;
    /** Degrees per pixel at full resolution. */
    private static final double PIXEL = 1.0 / 10800;
    private static final int MAX_OVERVIEW = 5;
    /** Release date of each 1° cell, by {@link #cell}. */
    private static final Map<Integer, String> RELEASES = releases();

    private final FloatCogs files;

    /**
     * @param base              URI prefix the dated cells are named under
     * @param cacheRoot         optional disk cache directory, or null for memory only
     * @param allowNetwork      false reads only the disk cache
     * @param maxBlocksInMemory decoded blocks kept (up to 1 MiB each)
     */
    public UsgsElevation(URI base, Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        files = new FloatCogs("USGS 3DEP elevation", DATASET_ID, "earth-3dep-io", base, UsgsElevation::name, -999999f,
                cacheRoot, allowNetwork, maxBlocksInMemory, maxConcurrentTransfers, timeout);
    }

    @Override public String id() { return DATASET_ID; }

    @Override public void refine(int zoom, int x, int y, float[] values) {
        if (zoom < MIN_ZOOM) return;
        int size = TerrariumTileSource.TILE_SIZE;
        double world = (double) size * (1L << zoom);
        double north = latitude(world, (double) y * size), south = latitude(world, (double) (y + 1) * size);
        double west = (double) x * size / world * 360 - 180, east = (double) (x + 1) * size / world * 360 - 180;
        if (!covers(north, south, west, east)) return;
        // Read 3DEP at about the tile's pixel size, measured north-south at its centre.
        double centre = latitude(world, (y + 0.5) * size);
        double pixel = 360 / world * Math.cos(Math.toRadians(centre));
        int overview = Math.max(0, Math.min(MAX_OVERVIEW, (int) Math.floor(Math.log(pixel / PIXEL) / Math.log(2))));
        int edge = Math.min(MAX_OVERVIEW, overview + 2);
        for (int j = 0; j < size; j++) {
            double lat = latitude(world, (double) y * size + j + 0.5);
            for (int i = 0; i < size; i++) {
                int at = j * size + i;
                float own = values[at];
                if (own == 0) continue;
                double lon = ((double) x * size + i + 0.5) / world * 360 - 180;
                int cell = cell(lat, lon);
                if (!RELEASES.containsKey(cell)) continue;
                FloatCogs.Grid around = files.grid(cell, edge, lon, lat);
                if (around == null) continue;
                double share = files.coverage(around, lon, lat);
                if (share <= 0.5) continue;
                double metres = Double.NaN;
                // A gap at the finer overview takes the coarser data around it.
                for (int level = overview; Double.isNaN(metres) && level <= MAX_OVERVIEW; level++) {
                    FloatCogs.Grid grid = files.grid(cell, level, lon, lat);
                    if (grid != null) metres = files.metres(grid, lon, lat);
                }
                if (Double.isNaN(metres)) continue;
                // 3DEP has no bathymetry; the composite's stays.
                if (metres <= 0 && own < 0) continue;
                double s = Math.min(1, (share - 0.5) * 2), weight = s * s * (3 - 2 * s);
                float out = (float) (own + weight * (metres - own));
                // Exactly 0 means open sea to the generator.
                values[at] = out == 0 ? Float.MIN_VALUE : out;
            }
        }
    }

    /** Whether any listed cell meets the box. */
    private static boolean covers(double north, double south, double west, double east) {
        for (int top = (int) Math.ceil(south); top <= (int) Math.ceil(north); top++)
            for (int left = (int) Math.floor(west); left <= (int) Math.floor(east); left++)
                if (RELEASES.containsKey(key(top, Math.floorMod(left + 180, 360) - 180))) return true;
        return false;
    }

    /** The 1° cell holding a point: north edge {@code ceil(lat)}, west edge {@code floor(lon)}. */
    static int cell(double latitude, double longitude) {
        return key((int) Math.ceil(latitude), Math.floorMod((int) Math.floor(longitude) + 180, 360) - 180);
    }

    private static int key(int top, int left) { return (top + 90) * 360 + left + 180; }

    /** A cell's name in the USGS tiling, as {@code n44w080}. */
    static String cellName(int cell) {
        int top = cell / 360 - 90, left = cell % 360 - 180;
        return String.format(Locale.ROOT, "%s%02d%s%03d", top >= 0 ? "n" : "s", Math.abs(top), left >= 0 ? "e" : "w", Math.abs(left));
    }

    static String name(int cell) {
        String name = cellName(cell), date = RELEASES.get(cell);
        if (date == null) throw new IllegalArgumentException("No 3DEP release for " + name);
        return name + "/USGS_13_" + name + "_" + date + ".tif";
    }

    /** Latitude in degrees of a Web Mercator pixel row. */
    private static double latitude(double world, double row) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * row / world))));
    }

    private static Map<Integer, String> releases() {
        Map<Integer, String> releases = new HashMap<>();
        try (InputStream raw = UsgsElevation.class.getResourceAsStream("3dep-13-v1.txt")) {
            if (raw == null) throw new IOException("Missing 3dep-13-v1.txt");
            BufferedReader in = new BufferedReader(new InputStreamReader(raw, StandardCharsets.US_ASCII));
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank()) continue;
                String[] parts = line.trim().split(" ");
                String name = parts[0];
                int top = Integer.parseInt(name.substring(1, 3)) * (name.charAt(0) == 'n' ? 1 : -1);
                int left = Integer.parseInt(name.substring(4, 7)) * (name.charAt(3) == 'e' ? 1 : -1);
                releases.put(key(top, left), parts[1]);
            }
        } catch (IOException | RuntimeException failure) {
            throw new UncheckedIOException("Could not read the 3DEP release list", failure instanceof IOException io ? io : new IOException(failure));
        }
        return Map.copyOf(releases);
    }

    @Override public void close() { files.close(); }
}
