package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.MissingTerrainException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies HYDRO-05 (version-pinned global-coordinate order-invariant {@link RiverMap} joins across
 * adjacent 256-px JRC tile seams, one-sided hop chains, antimeridian wrap, tie-breaking, and legacy v1
 * bit-for-bit preservation) and HYDRO-07 virtual-thread cancellation, interruptible shared single-flight
 * waits, and CPU interruption checkpoints in {@link Drainage} and {@link RiverMap}.
 */
class RiverMapSeamAndCancelTest {
    private static final int TILE = 256;
    private static final int LEGACY_MARGIN = 56;
    private static final int LEGACY_SPAN = TILE + 2 * LEGACY_MARGIN;
    private static final int V2_MARGIN = RiverMap.V2_MARGIN;
    private static final int V2_SPAN = RiverMap.V2_SPAN;

    private static int[] extractWindow(int[] world, int worldW, int offsetX, int offsetY, int span) {
        int[] window = new int[span * span];
        for (int j = 0; j < span; j++) {
            System.arraycopy(world, (offsetY + j) * worldW + offsetX, window, j * span, span);
        }
        return window;
    }

    private static String sha256FloatBits(float[] values) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ByteBuffer buf = ByteBuffer.allocate(4096);
        for (float v : values) {
            if (buf.remaining() < 4) {
                md.update(buf.array(), 0, buf.position());
                buf.clear();
            }
            buf.putInt(Float.floatToRawIntBits(v));
        }
        if (buf.position() > 0) md.update(buf.array(), 0, buf.position());
        return HexFormat.of().formatHex(md.digest());
    }

    @Test
    void horizontalAdjacentWindowsMatchBitForBitAcrossSeamInV2AndDifferInV1() {
        int worldW = TILE * 2 + 2 * V2_MARGIN;
        int worldH = V2_SPAN;
        int[] world = new int[worldW * worldH];
        int seamX = V2_MARGIN + TILE;

        // River 1: horizontal river with a 24-px gap straddling the seam.
        for (int x = seamX - 90; x <= seamX + 90; x++) {
            if (x < seamX - 12 || x >= seamX + 12) world[(V2_MARGIN + 84) * worldW + x] = 80;
        }
        // River 2: diagonal river with a multi-hop glimpse straddling the seam.
        for (int d = -70; d <= 70; d++) {
            boolean leftSeg = d <= -18;
            boolean glimpse = d >= -2 && d <= 1; // short glimpse (< LONG)
            boolean rightSeg = d >= 18;
            if (leftSeg || glimpse || rightSeg) {
                int x = seamX + d;
                int y = V2_MARGIN + 154 + d / 2;
                world[y * worldW + x] = 90;
            }
        }
        // River 3: opposing slight bend across the seam (tests order invariance).
        for (int x = seamX - 60; x <= seamX - 10; x++) world[(V2_MARGIN + 224) * worldW + x] = 85;
        for (int x = seamX + 10; x <= seamX + 60; x++) world[(V2_MARGIN + 226) * worldW + x] = 85;

        int[] win0 = extractWindow(world, worldW, 0, 0, V2_SPAN);
        int[] win1 = extractWindow(world, worldW, TILE, 0, V2_SPAN);

        int baseGlobalX = 10_240 - V2_MARGIN;
        int baseGlobalY = 20_480 - V2_MARGIN;

        float[] f0 = RiverMap.trace(win0, null, V2_SPAN, baseGlobalX, baseGlobalY, true);
        float[] f1 = RiverMap.trace(win1, null, V2_SPAN, baseGlobalX + TILE, baseGlobalY, true);

        int bridgedPixels = 0;
        for (int y = V2_MARGIN; y < V2_MARGIN + TILE; y++) {
            for (int dx = -20; dx < 20; dx++) {
                int x0 = V2_MARGIN + TILE + dx;
                int x1 = V2_MARGIN + dx;
                float a = f0[y * V2_SPAN + x0];
                float b = f1[y * V2_SPAN + x1];
                assertEquals(Float.floatToRawIntBits(a), Float.floatToRawIntBits(b),
                        "v2 horizontal seam mismatch at dx=" + dx + ", y=" + y + ": " + a + " vs " + b);
                if (dx >= -10 && dx < 10 && a >= RiverMap.EDGE) bridgedPixels++;
            }
        }
        assertTrue(bridgedPixels > 20, "test scene must bridge rivers across the seam, got " + bridgedPixels);

        // Confirm legacy v1 actually shears across the same seam (proving this test exercises meander phase).
        int legacyOffset = V2_MARGIN - LEGACY_MARGIN;
        int[] leg0 = extractWindow(world, worldW, legacyOffset, legacyOffset, LEGACY_SPAN);
        int[] leg1 = extractWindow(world, worldW, legacyOffset + TILE, legacyOffset, LEGACY_SPAN);
        float[] v1_0 = RiverMap.trace(leg0, null, LEGACY_SPAN, 10_240 - LEGACY_MARGIN, 20_480 - LEGACY_MARGIN, false);
        float[] v1_1 = RiverMap.trace(leg1, null, LEGACY_SPAN, 10_240 + TILE - LEGACY_MARGIN, 20_480 - LEGACY_MARGIN, false);
        int v1Diffs = 0;
        for (int y = LEGACY_MARGIN; y < LEGACY_MARGIN + TILE; y++) {
            for (int dx = -20; dx < 20; dx++) {
                if (Float.floatToRawIntBits(v1_0[y * LEGACY_SPAN + LEGACY_MARGIN + TILE + dx])
                        != Float.floatToRawIntBits(v1_1[y * LEGACY_SPAN + LEGACY_MARGIN + dx])) {
                    v1Diffs++;
                }
            }
        }
        assertTrue(v1Diffs > 0, "legacy v1 should exhibit phase shift across the 256-px tile seam");
    }

    @Test
    void verticalAdjacentWindowsMatchBitForBitAcrossSeamInV2() {
        int worldW = V2_SPAN;
        int worldH = TILE * 2 + 2 * V2_MARGIN;
        int[] world = new int[worldW * worldH];
        int seamY = V2_MARGIN + TILE;

        // Vertical river with a 24-px gap across the horizontal tile boundary.
        for (int y = seamY - 90; y <= seamY + 90; y++) {
            if (y < seamY - 12 || y >= seamY + 12) world[y * worldW + (V2_MARGIN + 84)] = 80;
        }
        // Steep diagonal river with a glimpse straddling the horizontal seam.
        for (int d = -70; d <= 70; d++) {
            if (d <= -16 || (d >= -1 && d <= 2) || d >= 16) {
                int x = V2_MARGIN + 164 + d / 2;
                int y = seamY + d;
                world[y * worldW + x] = 90;
            }
        }

        int[] win0 = extractWindow(world, worldW, 0, 0, V2_SPAN);
        int[] win1 = extractWindow(world, worldW, 0, TILE, V2_SPAN);

        int baseGlobalX = 30_720 - V2_MARGIN;
        int baseGlobalY = 40_960 - V2_MARGIN;

        float[] f0 = RiverMap.trace(win0, null, V2_SPAN, baseGlobalX, baseGlobalY, true);
        float[] f1 = RiverMap.trace(win1, null, V2_SPAN, baseGlobalX, baseGlobalY + TILE, true);

        int bridgedPixels = 0;
        for (int x = V2_MARGIN; x < V2_MARGIN + TILE; x++) {
            for (int dy = -20; dy < 20; dy++) {
                int y0 = V2_MARGIN + TILE + dy;
                int y1 = V2_MARGIN + dy;
                float a = f0[y0 * V2_SPAN + x];
                float b = f1[y1 * V2_SPAN + x];
                assertEquals(Float.floatToRawIntBits(a), Float.floatToRawIntBits(b),
                        "v2 vertical seam mismatch at x=" + x + ", dy=" + dy + ": " + a + " vs " + b);
                if (dy >= -10 && dy < 10 && a >= RiverMap.EDGE) bridgedPixels++;
            }
        }
        assertTrue(bridgedPixels > 20, "vertical test scene must bridge rivers across the seam, got " + bridgedPixels);
    }

    @Test
    void antimeridianWrapSeamMatchesBitForBitInV2() {
        int worldW = TILE * 2 + 2 * V2_MARGIN;
        int worldH = V2_SPAN;
        int[] world = new int[worldW * worldH];
        int seamX = V2_MARGIN + TILE;
        int riverY = V2_MARGIN + 124;

        for (int x = seamX - 80; x <= seamX + 80; x++) {
            if (x < seamX - 10 || x >= seamX + 10) world[riverY * worldW + x] = 85;
        }
        int[] winWest = extractWindow(world, worldW, 0, 0, V2_SPAN);
        int[] winEast = extractWindow(world, worldW, TILE, 0, V2_SPAN);

        int westOriginX = SurfaceWaterSource.ACROSS - TILE - V2_MARGIN;
        int eastOriginX = -V2_MARGIN;
        int originY = 50_000 - V2_MARGIN;

        float[] fWest = RiverMap.trace(winWest, null, V2_SPAN, westOriginX, originY, true);
        float[] fEast = RiverMap.trace(winEast, null, V2_SPAN, eastOriginX, originY, true);

        int bridgedPixels = 0;
        for (int y = V2_MARGIN; y < V2_MARGIN + TILE; y++) {
            for (int dx = -20; dx < 20; dx++) {
                float a = fWest[y * V2_SPAN + V2_MARGIN + TILE + dx];
                float b = fEast[y * V2_SPAN + V2_MARGIN + dx];
                assertEquals(Float.floatToRawIntBits(a), Float.floatToRawIntBits(b),
                        "antimeridian seam mismatch at dx=" + dx + ", y=" + y);
                if (dx >= -10 && dx < 10 && a >= RiverMap.EDGE) bridgedPixels++;
            }
        }
        assertTrue(bridgedPixels > 10, "antimeridian seam must actually bridge river pixels across seam, got " + bridgedPixels);
    }

    @Test
    void oneSidedHopChainAcrossNormalAndAntimeridianSeamsMatchesBitForBitInV2() {
        // Exact Sol R1 counterexample: LONG horizontal segment ending 70px west of the seam,
        // followed by 3px fragments (< LONG) at -42, -14, and +14. Both west and east tiles must
        // see the seed at -70, bridge all 3 hops across the seam, and agree bit-for-bit.
        int worldW = TILE * 2 + 2 * V2_MARGIN;
        int worldH = V2_SPAN;
        int[] world = new int[worldW * worldH];
        int seamX = V2_MARGIN + TILE;
        int riverY = V2_MARGIN + 128;

        for (int x = seamX - 95; x <= seamX - 70; x++) world[riverY * worldW + x] = 90;
        for (int dx = 0; dx < 3; dx++) {
            world[riverY * worldW + (seamX - 42 + dx)] = 90;
            world[riverY * worldW + (seamX - 14 + dx)] = 90;
            world[riverY * worldW + (seamX + 14 + dx)] = 90;
        }

        int[] winWest = extractWindow(world, worldW, 0, 0, V2_SPAN);
        int[] winEast = extractWindow(world, worldW, TILE, 0, V2_SPAN);

        // Case 1: Normal interior tile seam.
        assertOneSidedChainBridgesAndMatches(winWest, winEast, 10_240 - V2_MARGIN, 10_240 + TILE - V2_MARGIN, 20_480 - V2_MARGIN, "normal");

        // Case 2: Antimeridian wrap seam.
        assertOneSidedChainBridgesAndMatches(winWest, winEast, SurfaceWaterSource.ACROSS - TILE - V2_MARGIN, -V2_MARGIN, 50_000 - V2_MARGIN, "antimeridian");
    }

    private static void assertOneSidedChainBridgesAndMatches(int[] winWest, int[] winEast,
                                                             int westOriginX, int eastOriginX, int originY, String label) {
        float[] fWest = RiverMap.trace(winWest, null, V2_SPAN, westOriginX, originY, true);
        float[] fEast = RiverMap.trace(winEast, null, V2_SPAN, eastOriginX, originY, true);

        int bridgedAcrossSeam = 0;
        for (int y = V2_MARGIN; y < V2_MARGIN + TILE; y++) {
            for (int dx = -20; dx < 20; dx++) {
                float a = fWest[y * V2_SPAN + V2_MARGIN + TILE + dx];
                float b = fEast[y * V2_SPAN + V2_MARGIN + dx];
                assertEquals(Float.floatToRawIntBits(a), Float.floatToRawIntBits(b),
                        label + " one-sided hop chain mismatch at dx=" + dx + ", y=" + y + ": " + a + " vs " + b);
                if (dx >= -10 && dx < 10 && a >= RiverMap.EDGE) bridgedAcrossSeam++;
            }
        }
        assertTrue(bridgedAcrossSeam > 10,
                label + " one-sided hop chain must bridge non-zero pixels across seam, got " + bridgedAcrossSeam);
    }

    @Test
    void mirroredEqualScoreTiesAndReversedEnumerationMatchBitForBitInV2() {
        int span = V2_SPAN;
        int[] occurrence = new int[span * span];

        // 1. Symmetric Y-fork: horizontal stem heading east to two identically scored mirrored targets at y +/- 6.
        int yFork = V2_MARGIN + 80;
        for (int x = V2_MARGIN + 40; x <= V2_MARGIN + 70; x++) occurrence[yFork * span + x] = 90;
        for (int x = V2_MARGIN + 90; x <= V2_MARGIN + 120; x++) {
            occurrence[(yFork - 6) * span + x] = 90;
            occurrence[(yFork + 6) * span + x] = 90;
        }

        // 2. Facing ends with asymmetric widths (1-px west segment vs 2-px east segment) across a 20-px gap.
        int yFacing = V2_MARGIN + 150;
        for (int x = V2_MARGIN + 40; x <= V2_MARGIN + 80; x++) occurrence[yFacing * span + x] = 90;
        for (int x = V2_MARGIN + 100; x <= V2_MARGIN + 140; x++) {
            occurrence[yFacing * span + x] = 90;
            occurrence[(yFacing + 1) * span + x] = 90;
        }

        // 3. Glimpse with equal projection tie (vertical 2-px pair orthogonal to horizontal approach).
        int yGlimpse = V2_MARGIN + 210;
        for (int x = V2_MARGIN + 40; x <= V2_MARGIN + 70; x++) occurrence[yGlimpse * span + x] = 90;
        occurrence[(yGlimpse - 1) * span + (V2_MARGIN + 92)] = 90;
        occurrence[yGlimpse * span + (V2_MARGIN + 92)] = 90;
        occurrence[(yGlimpse + 1) * span + (V2_MARGIN + 92)] = 90;
        for (int x = V2_MARGIN + 114; x <= V2_MARGIN + 144; x++) occurrence[yGlimpse * span + x] = 90;

        float[] forward = RiverMap.trace(occurrence, null, span, 12_345, 67_890, true);
        float[] reversed = RiverMap.traceGlobalReversed(occurrence, span, 12_345, 67_890);

        int bridged = 0;
        for (int k = 0; k < forward.length; k++) {
            assertEquals(Float.floatToRawIntBits(forward[k]), Float.floatToRawIntBits(reversed[k]),
                    "v2 global join must be invariant to reversed end/candidate scan order at pixel " + k);
        }
        for (int x = V2_MARGIN + 72; x < V2_MARGIN + 89; x++) {
            for (int dy = -6; dy <= 6; dy++) {
                if (forward[(yFork + dy) * span + x] >= RiverMap.EDGE) bridged++;
            }
        }
        assertTrue(bridged > 10, "mirrored tie fixture must bridge across gap, got " + bridged);
    }

    @Test
    void legacyV1OverloadPreservesPinnedPrePrReferenceFixture() throws Exception {
        int[] occurrence = new int[LEGACY_SPAN * LEGACY_SPAN];
        for (int x = 60; x < 300; x++) {
            if (x < 160 || x >= 184) occurrence[180 * LEGACY_SPAN + x] = 80;
        }
        float[] legacyDefault = RiverMap.trace(occurrence, LEGACY_SPAN);
        float[] explicitV1 = RiverMap.trace(occurrence, null, LEGACY_SPAN, 12_345, 67_890, false);
        assertArrayEquals(legacyDefault, explicitV1, "v1 must ignore global origin and match legacy trace bit-for-bit");

        // Verify concrete pre-PR v1 fixture invariants (non-tautological reference pin).
        int gapBridged = 0;
        for (int y = 175; y <= 185; y++) {
            for (int x = 160; x < 184; x++) {
                if (legacyDefault[y * LEGACY_SPAN + x] >= RiverMap.EDGE) gapBridged++;
            }
        }
        assertEquals(24, gapBridged, "pre-PR v1 bridged pixel count across 24-px gap");
        assertEquals("1c9cf7b60685f8ff7541588fd3b248ce02e0a67b6216c931689a8c149325fcc4",
                sha256FloatBits(legacyDefault),
                "pre-PR v1 output digest must remain bit-for-bit frozen");
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

    @Test
    void drainageFollowerAndOwnerDetachPromptlyOnCancelWithoutFailingSharedProducer() throws Exception {
        // Case A: Follower cancels while owner keeps waiting -> follower detaches promptly, shared producer succeeds for owner.
        assertSharedRegionDetach(true);
        // Case B: Owner cancels while follower keeps waiting -> owner detaches promptly, shared producer succeeds for follower.
        assertSharedRegionDetach(false);
    }

    private static void assertSharedRegionDetach(boolean cancelFollower) throws Exception {
        CountDownLatch producerEntered = new CountDownLatch(1);
        CountDownLatch releaseProducer = new CountDownLatch(1);
        AtomicBoolean producerInterrupted = new AtomicBoolean();
        AtomicInteger tileLoads = new AtomicInteger();

        Drainage drainage = new Drainage(
                (zoom, x, y) -> {
                    tileLoads.incrementAndGet();
                    producerEntered.countDown();
                    try {
                        if (!releaseProducer.await(5, TimeUnit.SECONDS)) {
                            throw new MissingTerrainException("timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        producerInterrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw new MissingTerrainException("producer interrupted");
                    }
                    return new float[TerrariumTileSource.TILE_SIZE * TerrariumTileSource.TILE_SIZE];
                },
                (lat, lon) -> 800.0,
                4);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch followerReady = new CountDownLatch(1);
            Future<Throwable> caller1 = pool.submit(() -> {
                try {
                    drainage.links(45.0, 10.0, 45.001, 10.001, (la, lo, tla, tlo, area) -> {});
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            assertTrue(producerEntered.await(5, TimeUnit.SECONDS), "owner must enter drainage tile load");

            Future<Throwable> caller2 = pool.submit(() -> {
                followerReady.countDown();
                try {
                    drainage.links(45.0, 10.0, 45.001, 10.001, (la, lo, tla, tlo, area) -> {});
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            assertTrue(followerReady.await(5, TimeUnit.SECONDS));
            // Wait deterministically until both callers are attached to the shared in-flight region.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (drainage.inFlightWaiters() < 2 && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
            }
            assertEquals(2, drainage.inFlightWaiters(), "both callers must be attached to the same shared in-flight region");

            Future<Throwable> cancelled = cancelFollower ? caller2 : caller1;
            Future<Throwable> survivor = cancelFollower ? caller1 : caller2;

            assertTrue(cancelled.cancel(true), "cancelling waiter future must succeed");
            assertFalse(producerInterrupted.get(), "cancelling one shared waiter must NOT interrupt shared producer");

            releaseProducer.countDown();
            Throwable survivorResult = survivor.get(5, TimeUnit.SECONDS);
            assertNull(survivorResult, "surviving waiter must complete cleanly, got " + survivorResult);
            assertFalse(producerInterrupted.get(), "shared producer must have completed without interruption");
        }
    }

    @Test
    void cpuLoopsHonorThreadInterruptionCheckpoints() {
        float[] height = new float[128 * 128];
        int[] down = new int[height.length];
        int[] order = new int[height.length];
        float[] area = new float[height.length];

        Thread.currentThread().interrupt();
        try {
            assertThrows(MissingTerrainException.class, () -> Drainage.flood(height, down),
                    "Drainage.flood must abort promptly when interrupted");
        } finally {
            Thread.interrupted();
        }

        Thread.currentThread().interrupt();
        try {
            assertThrows(MissingTerrainException.class, () -> Drainage.keep(height, down, order, area),
                    "Drainage.keep must abort promptly when interrupted");
        } finally {
            Thread.interrupted();
        }

        int[] occ = new int[V2_SPAN * V2_SPAN];
        for (int x = V2_MARGIN + 40; x <= V2_MARGIN + 100; x++) {
            if (x < V2_MARGIN + 60 || x >= V2_MARGIN + 80) occ[(V2_MARGIN + 100) * V2_SPAN + x] = 90;
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(MissingTerrainException.class, () -> RiverMap.trace(occ, null, V2_SPAN, 0, 0, true),
                    "RiverMap.joinGlobal must abort promptly when interrupted");
        } finally {
            Thread.interrupted();
        }
    }
}
