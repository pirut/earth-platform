package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.util.ArrayList;
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
    public static final int ZOOM = 10, SPREAD = 1;
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
    private final LruMap<Long, Region> memory = new LruMap<>(16);
    private final Map<Long, CompletableFuture<Region>> inFlight = new ConcurrentHashMap<>();
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

    /** Drains every tile an area's streams come from, several at a time, so later reads never wait. */
    public void prefetch(double south, double west, double north, double east) {
        int across = 1 << ZOOM;
        int x0 = (int) Math.floor(pixelX(west, ZOOM) / TILE), x1 = (int) Math.floor(pixelX(east, ZOOM) / TILE);
        int y0 = Math.max(0, (int) Math.floor(pixelY(north, ZOOM) / TILE)), y1 = Math.min(across - 1, (int) Math.floor(pixelY(south, ZOOM) / TILE));
        List<Runnable> loads = new ArrayList<>();
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int tx = Math.floorMod(x, across), ty = y;
            loads.add(() -> region(tx, ty));
        }
        RangeFiles.all(loads);
    }

    /**
     * Elevation, metres, over a grid of latitudes and longitudes, bilinear between
     * {@value #FINE_ZOOM}-zoom pixels: {@code out[j * longitudes.length + i]} at the j-th latitude and
     * i-th longitude, each tile read once.
     */
    public void metres(double[] latitudes, double[] longitudes, float[] out) {
        int across = longitudes.length;
        long size = (long) TILE << FINE_ZOOM;
        long[] ix = new long[across];
        double[] tx = new double[across];
        for (int i = 0; i < across; i++) {
            double fx = pixelX(longitudes[i], FINE_ZOOM) - 0.5;
            ix[i] = (long) Math.floor(fx);
            tx[i] = fx - ix[i];
        }
        Map<Long, float[]> read = new java.util.HashMap<>();
        long[] key = {-1, -1};
        float[][] last = new float[2][];
        for (int j = 0; j < latitudes.length; j++) {
            double fy = pixelY(latitudes[j], FINE_ZOOM) - 0.5;
            long iy = (long) Math.floor(fy);
            double ty = fy - iy;
            long y0 = Math.max(0, Math.min(size - 1, iy)), y1 = Math.max(0, Math.min(size - 1, iy + 1));
            for (int i = 0; i < across; i++) {
                long x0 = Math.floorMod(ix[i], size), x1 = Math.floorMod(ix[i] + 1, size);
                float a = fine(read, key, last, x0, y0), b = fine(read, key, last, x1, y0);
                float c = fine(read, key, last, x0, y1), d = fine(read, key, last, x1, y1);
                out[j * across + i] = (float) ((a * (1 - tx[i]) + b * tx[i]) * (1 - ty) + (c * (1 - tx[i]) + d * tx[i]) * ty);
            }
        }
    }

    /** A fine pixel through a grid read's own tiles, its last two kept at hand. */
    private float fine(Map<Long, float[]> read, long[] key, float[][] last, long x, long y) {
        long k = (x / TILE) << 32 | y / TILE;
        float[] pixels;
        if (key[0] == k) pixels = last[0];
        else if (key[1] == k) pixels = last[1];
        else {
            pixels = read.computeIfAbsent(k, ignored -> tiles.tile(FINE_ZOOM, (int) (x / TILE), (int) (y / TILE)));
            key[1] = key[0]; last[1] = last[0];
            key[0] = k; last[0] = pixels;
        }
        return pixels[(int) ((y % TILE) * TILE + x % TILE)];
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
        TickShare.giveWay();
        CompletableFuture<Region> mine = new CompletableFuture<>(), shared = inFlight.putIfAbsent(key, mine);
        if (shared == null) {
            try {
                Region made = drain(tx, ty);
                synchronized (memory) {
                    memory.putAndTrim(key, made, maxRegions);
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
        long originX = (long) (tx - SPREAD) * TILE, originY = (long) (ty - SPREAD) * TILE;
        // The rain by rows of its points, which may each wait on a download: read while the land is
        // fetched and flooded, as only the ground's share of water needs it.
        int points = SIDE / RAIN_STEP + 1;
        double[] wet = new double[points * points];
        List<Runnable> rainRows = new ArrayList<>();
        for (int j = 0; j < points; j++) {
            int row = j;
            rainRows.add(() -> wetness(originX, originY, row, wet));
        }
        java.util.concurrent.FutureTask<Void> rained = new java.util.concurrent.FutureTask<>(() -> RangeFiles.all(rainRows), null);
        Thread.ofVirtual().start(rained);
        RangeFiles.all(loads);
        int[] down = new int[SIDE * SIDE], order = flood(height, down);
        try {
            rained.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MissingTerrainException("Interrupted while reading rain");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failed.getCause());
        }
        float[] area = new float[SIDE * SIDE];
        double[] row = new double[SIDE];
        for (int y = 0; y < SIDE; y++) {
            double metres = 2 * Math.PI * MercatorGrid.RADIUS / ((long) TILE << ZOOM) * Math.cos(Math.toRadians(latitude(originY + y + 0.5, ZOOM)));
            row[y] = metres * metres / 1e6;
        }
        for (int y = 0; y < SIDE; y++) for (int x = 0; x < SIDE; x++) {
            int i = Math.min(points - 2, x / RAIN_STEP), j = Math.min(points - 2, y / RAIN_STEP);
            double u = (x - i * RAIN_STEP) / (double) RAIN_STEP, v = (y - j * RAIN_STEP) / (double) RAIN_STEP;
            double share = (wet[j * points + i] * (1 - u) + wet[j * points + i + 1] * u) * (1 - v)
                    + (wet[(j + 1) * points + i] * (1 - u) + wet[(j + 1) * points + i + 1] * u) * v;
            area[y * SIDE + x] = (float) (row[y] * share);
        }
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
            gathered[out] = area[at];
        }
        return new Region(step, gathered);
    }

    /** Share of its rain the ground sends on, at one row of points {@value #RAIN_STEP} pixels apart over the window. */
    private void wetness(long originX, long originY, int j, double[] wet) {
        int points = SIDE / RAIN_STEP + 1;
        for (int i = 0; i < points; i++) {
            if (rain == null) { wet[j * points + i] = 1; continue; }
            double mm = rain.millimetres(latitude(originY + j * RAIN_STEP, ZOOM), longitude(originX + i * RAIN_STEP, ZOOM));
            wet[j * points + i] = Double.isNaN(mm) ? 0 : Math.max(0, Math.min(1, (mm - DRY) / (WET - DRY)));
        }
    }

    /**
     * Floods the window from its edge, lowest first: each pixel drains to the pixel the water
     * reached it from ({@code down}, -1 on the edge). Returns the pixels in the order reached, so
     * every pixel comes after the one it drains to.
     */
    static int[] flood(float[] height, int[] down) {
        int n = height.length, side = (int) Math.round(Math.sqrt(n));
        boolean[] reached = new boolean[n];
        int[] order = new int[n];
        // Pixels no higher than the water that reached them fill at its level, before anything
        // higher: they skip the heap and wait in a plain queue, which most of a pit or flat does.
        int[] pit = new int[n];
        float[] water = new float[n];
        long[] heap = new long[4 * side];
        int size = 0, head = 0, tail = 0, popped = 0;
        for (int at = 0; at < n; at++) {
            int x = at % side, y = at / side;
            if (x == 0 || y == 0 || x == side - 1 || y == side - 1) {
                reached[at] = true; down[at] = -1;
                if (size == heap.length) heap = java.util.Arrays.copyOf(heap, size * 2);
                size = push(heap, size, key(height[at], at));
            }
        }
        while (head < tail || size > 0) {
            int at;
            float level;
            if (head < tail) {
                at = pit[head++];
                level = water[at];
            } else {
                long top = heap[0];
                size = pop(heap, size);
                at = (int) top;
                level = level(top);
            }
            order[popped++] = at;
            int x = at % side, y = at / side;
            boolean inside = x > 0 && y > 0 && x < side - 1 && y < side - 1;
            for (int d = 0; d < 8; d++) {
                int nx = x + NX[d], ny = y + NY[d];
                if (!inside && (nx < 0 || ny < 0 || nx >= side || ny >= side)) continue;
                int next = ny * side + nx;
                if (reached[next]) continue;
                reached[next] = true;
                down[next] = at;
                if (height[next] <= level) {
                    water[next] = level;
                    pit[tail++] = next;
                } else {
                    if (size == heap.length) heap = java.util.Arrays.copyOf(heap, size * 2);
                    size = push(heap, size, key(height[next], next));
                }
            }
        }
        return order;
    }

    /** Heap key: the water level, ordered as a signed int, over the pixel, which breaks ties. */
    private static long key(float level, int pixel) {
        int bits = Float.floatToIntBits(level);
        return (long) (bits ^ (bits >> 31 & 0x7FFFFFFF)) << 32 | pixel;
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
    static boolean[] keep(float[] height, int[] down, int[] order, float[] area) {
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
        int[] above = up, below = ahead;
        java.util.Arrays.fill(above, 0);
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
