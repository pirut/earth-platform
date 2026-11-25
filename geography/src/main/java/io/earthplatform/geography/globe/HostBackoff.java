package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared pauses and outage checks per data host. When a server replies 429 or 503 it wants fewer
 * requests, so every transfer to that host waits out the pause together; when a host stops
 * answering altogether (or fails across multiple distinct canonical paths with 5xx), every transfer
 * to it fails at once instead of tying up worker threads in timeouts, while a background check
 * watches for it to come back.
 */
public final class HostBackoff {
    /** How many slow-down replies (429 / 503) one transfer will wait out before giving up. */
    static final int ATTEMPTS = 5;
    /** Longest single pause honoured from a Retry-After header or doubling backoff. */
    static final long MAX_PAUSE_MS = 30_000;

    private static final Map<String, AtomicLong> UNTIL = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> LOGGED = new ConcurrentHashMap<>();

    /**
     * How many tries in a row must fail before a host counts as down, and how long that streak must
     * go on for: many transfers broken off together by one blip are retried at once and come back.
     * Timeouts count at once, as each has already waited, and one connection timeout is enough.
     * HTTP 5xx replies are tracked separately from transport errors and require
     * {@link #DOWN_AFTER_PATHS} distinct canonical failing paths so one broken tile URL cannot
     * blackhole every healthy tile on the same host or prime an unrelated connection reset.
     */
    static final int DOWN_AFTER = 3;
    static final int DOWN_AFTER_PATHS = 3;
    static final long DOWN_STREAK_MS = 1_000;
    /** How long a host stays down before it is checked again, doubling up to the longest. */
    static final long FIRST_DOWN_MS = 5_000, LONGEST_DOWN_MS = 60_000;
    private static final int MAX_TRACKED_PATHS = 64;
    private static final int MAX_PROBE_CANDIDATES = 16;
    private static final int MAX_PROBE_DRAIN_BYTES = 65_536;

    private record ProbeCandidate(String path, URI uri) {}

    private static final class Health {
        final AtomicLong failedTransport = new AtomicLong(), streakTransportStart = new AtomicLong();
        final AtomicLong failed5xx = new AtomicLong(), streak5xxStart = new AtomicLong();
        final Set<String> failingPaths = ConcurrentHashMap.newKeySet();
        final Set<String> recentBadPaths = ConcurrentHashMap.newKeySet();
        final CopyOnWriteArrayList<ProbeCandidate> probeCandidates = new CopyOnWriteArrayList<>();
        final Set<String> probeCandidatePaths = ConcurrentHashMap.newKeySet();
        final Set<String> triedProbePaths = ConcurrentHashMap.newKeySet();
        volatile ProbeCandidate lastHealthy;
        volatile long downUntil, downFor, downSince;
        final AtomicBoolean checking = new AtomicBoolean();
        volatile String what;
    }

    private static final Map<String, Health> HEALTH = new ConcurrentHashMap<>();
    static final Duration DEFAULT_CHECK_TIMEOUT = Duration.ofSeconds(20);
    private static volatile Duration checkTimeout = DEFAULT_CHECK_TIMEOUT;
    private static volatile java.net.http.HttpClient checker;
    private static final ScheduledExecutorService PROBE_TIMEOUTS = createProbeTimeoutScheduler();

    private static ScheduledExecutorService createProbeTimeoutScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, NamedThreads.daemon("earth-host-check-timeout"));
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private HostBackoff() {}

    /** Package-private for tests: overrides recovery probe timeout (pass {@code null} to restore default). */
    static void setCheckTimeoutForTest(Duration timeout) {
        checkTimeout = timeout == null ? DEFAULT_CHECK_TIMEOUT : timeout;
        checker = null;
    }

    static String hostKey(URI uri) {
        String authority = uri.getAuthority();
        return authority != null ? authority : String.valueOf(uri.getHost());
    }

    /** Canonical path key (query strings stripped and {@code .}/{@code ..} segments resolved). */
    static String pathKey(URI uri) {
        String path = uri.normalize().getPath();
        return path != null ? path : "";
    }

    private static boolean isHttp5xx(Object why) {
        return why instanceof String s && s.startsWith("HTTP 5");
    }

    private static void rememberProbeCandidate(Health health, String path, URI uri) {
        if (health.probeCandidatePaths.contains(path)) return;
        synchronized (health.probeCandidates) {
            if (health.probeCandidatePaths.contains(path)) return;
            if (health.probeCandidates.size() >= MAX_PROBE_CANDIDATES) {
                ProbeCandidate evicted = health.probeCandidates.remove(0);
                health.probeCandidatePaths.remove(evicted.path());
            }
            health.probeCandidates.add(new ProbeCandidate(path, uri));
            health.probeCandidatePaths.add(path);
        }
    }

    /**
     * Fails at once while the uri's host is down. When it is due another check, that is started off to
     * the side: no caller ever waits on a host that is down, the server thread included.
     */
    static void reachable(URI uri, String what) {
        Health health = HEALTH.get(hostKey(uri));
        if (health == null || health.downFor == 0) return;
        String path = pathKey(uri);
        rememberProbeCandidate(health, path, uri);
        if (System.currentTimeMillis() >= health.downUntil && health.checking.compareAndSet(false, true)) {
            check(chooseProbeUri(uri, path, health), health);
        }
        throw new MissingTerrainException(what + " server " + uri.getHost() + " can't be reached; trying it again shortly");
    }

    /**
     * Selects a recovery probe URI in stable canonical-path order: prefers the remembered healthy URI
     * first, then advances through untried canonical-path candidates in stable insertion order (adding
     * the current request only at the end if not already covered) so alternating requests can never pin
     * probing on one broken tile and skip a healthy candidate.
     */
    private static URI chooseProbeUri(URI requested, String reqPath, Health health) {
        List<ProbeCandidate> candidates = new ArrayList<>(health.probeCandidates.size() + 2);
        Set<String> seen = new HashSet<>();
        ProbeCandidate healthy = health.lastHealthy;
        if (healthy != null && seen.add(healthy.path())) {
            candidates.add(healthy);
        }
        for (ProbeCandidate candidate : health.probeCandidates) {
            if (!health.recentBadPaths.contains(candidate.path()) && seen.add(candidate.path())) {
                candidates.add(candidate);
            }
        }
        for (ProbeCandidate candidate : health.probeCandidates) {
            if (seen.add(candidate.path())) {
                candidates.add(candidate);
            }
        }
        if (seen.add(reqPath)) {
            candidates.add(new ProbeCandidate(reqPath, requested));
        }
        for (ProbeCandidate candidate : candidates) {
            if (health.triedProbePaths.add(candidate.path())) {
                return candidate.uri();
            }
        }
        // Full rotation completed: start a fresh round from index 0.
        health.triedProbePaths.clear();
        ProbeCandidate first = candidates.get(0);
        health.triedProbePaths.add(first.path());
        return first.uri();
    }

    /** Asks the host for the first byte of a healthy (or rotated) file with a hard body deadline; any non-5xx answer means it is back. */
    private static void check(URI uri, Health health) {
        Thread.ofVirtual().name("earth-host-check").start(() -> {
            boolean recovered = false;
            try {
                Duration timeout = checkTimeout;
                java.net.http.HttpClient http = checker;
                if (http == null) checker = http = java.net.http.HttpClient.newBuilder().connectTimeout(timeout)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri).timeout(timeout)
                        .header("User-Agent", "earth-platform/check").header("Range", "bytes=0-0").GET().build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                InputStream rawStream = response.body();
                AtomicBoolean bodyTimedOut = new AtomicBoolean();
                ScheduledFuture<?> bodyDeadline = PROBE_TIMEOUTS.schedule(() -> {
                    bodyTimedOut.set(true);
                    try { rawStream.close(); }
                    catch (IOException | RuntimeException ignored) { /* already closed */ }
                }, Math.max(1L, timeout.toNanos()), TimeUnit.NANOSECONDS);
                try (InputStream bodyStream = rawStream) {
                    int status = response.statusCode();
                    byte[] scratch = new byte[4096];
                    int remaining = MAX_PROBE_DRAIN_BYTES;
                    while (remaining > 0) {
                        int read = bodyStream.read(scratch, 0, Math.min(scratch.length, remaining));
                        if (read < 0) break;
                        remaining -= read;
                    }
                    if (bodyTimedOut.get()) {
                        throw new HttpTimeoutException("Recovery probe body timed out after " + timeout.toMillis() + " ms for " + uri);
                    }
                    if (status < 500 || slowDown(status)) {
                        answered(uri);
                        recovered = true;
                    }
                } finally {
                    bodyDeadline.cancel(false);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException failure) {
                // Still down.
            } finally {
                if (!recovered) {
                    health.downFor = Math.min(LONGEST_DOWN_MS, health.downFor * 2);
                    health.downUntil = System.currentTimeMillis() + health.downFor;
                }
                health.checking.set(false);
            }
        });
    }

    /** The host answered (any non-5xx HTTP status): it is up. */
    static void answered(URI uri) {
        Health health = HEALTH.computeIfAbsent(hostKey(uri), h -> new Health());
        String path = pathKey(uri);
        health.lastHealthy = new ProbeCandidate(path, uri);
        rememberProbeCandidate(health, path, uri);
        health.recentBadPaths.remove(path);
        health.failedTransport.set(0);
        health.streakTransportStart.set(0);
        health.failed5xx.set(0);
        health.streak5xxStart.set(0);
        health.failingPaths.clear();
        health.triedProbePaths.clear();
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
        Health health = HEALTH.computeIfAbsent(hostKey(uri), h -> new Health());
        health.what = what;
        String path = pathKey(uri);
        rememberProbeCandidate(health, path, uri);
        long now = System.currentTimeMillis();
        // Already down: transfers started before it went down still report in, and change nothing.
        if (health.downFor != 0) return;
        if (isHttp5xx(why)) {
            if (health.recentBadPaths.size() >= MAX_TRACKED_PATHS) health.recentBadPaths.clear();
            health.recentBadPaths.add(path);
            if (health.failingPaths.size() < MAX_TRACKED_PATHS) health.failingPaths.add(path);
            long streak = health.streak5xxStart.updateAndGet(start -> start == 0 ? now : start);
            long count = health.failed5xx.incrementAndGet();
            if (health.failingPaths.size() < DOWN_AFTER_PATHS) return;
            if (count < DOWN_AFTER) return;
            if (now - streak < DOWN_STREAK_MS) return;
        } else {
            // Many transfers can fail together: the streak starts with whichever of them reports first.
            long streak = health.streakTransportStart.updateAndGet(start -> start == 0 ? now : start);
            // A connection that could not even be made in the whole timeout is down already: no need for more tries.
            boolean connectTimeout = why instanceof java.net.http.HttpConnectTimeoutException;
            long count = health.failedTransport.incrementAndGet();
            if (count < DOWN_AFTER && !connectTimeout) return;
            if (now - streak < DOWN_STREAK_MS && !(why instanceof HttpTimeoutException)) return;
        }
        synchronized (health) {
            if (health.downFor != 0) return;
            health.triedProbePaths.clear();
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

    /** Whether {@code uri}'s host is currently marked down; package-private for tests. */
    static boolean isDown(URI uri) {
        Health health = HEALTH.get(hostKey(uri));
        return health != null && health.downFor != 0;
    }

    /** Package-private for tests: immediately makes the next recovery probe due. */
    static void expireBackoffForTest(URI uri) {
        Health health = HEALTH.get(hostKey(uri));
        if (health != null) health.downUntil = 0L;
    }

    /** Package-private for tests: clears host state for {@code uri}'s authority. */
    static void clear(URI uri) {
        String key = hostKey(uri);
        HEALTH.remove(key);
        UNTIL.remove(key);
        LOGGED.remove(key);
    }

    static boolean slowDown(int status) { return status == 429 || status == 503; }

    /** Waits until the uri's host is no longer paused. */
    static void await(URI uri) throws InterruptedException {
        AtomicLong until = UNTIL.get(hostKey(uri));
        if (until == null) return;
        for (long wait; (wait = until.get() - System.currentTimeMillis()) > 0; ) Thread.sleep(Math.min(wait, MAX_PAUSE_MS));
    }

    /** Pauses the host after a slow-down reply: its Retry-After seconds when given, else 1 s doubling per attempt. */
    static void pause(URI uri, HttpResponse<?> response, int attempt, String what) {
        long ms = pauseMillis(response.headers().firstValue("Retry-After").orElse(null), attempt);
        String key = hostKey(uri);
        String host = String.valueOf(uri.getHost());
        long until = System.currentTimeMillis() + ms;
        UNTIL.computeIfAbsent(key, h -> new AtomicLong()).accumulateAndGet(until, Math::max);
        AtomicLong logged = LOGGED.computeIfAbsent(key, h -> new AtomicLong());
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
