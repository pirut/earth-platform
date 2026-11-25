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
        final double[] wd = new double[3];
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
    /** Stronger threshold for negative regional datum shifts so tall boreal forest canopy under bare-earth lidar never triggers a false negative datum. */
    private static final double NEGATIVE_OFFSET_AGREE = 22, NEGATIVE_OFFSET_DISAGREE = 30;
    /** Polar latitude taper bounds (degrees) reaching 1.0 right at SRTM's orthometric 56°S..60°N boundaries. */
    private static final double DATUM_NORTH_FROM = 59.0, DATUM_NORTH_TO = 60.0;
    private static final double DATUM_SOUTH_FROM = 55.0, DATUM_SOUTH_TO = 56.0;
    /**
     * Global geographic lattice for regional datum estimation in {@value #GEOID_ID}: {@value #DATUM_GRID} nodes per
     * degree (30 arcseconds, ~926 m in latitude) so every 1° square border and 0.25° part border lies on exact lattice
     * nodes regardless of latitude-band column width changes. Each node evaluates a canonical
     * {@code (2 * DATUM_SAMPLE_RADIUS + 1)} square of samples at {@value #DATUM_SAMPLE_GRID} samples per degree
     * (±1 arcminute, ~1.85 km radius) depending solely on geographic coordinates.
     */
    static final int DATUM_GRID = 120;
    private static final int DATUM_SAMPLE_SUB = 2;
    private static final int DATUM_SAMPLE_GRID = DATUM_GRID * DATUM_SAMPLE_SUB;
    private static final int DATUM_SAMPLE_RADIUS = 4;
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
     * Smooth signed regional datum offset on the shared global geographic lattice ({@value #DATUM_GRID} nodes/degree),
     * covering integer lattice indices {@code gy0 .. gy0 + rows - 1} in latitude and {@code gx0 .. gx0 + cols - 1}
     * in longitude. Adjacent parts and degree squares evaluate shared nodes identically and taper to 0 at missing
     * Copernicus coverage boundaries.
     */
    record DatumGrid(int gy0, int gx0, int rows, int cols, float[] values) {
        double at(double latitude, double longitude) {
            double fy = Math.max(0, Math.min(rows - 1.0, latitude * DATUM_GRID - gy0));
            int r0 = Math.min(rows - 2, (int) Math.floor(fy));
            double ty = fy - r0;
            double fx = Math.max(0, Math.min(cols - 1.0, longitude * DATUM_GRID - gx0));
            int c0 = Math.min(cols - 2, (int) Math.floor(fx));
            double tx = fx - c0;
            double d00 = values[r0 * cols + c0], d01 = values[r0 * cols + c0 + 1];
            double d10 = values[(r0 + 1) * cols + c0], d11 = values[(r0 + 1) * cols + c0 + 1];
            return (d00 * (1 - tx) + d01 * tx) * (1 - ty) + (d10 * (1 - tx) + d11 * tx) * ty;
        }
    }

    /**
     * One part of a degree square's weights (and optional offshore-void weights and regional datum lattice in
     * {@value #GEOID_ID}) at the check overview: rows {@code row0} on and columns {@code column0} on of a square
     * {@code width} x {@code height} check pixels, row-major from the north; null where all are 0.
     */
    record Part(int width, int height, int row0, int column0, int rows, int columns,
                byte[] weights, byte[] offshoreVoid, DatumGrid datum) {
        static final Part NONE = new Part(0, 0, 0, 0, 0, 0, null, null, null);
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
        if (!any(Math.max(-89.9999, south - margin), Math.min(89.9999, north + margin), west - margin, east + margin, geoid)) return;
        double pixel = EARTH_CIRCUMFERENCE * Math.cos(Math.toRadians((north + south) / 2)) / size;
        int overview = (int) Math.max(0, Math.min(CopernicusSource.MAX_OVERVIEW, Math.floor(Math.log(pixel / COPERNICUS_METRES) / Math.log(2))));
        List<Runnable> reads = new ArrayList<>();
        for (double latitude : new double[] {north, (north + south) / 2, south})
            for (double longitude : new double[] {west, (west + east) / 2, east})
                reads.add(() -> {
                    double at = Math.max(-89.9999, Math.min(89.9999, latitude));
                    // Only blocks apply reads: where a weight, offshore void, or regional datum stands.
                    double[] wd = new double[3];
                    sample(at, longitude, null, wd);
                    if (wd[0] > 0 || wd[2] > 0 || (geoid && wd[1] != 0)) dem.metres(at, longitude, overview);
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
        double[] wd = new double[3];
        for (int py = 0; py < TerrariumTileSource.TILE_SIZE; py++) {
            double latitude = latitude(tileY * 256L + py + 0.5, size);
            for (int px = 0; px < TerrariumTileSource.TILE_SIZE; px++) {
                int i = py * TerrariumTileSource.TILE_SIZE + px;
                float t = values[i];
                if (t == 0f) continue;
                if (t < TileRepair.IMPOSSIBLE_DEPTH) {
                    double c = dem.metres(latitude, longitude[px], overview);
                    if (Double.isNaN(c)) continue;
                    // Overviews average the sea's zeros into crumbs of a picometre.
                    if (Math.abs(c) < SEA_CRUMB) c = 0;
                    values[i] = (float) c;
                    continue;
                }
                sample(latitude, longitude[px], memo, wd);
                double weight = wd[0], datum = wd[1], voidWeight = wd[2];
                if (t < 0f) {
                    if (weight <= 0 && voidWeight <= 0) continue;
                    double c = dem.metres(latitude, longitude[px], overview);
                    boolean sea = Double.isNaN(c) || Math.abs(c) < SEA_CRUMB;
                    if (sea) c = 0;
                    // Over confirmed sea (or missing open-ocean square), only confirmed -32768 void ramps blend to 0;
                    // over fine Copernicus land, carry both land weight and confirmed void-ramp evidence.
                    double w = sea ? voidWeight : Math.max(weight, voidWeight);
                    if (w <= 0) continue;
                    float v = (float) (t + w * (c - t));
                    if (v == 0f && !(c == 0 && w >= 1)) v = Math.ulp(0f);
                    values[i] = v;
                    continue;
                }
                if (weight <= 0 && datum == 0) continue;
                double base = t - datum;
                boolean seaCandidate = Math.abs(datum) >= 1.0 && (base <= FJORD_PIXEL_MIN || (datum < 0 && t <= FJORD_PIXEL_MIN));
                if (weight <= 0 && !seaCandidate && base > 0) {
                    float v = (float) base;
                    values[i] = v == 0f ? Math.ulp(0f) : v;
                    continue;
                }
                double c = dem.metres(latitude, longitude[px], overview);
                if (!Double.isNaN(c) && Math.abs(c) < SEA_CRUMB) c = 0;
                if (Double.isNaN(c) || c == 0) {
                    // Over confirmed sea (or open-ocean missing square), emit exact 0f rather than carving negative
                    // trenches or leaving 0..5 m ArcticDEM sea shelves.
                    if (seaCandidate || base <= 0) {
                        values[i] = 0f;
                        continue;
                    }
                    double clampedBase = Math.max(0.0, base);
                    if (Double.isNaN(c)) {
                        float v = (float) clampedBase;
                        values[i] = v == 0f ? Math.ulp(0f) : v;
                        continue;
                    }
                    float v = weight > 0 ? (float) (clampedBase * (1.0 - weight)) : (float) clampedBase;
                    if (v == 0f && weight < 1) v = Math.ulp(0f);
                    values[i] = v;
                    continue;
                }
                if (c > SEA_CRUMB) {
                    // Never turn or flatten Copernicus-confirmed coastal land when datum over-subtracts below sea level.
                    if (base <= 0) base = Math.max(LAND_FLOOR, c);
                } else {
                    // Genuine below-sea-level land depression (e.g. Death Valley, Netherlands): never dig below Copernicus.
                    base = Math.max(c, base);
                }
                float v = weight > 0 ? (float) (base + weight * (c - base)) : (float) base;
                if (v == 0f) v = Math.ulp(0f);
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
                        if (p.weights != null || p.offshoreVoid != null || (includeDatum && p.datum != null)) return true;
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
        double[] out = new double[3];
        sample(latitude, longitude, null, out);
        return out[1];
    }

    private double weight(double latitude, double longitude, Memo memo) {
        double[] out = memo != null ? memo.wd : new double[3];
        sample(latitude, longitude, memo, out);
        return out[0];
    }

    /**
     * Samples bilinear weight ({@code out[0]}), smooth signed regional datum offset ({@code out[1]}), and
     * offshore coastal void-ramp weight ({@code out[2]}) at a point.
     */
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
        if (dims == null) { out[0] = 0; out[1] = 0; out[2] = 0; return; }
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
        double w00 = atByte(here, south, west, y0, x0, false), w01 = atByte(here, south, west, y0, x0 + 1, false);
        double w10 = atByte(here, south, west, y0 + 1, x0, false), w11 = atByte(here, south, west, y0 + 1, x0 + 1, false);
        out[0] = ((w00 * (1 - tx) + w01 * tx) * (1 - ty) + (w10 * (1 - tx) + w11 * tx) * ty) / 255;
        if (!geoid) {
            out[1] = 0;
            out[2] = 0;
            return;
        }
        out[1] = here.datum == null ? 0 : here.datum.at(latitude, longitude);
        if (here.offshoreVoid == null
                && y0 >= here.row0 && y0 + 1 < here.row0 + here.rows
                && x0 >= here.column0 && x0 + 1 < here.column0 + here.columns) {
            out[2] = 0;
        } else {
            double v00 = atByte(here, south, west, y0, x0, true), v01 = atByte(here, south, west, y0, x0 + 1, true);
            double v10 = atByte(here, south, west, y0 + 1, x0, true), v11 = atByte(here, south, west, y0 + 1, x0 + 1, true);
            out[2] = ((v00 * (1 - tx) + v01 * tx) * (1 - ty) + (v10 * (1 - tx) + v11 * tx) * ty) / 255;
        }
    }

    /**
     * A check pixel's weight (or offshore coastal void-ramp weight when {@code offshoreVoid}) of a square by row and
     * column, from the part given when it holds it, else the part that does; either may lie past the square's edge:
     * then the nearest pixel there, or 0 in an open-ocean square with no Copernicus file.
     */
    private double atByte(Part here, int south, int west, int row, int column, boolean offshoreVoid) {
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
        byte[] table = offshoreVoid ? here.offshoreVoid : here.weights;
        return table == null ? 0 : table[(row - here.row0) * here.columns + column - here.column0] & 0xFF;
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
        int m = WIDE, across = columns + 2 * m, down = rows + 2 * m;
        // The difference, Copernicus and Terrarium over the part and a margin round it, NaN where unknown.
        float[] difference = new float[across * down], copernicus = new float[across * down], raw = new float[across * down];
        // How far Terrarium lies under Copernicus on snow and ice, check pixel by check pixel; 0 elsewhere.
        float[] sunk = new float[across * down];
        // Canonical geographic datum sampling grid covering the full WIDE-padded box (geoid mode outside SRTM only).
        int gyMin = 0, gyMax = 0, gxMin = 0, gxMax = 0;
        int syMin = 0, syMax = 0, sxMin = 0, sxMax = 0, sRows = 0, sCols = 0;
        float[] canonDiff = null;
        boolean sampleDatum = false;
        if (geoid) {
            double padNorth = south + 1.0 - (row0 - m + 0.5) / height;
            double padSouth = south + 1.0 - (row0 + down - 1 - m + 0.5) / height;
            double padWest = west + (column0 - m + 0.5) / width;
            double padEast = west + (column0 + across - 1 - m + 0.5) / width;
            gyMin = (int) Math.floor(padSouth * DATUM_GRID);
            gyMax = (int) Math.floor(padNorth * DATUM_GRID) + 1;
            gxMin = (int) Math.floor(padWest * DATUM_GRID);
            gxMax = (int) Math.floor(padEast * DATUM_GRID) + 1;
            // Fast path: skip canonical datum sampling completely when every lattice node lies within the zero-taper SRTM interior [-55.0°, +59.0°].
            sampleDatum = gyMax > DATUM_NORTH_FROM * DATUM_GRID || gyMin < -DATUM_SOUTH_FROM * DATUM_GRID;
            if (sampleDatum) {
                syMin = gyMin * DATUM_SAMPLE_SUB - DATUM_SAMPLE_RADIUS;
                syMax = gyMax * DATUM_SAMPLE_SUB + DATUM_SAMPLE_RADIUS;
                sxMin = gxMin * DATUM_SAMPLE_SUB - DATUM_SAMPLE_RADIUS;
                sxMax = gxMax * DATUM_SAMPLE_SUB + DATUM_SAMPLE_RADIUS;
                sRows = syMax - syMin + 1;
                sCols = sxMax - sxMin + 1;
                canonDiff = new float[sRows * sCols];
            }
        }
        // Bands of rows read at once: on a first visit each reads its own tiles and blocks from the network, which
        // one after another kept a place's first tiles waiting seconds on this check.
        int bands = Math.min(CHECK_BANDS, down);
        List<Runnable> reads = new ArrayList<>(bands + (sampleDatum ? CHECK_BANDS : 0));
        // The tiles under the part load beside Copernicus's block rather than after it; a failure is left for the bands to meet.
        long size = (long) TerrariumTileSource.TILE_SIZE << CHECK_ZOOM;
        double topLat = south + 1 - (row0 - m - 1.0) / height;
        double bottomLat = south + 1 - (row0 + down - m + 1.0) / height;
        double leftLon = west + (column0 - m - 1.0) / width;
        double rightLon = west + (column0 + across - m + 1.0) / width;
        if (sampleDatum) {
            topLat = Math.max(topLat, (double) syMax / DATUM_SAMPLE_GRID);
            bottomLat = Math.min(bottomLat, (double) syMin / DATUM_SAMPLE_GRID);
            leftLon = Math.min(leftLon, (double) sxMin / DATUM_SAMPLE_GRID);
            rightLon = Math.max(rightLon, (double) sxMax / DATUM_SAMPLE_GRID);
        }
        double top = Math.min(85.0511, topLat), bottom = Math.max(-85.0511, bottomLat);
        int tileTop = (int) (mercatorY(top, size) / TerrariumTileSource.TILE_SIZE), tileBottom = (int) (mercatorY(bottom, size) / TerrariumTileSource.TILE_SIZE);
        int tileLeft = (int) Math.floor((leftLon + 180) / 360 * (1 << CHECK_ZOOM)), tileRight = (int) Math.floor((rightLon + 180) / 360 * (1 << CHECK_ZOOM));
        for (int ty = Math.max(0, tileTop); ty <= Math.min((1 << CHECK_ZOOM) - 1, tileBottom); ty++)
            for (int tx = tileLeft; tx <= tileRight; tx++) {
                int x = Math.floorMod(tx, 1 << CHECK_ZOOM), y = ty;
                reads.add(() -> { try { tiles.tile(CHECK_ZOOM, x, y); } catch (RuntimeException missing) { /* the bands meet it */ } });
            }
        // And Copernicus under the window's corners and middle, so a margin that reaches into the next degree square
        // has that square's header and block on their way from the start, not from the row that first meets them.
        for (double lat : new double[] {topLat, 0.5 * (topLat + bottomLat), bottomLat})
            for (double lon : new double[] {leftLon, 0.5 * (leftLon + rightLon), rightLon}) {
                if (Math.abs(lat) >= 90) continue;
                reads.add(() -> { try { dem.metres(lat, lon, CHECK_OVERVIEW); } catch (RuntimeException missing) { /* the bands meet it */ } });
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
                        }
                        // Only query WorldCover where Terrarium already sits significantly under Copernicus (same as v2).
                        if (t != 0 && (t > 0 || cop > SEA_CRUMB) && cop - t > AGREE && ice != null && ice.at(latitude, longitude))
                            sunk[i] = (float) (cop - t);
                    }
                }
            });
        }
        if (sampleDatum) {
            final float[] canon = canonDiff;
            final int fSyMin = syMin, fSxMin = sxMin, fSRows = sRows, fSCols = sCols;
            int sBands = Math.min(CHECK_BANDS, fSRows);
            for (int b = 0; b < sBands; b++) {
                int from = b * fSRows / sBands, to = (b + 1) * fSRows / sBands;
                reads.add(() -> {
                    Terrarium terrarium = new Terrarium();
                    for (int sr = from; sr < to; sr++) {
                        double lat = (fSyMin + sr) / (double) DATUM_SAMPLE_GRID;
                        int rowOff = sr * fSCols;
                        if (Math.abs(lat) >= 90) {
                            Arrays.fill(canon, rowOff, rowOff + fSCols, Float.NaN);
                            continue;
                        }
                        for (int sc = 0; sc < fSCols; sc++) {
                            canon[rowOff + sc] = Float.NaN;
                            double lon = CopernicusSource.wrap((fSxMin + sc) / (double) DATUM_SAMPLE_GRID);
                            double cop = dem.metres(lat, lon, CHECK_OVERVIEW);
                            if (Double.isNaN(cop) || !(cop > SEA_CRUMB)) continue;
                            double t = terrarium.metres(lat, lon);
                            if (!(t > 0)) continue;
                            double d = t - cop;
                            if (Math.abs(d) > MAX_DATUM_OFFSET) continue;
                            canon[rowOff + sc] = (float) d;
                        }
                    }
                });
            }
        }
        RangeFiles.all(reads);
        DatumGrid fullDatum = null, datumOut = null;
        Window window, landWindow = null, seaWindow = null, seaPosWindow = null;
        if (geoid) {
            if (sampleDatum) fullDatum = estimateDatum(canonDiff, syMin, sxMin, sCols, gyMin, gyMax, gxMin, gxMax);
            if (fullDatum != null) {
                double partNorth = south + 1.0 - (double) row0 / height;
                double partSouth = south + 1.0 - (double) (row0 + rows) / height;
                double partWest = west + (double) column0 / width;
                double partEast = west + (double) (column0 + columns) / width;
                int pGyMin = Math.max(gyMin, (int) Math.floor((partSouth - 1.0 / height) * DATUM_GRID));
                int pGyMax = Math.min(gyMax, (int) Math.floor((partNorth + 1.0 / height) * DATUM_GRID) + 1);
                int pGxMin = Math.max(gxMin, (int) Math.floor((partWest - 1.0 / width) * DATUM_GRID));
                int pGxMax = Math.min(gxMax, (int) Math.floor((partEast + 1.0 / width) * DATUM_GRID) + 1);
                int pRows = pGyMax - pGyMin + 1, pCols = pGxMax - pGxMin + 1;
                float[] sub = new float[pRows * pCols];
                boolean anyPartDatum = false;
                for (int gr = 0; gr < pRows; gr++) {
                    int srcOff = (pGyMin + gr - fullDatum.gy0) * fullDatum.cols + (pGxMin - fullDatum.gx0);
                    int dstOff = gr * pCols;
                    for (int gc = 0; gc < pCols; gc++) {
                        float v = fullDatum.values[srcOff + gc];
                        sub[dstOff + gc] = v;
                        if (v != 0f) anyPartDatum = true;
                    }
                }
                if (anyPartDatum) datumOut = new DatumGrid(pGyMin, pGxMin, pRows, pCols, sub);
            }
            float[] adjLandDiff = new float[across * down];
            float[] seaDiff = new float[across * down];
            float[] seaPos = new float[across * down];
            for (int r = 0; r < down; r++) {
                double lat = south + 1.0 - (row0 + r - m + 0.5) / height;
                int rowOff = r * across;
                for (int c = 0; c < across; c++) {
                    int i = rowOff + c;
                    float cop = copernicus[i];
                    if (cop != cop) {
                        adjLandDiff[i] = seaDiff[i] = seaPos[i] = Float.NaN;
                        continue;
                    }
                    float t = raw[i];
                    double lon = west + (column0 + c - m + 0.5) / width;
                    double d = fullDatum != null ? fullDatum.at(lat, lon) : 0;
                    double tAdj = t > 0f ? t - d : t;
                    if (t > 0f) difference[i] = (float) (tAdj - cop);
                    else if (Math.abs(cop) <= SEA_CRUMB) difference[i] = 0f;
                    adjLandDiff[i] = t > 0f && cop > SEA_CRUMB ? (float) (tAdj - cop) : Float.NaN;
                    if (Math.abs(cop) <= SEA_CRUMB) {
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
                    if (fullDatum != null) {
                        if (t != 0f && (t > 0f || cop > SEA_CRUMB) && cop - tAdj > AGREE && ice != null) {
                            if (sunk[i] > 0f) {
                                // Loop 1 already confirmed WorldCover snow/ice.
                                sunk[i] = (float) (cop - tAdj);
                            } else if (cop - t <= AGREE && Math.abs(lat) < 90 && ice.at(lat, lon)) {
                                // Only query WorldCover where positive datum subtraction uncovered a sunk glacier pit.
                                sunk[i] = (float) (cop - tAdj);
                            }
                        } else {
                            sunk[i] = 0f;
                        }
                    }
                }
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
        byte[] offshoreVoid = null;
        boolean any = false, anyOffshoreVoid = false;
        for (int r = 0; r < rows; r++) {
            double lat = south + 1.0 - (row0 + r + 0.5) / height;
            for (int c = 0; c < columns; c++) {
                int pr = r + m, pc = c + m;
                float rawT = raw[pr * across + pc], rawCop = copernicus[pr * across + pc];
                boolean negOverSea = geoid && rawT < 0f && (rawCop != rawCop || Math.abs(rawCop) <= SEA_CRUMB);
                double weight = 0;
                if (!negOverSea) {
                    double offset = (geoid ? landWindow : window).mean(pr - WIDE, pc - WIDE, pr + WIDE, pc + WIDE);
                    weight = ramp(offset, OFFSET_AGREE, OFFSET_DISAGREE);
                    double localDatum = 0;
                    if (geoid && fullDatum != null) {
                        double lon = west + (column0 + c + 0.5) / width;
                        localDatum = fullDatum.at(lat, lon);
                        if (localDatum != 0 && weight < 1) {
                            weight = Math.max(weight, ramp(-offset, OFFSET_AGREE, OFFSET_DISAGREE));
                        }
                    }
                    if (weight < 1) {
                        double fill = window.mean(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH);
                        double negLand = 0;
                        if (geoid && localDatum != 0) {
                            negLand = -landWindow.mean(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH);
                            if (localDatum > 0 && rawT > 0f && rawCop > SEA_CRUMB) {
                                double overSub = Math.min(localDatum, rawCop - (rawT - localDatum));
                                negLand = Math.max(negLand, overSub);
                            }
                        }
                        if (fill > AGREE || negLand > AGREE) {
                            float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
                            for (int j = pr - SMOOTH; j <= pr + SMOOTH; j++) for (int k = pc - SMOOTH; k <= pc + SMOOTH; k++) {
                                float v = copernicus[j * across + k];
                                if (v == v) { low = Math.min(low, v); high = Math.max(high, v); }
                            }
                            double allow = high >= low ? RELIEF_SHARE * (high - low) : 0;
                            if (fill > AGREE) weight = Math.max(weight, ramp(fill, AGREE + allow, DISAGREE + allow));
                            if (negLand > AGREE) weight = Math.max(weight, ramp(negLand, AGREE + allow, DISAGREE + allow));
                        }
                        if (geoid && weight < 1
                                && seaWindow.count(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH) >= FJORD_MIN_SEA_PIXELS
                                && seaPosWindow.mean(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH) >= FJORD_POS_SHARE) {
                            double seaFill = seaWindow.mean(pr - SMOOTH, pc - SMOOTH, pr + SMOOTH, pc + SMOOTH);
                            weight = Math.max(weight, ramp(seaFill, FJORD_AGREE, FJORD_DISAGREE));
                        }
                        weight = Math.max(weight, ramp(sunk[pr * across + pc], AGREE, DISAGREE));
                    }
                }
                double voidWeight = 0;
                if (!geoid) {
                    if (weight < 1) {
                        float t = rawT, cop = rawCop;
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
                } else if (rawT < 0f) {
                    float t = rawT, cop = rawCop;
                    if (t < TileRepair.IMPOSSIBLE_DEPTH) {
                        voidWeight = 1;
                        if (!negOverSea) weight = 1;
                    } else {
                        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
                        boolean nearImpossible = false, nearLandVoid = false;
                        for (int j = pr - SMOOTH; j <= pr + SMOOTH; j++) for (int k = pc - SMOOTH; k <= pc + SMOOTH; k++) {
                            int n = j * across + k;
                            float cv = copernicus[n];
                            if (cv == cv) {
                                low = Math.min(low, cv);
                                high = Math.max(high, cv);
                            }
                            if (raw[n] < TileRepair.IMPOSSIBLE_DEPTH) nearImpossible = true;
                            if (landVoid != null && landVoid[n]) nearLandVoid = true;
                        }
                        if (nearImpossible) {
                            // Only independent void-sentinel evidence may clear fine-resolution sea pixels.
                            voidWeight = 1;
                            if (!negOverSea) weight = 1;
                        } else if (nearLandVoid && t < VOID_SHALLOW) {
                            // Retain land-void mismatch heuristic for fine Copernicus land repair, never for fine sea.
                            weight = Math.max(weight, ramp(-t, -VOID_SHALLOW, -2 * VOID_SHALLOW));
                        }
                        if (cop > SEA_CRUMB && weight < 1) {
                            double allow = high >= low ? RELIEF_SHARE * (high - low) : 0;
                            weight = Math.max(weight, ramp(cop - t, AGREE + allow, DISAGREE + allow));
                        }
                    }
                }
                int quantised = (int) Math.round(weight * 255);
                weights[r * columns + c] = (byte) quantised;
                any |= quantised > 0;
                if (voidWeight > 0) {
                    int qv = (int) Math.round(voidWeight * 255);
                    if (qv > 0) {
                        if (offshoreVoid == null) offshoreVoid = new byte[rows * columns];
                        offshoreVoid[r * columns + c] = (byte) qv;
                        anyOffshoreVoid = true;
                    }
                }
            }
        }
        return new Part(width, height, row0, column0, rows, columns, any ? weights : null, anyOffshoreVoid ? offshoreVoid : null, datumOut);
    }

    /**
     * Estimates a smooth, signed coherent regional datum offset on the shared global geographic lattice
     * ({@value #DATUM_GRID} nodes/degree) from canonical land-to-land samples ({@code canonDiff}). Because every
     * node's coordinates and sampling window depend solely on global lattice indices, adjacent 0.25° parts and
     * 1° degree squares (even across latitude-band column width changes) agree identically at shared nodes, and
     * nodes touching a missing open-ocean Copernicus square evaluate to 0 so datum tapers smoothly to 0 at
     * coverage boundaries.
     */
    private DatumGrid estimateDatum(float[] canonDiff, int syMin, int sxMin, int sCols,
                                    int gyMin, int gyMax, int gxMin, int gxMax) {
        // Conservative early exit: a node with n >= MIN_LAND_SAMPLES and positive median > OFFSET_AGREE
        // (or negative median < -NEGATIVE_OFFSET_AGREE) requires at least (MIN_LAND_SAMPLES + 1) / 2 such samples.
        int minTrigger = (MIN_LAND_SAMPLES + 1) / 2;
        int shifted = 0;
        for (float d : canonDiff) {
            if ((d > OFFSET_AGREE || d < -NEGATIVE_OFFSET_AGREE) && ++shifted >= minTrigger) break;
        }
        if (shifted < minTrigger) return null;
        int minS = Math.max(-90, Math.floorDiv(gyMin - 1, DATUM_GRID));
        int maxS = Math.min(89, Math.floorDiv(gyMax, DATUM_GRID));
        int minW = Math.floorDiv(gxMin - 1, DATUM_GRID);
        int maxW = Math.floorDiv(gxMax, DATUM_GRID);
        int covRows = Math.max(0, maxS - minS + 1), covCols = Math.max(0, maxW - minW + 1);
        byte[] coverState = null;
        int cRows = gyMax - gyMin + 1;
        int cCols = gxMax - gxMin + 1;
        float[] coarse = new float[cRows * cCols];
        int maxWindow = (2 * DATUM_SAMPLE_RADIUS + 1) * (2 * DATUM_SAMPLE_RADIUS + 1);
        float[] sample = new float[maxWindow], dev = new float[maxWindow];
        boolean anyDatum = false;
        for (int cr = 0; cr < cRows; cr++) {
            int gy = gyMin + cr;
            double nodeLat = gy / (double) DATUM_GRID;
            double latBand = Math.max(ramp(nodeLat, DATUM_NORTH_FROM, DATUM_NORTH_TO), ramp(-nodeLat, DATUM_SOUTH_FROM, DATUM_SOUTH_TO));
            if (latBand == 0) continue;
            int scy = gy * DATUM_SAMPLE_SUB - syMin;
            for (int cc = 0; cc < cCols; cc++) {
                int gx = gxMin + cc;
                int scx = gx * DATUM_SAMPLE_SUB - sxMin;
                int n = 0;
                for (int j = scy - DATUM_SAMPLE_RADIUS; j <= scy + DATUM_SAMPLE_RADIUS; j++) {
                    int off = j * sCols;
                    for (int k = scx - DATUM_SAMPLE_RADIUS; k <= scx + DATUM_SAMPLE_RADIUS; k++) {
                        float d = canonDiff[off + k];
                        if (d == d) sample[n++] = d;
                    }
                }
                if (n < MIN_LAND_SAMPLES) continue;
                Arrays.sort(sample, 0, n);
                double median = (n & 1) == 1 ? sample[n / 2] : 0.5 * (sample[n / 2 - 1] + sample[n / 2]);
                double absMedian = Math.abs(median);
                double minAgree = median > 0 ? OFFSET_AGREE : NEGATIVE_OFFSET_AGREE;
                double maxDisagree = median > 0 ? OFFSET_DISAGREE : NEGATIVE_OFFSET_DISAGREE;
                if (absMedian <= minAgree) continue;
                int q1 = n / 4, q3 = n - q1;
                // Reject windows mixing strong opposing signs across the interquartile range.
                if (median > 0 ? sample[q1] < -OFFSET_AGREE : sample[q3 - 1] > OFFSET_AGREE) continue;
                double sum = 0;
                for (int k = q1; k < q3; k++) sum += sample[k];
                double trimmed = sum / (q3 - q1);
                if (Math.signum(trimmed) != Math.signum(median)) continue;
                double absTrimmed = Math.abs(trimmed);
                if (absTrimmed <= minAgree) continue;
                for (int k = 0; k < n; k++) dev[k] = (float) Math.abs(sample[k] - median);
                Arrays.sort(dev, 0, n);
                double mad = (n & 1) == 1 ? dev[n / 2] : 0.5 * (dev[n / 2 - 1] + dev[n / 2]);
                // Semi-IQR catches bimodal step boundaries (>50% shifted, ~40% aligned) where MAD alone collapses to 0.
                double spread = Math.max(mad, 0.5 * (sample[q3 - 1] - sample[q1]));
                double offsetWeight = ramp(absTrimmed, minAgree, maxDisagree);
                double madAllow = Math.max(DATUM_MAD_MIN, DATUM_MAD_SHARE * absTrimmed);
                double madReject = Math.max(DATUM_MAD_MAX, DATUM_MAD_REJECT_SHARE * absTrimmed);
                double coherence = 1.0 - ramp(spread, madAllow, madReject);
                float nodeDatum = (float) (trimmed * offsetWeight * coherence * latBand);
                if (nodeDatum != 0f) {
                    if (coverState == null) coverState = new byte[covRows * covCols];
                    if (!nodeCovered(gy, gx, minS, maxS, minW, covCols, coverState)) continue;
                    coarse[cr * cCols + cc] = nodeDatum;
                    anyDatum = true;
                }
            }
        }
        return anyDatum ? new DatumGrid(gyMin, gxMin, cRows, cCols, coarse) : null;
    }

    /** Whether every Copernicus 1° square touching global lattice node {@code (gy, gx)} has coverage, queried lazily. */
    private boolean nodeCovered(int gy, int gx, int minS, int maxS, int minW, int covCols, byte[] coverState) {
        int s0 = Math.floorDiv(gy, DATUM_GRID);
        int sFrom = Math.floorDiv(gy - 1, DATUM_GRID);
        int w0 = Math.floorDiv(gx, DATUM_GRID);
        int wFrom = Math.floorDiv(gx - 1, DATUM_GRID);
        for (int s = sFrom; s <= s0; s++) {
            if (s < -90 || s > 89) continue;
            if (s < minS || s > maxS) return false;
            for (int w = wFrom; w <= w0; w++) {
                int idx = (s - minS) * covCols + (w - minW);
                byte state = coverState[idx];
                if (state == 0) {
                    state = (byte) (dem.covers(s + 0.5, wrapWest(w) + 0.5) ? 1 : -1);
                    coverState[idx] = state;
                }
                if (state < 0) return false;
            }
        }
        return true;
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
