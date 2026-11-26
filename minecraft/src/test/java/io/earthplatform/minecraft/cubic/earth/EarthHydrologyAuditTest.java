package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.globe.MemoryPressure;
import io.earthplatform.geography.globe.MemoryPressureTestBridge;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator.HydroCache;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
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
import org.junit.jupiter.api.Test;

/**
 * Focused regression tests for Wave 2 PR 6 hydrology fixes in {@link EarthGenerator}:
 * HYDRO-07 (single-flight deduplication + bounded {@link MemoryPressure}-scaled caches),
 * HYDRO-04 (#254 steep-cliff shadow water rejection vs backed rivers/waterfalls),
 * HYDRO-06 ({@code drownedBy} cross-body elevation-band isolation), and
 * HYDRO-10 (negative-coordinate {@code shore()} floor symmetry).
 */
class EarthHydrologyAuditTest {

    @Test
    void singleFlightRunsOnceUnderConcurrencyAndPropagatesMissingTerrainCleanly() throws Exception {
        ConcurrentHashMap<Long, CompletableFuture<String>> flights = new ConcurrentHashMap<>();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        int threads = 16;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> EarthGenerator.singleFlight(flights, 77L, () -> {
                    runs.incrementAndGet();
                    entered.countDown();
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    return "ok-77";
                })));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Give sibling workers a moment to attach to the in-flight future.
            Thread.sleep(30);
            release.countDown();
            for (Future<String> f : futures) {
                assertEquals("ok-77", f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, runs.get(), "concurrent callers for same key must deduplicate to a single computation");
            assertTrue(flights.isEmpty(), "in-flight map must be cleaned up after completion");

            // Failure case: MissingTerrainException must propagate unwrapped to every waiter and leave no stale future.
            AtomicInteger failRuns = new AtomicInteger();
            CountDownLatch failEntered = new CountDownLatch(1);
            CountDownLatch failRelease = new CountDownLatch(1);
            List<Future<Throwable>> failResults = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                failResults.add(pool.submit(() -> {
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
            assertTrue(failEntered.await(5, TimeUnit.SECONDS));
            Thread.sleep(30);
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
    void hydroCacheStaysBoundedAndRespondsToMemoryPressureAndShed() throws Exception {
        MemoryPressureTestBridge.reset();
        try {
            HydroCache<Integer> cache = new HydroCache<>(4096);
            for (long k = 0; k < 20_000; k++) {
                cache.put(k, (int) k);
            }
            assertTrue(cache.size() <= 4096 * 2,
                    "bounded cache must never approach legacy 1<<20 ceiling, got " + cache.size());

            // Verify EarthGenerator's own reaches cache and MemoryPressure.Hook wiring.
            EarthShorelineTest.bootRegistries();
            try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                    (dx, dz) -> 20.0, (dx, dz) -> WorldCoverSource.GRASSLAND)) {
                EarthGenerator gen = world.generator;
                assertEquals(4096, gen.reaches.capacity(), "reaches base capacity should be 4096, not 1<<20");
                for (long k = 0; k < 5_000; k++) gen.crests.put(k, Boolean.TRUE);
                for (long k = 0; k < 3_000; k++) gen.waterCells.put(k, new EarthGenerator.WaterCell(0, 0, 0, new long[4]));

                // Simulate memory pressure high (85% of 4 GiB) -> capacity drops to 25%.
                MemoryPressureTestBridge.triggerHigh();
                MemoryPressureTestBridge.awaitShrinks();
                assertTrue(MemoryPressure.high(), "MemoryPressure should enter high state");
                assertEquals(1024, cache.capacity(), "cache capacity should scale to 25% under MemoryPressure");
                assertEquals(1024, gen.reaches.capacity(), "EarthGenerator.reaches capacity should scale to 25%");

                cache.shed();
                assertTrue(cache.size() <= 1024, "shed() must immediately trim cache to scaled budget, got " + cache.size());
                gen.shedCaches();
                assertTrue(gen.reaches.size() <= 1024);
                assertTrue(gen.waterCells.size() <= gen.waterCells.capacity());
            }
        } finally {
            MemoryPressureTestBridge.reset();
        }
    }

    @Test
    void steepCliffShadowFilterRejectsUnbackedRockWallShadowAndPreservesBackedWater() {
        // Isolated steep-cliff shadow on a sheer mountain wall (#254 Trollveggen): REJECTED.
        assertTrue(EarthGenerator.isSteepCliffShadow(1.4, 500.0, false, false, false),
                "unbacked sheer mountain cliff shadow must be rejected");

        // Real waterfall / alpine cascade backed by Drainage: PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(1.4, 500.0, false, true, false),
                "drainage-backed waterfall on steep cliff must be preserved");

        // Valley river bank / alpine lake shore / waterfall lip adjacent to gentler water reach: PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(1.2, 500.0, false, false, true),
                "steep water adjacent to gentler water reach must be preserved");

        // Retreating glacier front / proglacial lake: PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(1.4, 500.0, true, false, false),
                "glacial water must never be rejected as cliff shadow");

        // Gentle slope or non-mountain terrain: PRESERVED.
        assertFalse(EarthGenerator.isSteepCliffShadow(0.5, 500.0, false, false, false));
        assertFalse(EarthGenerator.isSteepCliffShadow(1.4, 60.0, false, false, false));
    }

    @Test
    void sheerCliffShadowPatchRejectedEndToEndWhileValleyRiverStaysWet() throws Exception {
        EarthShorelineTest.bootRegistries();
        // Sheer 600 m north-facing mountain wall: dz < 0 rises steeply at 1.4 m/block (steep = 1.4 > 0.7),
        // flat valley floor at dz >= 0 (y = 20 m). Place fake WorldCover shadow water high on the sheer
        // wall (dz in [-260, -140]) AND a real flat valley river at dz in [40, 120].
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> dz < 0 ? 20.0 + (-dz) * 1.4 : 20.0,
                (dx, dz) -> {
                    if (dz >= -260 && dz <= -140 && Math.abs(dx) <= 60) return WorldCoverSource.WATER;
                    if (dz >= 40 && dz <= 120) return WorldCoverSource.WATER;
                    return WorldCoverSource.BARE;
                })) {
            int[] cliffOut = new int[2];
            world.ground(0, -200, cliffOut);
            assertEquals(SurfaceGenerator.DRY, cliffOut[1],
                    "unbacked steep-cliff shadow water on sheer 1.4 slope mountain wall must stay dry");

            int[] valleyOut = new int[2];
            world.ground(0, 80, valleyOut);
            assertNotEquals(SurfaceGenerator.DRY, valleyOut[1],
                    "flat valley river below the mountain wall must stay wet");
            assertTrue(valleyOut[1] > valleyOut[0], "valley river water surface must sit above its bed");
        }
    }

    @Test
    void drownedByIsolatesHigherUplandPondFromLowerDrownedReservoir() {
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
    void negativeCoordinateShoreClampsBothRimsUpToLakeSurface() throws Exception {
        EarthShorelineTest.bootRegistries();
        // SyntheticWorld's default origin (36.2N, 75.8W) sits in negative Mercator (homeX < 0, homeZ < 0).
        // Large +50 m plateau + lake (|dx| <= 60, |dz| <= 60) with small 2.5 m shore hollows straddling the
        // east and west rims (48 <= |dx| <= 80, |dz| <= 14). Even with WorldCover shoreline warp, the first
        // dry shore block on both rims sits inside the hollow and must be lifted by shore() to >= lake[1].
        try (EarthShorelineTest.SyntheticWorld world = new EarthShorelineTest.SyntheticWorld(
                (dx, dz) -> (Math.abs(dx) >= 48 && Math.abs(dx) <= 80 && Math.abs(dz) <= 14) ? 47.5 : 50.0,
                (dx, dz) -> (Math.abs(dx) <= 60 && Math.abs(dz) <= 60) ? WorldCoverSource.WATER : WorldCoverSource.GRASSLAND)) {
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
