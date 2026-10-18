package io.earthplatform.minecraft.cubic;

import java.util.Locale;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * How long the cubic world's busy paths take, for the debug screen and for
 * {@code -Dearthplatform.cubic.perf=true} logs: each timing reports how often it ran and its
 * average since it was last read, so a slow path shows while it happens. Safe from any thread.
 */
public final class Timings {
    /** -Dearthplatform.cubic.perf=true logs every timing every few seconds. */
    public static final boolean LOG = Boolean.getBoolean("earthplatform.cubic.perf");

    public static final Timings FAR_MAKE = new Timings("far make"), FAR_MESH = new Timings("far mesh"),
            FAR_UPLOAD = new Timings("far upload"), FAR_DRAW = new Timings("far draw"), FAR_WATER = new Timings("far water"), CUBE_MESH = new Timings("cube mesh"),
            CUBE_UPLOAD = new Timings("cube upload"), CUBE_DRAW = new Timings("cube draw"), FRAME = new Timings("frame"),
            SERVER_TICK = new Timings("server cubes"), CUBE_UNLOAD = new Timings("cube unload"), CUBE_FLUSH = new Timings("cube flush");

    private final String name;
    private final LongAdder count = new LongAdder(), nanos = new LongAdder();
    /** Longest run since each reader last read. */
    private final LongAccumulator[] longest = {new LongAccumulator(Math::max, 0), new LongAccumulator(Math::max, 0)};
    /** Where each reader (the debug screen, the log) last read. */
    private final long[] readCount = new long[2], readNanos = new long[2], readAt = {System.nanoTime(), System.nanoTime()};
    public static final int SCREEN = 0, LOGGER = 1;

    private Timings(String name) { this.name = name; }

    /** Adds one run that began at {@code start} (from {@link System#nanoTime()}). */
    public void since(long start) { add(System.nanoTime() - start); }

    /** Adds one run that took {@code spent} nanoseconds. */
    public void add(long spent) {
        nanos.add(spent);
        count.increment();
        for (LongAccumulator max : longest) max.accumulate(spent);
    }

    /** Non-resetting totals for the bounded diagnostic reader; existing readers remain independent. */
    public record Total(long calls,long nanos) {}
    public Total total() { return new Total(count.sum(),nanos.sum()); }

    /** "name 2.10 ms (max 9.0) x 40/s": average and longest time, and runs a second, since this reader last read. */
    public synchronized String read(int reader) {
        long now = System.nanoTime(), runs = count.sum(), total = nanos.sum();
        long n = runs - readCount[reader], spent = total - readNanos[reader];
        double seconds = Math.max(1e-3, (now - readAt[reader]) / 1e9);
        readCount[reader] = runs; readNanos[reader] = total; readAt[reader] = now;
        return String.format(Locale.ROOT, "%s %.2f ms (max %.1f) x %.0f/s", name, n == 0 ? 0 : spent / 1e6 / n,
                longest[reader].getThenReset() / 1e6, n / seconds);
    }
}
