package io.earthplatform.geography.globe;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A shared pause for a data host that asked us to slow down (HTTP 429 or 503). Every transfer to that
 * host waits it out, so a busy server backs off as one instead of each worker retrying at once.
 */
final class HostBackoff {
    /** Slow-down replies to wait out before a piece counts as failed. */
    static final int ATTEMPTS = 5;
    /** The longest single pause, whatever the host's Retry-After says. */
    static final long MAX_PAUSE_MS = 30_000;

    private static final Map<String, AtomicLong> UNTIL = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> LOGGED = new ConcurrentHashMap<>();

    private HostBackoff() {}

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
