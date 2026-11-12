package io.earthplatform.minecraft.cubic.client;

/**
 * How much of a frame the render thread gives near cube work: up to {@code maximum} jobs, started while {@code nanos}
 * last. The first job always starts, so work goes on however slow the frame, and a job may end past the deadline.
 */
final class CubeWorkBudget {
    /** About how long one mesh takes a worker, to keep the workers busy for a whole frame. */
    static final long MESH_NANOS = 5_000_000L;
    static final int MIN_IN_FLIGHT = 48, MAX_IN_FLIGHT = 512;

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

    /**
     * The frame time budgets go by: the shorter of the last two frames. One long frame, such as a pause or a stall
     * while a world loads, is not a slow frame rate, and taken for one it let the next frame make hundreds of
     * snapshots and uploads at once.
     */
    static long pace(long lastNanos, long previousNanos) { return Math.min(lastNanos, previousNanos); }

    /**
     * Meshes kept started: enough to keep the workers busy until the next frame. A fixed 12 (then 48) meant a frame
     * rate of 1 under a shader pack built 12 cubes a second.
     */
    static int inFlight(long paceNanos, int workers) {
        return (int) Math.clamp(paceNanos * workers / MESH_NANOS, MIN_IN_FLIGHT, MAX_IN_FLIGHT);
    }

    /** Uploads: an eighth of the frame, as before, without the 48 that had to go up first however long they took. */
    static long uploadNanos(long paceNanos) { return paceNanos / 8; }

    /** Snapshots: a sixteenth of the frame, about a millisecond at 60 frames a second. */
    static long snapshotNanos(long paceNanos) { return paceNanos / 16; }
}
