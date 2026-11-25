package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.MissingTerrainException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostBackoffAndRangeFilesTest {

    @Test
    void hostBackoffCanonicalPathsSeparate5xxFromTransportAndRotatesRecoveryProbes() throws Exception {
        AtomicInteger goodHits = new AtomicInteger();
        AtomicBoolean goodFailing = new AtomicBoolean(false);
        AtomicBoolean altHealthy = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/good.tif")) {
                goodHits.incrementAndGet();
                if (goodFailing.get()) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                byte[] body = {1, 2, 3, 4};
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-3/4");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (path.equals("/alt-good.tif") && altHealthy.get()) {
                byte[] body = {9};
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-0/1");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try {
            HostBackoff.clear(base);
            URI good = base.resolve("good.tif");
            URI singleBad = base.resolve("broken.tif");

            // Record a known-healthy URI on this host first.
            HostBackoff.answered(good);

            // 1. Query variants & ./.. aliases of the same canonical path count as 1 path and must NOT trip backoff.
            HostBackoff.failed(base.resolve("tile.tif?v=1"), "Test", "HTTP 500");
            Thread.sleep(HostBackoff.DOWN_STREAK_MS + 100);
            HostBackoff.failed(base.resolve("tile.tif?v=2"), "Test", "HTTP 500");
            HostBackoff.failed(base.resolve("tile.tif?v=3"), "Test", "HTTP 500");
            HostBackoff.failed(base.resolve("sub/../tile.tif?v=4"), "Test", "HTTP 500");
            HostBackoff.failed(base.resolve("./tile.tif"), "Test", "HTTP 500");
            assertFalse(HostBackoff.isDown(base), "query variants and dot-segments of one canonical path must not trip host backoff");

            // 2. Repeated 5xx on 1 path + 1 unrelated transport error on another path must NOT trip host outage.
            HostBackoff.failed(base.resolve("other.tif"), "Test", new java.io.IOException("Connection reset"));
            assertFalse(HostBackoff.isDown(base), "single-path 5xx must not prime host-wide outage for one unrelated transport error");
            assertDoesNotThrow(() -> HostBackoff.reachable(good, "Test"));

            // Healthy tiles on the same host continue succeeding through RangeFiles.
            try (RangeFiles files = new RangeFiles("Test", "test-v1", "test-io", 2, Duration.ofSeconds(5))) {
                assertArrayEquals(new byte[] {1, 2, 3, 4}, files.fetch(good, 0, 4));
                assertEquals(1, goodHits.get());
            }

            // 3. Real outage: even the previously healthy path returns 500 during the outage alongside other paths!
            goodFailing.set(true);
            HostBackoff.failed(good, "Test", "HTTP 500");
            Thread.sleep(HostBackoff.DOWN_STREAK_MS + 100);
            HostBackoff.failed(base.resolve("bad-1.tif"), "Test", "HTTP 500");
            assertFalse(HostBackoff.isDown(base), "fewer than DOWN_AFTER_PATHS distinct paths should not trip yet");
            HostBackoff.failed(base.resolve("bad-2.tif"), "Test", "HTTP 500");
            assertTrue(HostBackoff.isDown(base), "500s across distinct canonical paths must trip host backoff");
            assertThrows(MissingTerrainException.class, () -> HostBackoff.reachable(good, "Test"));

            // Outage ends: good.tif is healthy again (even though it returned 500 during the outage),
            // while client keeps requesting singleBad. HostBackoff prefers lastHealthyUri and recovers!
            goodFailing.set(false);
            HostBackoff.expireBackoffForTest(base);
            assertThrows(MissingTerrainException.class, () -> HostBackoff.reachable(singleBad, "Test"));
            for (int i = 0; i < 100 && HostBackoff.isDown(base); i++) Thread.sleep(20);
            assertFalse(HostBackoff.isDown(base), "probe must prefer remembered healthy URI even if it saw 5xx during the outage");

            // 4. Probe rotation: suppose good.tif becomes permanently broken (500) during a new outage,
            // alt-good.tif is healthy, and the client keeps requesting broken.tif. Rotation must still recover!
            goodFailing.set(true);
            altHealthy.set(true);
            HostBackoff.failed(singleBad, "Test", "HTTP 500");
            Thread.sleep(HostBackoff.DOWN_STREAK_MS + 100);
            HostBackoff.failed(base.resolve("alt-good.tif"), "Test", "HTTP 500");
            HostBackoff.failed(base.resolve("bad-3.tif"), "Test", "HTTP 500");
            assertTrue(HostBackoff.isDown(base));
            for (int probe = 0; probe < 8 && HostBackoff.isDown(base); probe++) {
                HostBackoff.expireBackoffForTest(base);
                assertThrows(MissingTerrainException.class, () -> HostBackoff.reachable(singleBad, "Test"));
                for (int wait = 0; wait < 25 && HostBackoff.isDown(base); wait++) Thread.sleep(20);
            }
            assertFalse(HostBackoff.isDown(base), "rotating recovery probes must escape a permanently broken tile");
        } finally {
            HostBackoff.clear(base);
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void rangeFilesStalledBodyAbortsPromptlyAndReleasesTransferPermit() throws Exception {
        CountDownLatch releaseStall = new CountDownLatch(1);
        AtomicInteger stallHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/stalled")) {
                stallHits.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-1023/1024");
                exchange.sendResponseHeaders(206, 1024);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(42);
                    out.flush();
                    releaseStall.await(15, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Expected once client aborts stalled body read.
                }
                return;
            }
            if (path.equals("/healthy")) {
                byte[] body = {7, 8, 9, 10};
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-3/4");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try (RangeFiles files = new RangeFiles("Test", "test-v1", "test-stall-io", 1, Duration.ofMillis(200))) {
            HostBackoff.clear(base);
            long startNanos = System.nanoTime();
            assertThrows(MissingTerrainException.class, () -> files.fetchResult(base.resolve("stalled"), 0, 1024));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue(elapsedMs < 5_000, "stalled body read must abort promptly rather than hanging, took " + elapsedMs + " ms");
            assertTrue(stallHits.get() >= 1);

            // Clear host backoff so we specifically verify the single transfer permit was released!
            HostBackoff.clear(base);
            RangeFiles.FetchResult healthy = files.fetchResult(base.resolve("healthy"), 0, 4);
            assertTrue(healthy.isOk());
            assertArrayEquals(new byte[] {7, 8, 9, 10}, healthy.bytes());
        } finally {
            releaseStall.countDown();
            HostBackoff.clear(base);
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void rangeFilesGuardsOverflowFallbackCapEarlyChunked200And206ContentRange() throws Exception {
        AtomicInteger hugeHeaderHits = new AtomicInteger();
        AtomicInteger overflowHits = new AtomicInteger();
        AtomicInteger chunkedBytesWritten = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/huge-header")) {
                hugeHeaderHits.incrementAndGet();
                // Valid fixed-length Content-Length (NO conflicting Transfer-Encoding: chunked!).
                exchange.sendResponseHeaders(200, 3L << 30);
                exchange.close();
                return;
            }
            if (path.equals("/overflow")) {
                overflowHits.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            if (path.equals("/chunked-large")) {
                // Chunked HTTP 200 (no Content-Length) streaming 512 KiB (> 32 KiB test cap).
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    byte[] chunk = new byte[1024];
                    for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (i & 0xFF);
                    for (int c = 0; c < 512; c++) {
                        out.write(chunk);
                        out.flush();
                        chunkedBytesWritten.addAndGet(chunk.length);
                    }
                } catch (Exception ignored) {
                    // Client closed stream early once requested slice was filled.
                }
                return;
            }
            if (path.equals("/small-200")) {
                byte[] body = new byte[256];
                for (int i = 0; i < body.length; i++) body[i] = (byte) i;
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (path.equals("/206-wrong-offset")) {
                byte[] body = {1, 2, 3, 4};
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-3/100");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (path.equals("/206-short-not-eof")) {
                byte[] body = {1, 2, 3, 4};
                exchange.getResponseHeaders().set("Content-Range", "bytes 10-13/100");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (path.equals("/206-short-eof")) {
                byte[] body = {10, 11, 12, 13, 14};
                exchange.getResponseHeaders().set("Content-Range", "bytes 10-14/15");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (path.equals("/206-missing-cr")) {
                byte[] body = {42};
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (path.equals("/206-negative-total")) {
                byte[] body = {1, 2, 3, 4};
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-3/-5");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            if (path.equals("/404-with-body")) {
                byte[] body = "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try (RangeFiles files = new RangeFiles("Test", "test-v1", "test-oversize-io", 2, Duration.ofSeconds(5), 32_768L)) {
            HostBackoff.clear(base);

            // 1. 64-bit overflow and invalid length rejected BEFORE sending any HTTP request.
            assertThrows(IllegalArgumentException.class, () -> files.fetchResult(base.resolve("overflow"), Long.MAX_VALUE - 5, 10));
            assertThrows(IllegalArgumentException.class, () -> files.fetchResult(base.resolve("overflow"), Long.MAX_VALUE, 1));
            assertThrows(IllegalArgumentException.class, () -> files.fetchResult(base.resolve("overflow"), 0, 0));
            assertThrows(IllegalArgumentException.class, () -> files.fetchResult(base.resolve("overflow"), -1, 16));
            assertEquals(0, overflowHits.get(), "invalid/overflowing range must fail before any HTTP request is sent");

            // 2. > 2 GiB fixed Content-Length rejected immediately on first hit with "oversized" message.
            MissingTerrainException oversized = assertThrows(MissingTerrainException.class,
                    () -> files.fetchResult(base.resolve("huge-header"), 0, 1024));
            assertTrue(oversized.getMessage().toLowerCase().contains("oversized"), "message should mention oversized: " + oversized.getMessage());
            assertEquals(1, hugeHeaderHits.get(), "oversized HTTP 200 guard must reject on first hit without retrying");
            assertFalse(HostBackoff.isDown(base), "oversized HTTP 200 guard does not trip host outage");

            // 3. HTTP 200 cap is strictly independent of requested length (length > maxFallbackBytes must still reject).
            MissingTerrainException overCap = assertThrows(MissingTerrainException.class,
                    () -> files.fetchResult(base.resolve("small-200"), 0, 65_536));
            assertTrue(overCap.getMessage().toLowerCase().contains("oversized") || overCap.getMessage().contains("limit"));

            // 4. > 2 GiB offset with HTTP 200 rejected without (int) offset overflow.
            assertThrows(MissingTerrainException.class, () -> files.fetchResult(base.resolve("small-200"), 3L << 30, 16));

            // 5. Chunked HTTP 200 returns early as soon as requested slice is completely filled (does not drain whole stream).
            RangeFiles.FetchResult chunkedSlice = files.fetchResult(base.resolve("chunked-large"), 0, 1024);
            assertTrue(chunkedSlice.isOk());
            assertEquals(1024, chunkedSlice.bytes().length);
            assertEquals((byte) 17, chunkedSlice.bytes()[17]);

            // 6. Legitimate small HTTP 200 still slices [offset, offset + length) accurately.
            RangeFiles.FetchResult ok = files.fetchResult(base.resolve("small-200"), 10, 5);
            assertTrue(ok.isOk());
            assertArrayEquals(new byte[] {10, 11, 12, 13, 14}, ok.bytes());

            // 7. HTTP 206 missing Content-Range (1 byte for 4-byte request) rejected AND does NOT trip HostBackoff!
            MissingTerrainException missingCr = assertThrows(MissingTerrainException.class,
                    () -> files.fetchResult(base.resolve("206-missing-cr"), 0, 4));
            assertTrue(missingCr.getMessage().contains("Content-Range"), missingCr.getMessage());
            assertFalse(HostBackoff.isDown(base), "3 retries against one malformed-206 URL must NEVER mark host down");

            // 8. HTTP 206 negative Content-Range total (bytes 0-3/-5) rejected AND does NOT trip HostBackoff!
            MissingTerrainException negTotal = assertThrows(MissingTerrainException.class,
                    () -> files.fetchResult(base.resolve("206-negative-total"), 0, 4));
            assertTrue(negTotal.getMessage().contains("Content-Range"), negTotal.getMessage());
            assertFalse(HostBackoff.isDown(base), "malformed Content-Range total must NEVER mark host down");

            // 9. HTTP 206 wrong Content-Range start offset & short read before EOF rejected without tripping HostBackoff.
            MissingTerrainException wrongOffset = assertThrows(MissingTerrainException.class,
                    () -> files.fetchResult(base.resolve("206-wrong-offset"), 10, 4));
            assertTrue(wrongOffset.getMessage().contains("Content-Range"), wrongOffset.getMessage());
            assertFalse(HostBackoff.isDown(base));

            MissingTerrainException shortNonEof = assertThrows(MissingTerrainException.class,
                    () -> files.fetchResult(base.resolve("206-short-not-eof"), 10, 16));
            assertTrue(shortNonEof.getMessage().contains("Content-Range"), shortNonEof.getMessage());
            assertFalse(HostBackoff.isDown(base), "response-validation errors must leave unrelated healthy tiles unaffected");

            // 10. HTTP 206 short read AT genuine EOF accepted cleanly even immediately after malformed-206 failures!
            RangeFiles.FetchResult eofSlice = files.fetchResult(base.resolve("206-short-eof"), 10, 100);
            assertTrue(eofSlice.isOk());
            assertArrayEquals(new byte[] {10, 11, 12, 13, 14}, eofSlice.bytes());

            // 10. Small 404 error body drained cleanly for HTTP/1.1 keep-alive reuse.
            assertTrue(files.fetchResult(base.resolve("404-with-body"), 0, 16).isNotFound());
        } finally {
            HostBackoff.clear(base);
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void floatCogs403RecoveryVersionedNoneAndLegacyNoneRevalidation(@TempDir Path cache) throws Exception {
        byte[] cog = syntheticFloatCog(42.5f);
        AtomicBoolean denyTransient = new AtomicBoolean(true);
        AtomicInteger transientHits = new AtomicInteger();
        AtomicInteger missing404Hits = new AtomicInteger();
        AtomicInteger legacyRecoverHits = new AtomicInteger();
        AtomicInteger legacy404Hits = new AtomicInteger();
        AtomicInteger legacy403Hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/cogs/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/cogs/".length());
            switch (path) {
                case "file-404.tif" -> {
                    missing404Hits.incrementAndGet();
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                }
                case "file-403.tif" -> {
                    transientHits.incrementAndGet();
                    if (denyTransient.get()) {
                        exchange.sendResponseHeaders(403, -1);
                        exchange.close();
                        return;
                    }
                    sendRange(exchange, cog);
                }
                case "legacy-recover.tif" -> {
                    legacyRecoverHits.incrementAndGet();
                    sendRange(exchange, cog);
                }
                case "legacy-404.tif" -> {
                    legacy404Hits.incrementAndGet();
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                }
                case "legacy-403.tif" -> {
                    legacy403Hits.incrementAndGet();
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                }
                default -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                }
            }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cogs/");
        java.util.function.IntFunction<String> names = id -> switch (id) {
            case 1 -> "file-403.tif";
            case 2 -> "file-404.tif";
            case 3 -> "legacy-recover.tif";
            case 4 -> "legacy-404.tif";
            case 5 -> "legacy-403.tif";
            default -> "unknown.tif";
        };
        try {
            // Seed legacy 0-byte .none markers before opening sources.
            Files.write(cache.resolve("legacy-recover.none"), new byte[0]);
            Files.write(cache.resolve("legacy-404.none"), new byte[0]);
            Files.write(cache.resolve("legacy-403.none"), new byte[0]);

            // Offline mode MUST still honor legacy 0-byte .none markers without throwing or hitting network.
            try (FloatCogs offline = new FloatCogs("TestCOG", "test-cog-v1", "test-cog-off", base, names, -9999f, cache, false, 4, 2, Duration.ofSeconds(5))) {
                assertNull(offline.grid(3, 0, 1005, 1995), "legacy empty .none honored offline");
                assertNull(offline.grid(4, 0, 1005, 1995), "legacy empty .none honored offline");
                assertEquals(0, legacyRecoverHits.get());
                assertEquals(0, legacy404Hits.get());
            }

            try (FloatCogs cogs = new FloatCogs("TestCOG", "test-cog-v1", "test-cog-io", base, names, -9999f, cache, true, 4, 2, Duration.ofSeconds(5))) {
                cogs.setForbiddenTtlNanos(TimeUnit.MILLISECONDS.toNanos(120));

                // 403 -> treated as missing without writing .none to disk, cached in memory within TTL.
                assertNull(cogs.grid(1, 0, 1005, 1995));
                assertEquals(1, transientHits.get());
                assertFalse(Files.exists(cache.resolve("file-403.none")));

                // Second read within TTL hits in-memory negative cache without another HTTP request.
                assertNull(cogs.grid(1, 0, 1005, 1995));
                assertEquals(1, transientHits.get());

                // Server recovers; after TTL expires, FloatCogs re-fetches and succeeds!
                denyTransient.set(false);
                Thread.sleep(160);
                FloatCogs.Grid recovered = cogs.grid(1, 0, 1005, 1995);
                assertNotNull(recovered, "403 must self-heal after TTL expires");
                assertEquals(42.5, cogs.metres(recovered, 1005, 1995), 1e-4);
                assertTrue(Files.exists(cache.resolve("file-403.header")));

                // 404 -> persists versioned "404\n" .none on disk permanently.
                assertNull(cogs.grid(2, 0, 1005, 1995));
                assertEquals(1, missing404Hits.get());
                assertArrayEquals(RangeFiles.CONFIRMED_NOT_FOUND_MARKER, Files.readAllBytes(cache.resolve("file-404.none")));

                // Online revalidation of legacy 0-byte .none:
                // (a) 206 -> deletes legacy .none and caches real header!
                FloatCogs.Grid revGrid = cogs.grid(3, 0, 1005, 1995);
                assertNotNull(revGrid);
                assertEquals(42.5, cogs.metres(revGrid, 1005, 1995), 1e-4);
                assertFalse(Files.exists(cache.resolve("legacy-recover.none")));
                assertTrue(Files.exists(cache.resolve("legacy-recover.header")));

                // (b) 404 -> upgrades legacy .none to versioned "404\n" marker.
                assertNull(cogs.grid(4, 0, 1005, 1995));
                assertEquals(1, legacy404Hits.get());
                assertArrayEquals(RangeFiles.CONFIRMED_NOT_FOUND_MARKER, Files.readAllBytes(cache.resolve("legacy-404.none")));

                // (c) 403 -> preserves legacy .none for offline sessions and records in-memory TTL (no .403 file).
                assertNull(cogs.grid(5, 0, 1005, 1995));
                assertEquals(1, legacy403Hits.get());
                assertTrue(Files.exists(cache.resolve("legacy-403.none")), "ambiguous 403 must preserve legacy .none for offline sessions");
                assertFalse(Files.exists(cache.resolve("file-403.403")), "new 403 must write no .403 file to disk");
                assertNull(cogs.grid(5, 0, 1005, 1995));
                assertEquals(1, legacy403Hits.get(), "403 cached in memory for TTL");

                // Verify preserved legacy .none still works in offline mode!
                try (FloatCogs offlineAfter403 = new FloatCogs("TestCOG", "test-cog-v1", "test-cog-off2", base, names, -9999f, cache, false, 4, 2, Duration.ofSeconds(5))) {
                    assertNull(offlineAfter403.grid(5, 0, 1005, 1995), "preserved legacy .none must still work offline");
                    assertEquals(1, legacy403Hits.get());
                }

                // (d) Disk .header written during an active 403 TTL is read immediately without hitting the network!
                cogs.setForbiddenTtlNanos(TimeUnit.MINUTES.toNanos(10));
                Files.write(cache.resolve("legacy-403.header"), cog);
                FloatCogs.Grid fromDiskDuringTtl = cogs.grid(5, 0, 1005, 1995);
                assertNotNull(fromDiskDuringTtl, "disk .header written during active 403 TTL must be read immediately");
                assertEquals(1, legacy403Hits.get(), "reading disk .header during 403 TTL must not hit network");
            }
        } finally {
            HostBackoff.clear(base);
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void copernicusInstanceScoped403DiskCacheFirstVersionedNoneAndMidHeader403(@TempDir Path cache1, @TempDir Path cache2) throws Exception {
        byte[] dem = syntheticCopernicusDem(123.5f);
        byte[] shortHeaderNeedMore = new byte[16384];
        ByteBuffer hdrBuf = ByteBuffer.wrap(shortHeaderNeedMore).order(ByteOrder.LITTLE_ENDIAN);
        hdrBuf.put(new byte[] {'I', 'I', 42, 0});
        hdrBuf.putInt(4, 20_000); // IFD offset past initial 16384-byte read -> forces wider header fetch

        String sq403 = CopernicusSource.name(10, 20);
        String sq404 = CopernicusSource.name(11, 21);
        String sqMid403 = CopernicusSource.name(12, 22);
        String sqLegacyOk = CopernicusSource.name(13, 23);
        String sqLegacy404 = CopernicusSource.name(14, 24);
        String sqLegacy403 = CopernicusSource.name(15, 25);

        AtomicBoolean denySq403 = new AtomicBoolean(true);
        AtomicInteger sq403Hits = new AtomicInteger();
        AtomicInteger sq404Hits = new AtomicInteger();
        AtomicInteger mid403Hits = new AtomicInteger();
        AtomicInteger legacyOkHits = new AtomicInteger();
        AtomicInteger legacy403Hits = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains(sq403)) {
                sq403Hits.incrementAndGet();
                if (denySq403.get()) {
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                    return;
                }
                sendRange(exchange, dem);
                return;
            }
            if (path.contains(sq404) || path.contains(sqLegacy404)) {
                sq404Hits.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (path.contains(sqMid403)) {
                int hit = mid403Hits.incrementAndGet();
                if (hit == 1) {
                    sendRange(exchange, shortHeaderNeedMore);
                } else {
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                }
                return;
            }
            if (path.contains(sqLegacyOk)) {
                legacyOkHits.incrementAndGet();
                sendRange(exchange, dem);
                return;
            }
            if (path.contains(sqLegacy403)) {
                legacy403Hits.incrementAndGet();
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try {
            // Pre-populate cache2 with a valid .header for sq403.
            Files.write(cache2.resolve(sq403 + ".header"), dem);
            // Seed legacy 0-byte .none files in cache1.
            Files.write(cache1.resolve(sqLegacyOk + ".none"), new byte[0]);
            Files.write(cache1.resolve(sqLegacy404 + ".none"), new byte[0]);
            Files.write(cache1.resolve(sqLegacy403 + ".none"), new byte[0]);

            // Offline instance honors legacy 0-byte .none files.
            try (CopernicusSource offline = new CopernicusSource(base, cache1, false, 4, 2, Duration.ofSeconds(5))) {
                assertFalse(offline.covers(13.5, 23.5), "legacy empty .none honored offline");
                assertEquals(0, legacyOkHits.get());
            }

            try (CopernicusSource s1 = new CopernicusSource(base, cache1, true, 4, 2, Duration.ofSeconds(5));
                 CopernicusSource s2 = new CopernicusSource(base, cache2, false, 4, 2, Duration.ofSeconds(5))) {
                s1.setForbiddenTtlNanos(TimeUnit.MILLISECONDS.toNanos(120));

                // 1. s1 sees 403 on sq403 -> cached strictly in s1's instance TTL without writing .none or .403.
                assertFalse(s1.covers(10.5, 20.5));
                assertEquals(1, sq403Hits.get());
                assertFalse(Files.exists(cache1.resolve(sq403 + ".none")));
                assertFalse(Files.exists(cache1.resolve(sq403 + ".403")), "new 403 must write no durable .403 file");

                // 2. s2 (separate instance with disk .header cached!) MUST NOT be poisoned by s1's 403 and reads disk first!
                assertTrue(s2.covers(10.5, 20.5), "disk-cached .header in another instance must not be poisoned by s1 403");
                assertEquals(1, sq403Hits.get(), "s2 must read local disk .header without hitting network");

                // 3. s1 self-heals after TTL expires when server stops returning 403.
                denySq403.set(false);
                Thread.sleep(160);
                assertTrue(s1.covers(10.5, 20.5), "CopernicusSource 403 must recover after TTL");
                assertEquals(123.5, s1.metres(10.5, 20.5, 0), 1e-4);

                // 4. 404 writes versioned "404\n" .none marker on disk.
                assertFalse(s1.covers(11.5, 21.5));
                assertArrayEquals(RangeFiles.CONFIRMED_NOT_FOUND_MARKER, Files.readAllBytes(cache1.resolve(sq404 + ".none")));

                // 5. Mid-header 403 throws MissingTerrainException without caching missing or .none!
                assertThrows(MissingTerrainException.class, () -> s1.covers(12.5, 22.5));
                assertEquals(2, mid403Hits.get());
                assertFalse(Files.exists(cache1.resolve(sqMid403 + ".none")));

                // 6. Legacy 0-byte .none online revalidation: 206 deletes & caches, 404 upgrades to "404\n", 403 preserves legacy .none & records TTL.
                assertTrue(s1.covers(13.5, 23.5));
                assertEquals(123.5, s1.metres(13.5, 23.5, 0), 1e-4);
                assertFalse(Files.exists(cache1.resolve(sqLegacyOk + ".none")));
                assertTrue(Files.exists(cache1.resolve(sqLegacyOk + ".header")));

                assertFalse(s1.covers(14.5, 24.5));
                assertArrayEquals(RangeFiles.CONFIRMED_NOT_FOUND_MARKER, Files.readAllBytes(cache1.resolve(sqLegacy404 + ".none")));

                s1.setForbiddenTtlNanos(TimeUnit.MINUTES.toNanos(10));
                assertFalse(s1.covers(15.5, 25.5));
                assertTrue(Files.exists(cache1.resolve(sqLegacy403 + ".none")),
                        "ambiguous 403 on legacy .none must preserve legacy .none for offline use");
                assertFalse(Files.exists(cache1.resolve(sqLegacy403 + ".403")),
                        "ambiguous 403 must not write durable .403 file");

                assertEquals(1, legacy403Hits.get());
                try (CopernicusSource offlineAfter403 = new CopernicusSource(base, cache1, false, 4, 2, Duration.ofSeconds(5))) {
                    assertFalse(offlineAfter403.covers(15.5, 25.5), "preserved legacy .none must still be honored offline");
                    assertEquals(1, legacy403Hits.get());
                }

                // 7. A .header written to disk during an active 403 TTL is read immediately without hitting network!
                Files.write(cache1.resolve(sqLegacy403 + ".header"), dem);
                assertTrue(s1.covers(15.5, 25.5), "disk .header written during active 403 TTL must be read immediately");
                assertEquals(1, legacy403Hits.get(), "reading disk .header during 403 TTL must not hit network");
            }
        } finally {
            HostBackoff.clear(base);
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }


    @Test
    void recoveryProbeStalledBodyAbortsAtDeadlineAndAlternatingRequestsRotateStably() throws Exception {
        CountDownLatch releaseStall = new CountDownLatch(1);
        AtomicInteger stallProbeHits = new AtomicInteger();
        AtomicInteger brokenAHits = new AtomicInteger();
        AtomicInteger healthyBHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/stalled-probe")) {
                stallProbeHits.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-1023/1024");
                exchange.sendResponseHeaders(206, 1024);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(1);
                    out.flush();
                    releaseStall.await(15, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Expected once probe body deadline closes stream.
                }
                return;
            }
            if (path.equals("/broken-a")) {
                brokenAHits.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            if (path.equals("/healthy-b")) {
                healthyBHits.incrementAndGet();
                byte[] body = {9};
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-0/1");
                exchange.sendResponseHeaders(206, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        URI stalled = base.resolve("stalled-probe");
        URI brokenA = base.resolve("broken-a");
        URI healthyB = base.resolve("healthy-b");
        try {
            HostBackoff.setCheckTimeoutForTest(Duration.ofMillis(180));

            // Part A: Stalled probe body aborts at deadline (does NOT falsely recover host or hang forever),
            // allowing the next probe to rotate to healthyB and recover!
            HostBackoff.clear(base);
            HostBackoff.answered(stalled); // remember stalled as lastHealthy so probe #1 hits it
            HostBackoff.failed(healthyB, "Test", new java.io.IOException("transient"));
            HostBackoff.failed(stalled, "Test", new java.net.http.HttpConnectTimeoutException("down"));
            assertTrue(HostBackoff.isDown(base));

            long t0 = System.nanoTime();
            HostBackoff.expireBackoffForTest(base);
            assertThrows(MissingTerrainException.class, () -> HostBackoff.reachable(stalled, "Test"));
            // Wait past the 180ms probe deadline for probe #1 to abort cleanly.
            Thread.sleep(360);
            assertTrue(stallProbeHits.get() >= 1, "first probe must have hit stalled-probe");
            assertTrue(HostBackoff.isDown(base), "stalled probe body must NOT mark host healthy");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3_000);

            // Second probe rotates to healthyB and recovers!
            HostBackoff.expireBackoffForTest(base);
            assertThrows(MissingTerrainException.class, () -> HostBackoff.reachable(stalled, "Test"));
            for (int i = 0; i < 100 && HostBackoff.isDown(base); i++) Thread.sleep(20);
            assertFalse(HostBackoff.isDown(base), "next healthy candidate must recover host after stalled probe aborts");
            assertTrue(healthyBHits.get() >= 1);

            // Part B: Alternating requests (brokenA, healthyB, brokenA, healthyB) during outage
            // rotate stably across canonical paths and NEVER pin selection on brokenA!
            HostBackoff.clear(base);
            brokenAHits.set(0);
            healthyBHits.set(0);
            // Seed outage with brokenA first, then healthyB as a candidate (no lastHealthy).
            HostBackoff.failed(brokenA, "Test", new java.io.IOException("err1"));
            HostBackoff.failed(healthyB, "Test", new java.net.http.HttpConnectTimeoutException("down"));
            assertTrue(HostBackoff.isDown(base));

            // Probe 1 requested via brokenA -> tries brokenA (500).
            HostBackoff.expireBackoffForTest(base);
            assertThrows(MissingTerrainException.class, () -> HostBackoff.reachable(brokenA, "Test"));
            for (int i = 0; i < 50 && brokenAHits.get() == 0; i++) Thread.sleep(20);
            Thread.sleep(50);
            assertEquals(1, brokenAHits.get());
            assertTrue(HostBackoff.isDown(base));

            // Probe 2 requested via healthyB (alternating!) -> MUST advance to healthyB instead of re-probing brokenA!
            HostBackoff.expireBackoffForTest(base);
            assertThrows(MissingTerrainException.class, () -> HostBackoff.reachable(healthyB, "Test"));
            for (int i = 0; i < 100 && HostBackoff.isDown(base); i++) Thread.sleep(20);
            assertFalse(HostBackoff.isDown(base), "alternating requests must reach healthyB and recover host");
            assertEquals(1, brokenAHits.get(), "brokenA must not be re-probed before untried healthyB");
            assertTrue(healthyBHits.get() >= 1);
        } finally {
            releaseStall.countDown();
            HostBackoff.setCheckTimeoutForTest(null);
            HostBackoff.clear(base);
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    private static void sendRange(com.sun.net.httpserver.HttpExchange exchange, byte[] data) throws java.io.IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        String[] span = range.substring("bytes=".length()).split("-");
        int from = Integer.parseInt(span[0]);
        int to = Math.min(data.length - 1, Integer.parseInt(span[1]));
        byte[] slice = Arrays.copyOfRange(data, from, to + 1);
        exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + data.length);
        exchange.sendResponseHeaders(206, slice.length);
        exchange.getResponseBody().write(slice);
        exchange.close();
    }

    /** Builds a tiny valid uncompressed 16x16 tiled float32 GeoTIFF with constant height {@code value}. */
    private static byte[] syntheticFloatCog(float value) {
        int size = 16;
        int blockBytes = size * size * 4;
        int ifdOffset = 8;
        int entries = 12;
        int arraysOffset = ifdOffset + 2 + entries * 12 + 4;
        int scaleOffset = arraysOffset;
        int tieOffset = scaleOffset + 24;
        int dataOffset = tieOffset + 48;
        ByteBuffer file = ByteBuffer.allocate(dataOffset + blockBytes).order(ByteOrder.LITTLE_ENDIAN);
        file.put(new byte[] {'I', 'I', 42, 0});
        file.putInt(4, ifdOffset);

        file.putDouble(scaleOffset, 10.0).putDouble(scaleOffset + 8, 10.0).putDouble(scaleOffset + 16, 0.0);
        double[] ties = {0, 0, 0, 1000, 2000, 0};
        for (int i = 0; i < 6; i++) file.putDouble(tieOffset + 8 * i, ties[i]);

        int[][] tags = {
            {256, 3, 1, size},
            {257, 3, 1, size},
            {258, 3, 1, 32},
            {259, 3, 1, 1},
            {277, 3, 1, 1},
            {322, 3, 1, size},
            {323, 3, 1, size},
            {324, 4, 1, dataOffset},
            {325, 4, 1, blockBytes},
            {339, 3, 1, 3},
            {33550, 12, 3, scaleOffset},
            {33922, 12, 6, tieOffset}
        };
        file.putShort(ifdOffset, (short) tags.length);
        for (int e = 0; e < tags.length; e++) {
            int at = ifdOffset + 2 + e * 12;
            file.putShort(at, (short) tags[e][0]).putShort(at + 2, (short) tags[e][1]).putInt(at + 4, tags[e][2]);
            if (tags[e][1] == 3 && tags[e][2] == 1) file.putShort(at + 8, (short) tags[e][3]);
            else file.putInt(at + 8, tags[e][3]);
        }
        file.putInt(ifdOffset + 2 + tags.length * 12, 0);
        file.position(dataOffset);
        for (int i = 0; i < size * size; i++) file.putFloat(value);
        return file.array();
    }

    /** Builds a tiny valid uncompressed Copernicus COG with 4 chained IFDs (full + 3 overviews). */
    private static byte[] syntheticCopernicusDem(float value) {
        int[] dims = {16, 8, 4, 2};
        int entries = 11;
        int ifdSize = 2 + entries * 12 + 4;
        int dataStart = 8 + dims.length * ifdSize;
        int totalData = 0;
        for (int d : dims) totalData += d * d * 4;
        ByteBuffer file = ByteBuffer.allocate(dataStart + totalData).order(ByteOrder.LITTLE_ENDIAN);
        file.put(new byte[] {'I', 'I', 42, 0});
        file.putInt(4, 8);
        int dataPos = dataStart;
        for (int lvl = 0; lvl < dims.length; lvl++) {
            int d = dims[lvl];
            int blockBytes = d * d * 4;
            int ifdOffset = 8 + lvl * ifdSize;
            int nextIfd = lvl + 1 < dims.length ? ifdOffset + ifdSize : 0;
            int[][] tags = {
                {256, 3, 1, d},
                {257, 3, 1, d},
                {258, 3, 1, 32},
                {259, 3, 1, 1},
                {277, 3, 1, 1},
                {317, 3, 1, 1},
                {322, 3, 1, d},
                {323, 3, 1, d},
                {324, 4, 1, dataPos},
                {325, 4, 1, blockBytes},
                {339, 3, 1, 3}
            };
            file.putShort(ifdOffset, (short) tags.length);
            for (int e = 0; e < tags.length; e++) {
                int at = ifdOffset + 2 + e * 12;
                file.putShort(at, (short) tags[e][0]).putShort(at + 2, (short) tags[e][1]).putInt(at + 4, tags[e][2]);
                if (tags[e][1] == 3 && tags[e][2] == 1) file.putShort(at + 8, (short) tags[e][3]);
                else file.putInt(at + 8, tags[e][3]);
            }
            file.putInt(ifdOffset + 2 + tags.length * 12, nextIfd);
            file.position(dataPos);
            for (int i = 0; i < d * d; i++) file.putFloat(value);
            dataPos += blockBytes;
        }
        return file.array();
    }
}
