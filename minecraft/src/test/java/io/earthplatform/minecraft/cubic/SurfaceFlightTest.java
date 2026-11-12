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

    private static LongArrayList keys(SurfaceFlight.Plan plan) {
        LongArrayList keys = new LongArrayList();
        for (int i = 0; i < plan.size(); i++) keys.add(plan.key(i));
        return keys;
    }

    @Test void offByDefault() {
        assertFalse(SurfaceFlight.ENABLED);
        assertFalse(SurfaceFlight.active(63 + 1000, 63, false));
    }

    @Test void flightStartsAboveTheSurfaceAndNeverReplacesCaveStreaming() {
        assertFalse(SurfaceFlight.flying(63 + 47, 63, false));
        assertTrue(SurfaceFlight.flying(63 + 48, 63, false));
        assertTrue(SurfaceFlight.flying(8063 + 100, 8063, false));
        assertFalse(SurfaceFlight.flying(8163, 8063, true));
        assertFalse(SurfaceFlight.flying(163, Integer.MIN_VALUE, false));
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

    /** Off, or on foot, cubes are asked for and sent in the band's own order, as before surface flight. */
    @Test void withoutSurfaceThePlanIsTheBandOrder() {
        for (int sector : new int[] {-1, 0, 7}) {
            CubeOffsets offsets = CubeOffsets.of(12, 6, sector);
            SurfaceFlight.Plan plan = new SurfaceFlight.Plan();
            plan.around(offsets, 3, -5, -7);
            assertEquals(offsets.size(), plan.size());
            for (int i = 0; i < offsets.size(); i++)
                assertEquals(CubeKey.pack(3 + offsets.dx(i), -5 + offsets.dy(i), -7 + offsets.dz(i)), plan.key(i));
        }
    }

    @Test void landingDropsTheSurfaceAndMovingKeepsIt() {
        CubeOffsets offsets = CubeOffsets.of(4, 2, 0);
        SurfaceFlight.Plan plan = new SurfaceFlight.Plan();
        plan.around(offsets, 0, 10, 0);
        LongOpenHashSet surface = new LongOpenHashSet(column(1, 1, 63, 63, 8, 170, 8));
        plan.surface(surface, 8, 170, 8, 0);
        assertEquals(offsets.size() + surface.size(), plan.size());
        plan.around(offsets, 1, 10, 0);
        assertEquals(offsets.size() + surface.size(), plan.size(), "the surface stays until replanned");
        assertEquals(CubeKey.pack(1 + offsets.dx(0), 10 + offsets.dy(0), offsets.dz(0)), plan.key(0), "the band follows the player");
        plan.clear();
        assertEquals(offsets.size(), plan.size());
    }

    @Test void mergedPlanPutsTheNearBandFirstThenSurfaceNearestFirst() {
        CubeOffsets offsets = CubeOffsets.of(12, 6, ViewOrder.sector(0));
        LongOpenHashSet surface = new LongOpenHashSet();
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) SurfaceFlight.column(x, z, -140, -129, -24, 70, -40, surface::add);
        surface.add(CubeKey.pack(-2, 4, -3)); // inside the band too
        SurfaceFlight.Plan plan = new SurfaceFlight.Plan();
        plan.around(offsets, -2, 4, -3);
        plan.surface(surface, -24, 70, -40, 0);
        LongArrayList keys = keys(plan);
        LongOpenHashSet unique = new LongOpenHashSet(keys);
        for (int i = 0; i < offsets.size(); i++) {
            long key = CubeKey.pack(-2 + offsets.dx(i), 4 + offsets.dy(i), -3 + offsets.dz(i));
            assertTrue(unique.contains(key), "the whole band is kept");
            if (i < SurfaceFlight.FIRST) assertEquals(key, keys.getLong(i), "cubes right around the player come first");
        }
        for (long key : surface) assertTrue(unique.contains(key));
        double lookX = ViewOrder.lookX(0), lookZ = ViewOrder.lookZ(0), last = -1;
        for (int i = SurfaceFlight.FIRST; i < SurfaceFlight.FIRST + surface.size(); i++) {
            long key = keys.getLong(i);
            assertTrue(surface.contains(key));
            double dx = (CubeKey.x(key) << 4) + 8 + 24, dy = (CubeKey.y(key) << 4) + 8 - 70, dz = (CubeKey.z(key) << 4) + 8 + 40;
            double later = ViewOrder.ON ? ViewOrder.later(dx / 16, dz / 16, lookX, lookZ, 1.5) : 1;
            double far = (float) ((dx * dx + dy * dy + dz * dz) * later * later);
            assertTrue(far >= last, "surface nearest first");
            last = far;
        }
    }
}
