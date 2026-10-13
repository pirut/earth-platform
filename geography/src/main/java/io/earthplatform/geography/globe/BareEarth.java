package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Elevation tiles with the ground under towns brought down to the land it stands on.
 *
 * <p>The composite's radar and stereo surveys outside the United States are surface models: they
 * read the tops of buildings and the trees among them, not the ground. In a city that raised the
 * land tens of metres over kilometres (Tokyo's Marunouchi, 3 m above the sea, reads 25 to 37 m),
 * left building-sized lumps and street-sized grooves, set rivers in deep trenches between banks of
 * rooftops, and building shadows the survey read low left pits. As the world keeps no towns, that
 * ground becomes a smooth guess at the land under them.
 *
 * <p>The guess is made once per zoom {@value #ZOOM} tile, with a margin round it, and every zoom
 * from {@value #FROM_ZOOM} up reads the same guess, so cubes and far terrain agree. It takes the
 * land's broad shape (a weighted blur of the land around, water left out), then the low tenth of
 * what stands above or below that shape within {@value #LOW_METRES} m (streets, yards, squares
 * and rail yards, the lowest of the rooftops), and smooths it. Hills and terraces are broader
 * than that reach and keep their shape; buildings and blocks are narrower and go. A town's weight
 * comes from the share of built-up land around, reaching out past its parks and riverside fields,
 * and fades out over a few hundred metres at its edges, where the ground eases back to the
 * tiles'. Wide water keeps its own lower level, so rivers stay; ponds and the shadows mapped as
 * water lie at the ground's level. Away from towns tiles come back unchanged.
 */
public final class BareEarth implements GlobalElevation.Tiles {
    /** What a world pins to read its ground through this. */
    public static final String ID = "bare-earth-v1";
    /** Zoom the ground is worked out at: about 30 m pixels, as fine as most surface surveys. */
    static final int ZOOM = 12;
    /** Coarsest zoom changed; coarser ones are only read for bathymetry and the open sea. */
    static final int FROM_ZOOM = 11;

    /** Reach of the land's broad shape. */
    static final double TREND_METRES = 300;
    /** Reach within which the low share of the ground is found, wider than a city block. */
    static final double LOW_METRES = 250;
    /** Share of the land within that reach at or under the ground. */
    static final double LOW_SHARE = 0.10;
    /**
     * Share of that low ground within the same reach the ground then takes. The low share alone cuts
     * into land broader than the reach where it bends, as a hill's foot or a valley's shoulder; this
     * gives most of it back while leaving buildings, which only the highest of it would.
     */
    static final double OPEN_SHARE = 0.7;
    /** Reach of the smoothing over the ground found. */
    static final double SMOOTH_METRES = 120;
    /** Reach over which the share of built-up land is taken. */
    static final double BUILT_METRES = 200;
    /** How far a town reaches past its built-up land, over its parks and riverside fields. */
    static final double TOWN_METRES = 600;
    /** Reach of the fade at a town's edge. */
    static final double EDGE_METRES = 250;
    /** Built-up share where the ground starts to change, and where it is wholly the guess. */
    static final double BUILT_FROM = 0.25, BUILT_FULL = 0.60;
    /** Reach over which the land's roughness is taken. */
    static final double ROUGH_METRES = 500;
    /** Metres a pixel stands above or below its smoothing a pixel or two across to count as rough. */
    static final float ROUGH_STEP = 0.5f;
    /**
     * Shares of rough land in a town where the tiles start to read rooftops, and where they wholly
     * do: in Tokyo, Sao Paulo and Rio nine tenths of their built-up land has 0.55 to 0.9; in London
     * and New York, surveys of the bare ground, nine tenths has under 0.25 to 0.45, more only along
     * ridges and cliffs such as the Palisades; Paris 0.1 to 0.4.
     */
    static final double ROUGH_FROM = 0.45, ROUGH_FULL = 0.55;
    /**
     * Slopes of the land's broad shape from which towns' ground keeps the tiles' own, wholly so at the
     * second: on steep ground, as Rio's and Hong Kong's hills, the buildings are a small part of the
     * height and the low share of the ground would cut into the hillside.
     */
    static final double STEEP_FROM = 0.10, STEEP_TO = 0.22;
    /** Reach over which the share of water is taken, and the shares where water starts to keep its own level and wholly does. */
    static final double WATER_METRES = 100, WATER_FROM = 0.4, WATER_FULL = 0.7;

    private static final int SIZE = TerrariumTileSource.TILE_SIZE;
    /** Pixels read round a tile; the filters' reach in pixels is held under it. */
    private static final int MARGIN = 128, SPAN = SIZE + 2 * MARGIN;
    /** A field covers its tile and one pixel round it, for interpolation across tile edges. */
    private static final int CORE = SIZE + 2;
    /**
     * Samples across a field, every other pixel of its cover: the ground is smoothed over several
     * pixels, so this loses nothing and keeps a field to a quarter of the memory.
     */
    private static final int GRID = CORE / 2 + 1;
    /** Sampling step, in pixels, of the first look for any built-up land. */
    private static final int SCOUT = 8;
    private static final double EARTH_RADIUS = 6_378_137;

    /** Land cover class at a point, as {@link WorldCoverSource#code} at full detail. */
    @FunctionalInterface
    public interface Cover {
        int code(double latitude, double longitude);
    }

    /**
     * The guessed ground over one zoom {@value #ZOOM} tile and a pixel round it, with how much
     * the tiles give way to it (0 to 255) and how much water there keeps its own lower level.
     */
    record Field(float[] ground, byte[] weight, byte[] water) {}
    /** A tile with no town near it. */
    static final Field NONE = new Field(null, null, null);

    private final GlobalElevation.Tiles tiles;
    private final Cover cover;
    private final int maxFields, maxTiles;
    private final LinkedHashMap<Long, Field> fields = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, CompletableFuture<Field>> fieldsInFlight = new ConcurrentHashMap<>();
    /** Recently read tiles at the zooms changed; those left as they were are the source's own arrays. */
    private final LinkedHashMap<Long, float[]> changed = new LinkedHashMap<>(64, 0.75f, true);

    public BareEarth(TerrariumTileSource tiles, WorldCoverSource cover) {
        this(tiles::tile, (latitude, longitude) -> cover.code(latitude, longitude, 0), 160, 256);
    }

    BareEarth(GlobalElevation.Tiles tiles, Cover cover, int maxFields, int maxTiles) {
        this.tiles = tiles;
        this.cover = cover;
        this.maxFields = maxFields;
        this.maxTiles = maxTiles;
    }

    @Override public float[] tile(int zoom, int x, int y) {
        if (zoom < FROM_ZOOM) return tiles.tile(zoom, x, y);
        long key = (long) zoom << 58 | (long) x << 29 | y;
        synchronized (changed) {
            float[] hit = changed.get(key);
            if (hit != null) return hit;
        }
        float[] source = tiles.tile(zoom, x, y);
        float[] out = apply(source, zoom, x, y);
        // Kept whether or not it changed, so a tile beside a town is looked over only once.
        synchronized (changed) {
            changed.put(key, out);
            var iterator = changed.entrySet().iterator();
            while (changed.size() > maxTiles && iterator.hasNext()) { iterator.next(); iterator.remove(); }
        }
        return out;
    }

    /** The tile with the ground under towns in place of its own, or the tile itself where none lies. */
    private float[] apply(float[] source, int zoom, int x, int y) {
        int across = 1 << ZOOM;
        double scale = Math.scalb(1.0, ZOOM - zoom);
        // The zoom-12 tiles this one spans: one, or for zoom 11 a square of four.
        int span = Math.max(1, (int) Math.ceil(SIZE * scale / SIZE));
        int fx0 = (int) Math.floor(x * SIZE * scale / SIZE), fy0 = (int) Math.floor(y * SIZE * scale / SIZE);
        Field[] near = new Field[span * span];
        boolean any = false;
        for (int j = 0; j < span; j++) for (int i = 0; i < span; i++) {
            Field field = field(Math.floorMod(fx0 + i, across), Math.min(across - 1, fy0 + j));
            near[j * span + i] = field;
            any |= field != NONE;
        }
        if (!any) return source;
        float[] out = source.clone();
        boolean touched = false;
        for (int py = 0; py < SIZE; py++) {
            // Pixel centres in zoom-12 pixels, from the corner of the first field's tile.
            double v = ((double) y * SIZE + py + 0.5) * scale - 0.5 - (double) fy0 * SIZE;
            int fj = Math.min(span - 1, Math.max(0, (int) Math.floor(v / SIZE)));
            double fv = v - fj * SIZE + 1;
            for (int px = 0; px < SIZE; px++) {
                int i = py * SIZE + px;
                float metres = source[i];
                // Open sea is left for the bathymetry.
                if (metres == 0f) continue;
                double u = ((double) x * SIZE + px + 0.5) * scale - 0.5 - (double) fx0 * SIZE;
                int fi = Math.min(span - 1, Math.max(0, (int) Math.floor(u / SIZE)));
                Field field = near[fj * span + fi];
                if (field == NONE) continue;
                double fu = (u - fi * SIZE + 1) / 2, gv = fv / 2;
                int a = Math.min(GRID - 2, Math.max(0, (int) Math.floor(fu))), b = Math.min(GRID - 2, Math.max(0, (int) Math.floor(gv)));
                double tx = Math.min(1, Math.max(0, fu - a)), ty = Math.min(1, Math.max(0, gv - b));
                int at = b * GRID + a;
                double w = lerp(field.weight, at, tx, ty) / 255.0;
                if (w <= 0) continue;
                double ground = lerp(field.ground, at, tx, ty), keep = lerp(field.water, at, tx, ty) / 255.0;
                double target = keep * Math.min(metres, ground) + (1 - keep) * ground;
                float value = (float) (metres + (target - metres) * w);
                out[i] = value == 0f ? Math.ulp(0f) : value;
                touched = true;
            }
        }
        return touched ? out : source;
    }

    private static double lerp(float[] values, int at, double tx, double ty) {
        return (values[at] * (1 - tx) + values[at + 1] * tx) * (1 - ty) + (values[at + GRID] * (1 - tx) + values[at + GRID + 1] * tx) * ty;
    }

    private static double lerp(byte[] values, int at, double tx, double ty) {
        return ((values[at] & 0xFF) * (1 - tx) + (values[at + 1] & 0xFF) * tx) * (1 - ty)
                + ((values[at + GRID] & 0xFF) * (1 - tx) + (values[at + GRID + 1] & 0xFF) * tx) * ty;
    }

    /** The field of a zoom-12 tile, worked out once and shared by threads asking at the same time. */
    Field field(int x, int y) {
        long key = (long) x << 32 | y;
        synchronized (fields) {
            Field hit = fields.get(key);
            if (hit != null) return hit;
        }
        CompletableFuture<Field> mine = new CompletableFuture<>();
        CompletableFuture<Field> shared = fieldsInFlight.putIfAbsent(key, mine);
        if (shared == null) {
            try {
                Field field = compute(x, y);
                synchronized (fields) {
                    fields.put(key, field);
                    var iterator = fields.entrySet().iterator();
                    while (fields.size() > maxFields && iterator.hasNext()) { iterator.next(); iterator.remove(); }
                }
                mine.complete(field);
            } catch (Throwable failure) {
                mine.completeExceptionally(failure);
            } finally {
                fieldsInFlight.remove(key, mine);
            }
            shared = mine;
        }
        try {
            return shared.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MissingTerrainException("Interrupted working out the ground of tile " + ZOOM + "/" + x + "/" + y);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new MissingTerrainException("Ground of tile " + ZOOM + "/" + x + "/" + y + " failed: " + failed.getCause());
        }
    }

    /** Works out a tile's field from the tiles and land cover round it. */
    private Field compute(int tileX, int tileY) {
        long size = (long) SIZE << ZOOM;
        long gx0 = (long) tileX * SIZE - MARGIN, gy0 = (long) tileY * SIZE - MARGIN;
        double[] latitude = new double[SPAN], longitude = new double[SPAN];
        for (int k = 0; k < SPAN; k++) {
            double row = Math.max(0, Math.min(size - 1, gy0 + k)) + 0.5;
            latitude[k] = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * row / size))));
            double column = Math.floorMod(gx0 + k, size) + 0.5;
            longitude[k] = column / size * 360 - 180;
        }
        // A first, sparse look: most of the world has no town within reach.
        int built = 0;
        for (int j = SCOUT / 2; j < SPAN && built < 2; j += SCOUT)
            for (int i = SCOUT / 2; i < SPAN && built < 2; i += SCOUT)
                if (cover.code(latitude[j], longitude[i]) == WorldCoverSource.BUILT_UP) built++;
        if (built < 2) return NONE;

        int n = SPAN * SPAN;
        float[] dem = new float[n];
        byte[] code = new byte[n];
        float[] tile = null;
        long tileKey = -1;
        for (int j = 0; j < SPAN; j++) {
            long gy = Math.max(0, Math.min(size - 1, gy0 + j));
            for (int i = 0; i < SPAN; i++) {
                long gx = Math.floorMod(gx0 + i, size);
                long key = (gx >> 8) << 32 | gy >> 8;
                if (key != tileKey) { tile = tiles.tile(ZOOM, (int) (gx >> 8), (int) (gy >> 8)); tileKey = key; }
                dem[j * SPAN + i] = tile[(int) ((gy & 255) * SIZE + (gx & 255))];
                code[j * SPAN + i] = (byte) cover.code(latitude[j], longitude[i]);
            }
        }
        double metres = 2 * Math.PI * EARTH_RADIUS * Math.cos(Math.toRadians(latitude[SPAN / 2])) / size;
        return compute(dem, code, metres);
    }

    /** Pixels across the half-detail grid the smooth fields are worked out on. */
    private static final int HALF = SPAN / 2;

    /**
     * A field from a window's metres and land cover classes, {@code metres} a pixel across. The
     * land's broad shape and the town and water shares are smooth, so they are worked out at half
     * detail; only the low share of the ground needs every pixel.
     */
    static Field compute(float[] dem, byte[] code, double metres) {
        int n = HALF * HALF;
        float[] land = new float[n], built = new float[n], water = new float[n], height = new float[n];
        boolean[] dry = new boolean[SPAN * SPAN];
        for (int j = 0; j < SPAN; j++) for (int i = 0; i < SPAN; i++) {
            int at = j * SPAN + i, half = (j >> 1) * HALF + (i >> 1), c = code[at] & 0xFF;
            boolean wet = c == WorldCoverSource.WATER || c == WorldCoverSource.NO_DATA || dem[at] == 0f;
            dry[at] = !wet;
            if (wet) { water[half] += 0.25f; continue; }
            land[half] += 0.25f;
            height[half] += 0.25f * dem[at];
            if (c == WorldCoverSource.BUILT_UP) built[half] += 0.25f;
        }
        // How rough the land is a pixel or two across: rooftops, streets and treetops in a surface model, next to
        // nothing in a survey of the bare ground, as most of the United States', England's and the Netherlands' are.
        // A share of rough pixels rather than a mean, so the few along a ridge or an embankment don't count for much.
        float[] smoothed = dem.clone();
        blur(smoothed, SPAN, SPAN, 1);
        float[] rough = new float[n];
        for (int j = 0; j < SPAN; j++) for (int i = 0; i < SPAN; i++) {
            int at = j * SPAN + i;
            if (dry[at] && Math.abs(dem[at] - smoothed[at]) > ROUGH_STEP) rough[(j >> 1) * HALF + (i >> 1)] += 0.25f;
        }
        double coarse = 2 * metres;
        int town = Math.min(24, (int) Math.round(TOWN_METRES / coarse)), edge = reach(EDGE_METRES, coarse);
        // The town's weight: the share of built-up land, out past parks and riverside fields, faded at the edge.
        float[] share = weighted(built, land, HALF, reach(BUILT_METRES, coarse), 0f);
        share = spread(share, HALF, town);
        blur(share, HALF, HALF, edge);
        for (int i = 0; i < n; i++) if (land[i] > 0) rough[i] /= land[i];
        rough = weighted(rough, land, HALF, reach(ROUGH_METRES, coarse), 0f);
        blur(water, HALF, HALF, reach(WATER_METRES, coarse));
        // The land's broad shape, from land alone ({@code height} holds land-weighted sums, as the blur wants).
        for (int i = 0; i < n; i++) if (land[i] > 0) height[i] /= land[i];
        float[] trend = weighted(height, land, HALF, reach(TREND_METRES, coarse), Float.NaN);

        // The low share of the land round each pixel over that shape, where the field and its smoothing reach.
        int low = Math.max(1, Math.min(24, (int) Math.round(LOW_METRES / metres))), smooth = reach(SMOOTH_METRES, metres);
        int from = MARGIN - 1 - 3 * smooth - 2, to = MARGIN - 1 + CORE + 3 * smooth + 2, side = to - from;
        float[] base = new float[side * side];
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
            float t = sample(trend, HALF, (from + i + 0.5) / 2 - 0.5, (from + j + 0.5) / 2 - 0.5);
            base[j * side + i] = Float.isNaN(t) ? dem[(from + j) * SPAN + from + i] : t;
        }
        float[] ground = lowShare(dem, dry, base, from, side, low);
        blur(ground, side, side, smooth);

        float[] g = new float[GRID * GRID];
        byte[] w = new byte[GRID * GRID], keep = new byte[GRID * GRID];
        boolean any = false;
        int offset = MARGIN - 1 - from;
        for (int j = 0; j < GRID; j++) for (int i = 0; i < GRID; i++) {
            int cell = j * GRID + i;
            g[cell] = ground[(2 * j + offset) * side + 2 * i + offset];
            double hx = (MARGIN - 1 + 2 * i + 0.5) / 2 - 0.5, hy = (MARGIN - 1 + 2 * j + 0.5) / 2 - 0.5;
            double weight = step((sample(share, HALF, hx, hy) - BUILT_FROM) / (BUILT_FULL - BUILT_FROM))
                    * step((sample(rough, HALF, hx, hy) - ROUGH_FROM) / (ROUGH_FULL - ROUGH_FROM))
                    * step((STEEP_TO - slope(trend, hx, hy, coarse)) / (STEEP_TO - STEEP_FROM));
            w[cell] = (byte) Math.round(255 * weight);
            keep[cell] = (byte) Math.round(255 * step((sample(water, HALF, hx, hy) - WATER_FROM) / (WATER_FULL - WATER_FROM)));
            any |= w[cell] != 0;
        }
        return any ? new Field(g, w, keep) : NONE;
    }

    /** Slope, rise over run, of the land's broad shape at a half-detail position {@code metres} a pixel across; 0 where it is unknown. */
    private static double slope(float[] trend, double x, double y, double metres) {
        double dx = sample(trend, HALF, x + 1, y) - sample(trend, HALF, x - 1, y), dy = sample(trend, HALF, x, y + 1) - sample(trend, HALF, x, y - 1);
        double slope = Math.hypot(dx, dy) / (2 * metres);
        return Double.isNaN(slope) ? 0 : slope;
    }

    /** Box radius for a three-pass blur reaching about {@code reach} metres. */
    private static int reach(double reach, double metres) { return Math.max(1, Math.min(20, (int) Math.round(reach / metres / 1.7))); }

    private static double step(double t) {
        t = Math.min(1, Math.max(0, t));
        return t * t * (3 - 2 * t);
    }

    /** Bilinear sample of a square grid {@code width} across at a pixel position, clamped at its edges; NaN where a corner is. */
    private static float sample(float[] grid, int width, double x, double y) {
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(width - 1, y));
        int x0 = Math.min(width - 2, (int) x), y0 = Math.min(width - 2, (int) y);
        double fx = x - x0, fy = y - y0;
        int at = y0 * width + x0;
        return (float) ((grid[at] * (1 - fx) + grid[at + 1] * fx) * (1 - fy) + (grid[at + width] * (1 - fx) + grid[at + width + 1] * fx) * fy);
    }

    /**
     * The ground over a {@code side}-pixel square of the window from {@code from}: {@code base}, the
     * land's shape there, plus the {@link #LOW_SHARE} quantile of the dry land's height over it within
     * {@code radius}. A pixel with too little land round it keeps the shape. Worked out every other
     * pixel and filled between, as the blur after it smooths finer steps anyway.
     */
    private static float[] lowShare(float[] dem, boolean[] dry, float[] base, int from, int side, int radius) {
        int stride = radius >= 6 ? 2 : 1;
        float[] scratch = new float[(2 * radius + 1) * (2 * radius + 1)];
        float[] offsets = new float[side * side];
        int least = Math.max(8, scratch.length / 8);
        for (int j = 0; j < side; j += stride) for (int i = 0; i < side; i += stride) {
            int count = 0;
            for (int q = Math.max(0, j - radius); q <= Math.min(side - 1, j + radius); q++) {
                int row = (from + q) * SPAN + from, own = q * side;
                for (int p = Math.max(0, i - radius); p <= Math.min(side - 1, i + radius); p++)
                    if (dry[row + p]) scratch[count++] = dem[row + p] - base[own + p];
            }
            offsets[j * side + i] = count < least ? 0 : select(scratch, count, (int) (LOW_SHARE * (count - 1)));
        }
        fill(offsets, side, stride);
        if (OPEN_SHARE > 0) {
            // Then the high share of that within the same reach, which gives back the shape of land broader than
            // the reach, as a hill's foot or a valley's shoulder, that the low share alone cut into.
            float[] opened = new float[side * side];
            for (int j = 0; j < side; j += stride) for (int i = 0; i < side; i += stride) {
                int count = 0;
                for (int q = Math.max(0, j - radius); q <= Math.min(side - 1, j + radius); q++)
                    for (int p = Math.max(0, i - radius); p <= Math.min(side - 1, i + radius); p++)
                        scratch[count++] = offsets[q * side + p] + base[q * side + p] - base[j * side + i];
                opened[j * side + i] = select(scratch, count, (int) (OPEN_SHARE * (count - 1)));
            }
            fill(opened, side, stride);
            offsets = opened;
        }
        for (int i = 0; i < offsets.length; i++) offsets[i] += base[i];
        return offsets;
    }

    /** Fills a grid worked out every {@code stride} pixels between its known ones. */
    private static void fill(float[] grid, int side, int stride) {
        if (stride != 2) return;
        // Odd columns, then odd rows, from their even neighbours.
        for (int j = 0; j < side; j += 2) for (int i = 1; i < side; i += 2) {
            int at = j * side + i;
            grid[at] = i + 1 < side ? (grid[at - 1] + grid[at + 1]) / 2 : grid[at - 1];
        }
        for (int j = 1; j < side; j += 2) for (int i = 0; i < side; i++) {
            int at = j * side + i;
            grid[at] = j + 1 < side ? (grid[at - side] + grid[at + side]) / 2 : grid[at - side];
        }
    }

    /** The k-th smallest of the first n values, which are reordered. */
    static float select(float[] a, int n, int k) {
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            float pivot = a[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) { float t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else return a[k];
        }
        return a[k];
    }

    /** A three-pass blur of a square grid's {@code values} weighted by {@code weights}; where the weights are all zero, {@code empty}. */
    private static float[] weighted(float[] values, float[] weights, int width, int radius, float empty) {
        float[] num = new float[values.length], den = weights.clone();
        for (int i = 0; i < num.length; i++) num[i] = values[i] * weights[i];
        blur(num, width, width, radius);
        blur(den, width, width, radius);
        for (int i = 0; i < num.length; i++) num[i] = den[i] > 1e-4f ? num[i] / den[i] : empty;
        return num;
    }

    /** Three passes of a box blur of {@code radius} along rows and columns, about a Gaussian; edges are held. */
    static void blur(float[] a, int width, int height, int radius) {
        float[] line = new float[Math.max(width, height)], out = new float[line.length];
        for (int pass = 0; pass < 3; pass++) {
            for (int j = 0; j < height; j++) {
                System.arraycopy(a, j * width, line, 0, width);
                box(line, out, width, radius);
                System.arraycopy(out, 0, a, j * width, width);
            }
            for (int i = 0; i < width; i++) {
                for (int j = 0; j < height; j++) line[j] = a[j * width + i];
                box(line, out, height, radius);
                for (int j = 0; j < height; j++) a[j * width + i] = out[j];
            }
        }
    }

    /** A running box mean of the first {@code n} values, the ends held beyond the line. */
    private static void box(float[] line, float[] out, int n, int radius) {
        float first = line[0], last = line[n - 1], scale = 1f / (2 * radius + 1);
        double sum = 0;
        for (int k = -radius; k <= radius; k++) sum += line[Math.max(0, Math.min(n - 1, k))];
        for (int i = 0; i < n; i++) {
            out[i] = (float) sum * scale;
            int add = i + radius + 1, drop = i - radius;
            sum += (add < n ? line[add] : last) - (drop >= 0 ? line[drop] : first);
        }
    }

    /** The highest value within {@code radius} along rows, then columns, of a square grid. */
    private static float[] spread(float[] a, int width, int radius) {
        float[] rows = new float[a.length], out = new float[a.length];
        for (int j = 0; j < width; j++) for (int i = 0; i < width; i++) {
            float most = 0;
            for (int k = Math.max(0, i - radius); k <= Math.min(width - 1, i + radius); k++) most = Math.max(most, a[j * width + k]);
            rows[j * width + i] = most;
        }
        for (int i = 0; i < width; i++) for (int j = 0; j < width; j++) {
            float most = 0;
            for (int k = Math.max(0, j - radius); k <= Math.min(width - 1, j + radius); k++) most = Math.max(most, rows[k * width + i]);
            out[j * width + i] = most;
        }
        return out;
    }
}
