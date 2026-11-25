package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.MissingTerrainException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostBackoffAndRangeFilesTest {

    @Test
    void repeated500OnSameUrlDoesNotTripHostOutageButDistinctUrlsDo() throws Exception {
        AtomicInteger goodHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/bad")) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            goodHits.incrementAndGet();
            byte[] body = new byte[] {1, 2, 3, 4};
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try {
            HostBackoff.clear(base);
            URI singleBad = base.resolve("bad-only.tif");
            URI good = base.resolve("good.tif");

            // Repeated 500s on the SAME URL spanning > DOWN_STREAK_MS must NOT mark the host down.
            HostBackoff.failed(singleBad, "Test", "HTTP 500");
            Thread.sleep(HostBackoff.DOWN_STREAK_MS + 100);
            for (int i = 0; i < 6; i++) {
                HostBackoff.failed(singleBad, "Test", "HTTP 500");
            }
            assertFalse(HostBackoff.isDown(base), "single broken URL 500s must not trip host-wide backoff");
            assertDoesNotThrow(() -> HostBackoff.reachable(good, "Test"));

            // Healthy tiles on the same host continue succeeding through RangeFiles.
            try (RangeFiles files = new RangeFiles("Test", "test-v1", "test-io", 2, Duration.ofSeconds(5))) {
                assertArrayEquals(new byte[] {1, 2, 3, 4}, files.fetch(good, 0, 4));
                assertEquals(1, goodHits.get());
            }

            // Now fail across >= DOWN_AFTER_PATHS distinct paths spanning > DOWN_STREAK_MS: host MUST trip.
            HostBackoff.failed(base.resolve("bad-1.tif"), "Test", "HTTP 500");
            Thread.sleep(HostBackoff.DOWN_STREAK_MS + 100);
            HostBackoff.failed(base.resolve("bad-2.tif"), "Test", "HTTP 500");
            assertFalse(HostBackoff.isDown(base), "fewer than DOWN_AFTER_PATHS distinct paths should not trip yet");
            HostBackoff.failed(base.resolve("bad-3.tif"), "Test", "HTTP 500");
            assertTrue(HostBackoff.isDown(base), "500s across distinct paths must trip host backoff");
            assertThrows(MissingTerrainException.class, () -> HostBackoff.reachable(good, "Test"));

            // Wait for recovery window; even when a broken URL triggers the probe first, HostBackoff probes
            // the remembered healthy URL and brings the host back up!
            Thread.sleep(HostBackoff.FIRST_DOWN_MS + 150);
            assertThrows(MissingTerrainException.class, () -> HostBackoff.reachable(singleBad, "Test"));
            for (int i = 0; i < 100 && HostBackoff.isDown(base); i++) Thread.sleep(20);
            assertFalse(HostBackoff.isDown(base), "probe should use known healthy path instead of staying stuck on broken URL");
        } finally {
            HostBackoff.clear(base);
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void rangeFilesRejectsOversizedHttp200WithoutOomOrIntOverflow() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/huge-header")) {
                // Advertise a 3 GiB (> Integer.MAX_VALUE) HTTP 200 ignoring Range; never send 3 GiB!
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(3L << 30));
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            if (path.equals("/chunked-oversized")) {
                // Chunked HTTP 200 (no Content-Length) streaming beyond the cap.
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    byte[] chunk = new byte[4096];
                    for (int i = 0; i < 64; i++) out.write(chunk); // 256 KiB > 32 KiB test cap
                } catch (Exception ignored) {
                    // Client closed stream early once cap exceeded.
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
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try (RangeFiles files = new RangeFiles("Test", "test-v1", "test-oversize-io", 2, Duration.ofSeconds(5), 32_768L)) {
            HostBackoff.clear(base);
            // 1. > 2 GiB Content-Length rejected before buffering (no OOM, no int overflow).
            assertThrows(MissingTerrainException.class, () -> files.fetchResult(base.resolve("huge-header"), 0, 1024));
            assertFalse(HostBackoff.isDown(base), "oversized HTTP 200 guard does not trip host outage");

            // 2. > 2 GiB offset with HTTP 200 rejected without (int) offset overflow.
            assertThrows(MissingTerrainException.class, () -> files.fetchResult(base.resolve("small-200"), 3L << 30, 16));

            // 3. Chunked HTTP 200 exceeding cap is bounded and rejected safely.
            assertThrows(MissingTerrainException.class, () -> files.fetchResult(base.resolve("chunked-oversized"), 0, 1024));

            // 4. Legitimate small HTTP 200 still slices [offset, offset + length) accurately.
            RangeFiles.FetchResult ok = files.fetchResult(base.resolve("small-200"), 10, 5);
            assertTrue(ok.isOk());
            assertArrayEquals(new byte[] {10, 11, 12, 13, 14}, ok.bytes());
        } finally {
            HostBackoff.clear(base);
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void floatCogs403RecoversAfterTtlWithoutPoisoningDiskWhile404PersistsNone(@TempDir Path cache) throws Exception {
        byte[] cog = syntheticFloatCog(42.5f);
        AtomicBoolean denyTransient = new AtomicBoolean(true);
        AtomicInteger transientHits = new AtomicInteger();
        AtomicInteger missing404Hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/cogs/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/cogs/".length());
            if (path.equals("file-404.tif")) {
                missing404Hits.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (path.equals("file-403.tif")) {
                transientHits.incrementAndGet();
                if (denyTransient.get()) {
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                    return;
                }
                String range = exchange.getRequestHeaders().getFirst("Range");
                String[] span = range.substring("bytes=".length()).split("-");
                int from = Integer.parseInt(span[0]);
                int to = Math.min(cog.length - 1, Integer.parseInt(span[1]));
                byte[] slice = Arrays.copyOfRange(cog, from, to + 1);
                exchange.sendResponseHeaders(206, slice.length);
                exchange.getResponseBody().write(slice);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cogs/");
        try (FloatCogs cogs = new FloatCogs("TestCOG", "test-cog-v1", "test-cog-io", base,
                id -> id == 1 ? "file-403.tif" : "file-404.tif", -9999f, cache, true, 4, 2, Duration.ofSeconds(5))) {
            cogs.setForbiddenTtlNanos(TimeUnit.MILLISECONDS.toNanos(120));

            // 403 -> treated as missing without writing .none to disk, cached in memory within TTL.
            assertNull(cogs.grid(1, 0, 1005, 1995), "403 returns no grid while forbidden");
            assertEquals(1, transientHits.get());
            assertFalse(Files.exists(cache.resolve("file-403.none")), "403 must NOT persist permanent .none file");

            // Second read within TTL hits in-memory negative cache without another HTTP request.
            assertNull(cogs.grid(1, 0, 1005, 1995));
            assertEquals(1, transientHits.get(), "within TTL, 403 should be served from in-memory negative cache");

            // Server recovers; after TTL expires, FloatCogs re-fetches and succeeds!
            denyTransient.set(false);
            Thread.sleep(160);
            FloatCogs.Grid recovered = cogs.grid(1, 0, 1005, 1995);
            assertNotNull(recovered, "403 must self-heal after TTL expires");
            assertEquals(42.5, cogs.metres(recovered, 1005, 1995), 1e-4);
            assertTrue(Files.exists(cache.resolve("file-403.header")), "recovered file caches its header on disk");

            // 404 -> persists .none on disk permanently.
            assertNull(cogs.grid(2, 0, 1005, 1995));
            assertEquals(1, missing404Hits.get());
            assertTrue(Files.exists(cache.resolve("file-404.none")), "404 must persist .none marker on disk");
        } finally {
            HostBackoff.clear(base);
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    /** Builds a tiny valid uncompressed 16x16 tiled float32 GeoTIFF with constant height {@code value}. */
    private static byte[] syntheticFloatCog(float value) {
        int size = 16;
        int blockBytes = size * size * 4; // 1024 bytes
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
}
