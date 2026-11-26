package io.earthplatform.geography.globe;

/** Test-only bridge exposing package-private {@link MemoryPressure} hooks to cross-package tests. */
public final class MemoryPressureTestBridge {
    private MemoryPressureTestBridge() {}

    public static void triggerHigh() {
        long max = 4L << 30;
        long highUsed = (long) (max * 0.85);
        MemoryPressure.looked(highUsed, max, MemoryPressure.LOOKS_HIGH, false, false, System.nanoTime(), msg -> {});
    }

    public static void awaitShrinks() {
        MemoryPressure.awaitShrink();
    }

    public static void reset() {
        MemoryPressure.reset();
    }
}
