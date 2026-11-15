package io.earthplatform.geography.globe;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Whether Java is close to running out of memory, so Earth's caches and background work can make
 * room before the game dies. Earth sizes its caches and its far terrain threads by the memory and
 * processors it is given, as if it had them to itself; in a big mod pack the other mods hold much
 * of that memory, and arriving somewhere new (dozens of megabytes of map data decoded at once on
 * every core) then filled a 4 GB game until it exited with no crash report.
 *
 * <p>A daemon thread looks twice a second at the long-lived part of the heap (G1's old generation,
 * or its like) and at whether the collector had to stop everything to compact the whole heap, which
 * it only does once the heap is full. Memory counts as short ({@link #high()}) after a whole-heap
 * collection, or once the long-lived part stays above {@link #HIGH} of the most Java may use. While
 * it is short the globe's in-memory caches keep a quarter of their usual entries (see {@link #scaled})
 * and far terrain makes fewer columns at once. It counts as plentiful again only once the long-lived
 * part is under {@link #LOW} and has not been short for {@link #HOLD_NANOS}: G1 lets its old
 * generation fill and empty in a saw-tooth, and without the hold a game at the edge flipped every
 * few seconds.
 */
public final class MemoryPressure {
    /** Share of the most memory, held in the long-lived heap, that counts as running short. */
    static final double HIGH = 0.80;
    /** Share under which memory may count as plentiful again. */
    static final double LOW = 0.65;
    /** Share of their usual entries the in-memory caches keep while memory is short. */
    static final double SHORT_SHARE = 0.25;
    /** How long memory stays short after the last sign of it. */
    static final long HOLD_NANOS = 30_000_000_000L;
    private static final long LOOK_MS = 500;
    /** Looks in a row above {@link #HIGH} before memory counts as short: a second, past one late collection. */
    static final int LOOKS_HIGH = 2;

    private static volatile boolean high;
    /** When memory last looked short, by {@link System#nanoTime}. */
    private static long lastShort;
    private static final AtomicBoolean everShort = new AtomicBoolean();
    /** Set the first time memory runs short in this game, until {@link #firstShortage} takes it. */
    private static final AtomicBoolean untold = new AtomicBoolean();
    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile long usedMib, mostMib;

    private MemoryPressure() {}

    /** Whether memory is short now. */
    public static boolean high() { return high; }

    /** How many entries a cache that usually keeps {@code most} keeps now: a quarter while memory is short, at least two. */
    public static int scaled(int most) {
        return high ? Math.max(2, (int) (most * SHORT_SHARE)) : most;
    }

    /** True once, the first time memory runs short in this game: for telling the player. */
    public static boolean firstShortage() { return untold.compareAndSet(true, false); }

    /** The long-lived heap in use at the last look, in MiB. */
    public static long usedMib() { return usedMib; }

    /** The most memory Java may use, in MiB. */
    public static long mostMib() { return mostMib; }

    /** Starts watching, once per game; later calls do nothing. Never throws: without the means to look, memory never counts as short. */
    public static void watch(Consumer<String> log) {
        if (!started.compareAndSet(false, true)) return;
        try {
            Watcher.start(log);
        } catch (Throwable missing) {
            // A trimmed Java runtime without java.management: nothing to look at.
            log.accept("Earth Platform cannot watch memory here (" + missing + ")");
        }
    }

    /**
     * One look by the watcher thread: {@code used} of {@code most} bytes in the long-lived heap,
     * {@code above} looks in a row above {@link #HIGH}, and whether the heap had to be collected whole
     * since the last look. Sets {@link #high}; says when it changes, at most once a {@link #HOLD_NANOS}.
     */
    static synchronized void looked(long used, long most, int above, boolean wholeHeap, long now, Consumer<String> log) {
        usedMib = used >> 20;
        mostMib = most >> 20;
        double share = (double) used / most;
        boolean shortNow = wholeHeap || above >= LOOKS_HIGH;
        if (shortNow || (high && share >= LOW)) lastShort = now;
        if (!high && shortNow) {
            high = true;
            if (everShort.compareAndSet(false, true)) {
                untold.set(true);
                log.accept(String.format(Locale.ROOT, "Memory is short (%d of %d MB in long-lived use%s): Earth keeps less in memory and makes far terrain more slowly until it frees up",
                        usedMib, mostMib, wholeHeap ? ", after a whole-heap collection" : ""));
            }
        } else if (high && now - lastShort >= HOLD_NANOS) {
            high = false;
        }
    }

    /** For tests: back to a fresh game's state. */
    static synchronized void reset() {
        high = false;
        lastShort = 0;
        everShort.set(false);
        untold.set(false);
    }

    /** The part that needs java.management, kept apart so a runtime without it only goes unwatched. */
    static final class Watcher {
        private Watcher() {}

        static void start(Consumer<String> log) {
            List<MemoryPoolMXBean> pools = longLived(ManagementFactory.getMemoryPoolMXBeans());
            if (pools.isEmpty()) return;
            List<GarbageCollectorMXBean> whole = wholeHeap(ManagementFactory.getGarbageCollectorMXBeans());
            long most = Runtime.getRuntime().maxMemory();
            Thread watcher = new Thread(() -> {
                int above = 0;
                long wholeBefore = collections(whole);
                while (!Thread.currentThread().isInterrupted()) {
                    long used = 0;
                    for (MemoryPoolMXBean pool : pools) {
                        try { used += pool.getUsage().getUsed(); } catch (RuntimeException ignored) { /* a pool gone mid-look */ }
                    }
                    long wholeNow = collections(whole);
                    above = (double) used / most > HIGH ? above + 1 : 0;
                    looked(used, most, above, wholeNow > wholeBefore, System.nanoTime(), log);
                    wholeBefore = wholeNow;
                    try { Thread.sleep(LOOK_MS); } catch (InterruptedException stop) { return; }
                }
            }, "Earth memory watch");
            watcher.setDaemon(true);
            watcher.setPriority(Thread.MIN_PRIORITY);
            watcher.start();
        }

        /**
         * The heap pools that hold what lives on: all heap pools but the young ones (eden and survivor),
         * which fill and empty many times a second. For a collector with one pool for everything, that one.
         */
        static List<MemoryPoolMXBean> longLived(List<MemoryPoolMXBean> all) {
            List<MemoryPoolMXBean> heap = new ArrayList<>(), old = new ArrayList<>();
            for (MemoryPoolMXBean pool : all) {
                if (pool.getType() != MemoryType.HEAP) continue;
                heap.add(pool);
                String name = pool.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("eden") && !name.contains("survivor") && !name.contains("young") && !name.contains("nursery")) old.add(pool);
            }
            return old.isEmpty() ? heap : old;
        }

        /** The collectors that stop everything to collect the whole heap: G1's and Parallel's and Serial's full collections. */
        static List<GarbageCollectorMXBean> wholeHeap(List<GarbageCollectorMXBean> all) {
            List<GarbageCollectorMXBean> whole = new ArrayList<>();
            for (GarbageCollectorMXBean collector : all) {
                String name = collector.getName();
                if (name.equals("G1 Old Generation") || name.equals("PS MarkSweep") || name.equals("MarkSweepCompact")) whole.add(collector);
            }
            return whole;
        }

        private static long collections(List<GarbageCollectorMXBean> collectors) {
            long count = 0;
            for (GarbageCollectorMXBean collector : collectors) count += Math.max(0, collector.getCollectionCount());
            return count;
        }
    }
}
