package io.earthplatform.minecraft.cubic.client;

/**
 * Admission budget for one render frame's mesh uploads. An upload already in progress cannot be
 * interrupted, so the time limit stops starting work; it is not a bound on the final upload's time.
 * The first item always progresses, including one larger than the byte limit. Keep a rejected item
 * at the head of the queue so it becomes that first item next frame, rather than starving it behind
 * smaller meshes. This policy is opt-in until frame tails and terrain convergence are measured.
 */
final class UploadBudget {
    private final long start, nanos, maxBytes;
    private long bytes;
    private int uploads;

    UploadBudget(long start, long nanos, long maxBytes) {
        if (nanos <= 0 || maxBytes <= 0) throw new IllegalArgumentException("Upload limits must be positive");
        this.start = start;
        this.nanos = nanos;
        this.maxBytes = maxBytes;
    }

    /**
     * Reserves an item immediately before uploading it. {@code now} must use the same monotonic
     * clock as {@code start}. Elapsed subtraction tolerates {@link System#nanoTime()} wrapping;
     * a negative elapsed time instead stops subsequent work conservatively. A refusal changes no
     * accounting. Byte counts include all data in the mesh, including its water and smooth grid.
     */
    boolean tryAcquire(long now, long nextBytes) {
        if (nextBytes < 0) throw new IllegalArgumentException("Upload bytes must not be negative");
        long elapsed = now - start;
        if (uploads != 0 && (elapsed < 0 || elapsed >= nanos
                || bytes > maxBytes || nextBytes > maxBytes - bytes)) return false;
        uploads++;
        bytes = nextBytes > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : bytes + nextBytes;
        return true;
    }

    int uploads() { return uploads; }
    long bytes() { return bytes; }
}
