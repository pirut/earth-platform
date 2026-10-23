package io.earthplatform.minecraft.cubic.far;

import io.earthplatform.minecraft.cubic.util.NamedThreads;
import io.earthplatform.minecraft.cubic.util.TickShare;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Low-priority threads that make far columns, as many at once as keep the processor busy. Columns
 * made from data already at hand use the processor all the time, and {@code busy} of them run at
 * once. Over a new place most of a column's time is spent waiting on downloads one after another,
 * and the processor sat idle: then more columns run at once, up to {@code most}, so their
 * downloads overlap. How much of the recent columns' time went on the processor decides how many.
 * Over Mount Shasta, six workers on four cores made a first visit's far terrain in 154 s where two
 * took 272 s; with the data at hand, more than the cores only slow each other.
 */
public final class FarWorkers {
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final boolean MEASURED = CPU.isCurrentThreadCpuTimeSupported();
    /** Least share of their time recent columns spent on the processor that is believed; below it, waits dominate anyway. */
    private static final double LEAST_SHARE = 0.2;
    /** How much each finished column's times count against the ones before. */
    private static final double DECAY = 0.9;

    private final int busy, most;
    private final ExecutorService threads;
    private final Object gate = new Object();
    private int running;
    private double cpu, wall;
    /** Set while cubes near players are waiting to be made: they come first, so no more than {@code busy} run. */
    private volatile boolean held;

    public FarWorkers(String name, int busy, int most) {
        this.busy = busy;
        this.most = Math.max(busy, most);
        threads = Executors.newFixedThreadPool(this.most, NamedThreads.numbered(name, Thread.MIN_PRIORITY));
    }

    /** How many columns may run at once now. */
    public int allowed() {
        synchronized (gate) {
            if (held || !MEASURED || wall <= 0) return busy;
            double share = Math.max(LEAST_SHARE, cpu / wall);
            return Math.clamp((int) Math.ceil(busy / share), busy, most);
        }
    }

    /** Holds the workers to {@code busy} while near cubes wait, so far downloads and work never hold them up. */
    public void hold(boolean held) {
        if (this.held == held) return;
        this.held = held;
        if (!held) synchronized (gate) { gate.notifyAll(); }
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
            long wallStart = System.nanoTime(), cpuStart = MEASURED ? CPU.getCurrentThreadCpuTime() : 0;
            try { job.run(); }
            finally {
                long spent = System.nanoTime() - wallStart, used = MEASURED ? CPU.getCurrentThreadCpuTime() - cpuStart : spent;
                synchronized (gate) {
                    running--;
                    cpu = cpu * DECAY + used;
                    wall = wall * DECAY + spent;
                    gate.notifyAll();
                }
            }
        });
    }

    public void shutdownNow() { threads.shutdownNow(); }
}
