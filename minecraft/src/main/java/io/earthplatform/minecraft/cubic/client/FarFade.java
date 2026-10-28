package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.far.Far;

/**
 * How long a column takes to fade into its children or back, by the coarser level's number, in
 * hundredths of a second. Slow, it is {@link FarShaders#FADE_TICKS}. Fast, a level's edge steps out
 * one of its columns every time the camera crosses one, at the jet stream every second or two, and
 * a 0.6 s fade then left the edge flashing forward a row at a time with a plain seam in between.
 * There a fade lasts as long as the camera takes to cross the column, so one row has finished
 * fading in as the next starts and the edge sweeps forward as a soft band. Where crossing takes
 * many seconds the fade comes back down to the usual length, so a level that hardly moves still
 * sharpens at once. Lengths change a little each tick, so a fade under way never jumps or goes back.
 */
final class FarFade {
    /** The longest fade, in hundredths of a second. */
    static final int MAX_TICKS = 300;
    /** Most a fade's length changes in a tick, in hundredths: a tick's worth, so a fade never runs backwards. */
    static final float STEP = 5.0F;

    private final float[] ticks = new float[Far.MAX_LEVEL + 2];

    FarFade() { java.util.Arrays.fill(ticks, FarShaders.FADE_TICKS); }

    /**
     * The fade for a column of {@code level} at a speed: the time to cross it, at least the usual
     * fade and at most {@link #MAX_TICKS}; past twice that it falls back to the usual length.
     */
    static float target(int level, double blocksPerSecond) {
        if (blocksPerSecond <= 0) return FarShaders.FADE_TICKS;
        double crossing = Far.width(level) / blocksPerSecond * 100.0;
        if (crossing <= FarShaders.FADE_TICKS) return FarShaders.FADE_TICKS;
        if (crossing <= MAX_TICKS) return (float) crossing;
        if (crossing >= 2 * MAX_TICKS) return FarShaders.FADE_TICKS;
        return (float) (MAX_TICKS - (crossing - MAX_TICKS) / MAX_TICKS * (MAX_TICKS - FarShaders.FADE_TICKS));
    }

    /** Moves every level's fade a tick's step towards the length for this speed. */
    void tick(double blocksPerSecond) {
        for (int level = 0; level < ticks.length; level++) {
            float target = target(Math.min(level, Far.MAX_LEVEL), blocksPerSecond);
            ticks[level] += Math.clamp(target - ticks[level], -STEP, STEP);
        }
    }

    /** The fade for a column of a level, in whole hundredths, as the column's age is compared with it. */
    int ticks(int level) { return Math.round(ticks[Math.clamp(level, 0, ticks.length - 1)]); }

    /** Every level's fade, as the shaders take it ({@code earth_FadeTicks}), indexed by level. */
    float[] table() {
        float[] table = new float[ticks.length];
        for (int level = 0; level < table.length; level++) table[level] = ticks(level);
        return table;
    }
}
