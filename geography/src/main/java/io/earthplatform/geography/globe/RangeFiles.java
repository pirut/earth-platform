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
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.*;
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

    /** Hard cap on full-body HTTP 200 responses when a server ignores {@code Range}, preventing OOM and 32-bit overflow. */
    static final long MAX_UNRANGED_BODY_BYTES = 32L << 20;

    private final String what, agent;
    private final Duration timeout;
    private final long maxUnrangedBytes;
    private final HttpClient http;
    private final Semaphore transfers;
    private final ExecutorService executor;
    final AtomicLong downloads = new AtomicLong(), diskHits = new AtomicLong(), failures = new AtomicLong();

    /** @param what what is read, for messages ("Land cover"); {@code agent} names the dataset to servers */
    RangeFiles(String what, String agent, String threadName, int maxConcurrentTransfers, Duration timeout) {
        this(what, agent, threadName, maxConcurrentTransfers, timeout, MAX_UNRANGED_BODY_BYTES);
    }

    RangeFiles(String what, String agent, String threadName, int maxConcurrentTransfers, Duration timeout, long maxUnrangedBytes) {
        this.what = what;
        this.agent = agent;
        this.timeout = timeout;
        this.maxUnrangedBytes = Math.max(1L, maxUnrangedBytes);
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
        if (offset < 0 || length < 0) throw new IllegalArgumentException("Invalid range " + offset + "+" + length);
        long rangeEnd = length == 0 ? offset : offset + (long) length - 1;
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
                try (InputStream bodyStream = response.body()) {
                    int status = response.statusCode();
                    if (status >= 500 && !HostBackoff.slowDown(status)) HostBackoff.failed(uri, what, "HTTP " + status);
                    else HostBackoff.answered(uri);
                    if (status == 206 || status == 200) {
                        byte[] body = readBoundedBody(uri, status, offset, length, response.headers().firstValueAsLong("Content-Length"), bodyStream);
                        downloads.incrementAndGet();
                        Downloads.count(what, body.length, System.nanoTime() - start);
                        return FetchResult.ok(body);
                    }
                    if (status == 404) return FetchResult.NOT_FOUND;
                    if (status == 403) return FetchResult.FORBIDDEN;
                    last = new IOException("HTTP " + status + " for " + uri);
                    if (HostBackoff.slowDown(status) && ++slowed < HostBackoff.ATTEMPTS) {
                        HostBackoff.pause(uri, response, slowed, what);
                        attempt--;
                    }
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

    private byte[] readBoundedBody(URI uri, int status, long offset, int length, OptionalLong contentLength, InputStream stream) throws IOException {
        long limit = Math.max(maxUnrangedBytes, (long) length);
        if (contentLength.isPresent() && contentLength.getAsLong() > limit) {
            throw new MissingTerrainException(what + " server returned oversized HTTP " + status + " body (" + contentLength.getAsLong() + " bytes, limit " + limit + "): " + uri);
        }
        if (status == 200) {
            if (offset > limit || offset + (long) length > limit) {
                throw new MissingTerrainException(what + " server ignored Range request (HTTP 200) for offset " + offset + "+" + length + " exceeding limit " + limit + ": " + uri);
            }
            byte[] scratch = new byte[8192];
            long totalRead = 0;
            // Skip prefix up to offset without buffering it.
            long remainingSkip = offset;
            while (remainingSkip > 0) {
                int want = (int) Math.min(scratch.length, remainingSkip);
                int read = stream.read(scratch, 0, want);
                if (read < 0) return new byte[0];
                remainingSkip -= read;
                totalRead += read;
            }
            // Buffer at most the requested length bytes.
            int initialCap = (int) Math.min(Math.max(0, length), 65536);
            ByteArrayOutputStream out = new ByteArrayOutputStream(initialCap);
            int remainingTake = length;
            while (remainingTake > 0) {
                int want = Math.min(scratch.length, remainingTake);
                int read = stream.read(scratch, 0, want);
                if (read < 0) break;
                out.write(scratch, 0, read);
                remainingTake -= read;
                totalRead += read;
            }
            // When Content-Length was absent (chunked transfer), ensure the unranged stream stays within the cap.
            if (contentLength.isEmpty() && remainingTake == 0) {
                while (true) {
                    int read = stream.read(scratch, 0, scratch.length);
                    if (read < 0) break;
                    totalRead += read;
                    if (totalRead > limit) {
                        throw new MissingTerrainException(what + " server ignored Range request with oversized streamed HTTP 200 body (>" + limit + " bytes): " + uri);
                    }
                }
            }
            return out.toByteArray();
        }
        // HTTP 206 partial content: read bounded by limit.
        byte[] scratch = new byte[8192];
        int initialCap = (int) Math.min(Math.max(0, contentLength.orElse(length)), 65536);
        ByteArrayOutputStream out = new ByteArrayOutputStream(initialCap);
        long totalRead = 0;
        while (true) {
            int read = stream.read(scratch, 0, scratch.length);
            if (read < 0) break;
            totalRead += read;
            if (totalRead > limit) {
                throw new MissingTerrainException(what + " HTTP 206 body exceeded limit (" + limit + " bytes): " + uri);
            }
            out.write(scratch, 0, read);
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
