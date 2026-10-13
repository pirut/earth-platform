package io.earthplatform.geography.globe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streams the elevation's valleys carry, for rivers too narrow for any satellite water map.
 *
 * <p>A river ten or twenty metres wide, as the Cam through Cambridge, leaves no trace in
 * {@link SurfaceWaterSource}'s thirty-metre pixels, but its valley is plain in the elevation. Each
 * {@value #ZOOM}-zoom elevation tile is drained with {@value #SPREAD} tiles round it: the land is
 * flooded from the window's edge, lowest first, so every pixel drains to the one the water
 * reached it from, pits and flats included, and each pixel gathers the ground upstream of it. A
 * pixel is a stream where that ground, counted by how wet its climate is, passes
 * {@value #STREAM_AREA} square kilometres, and only where it lies in a valley: at least
 * {@value #VALLEY} metres below the middle of the ground {@value #RING} pixels round it. Flats
 * such as fens give no valley, so the made-up courses water takes across them are left out;
 * short gaps on a valley's stream are kept and short pieces dropped.
 *
 * <p>{@link #links} gives each stream pixel's step to the next, and {@link #metres} the finer
 * elevation the river map lays them along its valley floors with.
 */
public final class Drainage {
    /** Names how streams are found, pinned in a world's settings so its rivers never change partway. */
    public static final String ID = "elevation-streams-v1";
    /** Zoom the land is drained at, about 150 m pixels at the equator; and tiles of margin each way. */
    public static final int ZOOM = 10, SPREAD = 2;
    /** Zoom of the elevation channels are laid along. */
    public static final int FINE_ZOOM = 13;
    /** Upstream ground, square kilometres counted by wetness, a stream drains; and that of a full-sized river. */
    public static final double STREAM_AREA = 30, RIVER_AREA = 750;
    /** Metres a stream must lie below the middle of the ground {@value #RING} pixels round it. */
    static final double VALLEY = 1.0;
    static final int RING = 10;
    /** Longest gap, in pixels, kept on a valley's stream; and fewest pixels in a piece kept. */
    static final int GAP = 4, PIECE = 4;
    /** Yearly rain, mm, below which ground sends no water on; and from which it sends all its share. */
    static final double DRY = 200, WET = 700;
    private static final int TILE = TerrariumTileSource.TILE_SIZE, SIDE = (2 * SPREAD + 1) * TILE;
    private static final int[] NX = {1, 1, 0, -1, -1, -1, 0, 1}, NY = {0, 1, 1, 1, 0, -1, -1, -1};
    /** Rain sampled every this many pixels, about 20 km at the equator, and blended between. */
    private static final int RAIN_STEP = 128;

    /** Yearly precipitation in millimetres at a latitude and longitude. */
    @FunctionalInterface
    public interface Rain {
        double millimetres(double latitude, double longitude);
    }

    /** Receives one stream step, from a pixel's centre to the next pixel's, with the ground it drains. */
    @FunctionalInterface
    public interface Link {
        void accept(double latitude, double longitude, double toLatitude, double toLongitude, double squareKilometres);
    }

    private final GlobalElevation.Tiles tiles;
    private final Rain rain;
    private final int maxRegions;
    private final LinkedHashMap<Long, Region> memory = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Long, CompletableFuture<Region>> inFlight = new ConcurrentHashMap<>();
    /** A thread's most recent fine tile, since neighbouring reads mostly share one. */
    private final ThreadLocal<Last> lastFine = ThreadLocal.withInitial(Last::new);

    private static final class Last {
        long key = -1;
        float[] pixels;
    }

    /** One drained tile: per pixel, the direction (0 to 7) a kept stream steps on in, or -1; and the ground it drains. */
    private record Region(byte[] step, float[] area) {}

    /** @param rain yearly precipitation, or null to count all ground as wet */
    public Drainage(GlobalElevation.Tiles tiles, Rain rain, int maxRegions) {
        this.tiles = Objects.requireNonNull(tiles);
        this.rain = rain;
        if (maxRegions < 2) throw new IllegalArgumentException("Drainage needs room for at least two tiles");
        this.maxRegions = maxRegions;
    }

    public Drainage(TerrariumTileSource tiles, Rain rain, int maxRegions) {
        this((GlobalElevation.Tiles) tiles::tile, rain, maxRegions);
    }

    /** Every stream step starting inside an area. */
    public void links(double south, double west, double north, double east, Link sink) {
        long size = (long) TILE << ZOOM;
        long x0 = (long) Math.floor(pixelX(west, ZOOM)), x1 = (long) Math.floor(pixelX(east, ZOOM));
        long y0 = Math.max(0, (long) Math.floor(pixelY(north, ZOOM))), y1 = Math.min(size - 1, (long) Math.floor(pixelY(south, ZOOM)));
        for (long y = y0; y <= y1; y++) for (long x = x0; x <= x1; x++) {
            long gx = Math.floorMod(x, size);
            Region region = region((int) (gx / TILE), (int) (y / TILE));
            int at = (int) ((y % TILE) * TILE + gx % TILE), step = region.step[at];
            if (step < 0) continue;
            sink.accept(latitude(y + 0.5, ZOOM), longitude(x + 0.5, ZOOM),
                    latitude(y + NY[step] + 0.5, ZOOM), longitude(x + NX[step] + 0.5, ZOOM), region.area[at]);
        }
    }

    /** Elevation, metres, at a latitude and longitude, bilinear between {@value #FINE_ZOOM}-zoom pixels. */
    public double metres(double latitude, double longitude) {
        double fx = pixelX(longitude, FINE_ZOOM) - 0.5, fy = pixelY(latitude, FINE_ZOOM) - 0.5;
        long ix = (long) Math.floor(fx), iy = (long) Math.floor(fy);
        double tx = fx - ix, ty = fy - iy;
        return (fine(ix, iy) * (1 - tx) + fine(ix + 1, iy) * tx) * (1 - ty) + (fine(ix, iy + 1) * (1 - tx) + fine(ix + 1, iy + 1) * tx) * ty;
    }

    private float fine(long x, long y) {
        long size = (long) TILE << FINE_ZOOM;
        x = Math.floorMod(x, size);
        y = Math.max(0, Math.min(size - 1, y));
        long key = (x / TILE) << 32 | y / TILE;
        Last last = lastFine.get();
        if (last.key != key) {
            last.pixels = tiles.tile(FINE_ZOOM, (int) (x / TILE), (int) (y / TILE));
            last.key = key;
        }
        return last.pixels[(int) ((y % TILE) * TILE + x % TILE)];
    }

    static double pixelX(double longitude, int zoom) { return (longitude + 180) / 360 * ((long) TILE << zoom); }

    static double pixelY(double latitude, int zoom) {
        double clamped = Math.max(-MercatorGrid.MAX_LATITUDE, Math.min(MercatorGrid.MAX_LATITUDE, latitude)), r = Math.toRadians(clamped);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * ((long) TILE << zoom);
    }

    static double longitude(double pixelX, int zoom) { return pixelX / ((long) TILE << zoom) * 360 - 180; }

    static double latitude(double pixelY, int zoom) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * pixelY / ((long) TILE << zoom)))));
    }

    private Region region(int tx, int ty) {
        long key = (long) tx << 32 | ty;
        Region region;
        synchronized (memory) { region = memory.get(key); }
        if (region != null) return region;
        CompletableFuture<Region> mine = new CompletableFuture<>(), shared = inFlight.putIfAbsent(key, mine);
        if (shared == null) {
            try {
                Region made = drain(tx, ty);
                synchronized (memory) {
                    memory.put(key, made);
                    var iterator = memory.entrySet().iterator();
                    while (memory.size() > maxRegions && iterator.hasNext()) { iterator.next(); iterator.remove(); }
                }
                mine.complete(made);
            } catch (Throwable failure) {
                mine.completeExceptionally(failure);
                throw failure;
            } finally {
                inFlight.remove(key, mine);
            }
            shared = mine;
        }
        return shared.join();
    }

    private Region drain(int tx, int ty) {
        int across = 1 << ZOOM;
        float[] height = new float[SIDE * SIDE];
        List<Runnable> loads = new ArrayList<>();
        for (int j = -SPREAD; j <= SPREAD; j++) for (int i = -SPREAD; i <= SPREAD; i++) {
            int ox = (i + SPREAD) * TILE, oy = (j + SPREAD) * TILE, x = Math.floorMod(tx + i, across), y = ty + j;
            loads.add(() -> {
                float[] tile = y < 0 || y >= across ? null : tiles.tile(ZOOM, x, y);
                for (int r = 0; r < TILE; r++) for (int c = 0; c < TILE; c++)
                    height[(oy + r) * SIDE + ox + c] = tile == null ? 0 : tile[r * TILE + c];
            });
        }
        RangeFiles.all(loads);
        long originX = (long) (tx - SPREAD) * TILE, originY = (long) (ty - SPREAD) * TILE;
        double[] weight = new double[SIDE * SIDE];
        double[] row = new double[SIDE];
        for (int y = 0; y < SIDE; y++) {
            double metres = 2 * Math.PI * MercatorGrid.RADIUS / ((long) TILE << ZOOM) * Math.cos(Math.toRadians(latitude(originY + y + 0.5, ZOOM)));
            row[y] = metres * metres / 1e6;
        }
        double[] wet = wetness(originX, originY);
        int points = SIDE / RAIN_STEP + 1;
        for (int y = 0; y < SIDE; y++) for (int x = 0; x < SIDE; x++) {
            int i = Math.min(points - 2, x / RAIN_STEP), j = Math.min(points - 2, y / RAIN_STEP);
            double u = (x - i * RAIN_STEP) / (double) RAIN_STEP, v = (y - j * RAIN_STEP) / (double) RAIN_STEP;
            double share = (wet[j * points + i] * (1 - u) + wet[j * points + i + 1] * u) * (1 - v)
                    + (wet[(j + 1) * points + i] * (1 - u) + wet[(j + 1) * points + i + 1] * u) * v;
            weight[y * SIDE + x] = row[y] * share;
        }
        int[] down = new int[SIDE * SIDE], order = flood(height, down);
        double[] area = weight;
        for (int k = order.length - 1; k >= 0; k--) {
            int at = order[k], to = down[at];
            if (to >= 0) area[to] += area[at];
        }
        boolean[] kept = keep(height, down, order, area);
        int start = SPREAD * TILE;
        byte[] step = new byte[TILE * TILE];
        float[] gathered = new float[TILE * TILE];
        for (int r = 0; r < TILE; r++) for (int c = 0; c < TILE; c++) {
            int at = (start + r) * SIDE + start + c, out = r * TILE + c;
            step[out] = -1;
            if (!kept[at] || down[at] < 0) continue;
            int dx = down[at] % SIDE - at % SIDE, dy = down[at] / SIDE - at / SIDE;
            for (int d = 0; d < 8; d++) if (NX[d] == dx && NY[d] == dy) step[out] = (byte) d;
            gathered[out] = (float) area[at];
        }
        return new Region(step, gathered);
    }

    /** Share of its rain the ground sends on, at points {@value #RAIN_STEP} pixels apart over the window. */
    private double[] wetness(long originX, long originY) {
        int points = SIDE / RAIN_STEP + 1;
        double[] wet = new double[points * points];
        for (int j = 0; j < points; j++) for (int i = 0; i < points; i++) {
            if (rain == null) { wet[j * points + i] = 1; continue; }
            double mm = rain.millimetres(latitude(originY + j * RAIN_STEP, ZOOM), longitude(originX + i * RAIN_STEP, ZOOM));
            wet[j * points + i] = Double.isNaN(mm) ? 0 : Math.max(0, Math.min(1, (mm - DRY) / (WET - DRY)));
        }
        return wet;
    }

    /**
     * Floods the window from its edge, lowest first: each pixel drains to the pixel the water
     * reached it from ({@code down}, -1 on the edge). Returns the pixels in the order reached, so
     * every pixel comes after the one it drains to.
     */
    static int[] flood(float[] height, int[] down) {
        int n = height.length, side = (int) Math.round(Math.sqrt(n));
        boolean[] reached = new boolean[n];
        int[] order = new int[n], byTicket = new int[n];
        long[] heap = new long[n];
        int size = 0, ticket = 0, popped = 0;
        for (int at = 0; at < n; at++) {
            int x = at % side, y = at / side;
            if (x == 0 || y == 0 || x == side - 1 || y == side - 1) {
                reached[at] = true; down[at] = -1;
                byTicket[ticket] = at;
                size = push(heap, size, key(height[at], ticket++));
            }
        }
        while (size > 0) {
            long top = heap[0];
            size = pop(heap, size);
            int at = byTicket[(int) top];
            float level = level(top);
            order[popped++] = at;
            int x = at % side, y = at / side;
            for (int d = 0; d < 8; d++) {
                int nx = x + NX[d], ny = y + NY[d];
                if (nx < 0 || ny < 0 || nx >= side || ny >= side) continue;
                int next = ny * side + nx;
                if (reached[next]) continue;
                reached[next] = true;
                down[next] = at;
                byTicket[ticket] = next;
                size = push(heap, size, key(Math.max(level, height[next]), ticket++));
            }
        }
        return order;
    }

    /** Heap key: the water level, ordered as a signed int, over the ticket that breaks ties first come first. */
    private static long key(float level, int ticket) {
        int bits = Float.floatToIntBits(level);
        return (long) (bits ^ (bits >> 31 & 0x7FFFFFFF)) << 32 | ticket;
    }

    private static float level(long key) {
        int ordered = (int) (key >> 32);
        return Float.intBitsToFloat(ordered ^ (ordered >> 31 & 0x7FFFFFFF));
    }

    private static int push(long[] heap, int size, long value) {
        int at = size++;
        while (at > 0) {
            int parent = (at - 1) >> 1;
            if (heap[parent] <= value) break;
            heap[at] = heap[parent];
            at = parent;
        }
        heap[at] = value;
        return size;
    }

    private static int pop(long[] heap, int size) {
        long last = heap[--size];
        int at = 0;
        while (true) {
            int child = 2 * at + 1;
            if (child >= size) break;
            if (child + 1 < size && heap[child + 1] < heap[child]) child++;
            if (heap[child] >= last) break;
            heap[at] = heap[child];
            at = child;
        }
        if (size > 0) heap[at] = last;
        return size;
    }

    /** Stream pixels in valleys, with short gaps between them filled and short pieces dropped. */
    static boolean[] keep(float[] height, int[] down, int[] order, double[] area) {
        int n = height.length, side = (int) Math.round(Math.sqrt(n));
        boolean[] stream = new boolean[n], valley = new boolean[n];
        float[] ring = new float[16];
        int[] rx = new int[16], ry = new int[16];
        for (int k = 0; k < 16; k++) {
            rx[k] = (int) Math.round(RING * Math.cos(2 * Math.PI * k / 16));
            ry[k] = (int) Math.round(RING * Math.sin(2 * Math.PI * k / 16));
        }
        for (int at = 0; at < n; at++) {
            if (area[at] < STREAM_AREA) continue;
            stream[at] = true;
            int x = at % side, y = at / side;
            if (x < RING || y < RING || x >= side - RING || y >= side - RING) continue;
            for (int k = 0; k < 16; k++) ring[k] = height[(y + ry[k]) * side + x + rx[k]];
            java.util.Arrays.sort(ring);
            valley[at] = (ring[7] + ring[8]) / 2 - height[at] >= VALLEY;
        }
        // Pixels since the last valley pixel upstream, and to the next one downstream.
        int far = Integer.MAX_VALUE / 4;
        int[] up = new int[n], ahead = new int[n];
        java.util.Arrays.fill(up, far);
        for (int k = n - 1; k >= 0; k--) {
            int at = order[k];
            if (!stream[at]) continue;
            if (valley[at]) up[at] = 0;
            int to = down[at];
            if (to >= 0 && stream[to]) up[to] = Math.min(up[to], up[at] + 1);
        }
        for (int k = 0; k < n; k++) {
            int at = order[k];
            if (!stream[at]) continue;
            int to = down[at];
            ahead[at] = valley[at] ? 0 : to >= 0 && stream[to] ? ahead[to] + 1 : far;
        }
        boolean[] kept = new boolean[n];
        for (int at = 0; at < n; at++) kept[at] = stream[at] && (valley[at] || up[at] + ahead[at] <= GAP);
        // Pieces: kept pixels in a row upstream (the longest branch) and downstream.
        int[] above = new int[n], below = new int[n];
        for (int k = n - 1; k >= 0; k--) {
            int at = order[k];
            if (!kept[at]) continue;
            int to = down[at];
            if (to >= 0 && kept[to]) above[to] = Math.max(above[to], above[at] + 1);
        }
        for (int k = 0; k < n; k++) {
            int at = order[k];
            if (!kept[at]) continue;
            int to = down[at];
            below[at] = to >= 0 && kept[to] ? below[to] + 1 : 0;
        }
        for (int at = 0; at < n; at++) if (kept[at] && above[at] + below[at] + 1 < PIECE) kept[at] = false;
        return kept;
    }
}
