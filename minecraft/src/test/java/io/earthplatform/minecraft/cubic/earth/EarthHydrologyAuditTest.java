package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.globe.Drainage;
import io.earthplatform.geography.globe.GlobalElevation;
import io.earthplatform.geography.globe.MemoryPressure;
import io.earthplatform.geography.globe.MemoryPressureTestBridge;
import io.earthplatform.geography.globe.RiverMap;
import io.earthplatform.geography.globe.SurfaceWaterSource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator.HydroCache;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    void hydroCachePutDoesNotOverwriteExistingOldGenerationValue() {
        MemoryPressureTestBridge.reset();
        try {
            HydroCache<Integer> cache = new HydroCache<>(2);
            cache.putOrReplace(1L, 2);
            cache.put(2L, 0);
            cache.put(3L, 0); // rotates: young={3:0}, old={1:2, 2:0}
            cache.put(1L, 1); // delayed put for key sitting in old must not overwrite 2 with 1
            assertEquals(2, cache.get(1L), "put() must not overwrite an existing entry sitting in old");
        } finally {
            MemoryPressureTestBridge.reset();
        }
    }

    @Test
    void hydroCacheConcurrentStressEnforcesStrictCapacityAndReplacementMonotonicity() throws Exception {
        MemoryPressureTestBridge.reset();
        try {
            int capacity = 32;
            HydroCache<Integer> cache = new HydroCache<>(capacity);
            int trackedKeys = 16;
            AtomicInteger[] committed = new AtomicInteger[trackedKeys];
            Object[] keyLocks = new Object[trackedKeys];
            for (int i = 0; i < trackedKeys; i++) {
                committed[i] = new AtomicInteger(0);
                keyLocks[i] = new Object();
            }

            int threads = 10;
            int opsPerThread = 4_000;
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int tid = t;
                    futures.add(pool.submit(() -> {
                        try {
                            start.await(5, TimeUnit.SECONDS);
                            java.util.SplittableRandom rng = new java.util.SplittableRandom(0x9E3779B97F4A7C15L * (tid + 1));
                            for (int i = 0; i < opsPerThread && failure.get() == null; i++) {
                                int op = rng.nextInt(100);
                                if (op < 35) {
                                    // Monotonic putOrReplace on tracked keys.
                                    int k = rng.nextInt(trackedKeys);
                                    synchronized (keyLocks[k]) {
                                        int nextVal = committed[k].get() + 1;
                                        cache.putOrReplace(k, nextVal);
                                        committed[k].set(nextVal);
                                    }
                                } else if (op < 65) {
                                    // Read tracked key and assert monotonicity against latest completed putOrReplace.
                                    int k = rng.nextInt(trackedKeys);
                                    int minExpected = committed[k].get();
                                    Integer observed = cache.get(k);
                                    if (observed != null && observed < minExpected) {
                                        fail("stale value resurrected for key " + k + ": observed=" + observed
                                                + " < committed=" + minExpected);
                                    }
                                } else if (op < 90) {
                                    // Churn untracked keys via put() + get() to drive constant rotations and old-hit promotions.
                                    long churnKey = trackedKeys + rng.nextInt(capacity * 4);
                                    cache.put(churnKey, (int) churnKey);
                                    cache.get(trackedKeys + rng.nextInt(capacity * 4));
                                } else {
                                    cache.shed();
                                }
                                int young = cache.youngSize();
                                int total = cache.size();
                                if (young > capacity) {
                                    fail("young generation exceeded capacity: " + young + " > " + capacity);
                                }
                                if (total > 2 * capacity) {
                                    fail("total size exceeded 2*capacity: " + total + " > " + (2 * capacity));
                                }
                            }
                        } catch (Throwable thrown) {
                            failure.compareAndSet(null, thrown);
                        }
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }
            if (failure.get() != null) {
                fail("concurrent HydroCache stress invariant violated", failure.get());
            }
            assertTrue(cache.youngSize() <= capacity, "final young size must stay <= capacity");
            assertTrue(cache.size() <= 2 * capacity, "final total size must stay <= 2*capacity");

            // Overlapping-key concurrent stress: with capacity+1 distinct pre-populated keys and no shed(),
            // young U old always retains every key across continuous rotations, so stale put(k, 0) calls
            // repeatedly hit keys sitting in old and must never overwrite newer putOrReplace values.
            int overlapCap = 8;
            int overlapKeys = overlapCap + 1;
            HydroCache<Integer> overlapCache = new HydroCache<>(overlapCap);
            AtomicInteger[] overlapCommitted = new AtomicInteger[overlapKeys];
            Object[] overlapLocks = new Object[overlapKeys];
            for (int i = 0; i < overlapKeys; i++) {
                overlapCommitted[i] = new AtomicInteger(1);
                overlapLocks[i] = new Object();
                overlapCache.putOrReplace(i, 1);
            }
            CountDownLatch overlapStart = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> overlapFailure = new java.util.concurrent.atomic.AtomicReference<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int tid = t;
                    futures.add(pool.submit(() -> {
                        try {
                            overlapStart.await(5, TimeUnit.SECONDS);
                            java.util.SplittableRandom rng = new java.util.SplittableRandom(0xBF58476D1CE4E5B9L * (tid + 1));
                            for (int i = 0; i < opsPerThread && overlapFailure.get() == null; i++) {
                                int k = rng.nextInt(overlapKeys);
                                int op = rng.nextInt(100);
                                if (op < 35) {
                                    synchronized (overlapLocks[k]) {
                                        int nextVal = overlapCommitted[k].get() + 1;
                                        overlapCache.putOrReplace(k, nextVal);
                                        overlapCommitted[k].set(nextVal);
                                    }
                                } else if (op < 70) {
                                    // Stale put on the same overlapping key must never clobber young or old.
                                    overlapCache.put(k, 0);
                                } else {
                                    int minExpected = overlapCommitted[k].get();
                                    Integer observed = overlapCache.get(k);
                                    if (observed == null || observed < minExpected) {
                                        fail("overlapping put() clobbered key " + k + ": observed=" + observed
                                                + " < committed=" + minExpected);
                                    }
                                }
                                if (overlapCache.youngSize() > overlapCap) {
                                    fail("overlap young generation exceeded capacity: " + overlapCache.youngSize());
                                }
                                if (overlapCache.size() > 2 * overlapCap) {
                                    fail("overlap total size exceeded 2*capacity: " + overlapCache.size());
                                }
                            }
                        } catch (Throwable thrown) {
                            overlapFailure.compareAndSet(null, thrown);
                        }
                    }));
                }
                overlapStart.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }
            if (overlapFailure.get() != null) {
                fail("overlapping-key HydroCache stress invariant violated", overlapFailure.get());
            }
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
                        new double[]{0}, new boolean[]{false}, new float[256], new long[4], new long[4], new long[4], new long[4], null);
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

    @Test
    void cliffShadowFineLodCheapProbesNeverInvokeRiverMapOrDrainage() throws Exception {
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

        // Oblique 0.778-slope mountain wall (s = dx + dz < -280) above flat 220 m bench (s in [-280, 0]) and 20 m valley.
        java.util.function.BiFunction<Double, Double, Double> terrain = (dx, dz) -> {
            double s = dx + dz;
            if (s < -280) return 220.0 + (-280.0 - s) * 0.55;
            if (s <= 0) return 220.0;
            return 20.0;
        };

        // Case A (connected): water ends just uphill at s = -294 so d=0 (+gradient uphill) hits DRY rock at midR=8
        // (s ~ -298), while d=1 (-gradient downhill) stays continuously wet onto the flat 220 m bench.
        // Cheap probes must use raw WorldCover without calling RiverMap.strength(), and once d=1 exempts the pixel,
        // rivers.drained() must not be called either.
        // Case B (disconnected): dry rock strip separates cliff shadow from bench pond; d=1 probes dry rock at s ~ -263.
        // With drainage == null (so rivers.drained() returns false without touching SurfaceWaterSource), stripping the
        // cliff shadow must still make zero SurfaceWaterSource requests.
        try (EarthShorelineTest.SyntheticWorld connected = new EarthShorelineTest.SyntheticWorld(
                terrain::apply,
                (dx, dz) -> {
                    int s = dx + dz;
                    return (s >= -294 && s <= -140 && Math.abs(dx - dz) <= 1500)
                            ? WorldCoverSource.WATER : WorldCoverSource.BARE;
                });
             EarthShorelineTest.SyntheticWorld disconnected = new EarthShorelineTest.SyntheticWorld(
                     terrain::apply,
                     (dx, dz) -> {
                         int s = dx + dz;
                         if (s >= -480 && s <= -274 && Math.abs(dx - dz) <= 1500) return WorldCoverSource.WATER;
                         if (s >= -251 && s <= -140 && Math.abs(dx - dz) <= 1500) return WorldCoverSource.WATER;
                         return WorldCoverSource.BARE;
                     });
             SurfaceWaterSource sws = new SurfaceWaterSource(
                     URI.create("http://127.0.0.1:" + waterServer.getAddress().getPort() + "/water/"),
                     null, true, 4, 1, Duration.ofSeconds(5))) {
            Drainage spyDrainage = new Drainage(
                    (zoom, tx, ty) -> {
                        drainageTileCalls.incrementAndGet();
                        throw new AssertionError("connected gentle-water exemption must not trigger Drainage");
                    },
                    (lat, lon) -> 1000.0, 4);

            RiverMap spyRiversWithDrainage = new RiverMap(sws, spyDrainage, 8, true);
            EarthGenerator v2Connected = new EarthGenerator(1234L, EarthShorelineTest.biomes, connected.generator.settings(),
                    connected.tiles, connected.cover, null, null, spyRiversWithDrainage);
            v2Connected.enableHydrologyV2();
            assertFalse(v2Connected.cliffShadowWaterAt(0, connected.homeX - 143, connected.homeZ - 143, false, false),
                    "continuously connected gentle bench water must exempt steep cliff pixel at fine LOD");
            assertEquals(0, surfaceWaterRequests.get(),
                    "hasNearbyGentleWater dry-midpoint probe must never query SurfaceWaterSource via RiverMap.strength");
            assertEquals(0, drainageTileCalls.get(),
                    "exempted steep cliff pixel must never query Drainage");

            RiverMap spyRiversNoDrainage = new RiverMap(sws, null, 8, true);
            EarthGenerator v2Disconnected = new EarthGenerator(1234L, EarthShorelineTest.biomes, disconnected.generator.settings(),
                    disconnected.tiles, disconnected.cover, null, null, spyRiversNoDrainage);
            v2Disconnected.enableHydrologyV2();
            assertTrue(v2Disconnected.cliffShadowWaterAt(0, disconnected.homeX - 143, disconnected.homeZ - 143, false, false),
                    "disconnected cliff shadow must be stripped at fine LOD");
            assertEquals(0, surfaceWaterRequests.get(),
                    "hasNearbyGentleWater dry-rock probes on disconnected cliff shadow must never query SurfaceWaterSource");
        } finally {
            waterServer.stop(0);
        }
    }

    @Test
    void benchEdgeSubGridUplandPondWithDry100mDropKeeps150mLevelInV2() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Same 100 m drowned reservoir on the west (dx in [-480, -40], |dz| <= 320) and 150 m bench (dx in [0, 160],
        // |dz| <= 160) with a dry 100 m drop immediately west of the bench (dx < 0). Place the sub-grid upland pond
        // right at the bench rim (dx in [4, 28], |dz| <= 12) so a wet pond pixel near dx = 6 has its dry -8 cardinal
        // probe fall off the cliff to 100 m. Under hydrologyV2, dry cardinal probes must not drag drownedRef / local
        // down to 100 m and defeat the drowned-reservoir guards.
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> {
                    if (dx >= -480 && dx <= -40 && Math.abs(dz) <= 320) {
                        if (dx >= -360 && dx <= -160 && Math.abs(dz) <= 180) return 60.0;
                        if (dx >= -420 && dx <= -100 && Math.abs(dz) <= 250) return 75.0;
                        return 90.0;
                    }
                    if (dx >= 0 && dx <= 160 && Math.abs(dz) <= 160) return 150.0;
                    return 100.0;
                },
                (dx, dz) -> {
                    if (dx >= -480 && dx <= -40 && Math.abs(dz) <= 320) return WorldCoverSource.WATER;
                    if (dx >= 4 && dx <= 28 && Math.abs(dz) <= 12) return WorldCoverSource.WATER;
                    return WorldCoverSource.GRASSLAND;
                })) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            v2.enableHydrologyV2();

            // Verify drowned reservoir bed and reservoir shore still adopt the ~100 m reservoir level.
            int[] reservoirBed = new int[2];
            v2.ground(world.homeX - 260, world.homeZ, reservoirBed);
            assertNotEquals(SurfaceGenerator.DRY, reservoirBed[1], "drowned reservoir bed must stay wet");
            assertTrue(reservoirBed[1] >= EarthGenerator.SEA + 94 && reservoirBed[1] <= EarthGenerator.SEA + 106,
                    "drowned reservoir bed surface should sit near +100 m, got " + reservoirBed[1]);

            int[] reservoirShore = new int[2];
            v2.ground(world.homeX - 44, world.homeZ, reservoirShore);
            assertNotEquals(SurfaceGenerator.DRY, reservoirShore[1], "drowned reservoir shore must stay wet");
            assertTrue(reservoirShore[1] >= EarthGenerator.SEA + 94 && reservoirShore[1] <= EarthGenerator.SEA + 106,
                    "drowned reservoir shore surface should sit near +100 m, got " + reservoirShore[1]);

            // Probe bench-edge pond pixel whose west -8 neighbor falls off the dry cliff to ~100 m.
            int benchEdgeDx = -1;
            int[] v2Pond = new int[2];
            int[] westNeighbor = new int[2];
            for (int dx = 2; dx <= 7; dx++) {
                v2.ground(world.homeX + dx, world.homeZ, v2Pond);
                v2.ground(world.homeX + dx - 8, world.homeZ, westNeighbor);
                if (v2Pond[1] != SurfaceGenerator.DRY
                        && v2Pond[0] >= EarthGenerator.SEA + 145
                        && westNeighbor[1] == SurfaceGenerator.DRY
                        && westNeighbor[0] <= EarthGenerator.SEA + 105) {
                    benchEdgeDx = dx;
                    break;
                }
            }
            assertTrue(benchEdgeDx >= 2,
                    "fixture must locate a wet bench-edge pond column on +150 m rim with a dry <=105 m drop 8 blocks west");
            assertTrue(v2Pond[1] >= EarthGenerator.SEA + 145,
                    "bench-edge sub-grid upland pond at dx=" + benchEdgeDx
                            + " must stay near +150 m instead of sinking to 100 m drowned reservoir, got " + v2Pond[1]);
        }
    }

    @Test
    void isWorldCoverWaterIgnoresStrayRejectedCacheHistory() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Steep 50% slope with a small WorldCover WATER patch at home that strayWater() rejects to BARE.
        // Warming coverMemo via cover() caches BARE; isWorldCoverWater() must still return true,
        // independent of thread-local cache history.
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> 200.0 + dx * 0.5,
                (dx, dz) -> (Math.abs(dx) <= 15 && Math.abs(dz) <= 15)
                        ? WorldCoverSource.WATER : WorldCoverSource.BARE)) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            v2.enableHydrologyV2();

            assertTrue(v2.isWorldCoverWater(world.homeX, world.homeZ, 1),
                    "cold isWorldCoverWater must report WorldCover WATER pixel as water");
            assertEquals(WorldCoverSource.BARE, v2.cover(world.homeX, world.homeZ, 1),
                    "strayWater must reject small steep-slope water patch to BARE and warm coverMemo");
            assertTrue(v2.isWorldCoverWater(world.homeX, world.homeZ, 1),
                    "warmed coverMemo holding stray-rejected BARE must not flip isWorldCoverWater to false");

            assertFalse(v2.isWorldCoverWater(world.homeX + 80, world.homeZ, 1),
                    "cold dry BARE pixel must report false");
            assertEquals(WorldCoverSource.BARE, v2.cover(world.homeX + 80, world.homeZ, 1));
            assertFalse(v2.isWorldCoverWater(world.homeX + 80, world.homeZ, 1),
                    "warm dry BARE pixel must remain false");
        }
    }

    @SuppressWarnings("unchecked")
    private static void injectUniformRiverTiles(RiverMap map, GeoPoint center, boolean drainedBit) throws Exception {
        Field field = RiverMap.class.getDeclaredField("memory");
        field.setAccessible(true);
        Map<Long, Object> memory = (Map<Long, Object>) field.get(map);
        Constructor<?> tracedCtor = Class.forName(RiverMap.class.getName() + "$Traced")
                .getDeclaredConstructor(long.class, byte[].class, byte[].class, long[].class);
        tracedCtor.setAccessible(true);

        int tile = 256, cell = 16;
        int col = Math.floorMod((int) Math.floor((center.longitude() + 180.0) * SurfaceWaterSource.PIXELS_PER_DEGREE),
                SurfaceWaterSource.ACROSS);
        int row = (int) Math.floor((SurfaceWaterSource.NORTH - center.latitude()) * SurfaceWaterSource.PIXELS_PER_DEGREE);
        int tx0 = col / tile, ty0 = row / tile;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int tx = tx0 + dx, ty = ty0 + dy;
            if (ty < 0 || ty * tile >= SurfaceWaterSource.DOWN) continue;
            long key = (long) ty << 32 | Math.floorMod(tx, SurfaceWaterSource.ACROSS / tile);
            byte[] strength = new byte[tile * tile];
            byte[] cells = new byte[(tile / cell) * (tile / cell)];
            Arrays.fill(strength, (byte) 255);
            Arrays.fill(cells, (byte) 255);
            long[] drained = new long[(tile * tile) / 64];
            if (drainedBit) Arrays.fill(drained, -1L);
            memory.put(key, tracedCtor.newInstance(key, strength, cells, drained));
        }
    }

    @Test
    void trollveggenSlopingMountainShadowIsStrippedInV2AcrossNearFarAndUnbackedRiversWhileValleyAndDrainedStayWet() throws Exception {
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

        // Reproduce Trollveggen's exact failure geometry:
        // - Wide sloping mountain face (dz < 200, slope = 0.45 <= CLIFF_SHADOW_STEEP = 0.7, rising > 300 m)
        //   covered by a giant WorldCover WATER shadow band (1300 x 750 blocks -> > 5000 pixels & spans WATER_CELL
        //   borders so strayCell skips it), with SNOW_AND_ICE on the ridges above/beside it.
        // - Small 24x24 BARE pocket inside the shadow at (200, -300) so RiverMap strength >= EDGE produces rawCover == RIVER.
        // - Flat 100 m valley floor (dz >= 200) with a flat valley river (dz in [300, 500]).
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> dz < 200.0 ? 100.0 + (200.0 - dz) * 0.45 : 100.0,
                (dx, dz) -> {
                    if (Math.abs(dx - 200) <= 12 && Math.abs(dz + 300) <= 12) return WorldCoverSource.BARE;
                    if (dz >= -650 && dz <= 100 && Math.abs(dx) <= 650) return WorldCoverSource.WATER;
                    if (dz < -650 && dz >= -1000 && Math.abs(dx) <= 900) return WorldCoverSource.SNOW_AND_ICE;
                    if (dz >= -650 && dz <= -200 && Math.abs(dx) > 650 && Math.abs(dx) <= 900) return WorldCoverSource.SNOW_AND_ICE;
                    if (dz >= 300 && dz <= 500 && Math.abs(dx) <= 1200) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                });
             SurfaceWaterSource sws = new SurfaceWaterSource(
                     URI.create("http://127.0.0.1:" + waterServer.getAddress().getPort() + "/water/"),
                     null, true, 4, 1, Duration.ofSeconds(5))) {
            Drainage spyDrainage = new Drainage(
                    (zoom, tx, ty) -> {
                        drainageTileCalls.incrementAndGet();
                        throw new AssertionError("unexpected Drainage tile fetch");
                    },
                    (lat, lon) -> 1000.0, 4);

            // 1. Legacy (!hydrologyV2): leaves the sloping mountain shadow WET and carves it down as glacial.
            int[] legacyShadow = new int[2];
            world.ground(0, -300, legacyShadow);
            assertNotEquals(SurfaceGenerator.DRY, legacyShadow[1],
                    "precondition: legacy (!hydrologyV2) must leave sloping mountain shadow wet");
            assertTrue(legacyShadow[1] < EarthGenerator.SEA + 280,
                    "precondition: legacy must drop/carve 325 m sloping shadow down to glacial lowWater, got " + legacyShadow[1]);

            // 2. Hydrology v2 (with spyRivers to guarantee far-LOD never queries Drainage or SurfaceWaterSource):
            //    strips the sloping mountain shadow to DRY at both near (voxel=1) and far LOD (voxel=64),
            //    while keeping the flat valley river WET.
            RiverMap spyRivers = new RiverMap(sws, spyDrainage, 8, true);
            EarthGenerator v2Far = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null, spyRivers);
            v2Far.enableHydrologyV2();
            int[] farShadow = new int[2];
            v2Far.farGround(6, world.homeX, world.homeZ - 300, farShadow);
            assertEquals(SurfaceGenerator.DRY, farShadow[1],
                    "v2 coarse far-LOD (voxel=64) must strip sloping mountain shadow to DRY");
            assertEquals(0, drainageTileCalls.get(),
                    "coarse far-LOD (> RIVER_VOXEL) must never query Drainage");
            assertEquals(0, surfaceWaterRequests.get(),
                    "coarse far-LOD (> RIVER_VOXEL) must never query SurfaceWaterSource");

            int[] farValley = new int[2];
            v2Far.farGround(6, world.homeX, world.homeZ + 400, farValley);
            assertNotEquals(SurfaceGenerator.DRY, farValley[1],
                    "flat valley river must remain WET at far LOD in v2");

            EarthGenerator v2Near = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            v2Near.enableHydrologyV2();
            int[] nearShadow = new int[2];
            v2Near.ground(world.homeX, world.homeZ - 300, nearShadow);
            assertEquals(SurfaceGenerator.DRY, nearShadow[1],
                    "v2 near LOD (voxel=1) must strip sloping mountain shadow to DRY");

            int[] nearValley = new int[2];
            v2Near.ground(world.homeX, world.homeZ + 400, nearValley);
            assertNotEquals(SurfaceGenerator.DRY, nearValley[1],
                    "flat valley river must remain WET at near LOD in v2");

            // 3. Unbacked RIVER pixel (RiverMap JRC hit with drained == false) inside the sloping shadow:
            //    stripped to DRY in hydrologyV2, whereas Drainage-backed (drained == true) stays WET!
            MercatorGrid grid = new MercatorGrid(world.generator.settings().metresPerBlock());
            GeoPoint riverPoint = grid.tryGeographic(world.homeX + 200.5, world.homeZ - 299.5);
            assertNotNull(riverPoint);

            RiverMap unbackedRivers = new RiverMap(sws, spyDrainage, 16, true);
            injectUniformRiverTiles(unbackedRivers, riverPoint, false);
            EarthGenerator v2Unbacked = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null, unbackedRivers);
            v2Unbacked.enableHydrologyV2();
            int[] unbackedRiverOut = new int[2];
            v2Unbacked.ground(world.homeX + 200, world.homeZ - 300, unbackedRiverOut);
            assertEquals(SurfaceGenerator.DRY, unbackedRiverOut[1],
                    "unbacked RIVER pixel (drained == false) inside sloping shadow must be stripped to DRY in v2");

            RiverMap drainedRivers = new RiverMap(sws, spyDrainage, 16, true);
            injectUniformRiverTiles(drainedRivers, riverPoint, true);
            EarthGenerator v2Drained = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null, drainedRivers);
            v2Drained.enableHydrologyV2();
            int[] drainedRiverOut = new int[2];
            v2Drained.ground(world.homeX + 200, world.homeZ - 300, drainedRiverOut);
            assertNotEquals(SurfaceGenerator.DRY, drainedRiverOut[1],
                    "Drainage-backed RIVER stream (drained == true) inside sloping shadow must stay WET in v2");

            int[] drainedWaterOut = new int[2];
            v2Drained.ground(world.homeX, world.homeZ - 300, drainedWaterOut);
            assertNotEquals(SurfaceGenerator.DRY, drainedWaterOut[1],
                    "Drainage-backed WorldCover mountain stream (drained == true) inside sloping shadow must stay WET in v2");
        } finally {
            waterServer.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static void injectCustomRiverTiles(RiverMap map, GeoPoint center, byte strengthByte,
                                               java.util.function.BiPredicate<Integer, Integer> drainedAtPixel) throws Exception {
        Field field = RiverMap.class.getDeclaredField("memory");
        field.setAccessible(true);
        Map<Long, Object> memory = (Map<Long, Object>) field.get(map);
        Constructor<?> tracedCtor = Class.forName(RiverMap.class.getName() + "$Traced")
                .getDeclaredConstructor(long.class, byte[].class, byte[].class, long[].class);
        tracedCtor.setAccessible(true);

        int tile = 256, cell = 16;
        int col0 = Math.floorMod((int) Math.floor((center.longitude() + 180.0) * SurfaceWaterSource.PIXELS_PER_DEGREE),
                SurfaceWaterSource.ACROSS);
        int row0 = (int) Math.floor((SurfaceWaterSource.NORTH - center.latitude()) * SurfaceWaterSource.PIXELS_PER_DEGREE);
        int tx0 = col0 / tile, ty0 = row0 / tile;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int tx = tx0 + dx, ty = ty0 + dy;
            if (ty < 0 || ty * tile >= SurfaceWaterSource.DOWN) continue;
            int wtx = Math.floorMod(tx, SurfaceWaterSource.ACROSS / tile);
            long key = (long) ty << 32 | wtx;
            byte[] strength = new byte[tile * tile];
            byte[] cells = new byte[(tile / cell) * (tile / cell)];
            Arrays.fill(strength, strengthByte);
            Arrays.fill(cells, strengthByte);
            long[] drained = new long[(tile * tile) / 64];
            for (int r = 0; r < tile; r++) {
                int globalRow = ty * tile + r;
                for (int c = 0; c < tile; c++) {
                    int globalCol = wtx * tile + c;
                    if (drainedAtPixel.test(globalCol, globalRow)) {
                        int k = r * tile + c;
                        drained[k >>> 6] |= 1L << (k & 63);
                    }
                }
            }
            memory.put(key, tracedCtor.newInstance(key, strength, cells, drained));
        }
    }

    private static int riverCol(GeoPoint p) {
        return Math.floorMod((int) Math.floor((p.longitude() + 180.0) * SurfaceWaterSource.PIXELS_PER_DEGREE),
                SurfaceWaterSource.ACROSS);
    }

    private static int riverRow(GeoPoint p) {
        return (int) Math.floor((SurfaceWaterSource.NORTH - p.latitude()) * SurfaceWaterSource.PIXELS_PER_DEGREE);
    }

    private static boolean anyBitSet(long[] bits) {
        for (long word : bits) if (word != 0L) return true;
        return false;
    }

    @Test
    void mixedFlatPoolConnectedToSlopingShadowProducesNormalFlatReachInV2() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Large 0.45-grade sloping mountain shadow apron (dz in [-1100, -100], |dx| <= 750) directly
        // joined to a 320x320 flat pool (dz in [-100, 220], |dx| <= 160, flat at 100 m).
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> dz < -100.0 ? 100.0 + (-100.0 - dz) * 0.45 : 100.0,
                (dx, dz) -> {
                    if (dz >= -1100 && dz < -100 && Math.abs(dx) <= 750) return WorldCoverSource.WATER;
                    if (dz >= -100 && dz <= 220 && Math.abs(dx) <= 160) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                })) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            v2.enableHydrologyV2();

            int[] apron = new int[2];
            v2.ground(world.homeX, world.homeZ - 650, apron);
            assertEquals(SurfaceGenerator.DRY, apron[1],
                    "sloping shadow apron must be stripped to DRY in v2");

            int[] pool = new int[2];
            v2.ground(world.homeX, world.homeZ + 60, pool);
            assertNotEquals(SurfaceGenerator.DRY, pool[1],
                    "connected flat pool must remain WET in v2");
            assertEquals(EarthGenerator.SEA + 100, pool[1], 3,
                    "connected flat pool must keep its 100 m flat level");

            int poolCx = Math.floorDiv(world.homeX, EarthGenerator.WATER_CELL);
            int poolCz = Math.floorDiv(world.homeZ + 60, EarthGenerator.WATER_CELL);
            EarthGenerator.Reaches reaches = v2.reachLevels(poolCx, poolCz);
            boolean hasFlatReach = false;
            for (double lvl : reaches.levels()) {
                if (Math.abs(lvl - 100.0) <= 5.0) hasFlatReach = true;
            }
            assertTrue(hasFlatReach,
                    "connected flat pool must form a real ~100 m reach in computeReachLevels, got " + Arrays.toString(reaches.levels()));
        }
    }

    @Test
    void computeReachLevelsGlacialGuardRejectsSteepShadowAndPreservesTwentyPercentGlacier() throws Exception {
        EarthShorelineTest.bootRegistries();
        // High-latitude (73.5 deg N) terrain evaluated with equatorial 1 m/block Web Mercator settings:
        // groundMetresPerBlock = cos(73.5 deg) ~= 0.284 m/block -> spacingMetres ~= 18.2 m per 64-block sample.
        // A 0.38 physical slope rises 6.9 m <= DROP (8 m) per sample (0 crossings -> skips drownedGround!)
        // yet exceeds SHADOW_GLACIAL_FLAT_GRAD (0.30), so computeReachLevels must reject glacialBits.
        // The same geometry at 0.22 physical slope (<= 0.30) must keep glacialBits.
        double lat = 73.5, lon = 16.5;
        EarthSettings eqSettings = EarthSettings.at(0.0, 0.0);
        MercatorGrid eqGrid = new MercatorGrid(eqSettings.metresPerBlock());
        GeoPoint target = new GeoPoint(lat, lon);
        int gx = (int) Math.floor(eqGrid.blockX(target));
        int gz = (int) Math.floor(eqGrid.blockZ(target));
        int cx = Math.floorDiv(gx, EarthGenerator.WATER_CELL);
        int cz = Math.floorDiv(gz, EarthGenerator.WATER_CELL);
        GeoPoint cellCenter = eqGrid.tryGeographic((cx + 0.5) * EarthGenerator.WATER_CELL, (cz + 0.5) * EarthGenerator.WATER_CELL);
        assertNotNull(cellCenter);

        for (boolean steepShadow : new boolean[]{true, false}) {
            double slope = steepShadow ? 0.38 : 0.22;
            try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                    EarthShorelineTest.biomes, cellCenter.latitude(), cellCenter.longitude(),
                    (dx, dz) -> dz < 200.0 ? 100.0 + (200.0 - dz) * slope : 100.0,
                    (dx, dz) -> {
                        if (dz >= -100 && dz <= 120 && Math.abs(dx) <= 180) return WorldCoverSource.WATER;
                        if (dz < -100 && dz >= -280 && Math.abs(dx) <= 260) return WorldCoverSource.SNOW_AND_ICE;
                        return WorldCoverSource.BARE;
                    })) {
                EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, eqSettings,
                        world.tiles, world.cover, null, null);
                v2.enableHydrologyV2();
                EarthGenerator.Reaches reaches = v2.reachLevels(cx, cz);
                boolean hasGlacial = anyBitSet(reaches.glacial());
                if (steepShadow) {
                    assertFalse(hasGlacial,
                            "0.38 physical slope with < DROWNED_CROSSINGS touching SNOW_AND_ICE must NOT receive glacialBits");
                } else {
                    assertTrue(hasGlacial,
                            "0.22 physical slope retreating glacier tongue with < DROWNED_CROSSINGS touching SNOW_AND_ICE must keep glacialBits");
                }
            }
        }

        // Physical-slope unit alignment at lat = 73.5 deg N (groundMetresPerBlock ~= 0.284):
        // A physical 0.45-grade sloping shadow has block slope ~= 0.128 m/block (<= SHADOW_FLAT_GRAD = 0.15 in
        // m/block!) yet physical slope = 0.45 m/m (> 0.15), so shouldStripShadowReach must strip it to DRY.
        try (EarthShorelineTest.SyntheticWorld highLatWorld = new EarthShorelineTest.SyntheticWorld(
                EarthShorelineTest.biomes, cellCenter.latitude(), cellCenter.longitude(),
                (dx, dz) -> dz < 200.0 ? 100.0 + (200.0 - dz) * 0.45 : 100.0,
                (dx, dz) -> {
                    if (dz >= -500 && dz <= 100 && Math.abs(dx) <= 450) return WorldCoverSource.WATER;
                    if (dz >= 260 && dz <= 420 && Math.abs(dx) <= 300) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                })) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, eqSettings,
                    highLatWorld.tiles, highLatWorld.cover, null, null);
            v2.enableHydrologyV2();
            int centerEqX = (int) Math.floor(eqGrid.blockX(cellCenter));
            int centerEqZ = (int) Math.floor(eqGrid.blockZ(cellCenter));
            int[] shadowOut = new int[2];
            v2.ground(centerEqX, centerEqZ - 600, shadowOut);
            assertEquals(SurfaceGenerator.DRY, shadowOut[1],
                    "0.45 physical-grade shadow at 73.5 deg N (~0.128 m/block) must strip to DRY via physicalSlope");

            int[] flatPoolOut = new int[2];
            v2.ground(centerEqX, centerEqZ + 1200, flatPoolOut);
            assertNotEquals(SurfaceGenerator.DRY, flatPoolOut[1],
                    "flat valley water at 73.5 deg N must remain WET");
        }
    }

    @Test
    void coastalFjordShoreNearSlopingShadowStaysSeaInSurveyedAndWaterLevel() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Align to the 64-block reach-sample grid so the coastal fjord shore pocket sits between a shadowed
        // sample to the north (zSample) and a dry bar sample to the south (zSample + 64), with open sea
        // within TIDAL_REACH (256 blocks) at zSample + 128. This guarantees BOTH:
        //   1. seaWater == false (does not short-circuit at line 730)
        //   2. inShadowReach == true (would strip to DRY if isCoastalSea did not precede shouldStripShadowReach)
        EarthSettings settings = EarthSettings.at(36.2, -75.8);
        MercatorGrid grid = new MercatorGrid(settings.metresPerBlock());
        GeoPoint home = new GeoPoint(36.2, -75.8);
        int homeZ = (int) Math.floor(grid.blockZ(home));
        int zSample = Math.floorDiv(homeZ, 64) * 64 + 32;
        int dz0 = zSample - homeZ;
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> {
                    if (dz >= dz0 + 110.0) return -15.0;
                    if (dz >= dz0 + 50.0) return 1.0;
                    if (dz >= dz0 + 26.0) return 1.5 + (dz0 + 50.0 - dz) * 0.25;
                    return 10.0 + (dz0 + 26.0 - dz) * 0.45;
                },
                (dx, dz) -> {
                    if (dz >= dz0 + 110) return WorldCoverSource.NO_DATA;
                    if (dz >= dz0 + 26 && dz <= dz0 + 50 && Math.abs(dx) <= 200) return WorldCoverSource.WATER;
                    if (dz >= dz0 - 800 && dz <= dz0 + 10 && Math.abs(dx) <= 650) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                })) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            v2.enableHydrologyV2();

            // Sloping mountain shadow above the fjord is stripped to DRY in both ground() and waterLevelAt().
            int[] shadowOut = new int[2];
            v2.ground(world.homeX, world.homeZ + dz0 - 350, shadowOut);
            assertEquals(SurfaceGenerator.DRY, shadowOut[1], "high mountain shadow above fjord must be stripped to DRY");
            assertEquals(SurfaceGenerator.DRY, v2.waterLevelAt(0, world.homeX, world.homeZ + dz0 - 350),
                    "waterLevelAt on stripped mountain shadow above fjord must return DRY");

            // Coastal fjord shore columns with !seaWater AND active shadow reach stay SEA in surveyed() and waterLevel().
            for (int offset : new int[]{34, 40}) {
                int z = world.homeZ + dz0 + offset;
                assertFalse(v2.seaWaterAt(world.homeX, z, 1),
                        "precondition: fjord shore at offset=" + offset + " must have seaWater == false");
                assertTrue(v2.inShadowReach(world.homeX, z, Double.NaN, Double.NaN),
                        "precondition: fjord shore at offset=" + offset + " must lie in an active shadow reach");
                int[] shoreOut = new int[2];
                v2.ground(world.homeX, z, shoreOut);
                assertEquals(EarthGenerator.SEA, shoreOut[1],
                        "coastal fjord shore at offset=" + offset + " with active shadow reach must stay SEA in surveyed()");
                assertEquals(EarthGenerator.SEA, v2.waterLevelAt(0, world.homeX, z),
                        "coastal fjord shore at offset=" + offset + " with active shadow reach must stay SEA in waterLevel()");
            }
        }
    }

    @Test
    void flatCirqueTarnAndConnectedFlatReachStayWetAtNearAndFarLodWithoutDrainageCalls() throws Exception {
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

        // Continuous mountain-wall-to-cirque-floor geometry:
        // - Mountain slope (dz < 180, slope 0.45) meeting a flat cirque/valley floor (dz >= 180, elevation 100 m).
        // - Giant sloping mountain shadow (dz in [-1150, 175], |dx| <= 850, all on the 0.45 slope).
        // - Small flat cirque tarn (< REACH_SAMPLES, 90x90 blocks centered at (300, 230), dz in [185, 275], flat 100 m)
        //   separated from the shadow by a dry lip yet within NEAR of the shadow reach.
        // - Bounded connected flat reach (dz in (175, 268], dx in [-380, -220], flat 100 m -> < 10% of combined body):
        //   (a) At (-300, 168), the bounded flat reach lies 12..100 blocks south -> must stay WET identically at
        //       voxel = 1, voxel = 8, AND voxel = 64 (zero Drainage/SurfaceWaterSource calls at voxel = 64).
        //   (b) At (-300, 60), the flat reach lies 120 blocks south -> must strip to DRY at BOTH voxel = 1 AND
        //       voxel = 64 (no 8x far-LOD over-protection!).
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> dz < 180.0 ? 100.0 + (180.0 - dz) * 0.45 : 100.0,
                (dx, dz) -> {
                    if (dz >= -1150 && dz <= 175 && Math.abs(dx) <= 850) return WorldCoverSource.WATER;
                    if (Math.abs(dx - 300) <= 45 && dz >= 185 && dz <= 275) return WorldCoverSource.WATER;
                    if (dz > 175 && dz <= 268 && dx >= -380 && dx <= -220) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                });
             SurfaceWaterSource sws = new SurfaceWaterSource(
                     URI.create("http://127.0.0.1:" + waterServer.getAddress().getPort() + "/water/"),
                     null, true, 4, 1, Duration.ofSeconds(5))) {
            Drainage spyDrainage = new Drainage(
                    (zoom, tx, ty) -> {
                        drainageTileCalls.incrementAndGet();
                        throw new AssertionError("unexpected Drainage tile fetch");
                    },
                    (lat, lon) -> 1000.0, 4);
            RiverMap spyRivers = new RiverMap(sws, spyDrainage, 8, true);

            EarthGenerator v2Far = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null, spyRivers);
            v2Far.enableHydrologyV2();

            int connectedX = world.homeX - 300, connectedZ = world.homeZ + 168;
            int distantShadowX = world.homeX - 300, distantShadowZ = world.homeZ + 60;
            int tarnX = world.homeX + 300, tarnZ = world.homeZ + 230;
            int shadowX = world.homeX, shadowZ = world.homeZ - 400;

            // 1. Coarse far LOD (voxel = 64) with spyRivers: ZERO Drainage or SurfaceWaterSource calls.
            int[] farShadow = new int[2], tarnFar = new int[2], connectedFar = new int[2], distantFar = new int[2];
            v2Far.farGround(6, shadowX, shadowZ, farShadow);
            v2Far.farGround(6, tarnX, tarnZ, tarnFar);
            v2Far.farGround(6, connectedX, connectedZ, connectedFar);
            v2Far.farGround(6, distantShadowX, distantShadowZ, distantFar);
            assertEquals(SurfaceGenerator.DRY, farShadow[1], "disconnected sloping shadow must strip at voxel=64");
            assertNotEquals(SurfaceGenerator.DRY, tarnFar[1], "flat cirque tarn must stay WET at voxel=64");
            assertNotEquals(SurfaceGenerator.DRY, connectedFar[1],
                    "same column 12..100 blocks from bounded flat reach must stay WET at voxel=64");
            assertEquals(SurfaceGenerator.DRY, distantFar[1],
                    "sloping shadow 120 blocks from flat reach must strip to DRY at voxel=64 (no far-LOD over-protection)");
            assertEquals(0, drainageTileCalls.get(),
                    "coarse far-LOD (voxel=64) must never query Drainage");
            assertEquals(0, surfaceWaterRequests.get(),
                    "coarse far-LOD (voxel=64) must never query SurfaceWaterSource");

            // 2. Near LOD (voxel = 1) and mid LOD (voxel = 8) on the EXACT SAME COLUMNS.
            EarthGenerator v2Near = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            v2Near.enableHydrologyV2();
            assertTrue(v2Near.inShadowReach(tarnX, tarnZ, Double.NaN, 100.0),
                    "flat cirque tarn must lie within NEAR of the shadow reach so flat-slope protection is genuinely exercised");
            assertTrue(v2Near.inShadowReach(connectedX, connectedZ, Double.NaN, 100.0),
                    "connected column must lie inside the shadow reach so hasNearbyFlatWater is genuinely exercised");

            for (int level : new int[]{0, 3}) {
                int voxel = 1 << level;
                int[] shadowOut = new int[2], tarnOut = new int[2], connectedOut = new int[2], distantOut = new int[2];
                if (level == 0) {
                    v2Near.ground(shadowX, shadowZ, shadowOut);
                    v2Near.ground(tarnX, tarnZ, tarnOut);
                    v2Near.ground(connectedX, connectedZ, connectedOut);
                    v2Near.ground(distantShadowX, distantShadowZ, distantOut);
                } else {
                    v2Near.farGround(level, shadowX, shadowZ, shadowOut);
                    v2Near.farGround(level, tarnX, tarnZ, tarnOut);
                    v2Near.farGround(level, connectedX, connectedZ, connectedOut);
                    v2Near.farGround(level, distantShadowX, distantShadowZ, distantOut);
                }
                assertEquals(SurfaceGenerator.DRY, shadowOut[1], "disconnected sloping shadow must strip at voxel=" + voxel);
                assertNotEquals(SurfaceGenerator.DRY, tarnOut[1], "flat cirque tarn must stay WET at voxel=" + voxel);
                assertNotEquals(SurfaceGenerator.DRY, connectedOut[1],
                        "same column 12..100 blocks from bounded flat reach must stay WET at voxel=" + voxel);
                assertEquals(SurfaceGenerator.DRY, distantOut[1],
                        "sloping shadow 120 blocks from flat reach must strip to DRY at voxel=" + voxel);
            }
        } finally {
            waterServer.stop(0);
        }
    }

    @Test
    void waterBackedByDrainageWithoutRiverStrengthRespectsWarpedCoverCoordinates() throws Exception {
        EarthShorelineTest.bootRegistries();
        HttpServer waterServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        waterServer.createContext("/water/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        waterServer.start();

        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> dz < 200.0 ? 100.0 + (200.0 - dz) * 0.45 : 100.0,
                (dx, dz) -> {
                    if (dz >= -650 && dz <= 100 && Math.abs(dx) <= 650) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                });
             SurfaceWaterSource sws = new SurfaceWaterSource(
                     URI.create("http://127.0.0.1:" + waterServer.getAddress().getPort() + "/water/"),
                     null, true, 4, 1, Duration.ofSeconds(5))) {
            Drainage noopDrainage = new Drainage((z, tx, ty) -> { throw new AssertionError("cached tile expected"); },
                    (lat, lon) -> 1000.0, 4);
            EarthGenerator probeGen = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            MercatorGrid grid = new MercatorGrid(world.generator.settings().metresPerBlock());

            // Find a column inside the sloping shadow where voxel=1 cover warp crosses a RiverMap pixel boundary.
            int testX = Integer.MIN_VALUE, testZ = world.homeZ - 300;
            int warpedCol = -1, warpedRow = -1, rawCol = -1, rawRow = -1;
            for (int dx = -40; dx <= 40; dx++) {
                int x = world.homeX + dx;
                GeoPoint raw = grid.tryGeographic(x + 0.5, testZ + 0.5);
                GeoPoint warped = probeGen.warpedCoverPoint(x, testZ, 1);
                assertNotNull(raw);
                assertNotNull(warped);
                int rc = riverCol(raw), rr = riverRow(raw), wc = riverCol(warped), wr = riverRow(warped);
                if (rc != wc || rr != wr) {
                    testX = x;
                    rawCol = rc; rawRow = rr;
                    warpedCol = wc; warpedRow = wr;
                    break;
                }
            }
            assertNotEquals(Integer.MIN_VALUE, testX,
                    "expected a column where voxel=1 cover warp crosses a RiverMap pixel boundary");
            GeoPoint center = grid.tryGeographic(testX + 0.5, testZ + 0.5);
            final int fWarpedCol = warpedCol, fWarpedRow = warpedRow, fRawCol = rawCol, fRawRow = rawRow;

            // Case 1: strength == 0 (so rawCover stays WorldCover WATER, NOT RIVER) and drained == true ONLY at warped pixel.
            RiverMap warpedOnlyDrained = new RiverMap(sws, noopDrainage, 16, true);
            injectCustomRiverTiles(warpedOnlyDrained, center, (byte) 0, (c, r) -> c == fWarpedCol && r == fWarpedRow);
            EarthGenerator v2Warped = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null, warpedOnlyDrained);
            v2Warped.enableHydrologyV2();
            int[] outWarped = new int[2];
            v2Warped.ground(testX, testZ, outWarped);
            assertNotEquals(SurfaceGenerator.DRY, outWarped[1],
                    "WorldCover WATER (strength==0) with drained==true at warpedCoverPoint must stay WET");

            // Case 2: strength == 0 and drained == true ONLY at unwarped pixel (false at warped pixel) -> must strip to DRY.
            RiverMap rawOnlyDrained = new RiverMap(sws, noopDrainage, 16, true);
            injectCustomRiverTiles(rawOnlyDrained, center, (byte) 0, (c, r) -> c == fRawCol && r == fRawRow);
            EarthGenerator v2RawOnly = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null, rawOnlyDrained);
            v2RawOnly.enableHydrologyV2();
            int[] outRawOnly = new int[2];
            v2RawOnly.ground(testX, testZ, outRawOnly);
            assertEquals(SurfaceGenerator.DRY, outRawOnly[1],
                    "WorldCover WATER with drained==false at warpedCoverPoint (even if true at unwarped point) must strip to DRY");
        } finally {
            waterServer.stop(0);
        }
    }

    @Test
    void bridgedWaterLevelAndShoreStayConsistentWithStrippedShadowAndLegacyUnchanged() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Sloping mountain shadow (dz in [-650, 100], dx in [-650, 650], slope 0.45):
        // - BUILT_UP road strip across the shadow at dx in [-103, -97], dz in [-400, -200] to exercise bridged() -> waterLevel().
        // - Dry rock hollow 5 blocks south of the shadow edge at (0, 105) dipped 3 m below the shadow rim to exercise shore().
        java.util.function.DoubleBinaryOperator elev = (dx, dz) -> {
            double base = dz < 200.0 ? 100.0 + (200.0 - dz) * 0.45 : 100.0;
            if (Math.abs(dx) <= 4 && Math.abs(dz - 105) <= 4) return base - 3.0;
            return base;
        };
        try (EarthShorelineTest.SyntheticWorld shadowWorld = new EarthShorelineTest.SyntheticWorld(
                elev,
                (dx, dz) -> {
                    if (Math.abs(dx + 100) <= 3 && dz >= -400 && dz <= -200) return WorldCoverSource.BUILT_UP;
                    if (dz >= -650 && dz <= 100 && Math.abs(dx) <= 650) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                });
             EarthShorelineTest.SyntheticWorld dryControl = new EarthShorelineTest.SyntheticWorld(
                     elev,
                     (dx, dz) -> {
                         if (Math.abs(dx + 100) <= 3 && dz >= -400 && dz <= -200) return WorldCoverSource.BUILT_UP;
                         return WorldCoverSource.BARE;
                     })) {
            // 1. Hydrology v2: bridge over stripped shadow sees DRY; shore() does NOT lift dry rock hollow beside shadow.
            EarthGenerator v2Shadow = new EarthGenerator(1234L, EarthShorelineTest.biomes, shadowWorld.generator.settings(),
                    shadowWorld.tiles, shadowWorld.cover, null, null);
            v2Shadow.enableHydrologyV2();
            EarthGenerator v2Control = new EarthGenerator(1234L, EarthShorelineTest.biomes, dryControl.generator.settings(),
                    dryControl.tiles, dryControl.cover, null, null);
            v2Control.enableHydrologyV2();

            assertEquals(SurfaceGenerator.DRY, v2Shadow.waterLevelAt(0, shadowWorld.homeX - 92, shadowWorld.homeZ - 300),
                    "v2 waterLevel() on stripped sloping shadow must return DRY");
            int[] v2Road = new int[2];
            v2Shadow.ground(shadowWorld.homeX - 100, shadowWorld.homeZ - 300, v2Road);
            assertEquals(SurfaceGenerator.DRY, v2Road[1],
                    "v2 road across stripped sloping shadow must stay DRY (not bridged over phantom water)");

            int[] v2HollowShadow = new int[2], v2HollowControl = new int[2];
            v2Shadow.ground(shadowWorld.homeX, shadowWorld.homeZ + 105, v2HollowShadow);
            v2Control.ground(dryControl.homeX, dryControl.homeZ + 105, v2HollowControl);
            assertEquals(SurfaceGenerator.DRY, v2HollowShadow[1], "dry rock hollow beside shadow must stay DRY");
            assertEquals(v2HollowControl[0], v2HollowShadow[0],
                    "v2 shore() must not lift dry rock hollow adjacent to a stripped sloping mountain shadow");

            // 2. Legacy (!hydrologyV2): waterLevel() stays WET and road across shadow is still bridged.
            assertNotEquals(SurfaceGenerator.DRY, shadowWorld.generator.waterLevelAt(0, shadowWorld.homeX - 92, shadowWorld.homeZ - 300),
                    "legacy (!hydrologyV2) waterLevel() must remain WET on sloping shadow");
            int[] legacyRoad = new int[2];
            shadowWorld.ground(-100, -300, legacyRoad);
            assertNotEquals(SurfaceGenerator.DRY, legacyRoad[1],
                    "legacy (!hydrologyV2) road across sloping shadow preserves legacy bridged behavior");
        }
    }

    @Test
    void pureShadowCellReturnsEmptyReachLevelsWithoutLeakingIntoAdjacentUplandPond() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Align a pure-shadow WATER_CELL directly to the west of a cell containing only a small sub-grid
        // upland pond (24x24 blocks, < REACH_SAMPLES) sitting on a 600 m bench, far above the shadow cell's
        // ~200 m raw waterCell.level and outside NEAR of the shadow samples.
        EarthSettings settings = EarthSettings.at(36.2, -75.8);
        MercatorGrid grid = new MercatorGrid(settings.metresPerBlock());
        GeoPoint home = new GeoPoint(36.2, -75.8);
        int homeX = (int) Math.floor(grid.blockX(home));
        int homeZ = (int) Math.floor(grid.blockZ(home));
        int shadowCx = Math.floorDiv(homeX, EarthGenerator.WATER_CELL);
        int shadowCz = Math.floorDiv(homeZ, EarthGenerator.WATER_CELL);
        int cellMinDx = shadowCx * EarthGenerator.WATER_CELL - homeX;
        int cellMaxDx = cellMinDx + EarthGenerator.WATER_CELL;
        int cellMinDz = shadowCz * EarthGenerator.WATER_CELL - homeZ;
        int cellMaxDz = cellMinDz + EarthGenerator.WATER_CELL;
        // Put the sub-grid upland pond just past the east border of the shadow cell, > NEAR (~115 blocks) east
        // of the shadow's eastern edge so lakeSurface() queries the shadow cell in its 2x2 cell interpolation.
        int shadowEastDx = cellMaxDx - 128;
        int pondDx = cellMaxDx + 40;
        int pondDz = (cellMinDz + cellMaxDz) / 2;

        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> {
                    if (dx >= cellMaxDx - 40) return 600.0;
                    return 100.0 + Math.max(0.0, cellMaxDz + 400.0 - dz) * 0.45;
                },
                (dx, dz) -> {
                    if (dx >= cellMinDx - 256 && dx <= shadowEastDx && dz >= cellMinDz - 256 && dz <= cellMaxDz + 256) {
                        return WorldCoverSource.WATER;
                    }
                    if (Math.abs(dx - pondDx) <= 16 && Math.abs(dz - pondDz) <= 16) {
                        return WorldCoverSource.WATER;
                    }
                    return WorldCoverSource.BARE;
                })) {
            // Hydrology v2: pure-shadow cell must emit empty levels[] and never pull the adjacent 600 m pond down.
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, world.generator.settings(),
                    world.tiles, world.cover, null, null);
            v2.enableHydrologyV2();

            EarthGenerator.Reaches v2Reaches = v2.reachLevels(shadowCx, shadowCz);
            assertTrue(anyBitSet(v2Reaches.shadow()), "precondition: shadow cell must have shadowBits set");
            assertEquals(0, v2Reaches.levels().length,
                    "pure-shadow cell in v2 must return empty levels[] instead of falling back to waterCell.level");
            assertEquals(0, v2Reaches.drownedLevels().length,
                    "pure-shadow cell in v2 must return empty drownedLevels[]");

            int[] pondOut = new int[2];
            v2.ground(world.homeX + pondDx, world.homeZ + pondDz, pondOut);
            assertNotEquals(SurfaceGenerator.DRY, pondOut[1], "sub-grid upland pond must stay WET");
            assertTrue(pondOut[1] >= EarthGenerator.SEA + 595 && pondOut[1] <= EarthGenerator.SEA + 605,
                    "sub-grid upland pond must keep its ~600 m local elevation rather than leaking shadow cell level, got " + pondOut[1]);

            // Legacy (!hydrologyV2): pure-shadow cell still produces non-empty legacy reaches (zero legacy change).
            EarthGenerator.Reaches legacyReaches = world.generator.reachLevels(shadowCx, shadowCz);
            assertTrue(legacyReaches.levels().length >= 1,
                    "legacy (!hydrologyV2) reachLevels must preserve non-empty levels[]");
        }
    }

    @Test
    void tidalCoastalSeaAndSteepSeaColumnsStayConsistentAcrossSurveyedWaterLevelAndShore() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Align to the 64-block reach-sample grid:
        // Part A: Tidal coastal water pocket (elevation 5 m -> SEA + 5, within TIDAL = 6) separated from open sea
        //         at dz >= dz0 + 110 (within TIDAL_REACH = 256 so besideSea == true, while seaWater == false),
        //         with an adjacent dry rock hollow dipped to 1 m (within shore()'s 4 m lift band).
        //         In hydrologyV2: surveyed and waterLevel BOTH return SEA, and shore() returns DRY so the hollow
        //         is NEVER lifted above SEA.
        EarthSettings settings = EarthSettings.at(36.2, -75.8);
        MercatorGrid grid = new MercatorGrid(settings.metresPerBlock());
        GeoPoint home = new GeoPoint(36.2, -75.8);
        int homeZ = (int) Math.floor(grid.blockZ(home));
        int zSample = Math.floorDiv(homeZ, 64) * 64 + 32;
        int dz0 = zSample - homeZ;

        java.util.function.DoubleBinaryOperator tidalElev = (dx, dz) -> {
            if (dz >= dz0 + 110.0) return -15.0;
            if (dz < dz0 + 25.0) return 1.5;
            if (dz <= dz0 + 50.0) return 5.0;
            return 2.0;
        };
        try (EarthShorelineTest.SyntheticWorld tidalWorld = new EarthShorelineTest.SyntheticWorld(
                tidalElev,
                (dx, dz) -> {
                    if (dz >= dz0 + 110) return WorldCoverSource.NO_DATA;
                    if (dz >= dz0 + 25 && dz <= dz0 + 50 && Math.abs(dx) <= 120) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                });
             EarthShorelineTest.SyntheticWorld dryControl = new EarthShorelineTest.SyntheticWorld(
                     tidalElev,
                     (dx, dz) -> dz >= dz0 + 110 ? WorldCoverSource.NO_DATA : WorldCoverSource.BARE)) {
            EarthGenerator v2Tidal = new EarthGenerator(1234L, EarthShorelineTest.biomes, tidalWorld.generator.settings(),
                    tidalWorld.tiles, tidalWorld.cover, null, null);
            v2Tidal.enableHydrologyV2();
            EarthGenerator v2Control = new EarthGenerator(1234L, EarthShorelineTest.biomes, dryControl.generator.settings(),
                    dryControl.tiles, dryControl.cover, null, null);
            v2Control.enableHydrologyV2();

            int tidalZ = tidalWorld.homeZ + dz0 + 36;
            assertFalse(v2Tidal.seaWaterAt(tidalWorld.homeX, tidalZ, 1),
                    "precondition: tidal coastal pocket must have seaWater == false");
            int[] tidalOut = new int[2];
            v2Tidal.ground(tidalWorld.homeX, tidalZ, tidalOut);
            assertEquals(EarthGenerator.SEA, tidalOut[1], "tidal SEA+5 coastal column must return SEA in surveyed()");
            assertEquals(EarthGenerator.SEA, v2Tidal.waterLevelAt(0, tidalWorld.homeX, tidalZ),
                    "tidal SEA+5 coastal column must return SEA in waterLevel()");

            // Find a dry column right north of the warped tidal water edge (so shore()'s 3..8-block probe hits the
            // tidal coastal water!) and verify shore() does NOT lift it above its dry-control height.
            int hollowZ = Integer.MIN_VALUE;
            int[] hollowTidal = new int[2], hollowControl = new int[2];
            for (int off = 24; off >= 14; off--) {
                int candZ = tidalWorld.homeZ + dz0 + off;
                v2Tidal.ground(tidalWorld.homeX, candZ, hollowTidal);
                if (hollowTidal[1] == SurfaceGenerator.DRY) {
                    hollowZ = candZ;
                    v2Control.ground(dryControl.homeX, candZ, hollowControl);
                    break;
                }
            }
            assertNotEquals(Integer.MIN_VALUE, hollowZ, "expected dry shore column immediately north of tidal coastal pocket");
            assertEquals(EarthGenerator.SEA, v2Tidal.waterLevelAt(0, tidalWorld.homeX, hollowZ + 8),
                    "precondition: shore()'s 8-block probe south from hollowZ must hit the tidal coastal-sea column");
            assertEquals(hollowControl[0], hollowTidal[0],
                    "v2 shore() must NOT lift dry ground beside a tidal coastal-sea column to SEA + 5");
        }

        // Part B: Steep elevated coastal cliff column where seaWater == true && seaGround == false:
        //         surveyed() and waterLevel() must agree (both strip the unbacked steep cliff shadow to DRY)
        //         instead of waterLevel() blindly returning SEA on seaWater == true.
        try (EarthShorelineTest.SyntheticWorld steepSeaWorld = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> {
                    if (dz >= dz0 + 140.0) return -15.0;
                    if (dz >= dz0 + 52.0) return -1.5;
                    return 2.0 + (dz0 + 52.0 - dz) * 1.2;
                },
                (dx, dz) -> {
                    if (dz >= dz0 + 140) return WorldCoverSource.NO_DATA;
                    if (dz >= dz0 - 500 && Math.abs(dx) <= 600) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                })) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, steepSeaWorld.generator.settings(),
                    steepSeaWorld.tiles, steepSeaWorld.cover, null, null);
            v2.enableHydrologyV2();

            int steepZ = steepSeaWorld.homeZ + dz0 + 24;
            assertTrue(v2.seaWaterAt(steepSeaWorld.homeX, steepZ, 1),
                    "precondition: steep cliff water joined to adjacent open-sea sample must have seaWater == true");
            int[] surveyedOut = new int[2];
            v2.ground(steepSeaWorld.homeX, steepZ, surveyedOut);
            int waterLvl = v2.waterLevelAt(0, steepSeaWorld.homeX, steepZ);
            assertEquals(SurfaceGenerator.DRY, surveyedOut[1],
                    "steep elevated cliff water with seaWater && !seaGround must strip to DRY in surveyed()");
            assertEquals(surveyedOut[1], waterLvl,
                    "v2 waterLevel() must agree with surveyed() on steep elevated seaWater && !seaGround column");
        }
    }

    @Test
    void rotatedHighLatitudeShadowApronStrippedEndToEndAt73Point5North() throws Exception {
        EarthShorelineTest.bootRegistries();
        // 73.5° N: Mercator groundMetresPerBlock ≈ cos(73.5°) ≈ 0.284 m/block -> 64-block reach spacing ≈ 18.2 m.
        // A 45°-rotated 0.45-grade mountain slope has cardinal reach step 0.45/sqrt(2) * 18.2 ≈ 5.78 m (< DROP = 8 m)
        // and block-slope steep ≈ 0.45 * 0.284 ≈ 0.128 (<= CLIFF_SHADOW_STEEP = 0.7), so ONLY rotated Phase 1
        // shadow detection + shouldStripShadowReach can detect and strip it.
        double lat = 73.5, lon = 19.0;
        // Equatorial-scale Mercator world (1.0 m/block at equator -> cos(73.5°) ≈ 0.284 m/block at 73.5° N).
        EarthSettings eqSettings = new EarthSettings(lat, lon, 1.0, MercatorGrid.PROJECTION_ID);
        MercatorGrid eqGrid = new MercatorGrid(eqSettings.metresPerBlock());
        GeoPoint home = new GeoPoint(lat, lon);
        int eqX = (int) Math.floor(eqGrid.blockX(home));
        int eqZ = (int) Math.floor(eqGrid.blockZ(home));
        double mpb = eqGrid.groundMetresPerBlock(eqX + 0.5, eqZ + 0.5);
        double diagPhysicalSlope = 0.45 / Math.sqrt(2.0);
        double cardinalReachStep = diagPhysicalSlope * (64.0 * mpb);
        assertTrue(cardinalReachStep < 8.0,
                "precondition: cardinal reach step at 73.5° N on equatorial grid must be < DROP (8 m), got " + cardinalReachStep);

        // SyntheticWorld uses EarthSettings.at(73.5, 19.0) where (dx, dz) are 1:1 physical ground metres at 73.5° N,
        // so elev(dx, dz) = 500 + (dx + dz) * (0.45 / sqrt(2)) has exact 0.45 physical grade in geographic space.
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                EarthShorelineTest.biomes, lat, lon,
                (dx, dz) -> 500.0 + (dx + dz) * diagPhysicalSlope,
                (dx, dz) -> Math.abs(dx) <= 450 && Math.abs(dz) <= 450
                        ? WorldCoverSource.WATER
                        : WorldCoverSource.BARE)) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, eqSettings,
                    world.tiles, world.cover, null, null);
            v2.enableHydrologyV2();
            EarthGenerator legacy = new EarthGenerator(1234L, EarthShorelineTest.biomes, eqSettings,
                    world.tiles, world.cover, null, null);

            assertTrue(v2.inShadowReach(eqX, eqZ, Double.NaN, 500.0),
                    "45°-rotated 0.45-grade shadow apron at 73.5° N must be detected as a shadow reach");
            int[] v2Out = new int[2], legacyOut = new int[2];
            v2.ground(eqX, eqZ, v2Out);
            legacy.ground(eqX, eqZ, legacyOut);

            assertEquals(SurfaceGenerator.DRY, v2Out[1],
                    "45°-rotated 0.45-grade shadow apron at 73.5° N must strip to DRY in hydrologyV2");
            assertNotEquals(SurfaceGenerator.DRY, legacyOut[1],
                    "legacy (!hydrologyV2) must remain unchanged and keep the column wet");
        }
    }

    @Test
    void hasNearbyFlatWaterRejectsFourMetreDryGapAndFollowsContinuousRiverOnlyStream() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Part A: Real 1-pixel (~4.3 m) East-West dry rock gap at 62.5° N (Trollveggen latitude, where 1 WorldCover
        //         pixel in longitude is 9.28 * cos(62.5°) ≈ 4.3 m = ~4.3 blocks) between a sloping shadow (dx <= 174)
        //         and a flat pool (dx >= 180, 100 m) MUST stop the 4 m ray step and NOT protect the shadow column,
        //         even though the flat pool lies only 8 m away across the dry gap.
        try (EarthShorelineTest.SyntheticWorld gapWorld = new EarthShorelineTest.SyntheticWorld(
                EarthShorelineTest.biomes, 62.5, 7.7,
                (dx, dz) -> dx < 180.0 ? 100.0 + (180.0 - dx) * 0.45 : 100.0,
                (dx, dz) -> {
                    if (dx >= -1150 && dx <= 174 && Math.abs(dz) <= 850) return WorldCoverSource.WATER;
                    if (dx >= 180 && dx <= 320 && Math.abs(dz) <= 850) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                })) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, gapWorld.generator.settings(),
                    gapWorld.tiles, gapWorld.cover, null, null);
            v2.enableHydrologyV2();

            // Locate the sloped shadow column immediately west of the ~4.3 m dry gap (accounting for cover warp):
            // dist = 4 m probes +4 blocks east (lands inside the dry gap), while +8 blocks east is the flat pool.
            int probeZ = gapWorld.homeZ;
            int probeX = Integer.MIN_VALUE;
            for (int off = 155; off <= 195; off++) {
                int x = gapWorld.homeX + off;
                if (v2.isWorldCoverWater(x, probeZ, 1)
                        && !v2.isWorldCoverWater(x + 4, probeZ, 1)
                        && v2.isWorldCoverWater(x + 8, probeZ, 1)) {
                    probeX = x;
                    break;
                }
            }
            assertNotEquals(Integer.MIN_VALUE, probeX,
                    "precondition: must locate shadow edge column ~4 m west of the 4 m dry gap with flat pool 8 m away");
            assertTrue(v2.inShadowReach(probeX, probeZ, Double.NaN, 100.0),
                    "precondition: column beside dry gap must be inside the shadow reach");

            int[] gapOut = new int[2];
            v2.ground(probeX, probeZ, gapOut);
            assertEquals(SurfaceGenerator.DRY, gapOut[1],
                    "4 m dry gap between sloped shadow column and flat pool 8 m away must NOT protect the shadow column");
        }

        // Part B: Narrow RIVER-only stream (WorldCover == BARE, RiverMap strength >= EDGE, drained == false)
        //         continuously connected to a flat RIVER reach 12 m south MUST be protected at voxel = 1,
        //         while a distant sloped RIVER-only column 60 m north of the flat reach strips to DRY.
        HttpServer waterServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        waterServer.createContext("/water/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        waterServer.start();
        try (EarthShorelineTest.SyntheticWorld riverWorld = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> dz < 160.0 ? 100.0 + (160.0 - dz) * 0.45 : 100.0,
                (dx, dz) -> dz >= -1150 && dz <= 130 && Math.abs(dx) <= 850
                        ? WorldCoverSource.WATER
                        : WorldCoverSource.BARE);
             SurfaceWaterSource sws = new SurfaceWaterSource(
                     URI.create("http://127.0.0.1:" + waterServer.getAddress().getPort() + "/water/"),
                     null, true, 4, 1, Duration.ofSeconds(5))) {
            Drainage noDrainage = new Drainage(
                    (zoom, tx, ty) -> { throw new AssertionError("unexpected Drainage tile fetch"); },
                    (lat, lon) -> 0.0, 4);
            RiverMap riverMap = new RiverMap(sws, noDrainage, 16, true);
            GeoPoint center = new MercatorGrid(riverWorld.generator.settings().metresPerBlock())
                    .geographic(riverWorld.homeX + 0.5, riverWorld.homeZ + 145.5);
            // Uniform strength >= EDGE (200) with drained == false everywhere: dz > 130 is purely RIVER-only.
            injectCustomRiverTiles(riverMap, center, (byte) 200, (c, r) -> false);

            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, riverWorld.generator.settings(),
                    riverWorld.tiles, riverWorld.cover, null, null, riverMap);
            v2.enableHydrologyV2();

            int nearRiverZ = riverWorld.homeZ + 146; // WorldCover == BARE, ~11 m north of flat RIVER reach at dz >= 160
            int farRiverZ = riverWorld.homeZ + 80;   // WorldCover == WATER/RIVER, ~65 m north of flat reach
            assertFalse(v2.isWorldCoverWater(riverWorld.homeX, nearRiverZ, 1),
                    "precondition: near probe column must be RIVER-only (WorldCover != WATER)");
            assertTrue(v2.inShadowReach(riverWorld.homeX, nearRiverZ, Double.NaN, 100.0),
                    "precondition: near RIVER-only probe must lie inside shadow reach");

            int[] nearOut = new int[2], farOut = new int[2];
            v2.ground(riverWorld.homeX, nearRiverZ, nearOut);
            v2.ground(riverWorld.homeX, farRiverZ, farOut);
            assertNotEquals(SurfaceGenerator.DRY, nearOut[1],
                    "RIVER-only sloped column continuously connected to flat RIVER reach ~12 m away must stay WET at voxel = 1");
            assertEquals(SurfaceGenerator.DRY, farOut[1],
                    "distant sloped unbacked column 65 m from flat reach must still strip to DRY");
        } finally {
            waterServer.stop(0);
        }

        // Part C: Mid-ray dry rock rib (18..23 m) between wet sloping water (0..16 m) and flat water (>= 24 m)
        //         MUST stop continuous 1-block ray marching and strip to DRY at voxel = 1, voxel = 8, AND voxel = 64
        //         (discrete {4, 8, 12, 16, 24, 32} jumps previously skipped right over 18..23 m).
        EarthSettings midGapSettings = EarthSettings.at(62.5, 7.7);
        MercatorGrid midGapGrid = new MercatorGrid(midGapSettings.metresPerBlock());
        int midHomeX = (int) Math.floor(midGapGrid.blockX(new GeoPoint(62.5, 7.7)));
        int midSquareWest = Math.floorDiv((int) Math.floor(7.7), WorldCoverSource.SQUARE) * WorldCoverSource.SQUARE;
        double approxLon = midGapGrid.geographic(midHomeX + 177.5, midGapGrid.blockZ(new GeoPoint(62.5, 7.7))).longitude();
        int ov2Col = (int) Math.round(((approxLon - midSquareWest) * WorldCoverSource.PIXELS_PER_DEGREE) / 4.0 - 0.5);
        double ov2CenterLon = midSquareWest + ((ov2Col + 0.5) * 4.0) / WorldCoverSource.PIXELS_PER_DEGREE;
        double ov0CenterLon = midSquareWest + (ov2Col * 4.0 + 2.5) / WorldCoverSource.PIXELS_PER_DEGREE;
        int gapStart = (int) Math.round(midGapGrid.blockX(new GeoPoint(0, ov2CenterLon)) - midHomeX);
        int gapEnd = (int) Math.round(midGapGrid.blockX(new GeoPoint(0, ov0CenterLon)) - midHomeX);
        try (EarthShorelineTest.SyntheticWorld midGapWorld = new EarthShorelineTest.SyntheticWorld(
                EarthShorelineTest.biomes, 62.5, 7.7,
                (dx, dz) -> dx < gapEnd + 1 ? 100.0 + (gapEnd + 1 - dx) * 0.45 : 100.0,
                (dx, dz) -> {
                    if (dx >= -1150 && dx < gapStart && Math.abs(dz) <= 850) return WorldCoverSource.WATER;
                    if (dx > gapEnd && dx <= 320 && Math.abs(dz) <= 850) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                })) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, midGapWorld.generator.settings(),
                    midGapWorld.tiles, midGapWorld.cover, null, null);
            v2.enableHydrologyV2();

            int probeZ = midGapWorld.homeZ;
            int midGapX = Integer.MIN_VALUE;
            for (int off = gapStart - 30; off <= gapStart - 10; off++) {
                int x = midGapWorld.homeX + off;
                boolean wet0to16 = true;
                for (int r = 0; r <= 16; r++) {
                    if (!v2.isWorldCoverWater(x + r, probeZ, 1)) {
                        wet0to16 = false;
                        break;
                    }
                }
                if (!wet0to16) continue;
                boolean dry18to23 = false;
                for (int r = 18; r <= 23; r++) {
                    if (!v2.isWorldCoverWater(x + r, probeZ, 1)) {
                        dry18to23 = true;
                        break;
                    }
                }
                if (!dry18to23) continue;
                if (v2.isWorldCoverWater(x + 24, probeZ, 1)
                        && (x + 24 - midGapWorld.homeX) >= gapEnd + 1
                        && v2.isWorldCoverWater(x, probeZ, 8)
                        && v2.isWorldCoverWater(x, probeZ, 64)) {
                    midGapX = x;
                    break;
                }
            }
            assertNotEquals(Integer.MIN_VALUE, midGapX,
                    "precondition: must locate sloping column with wet 0..16 m, dry rib in 18..23 m, and flat water at 24 m");
            assertTrue(v2.inShadowReach(midGapX, probeZ, Double.NaN, 100.0),
                    "precondition: mid-ray gap column must lie inside the shadow reach");

            int[] outV1 = new int[2], outV8 = new int[2], outV64 = new int[2];
            v2.ground(midGapX, probeZ, outV1);
            v2.farGround(3, midGapX, probeZ, outV8);
            v2.farGround(6, midGapX, probeZ, outV64);
            assertEquals(SurfaceGenerator.DRY, outV1[1],
                    "mid-ray dry rock rib at 18..23 m must stop ray march and strip shadow column to DRY at voxel = 1");
            assertEquals(SurfaceGenerator.DRY, outV8[1],
                    "mid-ray dry rock rib at 18..23 m must stop ray march and strip shadow column to DRY at voxel = 8");
            assertEquals(SurfaceGenerator.DRY, outV64[1],
                    "mid-ray dry rock rib at 18..23 m must stop ray march and strip shadow column to DRY at voxel = 64");
        }
    }

    @Test
    void equidistantShadowTieBreakParityAndShoreSeaWaterInlandConsistency() throws Exception {
        EarthShorelineTest.bootRegistries();

        // Part A: Exact equidistant shadow-vs-wet tie-break parity between lakeSurface() and inShadowReach().
        try (EarthShorelineTest.SyntheticWorld flatWorld = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> 100.0,
                (dx, dz) -> WorldCoverSource.WATER)) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, flatWorld.generator.settings(),
                    flatWorld.tiles, flatWorld.cover, null, null);
            v2.enableHydrologyV2();

            int cx = Math.floorDiv(flatWorld.homeX, EarthGenerator.WATER_CELL);
            int cz = Math.floorDiv(flatWorld.homeZ, EarthGenerator.WATER_CELL);
            long cellKey = it.unimi.dsi.fastutil.HashCommon.mix((long) cx << 32 | cz & 0xFFFFFFFFL);

            float[] samples = new float[16 * 16];
            Arrays.fill(samples, Float.NaN);
            int shadowIdx = 7 * 16 + 7;
            int wetIdx = 7 * 16 + 8;
            samples[wetIdx] = 100f;
            long[] shadowBits = new long[4];
            shadowBits[shadowIdx >> 6] |= 1L << (shadowIdx & 63);
            long[] emptyBits = new long[4];
            EarthGenerator.Reaches synthetic = new EarthGenerator.Reaches(
                    new double[]{100.0}, new boolean[]{false}, samples,
                    emptyBits, emptyBits, emptyBits, shadowBits, null);
            v2.reaches.put(cellKey, synthetic);

            java.lang.reflect.Method warpReachX = EarthGenerator.class.getDeclaredMethod("warpReachX", double.class, double.class);
            warpReachX.setAccessible(true);
            java.lang.reflect.Method lakeSurface = EarthGenerator.class.getDeclaredMethod(
                    "lakeSurface", GlobalElevation.class, double.class, double.class, int.class, boolean[].class);
            lakeSurface.setAccessible(true);
            java.lang.reflect.Method zoomMethod = EarthGenerator.class.getDeclaredMethod("zoom", int.class);
            zoomMethod.setAccessible(true);
            int z1 = (int) zoomMethod.invoke(v2, 1);
            java.lang.reflect.Method elevationMethod = EarthGenerator.class.getDeclaredMethod("elevation", int.class, int.class);
            elevationMethod.setAccessible(true);
            GlobalElevation dem = (GlobalElevation) elevationMethod.invoke(v2, z1, 1);

            // Target warped X where sx = wx / 64.0 - 0.5 == cx * 16 + 7.5 (exact midpoint between si=7 and si=8).
            double targetWx = (cx * 16 + 8) * 64.0;
            double zMid = (cz * 16 + 7.5) * 64.0;
            double lo = targetWx - 80.0, hi = targetWx + 80.0;
            for (int iter = 0; iter < 80; iter++) {
                double mid = 0.5 * (lo + hi);
                double wx = (double) warpReachX.invoke(v2, mid, zMid);
                if (wx < targetWx) lo = mid;
                else hi = mid;
            }
            double xMid = 0.5 * (lo + hi);
            double checkWx = (double) warpReachX.invoke(v2, xMid, zMid);
            double sx = checkWx / 64.0 - 0.5;
            double dShadow = sx - (cx * 16 + 7);
            double dWet = sx - (cx * 16 + 8);
            assertEquals(dShadow * dShadow, dWet * dWet, 0.0,
                    "precondition: bisected midpoint must achieve bit-exact d2_shadow == d2_wet");

            boolean[] tieFlags = new boolean[3];
            lakeSurface.invoke(v2, dem, xMid, zMid, 1, tieFlags);
            boolean tieInShadow = v2.inShadowReach(xMid, zMid, Double.NaN, 100.0);
            assertFalse(tieInShadow, "inShadowReach must use strict < on equidistant shadow-vs-wet tie");
            assertEquals(tieInShadow, tieFlags[2],
                    "lakeSurface shadowNear must match inShadowReach strict < on equidistant tie");

            // Nudging strictly closer to shadow sample makes BOTH true; nudging toward wet sample makes BOTH false.
            boolean[] leftFlags = new boolean[3], rightFlags = new boolean[3];
            lakeSurface.invoke(v2, dem, xMid - 1e-3, zMid, 1, leftFlags);
            lakeSurface.invoke(v2, dem, xMid + 1e-3, zMid, 1, rightFlags);
            assertTrue(v2.inShadowReach(xMid - 1e-3, zMid, Double.NaN, 100.0));
            assertTrue(leftFlags[2], "lakeSurface shadowNear must be true when strictly closer to shadow sample");
            assertFalse(v2.inShadowReach(xMid + 1e-3, zMid, Double.NaN, 100.0));
            assertFalse(rightFlags[2], "lakeSurface shadowNear must be false when strictly closer to wet sample");
        }

        // Part B: shore() consistency on non-shadow, non-coastal inland column with seaWater == true && seaGround == false.
        // Low coastal hill (max elev 52 m -> relief <= MOUNTAINS so shadow filters do not fire) sloping at 1.0 m/block
        // (steep > 0.7 -> seaGround == false) with a WATER strip joining open sea (NO_DATA) at dz >= dz0 + 140
        // (seaWater == true) and surface ≈ SEA + 30 (> SEA + 2*TIDAL so isCoastalSea == false).
        // Dry rock hollow dipped 2.5 m just west of the water edge MUST be lifted by v2 shore() to match surveyed()/waterLevel().
        EarthSettings settings = EarthSettings.at(36.2, -75.8);
        MercatorGrid grid = new MercatorGrid(settings.metresPerBlock());
        GeoPoint home = new GeoPoint(36.2, -75.8);
        int homeZ = (int) Math.floor(grid.blockZ(home));
        int zSample = Math.floorDiv(homeZ, 64) * 64 + 32;
        int dz0 = zSample - homeZ;

        java.util.function.DoubleBinaryOperator coastalHillElev = (dx, dz) -> {
            double base;
            if (dz >= dz0 + 140.0) base = -15.0;
            else if (dz >= dz0 + 62.0) base = -1.5;
            // Steep 1.1 m/block hill across [dz0 - 12, dz0 + 62) -> at testZ = dz0 + 16, base = 50 m, steep = 1.1 > 0.7,
            // and coarse()'s 24-block ring ([dz0 - 8, dz0 + 40]) stays strictly non-level -> seaGround == false.
            else if (dz >= dz0 - 12.0) base = 50.0 + (dz0 + 16.0 - dz) * 1.1;
            else if (dz >= dz0 - 60.0) base = 80.8 - (dz0 - 12.0 - dz) * (30.8 / 48.0);
            else base = 50.0;
            // Hollow on the dry rock side west of the water edge (dx < -1) so dry ground 3..8 blocks west of
            // water dips 1..4 m below the adjacent inland water surface (triggering shore()'s 4 m lift band).
            if (dx < -1.0 && dz < dz0 + 62.0) {
                base -= Math.min(10.0, (-1.0 - dx) * 1.5);
            }
            return base;
        };
        try (EarthShorelineTest.SyntheticWorld inlandSeaWorld = new EarthShorelineTest.SyntheticWorld(
                coastalHillElev,
                (dx, dz) -> {
                    if (dz >= dz0 + 140) return WorldCoverSource.NO_DATA;
                    // Sea-connected WATER strip from dz0 + 2 to NO_DATA at dz0 + 140 -> seaAt[dz0 + 64] = true, so
                    // seaWater(waterX, dz0 + 16, 1) == true via waterBetween to dz0 + 64.
                    if (dx >= 0 && dx <= 600 && dz >= dz0 + 2 && dz < dz0 + 140) return WorldCoverSource.WATER;
                    // Separate 50 m upland lake covering sample dz0 - 64 (80 blocks < NEAR * 64 = 96 blocks from testZ),
                    // separated from the sea strip by a 62-block BARE gap [dz0 - 59, dz0 + 1] (> 1 overview-2 pixel).
                    if (dx >= -400 && dx <= 600 && dz >= dz0 - 500 && dz <= dz0 - 60) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                });
             EarthShorelineTest.SyntheticWorld dryControl = new EarthShorelineTest.SyntheticWorld(
                     coastalHillElev,
                     (dx, dz) -> dz >= dz0 + 140 ? WorldCoverSource.NO_DATA : WorldCoverSource.BARE)) {
            EarthGenerator v2 = new EarthGenerator(1234L, EarthShorelineTest.biomes, inlandSeaWorld.generator.settings(),
                    inlandSeaWorld.tiles, inlandSeaWorld.cover, null, null);
            v2.enableHydrologyV2();
            EarthGenerator v2Control = new EarthGenerator(1234L, EarthShorelineTest.biomes, dryControl.generator.settings(),
                    dryControl.tiles, dryControl.cover, null, null);
            v2Control.enableHydrologyV2();

            EarthGenerator legacy = new EarthGenerator(1234L, EarthShorelineTest.biomes, inlandSeaWorld.generator.settings(),
                    inlandSeaWorld.tiles, inlandSeaWorld.cover, null, null);

            // At testZ = dz0 + 16 (46 blocks north of sea level at dz0 + 62, inside [dz0, dz0 + 64] so seaWater == true
            // via dz0 + 64, and 80 blocks < NEAR = 96 from the 50 m upland lake at dz0 - 64), verify:
            // (1) the inland water column has seaWater == true && seaGround == false and surveyed() == waterLevel() > SEA + 15,
            // (2) a dry hollow column within shore()'s 3..8-block probe that dips 1..4 m below that water level IS lifted
            //     by v2 shore() up to >= waterLvl, while legacy (!hydrologyV2) leaves it unlifted (zero legacy change).
            int testZ = inlandSeaWorld.homeZ + dz0 + 16;
            boolean verifiedWater = false;
            boolean lifted = false;
            StringBuilder diag = new StringBuilder();
            for (int off = 12; off >= -20 && !lifted; off--) {
                int candX = inlandSeaWorld.homeX + off;
                int[] outV2 = new int[2];
                v2.ground(candX, testZ, outV2);
                if (outV2[1] != SurfaceGenerator.DRY) continue;
                // Find the eastern water column at r = 3 or r = 8 matching shore()'s exact east probe radii.
                int waterLvl = SurfaceGenerator.DRY;
                for (int r : new int[]{3, 8}) {
                    int wx = candX + r;
                    int[] probe = new int[2];
                    v2.ground(wx, testZ, probe);
                    if (probe[1] != SurfaceGenerator.DRY && probe[1] != EarthGenerator.SEA && v2.seaWaterAt(wx, testZ, 1)) {
                        int wl = v2.waterLevelAt(0, wx, testZ);
                        assertTrue(probe[1] > EarthGenerator.SEA + 15,
                                "inland seaWater && !seaGround column must keep its elevated inland surface, got " + probe[1]);
                        assertEquals(probe[1], wl,
                                "surveyed() and waterLevel() must agree on inland seaWater && !seaGround column");
                        verifiedWater = true;
                        waterLvl = Math.max(waterLvl, wl);
                    }
                }
                if (waterLvl == SurfaceGenerator.DRY) continue;
                int[] outCtrl = new int[2], outLegacy = new int[2];
                v2Control.ground(dryControl.homeX + off, testZ, outCtrl);
                legacy.ground(candX, testZ, outLegacy);
                diag.append(" (off=").append(off).append(",ctrl=").append(outCtrl[0])
                        .append(",leg=").append(outLegacy[0]).append(",v2=").append(outV2[0])
                        .append(",w=").append(waterLvl).append(")");
                if (outCtrl[0] < waterLvl && outCtrl[0] >= waterLvl - 4) {
                    assertTrue(outV2[0] > outCtrl[0],
                            "v2 shore() must lift dry hollow beside inland seaWater && !seaGround column (ctrl="
                                    + outCtrl[0] + ", v2=" + outV2[0] + ", water=" + waterLvl + ")");
                    assertTrue(outV2[0] >= waterLvl,
                            "lifted dry hollow (" + outV2[0] + ") must reach inland water surface (" + waterLvl + ")");
                    assertEquals(outCtrl[0], outLegacy[0],
                            "legacy (!hydrologyV2) shore() must retain exact !seaWater skip behavior");
                    lifted = true;
                }
            }
            assertTrue(verifiedWater, "precondition: must observe inland seaWater && !seaGround water column");
            assertTrue(lifted, "must verify at least one dry hollow column within shore()'s 4 m band is lifted by v2 shore();" + diag);
        }
    }
}