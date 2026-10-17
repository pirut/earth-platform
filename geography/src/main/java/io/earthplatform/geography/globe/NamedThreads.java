package io.earthplatform.geography.globe;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread factories for the mod's background pools: every thread is a daemon, so a pool never keeps
 * the game from exiting, and carries a readable name for profilers and crash reports. Used by the
 * geography downloads and by the cubic world's workers, meshers, writers and web map in the
 * {@code minecraft} module. A thread whose priority is not given keeps the priority it inherits
 * from the thread that creates it, as a plain {@code new Thread} does.
 */
public final class NamedThreads {
    private NamedThreads() {}

    /** Daemon threads all called {@code name}, at their inherited priority. */
    public static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Daemon threads all called {@code name}, at {@code priority}. */
    public static ThreadFactory daemon(String name, int priority) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        };
    }

    /** Daemon threads called {@code prefix} followed by a space and 1, 2, 3 and so on, at {@code priority}. */
    public static ThreadFactory numbered(String prefix, int priority) {
        return numbered(prefix, new AtomicInteger(), priority);
    }

    /**
     * Like {@link #numbered(String, int)}, but counting with {@code counter}, so pools made one after
     * another (one per world, say) carry on the numbering instead of starting again at 1.
     */
    public static ThreadFactory numbered(String prefix, AtomicInteger counter, int priority) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + " " + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        };
    }
}
