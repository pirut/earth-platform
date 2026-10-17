package io.earthplatform.geography.globe;

import java.util.concurrent.locks.LockSupport;

/**
 * Background map work giving the processor back to a server that falls behind. Decoding tiles,
 * working out bare ground and making far columns can keep every core busy over a new place, and
 * on a server with few cores (or sharing them with players' games) its tick then got so little
 * time that it fell a minute behind, when the watchdog stops the server. While the tick is late,
 * heavy pieces of work wait a moment before they start, so the tick catches up; the wait is short
 * and bounded, so work the tick itself waits on still arrives.
 */
public final class TickShare {
    /** How long a late tick holds work back for, unless the next tick is late too. */
    private static final long LATE_NANOS = 400_000_000L;
    /** The longest one piece of work waits, so nothing is held back for good. */
    private static final long MOST_WAIT_NANOS = 250_000_000L;
    private static final long STEP_NANOS = 10_000_000L;

    private static volatile long lateUntil;
    private static volatile Thread tick;

    private TickShare() {}

    /** The server thread, each tick it is running behind its schedule. */
    public static void late() {
        tick = Thread.currentThread();
        lateUntil = System.nanoTime() + LATE_NANOS;
    }

    /** Whether background work is being held back now. */
    public static boolean holding() {
        return lateUntil - System.nanoTime() > 0;
    }

    /**
     * Called before a heavy piece of background work starts, holding nothing others wait on: waits
     * while the server's tick is late, up to a quarter of a second. The tick's own thread never waits.
     */
    public static void giveWay() {
        if (!holding() || Thread.currentThread() == tick) return;
        long end = System.nanoTime() + MOST_WAIT_NANOS;
        while (holding() && end - System.nanoTime() > 0) {
            LockSupport.parkNanos(STEP_NANOS);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
