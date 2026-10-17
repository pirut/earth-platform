package io.earthplatform.geography.globe;

import io.earthplatform.geography.globe.TerrariumTileSource.Neighbours;

import static io.earthplatform.geography.globe.TerrariumTileSource.MAX_ZOOM;
import static io.earthplatform.geography.globe.TerrariumTileSource.TILE_SIZE;

/**
 * Gives zero pixels of a Terrarium tile that are not open sea the height of the ground around
 * them; see {@link #strayZeros}.
 */
final class StrayZeros {
    private StrayZeros() {}

    /** Fewest zero pixels in one piece at zoom 15, about 0.02 km², that {@link #strayZeros} takes for open sea. */
    static final int SEA_PIXELS = 1024;
    /** Half the side of the square of zeros, in zoom 15 pixels, that open sea is wide enough to hold. */
    static final int OPEN = 2;
    /** Steps to the four pixels beside one: pieces of zeros join along rows and columns, not at corners. */
    private static final int[] STEP_X = {1, -1, 0, 0}, STEP_Y = {0, 0, 1, -1};
    /** Farthest, in pixels along a row or column, that {@link #strayZeros} looks for ground to fill a stray zero from. */
    private static final int STRAY_REACH = 16;

    /**
     * Gives zero pixels that are not open sea the height of the ground around them. Zooms finer
     * than {@link GlobalElevation#BATHYMETRY_ZOOM} mark open sea as exactly 0 m, which the
     * bathymetry fills; but the composite also holds zeros that are no sea: lines one or two pixels
     * wide across shallow banks where its surveys meet, and squares of low islands' ground that its
     * survey rounded to 0 m. Filled from the bathymetry, those stood as straight trenches and square
     * pits fifty blocks deep in the Exumas' shallows and on its cays. Open sea is wide: zeros in a
     * piece that a square of zeros about 20 m across can't cover, or in a piece of less than about
     * 0.02 km² inside the tile, take the mean of the nearest ground along their
     * row and column, never exactly zero. A piece at the tile's edge is measured on into the tiles
     * beside it; one that can't be, as at the edge of the world, stays sea.
     */
    static float[] strayZeros(float[] metres, int zoom, int tileX, int tileY, Neighbours neighbours) {
        if (zoom <= GlobalElevation.BATHYMETRY_ZOOM) return metres;
        int size = TILE_SIZE;
        boolean any = false;
        for (float v : metres) if (v == 0f) { any = true; break; }
        if (!any) return metres;
        // The same ground at every zoom: the square and the piece shrink in pixels as the pixels grow, so far terrain
        // keeps as sea what the cubes do.
        int finer = MAX_ZOOM - zoom, open = Math.max(0, OPEN - finer), least = Math.max(4, SEA_PIXELS >> 2 * finer);
        // Zeros a square of zeros covers: the sea opened, so lines and bays narrower than the square drop out. A
        // square at the tile's edge counts only its part in the tile, as the sea may go on beyond it.
        boolean[] core = new boolean[size * size], sea = new boolean[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            boolean all = true;
            for (int qy = Math.max(0, y - open); qy <= Math.min(size - 1, y + open) && all; qy++)
                for (int qx = Math.max(0, x - open); qx <= Math.min(size - 1, x + open) && all; qx++) all = metres[qy * size + qx] == 0f;
            core[y * size + x] = all;
        }
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (metres[y * size + x] != 0f) continue;
            boolean covered = false;
            for (int qy = Math.max(0, y - open); qy <= Math.min(size - 1, y + open) && !covered; qy++)
                for (int qx = Math.max(0, x - open); qx <= Math.min(size - 1, x + open) && !covered; qx++) covered = core[qy * size + qx];
            sea[y * size + x] = covered;
        }
        // Small pieces of what is left, inside the tile, are no sea either.
        int[] queue = new int[size * size];
        boolean[] seen = new boolean[size * size];
        for (int start = 0; start < sea.length; start++) {
            if (!sea[start] || seen[start]) continue;
            int head = 0, tail = 0;
            boolean edge = false;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int at = queue[head++], x = at % size, y = at / size;
                edge |= x == 0 || y == 0 || x == size - 1 || y == size - 1;
                for (int d = 0; d < 4; d++) {
                    int qx = x + STEP_X[d], qy = y + STEP_Y[d], q = qy * size + qx;
                    if (qx < 0 || qy < 0 || qx >= size || qy >= size || seen[q] || !sea[q]) continue;
                    seen[q] = true;
                    queue[tail++] = q;
                }
            }
            if (tail >= least || edge && wideBeyond(metres, sea, queue, tail, least, zoom, tileX, tileY, neighbours)) continue;
            for (int k = 0; k < tail; k++) sea[queue[k]] = false;
        }
        float[] out = null;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int at = y * size + x;
            if (metres[at] != 0f || sea[at]) continue;
            double sum = 0, weight = 0;
            for (int d = 0; d < 4; d++) {
                int dx = d == 0 ? 1 : d == 1 ? -1 : 0, dy = d == 2 ? 1 : d == 3 ? -1 : 0;
                for (int r = 1; r <= STRAY_REACH; r++) {
                    int qx = x + dx * r, qy = y + dy * r;
                    if (qx < 0 || qy < 0 || qx >= size || qy >= size) break;
                    float q = metres[qy * size + qx];
                    if (q == 0f) { if (sea[qy * size + qx]) break; continue; }
                    sum += q / r;
                    weight += 1.0 / r;
                    break;
                }
            }
            if (weight == 0) continue;
            if (out == null) out = metres.clone();
            float value = (float) (sum / weight);
            out[at] = value == 0f ? Math.ulp(0f) : value;
        }
        return out == null ? metres : out;
    }

    /**
     * Whether a piece of zeros at a tile's edge, of fewer than {@code least} pixels inside it,
     * reaches that many with the zeros it joins in the tiles beside it, or runs into a tile that
     * can't be had.
     */
    private static boolean wideBeyond(float[] metres, boolean[] sea, int[] piece, int count, int least, int zoom, int tileX, int tileY, Neighbours neighbours) {
        int size = TILE_SIZE, reach = least, span = size + 2 * reach;
        float[][] around = new float[9][];
        boolean[] asked = new boolean[9];
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int k = 0; k < count; k++) {
            int at = (piece[k] / size + reach) * span + piece[k] % size + reach;
            seen.add(at);
            queue.add(at);
        }
        int zeros = count;
        while (!queue.isEmpty()) {
            int at = queue.poll(), x = at % span - reach, y = at / span - reach;
            for (int d = 0; d < 4; d++) {
                int qx = x + STEP_X[d], qy = y + STEP_Y[d];
                if (qx < -reach || qy < -reach || qx >= size + reach || qy >= size + reach) continue;
                int q = (qy + reach) * span + qx + reach;
                if (seen.contains(q)) continue;
                if (qx >= 0 && qy >= 0 && qx < size && qy < size) {
                    if (!sea[qy * size + qx]) continue;
                } else {
                    int t = (Math.floorDiv(qy, size) + 1) * 3 + Math.floorDiv(qx, size) + 1;
                    if (!asked[t]) {
                        asked[t] = true;
                        around[t] = neighbours == null ? null : neighbours.tile(zoom, tileX + Math.floorDiv(qx, size), tileY + Math.floorDiv(qy, size));
                    }
                    if (around[t] == null) return true;
                    if (around[t][Math.floorMod(qy, size) * size + Math.floorMod(qx, size)] != 0f) continue;
                }
                seen.add(q);
                queue.add(q);
                if (++zeros >= least) return true;
            }
        }
        return false;
    }
}
