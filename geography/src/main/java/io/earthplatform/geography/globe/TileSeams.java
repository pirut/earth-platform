package io.earthplatform.geography.globe;

import io.earthplatform.geography.globe.TerrariumTileSource.Neighbours;

import static io.earthplatform.geography.globe.TerrariumTileSource.TILE_SIZE;

/**
 * Mends the lines of bad pixels along whole degrees of latitude and longitude in a Terrarium
 * tile, where the composite's 1 degree SRTM source tiles meet; see {@link #seams}.
 */
final class TileSeams {
    private TileSeams() {}

    /** Degrees either side of a whole-degree line within which its bad pixels lie: three quarters of an SRTM arc-second. */
    private static final double SEAM_REACH = 0.75 / 3600;
    /**
     * Degrees either side of a whole-degree line that a resampled ocean void spreads. The composite's
     * fine tiles hold a slot a few arc-seconds wide along some whole-degree lines over the sea, about
     * 14 km deep south of Durban and the same shape on other oceans, where an SRTM edge was filled and
     * then resampled. {@link #SEAM_REACH} does not cover it, and the sea either side is exactly 0, which
     * {@link #across} will not use as ground.
     */
    private static final double VOID_REACH = 12.0 / 3600;
    /** Metres a void must lie below the ground outside the slot. Shallower seabed relief stays. */
    private static final double VOID_DROP = 40;
    /** Voids are below the sea. Land above this, and the open sea's own zeros, are left as they are. */
    private static final float VOID_SEA = -8f;
    /**
     * Shores this close to sea level are the sea. The resampled slot leaves a metre or two of ringing on its lip,
     * and filling toward that lip would leave a reef; both lips that low means the slot is open sea.
     */
    private static final float VOID_SHORE = 5f;
    /**
     * A void this deep is mended even where it does not run on along the line: the resampled slot frays
     * into a few pixels at a tile's edge, still kilometres deep, and a lone pixel that far below the sea
     * with ordinary ground either side of the line is the same fill.
     */
    private static final float VOID_ALONE = -120f;
    /** Coarsest zoom {@link #seams} mends; below it a seam averages away into pixels a kilometre or more across. */
    static final int SEAM_ZOOM = 10;

    /**
     * Mends the lines of bad pixels along whole degrees of latitude and longitude, where the
     * composite's 1° SRTM source tiles meet. In places a row or column there holds void fill or
     * resampled values: a trench or ridge running dead straight for kilometres, 4 km deep along
     * 31°N in Tibet and 80 m along 5°W in the Highlands. Its pixels agree with each other, so
     * {@link TileMending#mend} passes them. Across each line, a run of up to {@value #SEAM_RUN} pixels within
     * {@link #SEAM_REACH} of it is replaced by the line through the two pixels either side of it
     * when every pixel of the run lies further from that line than 4 m and a pixel's width, than
     * twice the step between either pair, and than four times as far as the pairs stray from it,
     * so cliff edges and rough ground keep their shape; and only where pixels about as far from
     * the line are off too at most of the five places along it. Pixels beyond the tile's edge come from
     * its neighbours, unmended as this tile is, so both sides of a seam agree.
     *
     * <p>A second pass, {@link #voidSlot}, fills the wider ocean voids {@link #across} misses: pixels
     * below the sea by tens of metres or more, inside {@link #VOID_REACH} of the line, with ordinary
     * ground or open sea just outside that. Open sea either side becomes open sea again, so the
     * bathymetry fill can put the real seabed back; land above the sea is not lowered.
     */
    static float[] seams(float[] metres, int zoom, int tileX, int tileY, Neighbours neighbours) {
        if (zoom < SEAM_ZOOM) return metres;
        double pixel = 40_075_016.7 / Math.cosh(Math.PI * (1 - 2.0 * (tileY + 0.5) / (1 << zoom))) / ((long) TILE_SIZE << zoom);
        float[][] out = {metres};
        seamLines(zoom, tileX, tileY, SEAM_REACH, (meridian, line, band) -> out[0] = across(metres, out[0], meridian, line, band, pixel, zoom, tileX, tileY, neighbours));
        seamLines(zoom, tileX, tileY, VOID_REACH, (meridian, line, band) -> out[0] = voidSlot(out[0], meridian, line, band, pixel, zoom, tileX, tileY, neighbours));
        return out[0];
    }

    /** A seam {@link #seams} looks at: column {@code line} (a meridian) or row {@code line} (a parallel) and its band. */
    private interface SeamLine { void at(boolean meridian, int line, int band); }

    /**
     * The whole-degree lines near a tile, in its pixels, with the band of pixels either side that a reach of
     * {@code reachDegrees} covers. {@link #SEAM_REACH} is the narrow SRTM edge; {@link #VOID_REACH} is the
     * resampled ocean void, which asks for the same neighbours and more.
     */
    private static void seamLines(int zoom, int tileX, int tileY, double reachDegrees, SeamLine each) {
        long size = (long) TILE_SIZE << zoom;
        double degrees = 360.0 / size;
        int band = (int) Math.ceil(reachDegrees / degrees) + 1;
        long left = (long) tileX * TILE_SIZE;
        for (long lon = (long) Math.ceil((left - band - 3) * degrees - 180); lon <= (left + TILE_SIZE + band + 3) * degrees - 180; lon++) {
            int line = (int) ((long) Math.floor((lon + 180) / 360.0 * size) - left);
            each.at(true, line, band);
        }
        long top = (long) tileY * TILE_SIZE;
        double north = latitude(top - 4 * band - 3, size), south = latitude(top + TILE_SIZE + 4 * band + 3, size);
        for (long lat = (long) Math.ceil(south); lat <= north; lat++) {
            double phi = Math.toRadians(lat);
            int line = (int) ((long) Math.floor(Mercator.row(phi, size)) - top);
            int rows = (int) Math.ceil(reachDegrees / (degrees * Math.cos(phi))) + 1;
            each.at(false, line, rows);
        }
    }

    /** The neighbouring tiles {@link #seams} will read for a tile, as {@link #across} and {@link #voidSlot} ask for them; known before any pixel is. */
    static void seamNeighbours(int zoom, int tileX, int tileY, Neighbour each) {
        if (zoom < SEAM_ZOOM) return;
        // The void reach is the wider one, so its neighbours include the narrow pass's.
        seamLines(zoom, tileX, tileY, VOID_REACH, (meridian, line, band) -> {
            int reach = band + 2;
            if (line + band < 0 || line - band >= TILE_SIZE) return;
            if (line - reach < 0) { if (meridian) each.at(zoom, tileX - 1, tileY); else each.at(zoom, tileX, tileY - 1); }
            if (line + reach >= TILE_SIZE) { if (meridian) each.at(zoom, tileX + 1, tileY); else each.at(zoom, tileX, tileY + 1); }
        });
    }

    interface Neighbour { void at(int zoom, int x, int y); }

    /** Latitude in degrees of the top edge of a global pixel row, clamped to the map. */
    private static double latitude(long row, long size) {
        double clamped = Math.max(0, Math.min(size, row));
        return Mercator.latitude(clamped, size);
    }

    /** Widest run of bad pixels across a seam that {@link #seams} mends. */
    private static final int SEAM_RUN = 5;

    /** One seam of {@link #seams}: column {@code line} (a meridian) or row {@code line} (a parallel), in this tile's pixels. */
    private static float[] across(float[] metres, float[] out, boolean meridian, int line, int band, double pixel,
                                  int zoom, int tileX, int tileY, Neighbours neighbours) {
        int reach = band + 2;
        if (line + band < 0 || line - band >= TILE_SIZE) return out;
        float[] before = null, after = null;
        if (line - reach < 0) before = meridian ? neighbours.tile(zoom, tileX - 1, tileY) : neighbours.tile(zoom, tileX, tileY - 1);
        if (line + reach >= TILE_SIZE) after = meridian ? neighbours.tile(zoom, tileX + 1, tileY) : neighbours.tile(zoom, tileX, tileY + 1);
        int width = 2 * band + 1;
        double[] profile = new double[2 * reach + 1], expected = new double[TILE_SIZE * width], misfit = new double[TILE_SIZE * width];
        boolean[] off = new boolean[TILE_SIZE * width];
        for (int s = 0; s < TILE_SIZE; s++) {
            for (int k = -reach; k <= reach; k++) {
                float v = at(metres, before, after, meridian, line + k, s);
                profile[k + reach] = v == 0f ? Double.NaN : v;
            }
            // Runs across the line, within the reach, that lie off the line through the ground two pixels either side
            // of them throughout; a pixel in several takes the line that fits its ground best.
            for (int run = 1; run <= SEAM_RUN; run++) {
                for (int first = 2; first + run + 2 <= profile.length; first++) {
                    int last = first + run - 1;
                    double l2 = profile[first - 2], l1 = profile[first - 1], r1 = profile[last + 1], r2 = profile[last + 2];
                    if (Double.isNaN(l2) || Double.isNaN(l1) || Double.isNaN(r1) || Double.isNaN(r2)) continue;
                    // Least-squares line through the four, at offsets -2, -1, run, run + 1 from the run's first pixel.
                    double mean = (run - 1) / 2.0, x0 = -2 - mean, x1 = -1 - mean, x2 = run - mean, x3 = run + 1 - mean;
                    double slope = (x0 * l2 + x1 * l1 + x2 * r1 + x3 * r2) / (x0 * x0 + x1 * x1 + x2 * x2 + x3 * x3);
                    double centre = (l2 + l1 + r1 + r2) / 4;
                    double m0 = l2 - centre - slope * x0, m1 = l1 - centre - slope * x1, m2 = r1 - centre - slope * x2, m3 = r2 - centre - slope * x3;
                    double rms = Math.sqrt((m0 * m0 + m1 * m1 + m2 * m2 + m3 * m3) / 2);
                    double limit = Math.max(Math.max(4 + pixel, 2 * Math.max(Math.abs(l1 - l2), Math.abs(r2 - r1))), 4 * rms);
                    boolean all = true;
                    for (int c = first; c <= last && all; c++) all = Math.abs(profile[c] - (centre + slope * (c - first - mean))) > limit;
                    if (!all) continue;
                    for (int c = first; c <= last; c++) {
                        int k = c - reach, at = s * width + k + band;
                        if (Math.abs(k) > band || line + k < 0 || line + k >= TILE_SIZE || off[at] && misfit[at] <= rms) continue;
                        off[at] = true;
                        misfit[at] = rms;
                        expected[at] = centre + slope * (c - first - mean);
                    }
                }
            }
        }
        // A seam runs along its line: a pixel is only mended where pixels at about its offset are off for most of
        // the five places around it, so a gully or knoll the line happens to cross keeps its shape. A pixel the fit
        // missed, as beside a knoll, between three or four off by about the same at its own offset is off by that too.
        for (int s = 0; s < TILE_SIZE; s++) {
            for (int k = 0; k < width; k++) {
                int c = line + k - band;
                if (c < 0 || c >= TILE_SIZE) continue;
                int index = meridian ? s * TILE_SIZE + c : c * TILE_SIZE + s;
                if (metres[index] == 0f) continue;
                int along = 0, same = 0;
                double low = Double.MAX_VALUE, high = -Double.MAX_VALUE, sum = 0;
                for (int t = Math.max(0, s - 2); t <= Math.min(TILE_SIZE - 1, s + 2); t++) {
                    boolean near = false;
                    for (int j = Math.max(0, k - 1); j <= Math.min(width - 1, k + 1); j++) near |= off[t * width + j];
                    if (near) along++;
                    if (t == s || !off[t * width + k]) continue;
                    double miss = (meridian ? metres[t * TILE_SIZE + c] : metres[c * TILE_SIZE + t]) - expected[t * width + k];
                    low = Math.min(low, miss); high = Math.max(high, miss); sum += miss; same++;
                }
                double value;
                if (off[s * width + k]) {
                    if (along < 3) continue;
                    value = expected[s * width + k];
                } else {
                    double miss = same == 0 ? 0 : sum / same;
                    if (same < 3 || Math.abs(miss) <= 4 + pixel || high - low > Math.abs(miss) / 2) continue;
                    value = metres[index] - miss;
                }
                if (out == metres) out = metres.clone();
                out[index] = (float) value;
            }
        }
        return out;
    }

    /**
     * One whole-degree line's ocean void. Within {@code band} pixels of the line, each run of pixels below the
     * sea that has a shore on both ends is the void when it lies more than {@link #VOID_DROP} under the line
     * joining those shores. The resampled slot does not sit on the degree pixel itself: south of Durban the sea
     * between 30°S and the slot is ordinary 0, and the slot, a few arc-seconds wide and offset to the south, runs
     * to about 14 km below sea level. Open sea is 0 here, which {@link #across} treats as missing, so that pass
     * never sees it. Where both shores are sea the slot becomes sea again; where one shore is a beach the slot
     * meets it. A run that reaches the edge of the search has no shore there, so a deep ocean stays deep, and
     * land above the sea is not lowered.
     */
    private static float[] voidSlot(float[] tile, boolean meridian, int line, int band, double pixel,
                                    int zoom, int tileX, int tileY, Neighbours neighbours) {
        if (line + band < 0 || line - band >= TILE_SIZE) return tile;
        float[] before = null, after = null;
        if (line - band < 0) before = meridian ? neighbours.tile(zoom, tileX - 1, tileY) : neighbours.tile(zoom, tileX, tileY - 1);
        if (line + band >= TILE_SIZE) after = meridian ? neighbours.tile(zoom, tileX + 1, tileY) : neighbours.tile(zoom, tileX, tileY + 1);
        int width = 2 * band + 1;
        boolean[] off = new boolean[TILE_SIZE * width];
        double[] expected = new double[TILE_SIZE * width];
        boolean[] openSea = new boolean[TILE_SIZE * width];
        float[] profile = new float[width];
        for (int s = 0; s < TILE_SIZE; s++) {
            for (int k = -band; k <= band; k++) profile[k + band] = at(tile, before, after, meridian, line + k, s);
            for (int a = 0; a < width; ) {
                while (a < width && !(profile[a] < VOID_SEA)) a++;
                int b = a;
                while (b < width && profile[b] < VOID_SEA) b++;
                // A run with a shore at both ends. One that meets the search edge may be deep ocean with no far shore.
                if (a > 0 && b < width && b > a) {
                    float ls = profile[a - 1], rs = profile[b];
                    if (!Float.isNaN(ls) && !Float.isNaN(rs)) {
                        float furtherLeft = a > 1 ? profile[a - 2] : ls, furtherRight = b + 1 < width ? profile[b + 1] : rs;
                        // A neighbouring void is not the ground's slope; measuring the step across it would hide this one.
                        double step = 0;
                        if (!Float.isNaN(furtherLeft) && furtherLeft >= VOID_SEA) step = Math.abs(ls - furtherLeft);
                        if (!Float.isNaN(furtherRight) && furtherRight >= VOID_SEA) step = Math.max(step, Math.abs(rs - furtherRight));
                        double limit = Math.max(VOID_DROP, Math.max(4 + pixel, 2 * step));
                        boolean open = ls <= VOID_SHORE && rs <= VOID_SHORE;
                        double span = b - (a - 1);
                        for (int p = a; p < b; p++) {
                            float v = profile[p];
                            if (Float.isNaN(v) || v == 0f) continue;
                            int c = line + p - band;
                            if (c < 0 || c >= TILE_SIZE) continue;
                            double level = ls + (p - (a - 1)) / span * (rs - ls);
                            if (v >= level - limit) continue;
                            off[s * width + p] = true;
                            openSea[s * width + p] = open;
                            expected[s * width + p] = level;
                        }
                    }
                }
                a = Math.max(b, a + 1);
            }
        }
        float[] out = tile;
        for (int s = 0; s < TILE_SIZE; s++) {
            for (int k = 0; k < width; k++) {
                if (!off[s * width + k]) continue;
                int c = line + k - band;
                int index = meridian ? s * TILE_SIZE + c : c * TILE_SIZE + s;
                int along = 0;
                for (int t = Math.max(0, s - 2); t <= Math.min(TILE_SIZE - 1, s + 2); t++) {
                    boolean near = false;
                    for (int j = Math.max(0, k - 1); j <= Math.min(width - 1, k + 1); j++) near |= off[t * width + j];
                    if (near) along++;
                }
                if (along < 3 && tile[index] >= VOID_ALONE) continue;
                double level = expected[s * width + k];
                float value = openSea[s * width + k] || level <= 0 ? 0f : (float) level;
                if (value == tile[index]) continue;
                if (out == tile) out = tile.clone();
                out[index] = value;
            }
        }
        return out;
    }

    /** A pixel across a seam, {@code c} pixels into this tile's frame (negative or past its edge in a neighbour); NaN when missing. */
    private static float at(float[] metres, float[] before, float[] after, boolean meridian, int c, int s) {
        float[] tile = metres;
        if (c < 0) { tile = before; c += TILE_SIZE; } else if (c >= TILE_SIZE) { tile = after; c -= TILE_SIZE; }
        if (tile == null) return Float.NaN;
        return meridian ? tile[s * TILE_SIZE + c] : tile[c * TILE_SIZE + s];
    }
}
