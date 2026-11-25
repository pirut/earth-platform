package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.util.ArrayList;
import java.util.Arrays;
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
 *   <li>Glaciers sunk under their ice, in worlds pinned to {@value #ICE_ID}. Radar lost much of
 *       the ice in 2000, and the composite filled the gaps from the ground round them in smooth
 *       dips with straight edges, tens of metres under the ice the land cover maps: by Perito
 *       Moreno's front a trench twenty blocks deep zigzagged across the tongue.</li>
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
 * repair}'s. On snow and ice no canopy stands, and given where the ice is, the fill also takes
 * Copernicus wherever Terrarium lies under it there, from {@value #AGREE} m to wholly by
 * {@value #DISAGREE} m, check pixel by check pixel: such dips are a few hundred metres across,
 * and the windows' averages missed them. Open sea in Terrarium counts as agreeing where Copernicus has sea too, and is never
 * weighed against Copernicus land, so no land is made out of sea. A tile then moves each pixel
 * that far toward Copernicus, read at the overview nearest its own pixel size. The weights are one
 * smooth field whatever the zoom, so near and far terrain agree, and where they are 0, nearly
 * everywhere, the tiles are left exactly as they were. Copernicus's sea, exactly 0 m, becomes the
 * tiles' open sea where the weight is whole.
 */
public final class CopernicusFill implements ElevationLayer {
    /** Pinned in a world's {@code earth.elevation}; reads {@link CopernicusSource#DATASET_ID}. */
    public static final String DATASET_ID = "glo30-fill-v1";
    /** The fill that also lifts Terrarium sunk under snow and ice; reads {@link CopernicusSource#DATASET_ID} too. */
    public static final String ICE_ID = "glo30-fill-v2";
    /**
     * Fill with smooth land-only regional datum subtraction, bringing ellipsoid-referenced ArcticDEM onto the
     * orthometric sea-level datum so high-resolution cliffs and canyons stay intact instead of being replaced by GLO-30.
     */
    public static final String GEOID_ID = "glo30-fill-v3";
    /** Coarsest zoom filled. Coarser zooms carry the bathymetry and stay as they are. */
    static final int MIN_ZOOM = 11;
    /**
     * Terrarium zoom the check reads, as it was before filling. Zoom 10 and coarser come from
     * other sources, which hold heights above the sea even where the finer zooms do not.
     */
    static final int CHECK_ZOOM = 11;
    static final int CHECK_OVERVIEW = 2;
    /**
     * A tile's weights keep the last degree square's check size and the last part at hand, rather than asking the
     * shared caches for them at each of its 65,536 pixels; {@code false} asks every time, for comparisons. The
     * weights are the same either way.
     */
    private static final boolean FAST_WEIGHT = !"false".equals(System.getProperty("earthplatform.copernicus.fastWeight"));

    /** The last square's check size and the last part one tile's weights read. */
    private static final class Memo {
        boolean sized;
        int south, west;
        int[] dims;
        long partKey = Long.MIN_VALUE;
        Part part;
        // The last check row and column asked about, with the side length it was over, and their parts.
        int rowAt = -1, rowOf, partRow, columnAt = -1, columnOf, partColumn;
        final double[] wd = new double[2];
    }
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
    /** Coarse grid stride and robust window radius (in check pixels) for regional datum estimation in {@value #GEOID_ID}. */
    static final int DATUM_STEP = 8;
    static final int DATUM_RADIUS = 16;
    private static final int DATUM_MARGIN = DATUM_RADIUS + SMOOTH + DATUM_STEP;
    /** Maximum plausible ellipsoid-to-geoid offset (metres); larger differences are local spikes or voids, not datum. */
    private static final double MAX_DATUM_OFFSET = 120.0;
    /** Minimum reliable land-to-land check pixels needed in a window before estimating a regional datum shift. */
    private static final int MIN_LAND_SAMPLES = 16;
    /** Coherence bounds on land-to-land MAD (metres / share of offset) for accepting a regional datum shift. */
    private static final double DATUM_MAD_MIN = 6.0, DATUM_MAD_SHARE = 0.25;
    private static final double DATUM_MAD_MAX = 12.0, DATUM_MAD_REJECT_SHARE = 0.45;
    /** Datum-adjusted filled-fjord thresholds over Copernicus sea ({@code cop <= SEA_CRUMB}). */
    private static final double FJORD_PIXEL_MIN = 5.0, FJORD_POS_SHARE = 0.75;
    private static final int FJORD_MIN_SEA_PIXELS = 3;
    private static final double FJORD_AGREE = 8.0, FJORD_DISAGREE = 18.0;
    /** Minimum positive elevation kept when datum subtraction would push Copernicus-confirmed land below sea level. */
    private static final double LAND_FLOOR = 0.01;
    /** Full-resolution Copernicus pixel height in metres. */
    private static final double COPERNICUS_METRES = 40_007_863.0 / 360 / CopernicusSource.ROWS_PER_DEGREE;
    /** Copernicus metres this close to 0 are sea. */
    private static final double SEA_CRUMB = 0.01;
    /** Negative Terrarium depth beside a coastal land void above which the offshore void ramp takes Copernicus sea. */
    private static final double VOID_SHALLOW = -40;
    /** Implausibly deep negative Terrarium directly beside Copernicus land (coastal -32768 void ramp). */
    private static final double COASTAL_ABYSS = -500, COASTAL_ABYSS_FULL = -800;
    private static final double EARTH_CIRCUMFERENCE = 40_075_016.7;

    /** The composite's tiles as they were before any layer, such as {@link TerrariumTileSource#cleaned}. */
    public interface Tiles { float[] tile(int zoom, int x, int y); }

    /** Whether the land cover maps snow or ice at a latitude and longitude, read at about the check's pixels. */
    public interface Ice { boolean at(double latitude, double longitude); }

    /** Parts a degree square is checked in, along each side, so a place's first tiles wait on a sixteenth of it. */
    static final int PARTS = 4;
    /** Bands of a part's rows read together on a check: about as many as the tiles and blocks a part's margin crosses. */
    static final int CHECK_BANDS = 8;

    /**
     * One part of a degree square's weights (and optional regional datum offset in {@value #GEOID_ID}) at the
     * check overview: rows {@code row0} on and columns {@code column0} on of a square {@code width} x {@code height}
     * check pixels, row-major from the north; null where all are 0.
     */
    record Part(int width, int height, int row0, int column0, int rows, int columns, byte[] weights, float[] datum) {
        static final Part NONE = new Part(0, 0, 0, 0, 0, 0, null, null);
    }

    private final CopernicusSource dem;
    private final Tiles tiles;
    private final Ice ice;
    private final boolean geoid;
    private final int maxParts;
    private final LruMap<Long, Part> parts = new LruMap<>(64);
    private final Map<Long, CompletableFuture<Part>> inFlight = new ConcurrentHashMap<>();

    public CopernicusFill(CopernicusSource dem, Tiles tiles, int maxParts) { this(dem, tiles, null, false, maxParts); }

    /** @param ice where the land cover has snow and ice, for {@value #ICE_ID}; null for {@value #DATASET_ID} */
    public CopernicusFill(CopernicusSource dem, Tiles tiles, Ice ice, int maxParts) { this(dem, tiles, ice, false, maxParts); }

    /** @param geoid true for {@value #GEOID_ID} regional land-only datum subtraction */
    public CopernicusFill(CopernicusSource dem, Tiles tiles, Ice ice, boolean geoid, int maxParts) {
        this.dem = dem;
        this.tiles = tiles;
        this.ice = ice;
        this.geoid = geoid;
        this.maxParts = maxParts;
    }

    @Override public String id() {
        if (geoid) return GEOID_ID;
        return ice == null ? DATASET_ID : ICE_ID;
    }

    @Override public void refine(int zoom, int x, int y, float[] values) { apply(values, zoom, x, y); }

    /** The checks {@link #apply} asks first, and the Copernicus blocks under the tile's corners and middle where they weigh anything. */
    @Override public void prefetch(int zoom, int tileX, int tileY) {
        if (zoom < MIN_ZOOM) return;
        long size = (long) TerrariumTileSource.TILE_SIZE << zoom;
        double north = latitude(tileY * 256L, size), south = latitude((tileY + 1) * 256L, size);
        double west = tileX * 360.0 / (1 << zoom) - 180, east = (tileX + 1) * 360.0 / (1 << zoom) - 180;
        double margin = 0.02;
        // The checks under the tile all at once: any() asks them one at a time until one stands, each a round of
        // downloads of its own on a first visit, and the tile's pixels then asked for the rest.
        checks(Math.max(-89.9999, south - margin), Math.min(89.9999, north + margin), west - margin, east + margin);
        if (!any(Math.max(-89.9999, south - margin), Math.min(89.9999, north + margin), west - margin, east + margin, false)) return;
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
        if (!any(Math.max(-89.9999, south - margin), Math.min(89.9999, north + margin), west - margin, east + margin, geoid)
                && !hasImpossibleDepth(values)) return;
        double mid = Math.toRadians((north + south) / 2);
        double pixel = EARTH_CIRCUMFERENCE * Math.cos(mid) / size;
        int overview = (int) Math.max(0, Math.min(CopernicusSource.MAX_OVERVIEW, Math.floor(Math.log(pixel / COPERNICUS_METRES) / Math.log(2))));
        double[] longitude = new double[TerrariumTileSource.TILE_SIZE];
        for (int px = 0; px < longitude.length; px++) longitude[px] = (tileX * 256L + px + 0.5) * 360.0 / size - 180;
        Memo memo = FAST_WEIGHT ? new Memo() : null;
        if (!geoid) {
            for (int py = 0; py < TerrariumTileSource.TILE_SIZE; py++) {
                double latitude = latitude(tileY * 256L + py + 0.5, size);
                for (int px = 0; px < TerrariumTileSource.TILE_SIZE; px++) {
                    int i = py * TerrariumTileSource.TILE_SIZE + px;
                    float t = values[i];
                    if (t == 0f) continue;
                    double weight = t < TileRepair.IMPOSSIBLE_DEPTH ? 1 : weight(latitude, longitude[px], memo);
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
            return;
        }
        double[] wd = new double[2];
        for (int py = 0; py < TerrariumTileSource.TILE_SIZE; py++) {
            double latitude = latitude(tileY * 256L + py + 0.5, size);
            for (int px = 0; px < TerrariumTileSource.TILE_SIZE; px++) {
                int i = py * TerrariumTileSource.TILE_SIZE + px;
                float t = values[i];
                if (t == 0f) continue;
                double weight, datum;
                if (t < TileRepair.IMPOSSIBLE_DEPTH) {
                    weight = 1;
                    datum = 0;
                } else {
                    sample(latitude, longitude[px], memo, wd);
                    weight = wd[0];
                    datum = wd[1];
                }
                if (weight <= 0 && (datum == 0 || t < 0f)) continue;
                double base = t > 0f ? t - datum : t;
                double c = Double.NaN;
                if (weight > 0 || (t > 0f && base <= 0)) {
                    c = dem.metres(latitude, longitude[px], overview);
                    // Overviews average the sea's zeros into crumbs of a picometre.
                    if (!Double.isNaN(c) && Math.abs(c) < SEA_CRUMB) c = 0;
                }
                if (t > 0f && base <= 0 && !Double.isNaN(c) && c > SEA_CRUMB) {
                    // Never turn Copernicus-confirmed land negative into fake sea due to smooth datum subtraction.
                    base = Math.max(LAND_FLOOR, base);
                }
                float v = weight > 0 && !Double.isNaN(c) ? (float) (base + weight * (c - base)) : (float) base;
                if (v == 0f && !(c == 0 && weight >= 1)) v = Math.ulp(0f);
                values[i] = v;
            }
        }
    }

    private static boolean hasImpossibleDepth(float[] values) {
        for (float v : values) if (v < TileRepair.IMPOSSIBLE_DEPTH) return true;
        return false;
    }

    /** Works out together the parts {@link #any} would ask of a box one by one; a failure is left for it to meet. */
    private void checks(double south, double north, double west, double east) {
        List<Runnable> squares = new ArrayList<>();
        for (int s = (int) Math.floor(south); s <= (int) Math.floor(north); s++)
            for (int w = (int) Math.floor(west); w <= (int) Math.floor(east); w++) {
                int at = s, from = w;
                squares.add(() -> {
                    int square = wrapWest(from);
                    int[] dims = dem.size(at + 0.5, square + 0.5, CHECK_OVERVIEW);
                    if (dims == null) return;
                    int width = dims[0], height = dims[1];
                    int top = row(Math.min(at + 1, north), at, height), bottom = row(Math.max(at, south), at, height);
                    int left = column(Math.max(from, west), from, width), right = column(Math.min(from + 1, east), from, width);
                    List<Runnable> checks = new ArrayList<>();
                    for (int pr = part(top, height); pr <= part(bottom, height); pr++)
                        for (int pc = part(left, width); pc <= part(right, width); pc++) {
                            int partRow = pr, partColumn = pc;
                            boolean known;
                            synchronized (this.parts) { known = this.parts.get(partKey(at, square, partRow, partColumn)) != null; }
                            if (!known) checks.add(() -> part(at, square, width, height, partRow, partColumn));
                        }
                    if (checks.size() == 1) checks.get(0).run();
                    else if (!checks.isEmpty()) RangeFiles.all(checks);
                });
            }
        try {
            if (squares.size() == 1) squares.get(0).run();
            else if (!squares.isEmpty()) RangeFiles.all(squares);
        } catch (RuntimeException later) {
            // any() and the tile's weights meet it, and report it.
        }
    }

    /** Whether any part in a box of latitudes and longitudes has non-zero weights (or datum shifts when {@code includeDatum}). */
    private boolean any(double south, double north, double west, double east, boolean includeDatum) {
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
                    for (int pc = part(left, width); pc <= part(right, width); pc++) {
                        Part p = part(s, square, width, height, pr, pc);
                        if (p.weights != null || (includeDatum && p.datum != null)) return true;
                    }
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
    double weight(double latitude, double longitude) { return weight(latitude, longitude, null); }

    /** The smooth regional datum offset (metres) at a point for {@value #GEOID_ID}, or 0 for earlier pins. */
    double datum(double latitude, double longitude) {
        if (!geoid) return 0;
        double[] out = new double[2];
        sample(latitude, longitude, null, out);
        return out[1];
    }

    private double weight(double latitude, double longitude, Memo memo) {
        double[] out = memo != null ? memo.wd : new double[2];
        sample(latitude, longitude, memo, out);
        return out[0];
    }

    /** Samples bilinear weight ({@code out[0]}) and regional datum offset ({@code out[1]}) at a point. */
    private void sample(double latitude, double longitude, Memo memo, double[] out) {
        longitude = CopernicusSource.wrap(longitude);
        int south = Math.min(89, (int) Math.floor(latitude)), west = (int) Math.floor(longitude);
        int[] dims;
        // The size names the square by the longitude wrapped once more, which moves it by far less than this margin.
        if (memo != null && longitude - west >= 1e-9 && west + 1 - longitude >= 1e-9) {
            if (!memo.sized || memo.south != south || memo.west != west) {
                memo.dims = dem.size(latitude, longitude, CHECK_OVERVIEW);
                memo.sized = true;
                memo.south = south;
                memo.west = west;
            }
            dims = memo.dims;
        } else {
            dims = dem.size(latitude, longitude, CHECK_OVERVIEW);
        }
        if (dims == null) { out[0] = 0; out[1] = 0; return; }
        int width = dims[0], height = dims[1];
        double fy = (south + 1 - latitude) * height - 0.5, fx = (longitude - west) * width - 0.5;
        int y0 = (int) Math.floor(fy), x0 = (int) Math.floor(fx);
        double ty = fy - y0, tx = fx - x0;
        int partRow, partColumn;
        if (memo != null) {
            int r = Math.max(0, y0), c = Math.max(0, x0);
            if (memo.rowAt != r || memo.rowOf != height) { memo.rowAt = r; memo.rowOf = height; memo.partRow = part(r, height); }
            if (memo.columnAt != c || memo.columnOf != width) { memo.columnAt = c; memo.columnOf = width; memo.partColumn = part(c, width); }
            partRow = memo.partRow;
            partColumn = memo.partColumn;
        } else {
            partRow = part(Math.max(0, y0), height);
            partColumn = part(Math.max(0, x0), width);
        }
        Part here;
        if (memo != null) {
            long key = partKey(south, west, partRow, partColumn);
            if (memo.partKey != key) {
                memo.part = part(south, west, width, height, partRow, partColumn);
                memo.partKey = key;
            }
            here = memo.part;
        } else {
            here = part(south, west, width, height, partRow, partColumn);
        }
        double w00 = atWeight(here, south, west, y0, x0), w01 = atWeight(here, south, west, y0, x0 + 1);
        double w10 = atWeight(here, south, west, y0 + 1, x0), w11 = atWeight(here, south, west, y0 + 1, x0 + 1);
        out[0] = ((w00 * (1 - tx) + w01 * tx) * (1 - ty) + (w10 * (1 - tx) + w11 * tx) * ty) / 255;
        if (!geoid) {
            out[1] = 0;
            return;
        }
        double d00 = atDatum(here, south, west, y0, x0), d01 = atDatum(here, south, west, y0, x0 + 1);
        double d10 = atDatum(here, south, west, y0 + 1, x0), d11 = atDatum(here, south, west, y0 + 1, x0 + 1);
        out[1] = (d00 * (1 - tx) + d01 * tx) * (1 - ty) + (d10 * (1 - tx) + d11 * tx) * ty;
    }

    /**
     * A check pixel's weight of a square by row and column, from the part given when it holds it, else the
     * part that does; either may lie past the square's edge: then the nearest pixel there.
     */
    private double atWeight(Part here, int south, int west, int row, int column) {
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

    /**
     * A check pixel's regional datum offset; when crossing into an open-ocean square with no Copernicus file,
     * holds the boundary pixel's datum so coastlines do not ramp artificially toward 0.
     */
    private double atDatum(Part here, int south, int west, int row, int column) {
        int width = here.width, height = here.height;
        boolean moved = false;
        if (row < 0 || column < 0 || row >= height || column >= width) {
            double latitude = south + 1 - (row + 0.5) / height, longitude = CopernicusSource.wrap(west + (column + 0.5) / width);
            if (latitude >= 90 || latitude <= -90) {
                row = Math.max(0, Math.min(height - 1, row));
                column = Math.max(0, Math.min(width - 1, column));
            } else {
                int ns = Math.min(89, (int) Math.floor(latitude));
                int nw = (int) Math.floor(longitude);
                int[] dims = dem.size(latitude, longitude, CHECK_OVERVIEW);
                if (dims == null) {
                    row = Math.max(0, Math.min(height - 1, row));
                    column = Math.max(0, Math.min(width - 1, column));
                } else {
                    south = ns;
                    west = nw;
                    width = dims[0];
                    height = dims[1];
                    row = row(latitude, south, height);
                    column = column(longitude, west, width);
                    moved = true;
                }
            }
        }
        if (moved || row < here.row0 || column < here.column0 || row >= here.row0 + here.rows || column >= here.column0 + here.columns)
            here = part(south, west, width, height, part(row, height), part(column, width));
        return here.datum == null ? 0 : here.datum[(row - here.row0) * here.columns + column - here.column0];
    }

    private static long partKey(int south, int west, int partRow, int partColumn) {
        return ((long) (south + 90) * 1000 + west + 180) * PARTS * PARTS + partRow * PARTS + partColumn;
    }

    Part part(int south, int west, int width, int height, int partRow, int partColumn) {
        long key = partKey(south, west, partRow, partColumn);
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
            return DataWaits.get(shared);
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
        int m = geoid ? DATUM_MARGIN : WIDE, across = columns + 2 * m, down = rows + 2 * m;
        // The difference, Copernicus and Terrarium over the part and a margin round it, NaN where unknown.
        float[] difference = new float[across * down], copernicus = new float[across * down], raw = new float[across * down];
        // How far Terrarium lies under Copernicus on snow and ice, check pixel by check pixel; 0 elsewhere.
        float[] sunk = new float[across * down];
        float[] landDiff = geoid ? new float[across * down] : null;
        boolean[] isIce = geoid && ice != null ? new boolean[across * down] : null;
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
        // And Copernicus under the window's corners and middle, so a margin that reaches into the next degree square
        // has that square's header and block on their way from the start, not from the row that first meets them.
        for (int r : new int[] {0, down / 2, down - 1})
            for (int c : new int[] {0, across / 2, across - 1}) {
                double latitude = south + 1 - (row0 + r - m + 0.5) / height, longitude = west + (column0 + c - m + 0.5) / width;
                if (Math.abs(latitude) >= 90) continue;
                reads.add(() -> { try { dem.metres(latitude, longitude, CHECK_OVERVIEW); } catch (RuntimeException missing) { /* the bands meet it */ } });
            }
        for (int b = 0; b < bands; b++) {
            int from = b * down / bands, to = (b + 1) * down / bands;
            reads.add(() -> {
                Terrarium terrarium = new Terrarium();
                for (int r = from; r < to; r++) {
                    double latitude = south + 1 - (row0 + r - m + 0.5) / height;
                    for (int c = 0; c < across; c++) {
                        int i = r * across + c;
                        difference[i] = copernicus[i] = raw[i] = Float.NaN;
                        if (landDiff != null) landDiff[i] = Float.NaN;
                        if (Math.abs(latitude) >= 90) continue;
                        double longitude = west + (column0 + c - m + 0.5) / width;
                        double cop = dem.metres(latitude, longitude, CHECK_OVERVIEW);
                        if (Double.isNaN(cop)) continue;
                        copernicus[i] = (float) cop;
                        double t = terrarium.metres(latitude, longitude);
                        raw[i] = (float) t;
                        if (!geoid) {
                            if (t > 0) difference[i] = (float) (t - cop);
                            else if (cop < SEA_CRUMB) difference[i] = 0;
                            if (t != 0 && (t > 0 || cop > SEA_CRUMB) && cop - t > AGREE && ice != null && ice.at(latitude, longitude))
                                sunk[i] = (float) (cop - t);
                        } else {
                            boolean snowIce = isIce != null && t != 0 && (t > 0 || cop > SEA_CRUMB)
                                    && t - cop < MAX_DATUM_OFFSET - AGREE && ice.at(latitude, longitude);
                            if (snowIce) isIce[i] = true;
                            if (t > 0 && cop > SEA_CRUMB) {
                                double d = t - cop;
                                if (Math.abs(d) <= MAX_DATUM_OFFSET && !(snowIce && cop - t > AGREE)) {
                                    landDiff[i] = (float) d;
                                }
                            }
                        }
                    }
                }
            });
        }
        RangeFiles.all(reads);
        float[] datumOut = null;
        Window window, landWindow = null, seaWindow = null, seaPosWindow = null;
        if (geoid) {
            float[] paddedDatum = estimateDatum(landDiff, row0, column0, rows, columns, m, across, down);
            boolean anyDatum = paddedDatum != null;
            if (anyDatum) {
                datumOut = new float[rows * columns];
                for (int r = 0; r < rows; r++)
                    System.arraycopy(paddedDatum, (r + m) * across + m, datumOut, r * columns, columns);
            }
            float[] adjLandDiff = new float[across * down];
            float[] seaDiff = new float[across * down];
            float[] seaPos = new float[across * down];
            for (int i = 0; i < across * down; i++) {
                float cop = copernicus[i];
                if (cop != cop) {
                    adjLandDiff[i] = seaDiff[i] = seaPos[i] = Float.NaN;
                    continue;
                }
                float t = raw[i];
                double d = anyDatum ? paddedDatum[i] : 0;
                double tAdj = t > 0f ? t - d : t;
                if (t > 0f) difference[i] = (float) (tAdj - cop);
                else if (cop < SEA_CRUMB) difference[i] = 0f;
                adjLandDiff[i] = t > 0f && cop > SEA_CRUMB ? (float) (tAdj - cop) : Float.NaN;
                if (cop <= SEA_CRUMB) {
                    if (t == 0f) {
                        seaDiff[i] = 0f;
                        seaPos[i] = 0f;
                    } else if (t > 0f) {
                        float excess = (float) Math.max(0.0, tAdj);
                        seaDiff[i] = excess;
                        seaPos[i] = excess > FJORD_PIXEL_MIN ? 1f : 0f;
                    } else {
                        seaDiff[i] = Float.NaN;
                        seaPos[i] = Float.NaN;
                    }
                } else {
                    seaDiff[i] = Float.NaN;
                    seaPos[i] = Float.NaN;
                }
                if (t != 0f && (t > 0f || cop > SEA_CRUMB) && cop - tAdj > AGREE && isIce != null && isIce[i])
                    sunk[i] = (float) (cop - tAdj);
            }
            window = new Window(difference, across, down);
            landWindow = new Window(adjLandDiff, across, down);
            seaWindow = new Window(seaDiff, across, down);
            seaPosWindow = new Window(seaPos, across, down);
        } else {
            window = new Window(difference, across, down);
        }
        // Coastal void ramps: negative Terrarium pixels where Copernicus has land, used to also clear
        // the offshore side of the same resampled -32768 ramp without touching genuine ocean bathymetry.
        boolean[] landVoid = null;
        for (int j = m - SMOOTH; j < m + rows + SMOOTH; j++) for (int k = m - SMOOTH; k < m + columns + SMOOTH; k++) {
            int idx = j * across + k;
            float t = raw[idx], cop = copernicus[idx];
            if (!(t < 0 && cop > SEA_CRUMB) || cop - t <= AGREE) continue;
            float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
            for (int rj = j - SMOOTH; rj <= j + SMOOTH; rj++) for (int rk = k - SMOOTH; rk <= k + SMOOTH; rk++) {
                float v = copernicus[rj * across + rk];
                if (v == v) { low = Math.min(low, v); high = Math.max(high, v); }
            }
            double allow = high >= low ? RELIEF_SHARE * (high - low) : 0;
            if (cop - t > DISAGREE + allow) {
                if (landVoid == null) landVoid = new boolean[across * down];
                landVoid[idx] = true;
            }
        }
        byte[] weights = new byte[rows * columns];
        boolean any = false;
        for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
            int pr = r + m, pc = c + m;
            double offset = (geoid ? landWindow : window).mean(pr - WIDE, pc - WIDE, pr + WIDE, pc + WIDE);
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
                if (geoid && weight < 1
                        && seaWindow.count(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH) >= FJORD_MIN_SEA_PIXELS
                        && seaPosWindow.mean(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH) >= FJORD_POS_SHARE) {
                    double seaFill = seaWindow.mean(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH);
                    weight = Math.max(weight, ramp(seaFill, FJORD_AGREE, FJORD_DISAGREE));
                }
                weight = Math.max(weight, ramp(sunk[pr * across + pc], AGREE, DISAGREE));
            }
            if (weight < 1) {
                float t = raw[pr * across + pc], cop = copernicus[pr * across + pc];
                if (t < TileRepair.IMPOSSIBLE_DEPTH) {
                    weight = 1;
                } else if (t < 0) {
                    float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
                    boolean nearImpossible = false, nearLandVoid = false, nearLand = false;
                    for (int j = pr - SMOOTH; j <= pr + SMOOTH; j++) for (int k = pc - SMOOTH; k <= pc + SMOOTH; k++) {
                        int n = j * across + k;
                        float cv = copernicus[n];
                        if (cv == cv) {
                            low = Math.min(low, cv);
                            high = Math.max(high, cv);
                            if (cv > SEA_CRUMB) nearLand = true;
                        }
                        if (raw[n] < TileRepair.IMPOSSIBLE_DEPTH) nearImpossible = true;
                        if (landVoid != null && landVoid[n]) nearLandVoid = true;
                    }
                    if (nearImpossible) {
                        weight = 1;
                    } else if (cop > SEA_CRUMB) {
                        double allow = high >= low ? RELIEF_SHARE * (high - low) : 0;
                        weight = Math.max(weight, ramp(cop - t, AGREE + allow, DISAGREE + allow));
                    } else if (nearLandVoid && t < VOID_SHALLOW) {
                        weight = Math.max(weight, ramp(-t, -VOID_SHALLOW, -2 * VOID_SHALLOW));
                    } else if (nearLand && t < COASTAL_ABYSS) {
                        weight = Math.max(weight, ramp(-t, -COASTAL_ABYSS, -COASTAL_ABYSS_FULL));
                    }
                }
            }
            int quantised = (int) Math.round(weight * 255);
            weights[r * columns + c] = (byte) quantised;
            any |= quantised > 0;
        }
        return new Part(width, height, row0, column0, rows, columns, any ? weights : null, datumOut);
    }

    /**
     * Estimates a smooth, coherent regional datum offset from reliable land-to-land samples ({@code landDiff})
     * on a coarse grid anchored to degree-square multiples of {@link #DATUM_STEP} so adjacent parts match
     * identically, returning a bilinearly interpolated field over the padded grid or null where no offset applies.
     */
    private static float[] estimateDatum(float[] landDiff, int row0, int column0, int rows, int columns, int m, int across, int down) {
        int positive = 0;
        for (float d : landDiff) if (d > OFFSET_AGREE) { if (++positive >= MIN_LAND_SAMPLES) break; }
        if (positive < MIN_LAND_SAMPLES) return null;
        int grMin = Math.floorDiv(row0 - SMOOTH, DATUM_STEP) * DATUM_STEP;
        int grMax = Math.floorDiv(row0 + rows - 1 + SMOOTH, DATUM_STEP) * DATUM_STEP + DATUM_STEP;
        int gcMin = Math.floorDiv(column0 - SMOOTH, DATUM_STEP) * DATUM_STEP;
        int gcMax = Math.floorDiv(column0 + columns - 1 + SMOOTH, DATUM_STEP) * DATUM_STEP + DATUM_STEP;
        int cRows = (grMax - grMin) / DATUM_STEP + 1;
        int cCols = (gcMax - gcMin) / DATUM_STEP + 1;
        float[] coarse = new float[cRows * cCols];
        int maxWindow = (2 * DATUM_RADIUS + 1) * (2 * DATUM_RADIUS + 1);
        float[] sample = new float[maxWindow], dev = new float[maxWindow];
        boolean anyDatum = false;
        for (int cr = 0; cr < cRows; cr++) {
            int pr = grMin + cr * DATUM_STEP - row0 + m;
            for (int cc = 0; cc < cCols; cc++) {
                int pc = gcMin + cc * DATUM_STEP - column0 + m;
                int n = 0;
                for (int j = pr - DATUM_RADIUS; j <= pr + DATUM_RADIUS; j++) {
                    int off = j * across;
                    for (int k = pc - DATUM_RADIUS; k <= pc + DATUM_RADIUS; k++) {
                        float d = landDiff[off + k];
                        if (d == d) sample[n++] = d;
                    }
                }
                if (n < MIN_LAND_SAMPLES) continue;
                Arrays.sort(sample, 0, n);
                double median = (n & 1) == 1 ? sample[n / 2] : 0.5 * (sample[n / 2 - 1] + sample[n / 2]);
                if (median <= OFFSET_AGREE) continue;
                int q1 = n / 4, q3 = n - q1;
                double sum = 0;
                for (int k = q1; k < q3; k++) sum += sample[k];
                double trimmed = sum / (q3 - q1);
                if (trimmed <= OFFSET_AGREE) continue;
                for (int k = 0; k < n; k++) dev[k] = (float) Math.abs(sample[k] - median);
                Arrays.sort(dev, 0, n);
                double mad = (n & 1) == 1 ? dev[n / 2] : 0.5 * (dev[n / 2 - 1] + dev[n / 2]);
                double offsetWeight = ramp(trimmed, OFFSET_AGREE, OFFSET_DISAGREE);
                double madAllow = Math.max(DATUM_MAD_MIN, DATUM_MAD_SHARE * trimmed);
                double madReject = Math.max(DATUM_MAD_MAX, DATUM_MAD_REJECT_SHARE * trimmed);
                double coherence = 1.0 - ramp(mad, madAllow, madReject);
                float nodeDatum = (float) (trimmed * offsetWeight * coherence);
                if (nodeDatum > 0f) {
                    coarse[cr * cCols + cc] = nodeDatum;
                    anyDatum = true;
                }
            }
        }
        if (!anyDatum) return null;
        float[] field = new float[across * down];
        for (int j = 0; j < down; j++) {
            double fr = Math.max(0, Math.min(cRows - 1.0, (double) (row0 + j - m - grMin) / DATUM_STEP));
            int r0 = Math.min(cRows - 2, (int) Math.floor(fr));
            double ty = fr - r0;
            for (int k = 0; k < across; k++) {
                double fc = Math.max(0, Math.min(cCols - 1.0, (double) (column0 + k - m - gcMin) / DATUM_STEP));
                int c0 = Math.min(cCols - 2, (int) Math.floor(fc));
                double tx = fc - c0;
                double d00 = coarse[r0 * cCols + c0], d01 = coarse[r0 * cCols + c0 + 1];
                double d10 = coarse[(r0 + 1) * cCols + c0], d11 = coarse[(r0 + 1) * cCols + c0 + 1];
                field[j * across + k] = (float) ((d00 * (1 - tx) + d01 * tx) * (1 - ty) + (d10 * (1 - tx) + d11 * tx) * ty);
            }
        }
        return field;
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

        /** Count of known values over rows {@code r0..r1} and columns {@code c0..c1} inclusive. */
        int count(int r0, int c0, int r1, int c1) {
            int a = r0 * across + c0, b = r0 * across + c1 + 1, d = (r1 + 1) * across + c0, e = (r1 + 1) * across + c1 + 1;
            return count[e] - count[b] - count[d] + count[a];
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
            double py = Mercator.row(phi, size) - 0.5;
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
        return Mercator.row(Math.toRadians(latitude), size);
    }

    /** Latitude in degrees of a global pixel row position at a map size. */
    private static double latitude(double row, long size) {
        return Mercator.latitude(Math.max(0, Math.min(size, row)), size);
    }

    private static int wrapWest(int west) { return Math.floorMod(west + 180, 360) - 180; }
}
