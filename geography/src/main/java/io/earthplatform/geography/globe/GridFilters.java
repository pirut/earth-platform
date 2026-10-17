package io.earthplatform.geography.globe;

/**
 * Filters over grids of samples stored row by row: blurs, a weighted blur, a quantile, bilinear
 * sampling, filling between samples worked out every other pixel, and growing a marked area.
 * {@link BareEarth} works out its fields with them and {@link ClearingEdges} blurs with them.
 */
final class GridFilters {
    private GridFilters() {}

    /** The cells of a square grid within {@code radius} of a marked one, along rows then columns. */
    static boolean[] dilate(boolean[] marked, int side, int radius) {
        boolean[] rows = new boolean[marked.length], out = new boolean[marked.length];
        for (int j = 0; j < side; j++) {
            int last = Integer.MIN_VALUE / 2;
            for (int i = 0; i < side; i++) if (marked[j * side + i]) last = i;
                else if (i - last <= radius) rows[j * side + i] = true;
            last = Integer.MAX_VALUE / 2;
            for (int i = side - 1; i >= 0; i--) {
                if (marked[j * side + i]) { last = i; rows[j * side + i] = true; }
                else if (last - i <= radius) rows[j * side + i] = true;
            }
        }
        for (int i = 0; i < side; i++) {
            int last = Integer.MIN_VALUE / 2;
            for (int j = 0; j < side; j++) if (rows[j * side + i]) last = j;
                else if (j - last <= radius) out[j * side + i] = true;
            last = Integer.MAX_VALUE / 2;
            for (int j = side - 1; j >= 0; j--) {
                if (rows[j * side + i]) { last = j; out[j * side + i] = true; }
                else if (last - j <= radius) out[j * side + i] = true;
            }
        }
        return out;
    }

    /** Bilinear sample of a square grid {@code width} across at a pixel position, clamped at its edges; NaN where a corner is. */
    static float sample(float[] grid, int width, double x, double y) {
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(width - 1, y));
        int x0 = Math.min(width - 2, (int) x), y0 = Math.min(width - 2, (int) y);
        double fx = x - x0, fy = y - y0;
        int at = y0 * width + x0;
        return (float) ((grid[at] * (1 - fx) + grid[at + 1] * fx) * (1 - fy) + (grid[at + width] * (1 - fx) + grid[at + width + 1] * fx) * fy);
    }

    /** Fills a grid worked out every {@code stride} pixels between its known ones. */
    static void fill(float[] grid, int side, int stride) {
        if (stride != 2) return;
        // Odd columns, then odd rows, from their even neighbours.
        for (int j = 0; j < side; j += 2) for (int i = 1; i < side; i += 2) {
            int at = j * side + i;
            grid[at] = i + 1 < side ? (grid[at - 1] + grid[at + 1]) / 2 : grid[at - 1];
        }
        for (int j = 1; j < side; j += 2) for (int i = 0; i < side; i++) {
            int at = j * side + i;
            grid[at] = j + 1 < side ? (grid[at - side] + grid[at + side]) / 2 : grid[at - side];
        }
    }

    /** The k-th smallest of the first n values, which are reordered. */
    static float select(float[] a, int n, int k) {
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            float pivot = a[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) { float t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else return a[k];
        }
        return a[k];
    }

    /** A three-pass blur of a square grid's {@code values} weighted by {@code weights}; where the weights are all zero, {@code empty}. */
    static float[] weighted(float[] values, float[] weights, int width, int radius, float empty) {
        float[] num = new float[values.length], den = weights.clone();
        for (int i = 0; i < num.length; i++) num[i] = values[i] * weights[i];
        blur(num, width, width, radius);
        blur(den, width, width, radius);
        for (int i = 0; i < num.length; i++) num[i] = den[i] > 1e-4f ? num[i] / den[i] : empty;
        return num;
    }

    /** Three passes of a box blur of {@code radius} along rows and columns, about a Gaussian; edges are held. */
    static void blur(float[] a, int width, int height, int radius) {
        float[] line = new float[Math.max(width, height)], out = new float[line.length];
        for (int pass = 0; pass < 3; pass++) {
            for (int j = 0; j < height; j++) {
                System.arraycopy(a, j * width, line, 0, width);
                box(line, out, width, radius);
                System.arraycopy(out, 0, a, j * width, width);
            }
            for (int i = 0; i < width; i++) {
                for (int j = 0; j < height; j++) line[j] = a[j * width + i];
                box(line, out, height, radius);
                for (int j = 0; j < height; j++) a[j * width + i] = out[j];
            }
        }
    }

    /** A running box mean of the first {@code n} values, the ends held beyond the line. */
    private static void box(float[] line, float[] out, int n, int radius) {
        float first = line[0], last = line[n - 1], scale = 1f / (2 * radius + 1);
        double sum = 0;
        for (int k = -radius; k <= radius; k++) sum += line[Math.max(0, Math.min(n - 1, k))];
        for (int i = 0; i < n; i++) {
            out[i] = (float) sum * scale;
            int add = i + radius + 1, drop = i - radius;
            sum += (add < n ? line[add] : last) - (drop >= 0 ? line[drop] : first);
        }
    }
}
