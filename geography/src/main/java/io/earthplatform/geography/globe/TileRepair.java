package io.earthplatform.geography.globe;

import static io.earthplatform.geography.globe.TerrariumTileSource.TILE_SIZE;

/**
 * Repairs runs of wildly wrong pixels in the composite's finest tiles against the bilinear surface
 * of the coarser reference tile that holds them. {@link TerrariumTileSource} reads the reference
 * tile at {@link TerrariumTileSource#REFERENCE_ZOOM} and calls {@link #repair}.
 */
final class TileRepair {
    private TileRepair() {}

    /**
     * Metres a fine pixel may stray from the reference, beyond the reference's own relief nearby, before it counts as
     * broken. Real cliffs and gorges stay inside that relief; broken runs do not.
     */
    static final float BROKEN_METRES = 150f;
    /** Fine pixels either side along the row and column whose detail a repaired pixel borrows. */
    private static final int REPAIR_REACH = 4;

    /** Repairs {@code values}, tile (x, y) at {@code shift} zooms finer than {@code reference}, the tile holding it. */
    static void repair(float[] values, float[] reference, int shift, int x, int y) {
        // This tile covers a (256 >> shift)-pixel square of the reference tile starting at (ox, oy).
        double scale = 1.0 / (1 << shift);
        int span = TILE_SIZE >> shift, ox = (x & ((1 << shift) - 1)) * span, oy = (y & ((1 << shift) - 1)) * span;
        float[] expected = new float[TILE_SIZE * TILE_SIZE];
        boolean[] broken = new boolean[TILE_SIZE * TILE_SIZE];
        boolean any = false;
        for (int py = 0; py < TILE_SIZE; py++) {
            double ry = Math.max(0, Math.min(TILE_SIZE - 1.001, oy + (py + 0.5) * scale - 0.5));
            int y0 = (int) ry;
            double fy = ry - y0;
            for (int px = 0; px < TILE_SIZE; px++) {
                double rx = Math.max(0, Math.min(TILE_SIZE - 1.001, ox + (px + 0.5) * scale - 0.5));
                int x0 = (int) rx, at = y0 * TILE_SIZE + x0;
                double fx = rx - x0;
                int i = py * TILE_SIZE + px;
                expected[i] = (float) ((reference[at] * (1 - fx) + reference[at + 1] * fx) * (1 - fy)
                    + (reference[at + TILE_SIZE] * (1 - fx) + reference[at + TILE_SIZE + 1] * fx) * fy);
                float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
                for (int j = Math.max(0, y0 - 1); j <= Math.min(TILE_SIZE - 1, y0 + 2); j++)
                    for (int k = Math.max(0, x0 - 1); k <= Math.min(TILE_SIZE - 1, x0 + 2); k++) {
                        float r = reference[j * TILE_SIZE + k];
                        low = Math.min(low, r); high = Math.max(high, r);
                    }
                broken[i] = values[i] != 0f && Math.abs(values[i] - expected[i]) > BROKEN_METRES + high - low;
                any |= broken[i];
            }
        }
        if (!any) return;
        // A broken pixel takes the reference plus the detail its sound neighbours along the row and column show
        // over it, so a thin broken run leaves no smooth groove; a wide one falls back to the reference.
        float[] fixed = new float[TILE_SIZE * TILE_SIZE];
        for (int i = 0; i < fixed.length; i++) {
            if (!broken[i]) continue;
            int px = i % TILE_SIZE, py = i / TILE_SIZE, n = 0;
            double detail = 0;
            for (int d = -REPAIR_REACH; d <= REPAIR_REACH; d++) {
                int qx = px + d, qy = py + d;
                if (d == 0) continue;
                if (qx >= 0 && qx < TILE_SIZE && sound(values, broken, py * TILE_SIZE + qx)) { detail += values[py * TILE_SIZE + qx] - expected[py * TILE_SIZE + qx]; n++; }
                if (qy >= 0 && qy < TILE_SIZE && sound(values, broken, qy * TILE_SIZE + px)) { detail += values[qy * TILE_SIZE + px] - expected[qy * TILE_SIZE + px]; n++; }
            }
            float value = (float) (expected[i] + (n == 0 ? 0 : detail / n));
            fixed[i] = value == 0f ? Math.ulp(0f) : value;
        }
        for (int i = 0; i < fixed.length; i++) if (broken[i]) values[i] = fixed[i];
    }

    private static boolean sound(float[] values, boolean[] broken, int i) { return !broken[i] && values[i] != 0f; }
}
