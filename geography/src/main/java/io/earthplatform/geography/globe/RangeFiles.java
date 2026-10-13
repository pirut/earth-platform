package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP range reads for the globe's streamed rasters: bounded in concurrency, retried, shared by
 * every caller waiting on the same piece (single flight), with atomic writes for disk caches.
 */
final class RangeFiles implements AutoCloseable {
    interface Load<T> { T get() throws IOException, InterruptedException; }

    private final String what, agent;
    private final Duration timeout;
    private final HttpClient http;
    private final Semaphore transfers;
    private final ExecutorService executor;
    final AtomicLong downloads = new AtomicLong(), diskHits = new AtomicLong(), failures = new AtomicLong();

    /** @param what what is read, for messages ("Land cover"); {@code agent} names the dataset to servers */
    RangeFiles(String what, String agent, String threadName, int maxConcurrentTransfers, Duration timeout) {
        this.what = what;
        this.agent = agent;
        this.timeout = timeout;
        this.transfers = new Semaphore(maxConcurrentTransfers);
        this.executor = Executors.newFixedThreadPool(maxConcurrentTransfers, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).executor(executor).build();
    }

    /** The bytes of a file from offset, or null when the file does not exist. */
    byte[] fetch(URI uri, long offset, int length) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", "earth-platform/" + agent)
                .header("Range", "bytes=" + offset + "-" + (offset + length - 1)).GET().build();
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(250L << attempt);
            transfers.acquire();
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 206 || status == 200) {
                    downloads.incrementAndGet();
                    byte[] body = response.body();
                    if (status == 200 && (offset != 0 || body.length > length)) body = Arrays.copyOfRange(body, (int) offset, (int) Math.min(body.length, offset + length));
                    return body;
                }
                if (status == 404 || status == 403) return null;
                last = new IOException("HTTP " + status + " for " + uri);
            } catch (InterruptedIOException interrupted) {
                throw interrupted;
            } catch (IOException failure) {
                last = failure;
            } finally {
                transfers.release();
            }
        }
        throw new MissingTerrainException(what + " download failed after retries: " + uri + " (" + last + ")");
    }

    static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "part", ".part");
        try {
            Files.write(temp, bytes);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Loads a piece once however many callers want it at the same time; a failure is retried by the next caller. */
    <K, T> T singleFlight(Map<K, CompletableFuture<T>> flights, K key, Load<T> load, String piece) {
        CompletableFuture<T> mine = new CompletableFuture<>(), shared = flights.putIfAbsent(key, mine);
        if (shared == null) {
            try { mine.complete(load.get()); }
            catch (Throwable failure) { failures.incrementAndGet(); mine.completeExceptionally(failure); }
            finally { flights.remove(key, mine); }
            shared = mine;
        }
        return await(shared, piece);
    }

    <T> T await(CompletableFuture<T> future, String piece) {
        try {
            return future.get(timeout.toMillis() * 4, TimeUnit.MILLISECONDS);
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
