package io.earthplatform.minecraft.cubic.client;

/** Cooperative render-thread budget: one indivisible job may finish past the deadline. */
final class CubeWorkBudget {
    private final long start, nanos;
    private final int maximum;
    private int started;

    CubeWorkBudget(long start, long nanos, int maximum) {
        this.start = start;
        this.nanos = nanos;
        this.maximum = maximum;
    }

    boolean take(long now) {
        if (started >= maximum || started > 0 && now - start >= nanos) return false;
        started++;
        return true;
    }

    static int inFlight(long frameNanos, int workers) {
        // Never interpret a stalled/paused frame as permission for hundreds of snapshots.
        long frames = Math.clamp(frameNanos, 0, 50_000_000L);
        return (int) Math.clamp(frames * workers / 5_000_000L, workers * 2L, workers * 8L);
    }
}
