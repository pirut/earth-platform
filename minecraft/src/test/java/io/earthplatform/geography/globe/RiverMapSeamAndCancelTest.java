package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.MissingTerrainException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies HYDRO-05 (version-pinned global-coordinate order-invariant {@link RiverMap} joins across
 * adjacent 256-px JRC tile seams, plus legacy v1 bit-for-bit preservation) and HYDRO-07 virtual-thread
 * {@code FutureTask} cancellation in {@link Drainage} and {@link RiverMap}.
 */
class RiverMapSeamAndCancelTest {
    private static final int TILE = 256;
    private static final int MARGIN = 56;
    private static final int SPAN = TILE + 2 * MARGIN;

    private static int[] extractWindow(int[] world, int worldW, int offsetX, int offsetY) {
        int[] window = new int[SPAN * SPAN];
        for (int j = 0; j < SPAN; j++) {
            System.arraycopy(world, (offsetY + j) * worldW + offsetX, window, j * SPAN, SPAN);
        }
        return window;
    }

    @Test
    void horizontalAdjacentWindowsMatchBitForBitAcrossSeamInV2AndDifferInV1() {
        int worldW = TILE * 2 + 2 * MARGIN;
        int worldH = SPAN;
        int[] world = new int[worldW * worldH];
        int seamX = MARGIN + TILE; // tile 0 right edge / tile 1 left edge in world grid

        // River 1: horizontal river with a 24-px gap straddling the seam.
        for (int x = seamX - 90; x <= seamX + 90; x++) {
            if (x < seamX - 12 || x >= seamX + 12) world[140 * worldW + x] = 80;
        }
        // River 2: diagonal river with a multi-hop glimpse straddling the seam.
        for (int d = -70; d <= 70; d++) {
            boolean leftSeg = d <= -18;
            boolean glimpse = d >= -2 && d <= 1; // short glimpse (< LONG)
            boolean rightSeg = d >= 18;
            if (leftSeg || glimpse || rightSeg) {
                int x = seamX + d;
                int y = 210 + d / 2;
                world[y * worldW + x] = 90;
            }
        }
        // River 3: opposing slight bend across the seam (tests order invariance).
        for (int x = seamX - 60; x <= seamX - 10; x++) world[280 * worldW + x] = 85;
        for (int x = seamX + 10; x <= seamX + 60; x++) world[282 * worldW + x] = 85;

        int[] win0 = extractWindow(world, worldW, 0, 0);
        int[] win1 = extractWindow(world, worldW, TILE, 0);

        int baseGlobalX = 10_240 - MARGIN;
        int baseGlobalY = 20_480 - MARGIN;

        // Global v2: adjacent windows MUST agree bit-for-bit across the entire overlapping seam strip!
        float[] f0 = RiverMap.trace(win0, null, SPAN, baseGlobalX, baseGlobalY, true);
        float[] f1 = RiverMap.trace(win1, null, SPAN, baseGlobalX + TILE, baseGlobalY, true);

        int bridgedPixels = 0;
        // Overlap in window coordinates: win0 x in [TILE .. SPAN - 1] corresponds to win1 x in [0 .. 2*MARGIN - 1].
        // Check a generous interior seam strip (20 px on each side of the tile boundary).
        for (int y = MARGIN; y < MARGIN + TILE; y++) {
            for (int dx = -20; dx < 20; dx++) {
                int x0 = MARGIN + TILE + dx;
                int x1 = MARGIN + dx;
                float a = f0[y * SPAN + x0];
                float b = f1[y * SPAN + x1];
                assertEquals(Float.floatToRawIntBits(a), Float.floatToRawIntBits(b),
                        "v2 horizontal seam mismatch at dx=" + dx + ", y=" + y + ": " + a + " vs " + b);
                if (dx >= -10 && dx < 10 && a >= RiverMap.EDGE) bridgedPixels++;
            }
        }
        assertTrue(bridgedPixels > 20, "test scene must bridge rivers across the seam, got " + bridgedPixels);

        // Confirm legacy v1 actually shears across the same seam (proving this test exercises meander phase).
        float[] v1_0 = RiverMap.trace(win0, null, SPAN, baseGlobalX, baseGlobalY, false);
        float[] v1_1 = RiverMap.trace(win1, null, SPAN, baseGlobalX + TILE, baseGlobalY, false);
        int v1Diffs = 0;
        for (int y = MARGIN; y < MARGIN + TILE; y++) {
            for (int dx = -20; dx < 20; dx++) {
                if (Float.floatToRawIntBits(v1_0[y * SPAN + MARGIN + TILE + dx])
                        != Float.floatToRawIntBits(v1_1[y * SPAN + MARGIN + dx])) {
                    v1Diffs++;
                }
            }
        }
        assertTrue(v1Diffs > 0, "legacy v1 should exhibit phase shift across the 256-px tile seam");
    }

    @Test
    void verticalAdjacentWindowsMatchBitForBitAcrossSeamInV2() {
        int worldW = SPAN;
        int worldH = TILE * 2 + 2 * MARGIN;
        int[] world = new int[worldW * worldH];
        int seamY = MARGIN + TILE;

        // Vertical river with a 24-px gap across the horizontal tile boundary.
        for (int y = seamY - 90; y <= seamY + 90; y++) {
            if (y < seamY - 12 || y >= seamY + 12) world[y * worldW + 140] = 80;
        }
        // Steep diagonal river with a glimpse straddling the horizontal seam.
        for (int d = -70; d <= 70; d++) {
            if (d <= -16 || (d >= -1 && d <= 2) || d >= 16) {
                int x = 220 + d / 2;
                int y = seamY + d;
                world[y * worldW + x] = 90;
            }
        }

        int[] win0 = extractWindow(world, worldW, 0, 0);
        int[] win1 = extractWindow(world, worldW, 0, TILE);

        int baseGlobalX = 30_720 - MARGIN;
        int baseGlobalY = 40_960 - MARGIN;

        float[] f0 = RiverMap.trace(win0, null, SPAN, baseGlobalX, baseGlobalY, true);
        float[] f1 = RiverMap.trace(win1, null, SPAN, baseGlobalX, baseGlobalY + TILE, true);

        int bridgedPixels = 0;
        for (int x = MARGIN; x < MARGIN + TILE; x++) {
            for (int dy = -20; dy < 20; dy++) {
                int y0 = MARGIN + TILE + dy;
                int y1 = MARGIN + dy;
                float a = f0[y0 * SPAN + x];
                float b = f1[y1 * SPAN + x];
                assertEquals(Float.floatToRawIntBits(a), Float.floatToRawIntBits(b),
                        "v2 vertical seam mismatch at x=" + x + ", dy=" + dy + ": " + a + " vs " + b);
                if (dy >= -10 && dy < 10 && a >= RiverMap.EDGE) bridgedPixels++;
            }
        }
        assertTrue(bridgedPixels > 20, "vertical test scene must bridge rivers across the seam, got " + bridgedPixels);
    }

    @Test
    void antimeridianWrapSeamMatchesBitForBitInV2() {
        int worldW = TILE * 2 + 2 * MARGIN;
        int worldH = SPAN;
        int[] world = new int[worldW * worldH];
        int seamX = MARGIN + TILE;

        for (int x = seamX - 80; x <= seamX + 80; x++) {
            if (x < seamX - 10 || x >= seamX + 10) world[180 * worldW + x] = 85;
        }
        int[] winWest = extractWindow(world, worldW, 0, 0);
        int[] winEast = extractWindow(world, worldW, TILE, 0);

        int westOriginX = SurfaceWaterSource.ACROSS - TILE - MARGIN;
        int eastOriginX = -MARGIN;
        int originY = 50_000 - MARGIN;

        float[] fWest = RiverMap.trace(winWest, null, SPAN, westOriginX, originY, true);
        float[] fEast = RiverMap.trace(winEast, null, SPAN, eastOriginX, originY, true);

        for (int y = MARGIN; y < MARGIN + TILE; y++) {
            for (int dx = -20; dx < 20; dx++) {
                assertEquals(
                        Float.floatToRawIntBits(fWest[y * SPAN + MARGIN + TILE + dx]),
                        Float.floatToRawIntBits(fEast[y * SPAN + MARGIN + dx]),
                        "antimeridian seam mismatch at dx=" + dx + ", y=" + y);
            }
        }
    }

    @Test
    void legacyV1OverloadPreservesExactPrePrBehavior() {
        int[] occurrence = new int[SPAN * SPAN];
        for (int x = 60; x < 300; x++) {
            if (x < 160 || x >= 184) occurrence[180 * SPAN + x] = 80;
        }
        float[] legacyDefault = RiverMap.trace(occurrence, SPAN);
        float[] explicitV1 = RiverMap.trace(occurrence, null, SPAN, 12_345, 67_890, false);
        assertArrayEquals(legacyDefault, explicitV1, "v1 must ignore global origin and match legacy trace bit-for-bit");
    }

    @Test
    void drainageCancelsRainFutureWhenTileLoadFails() throws Exception {
        CountDownLatch rainStarted = new CountDownLatch(1);
        CountDownLatch rainInterrupted = new CountDownLatch(1);
        Drainage drainage = new Drainage(
                (zoom, x, y) -> {
                    try { rainStarted.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    throw new MissingTerrainException("simulated tile failure");
                },
                (lat, lon) -> {
                    rainStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException interrupted) {
                        rainInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return 800.0;
                },
                4);

        assertThrows(MissingTerrainException.class,
                () -> drainage.links(45.0, 10.0, 45.1, 10.1, (la, lo, tla, tlo, area) -> {}));
        assertTrue(rainInterrupted.await(5, TimeUnit.SECONDS),
                "rain virtual thread should be cancelled when tile loading fails");
    }

    @Test
    void riverMapCancelsStreamsFutureWhenSurfaceWaterFails() throws Exception {
        CountDownLatch streamsStarted = new CountDownLatch(1);
        CountDownLatch streamsInterrupted = new CountDownLatch(1);
        Drainage blockingDrainage = new Drainage(
                (zoom, x, y) -> {
                    streamsStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException interrupted) {
                        streamsInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new MissingTerrainException("interrupted");
                    }
                    return new float[TerrariumTileSource.TILE_SIZE * TerrariumTileSource.TILE_SIZE];
                },
                (lat, lon) -> 800.0,
                4);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/water/", exchange -> {
            try { streamsStarted.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/water/");
            SurfaceWaterSource failingSource = new SurfaceWaterSource(base, null, true, 4, 1, Duration.ofSeconds(5));
            RiverMap map = new RiverMap(failingSource, blockingDrainage, 4, true);

            assertThrows(MissingTerrainException.class, () -> map.strength(45.0, 10.0));
            assertTrue(streamsInterrupted.await(5, TimeUnit.SECONDS),
                    "streams virtual thread should be cancelled when surface water fails");
        } finally {
            server.stop(0);
        }
    }
}
