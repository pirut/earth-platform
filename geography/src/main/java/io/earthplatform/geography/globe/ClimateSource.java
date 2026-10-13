package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Present-day climate from CHELSA V2.1, dataset id {@code chelsa-v2.1-1981-2010}: the 1981-2010
 * climatologies at 30 arc-seconds (about 1 km) over land and sea, released as CC0. For a place it
 * gives the Köppen-Geiger class (after Peel et al. 2007, CHELSA's {@code kg2}), the mean yearly air
 * temperature, the mean temperature of the warmest quarter and the yearly precipitation.
 * Temperatures are carried from each pixel's own height (CHELSA's elevation grid) to the height
 * asked for at the standard lapse rate, so a valley floor and the ridge above it differ as they do
 * on Earth, and are blended between the four nearest pixels.
 *
 * <p>The source is CHELSA's public object store: one deflated GeoTIFF per variable with a strip per
 * pixel row, and the elevation grid as raw floats. Only the headers and the rows a caller touches
 * are fetched, with HTTP range requests, and kept in an optional disk cache, so a world regenerates
 * from the same bytes offline. Loads are single-flight and bounded in concurrency and memory.
 * Callers block; use it from generation workers.
 */
public final class ClimateSource implements AutoCloseable {
    public static final String DATASET_ID = "chelsa-v2.1-1981-2010";
    public static final URI CHELSA = URI.create("https://os.zhdk.cloud.switch.ch/chelsav2/GLOBAL/");
    public static final String ATTRIBUTION = "CHELSA V2.1 climatologies 1981-2010, Karger et al. 2017, Scientific Data 4:170122 (CC0 1.0)";
    /** Köppen-Geiger classes as CHELSA numbers them; 0 where there is no data. */
    public static final int AF = 1, AM = 2, AS = 3, AW = 4, BWK = 5, BWH = 6, BSK = 7, BSH = 8, CFA = 9, CFB = 10, CFC = 11,
            CSA = 12, CSB = 13, CSC = 14, CWA = 15, CWB = 16, CWC = 17, DFA = 18, DFB = 19, DFC = 20, DFD = 21, DSA = 22, DSB = 23,
            DSC = 24, DSD = 25, DWA = 26, DWB = 27, DWC = 28, DWD = 29, ET = 30, EF = 31, NO_DATA = 0;
    /** Cooling with height, in degrees per metre. */
    public static final double LAPSE_RATE = 0.0065;
    /** The grid: whole longitudes from 180 W, latitudes from 84 N down to 90 S, 120 pixels a degree. */
    static final int PIXELS_PER_DEGREE = 120, WIDTH = 43200, HEIGHT = 20880;
    static final double NORTH = 84, WEST = -180;
    /** Elevation columns fetched together. */
    static final int DEM_CHUNK = 2048;
    private static final double GRAVITY = 9.80665;
    private static final int HEADER_BYTES = 256 << 10, MAX_HEADER_BYTES = 2 << 20, MAX_ROW_BYTES = 1 << 20;

    /** The variables read: Köppen class, mean temperature, warmest-quarter temperature, precipitation. */
    enum Layer {
        KOPPEN("kg2"), MEAN("bio1"), WARM("bio10"), RAIN("bio12");

        final String file;
        Layer(String variable) { file = "climatologies/1981-2010/bio/CHELSA_" + variable + "_1981-2010_V.2.1.tif"; }
        String stem() { return file.substring(file.lastIndexOf('/') + 1, file.length() - 4); }
    }
    static final String DEM_FILE = "input/dem_geo.sdat";
    private static final int DEM = Layer.values().length;

    /** Climate at one place and height: a Köppen-Geiger class, temperatures in degrees C, precipitation in mm a year. */
    public record Climate(int koppen, double meanTemperature, double warmestQuarter, double precipitation) {}

    private final URI base;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxRowsInMemory;
    private final RangeFiles files;
    private final Map<Layer, CompletableFuture<Header>> headers = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, Object> memory = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    /** Each thread's last few rows, so neighbouring reads skip the shared cache. */
    private final ThreadLocal<Recent> recent = ThreadLocal.withInitial(Recent::new);

    /**
     * @param base            URI prefix the files are named under
     * @param cacheRoot       optional disk cache directory, or null for memory only
     * @param allowNetwork    false reads only the disk cache
     * @param maxRowsInMemory decoded rows kept (each about 86 KiB)
     */
    public ClimateSource(URI base, Path cacheRoot, boolean allowNetwork, int maxRowsInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.base = Objects.requireNonNull(base);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException("CHELSA base URI must end with /");
        if (maxRowsInMemory < 8 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid climate source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxRowsInMemory = maxRowsInMemory;
        this.files = new RangeFiles("Climate", DATASET_ID, "earth-climate-io", maxConcurrentTransfers, timeout);
    }

    /** The climate at a latitude and longitude, {@code metres} above sea level, or null where the data has a gap. */
    public Climate at(double latitude, double longitude, double metres) {
        if (!(Math.abs(latitude) <= 90) || !Double.isFinite(longitude) || !Double.isFinite(metres)) throw new IllegalArgumentException("Invalid position " + latitude + ", " + longitude);
        longitude = wrap(longitude);
        // Pixel centres sit half a pixel in from the corners.
        double fx = (longitude - WEST) * PIXELS_PER_DEGREE - 0.5, fy = (NORTH - latitude) * PIXELS_PER_DEGREE - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        double weights = 0, mean = 0, warm = 0, rain = 0;
        for (int dy = 0; dy < 2; dy++) {
            int row = Math.clamp(y0 + dy, 0, HEIGHT - 1);
            short[] meanRow = row(Layer.MEAN, row), warmRow = row(Layer.WARM, row), rainRow = row(Layer.RAIN, row);
            for (int dx = 0; dx < 2; dx++) {
                int column = Math.floorMod(x0 + dx, WIDTH);
                int m = Short.toUnsignedInt(meanRow[column]), w = Short.toUnsignedInt(warmRow[column]), r = Short.toUnsignedInt(rainRow[column]);
                if (m == 0 || m == 0xFFFF || w == 0 || w == 0xFFFF || r == 0xFFFF) continue;
                double weight = (dx == 0 ? 1 - tx : tx) * (dy == 0 ? 1 - ty : ty);
                if (weight <= 0) continue;
                // Each pixel's temperatures brought down to sea level, to be lifted again to the height asked for.
                double sea = LAPSE_RATE * height(row, column);
                weights += weight;
                mean += weight * (kelvin(m) + sea);
                warm += weight * (kelvin(w) + sea);
                rain += weight * r * 0.1;
            }
        }
        if (weights <= 0) return null;
        int row = Math.clamp((int) Math.floor(fy + 0.5), 0, HEIGHT - 1), column = Math.floorMod((int) Math.floor(fx + 0.5), WIDTH);
        int koppen = Short.toUnsignedInt(row(Layer.KOPPEN, row)[column]);
        if (koppen > EF) koppen = NO_DATA;
        return new Climate(koppen, mean / weights - LAPSE_RATE * metres, warm / weights - LAPSE_RATE * metres, rain / weights);
    }

    /** Loads every row and elevation chunk an area needs, several at a time, so later reads never wait. */
    public void prefetch(double south, double west, double north, double east) {
        int top = Math.clamp((int) Math.floor((NORTH - Math.min(90, north)) * PIXELS_PER_DEGREE) - 1, 0, HEIGHT - 1);
        int bottom = Math.clamp((int) Math.floor((NORTH - Math.max(-90, south)) * PIXELS_PER_DEGREE) + 1, 0, HEIGHT - 1);
        double span = Math.min(360, east - west);
        int first = (int) Math.floor((wrap(west) - WEST) * PIXELS_PER_DEGREE) - 1, columns = (int) Math.ceil(span * PIXELS_PER_DEGREE) + 3;
        List<Runnable> loads = new ArrayList<>();
        for (int row = top; row <= bottom; row++) {
            int r = row;
            for (Layer layer : Layer.values()) loads.add(() -> row(layer, r));
            java.util.Set<Integer> chunks = new java.util.TreeSet<>();
            for (int c = 0; c < columns; c += DEM_CHUNK / 2) chunks.add(Math.floorMod(first + c, WIDTH) / DEM_CHUNK);
            chunks.add(Math.floorMod(first + columns - 1, WIDTH) / DEM_CHUNK);
            for (int chunk : chunks) loads.add(() -> demChunk(r, chunk));
        }
        loads.parallelStream().forEach(Runnable::run);
    }

    private static double kelvin(int value) { return value * 0.1 - 273.15; }

    // Rows -------------------------------------------------------------------------------------------

    /** One GeoTIFF's layout: where each pixel row's strip is, how it is stored and in which byte order. */
    record Header(long[] offsets, long[] counts, boolean deflate, boolean predictor, boolean littleEndian) {}

    private static long key(int layer, int row, int chunk) { return (long) layer << 40 | (long) row << 8 | chunk; }

    private short[] row(Layer layer, int row) {
        return (short[]) cached(key(layer.ordinal(), row, 0), () -> readRow(layer, row), "climate row");
    }

    /** Height in metres of a pixel in CHELSA's elevation grid (sea level where it has none). */
    private double height(int row, int column) {
        float[] chunk = (float[]) cached(key(DEM, row, column / DEM_CHUNK), () -> readDem(row, column / DEM_CHUNK), "climate elevation");
        return chunk[column % DEM_CHUNK];
    }

    private float[] demChunk(int row, int chunk) {
        return (float[]) cached(key(DEM, row, chunk), () -> readDem(row, chunk), "climate elevation");
    }

    private Object cached(long key, RangeFiles.Load<Object> load, String piece) {
        Recent mine = recent.get();
        Object hit = mine.get(key);
        if (hit != null) return hit;
        synchronized (memory) { hit = memory.get(key); }
        if (hit == null) {
            hit = files.singleFlight(inFlight, key, load, piece);
            synchronized (memory) {
                memory.put(key, hit);
                var iterator = memory.entrySet().iterator();
                while (memory.size() > maxRowsInMemory && iterator.hasNext()) { iterator.next(); iterator.remove(); }
            }
        }
        mine.put(key, hit);
        return hit;
    }

    /** A thread's last sixteen pieces, replaced in turn: enough for every row one read touches. */
    private static final class Recent {
        final long[] keys = new long[16];
        final Object[] values = new Object[16];
        int next;

        Recent() { java.util.Arrays.fill(keys, -1); }

        Object get(long key) {
            for (int i = 0; i < 16; i++) if (keys[i] == key) return values[i];
            return null;
        }

        void put(long key, Object value) {
            keys[next] = key;
            values[next] = value;
            next = (next + 1) & 15;
        }
    }

    private short[] readRow(Layer layer, int row) throws IOException, InterruptedException {
        Header header = header(layer);
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(layer.stem() + "/" + row + ".z");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                short[] decoded = decode(Files.readAllBytes(cached), header);
                files.diskHits.incrementAndGet();
                return decoded;
            } catch (IOException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        long offset = header.offsets[row], count = header.counts[row];
        if (count <= 0 || count > MAX_ROW_BYTES) throw new MissingTerrainException("Unreadable climate row " + row + " of " + layer.stem());
        if (!allowNetwork) throw new MissingTerrainException("Climate row " + row + " of " + layer.stem() + " not cached and network access is disabled");
        byte[] raw = files.fetch(base.resolve(layer.file), offset, (int) count);
        if (raw == null || raw.length != count) throw new MissingTerrainException("Climate row " + row + " of " + layer.stem() + " is missing");
        short[] decoded = decode(raw, header);
        if (cached != null) RangeFiles.write(cached, raw);
        return decoded;
    }

    private float[] readDem(int row, int chunk) throws IOException, InterruptedException {
        int from = chunk * DEM_CHUNK, size = Math.min(WIDTH, from + DEM_CHUNK) - from;
        Path cached = cacheRoot == null ? null : cacheRoot.resolve("dem/" + row + "/" + chunk + ".f");
        byte[] raw = null;
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            raw = Files.readAllBytes(cached);
            if (raw.length == size * 4) files.diskHits.incrementAndGet();
            else { Files.deleteIfExists(cached); raw = null; }
        }
        if (raw == null) {
            if (!allowNetwork) throw new MissingTerrainException("Climate elevation row " + row + " not cached and network access is disabled");
            // The grid is stored from its south edge up.
            long offset = ((long) (HEIGHT - 1 - row) * WIDTH + from) * 4;
            raw = files.fetch(base.resolve(DEM_FILE), offset, size * 4);
            if (raw == null || raw.length != size * 4) throw new MissingTerrainException("Climate elevation row " + row + " is missing");
            if (cached != null) RangeFiles.write(cached, raw);
        }
        ByteBuffer in = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] metres = new float[size];
        for (int i = 0; i < size; i++) {
            float geopotential = in.getFloat(i * 4);
            // Stored as geopotential (m^2/s^2); -99999 marks no data.
            metres[i] = geopotential <= -99998 || !Float.isFinite(geopotential) ? 0 : (float) (geopotential / GRAVITY);
        }
        return metres;
    }

    static short[] decode(byte[] raw, Header header) throws IOException {
        int size = WIDTH * 2;
        byte[] bytes;
        if (!header.deflate) {
            if (raw.length != size) throw new IOException("Unexpected climate row size");
            bytes = raw;
        } else {
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(raw);
                bytes = new byte[size];
                int done = 0;
                while (done < size && !inflater.finished()) {
                    int n = inflater.inflate(bytes, done, size - done);
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                    done += n;
                }
                if (done != size) throw new IOException("Short climate row");
            } catch (DataFormatException malformed) {
                throw new IOException("Malformed climate row", malformed);
            } finally {
                inflater.end();
            }
        }
        ByteBuffer in = ByteBuffer.wrap(bytes).order(header.littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        short[] values = new short[WIDTH];
        short previous = 0;
        for (int i = 0; i < WIDTH; i++) {
            short value = in.getShort(i * 2);
            // Horizontal differencing stores each value as the step from the one before.
            if (header.predictor) value += previous;
            values[i] = value;
            previous = value;
        }
        return values;
    }

    private Header header(Layer layer) {
        CompletableFuture<Header> mine = new CompletableFuture<>(), shared = headers.putIfAbsent(layer, mine);
        if (shared == null) {
            try { mine.complete(readHeader(layer)); }
            catch (Throwable failure) {
                files.failures.incrementAndGet();
                mine.completeExceptionally(failure);
                headers.remove(layer, mine);
            }
            shared = mine;
        }
        return files.await(shared, "climate header " + layer.stem());
    }

    private Header readHeader(Layer layer) throws IOException, InterruptedException {
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(layer.stem() + ".header");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Header header = parse(Files.readAllBytes(cached));
                files.diskHits.incrementAndGet();
                return header;
            } catch (IOException | RuntimeException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException("Climate " + layer.stem() + " not cached and network access is disabled");
        int size = HEADER_BYTES;
        while (true) {
            byte[] bytes = files.fetch(base.resolve(layer.file), 0, size);
            if (bytes == null) throw new MissingTerrainException("Climate file " + layer.file + " is missing");
            try {
                Header header = parse(bytes);
                if (cached != null) RangeFiles.write(cached, bytes);
                return header;
            } catch (HeaderTooShort tooShort) {
                if (bytes.length < size || size >= MAX_HEADER_BYTES) throw new IOException("Unreadable climate header " + layer.stem());
                size *= 2;
            }
        }
    }

    private static final class HeaderTooShort extends IOException {
        HeaderTooShort() { super("Header extends past the bytes read"); }
    }

    /** Reads the first image of a striped 16-bit single-band GeoTIFF on the CHELSA grid, one row per strip. */
    static Header parse(byte[] bytes) throws IOException {
        if (bytes.length < 8) throw new HeaderTooShort();
        ByteBuffer in = ByteBuffer.wrap(bytes);
        boolean little = bytes[0] == 'I';
        in.order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        if (in.getShort(2) != 42) throw new IOException("Not a classic TIFF");
        long at = Integer.toUnsignedLong(in.getInt(4));
        if (at + 2 > bytes.length) throw new HeaderTooShort();
        int entries = Short.toUnsignedInt(in.getShort((int) at));
        if (at + 2 + entries * 12L > bytes.length) throw new HeaderTooShort();
        int width = 0, height = 0, bits = 0, compression = 1, samples = 1, rowsPerStrip = 0, predictor = 1;
        long[] offsets = null, counts = null;
        double[] scale = null, tie = null;
        for (int e = 0; e < entries; e++) {
            int entry = (int) at + 2 + e * 12;
            int tag = Short.toUnsignedInt(in.getShort(entry)), type = Short.toUnsignedInt(in.getShort(entry + 2));
            long count = Integer.toUnsignedLong(in.getInt(entry + 4));
            switch (tag) {
                case 256 -> width = (int) scalar(in, entry, type);
                case 257 -> height = (int) scalar(in, entry, type);
                case 258 -> bits = (int) scalar(in, entry, type);
                case 259 -> compression = (int) scalar(in, entry, type);
                case 273 -> offsets = array(in, bytes.length, entry, type, count);
                case 277 -> samples = (int) scalar(in, entry, type);
                case 278 -> rowsPerStrip = (int) scalar(in, entry, type);
                case 279 -> counts = array(in, bytes.length, entry, type, count);
                case 317 -> predictor = (int) scalar(in, entry, type);
                case 33550 -> scale = doubles(in, bytes.length, entry, type, count);
                case 33922 -> tie = doubles(in, bytes.length, entry, type, count);
                default -> { }
            }
        }
        if (bits != 16 || samples != 1 || (predictor != 1 && predictor != 2) || (compression != 1 && compression != 8 && compression != 32946))
            throw new IOException("Unsupported climate encoding");
        if (width != WIDTH || height != HEIGHT || rowsPerStrip != 1 || offsets == null || counts == null || offsets.length != HEIGHT || counts.length != HEIGHT)
            throw new IOException("Unsupported climate layout");
        // The grid must be CHELSA's: 30 arc-second pixels from 180 W, 84 N.
        if (scale == null || tie == null || scale.length < 2 || tie.length < 6 || Math.abs(scale[0] * PIXELS_PER_DEGREE - 1) > 1e-6
                || Math.abs(scale[1] * PIXELS_PER_DEGREE - 1) > 1e-6 || Math.abs(tie[3] - WEST) > 1e-3 || Math.abs(tie[4] - NORTH) > 1e-3)
            throw new IOException("Unexpected climate grid");
        return new Header(offsets, counts, compression != 1, predictor == 2, little);
    }

    private static long scalar(ByteBuffer in, int at, int type) {
        return type == 3 ? Short.toUnsignedInt(in.getShort(at + 8)) : Integer.toUnsignedLong(in.getInt(at + 8));
    }

    private static long start(ByteBuffer in, int length, int at, long count, int size) throws IOException {
        if (count > 1 << 20) throw new IOException("Oversized TIFF array");
        long start = count * size <= 4 ? at + 8 : Integer.toUnsignedLong(in.getInt(at + 8));
        if (start + count * size > length) throw new HeaderTooShort();
        return start;
    }

    private static long[] array(ByteBuffer in, int length, int at, int type, long count) throws IOException {
        if (type != 3 && type != 4) throw new IOException("Unsupported TIFF array type " + type);
        int size = type == 3 ? 2 : 4;
        long start = start(in, length, at, count, size);
        long[] values = new long[(int) count];
        for (int i = 0; i < count; i++) {
            int p = (int) (start + (long) i * size);
            values[i] = size == 2 ? Short.toUnsignedInt(in.getShort(p)) : Integer.toUnsignedLong(in.getInt(p));
        }
        return values;
    }

    private static double[] doubles(ByteBuffer in, int length, int at, int type, long count) throws IOException {
        if (type != 12) throw new IOException("Unsupported TIFF double type " + type);
        long start = start(in, length, at, count, 8);
        double[] values = new double[(int) count];
        for (int i = 0; i < count; i++) values[i] = in.getDouble((int) (start + i * 8L));
        return values;
    }

    private static double wrap(double longitude) {
        double wrapped = ((longitude + 180) % 360 + 360) % 360 - 180;
        return wrapped == 180 ? -180 : wrapped;
    }

    public Stats stats() {
        int resident;
        synchronized (memory) { resident = memory.size(); }
        return new Stats(files.downloads.get(), files.diskHits.get(), files.failures.get(), resident);
    }

    public record Stats(long downloads, long diskHits, long failures, int residentRows) {}

    @Override public void close() { files.close(); }
}
