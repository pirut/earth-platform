package io.earthplatform.geography.globe;

import java.util.Arrays;

import static io.earthplatform.geography.globe.TerrariumTileSource.TILE_SIZE;

/**
 * Lowers buildings standing in flat land and isolated photogrammetric spikes or pits to the ground
 * around them, in Terrarium tiles from zoom {@value #SPIKE_ZOOM} on. {@link TerrariumTileSource}
 * runs {@link #despike(float[], int, int)} last, after the layers.
 */
final class TileDespike {
    private TileDespike() {}

    /** Zooms from which {@link #despike} runs: coarser pixels are too wide for a building to stand out. */
    static final int SPIKE_ZOOM = 10;
    /** Metres above the ground around it that a pixel in flat land may stand before it counts as a building. */
    static final float SPIKE_METRES = 30f;
    /** Spread, in metres, of the lower half of the ground around a pixel below which that ground counts as flat. */
    static final float FLAT_METRES = 8f;
    private static final int SPIKE_REACH = 2;
    /** Most pixels in the window that may stand that high: more make a hill, a ridge or a shore, not a building. */
    private static final int SPIKE_PIXELS = 4;

    /** Default ground width of a pixel, in metres, when {@link #despike(float[])} is called without a tile address. */
    private static final double DEFAULT_PIXEL_METRES = 8.0;
    /**
     * Minimum height jump above or below its surrounding perimeter's median, and minimum prominence
     * beyond the perimeter's extreme, for an isolated cluster on sloping or steep ground to count
     * as a stereo-photogrammetry spike or pit (such as a volcanic steam plume in REMA).
     */
    static final float SPIRE_METRES = 55f;
    private static final float SPIRE_PROMINENCE = 45f;
    /** Adjacent-pixel step, in metres, below which no isolated spire or pit search starts. */
    private static final float STEP_TRIGGER = 25f;
    /** Widest ground footprint, in metres, of an isolated photogrammetric spike or pit. */
    private static final double SPIRE_SPAN_METRES = 45.0;
    private static final float[] CUT_FRACTIONS = {0.65f, 0.45f, 0.25f};

    /** Despikes a tile using {@link #DEFAULT_PIXEL_METRES} as its pixel ground width. */
    static void despike(float[] values) {
        despike(values, DEFAULT_PIXEL_METRES);
    }

    /** Despikes tile {@code (zoom, tileY)} using its Mercator pixel ground width. */
    static void despike(float[] values, int zoom, int tileY) {
        double n = Math.PI * (1 - 2.0 * (tileY * TILE_SIZE + TILE_SIZE * 0.5) / (TILE_SIZE << zoom));
        double pixelMetres = 40_075_016.7 / Math.cosh(n) / (TILE_SIZE << zoom);
        despike(values, pixelMetres);
    }

    static void despike(float[] values, double pixelMetres) {
        despikeBuildings(values);
        despikeSpires(values, pixelMetres);
    }

    /**
     * Lowers buildings in flat land to the ground around them. The composite's surface returns
     * keep tall buildings, such as the towers along Lake Worth in West Palm Beach (86 m among 1 to
     * 5 m ground at zoom 12), which {@link TileMending#mend} keeps since they stand in groups and {@link TileRepair#repair}
     * keeps since they are far smaller than broken runs. Where the lower half of the pixels within
     * two of one lies within {@link #FLAT_METRES}, a pixel more than {@link #SPIKE_METRES} above their
     * median takes the median, as long as at most {@link #SPIKE_PIXELS} of them stand that high. Hills,
     * eskers, lake shores and coastal bluffs fill more of that window or slope through it, so they
     * stay. Open-sea zeros neither count nor change.
     */
    private static void despikeBuildings(float[] values) {
        float[] fixed = null, window = new float[(2 * SPIKE_REACH + 1) * (2 * SPIKE_REACH + 1)];
        // The lowest pixel and the number of pixels in each window, from those along each row's stretch first.
        float[] rowLow = new float[TILE_SIZE * TILE_SIZE];
        byte[] rowCount = new byte[TILE_SIZE * TILE_SIZE];
        for (int py = 0; py < TILE_SIZE; py++) for (int px = 0; px < TILE_SIZE; px++) {
            float low = Float.MAX_VALUE;
            int n = 0;
            for (int qx = Math.max(0, px - SPIKE_REACH); qx <= Math.min(TILE_SIZE - 1, px + SPIKE_REACH); qx++) {
                float q = values[py * TILE_SIZE + qx];
                if (q != 0f) { low = Math.min(low, q); n++; }
            }
            rowLow[py * TILE_SIZE + px] = low;
            rowCount[py * TILE_SIZE + px] = (byte) n;
        }
        for (int py = 0; py < TILE_SIZE; py++) for (int px = 0; px < TILE_SIZE; px++) {
            int i = py * TILE_SIZE + px;
            float value = values[i];
            // Land only: under water a shoal standing above the bed is real.
            if (value <= 0f) continue;
            // Cheap test first: most pixels are not far above their lowest neighbours.
            float low = Float.MAX_VALUE;
            int n = 0;
            for (int qy = Math.max(0, py - SPIKE_REACH); qy <= Math.min(TILE_SIZE - 1, py + SPIKE_REACH); qy++) {
                low = Math.min(low, rowLow[qy * TILE_SIZE + px]);
                n += rowCount[qy * TILE_SIZE + px];
            }
            if (n < 9 || value - low <= SPIKE_METRES) continue;
            n = 0;
            for (int qy = Math.max(0, py - SPIKE_REACH); qy <= Math.min(TILE_SIZE - 1, py + SPIKE_REACH); qy++)
                for (int qx = Math.max(0, px - SPIKE_REACH); qx <= Math.min(TILE_SIZE - 1, px + SPIKE_REACH); qx++) {
                    float q = values[qy * TILE_SIZE + qx];
                    if (q != 0f) window[n++] = q;
                }
            // And on slopes: the median is within FLAT_METRES of the lowest only where more than half the window is.
            int flat = 0;
            for (int k = 0; k < n; k++) if (window[k] - low <= FLAT_METRES) flat++;
            if (flat <= n / 2) continue;
            Arrays.sort(window, 0, n);
            float median = window[n / 2];
            if (value - median <= SPIKE_METRES || median - window[0] > FLAT_METRES || window[n - 1 - SPIKE_PIXELS] > median + SPIKE_METRES) continue;
            if (fixed == null) fixed = values.clone();
            fixed[i] = median == 0f ? Math.ulp(0f) : median;
        }
        if (fixed != null) System.arraycopy(fixed, 0, values, 0, values.length);
    }

    /**
     * Smoothly removes extreme isolated photogrammetric spikes and pits even on steep slopes or
     * crater walls. Stereo elevation layers (such as REMA's 2 m mosaic) are laid over tiles after
     * {@link TileMending#mend} and {@link TileRepair#repair}, and steam plumes or clouds can leave
     * 10 to 30 m wide towers jumping 60 to 200 m above steep ground where {@link #despikeBuildings}
     * does not run. A compact component bounded on all sides by pixels far lower (or higher, for a
     * pit) — steeper than real peaks and narrower than cliffs, ridges, eskers and sea stacks — is
     * replaced by an inverse-distance-weighted blend of its perimeter.
     */
    private static void despikeSpires(float[] values, double pixelMetres) {
        double safePixel = Math.max(0.5, pixelMetres);
        int maxSpan = Math.max(3, Math.min(24, (int) Math.ceil(SPIRE_SPAN_METRES / safePixel)));
        int maxArea = maxSpan * maxSpan;
        int[] mark = null, queue = null, comp = null, perim = null;
        float[] perimVals = null, leakHigh = null, leakLow = null;
        int stamp = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean changed = false;
            if (leakHigh != null) Arrays.fill(leakHigh, Float.NEGATIVE_INFINITY);
            if (leakLow != null) Arrays.fill(leakLow, Float.POSITIVE_INFINITY);
            for (int py = 0; py < TILE_SIZE; py++) {
                int row = py * TILE_SIZE;
                for (int px = 0; px < TILE_SIZE; px++) {
                    int a = row + px;
                    float va = values[a];
                    if (va == 0f) continue;
                    // Check right and down edges so each adjacent pair is tested once.
                    for (int dir = 0; dir < 2; dir++) {
                        int b;
                        if (dir == 0) {
                            if (px + 1 >= TILE_SIZE) continue;
                            b = a + 1;
                        } else {
                            if (py + 1 >= TILE_SIZE) continue;
                            b = a + TILE_SIZE;
                        }
                        float vb = values[b];
                        if (vb == 0f) continue;
                        float diff = va - vb;
                        if (Math.abs(diff) <= STEP_TRIGGER) continue;
                        if (mark == null) {
                            int total = TILE_SIZE * TILE_SIZE;
                            mark = new int[total];
                            queue = new int[maxArea + 1];
                            comp = new int[maxArea + 1];
                            perim = new int[4 * maxArea + 16];
                            perimVals = new float[4 * maxArea + 16];
                            leakHigh = new float[total];
                            leakLow = new float[total];
                            Arrays.fill(leakHigh, Float.NEGATIVE_INFINITY);
                            Arrays.fill(leakLow, Float.POSITIVE_INFINITY);
                        }
                        int hi = diff > 0 ? a : b, lo = diff > 0 ? b : a;
                        // Try removing an upward spire seeded at hi, then a downward pit seeded at lo.
                        int res = cleanOutlier(values, hi, lo, 1, safePixel, maxSpan, maxArea,
                                mark, stamp, queue, comp, perim, perimVals, leakHigh);
                        if (res < 0) {
                            stamp = -res;
                            changed = true;
                            va = values[a];
                            continue;
                        }
                        stamp = res;
                        res = cleanOutlier(values, lo, hi, -1, safePixel, maxSpan, maxArea,
                                mark, stamp, queue, comp, perim, perimVals, leakLow);
                        if (res < 0) {
                            stamp = -res;
                            changed = true;
                            va = values[a];
                        } else {
                            stamp = res;
                        }
                    }
                }
            }
            if (!changed) break;
        }
    }

    /**
     * Flood-fills a candidate spike ({@code sign = +1}) or pit ({@code sign = -1}) seeded at
     * {@code seed} against {@code other}. Returns the updated stamp, negated when {@code values}
     * was modified.
     */
    private static int cleanOutlier(float[] values, int seed, int other, int sign, double pixelMetres,
                                    int maxSpan, int maxArea, int[] mark, int stamp, int[] queue,
                                    int[] comp, int[] perim, float[] perimVals, float[] leakCut) {
        float vSeed = values[seed], vOther = values[other];
        float step = sign * (vSeed - vOther);
        if (step <= STEP_TRIGGER) return stamp;
        for (float frac : CUT_FRACTIONS) {
            float cut = vSeed - sign * frac * step;
            // Any component above a lower cut (or below a higher cut for pits) is a superset of an earlier leak.
            if (sign > 0 ? cut <= leakCut[seed] : cut >= leakCut[seed]) continue;
            int compMark = ++stamp, perimMark = ++stamp;
            int head = 0, tail = 0, compCount = 0, perimCount = 0;
            int sx = seed % TILE_SIZE, sy = seed / TILE_SIZE;
            int minX = sx, maxX = sx, minY = sy, maxY = sy;
            mark[seed] = compMark;
            queue[tail++] = seed;
            boolean leaked = false, touchesSea = false;
            while (head < tail) {
                int at = queue[head++];
                comp[compCount++] = at;
                int x = at % TILE_SIZE, y = at / TILE_SIZE;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (compCount > maxArea || maxX - minX + 1 > maxSpan || maxY - minY + 1 > maxSpan) {
                    leaked = true;
                    break;
                }
                for (int d = 0; d < 4; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx < 0 || ny < 0 || nx >= TILE_SIZE || ny >= TILE_SIZE) continue;
                    int n = ny * TILE_SIZE + nx;
                    if (mark[n] == compMark) continue;
                    float vn = values[n];
                    if (vn == 0f) { touchesSea = true; continue; }
                    if (sign * (vn - cut) > 0f) {
                        if (tail >= maxArea) { leaked = true; break; }
                        mark[n] = compMark;
                        queue[tail++] = n;
                    } else if (mark[n] != perimMark) {
                        mark[n] = perimMark;
                        perim[perimCount] = n;
                        perimVals[perimCount++] = vn;
                    }
                }
                if (leaked) break;
            }
            if (leaked) {
                for (int k = 0; k < compCount; k++) {
                    int u = comp[k];
                    if (sign > 0 ? cut > leakCut[u] : cut < leakCut[u]) leakCut[u] = cut;
                }
                continue;
            }
            if (touchesSea) return stamp;
            boolean touchesEdge = minX == 0 || maxX == TILE_SIZE - 1 || minY == 0 || maxY == TILE_SIZE - 1;
            if (perimCount < (touchesEdge ? 6 : 4)) continue;
            float extreme = vSeed;
            for (int k = 0; k < compCount; k++) {
                float v = values[comp[k]];
                if (sign * (v - extreme) > 0f) extreme = v;
            }
            Arrays.sort(perimVals, 0, perimCount);
            float perimMedian = perimVals[perimCount / 2];
            float perimEdge = sign > 0 ? perimVals[perimCount - 1] : perimVals[0];
            double radiusMetres = Math.max(pixelMetres, 0.5 * Math.hypot(maxX - minX + 1, maxY - minY + 1) * pixelMetres);
            float jump = sign * (extreme - perimMedian);
            float prominence = sign * (extreme - perimEdge);
            if (jump <= Math.max(SPIRE_METRES, 3.0 * radiusMetres)
                    || prominence <= Math.max(SPIRE_PROMINENCE, 2.5 * radiusMetres)) continue;
            float[] replacement = new float[compCount];
            for (int k = 0; k < compCount; k++) {
                int p = comp[k], px = p % TILE_SIZE, py = p / TILE_SIZE;
                double wSum = 0, vSum = 0;
                for (int j = 0; j < perimCount; j++) {
                    int q = perim[j], qx = q % TILE_SIZE, qy = q / TILE_SIZE;
                    double w = 1.0 / ((px - qx) * (px - qx) + (py - qy) * (py - qy));
                    wSum += w;
                    vSum += values[q] * w;
                }
                float out = (float) (vSum / wSum);
                replacement[k] = out == 0f ? Math.ulp(0f) : out;
            }
            for (int k = 0; k < compCount; k++) values[comp[k]] = replacement[k];
            return -stamp;
        }
        return stamp;
    }
}
