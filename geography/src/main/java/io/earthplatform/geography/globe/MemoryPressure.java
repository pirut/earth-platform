package io.earthplatform.geography.globe;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * Whether Java is close to running out of memory, so Earth's caches and background work can make
 * room before the game dies. Earth sizes its caches and its far terrain threads by the memory and
 * processors it is given, as if it had them to itself; in a big mod pack the other mods hold much
 * of that memory, and arriving somewhere new (dozens of megabytes of map data decoded at once on
 * every core) then filled a 4 GB game until it exited with no crash report.
 *
 * <p>Two signals, because a heap that is about to collect looks full and a heap that is still full
 * after it collected does not. {@link #tight()} is the first: less than {@link #reserve(long)} free,
 * counting garbage not yet collected. New downloads, decodes and far columns wait while it is, so a
 * burst spends that reserve on what is already in flight instead of starting more. It is not by
 * itself a reason to forget cached map data; a young collection often clears it.
 *
 * <p>The second is {@link #high()}: the long-lived heap staying above {@link #HIGH}, a collection of
 * the whole heap, or a collection that left less than the reserve free. Then the globe's caches keep
 * a quarter of their entries (see {@link #scaled}) and far terrain makes fewer columns at once, and
 * they are trimmed then rather than whenever they next gain an entry. Memory counts as plentiful
 * again only once the long-lived part is under {@link #LOW} and has not been short for
 * {@link #HOLD_NANOS}.
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
    /**
     * Heavy pieces of work (a far column, a tile decode) that may run at once while {@link #tight()}.
     * Enough for a task and the child task it is waiting on, not for every core at once.
     */
    static final int HEAVY_WHEN_TIGHT = 4;
    /** After this, one more heavy piece starts every {@link #DRIP_NANOS} so a stuck reading cannot stall travel. */
    private static final long HEAVY_WAIT_NANOS = 5_000_000_000L;
    private static final long DRIP_NANOS = 200_000_000L;
    /** How long the heap may stay {@link #tight()} with no collection before that itself counts as short. */
    static final long TIGHT_WITHOUT_GC_NANOS = 2_000_000_000L;

    private static volatile boolean high;
    /** When memory last looked short, by {@link System#nanoTime}. */
    private static long lastShort;
    private static final AtomicBoolean everShort = new AtomicBoolean();
    /** Set the first time memory runs short in this game, until {@link #firstShortage} takes it. */
    private static final AtomicBoolean untold = new AtomicBoolean();
    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile long usedMib, mostMib;
    /** Test stand-in for the live heap; negative means the real one. Cleared by {@link #reset}. */
    private static volatile long overrideUsed = -1, overrideMax = -1;
    private static volatile boolean testWatching;
    private static final AtomicInteger heavy = new AtomicInteger();
    private static final AtomicLong nextDrip = new AtomicLong();
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final List<WeakReference<Hook>> hooks = new ArrayList<>();

    private MemoryPressure() {}

    /**
     * A cache's trim, kept alive by the cache (this is held weakly here). {@link #keep} returns it
     * so the cache can store it; once the cache is gone the hook is dropped.
     */
    public static final class Hook {
        private final Runnable action;
        private Hook(Runnable action) { this.action = action; }
        void run() { action.run(); }
    }

    /** Remembers {@code shed} until the returned hook is itself forgotten. */
    public static Hook keep(Runnable shed) {
        Hook hook = new Hook(shed);
        synchronized (hooks) { hooks.add(new WeakReference<>(hook)); }
        return hook;
    }

    /** Whether memory is short now: caches keep a quarter and far terrain slows until it frees up. */
    public static boolean high() { return high; }

    /**
     * Whether the heap has less than {@link #reserve(long)} free, including garbage a collection has
     * not taken yet. False until {@link #watch} (or a test) is looking, so unit tests with a small
     * heap are left alone.
     */
    public static boolean tight() {
        if (!watching()) return false;
        long max = maxBytes();
        return max - usedBytes() < reserve(max);
    }

    /** Bytes kept free for work already in flight. At least 512 MiB, and an eighth of the heap when that is more. */
    static long reserve(long max) { return Math.max(512L << 20, max / 8); }

    /** Whether a collection that left {@code used} of {@code max} bytes live counts as short. */
    static boolean liveSetShort(long used, long max) { return max > 0 && max - used < reserve(max); }

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

    /**
     * Runs {@code work} as one heavy piece: while the heap is {@link #tight()}, only
     * {@link #HEAVY_WHEN_TIGHT} such pieces run at once. Nested calls on the same thread count as the
     * outer one, so a column and the tile it decodes do not wait on each other.
     */
    public static void runHeavy(Runnable work) {
        enterHeavy();
        try { work.run(); }
        finally { exitHeavy(); }
    }

    /** See {@link #runHeavy}. */
    public static void enterHeavy() {
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);
        if (depth > 0) return;
        try {
            long start = System.nanoTime();
            while (true) {
                if (!tight()) { heavy.incrementAndGet(); return; }
                int running = heavy.get();
                if (running < HEAVY_WHEN_TIGHT && heavy.compareAndSet(running, running + 1)) return;
                if (System.nanoTime() - start >= HEAVY_WAIT_NANOS && drip()) return;
                Thread.sleep(20L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            heavy.incrementAndGet();
        }
    }

    /** See {@link #runHeavy}. */
    public static void exitHeavy() {
        int depth = DEPTH.get();
        if (depth > 1) { DEPTH.set(depth - 1); return; }
        if (depth == 1) {
            DEPTH.set(0);
            heavy.decrementAndGet();
        }
    }

    /** One extra heavy piece, no more than five a second, once {@link #enterHeavy} has waited out its pause. */
    private static boolean drip() {
        long now = System.nanoTime();
        long next = nextDrip.get();
        if (now < next || !nextDrip.compareAndSet(next, now + DRIP_NANOS)) return false;
        heavy.incrementAndGet();
        return true;
    }

    /** Starts watching, once per game; later calls do nothing. Never throws: without the means to look, memory never counts as short. */
    public static void watch(Consumer<String> log) {
        if (!started.compareAndSet(false, true)) return;
        try {
            Watcher.start(log);
        } catch (Throwable missing) {
            started.set(false);
            // A trimmed Java runtime without java.management: nothing to look at.
            log.accept("Earth Platform cannot watch memory here (" + missing + ")");
        }
    }

    /**
     * One look by the watcher thread: {@code used} of {@code most} bytes in the long-lived heap,
     * {@code above} looks in a row above {@link #HIGH}, whether the heap had to be collected whole
     * since the last look, and whether a collection left the live set short (or the heap has been
     * {@link #tight()} with no collection for a while). Sets {@link #high} and trims caches when it
     * becomes short. Says when it changes, at most once a {@link #HOLD_NANOS}.
     */
    static void looked(long used, long most, int above, boolean wholeHeap, boolean sustainedTight, long now, Consumer<String> log) {
        boolean shed = false;
        synchronized (MemoryPressure.class) {
            usedMib = used >> 20;
            mostMib = most >> 20;
            double share = most <= 0 ? 0 : (double) used / most;
            boolean shortNow = wholeHeap || above >= LOOKS_HIGH || sustainedTight;
            if (shortNow || (high && share >= LOW)) lastShort = now;
            if (!high && shortNow) {
                high = true;
                shed = true;
                if (everShort.compareAndSet(false, true)) {
                    untold.set(true);
                    boolean liveSet = sustainedTight && !wholeHeap && above < LOOKS_HIGH;
                    String why = wholeHeap ? ", after a whole-heap collection" : liveSet ? ", with little heap free" : "";
                    String kind = liveSet ? "in use" : "in long-lived use";
                    log.accept(String.format(Locale.ROOT, "Memory is short (%d of %d MB %s%s): Earth keeps less in memory and makes far terrain more slowly until it frees up",
                            usedMib, mostMib, kind, why));
                }
            } else if (high && !shortNow && share < LOW && now - lastShort >= HOLD_NANOS) {
                high = false;
            }
        }
        if (shed) shrinkNow();
    }

    /** Trims every cache that {@link #keep}s a hook. Safe from any thread; each cache takes its own lock. */
    static void shrinkNow() {
        List<Hook> live = new ArrayList<>();
        synchronized (hooks) {
            for (Iterator<WeakReference<Hook>> iterator = hooks.iterator(); iterator.hasNext(); ) {
                Hook hook = iterator.next().get();
                if (hook == null) iterator.remove();
                else live.add(hook);
            }
        }
        for (Hook hook : live) {
            try { hook.run(); }
            catch (RuntimeException ignored) { /* a cache mid-close; the next look tries again */ }
        }
    }

    /** For tests: back to a fresh game's state. Does not stop a watcher {@link #watch} already started. */
    static synchronized void reset() {
        high = false;
        lastShort = 0;
        everShort.set(false);
        untold.set(false);
        overrideUsed = -1;
        overrideMax = -1;
        testWatching = false;
        heavy.set(0);
        nextDrip.set(0);
        synchronized (hooks) { hooks.clear(); }
    }

    /** For tests: pretend the heap has {@code used} of {@code max} bytes live, and that watching is on. */
    static void simulate(long used, long max) {
        overrideUsed = used;
        overrideMax = max;
        testWatching = true;
    }

    private static boolean watching() { return started.get() || testWatching; }

    private static long maxBytes() { return overrideMax >= 0 ? overrideMax : Runtime.getRuntime().maxMemory(); }

    private static long usedBytes() {
        if (overrideUsed >= 0) return overrideUsed;
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0, runtime.totalMemory() - runtime.freeMemory());
    }

    /**
     * Whether {@code tight} has lasted through a collection, or for {@link #TIGHT_WITHOUT_GC_NANOS}
     * with none. The first look only remembers; a young collection's pause is not yet a short heap.
     */
    static final class TightSpan {
        private long collectionsAtStart = -1;
        private long since;

        boolean update(boolean tight, long collections, long now) {
            if (!tight) { collectionsAtStart = -1; return false; }
            if (collectionsAtStart < 0) { collectionsAtStart = collections; since = now; return false; }
            return collections > collectionsAtStart || now - since >= TIGHT_WITHOUT_GC_NANOS;
        }
    }

    /** The part that needs java.management, kept apart so a runtime without it only goes unwatched. */
    static final class Watcher {
        private Watcher() {}

        static void start(Consumer<String> log) {
            List<MemoryPoolMXBean> pools = longLived(ManagementFactory.getMemoryPoolMXBeans());
            if (pools.isEmpty()) return;
            List<MemoryPoolMXBean> heap = heap(ManagementFactory.getMemoryPoolMXBeans());
            List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
            List<GarbageCollectorMXBean> whole = wholeHeap(collectors);
            long most = Runtime.getRuntime().maxMemory();
            listen(collectors, heap, most, log);
            Thread watcher = new Thread(() -> {
                int above = 0;
                long wholeBefore = collections(whole);
                TightSpan span = new TightSpan();
                while (!Thread.currentThread().isInterrupted()) {
                    long used = 0;
                    for (MemoryPoolMXBean pool : pools) {
                        try { used += pool.getUsage().getUsed(); } catch (RuntimeException ignored) { /* a pool gone mid-look */ }
                    }
                    long wholeNow = collections(whole);
                    above = (double) used / most > HIGH ? above + 1 : 0;
                    long now = System.nanoTime();
                    boolean sustained = span.update(tight(), collections(collectors), now);
                    // Old-gen occupancy for the 80% mark; the whole heap once that is what ran short.
                    long reportedUsed = sustained ? usedBytes() : used;
                    long reportedMax = sustained ? maxBytes() : most;
                    looked(reportedUsed, reportedMax, above, wholeNow > wholeBefore, sustained, now, log);
                    wholeBefore = wholeNow;
                    try { Thread.sleep(LOOK_MS); } catch (InterruptedException stop) { return; }
                }
            }, "Earth memory watch");
            watcher.setDaemon(true);
            watcher.setPriority(Thread.MIN_PRIORITY);
            watcher.start();
        }

        /**
         * After each collection, on a HotSpot JVM, looks at the heap it left. A young collection that
         * freed the reserve does nothing; one that left the live set inside the reserve counts as short
         * at once, which a poll every half second can miss by landing in the next eden peak.
         * The com.sun class is loaded by name so a Java release compile, which cannot see it, still builds.
         */
        @SuppressWarnings("unchecked")
        private static void listen(List<GarbageCollectorMXBean> collectors, List<MemoryPoolMXBean> heap, long most, Consumer<String> log) {
            try {
                Class<?> type = Class.forName("com.sun.management.GarbageCollectionNotificationInfo");
                var from = type.getMethod("from", CompositeData.class);
                var getGcInfo = type.getMethod("getGcInfo");
                var getAfter = Class.forName("com.sun.management.GcInfo").getMethod("getMemoryUsageAfterGc");
                String note = (String) type.getField("GARBAGE_COLLECTION_NOTIFICATION").get(null);
                for (GarbageCollectorMXBean collector : collectors) {
                    if (!(collector instanceof NotificationEmitter emitter)) continue;
                    emitter.addNotificationListener((notification, handback) -> {
                        if (!note.equals(notification.getType())) return;
                        try {
                            Object info = from.invoke(null, notification.getUserData());
                            Object gcInfo = getGcInfo.invoke(info);
                            @SuppressWarnings("unchecked")
                            Map<String, MemoryUsage> after = (Map<String, MemoryUsage>) getAfter.invoke(gcInfo);
                            long used = 0;
                            for (MemoryPoolMXBean pool : heap) {
                                MemoryUsage usage = after.get(pool.getName());
                                if (usage != null) used += usage.getUsed();
                            }
                            if (liveSetShort(used, most)) looked(used, most, 0, false, true, System.nanoTime(), log);
                        } catch (Throwable ignored) { /* one odd notification; the poll still looks */ }
                    }, null, null);
                }
            } catch (Throwable missing) {
                // No HotSpot notification: the poll's two-second backstop is the whole of this signal.
            }
        }

        private static List<MemoryPoolMXBean> heap(List<MemoryPoolMXBean> all) {
            List<MemoryPoolMXBean> heap = new ArrayList<>();
            for (MemoryPoolMXBean pool : all) if (pool.getType() == MemoryType.HEAP) heap.add(pool);
            return heap;
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
