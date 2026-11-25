package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP range reads for the globe's streamed rasters: bounded in concurrency, retried, shared by
 * every caller waiting on the same piece (single flight). Their disk caches are written through
 * {@link DiskCache#write(java.nio.file.Path, byte[])}.
 */
final class RangeFiles implements AutoCloseable {
    interface Load<T> { T get() throws IOException, InterruptedException; }

    /** Distinguishes confirmed missing (404) from forbidden (403) so callers never poison disk caches on transient 403s. */
    enum FetchStatus { OK, NOT_FOUND, FORBIDDEN }

    record FetchResult(FetchStatus status, byte[] bytes) {
        static final FetchResult NOT_FOUND = new FetchResult(FetchStatus.NOT_FOUND, null);
        static final FetchResult FORBIDDEN = new FetchResult(FetchStatus.FORBIDDEN, null);

        static FetchResult ok(byte[] bytes) { return new FetchResult(FetchStatus.OK, bytes); }
        boolean isOk() { return status == FetchStatus.OK; }
        boolean isNotFound() { return status == FetchStatus.NOT_FOUND; }
        boolean isForbidden() { return status == FetchStatus.FORBIDDEN; }
    }

    /** Hard cap on full-body HTTP 200 fallback responses when a server ignores {@code Range}. */
    static final long MAX_FALLBACK_BYTES = 32L << 20;
    /** Alias for {@link #MAX_FALLBACK_BYTES}. */
    static final long MAX_UNRANGED_BODY_BYTES = MAX_FALLBACK_BYTES;
    /** Maximum error body bytes drained to keep HTTP/1.1 connections reusable across 403/404/5xx responses. */
    private static final int MAX_ERROR_DRAIN_BYTES = 65_536;

    /** Versioned marker written to {@code .none} disk cache files on confirmed HTTP 404 responses. */
    static final byte[] CONFIRMED_NOT_FOUND_MARKER = new byte[] {'4', '0', '4', '\n'};

    /** Daemon timer enforcing hard body-read deadlines on {@link HttpResponse.BodyHandlers#ofInputStream()}. */
    private static final ScheduledExecutorService BODY_TIMEOUTS = createBodyTimeoutScheduler();

    private static ScheduledExecutorService createBodyTimeoutScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, NamedThreads.daemon("earth-range-timeout"));
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** Whether {@code path} holds a versioned confirmed-404 marker rather than a legacy 0-byte {@code .none} file. */
    static boolean isConfirmedNotFoundMarker(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
            if (Files.size(path) != CONFIRMED_NOT_FOUND_MARKER.length) return false;
            return Arrays.equals(DiskCache.read(path), CONFIRMED_NOT_FOUND_MARKER);
        } catch (IOException unreadable) {
            return false;
        }
    }

    private final String what, agent;
    private final Duration timeout;
    private final long maxFallbackBytes;
    private final HttpClient http;
    private final Semaphore transfers;
    private final ExecutorService executor;
    final AtomicLong downloads = new AtomicLong(), diskHits = new AtomicLong(), failures = new AtomicLong();

    /** @param what what is read, for messages ("Land cover"); {@code agent} names the dataset to servers */
    RangeFiles(String what, String agent, String threadName, int maxConcurrentTransfers, Duration timeout) {
        this(what, agent, threadName, maxConcurrentTransfers, timeout, MAX_FALLBACK_BYTES);
    }

    RangeFiles(String what, String agent, String threadName, int maxConcurrentTransfers, Duration timeout, long maxFallbackBytes) {
        this.what = what;
        this.agent = agent;
        this.timeout = timeout;
        this.maxFallbackBytes = Math.max(1L, maxFallbackBytes);
        this.transfers = new Semaphore(maxConcurrentTransfers);
        this.executor = Executors.newFixedThreadPool(maxConcurrentTransfers, NamedThreads.daemon(threadName));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).executor(executor).build();
    }

    /** The bytes of a file from offset, or null when the file does not exist (404 or 403). */
    byte[] fetch(URI uri, long offset, int length) throws IOException, InterruptedException {
        FetchResult result = fetchResult(uri, offset, length);
        return result.isOk() ? result.bytes() : null;
    }

    /** Typed range read distinguishing 200/206 bytes from 404 (not found) and 403 (forbidden). */
    FetchResult fetchResult(URI uri, long offset, int length) throws IOException, InterruptedException {
        DataWaits.before();
        try { return transfer(uri, offset, length); }
        finally { DataWaits.after(); }
    }

    private FetchResult transfer(URI uri, long offset, int length) throws IOException, InterruptedException {
        if (offset < 0 || length <= 0 || offset > Long.MAX_VALUE - (long) length) {
            throw new IllegalArgumentException("Invalid range " + offset + "+" + length);
        }
        long rangeEnd = offset + (long) length - 1;
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", "earth-platform/" + agent)
                .header("Range", "bytes=" + offset + "-" + rangeEnd).GET().build();
        IOException last = null;
        // Three tries for ordinary failures; a host asking us to slow down is waited out a few more times.
        for (int attempt = 0, slowed = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(250L << attempt);
            HostBackoff.await(uri);
            HostBackoff.reachable(uri, what);
            transfers.acquire();
            try {
                // Asked again: a host can go down while this transfer waited its turn.
                HostBackoff.reachable(uri, what);
                long start = System.nanoTime();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                InputStream rawStream = response.body();
                AtomicBoolean bodyTimedOut = new AtomicBoolean();
                ScheduledFuture<?> bodyDeadline = BODY_TIMEOUTS.schedule(() -> {
                    bodyTimedOut.set(true);
                    try { rawStream.close(); }
                    catch (IOException | RuntimeException ignored) { /* already closed */ }
                }, Math.max(1L, timeout.toNanos()), TimeUnit.NANOSECONDS);
                try (InputStream bodyStream = rawStream) {
                    int status = response.statusCode();
                    if (status == 206 || status == 200) {
                        byte[] body = readBoundedBody(
                                uri,
                                status,
                                offset,
                                length,
                                response.headers().firstValueAsLong("Content-Length"),
                                response.headers().firstValue("Content-Range"),
                                bodyStream);
                        HostBackoff.answered(uri);
                        downloads.incrementAndGet();
                        Downloads.count(what, body.length, System.nanoTime() - start);
                        return FetchResult.ok(body);
                    }
                    // Drain small error bodies so HTTP/1.1 keep-alive connections remain reusable.
                    drainSmall(bodyStream, MAX_ERROR_DRAIN_BYTES);
                    if (status >= 500 && !HostBackoff.slowDown(status)) HostBackoff.failed(uri, what, "HTTP " + status);
                    else HostBackoff.answered(uri);
                    if (status == 404) return FetchResult.NOT_FOUND;
                    if (status == 403) return FetchResult.FORBIDDEN;
                    last = new IOException("HTTP " + status + " for " + uri);
                    if (HostBackoff.slowDown(status) && ++slowed < HostBackoff.ATTEMPTS) {
                        HostBackoff.pause(uri, response, slowed, what);
                        attempt--;
                    }
                } catch (IOException bodyFailure) {
                    if (bodyTimedOut.get()) {
                        throw new HttpTimeoutException("Body read timed out after " + timeout.toMillis() + " ms for " + uri);
                    }
                    throw bodyFailure;
                } finally {
                    bodyDeadline.cancel(false);
                }
            } catch (InterruptedIOException interrupted) {
                throw interrupted;
            } catch (IOException failure) {
                last = failure;
                HostBackoff.failed(uri, what, failure);
            } finally {
                transfers.release();
            }
        }
        throw new MissingTerrainException(what + " download failed after retries: " + uri + " (" + last + ")");
    }

    private static void drainSmall(InputStream stream, int maxBytes) {
        byte[] scratch = new byte[Math.min(4096, maxBytes)];
        int remaining = maxBytes;
        try {
            while (remaining > 0) {
                int read = stream.read(scratch, 0, Math.min(scratch.length, remaining));
                if (read < 0) return;
                remaining -= read;
            }
        } catch (IOException ignored) {
            // Best-effort drain for connection reuse; status code is already known.
        }
    }

    private record ContentRange(long start, long end, long total) {
        long length() { return end - start + 1; }
    }

    private static ContentRange parseContentRange(String header, URI uri) throws IOException {
        String trimmed = header.trim();
        if (!trimmed.regionMatches(true, 0, "bytes ", 0, 6)) {
            throw new IOException("Unsupported HTTP 206 Content-Range '" + header + "' for " + uri);
        }
        String spec = trimmed.substring(6).trim();
        int slash = spec.indexOf('/');
        int dash = spec.indexOf('-');
        if (slash < 0 || dash <= 0 || dash >= slash - 1) {
            throw new IOException("Malformed HTTP 206 Content-Range '" + header + "' for " + uri);
        }
        try {
            long start = Long.parseLong(spec.substring(0, dash).trim());
            long end = Long.parseLong(spec.substring(dash + 1, slash).trim());
            String totalText = spec.substring(slash + 1).trim();
            long total = "*".equals(totalText) ? -1L : Long.parseLong(totalText);
            if (start < 0 || end < start || end == Long.MAX_VALUE) {
                throw new IOException("Invalid HTTP 206 Content-Range bounds '" + header + "' for " + uri);
            }
            if (total >= 0 && end >= total) {
                throw new IOException("Out-of-bounds HTTP 206 Content-Range '" + header + "' for " + uri);
            }
            return new ContentRange(start, end, total);
        } catch (NumberFormatException malformed) {
            throw new IOException("Malformed HTTP 206 Content-Range '" + header + "' for " + uri, malformed);
        }
    }

    private byte[] readBoundedBody(URI uri, int status, long offset, int length,
                                   OptionalLong contentLength, Optional<String> contentRangeHeader,
                                   InputStream stream) throws IOException {
        if (status == 200) {
            // Strictly enforce the independent HTTP 200 fallback cap regardless of requested length.
            long limit = maxFallbackBytes;
            if (contentLength.isPresent() && contentLength.getAsLong() > limit) {
                throw new MissingTerrainException(what + " server returned oversized HTTP 200 body (" + contentLength.getAsLong() + " bytes, limit " + limit + "): " + uri);
            }
            if (offset > limit || offset + (long) length > limit) {
                throw new MissingTerrainException(what + " server ignored Range request (HTTP 200) for oversized range " + offset + "+" + length + " exceeding limit " + limit + ": " + uri);
            }
            byte[] scratch = new byte[8192];
            // Skip prefix up to offset without buffering it.
            long remainingSkip = offset;
            while (remainingSkip > 0) {
                int want = (int) Math.min(scratch.length, remainingSkip);
                int read = stream.read(scratch, 0, want);
                if (read < 0) return new byte[0];
                remainingSkip -= read;
            }
            // Buffer at most the requested length bytes and return immediately once filled.
            int initialCap = Math.min(length, 65536);
            ByteArrayOutputStream out = new ByteArrayOutputStream(initialCap);
            int remainingTake = length;
            while (remainingTake > 0) {
                int want = Math.min(scratch.length, remainingTake);
                int read = stream.read(scratch, 0, want);
                if (read < 0) break;
                out.write(scratch, 0, read);
                remainingTake -= read;
            }
            return out.toByteArray();
        }
        // HTTP 206 partial content: validate Content-Range when present and enforce requested length bound.
        long expectedLength = -1L;
        if (contentRangeHeader.isPresent()) {
            ContentRange range = parseContentRange(contentRangeHeader.get(), uri);
            if (range.start() != offset) {
                throw new IOException("HTTP 206 Content-Range start " + range.start() + " != requested offset " + offset + " for " + uri);
            }
            long rangeLength = range.length();
            if (rangeLength > (long) length) {
                throw new IOException("HTTP 206 Content-Range length " + rangeLength + " exceeds requested " + length + " for " + uri);
            }
            if (rangeLength < (long) length && (range.total() < 0 || range.end() + 1 != range.total())) {
                throw new IOException("Short HTTP 206 Content-Range '" + contentRangeHeader.get() + "' before EOF (requested " + length + " bytes from " + offset + ") for " + uri);
            }
            if (contentLength.isPresent() && contentLength.getAsLong() != rangeLength) {
                throw new IOException("HTTP 206 Content-Length " + contentLength.getAsLong() + " != Content-Range length " + rangeLength + " for " + uri);
            }
            expectedLength = rangeLength;
        } else if (contentLength.isPresent()) {
            if (contentLength.getAsLong() > (long) length) {
                throw new IOException("HTTP 206 Content-Length " + contentLength.getAsLong() + " exceeds requested " + length + " for " + uri);
            }
            expectedLength = contentLength.getAsLong();
        }
        long maxRead = expectedLength >= 0 ? expectedLength : (long) length;
        byte[] scratch = new byte[8192];
        int initialCap = (int) Math.min(Math.max(0L, maxRead), 65536L);
        ByteArrayOutputStream out = new ByteArrayOutputStream(initialCap);
        long totalRead = 0;
        while (true) {
            int read = stream.read(scratch, 0, scratch.length);
            if (read < 0) break;
            totalRead += read;
            if (totalRead > maxRead) {
                throw new IOException("HTTP 206 body exceeded expected length (" + maxRead + " bytes) for " + uri);
            }
            out.write(scratch, 0, read);
        }
        if (expectedLength >= 0 && totalRead != expectedLength) {
            throw new IOException("Truncated HTTP 206 body (" + totalRead + " of " + expectedLength + " bytes) for " + uri);
        }
        return out.toByteArray();
    }

    /**
     * Runs loads all at once, each on its own virtual thread, so as many transfers run together as
     * the transfer limit allows (a parallel stream would stop at the processor count). Waits for
     * all of them, then rethrows the first failure.
     */
    static void all(java.util.List<Runnable> loads) {
        RuntimeException first = null;
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<Future<?>> running = new java.util.ArrayList<>(loads.size());
            for (Runnable load : loads) running.add(threads.submit(() -> MemoryPressure.runHeavy(load)));
            for (Future<?> load : running) {
                try { DataWaits.get(load); }
                catch (ExecutionException failed) {
                    if (first == null) first = failed.getCause() instanceof RuntimeException runtime ? runtime : new MissingTerrainException(String.valueOf(failed.getCause()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new MissingTerrainException("Interrupted while prefetching");
                }
            }
        }
        if (first != null) throw first;
    }

    /** Loads a piece once however many callers want it at the same time; a failure is retried by the next caller. */
    <K, T> T singleFlight(Map<K, CompletableFuture<T>> flights, K key, Load<T> load, String piece) {
        TickShare.giveWay();
        CompletableFuture<T> mine = new CompletableFuture<>(), shared = flights.putIfAbsent(key, mine);
        if (shared == null) {
            try {
                MemoryPressure.runHeavy(() -> {
                    try { mine.complete(load.get()); }
                    catch (Throwable failure) { failures.incrementAndGet(); mine.completeExceptionally(failure); }
                });
            } finally { flights.remove(key, mine); }
            shared = mine;
        }
        return await(shared, piece);
    }

    <T> T await(CompletableFuture<T> future, String piece) {
        try {
            return DataWaits.get(future, timeout.toMillis() * 4, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MissingTerrainException("Interrupted waiting for " + piece);
        } catch (TimeoutException late) {
            throw new MissingTerrainException("Timed out waiting for " + piece);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof MissingTerrainException missing) throw missing;
            throw new MissingTerrainException(piece + " failed: " + failed.getCause());
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
