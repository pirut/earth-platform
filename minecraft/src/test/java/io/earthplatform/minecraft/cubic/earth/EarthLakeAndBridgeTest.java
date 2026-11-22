package io.earthplatform.minecraft.cubic.earth;

import static io.earthplatform.minecraft.cubic.earth.EarthGenerator.BRIDGE_SPAN;
import static io.earthplatform.minecraft.cubic.earth.EarthGenerator.MAX_LAKE_DEPTH;
import static io.earthplatform.minecraft.cubic.earth.EarthGenerator.SEA;
import static io.earthplatform.minecraft.cubic.earth.EarthGenerator.WATER_CELL;
import static io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.DRY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator.CellProbe;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator.CoverProbe;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator.LevelProbe;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator.WaterCell;
import org.junit.jupiter.api.Test;

/** Relief-capped inland lake depths (#217) and near/far bridge clearing (#218). */
class EarthLakeAndBridgeTest {
    private static final long[] NO_SEA = new long[4];

    @Test void flatInlandPlainsLakeStaysShallow() {
        // Inland plains lake at 55 m ASL (like Aasee in Münster): 10 m local relief, mostly land cell.
        CellProbe plain = (cx, cz) -> new WaterCell(55.0, 40, 10.0, NO_SEA);
        double cap = EarthGenerator.inlandDepthCap(512, 512, plain);
        assertEquals(3.5, cap, 1e-6);
        // Far from shore (126 blocks, matching max voxel=1 ring distance), depth stays ~3-4 blocks, not 15-22.
        for (double noise : new double[] {-1.0, -0.5, 0.0, 0.5, 1.0}) {
            int depth = EarthGenerator.cappedLakeDepth(126.0, noise, cap);
            assertTrue(depth >= 2 && depth <= 5, "plains lake depth should stay shallow, got " + depth);
        }
        // Very flat marsh/plain (<= 4 m relief) stays at 2 blocks.
        assertEquals(2.0, EarthGenerator.reliefCap(0.0), 1e-6);
        assertEquals(2.0, EarthGenerator.reliefCap(4.0), 1e-6);
        assertEquals(2, EarthGenerator.cappedLakeDepth(126.0, 0.0, EarthGenerator.reliefCap(3.0)));
    }

    @Test void highReliefAlpineLakeStaysDeep() {
        // Steep mountain/glacial/caldera lake: 200 m local relief keeps the full 40-block depth cap.
        CellProbe alpine = (cx, cz) -> new WaterCell(1897.0, 120, 200.0, NO_SEA);
        double cap = EarthGenerator.inlandDepthCap(512, 512, alpine);
        assertEquals(MAX_LAKE_DEPTH, cap, 1e-6);
        int nearMid = EarthGenerator.cappedLakeDepth(126.0, 0.0, cap);
        int farDeep = EarthGenerator.cappedLakeDepth(336.0, 0.0, cap);
        assertTrue(nearMid >= 20, "alpine lake at voxel=1 should stay deep, got " + nearMid);
        assertEquals(MAX_LAKE_DEPTH, farDeep);
    }

    @Test void moderateHillsHaveIntermediateDepth() {
        double plainCap = EarthGenerator.reliefCap(10.0);
        double hillCap = EarthGenerator.reliefCap(36.0);
        double mountainCap = EarthGenerator.reliefCap(160.0);
        assertEquals(10.0, hillCap, 1e-6);
        assertTrue(plainCap < hillCap && hillCap < mountainCap);
        int hillDepth = EarthGenerator.cappedLakeDepth(126.0, 0.0, hillCap);
        assertEquals(10, hillDepth);
    }

    @Test void openWaterLargeLakeStaysDeepWhileMediumPlainsLakeStaysShallow() {
        // Center of a huge lake where the cell and all 8 neighbours are open water: keeps full depth.
        CellProbe inlandSea = (cx, cz) -> new WaterCell(183.0, 256, 0.0, NO_SEA);
        assertEquals(MAX_LAKE_DEPTH, EarthGenerator.cellDepthCap(0, 0, inlandSea), 1e-6);

        // Medium (~1.5 km) flat-plains lake: center cell is all water, but neighbouring cells measure flat land.
        CellProbe mediumPlainLake = (cx, cz) -> (cx == 0 && cz == 0)
                ? new WaterCell(55.0, 256, 0.0, NO_SEA)
                : new WaterCell(55.0, 60, 8.0, NO_SEA);
        assertEquals(3.0, EarthGenerator.cellDepthCap(0, 0, mediumPlainLake), 1e-6);
    }

    @Test void depthCapBlendsSmoothlyAcrossCellBoundaries() {
        CellProbe transition = (cx, cz) -> cx <= 0
                ? new WaterCell(55.0, 40, 8.0, NO_SEA)
                : new WaterCell(55.0, 40, 84.0, NO_SEA);
        double prev = EarthGenerator.inlandDepthCap(0, 512, transition);
        for (int x = 32; x <= 2 * WATER_CELL; x += 32) {
            double cur = EarthGenerator.inlandDepthCap(x, 512, transition);
            assertTrue(cur >= prev - 1e-9, "cap should rise monotonically into higher relief");
            assertTrue(cur - prev <= 1.0, "cap should blend smoothly without steps, jumped by " + (cur - prev));
            prev = cur;
        }
        assertTrue(prev > 20.0);
    }

    @Test void nearTerrainOrthogonalBridgeIsCleared() {
        // North-South river along x in [-100, 100), crossed by a 24-block East-West built-up bridge at z in [0, 24).
        int riverLevel = SEA + 2;
        CoverProbe cover = (x, z) -> (x >= -100 && x < 100)
                ? (z >= 0 && z < 24 ? WorldCoverSource.BUILT_UP : WorldCoverSource.WATER)
                : WorldCoverSource.BUILT_UP;
        LevelProbe water = (x, z) -> cover.cover(x, z) == WorldCoverSource.WATER ? riverLevel : DRY;

        // Every column across the bridge over the river clears to riverLevel at voxel == 1.
        for (int z = 0; z < 24; z++) {
            assertEquals(riverLevel, EarthGenerator.bridged(0, z, 1, cover, water), "bridge column z=" + z);
        }
        // The built-up riverbank outside the river stays dry land.
        assertEquals(DRY, EarthGenerator.bridged(-120, 12, 1, cover, water));
        assertEquals(DRY, EarthGenerator.bridged(120, 12, 1, cover, water));
    }

    @Test void wideAndObliqueBridgesAreClearedAcrossNearAndFarVoxels() {
        int riverLevel = SEA + 4;

        // Wide 48-block freeway bridge at voxel == 1.
        CoverProbe wide = (x, z) -> z >= 0 && z < 48 ? WorldCoverSource.BUILT_UP : WorldCoverSource.WATER;
        LevelProbe wideWater = (x, z) -> wide.cover(x, z) == WorldCoverSource.WATER ? riverLevel : DRY;
        for (int z = 0; z < 48; z++) {
            assertEquals(riverLevel, EarthGenerator.bridged(0, z, 1, wide, wideWater), "wide bridge z=" + z);
        }

        // 45-degree diagonal bridge (30 blocks wide perpendicular: |x - z| / sqrt(2) < 15).
        CoverProbe diag = (x, z) -> Math.abs(x - z) < 21 ? WorldCoverSource.BUILT_UP : WorldCoverSource.WATER;
        LevelProbe diagWater = (x, z) -> diag.cover(x, z) == WorldCoverSource.WATER ? riverLevel : DRY;
        for (int offset = -18; offset <= 18; offset += 3) {
            assertEquals(riverLevel, EarthGenerator.bridged(offset, 0, 1, diag, diagWater), "diagonal bridge offset=" + offset);
        }

        // 22.5-degree oblique bridge (32 blocks wide perpendicular).
        double cos = Math.cos(Math.toRadians(22.5)), sin = Math.sin(Math.toRadians(22.5));
        CoverProbe oblique = (x, z) -> Math.abs(x * sin + z * cos) < 16.0 ? WorldCoverSource.BUILT_UP : WorldCoverSource.WATER;
        LevelProbe obliqueWater = (x, z) -> oblique.cover(x, z) == WorldCoverSource.WATER ? riverLevel : DRY;
        for (int z = -15; z <= 15; z++) {
            assertEquals(riverLevel, EarthGenerator.bridged(0, z, 1, oblique, obliqueWater), "oblique bridge z=" + z);
        }

        // Far voxels (8, 16, 32) continue to clear bridges.
        CoverProbe farBridge = (x, z) -> z >= 0 && z < 24 ? WorldCoverSource.BUILT_UP : WorldCoverSource.WATER;
        LevelProbe farWater = (x, z) -> farBridge.cover(x, z) == WorldCoverSource.WATER ? riverLevel : DRY;
        for (int voxel : new int[] {8, 16, 32}) {
            assertEquals(riverLevel, EarthGenerator.bridged(0, 8, voxel, farBridge, farWater), "far voxel=" + voxel);
        }
    }

    @Test void bridgeWithBareMedianIsClearedButNaturalSandbarStaysDry() {
        int riverLevel = SEA + 5;
        // 32-block bridge with a 8-block bare median down the middle.
        CoverProbe bridgeWithMedian = (x, z) -> {
            if (z < 0 || z >= 32) return WorldCoverSource.WATER;
            return (z >= 12 && z < 20) ? WorldCoverSource.BARE : WorldCoverSource.BUILT_UP;
        };
        LevelProbe bridgeWater = (x, z) -> bridgeWithMedian.cover(x, z) == WorldCoverSource.WATER ? riverLevel : DRY;
        for (int z = 0; z < 32; z++) {
            assertEquals(riverLevel, EarthGenerator.bridged(0, z, 1, bridgeWithMedian, bridgeWater), "median bridge z=" + z);
        }

        // Natural 20-block bare sandbar / gravel bar with no built-up cover stays dry.
        CoverProbe sandbar = (x, z) -> (z >= 0 && z < 20) ? WorldCoverSource.BARE : WorldCoverSource.WATER;
        LevelProbe sandbarWater = (x, z) -> sandbar.cover(x, z) == WorldCoverSource.WATER ? riverLevel : DRY;
        for (int z = 0; z < 20; z++) {
            assertEquals(DRY, EarthGenerator.bridged(0, z, 1, sandbar, sandbarWater), "natural sandbar z=" + z);
        }
    }

    @Test void damBetweenDifferentWaterLevelsStands() {
        CoverProbe dam = (x, z) -> (z >= 0 && z < 24) ? WorldCoverSource.BUILT_UP : WorldCoverSource.WATER;
        LevelProbe reservoirAndRiver = (x, z) -> z < 0 ? SEA + 120 : z >= 24 ? SEA + 40 : DRY;
        for (int z = 0; z < 24; z++) {
            assertEquals(DRY, EarthGenerator.bridged(0, z, 1, dam, reservoirAndRiver), "high dam z=" + z);
        }

        // Even a 3-block weir stays solid.
        LevelProbe weir = (x, z) -> z < 0 ? SEA + 10 : z >= 24 ? SEA + 7 : DRY;
        for (int z = 0; z < 24; z++) {
            assertEquals(DRY, EarthGenerator.bridged(0, z, 1, dam, weir), "low weir z=" + z);
        }

        // A 1-block gradient across a bridge still clears to the lower side.
        LevelProbe gentleSlope = (x, z) -> z < 0 ? SEA + 10 : z >= 24 ? SEA + 9 : DRY;
        assertEquals(SEA + 9, EarthGenerator.bridged(0, 12, 1, dam, gentleSlope));
    }

    @Test void wideUrbanIslandStaysDryFromEdgeToEdge() {
        int width = BRIDGE_SPAN + 16; // 80 blocks wide: wider than any bridge span.
        CoverProbe island = (x, z) -> (z >= 0 && z < width) ? WorldCoverSource.BUILT_UP : WorldCoverSource.WATER;
        LevelProbe water = (x, z) -> island.cover(x, z) == WorldCoverSource.WATER ? SEA : DRY;
        for (int z = 0; z < width; z++) {
            assertEquals(DRY, EarthGenerator.bridged(0, z, 1, island, water), "wide urban island z=" + z);
        }
    }

    @Test void belowSeaLevelBridgePreservesNegativeWaterLevel() {
        int deadSeaLevel = -300;
        CoverProbe bridge = (x, z) -> (z >= 0 && z < 20) ? WorldCoverSource.BUILT_UP : WorldCoverSource.WATER;
        LevelProbe water = (x, z) -> bridge.cover(x, z) == WorldCoverSource.WATER ? deadSeaLevel : DRY;
        assertEquals(deadSeaLevel, EarthGenerator.bridged(0, 10, 1, bridge, water));
    }
}
