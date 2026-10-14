package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A shared pause for a data host that asked us to slow down (HTTP 429 or 503). Every transfer to that
 * host waits it out, so a busy server backs off as one instead of each worker retrying at once.
 *
 * <p>Also the shared word on a host that can't be reached at all: refused, timed out or failing on
 * every try. Once a host is down its transfers fail at once rather than each waiting out its own
 * timeouts, so a host that hangs can't tie up every worker, and one transfer at a time checks
 * whether it is back, a little less often the longer it stays down.
 */
public final class HostBackoff {
    /** Slow-down replies to wait out before a piece counts as failed. */
    static final int ATTEMPTS = 5;
    /** The longest single pause, whatever the host's Retry-After says. */
    static final long MAX_PAUSE_MS = 30_000;

    private static final Map<String, AtomicLong> UNTIL = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> LOGGED = new ConcurrentHashMap<>();

    /**
     * Failed tries in a row, with no answer between them, that mark a host down, and how long they must
     * go on for: many transfers broken off together by one blip are retried at once and come back.
     * Timeouts count at once, as each has already waited, and one connection timeout is enough.
     */
    static final int DOWN_AFTER = 3;
    static final long DOWN_STREAK_MS = 1_000;
    /** How long a host stays down before one transfer checks it again, doubling up to the longest. */
    static final long FIRST_DOWN_MS = 5_000, LONGEST_DOWN_MS = 60_000, CHECK_GIVES_WAY_MS = 180_000;

    private static final class Health {
        final AtomicLong failed = new AtomicLong(), streakStart = new AtomicLong();
        volatile long downUntil, downFor;
        /** When the transfer checking a down host started, 0 for none. */
        final AtomicLong checking = new AtomicLong();
        volatile String what;
        volatile long downSince;
    }

    private static final Map<String, Health> HEALTH = new ConcurrentHashMap<>();

    private HostBackoff() {}

    /**
     * Fails at once while the uri's host is down, unless this transfer is the one to check whether
     * it is back; that one goes ahead, and {@link #answered} or {@link #failed} says how it went.
     *
     * @return whether this transfer is that check
     */
    static boolean reachable(URI uri, String what) {
        Health health = HEALTH.get(String.valueOf(uri.getAuthority()));
        if (health == null || health.downFor == 0) return false;
        long now = System.currentTimeMillis();
        long since = health.checking.get();
        // A check that never reported back (its thread stopped) gives way after a while.
        if (now >= health.downUntil && (since == 0 || now - since > CHECK_GIVES_WAY_MS) && health.checking.compareAndSet(since, now)) return true;
        throw new MissingTerrainException(what + " server " + uri.getHost() + " can't be reached; trying it again shortly");
    }

    /** The host answered (any HTTP status): it is up. */
    static void answered(URI uri) {
        Health health = HEALTH.get(String.valueOf(uri.getAuthority()));
        if (health == null) return;
        health.failed.set(0);
        health.streakStart.set(0);
        health.checking.set(0);
        if (health.downFor != 0) {
            long seconds = (System.currentTimeMillis() - health.downSince) / 1000;
            health.downFor = 0;
            System.getLogger("earthplatform").log(System.Logger.Level.INFO, "{0} host {1} is reachable again after {2} s; downloads resume",
                    health.what, uri.getHost(), seconds);
        }
    }

    /** A try failed without an answer (refused, timed out, broken off) or with a server error. */
    static void failed(URI uri, String what, Object why) {
        String host = String.valueOf(uri.getHost());
        Health health = HEALTH.computeIfAbsent(String.valueOf(uri.getAuthority()), h -> new Health());
        health.what = what;
        long now = System.currentTimeMillis();
        if (health.downFor != 0) {
            // The check failed, or a transfer started before the host went down did: wait longer.
            if (health.checking.getAndSet(0) != 0) {
                health.downFor = Math.min(LONGEST_DOWN_MS, health.downFor * 2);
                health.downUntil = now + health.downFor;
            }
            return;
        }
        // Many transfers can fail together: the streak starts with whichever of them reports first.
        long streak = health.streakStart.updateAndGet(start -> start == 0 ? now : start);
        // A connection that could not even be made in the whole timeout is down already: no need for more tries.
        boolean connectTimeout = why instanceof java.net.http.HttpConnectTimeoutException;
        if (health.failed.incrementAndGet() < DOWN_AFTER && !connectTimeout) return;
        if (now - streak < DOWN_STREAK_MS && !(why instanceof java.net.http.HttpTimeoutException)) return;
        synchronized (health) {
            if (health.downFor != 0) return;
            health.downSince = now;
            health.downFor = FIRST_DOWN_MS;
            health.downUntil = now + FIRST_DOWN_MS;
        }
        System.getLogger("earthplatform").log(System.Logger.Level.WARNING,
                "{0} host {1} is not answering ({2}); places that need it wait until it is back, checked every few seconds to a minute",
                what, host, why);
    }

    /** Whether any data host is down now. */
    public static boolean anyDown() {
        for (Health health : HEALTH.values()) if (health.downFor != 0) return true;
        return false;
    }

    static boolean slowDown(int status) { return status == 429 || status == 503; }

    /** Waits until the uri's host is no longer paused. */
    static void await(URI uri) throws InterruptedException {
        AtomicLong until = UNTIL.get(String.valueOf(uri.getHost()));
        if (until == null) return;
        for (long wait; (wait = until.get() - System.currentTimeMillis()) > 0; ) Thread.sleep(Math.min(wait, MAX_PAUSE_MS));
    }

    /** Pauses the host after a slow-down reply: its Retry-After seconds when given, else 1 s doubling per attempt. */
    static void pause(URI uri, HttpResponse<?> response, int attempt, String what) {
        long ms = pauseMillis(response.headers().firstValue("Retry-After").orElse(null), attempt);
        String host = String.valueOf(uri.getHost());
        long until = System.currentTimeMillis() + ms;
        UNTIL.computeIfAbsent(host, h -> new AtomicLong()).accumulateAndGet(until, Math::max);
        AtomicLong logged = LOGGED.computeIfAbsent(host, h -> new AtomicLong());
        long now = System.currentTimeMillis(), last = logged.get();
        if (now - last > 60_000 && logged.compareAndSet(last, now))
            System.getLogger("earthplatform").log(System.Logger.Level.WARNING, "{0} host {1} asked us to slow down (HTTP {2}); pausing downloads from it for {3} s",
                    what, host, response.statusCode(), (ms + 999) / 1000);
    }

    static long pauseMillis(String retryAfter, int attempt) {
        if (retryAfter != null) {
            try { return Math.max(1_000, Math.min(MAX_PAUSE_MS, Long.parseLong(retryAfter.trim()) * 1000)); }
            catch (NumberFormatException httpDate) { /* a date form: fall back to doubling */ }
        }
        return Math.min(MAX_PAUSE_MS, 1_000L << Math.max(0, Math.min(attempt - 1, 5)));
    }
}
