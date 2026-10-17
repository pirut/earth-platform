package io.earthplatform.geography.globe;

import static io.earthplatform.geography.globe.TerrariumTileSource.TILE_SIZE;

/**
 * Lowers buildings standing in flat land to the ground around them, in Terrarium tiles from zoom
 * {@value #SPIKE_ZOOM} on. {@link TerrariumTileSource} runs {@link #despike} last, after the layers.
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
    static void despike(float[] values) {
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
            java.util.Arrays.sort(window, 0, n);
            float median = window[n / 2];
            if (value - median <= SPIKE_METRES || median - window[0] > FLAT_METRES || window[n - 1 - SPIKE_PIXELS] > median + SPIKE_METRES) continue;
            if (fixed == null) fixed = values.clone();
            fixed[i] = median == 0f ? Math.ulp(0f) : median;
        }
        if (fixed != null) System.arraycopy(fixed, 0, values, 0, values.length);
    }
}
