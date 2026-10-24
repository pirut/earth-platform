package io.earthplatform.geography.globe;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Threads waiting on map data: on a download of their own, or on another thread's load of the
 * same piece. A pool making terrain from the data can be told when each of its threads starts and
 * stops waiting, and start other work in its place: over a new place most of a far column's time
 * went on downloads one after another, while the processor sat idle. Waits on memory or the disk
 * are not told, nor a {@link TickShare} pause, which is meant to leave the processor alone.
 */
public final class DataWaits {
    /** Told when its thread starts waiting on data and when it stops; never twice the same in a row. */
    public interface Listener { void waiting(boolean waiting); }

    private static final class Watch {
        final Listener listener;
        int depth;
        Watch(Listener listener) { this.listener = listener; }
    }

    private static final ThreadLocal<Watch> WATCH = new ThreadLocal<>();

    private DataWaits() {}

    /** Tells {@code listener} whenever the calling thread waits on data from now on; null stops telling. */
    public static void watch(Listener listener) {
        WATCH.set(listener == null ? null : new Watch(listener));
    }

    /** Before a wait on the network or on another thread's load; always followed by {@link #after}. */
    static void before() {
        Watch watch = WATCH.get();
        if (watch != null && watch.depth++ == 0) watch.listener.waiting(true);
    }

    static void after() {
        Watch watch = WATCH.get();
        if (watch != null && watch.depth > 0 && --watch.depth == 0) watch.listener.waiting(false);
    }

    /** {@link Future#get()}, told as a wait when the piece is not in yet. */
    static <T> T get(Future<T> future) throws InterruptedException, ExecutionException {
        if (future.isDone()) return future.get();
        before();
        try { return future.get(); }
        finally { after(); }
    }

    /** {@link Future#get(long, TimeUnit)}, told as a wait when the piece is not in yet. */
    static <T> T get(Future<T> future, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (future.isDone()) return future.get();
        before();
        try { return future.get(timeout, unit); }
        finally { after(); }
    }

    /** {@link CompletableFuture#join()}, told as a wait when the piece is not in yet. */
    static <T> T join(CompletableFuture<T> future) {
        if (future.isDone()) return future.join();
        before();
        try { return future.join(); }
        finally { after(); }
    }
}
