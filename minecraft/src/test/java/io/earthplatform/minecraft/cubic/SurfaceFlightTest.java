package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

class SurfaceFlightTest {
    private static LongArrayList column(int x, int z, int low, int high, double eyeX, double eyeY, double eyeZ) {
        LongArrayList out = new LongArrayList();
        SurfaceFlight.column(x, z, low, high, eyeX, eyeY, eyeZ, out::add);
        return out;
    }

    @Test void flightStartsAboveTheSurfaceAndNeverReplacesCaveStreaming() {
        assertFalse(SurfaceFlight.active(63 + 47, 63, false));
        assertTrue(SurfaceFlight.active(63 + 48, 63, false));
        assertTrue(SurfaceFlight.active(8063 + 100, 8063, false));
        assertFalse(SurfaceFlight.active(8163, 8063, true));
        assertFalse(SurfaceFlight.active(163, Integer.MIN_VALUE, false));
    }

    @Test void keepsGroundLightingNeighbourAndTallTreesAfterPlayerBandHasLeftGround() {
        LongArrayList surface = column(0, 0, 63, 63, 8, 263, 8);
        assertEquals(8, surface.size());
        assertTrue(surface.contains(CubeKey.pack(0, 2, 0)), "one cube below ground for neighbours");
        assertTrue(surface.contains(CubeKey.pack(0, 3, 0)), "ground remains full detail at 200 blocks up");
        assertTrue(surface.contains(CubeKey.pack(0, (63 + 90) >> 4, 0)), "tall canopy remains loaded");
        for (long key : surface) assertTrue(CubeKey.y(key) < (263 >> 4) - 6, "surface is outside the old player band");
    }

    @Test void rangeUsesSurfaceRelativeSlantDistanceAndRejectsDistantHighPlateaus() {
        assertFalse(column(0, 0, 8063, 8063, 8, 8263, 8).isEmpty());
        assertTrue(column(0, 0, 63, 63, 8, 3063, 8).isEmpty());
        assertTrue(column(0, 0, 8063, 8063, 8, 1063, 8).isEmpty());
        assertFalse(column(0, 0, 63, 63, 8, 64 + SurfaceFlight.REACH, 8).isEmpty());
        assertTrue(column(0, 0, 63, 63, 8, 65 + SurfaceFlight.REACH, 8).isEmpty());
        assertTrue(column(24, 0, 63, 63, 0, 164, 8).isEmpty(), "horizontal and vertical distance both count");
    }

    @Test void unknownBoundsAndExtremeCliffsUseFarTerrain() {
        assertTrue(column(0, 0, Integer.MAX_VALUE, 100, 8, 200, 8).isEmpty());
        assertTrue(column(0, 0, Integer.MIN_VALUE, 100, 8, 200, 8).isEmpty());
        assertTrue(column(0, 0, 100, 99, 8, 200, 8).isEmpty());
        assertTrue(column(0, 0, 63, 1063, 8, 200, 8).isEmpty());
    }

    @Test void negativeCoordinatesAndWorldEdgesStayInRange() {
        LongArrayList negative = column(-2, -3, -140, -129, -24, -30, -40);
        assertFalse(negative.isEmpty());
        assertTrue(negative.contains(CubeKey.pack(-2, -9, -3)));
        for (int y : new int[] {CubeWorld.MIN_Y, CubeWorld.MAX_Y}) {
            LongArrayList edge = column(0, 0, y, y, 8, y + 100, 8);
            assertFalse(edge.isEmpty());
            for (long key : edge) assertTrue(CubeKey.y(key) >= CubeWorld.MIN_Y >> 4 && CubeKey.y(key) <= CubeWorld.MAX_Y >> 4);
        }
    }

    @Test void mergedPlanPreservesEveryCollisionCubeAndHasNoDuplicates() {
        CubeOffsets offsets = CubeOffsets.of(12, 6, ViewOrder.sector(0));
        LongArrayList surface = column(-2, -3, -140, -129, -24, 70, -40);
        surface.add(CubeKey.pack(-2, 4, -3)); // overlaps original band
        LongArrayList plan = new LongArrayList();
        SurfaceFlight.merge(-2, 4, -3, offsets, surface, plan);
        LongOpenHashSet unique = new LongOpenHashSet(plan);
        assertEquals(plan.size(), unique.size());
        for (int i = 0; i < offsets.size(); i++) {
            long key = CubeKey.pack(-2 + offsets.dx(i), 4 + offsets.dy(i), -3 + offsets.dz(i));
            assertTrue(unique.contains(key), "the original player band is retained");
            if (i < 64) assertEquals(key, plan.getLong(i), "immediate player cubes have priority");
        }
        for (long key : surface) assertTrue(unique.contains(key));
        assertTrue(plan.indexOf(surface.getLong(0)) < 64 + surface.size());
    }

    @Test void onFootKeepsTheOriginalOrder() {
        CubeOffsets offsets = CubeOffsets.of(4, 6, -1);
        LongArrayList plan = new LongArrayList();
        SurfaceFlight.merge(0, -5, 0, offsets, new LongArrayList(), plan);
        assertEquals(offsets.size(), plan.size());
        for (int i = 0; i < offsets.size(); i++)
            assertEquals(CubeKey.pack(offsets.dx(i), -5 + offsets.dy(i), offsets.dz(i)), plan.getLong(i));
    }
}
