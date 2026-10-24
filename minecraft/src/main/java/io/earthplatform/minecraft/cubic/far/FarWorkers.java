package io.earthplatform.minecraft.cubic.far;

import io.earthplatform.geography.globe.DataWaits;
import io.earthplatform.geography.globe.NamedThreads;
import io.earthplatform.geography.globe.TickShare;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Low-priority threads that make far columns, as many at once as keep the processor busy. Columns
 * made from data already at hand use the processor all the time, and {@code busy} of them run at
 * once. Over a new place most of a column's time went on downloads one after another, while the
 * processor sat idle: each column waiting on map data (see {@link DataWaits}) lets another start
 * in its place, up to {@code most} at once, so {@code busy} stay at work while the others'
 * downloads overlap, and columns whose data came in take turns at the processor again. Over Mount
 * Shasta on four cores, a first visit's far terrain took 58 s instead of 292 s (with the far
 * levels' climate fetched at once by {@code EarthGenerator.prepareFar}), and 41 s with the data on disk.
 */
public final class FarWorkers {
    /** Longest a column back from a wait on data waits for its turn at the processor: it may hold what those at work need. */
    private static final long TURN_NANOS = 250_000_000L;

    private final int busy, most;
    private final ExecutorService threads;
    private final Object gate = new Object();
    /** Columns being made; how many of them wait on map data, or with it in for their turn at the processor again. */
    private int running, waiting, turns;
    /** Set while cubes near players are waiting to be made: they come first, so no more than {@code busy} run. */
    private volatile boolean held;

    public FarWorkers(String name, int busy, int most) {
        this.busy = busy;
        this.most = Math.max(busy, most);
        ThreadFactory named = NamedThreads.numbered(name, Thread.MIN_PRIORITY);
        threads = Executors.newFixedThreadPool(this.most, job -> named.newThread(() -> {
            DataWaits.watch(this::waiting);
            job.run();
        }));
    }

    /**
     * How many columns may run at once now: {@code busy} at work and as many more as wait on data, up
     * to {@code most}. Columns whose data came in go back to work before any new one starts.
     */
    public int allowed() {
        synchronized (gate) {
            return held ? busy : Math.min(most, busy + waiting - turns);
        }
    }

    /** Holds the workers to {@code busy} while near cubes wait, so far downloads and work never hold them up. */
    public void hold(boolean held) {
        if (this.held == held) return;
        this.held = held;
        if (!held) synchronized (gate) { gate.notifyAll(); }
    }

    private void waiting(boolean now) {
        synchronized (gate) {
            if (now) {
                waiting++;
                gate.notifyAll();
                return;
            }
            // Back to work only while fewer than busy are, as a new column would start: a burst of columns whose
            // data came in together, or the many waiting while near cubes are made first, never crowds the processor.
            turns++;
            try {
                long until = System.nanoTime() + TURN_NANOS;
                for (long left; running - waiting >= busy && (left = until - System.nanoTime()) > 0; )
                    gate.wait(Math.max(1, left / 1_000_000));
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } finally {
                turns--;
                waiting--;
            }
        }
    }

    public void execute(Runnable job) {
        threads.execute(() -> {
            // Before taking a place, so a late server tick is not kept waiting by far terrain.
            TickShare.giveWay();
            synchronized (gate) {
                try {
                    while (running >= allowed()) gate.wait();
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
                running++;
            }
            try { job.run(); }
            finally {
                synchronized (gate) {
                    running--;
                    gate.notifyAll();
                }
            }
        });
    }

    public void shutdownNow() { threads.shutdownNow(); }
}
