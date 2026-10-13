package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Takes the Copernicus DEM where the Terrarium tiles are wrong over a wide area. Two kinds of
 * fault are common there, and neither shows inside the tiles alone:
 *
 * <ul>
 *   <li>Water filled in as land. ArcticDEM and some survey patches carry no water surface, so
 *       fjords and sounds read as a smooth sheet far above the sea: 86 m over the fjord at
 *       Ísafjörður, 230 to 250 m in Milford Sound. The generator cuts the water down to the sea
 *       and leaves walls hundreds of blocks high round it.</li>
 *   <li>Heights above the ellipsoid instead of the sea. North of SRTM the composite takes
 *       ArcticDEM, whose heights stand 60 to 70 m too high in Iceland and the Faroes, 60 m in
 *       eastern Greenland, 30 m on Svalbard and 20 to 25 m in Lapland and Kola, so every coast
 *       there ends in a cliff.</li>
 * </ul>
 *
 * <p>Each degree square is checked once: Terrarium at zoom {@value #CHECK_ZOOM} against Copernicus
 * at overview {@value #CHECK_OVERVIEW} (about 120 m), at Copernicus's pixels. Their difference,
 * averaged over the {@code 2 * SMOOTH + 1} pixels square round each, gives a weight from 0 where
 * they agree within {@value #AGREE} m to 1 beyond {@value #DISAGREE} m. Survey noise, forests and
 * the two datasets' different pixels average out; a fill or a datum offset does not. Open sea in
 * Terrarium counts as agreeing when Copernicus has sea too, and is never weighed against
 * Copernicus land, so no land is made out of sea. A tile then moves each pixel that far toward
 * Copernicus, read at the overview nearest its own pixel size. The weights are one smooth field
 * whatever the zoom, so near and far terrain agree, and where they are 0, nearly everywhere, the
 * tiles are left exactly as they were. Copernicus's sea, exactly 0 m, becomes the tiles' open sea
 * where the weight is whole.
 */
final class CopernicusFill {
    /** Coarsest zoom filled. Coarser zooms carry the bathymetry and stay as they are. */
    static final int MIN_ZOOM = 11;
    /**
     * Terrarium zoom the check reads, as it was before filling. Zoom 10 and coarser come from
     * other sources, which hold heights above the sea even where the finer zooms do not.
     */
    static final int CHECK_ZOOM = 11;
    static final int CHECK_OVERVIEW = 2;
    /** Check pixels either side whose difference is averaged. */
    static final int SMOOTH = 2;
    /** Average difference in metres up to which the tiles stand, and from which Copernicus does. */
    static double AGREE = 10, DISAGREE = 25;
    /** Share of Copernicus's relief in the narrow window added to both. */
    static double RELIEF_SHARE = 0.25;
    /** Check pixels either side whose difference is averaged for an offset of the whole ground. */
    static int WIDE = 20;
    /** Wide average difference in metres up to which the tiles stand, and from which Copernicus does. */
    static double OFFSET_AGREE = 8, OFFSET_DISAGREE = 16;
    /** Full-resolution Copernicus pixel height in metres. */
    private static final double COPERNICUS_METRES = 40_007_863.0 / 360 / CopernicusSource.ROWS_PER_DEGREE;
    /** Copernicus metres this close to 0 are sea. */
    private static final double SEA_CRUMB = 0.01;
    private static final double EARTH_CIRCUMFERENCE = 40_075_016.7;

    interface Tiles { float[] tile(int zoom, int x, int y); }

    /** A degree square's weights at the check overview, 0 to 255, row-major from the north; null where all are 0. */
    record Weights(int width, int height, byte[] weights) {
        static final Weights NONE = new Weights(0, 0, null);
    }

    private final CopernicusSource dem;
    private final Tiles tiles;
    private final int maxSquares;
    private final LinkedHashMap<Integer, Weights> squares = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<Integer, CompletableFuture<Weights>> inFlight = new ConcurrentHashMap<>();

    CopernicusFill(CopernicusSource dem, Tiles tiles, int maxSquares) {
        this.dem = dem;
        this.tiles = tiles;
        this.maxSquares = maxSquares;
    }

    /** Moves a tile's pixels toward Copernicus by the weight where they stand. */
    void apply(float[] values, int zoom, int tileX, int tileY) {
        if (zoom < MIN_ZOOM) return;
        long size = (long) TerrariumTileSource.TILE_SIZE << zoom;
        double north = latitude(tileY * 256L, size), south = latitude((tileY + 1) * 256L, size);
        double west = tileX * 360.0 / (1 << zoom) - 180, east = (tileX + 1) * 360.0 / (1 << zoom) - 180;
        // Squares the tile and its weights' reach touch: a weight near a square's edge reads the next square.
        double margin = 0.02;
        boolean any = false;
        for (int s = (int) Math.floor(Math.max(-90, south - margin)); s <= Math.min(89, (int) Math.floor(north + margin)); s++)
            for (int w = (int) Math.floor(west - margin); w <= (int) Math.floor(east + margin); w++)
                any |= weights(s, wrapWest(w)).weights != null;
        if (!any) return;
        double mid = Math.toRadians((north + south) / 2);
        double pixel = EARTH_CIRCUMFERENCE * Math.cos(mid) / size;
        int overview = (int) Math.max(0, Math.min(CopernicusSource.MAX_OVERVIEW, Math.floor(Math.log(pixel / COPERNICUS_METRES) / Math.log(2))));
        double[] longitude = new double[TerrariumTileSource.TILE_SIZE];
        for (int px = 0; px < longitude.length; px++) longitude[px] = (tileX * 256L + px + 0.5) * 360.0 / size - 180;
        for (int py = 0; py < TerrariumTileSource.TILE_SIZE; py++) {
            double latitude = latitude(tileY * 256L + py + 0.5, size);
            for (int px = 0; px < TerrariumTileSource.TILE_SIZE; px++) {
                int i = py * TerrariumTileSource.TILE_SIZE + px;
                float t = values[i];
                if (t == 0f) continue;
                double weight = weight(latitude, longitude[px]);
                if (weight <= 0) continue;
                double c = dem.metres(latitude, longitude[px], overview);
                if (Double.isNaN(c)) continue;
                // Overviews average the sea's zeros into crumbs of a picometre.
                if (Math.abs(c) < SEA_CRUMB) c = 0;
                float v = (float) (t + weight * (c - t));
                if (v == 0f && !(c == 0 && weight >= 1)) v = Math.ulp(0f);
                values[i] = v;
            }
        }
    }

    /** The weight at a point, bilinear between check pixels, reaching into the squares around at the edges. */
    double weight(double latitude, double longitude) {
        longitude = CopernicusSource.wrap(longitude);
        int south = Math.min(89, (int) Math.floor(latitude)), west = (int) Math.floor(longitude);
        Weights here = weights(south, west);
        if (here.width == 0) return 0;
        double fy = (south + 1 - latitude) * here.height - 0.5, fx = (longitude - west) * here.width - 0.5;
        int y0 = (int) Math.floor(fy), x0 = (int) Math.floor(fx);
        double ty = fy - y0, tx = fx - x0;
        double v00 = at(here, south, west, y0, x0), v01 = at(here, south, west, y0, x0 + 1);
        double v10 = at(here, south, west, y0 + 1, x0), v11 = at(here, south, west, y0 + 1, x0 + 1);
        return ((v00 * (1 - tx) + v01 * tx) * (1 - ty) + (v10 * (1 - tx) + v11 * tx) * ty) / 255;
    }

    /** A check pixel by row and column, either of which may lie past the square's edge: then the nearest pixel there. */
    private double at(Weights here, int south, int west, int row, int column) {
        if (row < 0 || column < 0 || row >= here.height || column >= here.width) {
            double latitude = south + 1 - (row + 0.5) / here.height, longitude = CopernicusSource.wrap(west + (column + 0.5) / here.width);
            if (latitude >= 90 || latitude <= -90) {
                row = Math.max(0, Math.min(here.height - 1, row));
                column = Math.max(0, Math.min(here.width - 1, column));
            } else {
                south = Math.min(89, (int) Math.floor(latitude));
                west = (int) Math.floor(longitude);
                here = weights(south, west);
                if (here.width == 0) return 0;
                row = (int) Math.max(0, Math.min(here.height - 1, Math.floor((south + 1 - latitude) * here.height)));
                column = (int) Math.max(0, Math.min(here.width - 1, Math.floor((longitude - west) * here.width)));
            }
        }
        return here.weights == null ? 0 : here.weights[row * here.width + column] & 0xFF;
    }

    Weights weights(int south, int west) {
        int key = (south + 90) * 1000 + west + 180;
        synchronized (squares) {
            Weights hit = squares.get(key);
            if (hit != null) return hit;
        }
        CompletableFuture<Weights> mine = new CompletableFuture<>(), shared = inFlight.putIfAbsent(key, mine);
        if (shared == null) {
            try {
                Weights computed = check(south, west);
                synchronized (squares) {
                    squares.put(key, computed);
                    var iterator = squares.entrySet().iterator();
                    while (squares.size() > maxSquares && iterator.hasNext()) { iterator.next(); iterator.remove(); }
                }
                mine.complete(computed);
            } catch (Throwable failure) {
                mine.completeExceptionally(failure);
            } finally {
                inFlight.remove(key, mine);
            }
            shared = mine;
        }
        try {
            return shared.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MissingTerrainException("Interrupted waiting for the elevation check of " + CopernicusSource.name(south, west));
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof MissingTerrainException missing) throw missing;
            throw new MissingTerrainException("Elevation check of " + CopernicusSource.name(south, west) + " failed: " + failed.getCause());
        }
    }

    /** Checks one degree square: see the class notes. */
    private Weights check(int south, int west) {
        int[] dims = dem.size(south + 0.5, west + 0.5, CHECK_OVERVIEW);
        if (dims == null) return Weights.NONE;
        int width = dims[0], height = dims[1], m = WIDE, across = width + 2 * m, down = height + 2 * m;
        // The difference and Copernicus over the square and a margin round it, NaN where unknown.
        float[] difference = new float[across * down], copernicus = new float[across * down];
        Terrarium terrarium = new Terrarium();
        for (int r = 0; r < down; r++) {
            double latitude = south + 1 - (r - m + 0.5) / height;
            for (int c = 0; c < across; c++) {
                int i = r * across + c;
                difference[i] = copernicus[i] = Float.NaN;
                if (Math.abs(latitude) >= 90) continue;
                double longitude = west + (c - m + 0.5) / width;
                double cop = dem.metres(latitude, longitude, CHECK_OVERVIEW);
                if (Double.isNaN(cop)) continue;
                copernicus[i] = (float) cop;
                double t = terrarium.metres(latitude, longitude);
                if (t > 0) difference[i] = (float) (t - cop);
                else if (cop < SEA_CRUMB) difference[i] = 0;
            }
        }
        Window wide = new Window(difference, across, down), narrow = wide;
        byte[] weights = new byte[width * height];
        boolean any = false;
        for (int r = 0; r < height; r++) for (int c = 0; c < width; c++) {
            int pr = r + m, pc = c + m;
            double offset = wide.mean(pr - WIDE, pc - WIDE, pr + WIDE, pc + WIDE);
            double weight = ramp(offset, OFFSET_AGREE, OFFSET_DISAGREE);
            if (weight < 1) {
                double fill = narrow.mean(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH);
                if (fill > AGREE) {
                    float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
                    for (int j = pr - SMOOTH; j <= pr + SMOOTH; j++) for (int k = pc - SMOOTH; k <= pc + SMOOTH; k++) {
                        float v = copernicus[j * across + k];
                        if (v == v) { low = Math.min(low, v); high = Math.max(high, v); }
                    }
                    double allow = high >= low ? RELIEF_SHARE * (high - low) : 0;
                    weight = Math.max(weight, ramp(fill, AGREE + allow, DISAGREE + allow));
                }
            }
            int quantised = (int) Math.round(weight * 255);
            weights[r * width + c] = (byte) quantised;
            any |= quantised > 0;
        }
        return new Weights(width, height, any ? weights : null);
    }

    /** 0 up to {@code from}, 1 from {@code to}, smooth between. */
    private static double ramp(double value, double from, double to) {
        double t = Math.max(0, Math.min(1, (value - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }

    /** Means of the known values over rectangles of a grid, from summed-area tables. */
    private static final class Window {
        private final int across;
        private final double[] sum;
        private final int[] count;

        Window(float[] values, int across, int down) {
            this.across = across + 1;
            sum = new double[(across + 1) * (down + 1)];
            count = new int[(across + 1) * (down + 1)];
            for (int r = 0; r < down; r++) {
                double rowSum = 0;
                int rowCount = 0;
                for (int c = 0; c < across; c++) {
                    float v = values[r * across + c];
                    if (v == v) { rowSum += v; rowCount++; }
                    int at = (r + 1) * this.across + c + 1;
                    sum[at] = sum[at - this.across] + rowSum;
                    count[at] = count[at - this.across] + rowCount;
                }
            }
        }

        /** Mean over rows {@code r0..r1} and columns {@code c0..c1} inclusive; 0 where none is known. */
        double mean(int r0, int c0, int r1, int c1) {
            int a = r0 * across + c0, b = r0 * across + c1 + 1, d = (r1 + 1) * across + c0, e = (r1 + 1) * across + c1 + 1;
            int n = count[e] - count[b] - count[d] + count[a];
            return n == 0 ? 0 : (sum[e] - sum[b] - sum[d] + sum[a]) / n;
        }
    }

    /** Bilinear Terrarium metres at the check zoom, remembering the tiles read. */
    private final class Terrarium {
        private final Map<Long, float[]> read = new java.util.HashMap<>();
        private long key = -1;
        private float[] tile;

        double metres(double latitude, double longitude) {
            long size = (long) TerrariumTileSource.TILE_SIZE << CHECK_ZOOM;
            double phi = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, latitude)));
            double px = (CopernicusSource.wrap(longitude) + 180) / 360 * size - 0.5;
            double py = (1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2 * size - 0.5;
            long x0 = (long) Math.floor(px), y0 = (long) Math.floor(py);
            double tx = px - x0, ty = py - y0;
            return (pixel(x0, y0, size) * (1 - tx) + pixel(x0 + 1, y0, size) * tx) * (1 - ty)
                    + (pixel(x0, y0 + 1, size) * (1 - tx) + pixel(x0 + 1, y0 + 1, size) * tx) * ty;
        }

        private float pixel(long gx, long gy, long size) {
            gx = Math.floorMod(gx, size);
            gy = Math.max(0, Math.min(size - 1, gy));
            long k = (gx >> 8) << 32 | gy >> 8;
            if (k != key) {
                int tx = (int) (gx >> 8), ty = (int) (gy >> 8);
                tile = read.computeIfAbsent(k, unused -> tiles.tile(CHECK_ZOOM, tx, ty));
                key = k;
            }
            return tile[(int) ((gy & 255) * 256 + (gx & 255))];
        }
    }

    /** Latitude in degrees of a global pixel row position at a map size. */
    private static double latitude(double row, long size) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * Math.max(0, Math.min(size, row)) / size))));
    }

    private static int wrapWest(int west) { return Math.floorMod(west + 180, 360) - 180; }
}
