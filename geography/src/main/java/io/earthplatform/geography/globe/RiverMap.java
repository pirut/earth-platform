package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static io.earthplatform.geography.globe.Morphology.NX;
import static io.earthplatform.geography.globe.Morphology.NY;
import static io.earthplatform.geography.globe.Morphology.at;
import static io.earthplatform.geography.globe.Morphology.grow;
import static io.earthplatform.geography.globe.Morphology.label;
import static io.earthplatform.geography.globe.Morphology.lowest;
import static io.earthplatform.geography.globe.Morphology.open;
import static io.earthplatform.geography.globe.Morphology.prune;
import static io.earthplatform.geography.globe.Morphology.thin;

/**
 * Narrow rivers and streams, traced from {@link SurfaceWaterSource}'s occurrence pixels, as a
 * smooth strength from 0 to 1 that callers cut at {@link #EDGE} for a river's outline.
 *
 * <p>Land cover maps drop rivers narrower than a few of its pixels, and forest canopy hides
 * stretches of them from every satellite, so a river reads as a broken line of faint pixels,
 * often a fifth of the time or less under water. Each 256-pixel square is traced with a margin
 * round it, so neighbours agree along their seam:
 * <ol>
 * <li>occurrence rises to full strength by {@value #FULL} percent, ignoring flooded forest's few;
 * <li>gaps of up to {@value #CLOSE} pixels between strong pixels on a line are closed;
 * <li>the line's ends, found on its skeleton, join the nearest end facing them across a gap of
 * up to {@value #JOIN} pixels along a smooth curve, or else the water they point at, as a stream
 * meets its river;
 * <li>water wider than {@value #WIDE} pixels is left out: it is a lake, a wide river or the sea,
 * which land cover already holds, or seasonal floodplain the land cover rightly calls land.
 * </ol>
 * Strength between pixel centres follows a cubic B-spline, so outlines curve as rivers do.
 *
 * <p>Rivers narrower than the occurrence pixels, which no satellite water map holds, come from
 * {@link Drainage}'s streams where it is given: each step of a stream is laid along the valley
 * floor of the finer elevation between the lowest points by its two ends, as a line one pixel
 * wide and stronger for the more ground it drains. Next to a narrow river the occurrence already
 * shows, the stream gives way to it and joins it as its gaps do.
 */
public final class RiverMap {
    /** Strength at a river's edge. */
    public static final double EDGE = 0.3;
    /** Occurrence (percent) below which a pixel is no river, and from which it is wholly one. */
    static final int NONE = 3, FULL = 15;
    /** Longest gap, in pixels, closed between strong pixels on a line; and between a line's facing ends. */
    static final int CLOSE = 3, JOIN = 36;
    /** Pixels walked back from a line's end to find which way it heads. */
    private static final int WALK = 10;
    /** Pixels in a line long enough to be the river rather than a glimpse of it, and glimpses crossed in one go. */
    static final int LONG = 6, HOPS = 8;
    /** Most water pixels round a line's end, in five by five, for a narrow river; and pixels per meander bend on a join. */
    static final int NARROW = 12, MEANDER = 9;
    /** Radius, in pixels, of the widest water left out as a lake or wide river (it fits a disc this wide). */
    static final int WIDE = 3;
    private static final int TILE = 256, MARGIN = 56, SPAN = TILE + 2 * MARGIN, CELL = 16;
    private static final float SOLID = 0.5f;
    /** Pixels round a stream step's ends searched for the valley floor; extra pixels round a step its course may take. */
    static final int SNAP = 3, ROOM = 4;
    /** Cost, per metre above the lowest ground within {@value #FLOOR} pixels, of a stream's course leaving the valley floor. */
    static final double CLIMB = 30;
    static final int FLOOR = 3;
    /** Longest spur, in pixels, pruned off stream courses; and pixels round a narrow river's water a stream gives way within. */
    static final int SPUR = 3, BESIDE = 12;
    /** Strength of the smallest stream's line, enough that it stays unbroken where it runs aslant. */
    private static final float STREAM = 0.7f;

    private final SurfaceWaterSource source;
    private final Drainage drainage;
    private final int maxTiles;
    private final LruMap<Long, Traced> memory = new LruMap<>(64);
    private final Map<Long, CompletableFuture<Traced>> inFlight = new ConcurrentHashMap<>();
    private final ThreadLocal<Traced> recent = new ThreadLocal<>();

    /** One traced square: strength per pixel (0 to 255), and the most in each {@value #CELL}-pixel cell. */
    private record Traced(long key, byte[] strength, byte[] cells) {}

    public RiverMap(SurfaceWaterSource source, int maxTiles) {
        this(source, null, maxTiles);
    }

    /** @param drainage streams too narrow for the occurrence pixels, or null for the occurrence alone */
    public RiverMap(SurfaceWaterSource source, Drainage drainage, int maxTiles) {
        this.source = Objects.requireNonNull(source);
        this.drainage = drainage;
        if (maxTiles < 4) throw new IllegalArgumentException("River map needs room for at least four squares");
        this.maxTiles = maxTiles;
    }

    /** River strength, 0 to 1, at a latitude and longitude. */
    public double strength(double latitude, double longitude) {
        double fx = (longitude + 180) * SurfaceWaterSource.PIXELS_PER_DEGREE - 0.5;
        double fy = (SurfaceWaterSource.NORTH - latitude) * SurfaceWaterSource.PIXELS_PER_DEGREE - 0.5;
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
        double tx = fx - ix, ty = fy - iy;
        double[] wx = spline(tx), wy = spline(ty);
        double sum = 0;
        for (int j = 0; j < 4; j++) {
            double row = 0;
            for (int i = 0; i < 4; i++) row += wx[i] * pixel(ix - 1 + i, iy - 1 + j);
            sum += wy[j] * row;
        }
        return sum / 255.0;
    }

    /** How often, 0 to 100 percent, the surface water record saw water at a latitude and longitude. */
    public int occurrence(double latitude, double longitude) {
        return source.occurrence(latitude, longitude);
    }

    /**
     * The greatest strength, 0 to 1, of any pixel within about {@value #CELL} pixels (half a
     * kilometre) of a latitude and longitude: zero means no river near.
     */
    public double near(double latitude, double longitude) {
        int column = (int) Math.floor((longitude + 180) * SurfaceWaterSource.PIXELS_PER_DEGREE);
        int row = (int) Math.floor((SurfaceWaterSource.NORTH - latitude) * SurfaceWaterSource.PIXELS_PER_DEGREE);
        int cx = Math.floorDiv(column, CELL), cy = Math.floorDiv(row, CELL), most = 0;
        for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
            int x = (cx + i) * CELL, y = (cy + j) * CELL;
            if (y < 0 || y >= SurfaceWaterSource.DOWN) continue;
            Traced traced = traced(x, y);
            int lx = Math.floorMod(x, SurfaceWaterSource.ACROSS) % TILE, ly = y % TILE;
            most = Math.max(most, traced.cells[(ly / CELL) * (TILE / CELL) + lx / CELL] & 0xFF);
        }
        return most / 255.0;
    }

    /** Traces every square an area needs, several at a time, so later reads never wait. */
    public void prefetch(double south, double west, double north, double east) {
        int r0 = (int) Math.floor((SurfaceWaterSource.NORTH - Math.min(SurfaceWaterSource.NORTH, north)) * SurfaceWaterSource.PIXELS_PER_DEGREE) / TILE;
        int r1 = (int) Math.floor((SurfaceWaterSource.NORTH - Math.max(SurfaceWaterSource.SOUTH, south)) * SurfaceWaterSource.PIXELS_PER_DEGREE) / TILE;
        int c0 = (int) Math.floor((west + 180) * SurfaceWaterSource.PIXELS_PER_DEGREE) / TILE, c1 = (int) Math.floor((east + 180) * SurfaceWaterSource.PIXELS_PER_DEGREE) / TILE;
        List<Runnable> loads = new ArrayList<>();
        // The streams' drained land is the longest wait, so it starts at once beside the water record's reads.
        if (drainage != null) {
            double margin = MARGIN / SurfaceWaterSource.PIXELS_PER_DEGREE;
            double top = SurfaceWaterSource.NORTH - (double) Math.max(0, r0) * TILE / SurfaceWaterSource.PIXELS_PER_DEGREE + margin;
            double bottom = SurfaceWaterSource.NORTH - (double) (r1 + 1) * TILE / SurfaceWaterSource.PIXELS_PER_DEGREE - margin;
            double left = (double) c0 * TILE / SurfaceWaterSource.PIXELS_PER_DEGREE - 180 - margin;
            double right = (double) (c1 + 1) * TILE / SurfaceWaterSource.PIXELS_PER_DEGREE - 180 + margin;
            loads.add(() -> drainage.prefetch(bottom, left, top, right));
        }
        for (int r = Math.max(0, r0); r <= r1 && r * TILE < SurfaceWaterSource.DOWN; r++)
            for (int c = c0; c <= c1; c++) { int row = r * TILE, column = c * TILE; loads.add(() -> traced(column, row)); }
        RangeFiles.all(loads);
    }

    private static double[] spline(double t) {
        double s = 1 - t;
        return new double[]{s * s * s / 6, (4 - 6 * t * t + 3 * t * t * t) / 6, (4 - 6 * s * s + 3 * s * s * s) / 6, t * t * t / 6};
    }

    private int pixel(int column, int row) {
        if (row < 0 || row >= SurfaceWaterSource.DOWN) return 0;
        Traced traced = traced(column, row);
        return traced.strength[(row % TILE) * TILE + Math.floorMod(column, SurfaceWaterSource.ACROSS) % TILE] & 0xFF;
    }

    private Traced traced(int column, int row) {
        column = Math.floorMod(column, SurfaceWaterSource.ACROSS);
        long key = (long) (row / TILE) << 32 | column / TILE;
        Traced last = recent.get();
        if (last != null && last.key == key) return last;
        Traced traced;
        synchronized (memory) { traced = memory.get(key); }
        if (traced == null) {
            CompletableFuture<Traced> mine = new CompletableFuture<>(), shared = inFlight.putIfAbsent(key, mine);
            if (shared == null) {
                try {
                    Traced made = trace(key, (column / TILE) * TILE, (row / TILE) * TILE);
                    synchronized (memory) {
                        memory.putAndTrim(key, made, maxTiles);
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
            traced = shared.join();
        }
        recent.set(traced);
        return traced;
    }

    // Tracing -------------------------------------------------------------------------------------

    /** Steps along eight directions, for closing gaps on lines. */
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}, {2, 1}, {1, 2}, {2, -1}, {1, -2}};
    /** Steps along each of {@link #DIRECTIONS} that span a gap of {@value #CLOSE} pixels. */
    private static final int[] CLOSE_STEPS = java.util.Arrays.stream(DIRECTIONS).mapToInt(d -> Math.max(1, (int) Math.round(CLOSE / Math.hypot(d[0], d[1])))).toArray();

    private Traced trace(long key, int x0, int y0) {
        // The streams are worked out while the occurrence pixels are read, which may wait on downloads.
        java.util.concurrent.FutureTask<float[]> streams = drainage == null ? null : new java.util.concurrent.FutureTask<>(() -> streams(x0 - MARGIN, y0 - MARGIN));
        if (streams != null) Thread.ofVirtual().start(streams);
        int[] occurrence = new int[SPAN * SPAN];
        for (int j = 0; j < SPAN; j++) for (int i = 0; i < SPAN; i++)
            occurrence[j * SPAN + i] = source.occurrence(x0 - MARGIN + i, y0 - MARGIN + j);
        float[] strength = trace(occurrence, streams == null ? null : finished(streams), SPAN);
        byte[] out = new byte[TILE * TILE], cells = new byte[(TILE / CELL) * (TILE / CELL)];
        for (int j = 0; j < TILE; j++) for (int i = 0; i < TILE; i++) {
            int value = Math.round(Math.min(1, strength[(j + MARGIN) * SPAN + i + MARGIN]) * 255);
            out[j * TILE + i] = (byte) value;
            int cell = (j / CELL) * (TILE / CELL) + i / CELL;
            if (value > (cells[cell] & 0xFF)) cells[cell] = (byte) value;
        }
        return new Traced(key, out, cells);
    }

    private static float[] finished(java.util.concurrent.FutureTask<float[]> task) {
        try {
            return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MissingTerrainException("Interrupted while tracing streams");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failed.getCause());
        }
    }

    /** Strength, 0 to 1, per pixel of a square {@code span} pixels across from its occurrence pixels. */
    static float[] trace(int[] occurrence, int span) {
        return trace(occurrence, null, span);
    }

    /** As {@link #trace(int[], int)}, with streams' strength per pixel laid in where no narrow river shows beside them. */
    static float[] trace(int[] occurrence, float[] streams, int span) {
        int n = span * span;
        float[] v = new float[n];
        for (int k = 0; k < n; k++) v[k] = Math.max(0, Math.min(1, (occurrence[k] - NONE) / (float) (FULL - NONE)));
        float[] f = close(v, span);
        boolean[] solid = new boolean[n];
        for (int k = 0; k < n; k++) solid[k] = f[k] >= SOLID;
        if (streams != null) {
            boolean[] wide = open(solid, span, WIDE), narrow = new boolean[n];
            for (int k = 0; k < n; k++) narrow[k] = solid[k] && !wide[k];
            boolean[] beside = grow(narrow, span, BESIDE);
            for (int k = 0; k < n; k++) if (streams[k] > 0 && !beside[k]) {
                f[k] = Math.max(f[k], streams[k]);
                solid[k] |= streams[k] >= SOLID;
            }
        }
        join(f, solid, span);
        // Leave out wide water: pixels inside a disc of the given radius that fits wholly in water, and the disc.
        boolean[] wide = open(solid, span, WIDE);
        for (int k = 0; k < n; k++) if (wide[k]) f[k] = 0;
        return f;
    }

    /**
     * Drainage's streams over a square of pixels from a corner: each step from the lowest point
     * near one end to the lowest near the other along the cheapest course, where leaving the
     * valley floor costs {@value #CLIMB} a metre, with spurs off the courses pruned. Strength per
     * pixel, rising from {@link #STREAM} for the smallest stream to full for a river.
     */
    private float[] streams(int column, int row) {
        int span = SPAN, n = span * span;
        float[] height = new float[n];
        double[] latitudes = new double[span], longitudes = new double[span];
        for (int j = 0; j < span; j++) latitudes[j] = SurfaceWaterSource.NORTH - (row + j + 0.5) / SurfaceWaterSource.PIXELS_PER_DEGREE;
        for (int i = 0; i < span; i++) longitudes[i] = (column + i + 0.5) / SurfaceWaterSource.PIXELS_PER_DEGREE - 180;
        drainage.metres(latitudes, longitudes, height);
        float[] floor = lowest(height, span, FLOOR);
        float[] streams = new float[n];
        double north = SurfaceWaterSource.NORTH - (double) row / SurfaceWaterSource.PIXELS_PER_DEGREE;
        double south = SurfaceWaterSource.NORTH - (double) (row + span) / SurfaceWaterSource.PIXELS_PER_DEGREE;
        double west = (double) column / SurfaceWaterSource.PIXELS_PER_DEGREE - 180, east = (double) (column + span) / SurfaceWaterSource.PIXELS_PER_DEGREE - 180;
        drainage.links(south, west, north, east, (latitude, longitude, toLatitude, toLongitude, area) -> {
            int ax = (int) Math.floor((longitude + 180) * SurfaceWaterSource.PIXELS_PER_DEGREE) - column;
            int ay = (int) Math.floor((SurfaceWaterSource.NORTH - latitude) * SurfaceWaterSource.PIXELS_PER_DEGREE) - row;
            int bx = (int) Math.floor((toLongitude + 180) * SurfaceWaterSource.PIXELS_PER_DEGREE) - column;
            int by = (int) Math.floor((SurfaceWaterSource.NORTH - toLatitude) * SurfaceWaterSource.PIXELS_PER_DEGREE) - row;
            if (ax < 0 || ay < 0 || bx < 0 || by < 0 || ax >= span || ay >= span || bx >= span || by >= span) return;
            double share = Math.log(Math.max(1, area / Drainage.STREAM_AREA)) / Math.log(Drainage.RIVER_AREA / Drainage.STREAM_AREA);
            float strength = (float) (STREAM + (1 - STREAM) * Math.min(1, share));
            course(height, floor, streams, span, snap(height, span, ax, ay), snap(height, span, bx, by), strength);
        });
        boolean[] on = new boolean[n];
        for (int k = 0; k < n; k++) on[k] = streams[k] > 0;
        prune(on, span, SPUR);
        for (int k = 0; k < n; k++) if (!on[k]) streams[k] = 0;
        return streams;
    }

    /** The lowest pixel within {@value #SNAP} pixels of one, as its index. */
    private static int snap(float[] height, int span, int x, int y) {
        int best = -1;
        for (int j = Math.max(0, y - SNAP); j <= Math.min(span - 1, y + SNAP); j++)
            for (int i = Math.max(0, x - SNAP); i <= Math.min(span - 1, x + SNAP); i++)
                if (best < 0 || height[j * span + i] < height[best]) best = j * span + i;
        return best;
    }

    /** Lays the cheapest course from one pixel to another, within {@value #ROOM} pixels of the box round them, at a strength. */
    private static void course(float[] height, float[] floor, float[] out, int span, int from, int to, float strength) {
        int fx = from % span, fy = from / span, tx = to % span, ty = to / span;
        int x0 = Math.max(0, Math.min(fx, tx) - ROOM), y0 = Math.max(0, Math.min(fy, ty) - ROOM);
        int x1 = Math.min(span - 1, Math.max(fx, tx) + ROOM), y1 = Math.min(span - 1, Math.max(fy, ty) + ROOM);
        int w = x1 - x0 + 1, h = y1 - y0 + 1, n = w * h;
        double[] cost = new double[n];
        int[] previous = new int[n];
        java.util.Arrays.fill(cost, Double.MAX_VALUE);
        java.util.PriorityQueue<double[]> queue = new java.util.PriorityQueue<>((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int start = (fy - y0) * w + fx - x0, goal = (ty - y0) * w + tx - x0;
        cost[start] = 0;
        previous[start] = -1;
        queue.add(new double[]{0, start});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int at = (int) top[1];
            if (top[0] > cost[at]) continue;
            if (at == goal) break;
            int x = at % w, y = at / w;
            for (int d = 0; d < 8; d++) {
                int nx = x + NX[d], ny = y + NY[d];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int next = ny * w + nx, k = (ny + y0) * span + nx + x0;
                double step = (1 + CLIMB * (height[k] - floor[k])) * (d % 2 == 1 ? Math.sqrt(2) : 1);
                if (cost[at] + step < cost[next]) {
                    cost[next] = cost[at] + step;
                    previous[next] = at;
                    queue.add(new double[]{cost[next], next});
                }
            }
        }
        for (int at = goal; at >= 0; at = at == start ? -1 : previous[at]) {
            int k = (at / w + y0) * span + at % w + x0;
            out[k] = Math.max(out[k], strength);
        }
    }

    /** Pixels raised to most of the weaker of the strongest pixels within a gap on either side of them along a line. */
    static float[] close(float[] v, int span) {
        float[] f = v.clone();
        // A pixel only rises with water on both sides of it within the gap, so nowhere without any near by.
        boolean[] some = new boolean[v.length];
        for (int k = 0; k < v.length; k++) some[k] = v[k] > 0;
        boolean[] near = grow(some, span, CLOSE);
        for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) {
            int k = y * span + x;
            if (!near[k] || v[k] >= 0.8f) continue;
            float best = 0;
            for (int i = 0; i < DIRECTIONS.length; i++) {
                int[] d = DIRECTIONS[i];
                int steps = CLOSE_STEPS[i];
                float ahead = 0, behind = 0;
                for (int s = 1; s <= steps; s++) {
                    ahead = Math.max(ahead, at(v, span, x + d[0] * s, y + d[1] * s));
                    behind = Math.max(behind, at(v, span, x - d[0] * s, y - d[1] * s));
                }
                best = Math.max(best, Math.min(ahead, behind));
            }
            f[k] = Math.max(v[k], best * 0.8f);
        }
        return f;
    }

    /** A line's loose end: where it is, which way it heads, how wide the line is there, and its line. */
    private record End(int x, int y, double dx, double dy, double width, int line) {}

    /**
     * Carries each line on across the gaps in it. From each end of a line at least
     * {@value #LONG} pixels long, the river heads on to the nearest piece of another line ahead of
     * it, within {@value #JOIN} pixels and a cone that narrows with distance, along a smooth curve.
     * A piece that small is only a glimpse of the river under the trees, so it goes on from that
     * piece's far side, up to {@value #HOPS} times, until it meets a longer line, as the rest of
     * the river or the river a stream runs into. Lines already joined are not joined again.
     */
    static void join(float[] f, boolean[] solid, int span) {
        boolean[] skeleton = thin(solid, span);
        int[] line = label(skeleton, span);
        int lines = 0;
        for (int l : line) lines = Math.max(lines, l);
        int[] size = new int[lines + 1], root = new int[lines + 1];
        for (int l : line) size[l]++;
        for (int l = 0; l <= lines; l++) root[l] = l;
        List<End> ends = new ArrayList<>();
        int edge = 4;
        for (int y = edge; y < span - edge; y++) for (int x = edge; x < span - edge; x++) {
            if (!skeleton[y * span + x] || size[line[y * span + x]] < LONG || !end(skeleton, span, x, y)) continue;
            // Walk back along the line, over the corners of its stair steps; a short spur off a junction is no end.
            int px = x, py = y, steps = 0;
            List<Integer> seen = new ArrayList<>(List.of(y * span + x));
            while (steps < WALK) {
                int bx = -1, by = -1, count = 0;
                int[] cx = new int[8], cy = new int[8];
                for (int d = 0; d < 8; d++) {
                    int qx = px + NX[d], qy = py + NY[d];
                    if (!at(skeleton, span, qx, qy) || seen.contains(qy * span + qx)) continue;
                    cx[count] = qx; cy[count++] = qy;
                }
                boolean together = count > 0;
                for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++)
                    together &= Math.abs(cx[i] - cx[j]) <= 1 && Math.abs(cy[i] - cy[j]) <= 1;
                if (!together) break;
                double far = -1;
                for (int i = 0; i < count; i++) {
                    seen.add(cy[i] * span + cx[i]);
                    double d = Math.hypot(cx[i] - x, cy[i] - y);
                    if (d > far) { far = d; bx = cx[i]; by = cy[i]; }
                }
                px = bx; py = by; steps++;
            }
            if (steps < 3) continue;
            double dx = x - px, dy = y - py, length = Math.hypot(dx, dy);
            if (length == 0) continue;
            // Only a narrow river goes on: a pond or lake's outline is no river heading anywhere.
            int wet = wet(solid, span, x, y);
            if (wet > NARROW) continue;
            ends.add(new End(x, y, dx / length, dy / length, Math.max(1, wet / 5.0), line[y * span + x]));
        }
        // Each row's line pixels, by column, for the search ahead of each end.
        int[] rowStart = new int[span + 1], columns;
        for (int k = 0; k < line.length; k++) if (line[k] != 0) rowStart[k / span + 1]++;
        for (int y = 0; y < span; y++) rowStart[y + 1] += rowStart[y];
        columns = new int[rowStart[span]];
        for (int k = 0, at = 0; k < line.length; k++) if (line[k] != 0) columns[at++] = k % span;
        for (End start : ends) {
            End at = start;
            for (int hop = 0; hop < HOPS; hop++) {
                int own = find(root, at.line);
                // The nearest piece of another line ahead; the longer the gap, the straighter ahead it must lie.
                int tx = -1, ty = -1;
                double best = Double.MAX_VALUE;
                for (int y = Math.max(0, at.y - JOIN); y <= Math.min(span - 1, at.y + JOIN); y++)
                    for (int c = first(columns, rowStart[y], rowStart[y + 1], at.x - JOIN); c < rowStart[y + 1] && columns[c] <= at.x + JOIN; c++) {
                        int x = columns[c], l = line[y * span + x];
                        double gx = x - at.x, gy = y - at.y, squared = gx * gx + gy * gy;
                        if (squared > JOIN * JOIN || squared < 1 || find(root, l) == own) continue;
                        double d = Math.hypot(gx, gy), ahead = (at.dx * gx + at.dy * gy) / d;
                        if (ahead < 0.5 + 0.4 * d / JOIN) continue;
                        double score = d * (2 - ahead);
                        if (score < best && wet(solid, span, x, y) <= NARROW) { best = score; tx = x; ty = y; }
                    }
                if (tx < 0) break;
                int target = line[ty * span + tx];
                double gx = tx - at.x, gy = ty - at.y, d = Math.hypot(gx, gy);
                double hx = at.dx + gx / d, hy = at.dy + gy / d, h = Math.hypot(hx, hy);
                hx /= h; hy /= h;
                paint(f, span, at.x, at.y, at.dx * d, at.dy * d, tx, ty, hx * d, hy * d, start.width, (at.x * 31 + at.y * 17) % 7);
                root[find(root, target)] = own;
                if (size[target] >= LONG) break;
                // Go on from the glimpse's far side.
                int fx = tx, fy = ty;
                double furthest = Double.NEGATIVE_INFINITY;
                for (int y = Math.max(0, ty - LONG); y <= Math.min(span - 1, ty + LONG); y++)
                    for (int x = Math.max(0, tx - LONG); x <= Math.min(span - 1, tx + LONG); x++) {
                        if (line[y * span + x] != target) continue;
                        double along = (x - tx) * hx + (y - ty) * hy;
                        if (along > furthest) { furthest = along; fx = x; fy = y; }
                    }
                at = new End(fx, fy, hx, hy, start.width, target);
            }
        }
    }

    /** The first index from {@code from} to {@code to} whose column is at least {@code x}. */
    private static int first(int[] columns, int from, int to, int x) {
        while (from < to) {
            int middle = (from + to) >>> 1;
            if (columns[middle] < x) from = middle + 1; else to = middle;
        }
        return from;
    }

    /** Water pixels in the five by five around a pixel: five for a line one pixel wide, more for wider water. */
    private static int wet(boolean[] solid, int span, int x, int y) {
        int wet = 0;
        for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) if (at(solid, span, x + i, y + j)) wet++;
        return wet;
    }

    private static int find(int[] root, int l) {
        while (root[l] != l) { root[l] = root[root[l]]; l = root[l]; }
        return l;
    }

    /**
     * Paints a river of a width along a cubic Hermite curve between two points with the given
     * tangents, swinging from side to side as rivers meander, by more on longer joins: a river
     * under trees is no straight canal.
     */
    private static void paint(float[] f, int span, double ax, double ay, double tax, double tay, double bx, double by, double tbx, double tby,
                              double width, int phase) {
        double length = Math.hypot(bx - ax, by - ay);
        int steps = (int) Math.ceil(length * 4) + 1;
        double radius = Math.max(0.5, width / 2 - 0.25);
        double bends = Math.max(1, Math.round(length / MEANDER)), swing = Math.min(3, length * 0.07);
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps, t2 = t * t, t3 = t2 * t;
            double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
            double x = h00 * ax + h10 * tax + h01 * bx + h11 * tbx, y = h00 * ay + h10 * tay + h01 * by + h11 * tby;
            // Sideways off the curve, to nothing at either end.
            double dx = 6 * t2 - 6 * t, dy = dx;
            dx = dx * ax + (3 * t2 - 4 * t + 1) * tax + (-6 * t2 + 6 * t) * bx + (3 * t2 - 2 * t) * tbx;
            dy = dy * ay + (3 * t2 - 4 * t + 1) * tay + (-6 * t2 + 6 * t) * by + (3 * t2 - 2 * t) * tby;
            double norm = Math.hypot(dx, dy);
            if (norm > 0) {
                double side = swing * Math.sin(Math.PI * bends * t + phase) * Math.sin(Math.PI * t);
                x += -dy / norm * side;
                y += dx / norm * side;
            }
            int r = (int) Math.ceil(radius);
            for (int j = -r; j <= r; j++) for (int i = -r; i <= r; i++) {
                int qx = (int) Math.round(x) + i, qy = (int) Math.round(y) + j;
                if (qx < 0 || qy < 0 || qx >= span || qy >= span) continue;
                if (Math.hypot(qx - x, qy - y) <= radius) f[qy * span + qx] = Math.max(f[qy * span + qx], 1);
            }
        }
    }

    /** Whether a skeleton pixel ends its line: its neighbours, one or two, touch each other. */
    private static boolean end(boolean[] a, int span, int x, int y) {
        int count = 0, turns = 0;
        for (int d = 0; d < 8; d++) {
            boolean here = at(a, span, x + NX[d], y + NY[d]), next = at(a, span, x + NX[(d + 1) % 8], y + NY[(d + 1) % 8]);
            if (here) count++;
            if (!here && next) turns++;
        }
        return count >= 1 && count <= 2 && turns == 1;
    }
}
