package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.globe.Drainage;
import io.earthplatform.geography.globe.MemoryPressure;
import io.earthplatform.geography.globe.MemoryPressureTestBridge;
import io.earthplatform.geography.globe.RiverMap;
import io.earthplatform.geography.globe.SurfaceWaterSource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator.HydroCache;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/**
 * Focused regression tests for Wave 2 PR 6 hydrology fixes in {@link EarthGenerator}:
 * saved-world {@code hydrologyV2} version pinning, HYDRO-07 (single-flight deduplication + bounded
 * {@link MemoryPressure}-scaled {@link HydroCache}), HYDRO-04 (#254 steep-cliff shadow water filter
 * with far-LOD budget, connected gentle probes, and falling-reach preservation), HYDRO-06 (lower
 * drowned reservoir guard + {@code drownedBy} elevation-band isolation), and HYDRO-10 (shore lifting).
 */
class EarthHydrologyAuditTest {

    private static void awaitWaiters(ConcurrentHashMap<Long, CompletableFuture<String>> flights, long key, int expectedWaiters) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            CompletableFuture<String> inFlight = flights.get(key);
            if (inFlight != null && inFlight.getNumberOfDependents() >= expectedWaiters) return;
            LockSupport.parkNanos(200_000L);
        }
        CompletableFuture<String> inFlight = flights.get(key);
        fail("Expected " + expectedWaiters + " waiters attached to singleFlight future, got "
                + (inFlight == null ? "null" : inFlight.getNumberOfDependents()));
    }

    @Test
    void singleFlightRunsOnceUnderConcurrencyAndPropagatesMissingTerrainCleanly() throws Exception {
        ConcurrentHashMap<Long, CompletableFuture<String>> flights = new ConcurrentHashMap<>();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        int threads = 16;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch allStarted = new CountDownLatch(threads);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    allStarted.countDown();
                    return EarthGenerator.singleFlight(flights, 77L, () -> {
                        runs.incrementAndGet();
                        entered.countDown();
                        try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                        return "ok-77";
                    });
                }));
            }
            assertTrue(allStarted.await(5, TimeUnit.SECONDS));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            awaitWaiters(flights, 77L, threads - 1);
            release.countDown();
            for (Future<String> f : futures) {
                assertEquals("ok-77", f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, runs.get(), "concurrent callers for same key must deduplicate to a single computation");
            assertTrue(flights.isEmpty(), "in-flight map must be cleaned up after completion");

            // Failure case: MissingTerrainException must propagate unwrapped to every waiter and leave no stale future.
            int failThreads = 8;
            AtomicInteger failRuns = new AtomicInteger();
            CountDownLatch failAllStarted = new CountDownLatch(failThreads);
            CountDownLatch failEntered = new CountDownLatch(1);
            CountDownLatch failRelease = new CountDownLatch(1);
            List<Future<Throwable>> failResults = new ArrayList<>();
            for (int i = 0; i < failThreads; i++) {
                failResults.add(pool.submit(() -> {
                    failAllStarted.countDown();
                    try {
                        EarthGenerator.singleFlight(flights, 88L, () -> {
                            failRuns.incrementAndGet();
                            failEntered.countDown();
                            try { failRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                            throw new MissingTerrainException("tile offline");
                        });
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            assertTrue(failAllStarted.await(5, TimeUnit.SECONDS));
            assertTrue(failEntered.await(5, TimeUnit.SECONDS));
            awaitWaiters(flights, 88L, failThreads - 1);
            failRelease.countDown();
            for (Future<Throwable> f : failResults) {
                Throwable caught = f.get(5, TimeUnit.SECONDS);
                assertInstanceOf(MissingTerrainException.class, caught,
                        "waiters must receive unwrapped MissingTerrainException, got " + caught);
            }
            assertEquals(1, failRuns.get());
            assertTrue(flights.isEmpty(), "in-flight map must be empty after failure");

            // Next call after failure must retry and succeed.
            String recovered = EarthGenerator.singleFlight(flights, 88L, () -> "recovered");
            assertEquals("recovered", recovered);
        }
    }

    @Test
    void hydroCachePromotionStaysBoundedAcrossRotations() {
        MemoryPressureTestBridge.reset();
        try {
            HydroCache<Integer> cache = new HydroCache<>(64);
            // Alternate full put() batches with get() hits on every key from the previous (old) batch
            // across 8 rotations on one thread; young promotions must never exceed capacity().
            for (int round = 0; round < 8; round++) {
                long base = round * 64L;
                for (long k = base; k < base + 64; k++) {
                    cache.put(k, (int) k);
                    assertTrue(cache.size() <= 2 * cache.capacity(),
                            "size exceeded 2*capacity during put in round " + round + ": " + cache.size());
                }
                if (round > 0) {
                    long prevBase = (round - 1) * 64L;
                    for (long k = prevBase; k < prevBase + 64; k++) {
                        assertEquals((int) k, cache.get(k), "old generation key must remain readable");
                        assertTrue(cache.size() <= 2 * cache.capacity(),
                                "size exceeded 2*capacity during old-hit promotion in round " + round + ": " + cache.size());
                    }
                }
            }
        } finally {
            MemoryPressureTestBridge.reset();
        }
    }

    @Test
    void hydroCachePutOrReplaceNeverResurrectsStaleOldValue() {
        MemoryPressureTestBridge.reset();
        try {
            HydroCache<String> cache = new HydroCache<>(16);
            cache.putOrReplace(1L, "stale");
            // Fill young to trigger rotation so key 1L moves into old.
            for (long k = 2; k <= 17; k++) cache.put(k, "fill-" + k);
            assertEquals("stale", cache.get(1L));

            // Rotate again so 1L is back in old, then replace it with "fresh".
            for (long k = 18; k <= 34; k++) cache.put(k, "fill-" + k);
            cache.putOrReplace(1L, "fresh");
            assertEquals("fresh", cache.get(1L), "putOrReplace must immediately shadow stale value");

            // Rotate again so "fresh" moves to old (not "stale"), and verify "fresh" is served.
            for (long k = 35; k <= 51; k++) cache.put(k, "fill-" + k);
            assertEquals("fresh", cache.get(1L), "rotation after putOrReplace must never resurrect stale value");

            // Shed clears old generation; subsequent get must either return "fresh" (if kept) or null, never "stale".
            cache.shed();
            String afterShed = cache.get(1L);
            assertTrue(afterShed == null || "fresh".equals(afterShed),
                    "shed must never resurrect stale value, got " + afterShed);
        } finally {
            MemoryPressureTestBridge.reset();
        }
    }

    @Test
    void hydroCacheStaysBoundedAndRespondsToMemoryPressureHookAutomatically() throws Exception {
        MemoryPressureTestBridge.reset();
        try {
            HydroCache<Integer> cache = new HydroCache<>(4096);
            for (long k = 0; k < 20_000; k++) {
                cache.put(k, (int) k);
            }
            assertTrue(cache.size() <= 4096 * 2,
                    "bounded cache must never approach legacy 1<<20 ceiling, got " + cache.size());

            // Verify EarthGenerator's own reaches cache and automatic MemoryPressure.Hook wiring.
            EarthShorelineTest.bootRegistries();
            try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                    (dx, dz) -> 20.0, (dx, dz) -> WorldCoverSource.GRASSLAND)) {
                EarthGenerator gen = world.generator;
                assertEquals(4096, gen.reaches.capacity(), "reaches base capacity should be 4096");
                assertEquals(1 << 16, gen.openSea.capacity(), "openSea base capacity should be 65536");

                // Populate gen.reaches ABOVE its 25% memory-pressure budget (1024), and gen.crests above 8192.
                EarthGenerator.Reaches dummyReach = new EarthGenerator.Reaches(
                        new double[]{0}, new boolean[]{false}, new float[256], new long[4], new long[4], new long[4], null);
                for (long k = 0; k < 3_000; k++) gen.reaches.put(k, dummyReach);
                for (long k = 0; k < 10_000; k++) gen.crests.put(k, Boolean.TRUE);
                assertTrue(gen.reaches.size() > 1024, "precondition: reaches must exceed 25% budget before pressure");
                assertTrue(gen.crests.size() > 8192, "precondition: crests must exceed 25% budget before pressure");

                // Simulate memory pressure high (85% of 4 GiB) -> capacity drops to 25% and hook trims automatically!
                MemoryPressureTestBridge.triggerHigh();
                MemoryPressureTestBridge.awaitShrinks();
                assertTrue(MemoryPressure.high(), "MemoryPressure should enter high state");
                assertEquals(1024, cache.capacity(), "cache capacity should scale to 25% under MemoryPressure");
                assertEquals(1024, gen.reaches.capacity(), "EarthGenerator.reaches capacity should scale to 25%");
                assertEquals(16384, gen.openSea.capacity(), "EarthGenerator.openSea capacity should scale to 25%");

                // Assert BEFORE any manual gen.shedCaches() call that the registered MemoryPressure.Hook trimmed them!
                assertTrue(gen.reaches.size() <= 1024,
                        "automatic MemoryPressure.Hook must trim gen.reaches to <= 1024, got " + gen.reaches.size());
                assertTrue(gen.crests.size() <= 8192,
                        "automatic MemoryPressure.Hook must trim gen.crests to <= 8192, got " + gen.crests.size());

                // Standalone cache (not hooked) trims on explicit shed().
                cache.shed();
                assertTrue(cache.size() <= 1024, "shed() must immediately trim cache to scaled budget, got " + cache.size());
            }
        } finally {
            MemoryPressureTestBridge.reset();
        }
    }

    @Test
    void steepCliffShadowFilterRejectsUnbackedRockWallShadowAndPreservesBackedWater() {
        // Isolated steep-cliff shadow on a sheer mountain wall (#254 Trollveggen): REJECTED.
        assertTrue(EarthGenerator.isSteepCliffShadow(1.4, 500.0, false, false, false, false, false),
                "unbacked sheer mountain cliff shadow must be rejected");

        // Real waterfall / alpine cascade backed by Drainage: PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(1.4, 500.0, false, false, false, true, false),
                "drainage-backed waterfall on steep cliff must be preserved");

        // Real mapped falling reach (e.g., cascade below Drainage threshold with gentle reach >32 blocks away): PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(1.4, 500.0, false, true, false, false, false),
                "falling reach on steep cliff must be preserved");

        // Surveyed regional river: PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(1.4, 500.0, false, false, true, false, false),
                "surveyed river on steep slope must be preserved");

        // Valley river bank / alpine lake shore / waterfall lip continuously connected to gentler water reach: PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(1.2, 500.0, false, false, false, false, true),
                "steep water connected to gentler water reach must be preserved");

        // Retreating glacier front / proglacial lake: PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(1.4, 500.0, true, false, false, false, false),
                "glacial water must never be rejected as cliff shadow");

        // Gentle slope or non-mountain terrain: PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(0.5, 500.0, false, false, false, false, false));
        assertFalse(EarthGenerator.isSteepCliffShadow(1.4, 60.0, false, false, false, false, false));
    }

    @Test
    void sheerCliffShadowPatchRejectedEndToEndWhileValleyRiverStaysWet() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Broad oblique NW mountain rock-wall shadow band (spanning > 1500 blocks along the ridge so
        // strayWater does not treat it as a small isolated patch): dx + dz < -280 rises at 0.55 m/block
        // along both axes above a 220 m shelf (s in [-280, -140]) and 20 m valley floor (s > -140, relief > 300 m).
        // At (-143, -143) (s = -286), steep = hypot(0.55, 0.55) = 0.778 > 0.7 while metres = 223.3 m and
        // surface >= 220 m (SEA + 0.5 + metres - surface <= 3.8 <= 4), so pre-PR line 730 leaves it WET
        // while HYDRO-04 in v2 strips it to DRY. Meanwhile the flat valley river stays WET.
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> {
                    double s = dx + dz;
                    if (s < -280) return 220.0 + (-280.0 - s) * 0.55;
                    if (s <= 0) return 220.0;
                    if (s < 200) return 220.0 - s;
                    return 20.0;
                },
                (dx, dz) -> {
                    int s = dx + dz;
                    if (s >= -480 && s <= -274 && Math.abs(dx - dz) <= 1500) return WorldCoverSource.WATER;
                    if (s >= 1200 && s <= 1320) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                })) {
            // Legacy generator (hydrologyV2 == false) preserves pre-PR cliff water where metres - surface <= 4.
            int[] legacyCliff = new int[2];
            world.ground(-143, -143, legacyCliff);
            assertNotEquals(SurfaceGenerator.DRY, legacyCliff[1],
                    "legacy generator without hydrologyV2 pin must preserve pre-PR oblique cliff output");

            // V2 generator rejects the unbacked steep cliff shadow while preserving the valley river.
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            v2.enableHydrologyV2();

            int[] cliffOut = new int[2];
            v2.ground(world.homeX - 143, world.homeZ - 143, cliffOut);
            assertEquals(SurfaceGenerator.DRY, cliffOut[1],
                    "unbacked steep-cliff shadow water on oblique 0.778 slope mountain wall must stay dry in v2");

            int[] valleyOut = new int[2];
            v2.ground(world.homeX + 630, world.homeZ + 630, valleyOut);
            assertNotEquals(SurfaceGenerator.DRY, valleyOut[1],
                    "flat valley river below the mountain wall must stay wet");
            assertTrue(valleyOut[1] > valleyOut[0], "valley river water surface must sit above its bed");
        }
    }

    @Test
    void cliffShadowCoarseFarLodNeverCallsRiversDrained() throws Exception {
        EarthShorelineTest.bootRegistries();
        AtomicInteger surfaceWaterRequests = new AtomicInteger();
        AtomicInteger drainageTileCalls = new AtomicInteger();

        HttpServer waterServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        waterServer.createContext("/water/", exchange -> {
            surfaceWaterRequests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        waterServer.start();
        // Concave cliff foot at dz = -200 (y = 400 m; rises north at 2.0 m/block, flat south to dz = 400, then
        // drops to 20 m valley so relief > MOUNTAINS). At far LOD level = 6 (voxel = 64 > RIVER_VOXEL),
        // (0, -200) has steep = 1.0 > 0.7 and lowest = 400 m (metres - surface <= 0.5 <= 4), so pre-PR leaves it
        // WET while v2 enters cliffShadowWater and strips it to DRY without ever calling rivers.drained().
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> {
                    if (dz < -200) return 400.0 + (-200 - dz) * 2.0;
                    if (dz <= 400) return 400.0;
                    return 20.0;
                },
                (dx, dz) -> (dz >= -600 && dz <= -160 && Math.abs(dx) <= 100) ? WorldCoverSource.WATER : WorldCoverSource.BARE);
             SurfaceWaterSource sws = new SurfaceWaterSource(
                     URI.create("http://127.0.0.1:" + waterServer.getAddress().getPort() + "/water/"),
                     null, true, 4, 1, Duration.ofSeconds(5))) {
            Drainage spyDrainage = new Drainage(
                    (zoom, tx, ty) -> {
                        drainageTileCalls.incrementAndGet();
                        throw new AssertionError("coarse far-LOD cliffShadowWater must never trigger Drainage");
                    },
                    (lat, lon) -> 1000.0, 4);
            RiverMap spyRivers = new RiverMap(sws, spyDrainage, 8, true);

            // Legacy at level = 6 (voxel = 64) stays wet, proving pre-PR line 730 did not short-circuit cliffShadowWater.
            EarthGenerator legacyGen = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null, null);
            int[] legacyFar = new int[2];
            legacyGen.farGround(6, world.homeX, world.homeZ - 200, legacyFar);
            assertNotEquals(SurfaceGenerator.DRY, legacyFar[1],
                    "precondition: legacy coarse far-LOD must leave concave cliff-foot shadow wet so v2 path is exercised");

            EarthGenerator v2Gen = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null, spyRivers);
            v2Gen.enableHydrologyV2();
            int[] farOut = new int[2];
            v2Gen.farGround(6, world.homeX, world.homeZ - 200, farOut);
            assertEquals(SurfaceGenerator.DRY, farOut[1], "v2 coarse far-LOD cliff shadow must be stripped");
            assertEquals(0, surfaceWaterRequests.get(), "coarse far-LOD (> RIVER_VOXEL) must never query SurfaceWaterSource");
            assertEquals(0, drainageTileCalls.get(), "coarse far-LOD (> RIVER_VOXEL) must never query Drainage");
        } finally {
            waterServer.stop(0);
        }
    }

    @Test
    void cliffShadowDisconnectedGentlePondAcrossDryRockDoesNotExemptCliffShadow() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Broad oblique 0.778-slope mountain wall (dx + dz < -280) meeting a flat 220 m bench (s in [-280, -140])
        // above a 20 m valley (s > -140, so relief > MOUNTAINS). Bands span > 1500 blocks along ridge so
        // strayWater does not strip them before HYDRO-04 runs.
        // At (-143, -143) (s = -286), metres = 223.3 m, surface >= 220 m (legacy stays WET).
        // Case A: dry rock strip (s in [-273, -252]) separates cliff shadow (s in [-480, -274]) from gentle bench
        //   pond (s in [-251, -140]). Downslope dist=32 hits the gentle pond (s = -240.7), but midpoint dist=16
        //   (s = -263.4) is dry rock -> v2 MUST still reject the cliff shadow.
        // Case B: continuous water (s in [-480, -140]) connects into the gentle bench -> v2 MUST preserve it.
        java.util.function.BiFunction<Double, Double, Double> terrain = (dx, dz) -> {
            double s = dx + dz;
            if (s < -280) return 220.0 + (-280.0 - s) * 0.55;
            if (s <= 0) return 220.0;
            return 20.0;
        };
        try (EarthShorelineTest.SyntheticWorld disconnected = new EarthShorelineTest.SyntheticWorld(
                terrain::apply,
                (dx, dz) -> {
                    int s = dx + dz;
                    if (s >= -480 && s <= -274 && Math.abs(dx - dz) <= 1500) return WorldCoverSource.WATER; // cliff shadow
                    if (s >= -251 && s <= -140 && Math.abs(dx - dz) <= 1500) return WorldCoverSource.WATER; // disconnected gentle pond
                    return WorldCoverSource.BARE;
                });
             EarthShorelineTest.SyntheticWorld connected = new EarthShorelineTest.SyntheticWorld(
                     terrain::apply,
                     (dx, dz) -> {
                         int s = dx + dz;
                         if (s >= -480 && s <= -140 && Math.abs(dx - dz) <= 1500) return WorldCoverSource.WATER; // continuous water
                         return WorldCoverSource.BARE;
                     })) {
            // Precondition: legacy leaves (-143, -143) (s = -286) wet so v2 cliffShadowWater is genuinely tested.
            int[] legacyShadow = new int[2];
            disconnected.ground(-143, -143, legacyShadow);
            assertNotEquals(SurfaceGenerator.DRY, legacyShadow[1],
                    "precondition: legacy must leave oblique cliff shadow wet");

            EarthGenerator v2Disconnected = new EarthGenerator(1234L, EarthShorelineTest.biomes, disconnected.generator.settings(),
                    disconnected.tiles, disconnected.cover, null, null);
            v2Disconnected.enableHydrologyV2();
            int[] shadowOut = new int[2];
            v2Disconnected.ground(disconnected.homeX - 143, disconnected.homeZ - 143, shadowOut);
            assertEquals(SurfaceGenerator.DRY, shadowOut[1],
                    "disconnected gentle pond across dry rock must NOT exempt steep cliff shadow");

            int[] pondOut = new int[2];
            v2Disconnected.ground(disconnected.homeX - 100, disconnected.homeZ - 100, pondOut);
            assertNotEquals(SurfaceGenerator.DRY, pondOut[1], "flat bench pond itself must remain wet");

            EarthGenerator v2Connected = new EarthGenerator(1234L, EarthShorelineTest.biomes, connected.generator.settings(),
                    connected.tiles, connected.cover, null, null);
            v2Connected.enableHydrologyV2();
            int[] connectedOut = new int[2];
            v2Connected.ground(connected.homeX - 143, connected.homeZ - 143, connectedOut);
            assertNotEquals(SurfaceGenerator.DRY, connectedOut[1],
                    "continuously connected gentle bench water MUST exempt steep cliff reach in v2");
        }
    }

    @Test
    void cliffShadowFallingReachPreservedOnSteepMountainWaterfall() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Wide upper plateau lake (y = 220 m, dz <= -150) feeding a steep waterfall face
        // (dz in [-149, -50], slope = 2.0 > 0.7, 100 blocks tall -> center at dz = -100 is > 32 blocks
        // from both gentle lakes!) into a wide lower plunge lake (y = 20 m, dz >= -49).
        // Reach detection classifies the waterfall face as a falling reach, which HYDRO-04 must preserve.
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> {
                    if (dz <= -150) return 220.0;
                    if (dz >= -50) return 20.0;
                    return 20.0 + (-50 - dz) * 2.0;
                },
                (dx, dz) -> {
                    if (dz <= -150 && dz >= -550 && Math.abs(dx) <= 300) return WorldCoverSource.WATER; // upper lake
                    if (dz >= -50 && dz <= 350 && Math.abs(dx) <= 300) return WorldCoverSource.WATER;   // lower lake
                    if (dz > -150 && dz < -50 && Math.abs(dx) <= 45) return WorldCoverSource.WATER;     // steep falls face
                    return WorldCoverSource.BARE;
                })) {
            world.generator.enableHydrologyV2();
            int[] fallsMid = new int[2];
            world.ground(0, -100, fallsMid);
            assertNotEquals(SurfaceGenerator.DRY, fallsMid[1],
                    "real falling reach on steep cliff face (>32 blocks from gentle pools) must stay wet in v2");
        }
    }

    @Test
    void drownedByAndLowerDrownedGuardIsolateHigherUplandPondFromLowerDrownedReservoir() {
        // Unit checks for isLowerDrownedOutsideBand (pre-nearest guard) and applyDrownedBy.
        assertTrue(EarthGenerator.isLowerDrownedOutsideBand(100.0, 150.0, 150.0),
                "100 m drowned reservoir must be rejected for 150 m upland pond");
        assertTrue(EarthGenerator.isLowerDrownedOutsideBand(100.0, Double.NaN, 150.0),
                "100 m drowned reservoir must be rejected for sub-grid 150 m upland pond with no cell offer");
        assertFalse(EarthGenerator.isLowerDrownedOutsideBand(100.0, 100.0, 60.0),
                "100 m drowned reservoir must be accepted over its own 60 m flooded valley bed");
        assertFalse(EarthGenerator.isLowerDrownedOutsideBand(100.0, 100.0, 105.0),
                "100 m drowned reservoir must be accepted on its own 105 m shore within DROP");

        // Upland pond at 150 m beside a drowned reservoir 50 m below at 100 m: must NOT pull pond down!
        double[] heights = {100.0, 150.0};
        boolean[] drowned = {true, false};
        assertTrue(Double.isNaN(EarthGenerator.applyDrownedBy(150.0, heights, drowned, 2)),
                "lower drowned reservoir outside DROP band must not pull higher upland pond down");

        // Reservoir shoreline climbing 4 m above drowned reservoir (within DROP = 8 m): MUST snap to reservoir!
        double[] shoreHeights = {100.0, 104.0};
        boolean[] shoreDrowned = {true, false};
        assertEquals(100.0, EarthGenerator.applyDrownedBy(104.0, shoreHeights, shoreDrowned, 2), 1e-9,
                "reservoir shore within DROP band must snap down to drowned level");

        // Mixed drowned samples: far-lower (50 m) + same-band (146 m) for pond at 150 m -> picks 146 m only!
        double[] mixedHeights = {50.0, 146.0};
        boolean[] mixedDrowned = {true, true};
        assertEquals(146.0, EarthGenerator.applyDrownedBy(150.0, mixedHeights, mixedDrowned, 2), 1e-9,
                "only drowned samples within the same elevation band should apply");
    }

    @Test
    void subGridUplandPondBesideLowerDrownedReservoirStaysAtUplandLevelInV2AndFollowsLegacyPinInV1() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Wide drowned reservoir on the west (dx in [-480, -40], dz in [-320, 320]) with stepped pre-dam valley bed
        // (60 m -> 75 m -> 90 m, giving >= DROWNED_CROSSINGS steps > DROP) surrounded by 100 m shores, so Reaches
        // classifies it as drowned at ~100 m. Right beside it on a 150 m bench sits a small sub-grid upland pond
        // (24 x 24 blocks, centered between 64-block WATER_SAMPLES centers) within NEAR (96 blocks) of the reservoir.
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> {
                    if (dx >= -480 && dx <= -40 && Math.abs(dz) <= 320) {
                        // Stepped drowned valley bed inside reservoir.
                        if (dx >= -360 && dx <= -160 && Math.abs(dz) <= 180) return 60.0;
                        if (dx >= -420 && dx <= -100 && Math.abs(dz) <= 250) return 75.0;
                        return 90.0;
                    }
                    if (dx >= 0 && dx <= 160 && Math.abs(dz) <= 160) return 150.0; // upland bench
                    return 100.0; // surrounding reservoir shores at 100 m
                },
                (dx, dz) -> {
                    if (dx >= -480 && dx <= -40 && Math.abs(dz) <= 320) return WorldCoverSource.WATER; // drowned reservoir
                    // Sub-grid upland pond (24x24 blocks, placed at dx in [20, 44], dz in [-12, 12], within NEAR of reservoir).
                    if (dx >= 20 && dx <= 44 && Math.abs(dz) <= 12) return WorldCoverSource.WATER;
                    return WorldCoverSource.GRASSLAND;
                })) {
            // Find an exact sub-grid pond column between 64-block sample centers that stays wet.
            int pondDx = 32, pondDz = 0;

            // Legacy generator (!hydrologyV2): lower drowned reservoir pulls the sub-grid upland pond down toward ~100 m.
            int[] legacyPond = new int[2];
            world.ground(pondDx, pondDz, legacyPond);

            // V2 generator: reservoir stays at ~100 m, and sub-grid upland pond stays perched near 150 m!
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            v2.enableHydrologyV2();

            int[] reservoirOut = new int[2];
            v2.ground(world.homeX - 260, world.homeZ, reservoirOut);
            assertNotEquals(SurfaceGenerator.DRY, reservoirOut[1], "drowned reservoir must stay wet");
            assertTrue(reservoirOut[1] >= EarthGenerator.SEA + 94 && reservoirOut[1] <= EarthGenerator.SEA + 106,
                    "drowned reservoir surface should sit near +100 m, got " + reservoirOut[1]);

            int[] v2Pond = new int[2];
            v2.ground(world.homeX + pondDx, world.homeZ + pondDz, v2Pond);
            assertNotEquals(SurfaceGenerator.DRY, v2Pond[1], "sub-grid upland pond must stay wet");
            assertTrue(v2Pond[1] >= EarthGenerator.SEA + 145,
                    "sub-grid upland pond must stay near +150 m in v2 instead of sinking to reservoir, got " + v2Pond[1]);
            assertTrue(legacyPond[1] < EarthGenerator.SEA + 120,
                    "legacy generator without hydrologyV2 pin must preserve pre-PR drowned pull, got " + legacyPond[1]);
        }
    }

    @Test
    void lakeShoreRaisesHollowsOnBothRims() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Large +50 m plateau + lake (|dx| <= 60, |dz| <= 60) with small 2.5 m shore hollows straddling the
        // east and west rims (48 <= |dx| <= 80, |dz| <= 14). Even with WorldCover shoreline warp, the first
        // dry shore block on both rims sits inside the hollow and must be lifted by shore() to >= lake[1].
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> (Math.abs(dx) >= 48 && Math.abs(dx) <= 80 && Math.abs(dz) <= 14) ? 47.5 : 50.0,
                (dx, dz) -> (Math.abs(dx) <= 60 && Math.abs(dz) <= 60) ? WorldCoverSource.WATER : WorldCoverSource.GRASSLAND)) {
            world.generator.enableHydrologyV2();
            assertTrue(world.homeX < 0 && world.homeZ < 0, "fixture origin must be in negative Mercator quadrant");
            int[] lake = new int[2], probe = new int[2], away = new int[2];
            world.ground(0, 0, lake);
            assertTrue(lake[1] >= EarthGenerator.SEA + 49, "lake surface should sit near +50 m, got " + lake[1]);
            world.ground(74, 0, away);
            assertEquals(SurfaceGenerator.DRY, away[1], "far hollow stays dry");
            assertTrue(away[0] < lake[1], "hollow beyond shore() reach should stay below lake level, got " + away[0]);

            int eastShoreY = Integer.MIN_VALUE;
            for (int dx = 45; dx <= 70; dx++) {
                world.ground(dx, 0, probe);
                if (probe[1] == SurfaceGenerator.DRY) { eastShoreY = probe[0]; break; }
            }
            int westShoreY = Integer.MIN_VALUE;
            for (int dx = -45; dx >= -70; dx--) {
                world.ground(dx, 0, probe);
                if (probe[1] == SurfaceGenerator.DRY) { westShoreY = probe[0]; break; }
            }
            assertTrue(eastShoreY >= lake[1], "east shore bank should rise to lake level " + lake[1] + ", got " + eastShoreY);
            assertTrue(westShoreY >= lake[1], "west shore bank should rise to lake level " + lake[1] + ", got " + westShoreY);
        }
    }
}
