package io.earthplatform.geography.globe;

import java.util.Arrays;

import static io.earthplatform.geography.globe.TerrariumTileSource.TILE_SIZE;

/**
 * Mends lone bad pixels and small clusters of them in a Terrarium tile, each against the
 * least-squares surface through the pixels around it; see {@link #mend}.
 */
final class TileMending {
    private TileMending() {}

    /**
     * Mends lone bad pixels and returns the tile. The composite holds single pixels and small
     * clusters, mostly where its source patches meet, that read hundreds or thousands of metres off
     * the ground or water around them; interpolated, each would sink a pit or raise a spire tens of
     * blocks wide. A pixel is mended when it lies more than twice a tolerance, plus the ground's
     * fall across a pixel there, from what its neighbours within two pixels predict, and fewer than
     * three of them lie within the tolerance of it: 20 m plus two pixel widths, steeper than nearly
     * all real ground. The prediction is the {@link #surface} through them, which follows a face
     * curving over its brink; the fall allows for the lip and ledges of a sheer face such as El
     * Capitan's, which no smooth surface follows. Cliffs, ridges and valleys always keep neighbours
     * along them at their own height, so only spikes match. Two passes clear clusters.
     * Open-sea zeros neither count as neighbours nor change.
     */
    static float[] mend(float[] metres, int zoom, int tileY) {
        float[] out = metres;
        for (int pass = 0; pass < 2; pass++) out = mendPass(out, zoom, tileY);
        return out;
    }

    private static float[] mendPass(float[] metres, int zoom, int tileY) {
        float[] around = new float[24];
        int[] at = new int[24];
        double[] scratch = new double[6 * 7], fit = new double[6];
        float[] mended = null;
        for (int y = 0; y < TILE_SIZE; y++) {
            // Ground width of a pixel on this row: the Mercator scale shrinks it toward the poles.
            double n = Math.PI * (1 - 2.0 * (tileY * TILE_SIZE + y + 0.5) / (TILE_SIZE << zoom));
            double tolerance = 20 + 2 * 40_075_016.7 / Math.cosh(n) / (TILE_SIZE << zoom);
            for (int x = 0; x < TILE_SIZE; x++) {
                float value = metres[y * TILE_SIZE + x];
                if (value == 0f) continue;
                int count = 0, close = 0;
                // Most pixels have three close neighbours, which settles them, so the scan stops there.
                scan:
                for (int j = Math.max(0, y - 2); j <= Math.min(TILE_SIZE - 1, y + 2); j++) {
                    for (int i = Math.max(0, x - 2); i <= Math.min(TILE_SIZE - 1, x + 2); i++) {
                        float other = metres[j * TILE_SIZE + i];
                        if (i == x && j == y || other == 0f) continue;
                        at[count] = (j - y) * 8 + i - x;
                        around[count++] = other;
                        if (Math.abs(other - value) <= tolerance && ++close >= 3) break scan;
                    }
                }
                if (close >= 3 || count < 8) continue;
                double expected = surface(around, at, count, 2 * tolerance, scratch, fit);
                if (Math.abs(value - expected) <= 2 * tolerance + Math.hypot(fit[1], fit[2])) continue;
                if (mended == null) mended = metres.clone();
                mended[y * TILE_SIZE + x] = (float) expected;
            }
        }
        return mended == null ? metres : mended;
    }

    /**
     * Height at the centre of the least-squares surface through neighbours at offsets {@code at}
     * (dy * 8 + dx), its terms left in {@code fit}: a quadratic where they surround the pixel, which follows a cliff's curving
     * brink and foot, or a plane where the tile's edge or open sea cuts the window short, so a
     * one-sided window on a slope predicts the slope. Refitted without the one furthest from it
     * while that lies further than {@code outlier}, so a few bad pixels among them cannot bend it.
     */
    private static double surface(float[] values, int[] at, int count, double outlier, double[] scratch, double[] fit) {
        int terms = count >= 15 ? 6 : 3;
        boolean[] dropped = new boolean[count];
        for (int round = 0; ; round++) {
            fit(values, at, count, dropped, terms, fit, scratch);
            if (round == 4) return fit[0];
            int worst = -1;
            double furthest = outlier;
            for (int k = 0; k < count; k++) {
                if (dropped[k]) continue;
                int dy = Math.floorDiv(at[k] + 2, 8), dx = at[k] - dy * 8;
                double off = Math.abs(fit[0] + fit[1] * dx + fit[2] * dy + fit[3] * dx * dx + fit[4] * dx * dy + fit[5] * dy * dy - values[k]);
                if (off > furthest) { furthest = off; worst = k; }
            }
            if (worst < 0) return fit[0];
            dropped[worst] = true;
        }
    }

    /**
     * Least-squares fit of the first {@code terms} of 1, dx, dy, dx², dx dy, dy² through the
     * neighbours not dropped, into {@code fit}, the rest zero; level at their mean where too few
     * remain to fix them.
     */
    private static void fit(float[] values, int[] at, int count, boolean[] dropped, int terms, double[] fit, double[] a) {
        int width = terms + 1, n = 0;
        double sum = 0;
        Arrays.fill(a, 0);
        Arrays.fill(fit, 0);
        for (int k = 0; k < count; k++) {
            if (dropped[k]) continue;
            int dy = Math.floorDiv(at[k] + 2, 8), dx = at[k] - dy * 8;
            double v = values[k];
            double[] basis = {1, dx, dy, dx * dx, dx * dy, dy * dy};
            for (int r = 0; r < terms; r++) {
                for (int c = 0; c < terms; c++) a[r * width + c] += basis[r] * basis[c];
                a[r * width + terms] += basis[r] * v;
            }
            n++;
            sum += v;
        }
        fit[0] = n == 0 ? 0 : sum / n;
        if (n < terms + 1) return;
        // Normal equations, by Gaussian elimination with partial pivoting.
        for (int col = 0; col < terms; col++) {
            int pivot = col;
            for (int r = col + 1; r < terms; r++) if (Math.abs(a[r * width + col]) > Math.abs(a[pivot * width + col])) pivot = r;
            if (Math.abs(a[pivot * width + col]) < 1e-9) { Arrays.fill(fit, 0); fit[0] = sum / n; return; }
            for (int c = 0; c < width; c++) { double t = a[col * width + c]; a[col * width + c] = a[pivot * width + c]; a[pivot * width + c] = t; }
            for (int r = col + 1; r < terms; r++) {
                double f = a[r * width + col] / a[col * width + col];
                for (int c = col; c < width; c++) a[r * width + c] -= f * a[col * width + c];
            }
        }
        for (int r = terms - 1; r >= 0; r--) {
            double v = a[r * width + terms];
            for (int c = r + 1; c < terms; c++) v -= a[r * width + c] * fit[c];
            fit[r] = v / a[r * width + r];
        }
    }
}
