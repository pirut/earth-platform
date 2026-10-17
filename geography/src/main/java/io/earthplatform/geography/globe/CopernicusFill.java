package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Each degree square is checked once, a sixteenth at a time: Terrarium at zoom {@value #CHECK_ZOOM}, as it was, against
 * Copernicus at overview {@value #CHECK_OVERVIEW} (about 120 m), at Copernicus's pixels. Two
 * averages of how far Terrarium stands above Copernicus give a weight from 0, where the tiles
 * stand, to 1, where Copernicus does:
 *
 * <ul>
 *   <li>over {@code 2 * WIDE + 1} pixels square (about 5 km), from {@value #OFFSET_AGREE} m to
 *       {@value #OFFSET_DISAGREE} m: an offset of the whole ground;</li>
 *   <li>over {@code 2 * SMOOTH + 1} pixels square (about 600 m), from {@value #AGREE} m to
 *       {@value #DISAGREE} m plus a quarter of Copernicus's relief there: a fill. The relief keeps
 *       steep ground, where the two datasets' pixels differ most, as it is.</li>
 * </ul>
 *
 * <p>Only Terrarium standing above counts. Where it lies below, it is mostly bare-earth lidar
 * under forest, which Copernicus, a surface model, tops with the canopy; pits are {@code
 * repair}'s. Open sea in Terrarium counts as agreeing where Copernicus has sea too, and is never
 * weighed against Copernicus land, so no land is made out of sea. A tile then moves each pixel
 * that far toward Copernicus, read at the overview nearest its own pixel size. The weights are one
 * smooth field whatever the zoom, so near and far terrain agree, and where they are 0, nearly
 * everywhere, the tiles are left exactly as they were. Copernicus's sea, exactly 0 m, becomes the
 * tiles' open sea where the weight is whole.
 */
public final class CopernicusFill implements ElevationLayer {
    /** Pinned in a world's {@code earth.elevation}; reads {@link CopernicusSource#DATASET_ID}. */
    public static final String DATASET_ID = "glo30-fill-v1";
    /** Coarsest zoom filled. Coarser zooms carry the bathymetry and stay as they are. */
    static final int MIN_ZOOM = 11;
    /**
     * Terrarium zoom the check reads, as it was before filling. Zoom 10 and coarser come from
     * other sources, which hold heights above the sea even where the finer zooms do not.
     */
    static final int CHECK_ZOOM = 11;
    static final int CHECK_OVERVIEW = 2;
    /** Check pixels either side whose difference is averaged for a fill. */
    static final int SMOOTH = 2;
    /** Average height in metres above Copernicus, beside its relief, up to which the tiles stand, and from which Copernicus does. */
    static final double AGREE = 10, DISAGREE = 25;
    /** Share of Copernicus's relief in the narrow window added to both. */
    static final double RELIEF_SHARE = 0.25;
    /** Check pixels either side whose difference is averaged for an offset of the whole ground. */
    static final int WIDE = 20;
    /** Wide average height in metres above Copernicus up to which the tiles stand, and from which Copernicus does. */
    static final double OFFSET_AGREE = 8, OFFSET_DISAGREE = 16;
    /** Full-resolution Copernicus pixel height in metres. */
    private static final double COPERNICUS_METRES = 40_007_863.0 / 360 / CopernicusSource.ROWS_PER_DEGREE;
    /** Copernicus metres this close to 0 are sea. */
    private static final double SEA_CRUMB = 0.01;
    private static final double EARTH_CIRCUMFERENCE = 40_075_016.7;

    /** The composite's tiles as they were before any layer, such as {@link TerrariumTileSource#cleaned}. */
    public interface Tiles { float[] tile(int zoom, int x, int y); }

    /** Parts a degree square is checked in, along each side, so a place's first tiles wait on a sixteenth of it. */
    static final int PARTS = 4;
    /** Bands of a part's rows read together on a check: about as many as the tiles and blocks a part's margin crosses. */
    static final int CHECK_BANDS = 8;

    /**
     * One part of a degree square's weights at the check overview: rows {@code row0} on and
     * columns {@code column0} on of a square {@code width} x {@code height} check pixels, 0 to
     * 255, row-major from the north; null where all are 0.
     */
    record Part(int width, int height, int row0, int column0, int rows, int columns, byte[] weights) {
        static final Part NONE = new Part(0, 0, 0, 0, 0, 0, null);
    }

    private final CopernicusSource dem;
    private final Tiles tiles;
    private final int maxParts;
    private final LruMap<Long, Part> parts = new LruMap<>(64);
    private final Map<Long, CompletableFuture<Part>> inFlight = new ConcurrentHashMap<>();

    public CopernicusFill(CopernicusSource dem, Tiles tiles, int maxParts) {
        this.dem = dem;
        this.tiles = tiles;
        this.maxParts = maxParts;
    }

    @Override public String id() { return DATASET_ID; }

    @Override public void refine(int zoom, int x, int y, float[] values) { apply(values, zoom, x, y); }

    /** The checks {@link #apply} asks first, and the Copernicus blocks under the tile's corners and middle where they weigh anything. */
    @Override public void prefetch(int zoom, int tileX, int tileY) {
        if (zoom < MIN_ZOOM) return;
        long size = (long) TerrariumTileSource.TILE_SIZE << zoom;
        double north = latitude(tileY * 256L, size), south = latitude((tileY + 1) * 256L, size);
        double west = tileX * 360.0 / (1 << zoom) - 180, east = (tileX + 1) * 360.0 / (1 << zoom) - 180;
        double margin = 0.02;
        if (!any(Math.max(-89.9999, south - margin), Math.min(89.9999, north + margin), west - margin, east + margin)) return;
        double pixel = EARTH_CIRCUMFERENCE * Math.cos(Math.toRadians((north + south) / 2)) / size;
        int overview = (int) Math.max(0, Math.min(CopernicusSource.MAX_OVERVIEW, Math.floor(Math.log(pixel / COPERNICUS_METRES) / Math.log(2))));
        List<Runnable> reads = new ArrayList<>();
        for (double latitude : new double[] {north, (north + south) / 2, south})
            for (double longitude : new double[] {west, (west + east) / 2, east})
                reads.add(() -> {
                    double at = Math.max(-89.9999, Math.min(89.9999, latitude));
                    // Only blocks apply reads: where the weight stands.
                    if (weight(at, longitude) > 0) dem.metres(at, longitude, overview);
                });
        RangeFiles.all(reads);
    }

    /** Moves a tile's pixels toward Copernicus by the weight where they stand. */
    void apply(float[] values, int zoom, int tileX, int tileY) {
        if (zoom < MIN_ZOOM) return;
        long size = (long) TerrariumTileSource.TILE_SIZE << zoom;
        double north = latitude(tileY * 256L, size), south = latitude((tileY + 1) * 256L, size);
        double west = tileX * 360.0 / (1 << zoom) - 180, east = (tileX + 1) * 360.0 / (1 << zoom) - 180;
        // Parts the tile and its weights' reach touch: a weight near a part's edge reads the next part.
        double margin = 0.02;
        if (!any(Math.max(-89.9999, south - margin), Math.min(89.9999, north + margin), west - margin, east + margin)) return;
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

    /** Whether any weight in a box of latitudes and longitudes is above 0. */
    private boolean any(double south, double north, double west, double east) {
        for (int s = (int) Math.floor(south); s <= (int) Math.floor(north); s++)
            for (int w = (int) Math.floor(west); w <= (int) Math.floor(east); w++) {
                int square = wrapWest(w);
                int[] dims = dem.size(s + 0.5, square + 0.5, CHECK_OVERVIEW);
                if (dims == null) continue;
                int width = dims[0], height = dims[1];
                // Check rows and columns of this square the box covers.
                int top = row(Math.min(s + 1, north), s, height), bottom = row(Math.max(s, south), s, height);
                int left = column(Math.max(w, west), w, width), right = column(Math.min(w + 1, east), w, width);
                for (int pr = part(top, height); pr <= part(bottom, height); pr++)
                    for (int pc = part(left, width); pc <= part(right, width); pc++)
                        if (part(s, square, width, height, pr, pc).weights != null) return true;
            }
        return false;
    }

    private static int row(double latitude, int south, int height) { return (int) Math.max(0, Math.min(height - 1, Math.floor((south + 1 - latitude) * height))); }
    private static int column(double longitude, int west, int width) { return (int) Math.max(0, Math.min(width - 1, Math.floor((longitude - west) * width))); }

    /** The part along a side of {@code n} check pixels holding pixel {@code i}: part p holds {@code p*n/PARTS} up to {@code (p+1)*n/PARTS}. */
    private static int part(int i, int n) {
        int p = Math.min(PARTS - 1, i * PARTS / n);
        while (p > 0 && p * n / PARTS > i) p--;
        while (p < PARTS - 1 && (p + 1) * n / PARTS <= i) p++;
        return p;
    }

    /** The weight at a point, bilinear between check pixels, reaching into the squares around at the edges. */
    double weight(double latitude, double longitude) {
        longitude = CopernicusSource.wrap(longitude);
        int south = Math.min(89, (int) Math.floor(latitude)), west = (int) Math.floor(longitude);
        int[] dims = dem.size(latitude, longitude, CHECK_OVERVIEW);
        if (dims == null) return 0;
        int width = dims[0], height = dims[1];
        double fy = (south + 1 - latitude) * height - 0.5, fx = (longitude - west) * width - 0.5;
        int y0 = (int) Math.floor(fy), x0 = (int) Math.floor(fx);
        double ty = fy - y0, tx = fx - x0;
        Part here = part(south, west, width, height, part(Math.max(0, y0), height), part(Math.max(0, x0), width));
        double v00 = at(here, south, west, y0, x0), v01 = at(here, south, west, y0, x0 + 1);
        double v10 = at(here, south, west, y0 + 1, x0), v11 = at(here, south, west, y0 + 1, x0 + 1);
        return ((v00 * (1 - tx) + v01 * tx) * (1 - ty) + (v10 * (1 - tx) + v11 * tx) * ty) / 255;
    }

    /**
     * A check pixel of a square by row and column, from the part given when it holds it, else the
     * part that does; either may lie past the square's edge: then the nearest pixel there.
     */
    private double at(Part here, int south, int west, int row, int column) {
        int width = here.width, height = here.height;
        boolean moved = false;
        if (row < 0 || column < 0 || row >= height || column >= width) {
            double latitude = south + 1 - (row + 0.5) / height, longitude = CopernicusSource.wrap(west + (column + 0.5) / width);
            if (latitude >= 90 || latitude <= -90) {
                row = Math.max(0, Math.min(height - 1, row));
                column = Math.max(0, Math.min(width - 1, column));
            } else {
                south = Math.min(89, (int) Math.floor(latitude));
                west = (int) Math.floor(longitude);
                int[] dims = dem.size(latitude, longitude, CHECK_OVERVIEW);
                if (dims == null) return 0;
                width = dims[0];
                height = dims[1];
                row = row(latitude, south, height);
                column = column(longitude, west, width);
                moved = true;
            }
        }
        if (moved || row < here.row0 || column < here.column0 || row >= here.row0 + here.rows || column >= here.column0 + here.columns)
            here = part(south, west, width, height, part(row, height), part(column, width));
        return here.weights == null ? 0 : here.weights[(row - here.row0) * here.columns + column - here.column0] & 0xFF;
    }

    Part part(int south, int west, int width, int height, int partRow, int partColumn) {
        long key = ((long) (south + 90) * 1000 + west + 180) * PARTS * PARTS + partRow * PARTS + partColumn;
        synchronized (parts) {
            Part hit = parts.get(key);
            if (hit != null) return hit;
        }
        CompletableFuture<Part> mine = new CompletableFuture<>(), shared = inFlight.putIfAbsent(key, mine);
        if (shared == null) {
            try {
                Part computed = check(south, west, width, height, partRow, partColumn);
                synchronized (parts) {
                    parts.putAndTrim(key, computed, maxParts);
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

    /** Checks one part of a degree square, {@code width} x {@code height} check pixels: see the class notes. */
    private Part check(int south, int west, int width, int height, int partRow, int partColumn) {
        int row0 = partRow * height / PARTS, column0 = partColumn * width / PARTS;
        int rows = (partRow + 1) * height / PARTS - row0, columns = (partColumn + 1) * width / PARTS - column0;
        int m = WIDE, across = columns + 2 * m, down = rows + 2 * m;
        // The difference and Copernicus over the part and a margin round it, NaN where unknown.
        float[] difference = new float[across * down], copernicus = new float[across * down];
        // Bands of rows read at once: on a first visit each reads its own tiles and blocks from the network, which
        // one after another kept a place's first tiles waiting seconds on this check.
        int bands = Math.min(CHECK_BANDS, down);
        List<Runnable> reads = new ArrayList<>(bands);
        // The tiles under the part load beside Copernicus's block rather than after it; a failure is left for the bands to meet.
        long size = (long) TerrariumTileSource.TILE_SIZE << CHECK_ZOOM;
        double top = Math.min(85.0511, south + 1 - (row0 - m - 1.0) / height), bottom = Math.max(-85.0511, south + 1 - (row0 + down - m + 1.0) / height);
        int tileTop = (int) (mercatorY(top, size) / TerrariumTileSource.TILE_SIZE), tileBottom = (int) (mercatorY(bottom, size) / TerrariumTileSource.TILE_SIZE);
        double left = west + (column0 - m - 1.0) / width, right = west + (column0 + across - m + 1.0) / width;
        int tileLeft = (int) Math.floor((left + 180) / 360 * (1 << CHECK_ZOOM)), tileRight = (int) Math.floor((right + 180) / 360 * (1 << CHECK_ZOOM));
        for (int ty = Math.max(0, tileTop); ty <= Math.min((1 << CHECK_ZOOM) - 1, tileBottom); ty++)
            for (int tx = tileLeft; tx <= tileRight; tx++) {
                int x = Math.floorMod(tx, 1 << CHECK_ZOOM), y = ty;
                reads.add(() -> { try { tiles.tile(CHECK_ZOOM, x, y); } catch (RuntimeException missing) { /* the bands meet it */ } });
            }
        for (int b = 0; b < bands; b++) {
            int from = b * down / bands, to = (b + 1) * down / bands;
            reads.add(() -> {
                Terrarium terrarium = new Terrarium();
                for (int r = from; r < to; r++) {
                    double latitude = south + 1 - (row0 + r - m + 0.5) / height;
                    for (int c = 0; c < across; c++) {
                        int i = r * across + c;
                        difference[i] = copernicus[i] = Float.NaN;
                        if (Math.abs(latitude) >= 90) continue;
                        double longitude = west + (column0 + c - m + 0.5) / width;
                        double cop = dem.metres(latitude, longitude, CHECK_OVERVIEW);
                        if (Double.isNaN(cop)) continue;
                        copernicus[i] = (float) cop;
                        double t = terrarium.metres(latitude, longitude);
                        if (t > 0) difference[i] = (float) (t - cop);
                        else if (cop < SEA_CRUMB) difference[i] = 0;
                    }
                }
            });
        }
        RangeFiles.all(reads);
        Window window = new Window(difference, across, down);
        byte[] weights = new byte[rows * columns];
        boolean any = false;
        for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
            int pr = r + m, pc = c + m;
            double offset = window.mean(pr - WIDE, pc - WIDE, pr + WIDE, pc + WIDE);
            double weight = ramp(offset, OFFSET_AGREE, OFFSET_DISAGREE);
            if (weight < 1) {
                double fill = window.mean(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH);
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
            weights[r * columns + c] = (byte) quantised;
            any |= quantised > 0;
        }
        return new Part(width, height, row0, column0, rows, columns, any ? weights : null);
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

    /** Global pixel row of a latitude at a map size. */
    private static double mercatorY(double latitude, long size) {
        double phi = Math.toRadians(latitude);
        return (1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2 * size;
    }

    /** Latitude in degrees of a global pixel row position at a map size. */
    private static double latitude(double row, long size) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * Math.max(0, Math.min(size, row)) / size))));
    }

    private static int wrapWest(int west) { return Math.floorMod(west + 180, 360) - 180; }
}
