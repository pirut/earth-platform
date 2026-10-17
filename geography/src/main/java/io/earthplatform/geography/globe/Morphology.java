package io.earthplatform.geography.globe;

import java.util.ArrayList;
import java.util.List;

/**
 * Morphology on square grids of pixels {@code span} across, row-major: growing, opening, thinning
 * to a skeleton, labeling connected shapes, pruning spurs and the lowest value around each pixel.
 * {@link RiverMap} traces rivers with them.
 */
final class Morphology {
    private Morphology() {}

    /** Steps to the eight pixels around one, in turn round the ring from east. */
    static final int[] NX = {1, 1, 0, -1, -1, -1, 0, 1}, NY = {0, 1, 1, 1, 0, -1, -1, -1};

    /** Pixels within a square of the given radius of any set pixel. */
    static boolean[] grow(boolean[] a, int span, int radius) {
        // Along each row, then down each column, counting set pixels in a window sliding by one.
        boolean[] across = new boolean[a.length], out = new boolean[a.length];
        for (int y = 0; y < span; y++) {
            int count = 0, row = y * span;
            for (int x = 0; x < Math.min(radius, span); x++) if (a[row + x]) count++;
            for (int x = 0; x < span; x++) {
                if (x + radius < span && a[row + x + radius]) count++;
                if (x - radius - 1 >= 0 && a[row + x - radius - 1]) count--;
                across[row + x] = count > 0;
            }
        }
        for (int x = 0; x < span; x++) {
            int count = 0;
            for (int y = 0; y < Math.min(radius, span); y++) if (across[y * span + x]) count++;
            for (int y = 0; y < span; y++) {
                if (y + radius < span && across[(y + radius) * span + x]) count++;
                if (y - radius - 1 >= 0 && across[(y - radius - 1) * span + x]) count--;
                out[y * span + x] = count > 0;
            }
        }
        return out;
    }

    /** Lowest height within a square of the given radius of each pixel. */
    static float[] lowest(float[] height, int span, int radius) {
        float[] across = new float[height.length], out = new float[height.length];
        for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) {
            float low = Float.MAX_VALUE;
            for (int i = Math.max(0, x - radius); i <= Math.min(span - 1, x + radius); i++) low = Math.min(low, height[y * span + i]);
            across[y * span + x] = low;
        }
        for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) {
            float low = Float.MAX_VALUE;
            for (int j = Math.max(0, y - radius); j <= Math.min(span - 1, y + radius); j++) low = Math.min(low, across[j * span + x]);
            out[y * span + x] = low;
        }
        return out;
    }

    /**
     * Prunes spurs of up to a length off lines one pixel wide: their ends are worn back that
     * many times, then the ends left grow back over what was worn off them, which a spur's stub,
     * sitting on a line, does not.
     */
    static void prune(boolean[] on, int span, int length) {
        List<int[]> worn = new ArrayList<>();
        for (int round = 0; round < length; round++) {
            List<Integer> ends = new ArrayList<>();
            for (int k = 0; k < on.length; k++) if (on[k] && neighbours(on, span, k) == 1) ends.add(k);
            int[] these = ends.stream().mapToInt(Integer::intValue).toArray();
            for (int k : these) on[k] = false;
            worn.add(these);
        }
        for (int round = length - 1; round >= 0; round--) {
            List<Integer> back = new ArrayList<>();
            for (int k : worn.get(round)) {
                int x = k % span, y = k / span;
                for (int d = 0; d < 8; d++) {
                    int qx = x + NX[d], qy = y + NY[d];
                    if (at(on, span, qx, qy) && neighbours(on, span, qy * span + qx) <= 1) { back.add(k); break; }
                }
            }
            for (int k : back) on[k] = true;
        }
    }

    private static int neighbours(boolean[] on, int span, int k) {
        int x = k % span, y = k / span, count = 0;
        for (int d = 0; d < 8; d++) if (at(on, span, x + NX[d], y + NY[d])) count++;
        return count;
    }

    static float at(float[] a, int span, int x, int y) {
        return x < 0 || y < 0 || x >= span || y >= span ? 0 : a[y * span + x];
    }

    static boolean at(boolean[] a, int span, int x, int y) {
        return x >= 0 && y >= 0 && x < span && y < span && a[y * span + x];
    }

    /** Zhang-Suen thinning: a one-pixel line down the middle of each shape. */
    static boolean[] thin(boolean[] solid, int span) {
        boolean[] a = solid.clone();
        int n = a.length;
        // A pixel's fate in a pass hangs on its eight neighbours alone, so after the first look each
        // pass only looks again at pixels next to one cleared since: wide water thins in many rounds.
        int[][] dirty = {new int[n], new int[n]};
        boolean[][] marked = {new boolean[n], new boolean[n]};
        int[] count = new int[2], clear = new int[n];
        for (int y = 1; y < span - 1; y++) for (int x = 1; x < span - 1; x++) {
            int k = y * span + x;
            if (!a[k]) continue;
            for (int pass = 0; pass < 2; pass++) { dirty[pass][count[pass]++] = k; marked[pass][k] = true; }
        }
        // Ring order P2..P9: north, north-east, east, south-east, south, south-west, west, north-west.
        int[] ring = {-span, -span + 1, 1, span + 1, span, span - 1, -1, -span - 1};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int pass = 0; pass < 2; pass++) {
                int[] list = dirty[pass];
                int m = count[pass], cleared = 0;
                count[pass] = 0;
                for (int i = 0; i < m; i++) {
                    int k = list[i];
                    marked[pass][k] = false;
                    if (!a[k]) continue;
                    int bits = 0;
                    for (int r = 0; r < 8; r++) if (a[k + ring[r]]) bits |= 1 << r;
                    if (THIN[pass][bits]) clear[cleared++] = k;
                }
                for (int i = 0; i < cleared; i++) a[clear[i]] = false;
                for (int i = 0; i < cleared; i++) {
                    int k = clear[i];
                    for (int r = 0; r < 8; r++) {
                        int q = k + ring[r], qx = q % span, qy = q / span;
                        if (!a[q] || qx < 1 || qy < 1 || qx >= span - 1 || qy >= span - 1) continue;
                        for (int other = 0; other < 2; other++)
                            if (!marked[other][q]) { marked[other][q] = true; dirty[other][count[other]++] = q; }
                    }
                }
                changed |= cleared > 0;
            }
        }
        return a;
    }

    /** Whether each pass of the thinning clears a pixel, by its neighbours as bits in ring order. */
    private static final boolean[][] THIN = new boolean[2][256];
    static {
        for (int bits = 0; bits < 256; bits++) {
            boolean[] p = new boolean[8];
            for (int k = 0; k < 8; k++) p[k] = (bits >> k & 1) != 0;
            int count = Integer.bitCount(bits), turns = 0;
            for (int k = 0; k < 8; k++) if (!p[k] && p[(k + 1) % 8]) turns++;
            if (count < 2 || count > 6 || turns != 1) continue;
            THIN[0][bits] = !((p[0] && p[2] && p[4]) || (p[2] && p[4] && p[6]));
            THIN[1][bits] = !((p[0] && p[2] && p[6]) || (p[0] && p[4] && p[6]));
        }
    }

    /** Numbers each eight-connected shape from 1; 0 outside them. */
    static int[] label(boolean[] a, int span) {
        int[] labels = new int[a.length];
        int next = 0;
        int[] queue = new int[a.length];
        for (int start = 0; start < a.length; start++) {
            if (!a[start] || labels[start] != 0) continue;
            labels[start] = ++next;
            int head = 0, tail = 0;
            queue[tail++] = start;
            while (head < tail) {
                int k = queue[head++], x = k % span, y = k / span;
                for (int d = 0; d < 8; d++) {
                    int qx = x + NX[d], qy = y + NY[d];
                    if (!at(a, span, qx, qy) || labels[qy * span + qx] != 0) continue;
                    labels[qy * span + qx] = next;
                    queue[tail++] = qy * span + qx;
                }
            }
        }
        return labels;
    }

    /** Morphological opening with a disc: the pixels covered by discs of the radius that fit wholly in the shapes. */
    static boolean[] open(boolean[] a, int span, int radius) {
        // The disc as a run of pixels across each of its rows, checked by counts along each row.
        int[] half = new int[2 * radius + 1];
        for (int j = -radius; j <= radius; j++) {
            half[j + radius] = -1;
            for (int i = 0; i <= radius; i++) if (i * i + j * j <= radius * radius + radius) half[j + radius] = i;
        }
        int[] sums = rowSums(a, span);
        boolean[] eroded = new boolean[a.length];
        for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) {
            if (!a[y * span + x]) continue;
            boolean fits = true;
            for (int j = -radius; j <= radius && fits; j++) {
                int w = half[j + radius];
                if (w < 0) continue;
                int py = y + j, x0 = x - w, x1 = x + w;
                fits = py >= 0 && py < span && x0 >= 0 && x1 < span && run(sums, span, py, x0, x1) == x1 - x0 + 1;
            }
            eroded[y * span + x] = fits;
        }
        // The disc is its own mirror, so a pixel is in the opening where the disc round it holds an eroded one.
        sums = rowSums(eroded, span);
        boolean[] opened = new boolean[a.length];
        for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) {
            boolean hit = false;
            for (int j = -radius; j <= radius && !hit; j++) {
                int w = half[j + radius], py = y + j;
                if (w < 0 || py < 0 || py >= span) continue;
                hit = run(sums, span, py, Math.max(0, x - w), Math.min(span - 1, x + w)) > 0;
            }
            opened[y * span + x] = hit;
        }
        return opened;
    }

    /** Set pixels in each row before each column, {@code span + 1} a row. */
    private static int[] rowSums(boolean[] a, int span) {
        int[] sums = new int[span * (span + 1)];
        for (int y = 0; y < span; y++) for (int x = 0; x < span; x++)
            sums[y * (span + 1) + x + 1] = sums[y * (span + 1) + x] + (a[y * span + x] ? 1 : 0);
        return sums;
    }

    /** Set pixels in a row from one column to another, both included. */
    private static int run(int[] sums, int span, int y, int x0, int x1) {
        return sums[y * (span + 1) + x1 + 1] - sums[y * (span + 1) + x0];
    }
}
