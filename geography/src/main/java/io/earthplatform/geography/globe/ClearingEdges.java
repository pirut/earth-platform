package io.earthplatform.geography.globe;

import java.util.ArrayDeque;

/**
 * Takes out the straight steps a surface model leaves where it read a forest's canopy beside cleared
 * land.
 *
 * <p>Where forest was cleared in blocks, as in Rondônia and Mato Grosso, the surveys read the canopy
 * as ground, so the land stands in flat plateaus a canopy's height, 3 to 35 m, over the fields
 * round them, edged with cliffs as straight as the clearing was cut. Land doesn't do that: a valley
 * side, a terrace or a ridge slopes over more than a pixel or two, or bends along its length, or
 * both. So a step is taken for a canopy's edge only where it is sharp (the land flat on both sides
 * of it, a pixel or two across) and holds its height along a straight line over
 * {@value #LENGTH_METRES} m; then it is followed along fainter, shorter stretches of the same kind,
 * which find the rest of the edge and its corners. Steady, low land only: where the land's broad
 * shape slopes, has the relief of hills within a kilometre, or meets water, the steps are real
 * banks, bluffs and hillsides and stay.
 *
 * <p>The step is then taken out of the land's slopes, which are put back together as the closest
 * surface to the rest of them: the plateau's edge comes down and the field beside it rises a little
 * to meet it, easing over about {@value #DECAY_METRES} m each way, so a narrow plateau comes down
 * whole and a broad one rises from the fields as gently as a low hill, while every other slope, a
 * valley or a hill crossing the edge, keeps its shape. Close to the step the land is smoothed, so
 * finer zooms, read through the same correction, show no ledge where it was.
 */
final class ClearingEdges {
    /** Reach of a step's sides from its middle. */
    static final double ACROSS_METRES = 75;
    /** Length along which a step must hold, and for the fainter ones it is followed along. */
    static final double LENGTH_METRES = 600, FAINT_LENGTH_METRES = 300;
    /** Reach past a step's sides over which the land must stay level. */
    static final double FLANK_METRES = 225, FAINT_FLANK_METRES = 225;
    /** Most the land beyond a step's sides may rise or fall, as a share of the step. */
    static final double FLANK_SHARE = 0.4, FAINT_FLANK_SHARE = 0.5;
    /** Least share of a step's height that holds its sign along the line. */
    static final double STRAIGHT = 0.92, FAINT_STRAIGHT = 0.88;
    /** Heights of a canopy: steps from the first, up to the last. */
    static final double LOWEST = 2.5, HIGHEST = 40;
    /** Farthest a step is followed from a clear one. */
    static final double FOLLOW_METRES = 2300;
    /** Directions a step is looked for in, over a half turn. */
    static final int DIRECTIONS = 12;
    /** Reach of the land's broad shape, and of the broader one whose slope stands in for a step's. */
    static final double TREND_METRES = 300, BROAD_METRES = 400;
    /** Slopes of the broad shape from which steps stay, wholly so at the second. */
    static final double FLAT_TO = 0.04, FLAT_FROM = 0.06;
    /** Reach and heights of the relief from which steps stay, wholly so at the second. */
    static final double RELIEF_METRES = 1000, RELIEF_TO = 40, RELIEF_FROM = 60;
    /** How near water a step stays. */
    static final double WATER_METRES = 500;
    /** Distance over which a step's correction fades. */
    static final double DECAY_METRES = 570;

    /**
     * A correction over a square of a window: {@code delta} to add to the land, and near the steps,
     * {@code band} of the way (0 to 1) to {@code smooth} instead.
     */
    record Steps(int from, int side, float[] delta, float[] smooth, float[] band) {}

    private ClearingEdges() {}

    /**
     * The steps to take out of a {@code span}-pixel window of land, {@code metres} a pixel across, over
     * the {@code side}-pixel square from {@code from}; null where it has none. {@code dry} marks the
     * land, {@code water} open water and wetland. The window reaches far enough round the square for
     * the steps' fade. Worked out on pixels of about 75 m: canopy steps are a pixel or two of the
     * 30 m surveys across, so still sharp there, and the correction is smooth.
     */
    static Steps find(float[] dem, boolean[] dry, boolean[] water, int span, double metres, int from, int side) {
        if (from < 0 || side < 1 || from + side > span)
            throw new IllegalArgumentException("Square " + from + "+" + side + " is not inside a " + span + "-pixel window");
        int n = span * span;
        // The gates, over the whole window: flat, low and dry.
        float[] land = new float[n], height = new float[n];
        for (int i = 0; i < n; i++) if (dry[i]) { land[i] = 1; height[i] = dem[i]; }
        int trendReach = px(TREND_METRES / 1.7, metres);
        float[] num = height, den = land.clone();
        GridFilters.blur(num, span, span, trendReach);
        GridFilters.blur(den, span, span, trendReach);
        float[] trend = new float[n];
        for (int i = 0; i < n; i++) trend[i] = den[i] > 1e-4f ? num[i] / den[i] : 0;
        int relief = px(RELIEF_METRES, metres);
        float[] highest = filter(trend, span, relief, true), lowest = filter(trend, span, relief, false);
        float[] wet = new float[n];
        for (int i = 0; i < n; i++) if (water[i]) wet[i] = 1;
        wet = filter(wet, span, px(WATER_METRES, metres), true);
        GridFilters.blur(wet, span, span, 3);

        int m = side * side;
        float[] gate = new float[m];
        boolean open = false;
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
            int w = (from + j) * span + from + i;
            double gx = (trend[Math.min(n - 1, w + 1)] - trend[Math.max(0, w - 1)]) / (2 * metres);
            double gy = (trend[Math.min(n - 1, w + span)] - trend[Math.max(0, w - span)]) / (2 * metres);
            gate[j * side + i] = (float) (clamp((FLAT_FROM - Math.hypot(gx, gy)) / (FLAT_FROM - FLAT_TO))
                    * clamp((RELIEF_FROM - (highest[w] - lowest[w])) / (RELIEF_FROM - RELIEF_TO))
                    * clamp(1 - 3 * wet[w]));
            open |= gate[j * side + i] > 0;
        }
        if (!open) return null;

        int length = px(LENGTH_METRES, metres), faintLength = px(FAINT_LENGTH_METRES, metres), across = px(ACROSS_METRES, metres);
        float[] clear = edges(dem, span, from, side, length, across, px(FLANK_METRES, metres), FLANK_SHARE, STRAIGHT);
        boolean seen = false;
        for (int j = 0; j < side && !seen; j++) for (int i = 0; i < side && !seen; i++)
            seen = clear[j * side + i] > 0.5f && dry[(from + j) * span + from + i];
        if (!seen) return null;
        float[] faint = edges(dem, span, from, side, faintLength, across, px(FAINT_FLANK_METRES, metres), FAINT_FLANK_SHARE, FAINT_STRAIGHT);

        // Follow the clear steps along the faint ones.
        int follow = px(FOLLOW_METRES, metres);
        int[] reached = new int[m];
        java.util.Arrays.fill(reached, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        boolean[] along = new boolean[m];
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
            int at = j * side + i, w = (from + j) * span + from + i;
            along[at] = faint[at] > 0.3f && dry[w];
            if (along[at] && clear[at] > 0.5f) { reached[at] = 0; queue.add(at); }
        }
        if (queue.isEmpty()) return null;
        while (!queue.isEmpty()) {
            int at = queue.poll(), j = at / side, i = at % side, d = reached[at];
            if (d >= follow) continue;
            for (int q = Math.max(0, j - 1); q <= Math.min(side - 1, j + 1); q++)
                for (int p = Math.max(0, i - 1); p <= Math.min(side - 1, i + 1); p++) {
                    int next = q * side + p;
                    if (along[next] && reached[next] < 0) { reached[next] = d + 1; queue.add(next); }
                }
        }
        float[] mask = new float[m];
        for (int i = 0; i < m; i++) if (reached[i] >= 0) mask[i] = 1;
        mask = filter(mask, side, px(ACROSS_METRES, metres), true);
        GridFilters.blur(mask, side, side, 1);
        boolean any = false;
        for (int at = 0; at < m; at++) {
            mask[at] = Math.min(1, 2 * mask[at]) * gate[at];
            any |= mask[at] > 0.01f;
        }
        if (!any) return null;

        // The land's slopes with the steps' given over to the broad shape's, put back together.
        float[] broad = dem.clone();
        GridFilters.blur(broad, span, span, px(BROAD_METRES / 1.7, metres));
        float[] dx = new float[m], dy = new float[m];
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
            int at = j * side + i, w = (from + j) * span + from + i;
            if (i + 1 < side) {
                float weight = Math.max(mask[at], mask[at + 1]);
                if (weight > 0) dx[at] = -weight * ((dem[w + 1] - dem[w]) - (broad[w + 1] - broad[w]));
            }
            if (j + 1 < side) {
                float weight = Math.max(mask[at], mask[at + side]);
                if (weight > 0) dy[at] = -weight * ((dem[w + span] - dem[w]) - (broad[w + span] - broad[w]));
            }
        }
        float[] b = new float[m];
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
            int at = j * side + i;
            float div = dx[at] + dy[at];
            if (i > 0) div -= dx[at - 1];
            if (j > 0) div -= dy[at - side];
            b[at] = -div;
        }
        double decay = DECAY_METRES / metres;
        float[] delta = solve(b, side, 1 / (decay * decay));

        float[] smooth = new float[m];
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
            int at = j * side + i;
            smooth[at] = dem[(from + j) * span + from + i] + delta[at];
        }
        GridFilters.blur(smooth, side, side, px(ACROSS_METRES, metres));
        float[] band = mask.clone();
        GridFilters.blur(band, side, side, 1);
        for (int i = 0; i < m; i++) band[i] = Math.min(1, 1.5f * band[i]);
        return new Steps(from, side, delta, smooth, band);
    }

    /**
     * How surely each pixel of the {@code side}-pixel square from {@code from} of a {@code span}-pixel
     * window lies on a straight, sharp step, 0 to 1: one that holds within {@code length} pixels
     * either way along it, measured {@code across} pixels either side, the land staying level for
     * {@code flank} pixels past that to within {@code flatness} of the step, and its sign holding
     * for {@code straightness} of its height.
     */
    static float[] edges(float[] dem, int span, int from, int side, int length, int across, int flank, double flatness, double straightness) {
        int ext = side + 2 * length, m = side * side;
        float[] best = new float[m];
        float[] step = new float[ext * ext], size = new float[ext * ext], rise = new float[ext * ext];
        float[] sum = new float[m], total = new float[m], flanks = new float[m];
        int count = 2 * length + 1;
        for (int direction = 0; direction < DIRECTIONS; direction++) {
            double angle = Math.PI * direction / DIRECTIONS;
            // Along the step, and across it.
            double ty = Math.sin(angle), tx = Math.cos(angle), ny = Math.cos(angle), nx = -Math.sin(angle);
            int ay = (int) Math.round(across * ny), ax = (int) Math.round(across * nx);
            int by = (int) Math.round(flank * ny), bx = (int) Math.round(flank * nx);
            int origin = from - length;
            for (int j = 0; j < ext; j++) for (int i = 0; i < ext; i++) {
                int y = origin + j, x = origin + i;
                float p2 = at(dem, span, y + ay, x + ax), m2 = at(dem, span, y - ay, x - ax);
                float p5 = at(dem, span, y + by, x + bx), m5 = at(dem, span, y - by, x - bx);
                int e = j * ext + i;
                step[e] = p2 - m2;
                size[e] = Math.abs(p2 - m2);
                rise[e] = Math.max(Math.abs(p5 - p2), Math.abs(m2 - m5));
            }
            java.util.Arrays.fill(sum, 0);
            java.util.Arrays.fill(total, 0);
            java.util.Arrays.fill(flanks, 0);
            for (int k = -length; k <= length; k++) {
                int oy = (int) Math.round(k * ty) + length, ox = (int) Math.round(k * tx) + length;
                for (int j = 0; j < side; j++) {
                    int row = j * side, e = (j + oy) * ext + ox;
                    for (int i = 0; i < side; i++) {
                        sum[row + i] += step[e + i];
                        total[row + i] += size[e + i];
                        flanks[row + i] += rise[e + i];
                    }
                }
            }
            for (int i = 0; i < m; i++) {
                double s = Math.abs(sum[i]) / count;
                if (s < LOWEST) continue;
                double straight = Math.abs(sum[i]) / (total[i] + 1e-6), level = flanks[i] / count / (s + 1e-6);
                double score = clamp((s - LOWEST) / 2) * clamp((HIGHEST - s) / 10)
                        * clamp((flatness - level) / 0.15) * clamp((straight - straightness) / 0.05);
                if (score > best[i]) best[i] = (float) score;
            }
        }
        return best;
    }

    /**
     * Solves {@code (lambda - laplacian) x = b} over a square {@code side} across, the edges held level,
     * by multigrid: smoothing settles the fine detail, and the broad remainder is solved on a grid half
     * as fine, and so on down.
     */
    static float[] solve(float[] b, int side, double lambda) {
        double[] x = new double[side * side], rhs = new double[side * side];
        double norm = 0;
        for (int i = 0; i < rhs.length; i++) { rhs[i] = b[i]; norm += rhs[i] * rhs[i]; }
        for (int cycle = 0; cycle < 12; cycle++) {
            cycle(x, rhs, side, lambda, 1);
            if (residual(x, rhs, side, lambda, 1, null) < 1e-8 * norm) break;
        }
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++) out[i] = (float) x[i];
        return out;
    }

    /** One V-cycle on a grid {@code side} across whose pixels are {@code h} apart. */
    private static void cycle(double[] x, double[] b, int side, double lambda, double h) {
        if (side <= 4) {
            for (int sweep = 0; sweep < 50; sweep++) relax(x, b, side, lambda, h);
            return;
        }
        relax(x, b, side, lambda, h);
        relax(x, b, side, lambda, h);
        double[] r = new double[side * side];
        residual(x, b, side, lambda, h, r);
        int coarse = (side + 1) / 2;
        double[] rc = new double[coarse * coarse], ec = new double[coarse * coarse];
        int[] count = new int[coarse * coarse];
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
            int c = (j >> 1) * coarse + (i >> 1);
            rc[c] += r[j * side + i];
            count[c]++;
        }
        for (int c = 0; c < rc.length; c++) rc[c] /= count[c];
        cycle(ec, rc, coarse, lambda, 2 * h);
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
            // Bilinear from the coarse cells' centres.
            double u = (i - 0.5) / 2, v = (j - 0.5) / 2;
            int i0 = Math.max(0, Math.min(coarse - 1, (int) Math.floor(u))), j0 = Math.max(0, Math.min(coarse - 1, (int) Math.floor(v)));
            int i1 = Math.min(coarse - 1, i0 + 1), j1 = Math.min(coarse - 1, j0 + 1);
            double fu = Math.max(0, Math.min(1, u - i0)), fv = Math.max(0, Math.min(1, v - j0));
            x[j * side + i] += (ec[j0 * coarse + i0] * (1 - fu) + ec[j0 * coarse + i1] * fu) * (1 - fv)
                    + (ec[j1 * coarse + i0] * (1 - fu) + ec[j1 * coarse + i1] * fu) * fv;
        }
        relax(x, b, side, lambda, h);
        relax(x, b, side, lambda, h);
    }

    /** A red-black Gauss-Seidel sweep. */
    private static void relax(double[] x, double[] b, int side, double lambda, double h) {
        double scale = 1 / (h * h);
        for (int colour = 0; colour < 2; colour++)
            for (int j = 0; j < side; j++)
                for (int i = (j + colour) & 1; i < side; i += 2) {
                    int at = j * side + i, k = 0;
                    double around = 0;
                    if (i > 0) { around += x[at - 1]; k++; }
                    if (i + 1 < side) { around += x[at + 1]; k++; }
                    if (j > 0) { around += x[at - side]; k++; }
                    if (j + 1 < side) { around += x[at + side]; k++; }
                    x[at] = (b[at] + around * scale) / (lambda + k * scale);
                }
    }

    /** The squared size of {@code b - (lambda - laplacian) x}, written to {@code r} if given. */
    private static double residual(double[] x, double[] b, int side, double lambda, double h, double[] r) {
        double scale = 1 / (h * h), sum = 0;
        for (int j = 0; j < side; j++) for (int i = 0; i < side; i++) {
            int at = j * side + i, k = 0;
            double around = 0;
            if (i > 0) { around += x[at - 1]; k++; }
            if (i + 1 < side) { around += x[at + 1]; k++; }
            if (j > 0) { around += x[at - side]; k++; }
            if (j + 1 < side) { around += x[at + side]; k++; }
            double rest = b[at] - (lambda + k * scale) * x[at] + around * scale;
            if (r != null) r[at] = rest;
            sum += rest * rest;
        }
        return sum;
    }

    private static float at(float[] dem, int span, int y, int x) {
        return dem[Math.max(0, Math.min(span - 1, y)) * span + Math.max(0, Math.min(span - 1, x))];
    }

    /** The highest, or lowest, value within {@code radius} along rows, then columns, of a square grid. */
    static float[] filter(float[] a, int width, int radius, boolean highest) {
        float[] rows = new float[a.length], out = new float[a.length];
        for (int j = 0; j < width; j++) for (int i = 0; i < width; i++) {
            float most = a[j * width + i];
            for (int k = Math.max(0, i - radius); k <= Math.min(width - 1, i + radius); k++)
                most = highest ? Math.max(most, a[j * width + k]) : Math.min(most, a[j * width + k]);
            rows[j * width + i] = most;
        }
        for (int i = 0; i < width; i++) for (int j = 0; j < width; j++) {
            float most = rows[j * width + i];
            for (int k = Math.max(0, j - radius); k <= Math.min(width - 1, j + radius); k++)
                most = highest ? Math.max(most, rows[k * width + i]) : Math.min(most, rows[k * width + i]);
            out[j * width + i] = most;
        }
        return out;
    }

    private static int px(double reach, double metres) { return Math.max(1, (int) Math.round(reach / metres)); }

    private static double clamp(double t) { return Math.min(1, Math.max(0, t)); }
}
