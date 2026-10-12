package io.earthplatform.minecraft.survival.runtime;

/** Probe-local conservative allocation model. No external authority or callback is retained.
 * This bounds the charged allocation volume, not an assertion about exact JVM heap size.
 * Reservation precedes copying; a failed reservation cannot spend or recycle credit.
 */
final class InstalledProbeMetadataBudget {
    private final long limit;
    private long charged;
    InstalledProbeMetadataBudget(long limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative diagnostic metadata limit");
        this.limit = limit;
    }
    void reserve(long bytes) {
        if (bytes < 0 || bytes > limit - charged)
            throw new IllegalStateException("Diagnostic aggregate metadata precharge exceeded");
        charged += bytes;
    }
    long charged() { return charged; }
}
