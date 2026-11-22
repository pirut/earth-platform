package io.earthplatform.minecraft.cubic.earth;

/**
 * Where a water level that was taken from its shores meets the bank beside one column.
 *
 * <p>Lakes and rivers are levelled from the shores of a wide reach, and a reservoir whose survey
 * is the valley before the dam stays at those shores, far above the old bed. The same rule lifts
 * a canyon river and the pool under a falls: the coarse shore there is the wall blurred into the
 * bank, so the Colorado's floor shows through a flat sheet and Niagara's gorge stands as a pillar
 * of the upper river. The bank on the ground the column is built from tells them apart. A
 * reservoir's bank sits up at the level. A channel's bank sits with the ground, and the level
 * comes down to it. Open water with no bank in reach, the middle of a wide reservoir, is left as
 * it is.
 */
final class WaterLevels {
    /** No bank was found within the search. */
    static final int MISSING = Integer.MIN_VALUE;

    private WaterLevels() {}

    /**
     * The bank at or above {@code ground} nearest it. Banks below the column are a lower terrace
     * or the next reach downstream, not this channel's edge. {@link #MISSING} when none qualify.
     */
    static int closestBank(int ground, int... heights) {
        int best = MISSING, bestRise = Integer.MAX_VALUE;
        for (int height : heights) {
            if (height == MISSING || height < ground) continue;
            int rise = height - ground;
            if (rise < bestRise) { bestRise = rise; best = height; }
        }
        return best;
    }

    /**
     * The bank one direction found. {@code land} is the first dry ground and {@code waterSide} the
     * last water before it. On a gentle edge they are a few blocks apart and the dry ground is the
     * bank. Where the dry ground is a cliff and the water in front of it is still this column's
     * channel, the cliff top is not the bank and the channel's edge is.
     *
     * @param fall how far, in blocks, a cliff stands above the water in front of it
     */
    static int bankReading(int ground, int land, int waterSide, int fall) {
        if (land - waterSide > fall && waterSide <= ground + fall) return waterSide;
        return land;
    }

    /**
     * @param surface the proposed water surface, in block Y
     * @param ground  this column's own ground, in block Y
     * @param bank    the bank beside it, from {@link #closestBank}, or {@link #MISSING}
     * @param slack   how far, in blocks, water may stand above the bank it meets
     */
    static int capToBanks(int surface, int ground, int bank, int slack) {
        if (bank == MISSING || surface <= ground + slack || surface <= bank + slack) return surface;
        // Nearer the bed than the proposed level: the level is above the bank, not resting on it.
        if (bank - ground < surface - bank) return bank + slack;
        return surface;
    }
}
