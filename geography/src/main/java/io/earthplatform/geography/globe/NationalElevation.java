package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * High-resolution national elevation, dataset id {@code mapterhorn-v1}: an {@link ElevationLayer}
 * that lays open national terrain models (swissALTI3D, Austrian and German ALS models, IGN RGE ALTI
 * and LiDAR HD, Kartverket, the Environment Agency, USGS 3DEP 1 m and well over a hundred more) over
 * the Terrarium composite wherever one exists.
 *
 * <p>The global composites round off sheer peaks: radar can't see into the shadow behind a spire, and
 * the gaps are filled from coarser models, so Tre Cime reads about 200 m low and the Matterhorn and
 * Dru about 130 m. Airborne lidar and photogrammetry have no such shadow. The data comes from
 * <a href="https://mapterhorn.com">Mapterhorn</a>, which reprojects those models onto the same Web
 * Mercator grid in Terrarium encoding: 512-pixel lossless WebP tiles, so its zoom {@code m} tile holds
 * exactly the pixels of the four 256-pixel tiles at zoom {@code m + 1}. Its tiles at zoom
 * {@value #COVERAGE_ZOOM} and finer exist only where a national model does, which is how this layer
 * knows where to apply: which of them exist comes from each region's PMTiles directory, read once.
 *
 * <p>Inside that coverage the national values replace the composite's, faded in over
 * {@value #FEATHER_METRES} m from its edge so borders join smoothly. Open-sea zeros stay as they are,
 * as do pixels the national data leaves empty. Where the national data is coarser than the zoom asked
 * for, its finest tile is interpolated. Downloads are cached on disk, so a place regenerates from the
 * same bytes offline.
 */
public final class NationalElevation implements ElevationLayer, AutoCloseable {
    public static final String DATASET_ID = "mapterhorn-v1";
    public static final URI MAPTERHORN_TILES = URI.create("https://tiles.mapterhorn.com/");
    public static final URI MAPTERHORN_DOWNLOADS = URI.create("https://download.mapterhorn.com/");
    /** Mapterhorn tiles at this zoom and finer exist only where a national model does. */
    static final int COVERAGE_ZOOM = 13;
    /** Zoom of the regional archives listed in the catalogue; each holds one tile of this zoom. */
    static final int REGION_ZOOM = 6;
    /** Below this our tiles span several regions, and a pixel is far coarser than any national model. */
    static final int MIN_ZOOM = REGION_ZOOM + 2;
    static final int SOURCE_SIZE = 512;
    /** Distance over which national values fade in from the edge of their coverage. */
    static final double FEATHER_METRES = 200;
    private static final double EARTH_CIRCUMFERENCE = 40_075_016.686;
    private static final int MAX_TILE_BYTES = 8 << 20;
    /** Required attribution: Mapterhorn and the national sources it lists. */
    public static final String ATTRIBUTION = "National terrain models via Mapterhorn (https://mapterhorn.com/attribution), "
            + "including swisstopo swissALTI3D, BEV and the Austrian states, the German states (GeoBasis-DE), IGN France, "
            + "Kartverket, Lantmäteriet, National Land Survey of Finland, Klimadatastyrelsen, CNIG Spain, DGT Portugal, "
            + "TINITALY/INGV and the Italian regions and provinces, Environment Agency, Scottish and Welsh Governments, "
            + "USGS 3DEP, Natural Resources Canada, Geoscience Australia, LINZ, GSI Japan, Natural Science Institute of "
            + "Iceland, and others, under their open licences";

    private final URI tiles, downloads;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxTilesInMemory;
    private final RangeFiles http;
    private final Map<Long, CompletableFuture<float[]>> tileFlights = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<BitSet>> regionFlights = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Map<Integer, Region>>> catalogueFlight = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, float[]> memory = new LinkedHashMap<>(32, 0.75f, true);
    /** Tiles known not to exist, so they are asked for once. */
    private final Map<Long, Boolean> absent = new ConcurrentHashMap<>();
    private final Map<Integer, BitSet> coverage = new ConcurrentHashMap<>();
    private volatile Map<Integer, Region> catalogue;

    /** A regional archive: its file name and finest zoom. */
    record Region(String file, int maxZoom) {}

    /**
     * @param tiles    tile URI prefix; tiles are fetched from {@code tiles + m/x/y.webp}
     * @param downloads prefix of the regional PMTiles archives and their {@code download_urls.json} catalogue
     * @param cacheRoot optional disk cache directory, or null for memory only
     * @param maxTilesInMemory decoded source tiles kept (each 1 MiB)
     */
    public NationalElevation(URI tiles, URI downloads, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory,
                             int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.tiles = Objects.requireNonNull(tiles);
        this.downloads = Objects.requireNonNull(downloads);
        if (!tiles.toString().endsWith("/") || !downloads.toString().endsWith("/")) throw new IllegalArgumentException("Base URIs must end with /");
        if (maxTilesInMemory < 1) throw new IllegalArgumentException("Invalid tile budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxTilesInMemory = maxTilesInMemory;
        this.http = new RangeFiles("National elevation", DATASET_ID, "earth-national-io", maxConcurrentTransfers, timeout);
    }

    @Override public String id() { return DATASET_ID; }

    @Override
    public void refine(int zoom, int x, int y, float[] values) {
        if (zoom < MIN_ZOOM || zoom > 22) return;
        int n = 1 << zoom, size = TerrariumTileSource.TILE_SIZE;
        // The tile's span in coverage cells, with a feather's margin, for the cells beyond its edge.
        double latitude = latitude((y + 0.5) / n);
        double feather = FEATHER_METRES / (EARTH_CIRCUMFERENCE * Math.cos(Math.toRadians(latitude)));
        int cells = 1 << COVERAGE_ZOOM;
        int cx0 = (int) Math.floor(((double) x / n - feather) * cells), cx1 = (int) Math.floor(((x + 1.0) / n + feather) * cells - 1e-9);
        int cy0 = Math.max(0, (int) Math.floor(((double) y / n - feather) * cells)), cy1 = Math.min(cells - 1, (int) Math.floor(((y + 1.0) / n + feather) * cells - 1e-9));
        int across = cx1 - cx0 + 1, down = cy1 - cy0 + 1;
        // A covered cell beside an uncovered one feathers; a feather never reaches past a neighbouring cell.
        if (feather * cells >= 1) return;
        boolean[] covered = new boolean[across * down];
        boolean any = false;
        int ix0 = (int) ((double) x / n * cells), ix1 = (int) Math.min(cells - 1, Math.ceil((x + 1.0) / n * cells) - 1);
        int iy0 = (int) ((double) y / n * cells), iy1 = (int) Math.min(cells - 1, Math.ceil((y + 1.0) / n * cells) - 1);
        for (int j = 0; j < down; j++)
            for (int i = 0; i < across; i++) {
                int cx = Math.floorMod(cx0 + i, cells), cy = cy0 + j;
                boolean in = covers(cx, cy);
                covered[j * across + i] = in;
                if (in && cx0 + i >= ix0 && cx0 + i <= ix1 && cy >= iy0 && cy <= iy1) any = true;
            }
        if (!any) return;
        Source source = source(zoom, x, y);
        if (source == null) return;
        for (int py = 0; py < size; py++) {
            double v = (y * (double) size + py + 0.5) / ((double) n * size);
            for (int px = 0; px < size; px++) {
                int i = py * size + px;
                if (values[i] == 0f) continue;
                double u = (x * (double) size + px + 0.5) / ((double) n * size);
                double cu = u * cells, cv = v * cells;
                int ci = (int) Math.floor(cu) - cx0, cj = (int) Math.floor(cv) - cy0;
                if (ci < 0 || cj < 0 || ci >= across || cj >= down || !covered[cj * across + ci]) continue;
                double weight = weight(covered, across, down, ci, cj, cu - cx0, cv - cy0, feather * cells);
                if (weight <= 0) continue;
                float national = source.at(u, v);
                if (national == 0f || Float.isNaN(national)) continue;
                float blended = (float) (values[i] + weight * (national - values[i]));
                values[i] = blended == 0f ? Math.ulp(0f) : blended;
            }
        }
    }

    /**
     * How far in a pixel at (cu, cv), in cells from the local grid's corner, lies from the nearest
     * uncovered cell, as a smooth weight that reaches 1 a feather's width inside.
     */
    private static double weight(boolean[] covered, int across, int down, int ci, int cj, double cu, double cv, double feather) {
        double nearest = Double.MAX_VALUE;
        for (int j = cj - 1; j <= cj + 1; j++)
            for (int i = ci - 1; i <= ci + 1; i++) {
                // Cells outside the local grid lie beyond the feather's reach, or past a pole.
                if (i < 0 || j < 0 || i >= across || j >= down || covered[j * across + i]) continue;
                double dx = Math.max(0, Math.max(i - cu, cu - (i + 1))), dy = Math.max(0, Math.max(j - cv, cv - (j + 1)));
                nearest = Math.min(nearest, Math.hypot(dx, dy));
            }
        if (nearest >= feather) return 1;
        double t = nearest / feather;
        return t * t * (3 - 2 * t);
    }

    /** National values for a tile, from the source tile of the same detail or else the finest one coarser. */
    private Source source(int zoom, int x, int y) {
        int m = zoom - 1;
        if (m >= COVERAGE_ZOOM) {
            Region region = catalogue().get(regionKey(x >> (zoom - REGION_ZOOM), y >> (zoom - REGION_ZOOM)));
            if (region == null) return null;
            m = Math.min(m, region.maxZoom());
        }
        for (int at = m; at >= Math.min(m, COVERAGE_ZOOM); at--) {
            int shift = zoom - at;
            float[] tile = tile(at, x >> shift, y >> shift);
            if (tile != null) return new Source(tile, at, x >> shift, y >> shift);
        }
        return null;
    }

    /** One source tile, sampled at normalised Mercator positions (0..1 west to east, north to south). */
    private record Source(float[] values, int zoom, int x, int y) {
        float at(double u, double v) {
            double scale = (double) SOURCE_SIZE * (1 << zoom);
            double sx = snap(u * scale - (double) x * SOURCE_SIZE - 0.5), sy = snap(v * scale - (double) y * SOURCE_SIZE - 0.5);
            int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
            double fx = sx - x0, fy = sy - y0;
            if (fx == 0 && fy == 0 && x0 >= 0 && y0 >= 0 && x0 < SOURCE_SIZE && y0 < SOURCE_SIZE) return values[y0 * SOURCE_SIZE + x0];
            float a = pixel(x0, y0), b = pixel(x0 + 1, y0), c = pixel(x0, y0 + 1), d = pixel(x0 + 1, y0 + 1);
            // Empty pixels (0) must not pull their neighbours down; a sample touching one is left alone.
            if (a == 0f || b == 0f || c == 0f || d == 0f) return 0f;
            return (float) ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy);
        }

        /** A position that lands on a pixel centre up to rounding lands on it exactly. */
        private static double snap(double s) {
            double r = Math.rint(s);
            return Math.abs(s - r) < 1e-6 ? r : s;
        }

        private float pixel(int px, int py) {
            px = Math.max(0, Math.min(SOURCE_SIZE - 1, px));
            py = Math.max(0, Math.min(SOURCE_SIZE - 1, py));
            return values[py * SOURCE_SIZE + px];
        }
    }

    /** Whether a national model covers coverage cell (cx, cy). */
    boolean covers(int cx, int cy) {
        int shift = COVERAGE_ZOOM - REGION_ZOOM;
        int rx = cx >> shift, ry = cy >> shift, key = regionKey(rx, ry);
        BitSet bits = coverage.get(key);
        if (bits == null) {
            Region region = catalogue().get(key);
            if (region == null) {
                bits = new BitSet(0);
            } else {
                bits = http.singleFlight(regionFlights, key, () -> loadCoverage(rx, ry, region), "national elevation coverage " + region.file());
            }
            coverage.put(key, bits);
        }
        int side = 1 << shift;
        return bits.get((cy & (side - 1)) * side + (cx & (side - 1)));
    }

    private static int regionKey(int rx, int ry) { return rx << 16 | ry; }

    // Catalogue and coverage ------------------------------------------------------------------------------

    private static final Pattern ITEM = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"(" + REGION_ZOOM + ")-(\\d+)-(\\d+)\\.pmtiles\"");
    private static final Pattern MIN_ZOOM_FIELD = Pattern.compile("\"min_zoom\"\\s*:\\s*(\\d+)");
    private static final Pattern MAX_ZOOM_FIELD = Pattern.compile("\"max_zoom\"\\s*:\\s*(\\d+)");

    /** The regions that hold national tiles, from Mapterhorn's download list. */
    private Map<Integer, Region> catalogue() {
        Map<Integer, Region> known = catalogue;
        if (known != null) return known;
        known = http.singleFlight(catalogueFlight, "catalogue", this::loadCatalogue, "national elevation catalogue");
        catalogue = known;
        return known;
    }

    private Map<Integer, Region> loadCatalogue() throws IOException, InterruptedException {
        byte[] bytes = cached("download_urls.json", () -> http.fetch(downloads.resolve("download_urls.json"), 0, MAX_TILE_BYTES));
        if (bytes == null) throw new MissingTerrainException("National elevation catalogue not available");
        return parseCatalogue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    static Map<Integer, Region> parseCatalogue(String json) {
        Map<Integer, Region> regions = new HashMap<>();
        Matcher item = ITEM.matcher(json);
        while (item.find()) {
            String body = item.group();
            Matcher name = NAME.matcher(body), min = MIN_ZOOM_FIELD.matcher(body), max = MAX_ZOOM_FIELD.matcher(body);
            if (!name.find() || !min.find() || !max.find()) continue;
            int lowest = Integer.parseInt(min.group(1)), highest = Integer.parseInt(max.group(1));
            if (lowest > COVERAGE_ZOOM || highest < COVERAGE_ZOOM) continue;
            int rx = Integer.parseInt(name.group(2)), ry = Integer.parseInt(name.group(3));
            regions.put(regionKey(rx, ry), new Region(REGION_ZOOM + "-" + rx + "-" + ry + ".pmtiles", highest));
        }
        if (regions.isEmpty()) throw new MissingTerrainException("National elevation catalogue lists no regions");
        return regions;
    }

    /** Which coverage cells of a region hold national tiles: bit (cy * side + cx) within the region. */
    private BitSet loadCoverage(int rx, int ry, Region region) throws IOException, InterruptedException {
        String name = "coverage/" + region.file().replace(".pmtiles", ".bits");
        byte[] bytes = cached(name, () -> {
            URI archive = downloads.resolve(region.file());
            BitSet bits = PmtilesIndex.tiles(range -> http.fetch(archive, range[0], (int) range[1]), COVERAGE_ZOOM, rx, ry, REGION_ZOOM);
            byte[] out = bits.toByteArray();
            // An empty marker byte keeps "nothing covered" distinct from "never read".
            return out.length == 0 ? new byte[]{0} : out;
        });
        if (bytes == null) throw new MissingTerrainException("National elevation coverage not available for " + region.file());
        return BitSet.valueOf(bytes);
    }

    // Source tiles ------------------------------------------------------------------------------------------

    /** Decoded metres of a 512-pixel source tile, or null where none exists. */
    float[] tile(int m, int x, int y) {
        long key = (long) m << 58 | (long) x << 29 | y;
        if (absent.containsKey(key)) return null;
        synchronized (memory) {
            float[] hit = memory.get(key);
            if (hit != null) return hit;
        }
        float[] values = http.singleFlight(tileFlights, key, () -> loadTile(m, x, y), "national elevation tile " + m + "/" + x + "/" + y);
        if (values.length == 0) {
            absent.put(key, Boolean.TRUE);
            return null;
        }
        synchronized (memory) {
            memory.put(key, values);
            var iterator = memory.entrySet().iterator();
            while (memory.size() > maxTilesInMemory && iterator.hasNext()) { iterator.next(); iterator.remove(); }
        }
        return values;
    }

    private float[] loadTile(int m, int x, int y) throws IOException, InterruptedException {
        String path = m + "/" + x + "/" + y + ".webp";
        byte[] bytes = cached(path, () -> {
            byte[] body = http.fetch(tiles.resolve(path), 0, MAX_TILE_BYTES);
            return body == null ? new byte[0] : body;
        });
        if (bytes == null) throw new MissingTerrainException("National elevation tile " + path + " not cached and network access is disabled");
        if (bytes.length == 0) return new float[0];
        WebpLossless.Image image = WebpLossless.decode(bytes);
        if (image.width() != SOURCE_SIZE || image.height() != SOURCE_SIZE) throw new IOException("Unexpected national tile size " + image.width() + "x" + image.height());
        int[] argb = image.argb();
        float[] metres = new float[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            metres[i] = (float) ((((p >>> 16) & 0xFF) * 256 + ((p >>> 8) & 0xFF) + (p & 0xFF) / 256.0) - 32768.0);
        }
        return metres;
    }

    /** A cached file's bytes, else fetched and cached; null when not cached and the network is off. */
    private byte[] cached(String name, RangeFiles.Load<byte[]> fetch) throws IOException, InterruptedException {
        Path file = cacheRoot == null ? null : cacheRoot.resolve(name);
        if (file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            http.diskHits.incrementAndGet();
            return Files.readAllBytes(file);
        }
        if (!allowNetwork) return null;
        byte[] bytes = fetch.get();
        if (file != null && bytes != null) RangeFiles.write(file, bytes);
        return bytes;
    }

    private static double latitude(double v) { return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * v)))); }

    public record Stats(long downloads, long diskHits, long failures) {}

    public Stats stats() { return new Stats(http.downloads.get(), http.diskHits.get(), http.failures.get()); }

    @Override public void close() { http.close(); }

    /** Reads which tiles of one zoom a PMTiles v3 archive holds, from its directories alone. */
    static final class PmtilesIndex {
        interface Ranges { byte[] read(long[] range) throws IOException, InterruptedException; }

        private PmtilesIndex() {}

        /**
         * Tiles at {@code zoom} inside tile (rx, ry) of {@code regionZoom}, as bits (y * side + x)
         * relative to the region's corner.
         */
        static BitSet tiles(Ranges ranges, int zoom, int rx, int ry, int regionZoom) throws IOException, InterruptedException {
            byte[] header = ranges.read(new long[]{0, 127});
            if (header == null || header.length < 127 || !new String(header, 0, 7, java.nio.charset.StandardCharsets.US_ASCII).equals("PMTiles") || header[7] != 3)
                throw new IOException("Not a PMTiles v3 archive");
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            long rootOffset = h.getLong(8), rootLength = h.getLong(16), leafOffset = h.getLong(40);
            int compression = header[97];
            long first = base(zoom), end = first + (1L << (2 * zoom));
            int shift = zoom - regionZoom, side = 1 << shift;
            BitSet bits = new BitSet(side * side);
            walk(ranges, compression, rootOffset, rootLength, leafOffset, first, end, zoom, rx << shift, ry << shift, side, bits, 0);
            return bits;
        }

        private static void walk(Ranges ranges, int compression, long offset, long length, long leafOffset, long first, long end,
                                 int zoom, int ox, int oy, int side, BitSet bits, int depth) throws IOException, InterruptedException {
            if (depth > 4 || length > 64L << 20) throw new IOException("Malformed PMTiles directory");
            byte[] raw = ranges.read(new long[]{offset, length});
            if (raw == null) throw new IOException("PMTiles directory missing");
            byte[] dir = switch (compression) {
                case 1 -> raw;
                case 2 -> { try (var in = new GZIPInputStream(new ByteArrayInputStream(raw))) { yield in.readAllBytes(); } }
                default -> throw new IOException("Unsupported PMTiles compression " + compression);
            };
            int[] at = {0};
            int count = (int) varint(dir, at);
            long[] ids = new long[count], runs = new long[count], lengths = new long[count], offsets = new long[count];
            long id = 0;
            for (int i = 0; i < count; i++) { id += varint(dir, at); ids[i] = id; }
            for (int i = 0; i < count; i++) runs[i] = varint(dir, at);
            for (int i = 0; i < count; i++) lengths[i] = varint(dir, at);
            for (int i = 0; i < count; i++) {
                long value = varint(dir, at);
                offsets[i] = value == 0 && i > 0 ? offsets[i - 1] + lengths[i - 1] : value - 1;
            }
            for (int i = 0; i < count; i++) {
                if (runs[i] == 0) {
                    // A leaf directory holds ids from here up to the next entry's.
                    long next = i + 1 < count ? ids[i + 1] : Long.MAX_VALUE;
                    if (ids[i] < end && next > first) walk(ranges, compression, leafOffset + offsets[i], lengths[i], leafOffset, first, end, zoom, ox, oy, side, bits, depth + 1);
                    continue;
                }
                for (long t = Math.max(ids[i], first); t < Math.min(ids[i] + runs[i], end); t++) {
                    long[] xy = hilbert(zoom, t - first);
                    long dx = xy[0] - ox, dy = xy[1] - oy;
                    if (dx >= 0 && dy >= 0 && dx < side && dy < side) bits.set((int) (dy * side + dx));
                }
            }
        }

        /** Tile ids before the first tile of a zoom: one per tile of every coarser zoom. */
        static long base(int zoom) {
            long total = 0;
            for (int z = 0; z < zoom; z++) total += 1L << (2 * z);
            return total;
        }

        /** Position along the Hilbert curve that orders a zoom's tiles, as tile (x, y). */
        static long[] hilbert(int zoom, long d) {
            long x = 0, y = 0, t = d;
            for (long s = 1; s < 1L << zoom; s <<= 1) {
                long rx = 1 & (t >> 1), ry = 1 & (t ^ rx);
                if (ry == 0) {
                    if (rx == 1) { x = s - 1 - x; y = s - 1 - y; }
                    long swap = x; x = y; y = swap;
                }
                x += s * rx;
                y += s * ry;
                t >>= 2;
            }
            return new long[]{x, y};
        }

        private static long varint(byte[] b, int[] at) throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at[0] >= b.length) throw new IOException("Truncated PMTiles directory");
                int c = b[at[0]++] & 0xFF;
                value |= (long) (c & 0x7F) << shift;
                if (c < 0x80) return value;
            }
            throw new IOException("Bad varint in PMTiles directory");
        }
    }
}
