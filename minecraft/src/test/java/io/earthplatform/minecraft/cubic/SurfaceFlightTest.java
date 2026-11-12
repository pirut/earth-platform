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

    /** Review: a flyer entering new ground queued every column survey before the first cube around it. */
    @Test void aFlyerQueuesNoSurveyAheadOfItsCubesAndFewAfter() {
        assertEquals(0, SurfaceFlight.surveyRoom(true, true, 0), "none while its cubes are asked for");
        assertEquals(SurfaceFlight.SURVEYS, SurfaceFlight.surveyRoom(true, false, 0));
        assertEquals(SurfaceFlight.SURVEYS - 5, SurfaceFlight.surveyRoom(true, false, 5));
        assertEquals(0, SurfaceFlight.surveyRoom(true, false, 1000), "never below none");
        assertTrue(SurfaceFlight.SURVEYS <= 16, "far fewer than the 489 columns of the widest band");
        for (boolean asking : new boolean[] {true, false})
            assertEquals(Integer.MAX_VALUE, SurfaceFlight.surveyRoom(false, asking, 1000), "on foot, as before");
    }

    /** Review: the ground under a flyer came from waterY, which could sample Earth data on the server thread. */
    @Test void groundUnderAFlyerComesFromItsColumnsBoundsAndHoldsWhileANewColumnIsSurveyed() {
        assertEquals(90, SurfaceFlight.ground(SurfaceFlight.bounds(63, 90), Integer.MIN_VALUE), "the column's highest");
        assertEquals(90, SurfaceFlight.ground(SurfaceFlight.UNKNOWN, 90), "the last column's while this one is surveyed");
        assertEquals(Integer.MIN_VALUE, SurfaceFlight.ground(SurfaceFlight.UNKNOWN, Integer.MIN_VALUE));
        long failed = SurfaceFlight.bounds(Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertNotEquals(SurfaceFlight.UNKNOWN, failed);
        assertFalse(SurfaceFlight.flying(1000, SurfaceFlight.ground(failed, 63), false), "ground that can't be had is not flown over");
        for (int[] b : new int[][] {{-140, -129}, {63, 63}, {CubeWorld.MIN_Y, CubeWorld.MAX_Y}}) {
            long packed = SurfaceFlight.bounds(b[0], b[1]);
            assertEquals(b[0], SurfaceFlight.low(packed));
            assertEquals(b[1], SurfaceFlight.high(packed));
        }
    }

    /**
     * Review: the 384-block reach was stated but the scan stopped at the band (12 cubes at most). It is the band
     * across, as the client draws far terrain past it, and 384 blocks in a straight line.
     */
    @Test void surfaceColumnsAreThoseOfTheBandAcrossWithinTheReachInAStraightLine() {
        int h = Cubic.MAX_HORIZONTAL_RADIUS;
        LongOpenHashSet asked = new LongOpenHashSet();
        LongArrayList cubes = new LongArrayList();
        SurfaceFlight.surface(0, 0, h, (x, z) -> { asked.add(CubeKey.column(x, z)); return SurfaceFlight.bounds(63, 63); }, 8, 263, 8, cubes::add);
        int columns = 0;
        for (int z = -30; z <= 30; z++) for (int x = -30; x <= 30; x++) {
            boolean within = CubeOffsets.within(x, 0, z, h, 0);
            if (within) columns++;
            assertEquals(within, asked.contains(CubeKey.column(x, z)), "column " + x + "," + z);
        }
        assertEquals(columns * 8, cubes.size(), "every band column, 200 blocks up, is in reach");
        assertTrue(cubes.contains(CubeKey.pack(12, 3, 0)));
        assertFalse(asked.contains(CubeKey.column(19, 0)), "past the band, far terrain draws the ground");
        assertTrue(h * 16 < SurfaceFlight.REACH);

        cubes.clear();
        SurfaceFlight.surface(0, 0, h, (x, z) -> SurfaceFlight.bounds(63, 63), 8, 64 + 360, 8, cubes::add);
        assertTrue(cubes.contains(CubeKey.pack(0, 3, 0)), "straight under, 360 blocks down");
        assertFalse(cubes.contains(CubeKey.pack(12, 3, 0)), "at the band's edge that far up, past the reach");

        cubes.clear();
        SurfaceFlight.surface(0, 0, h, (x, z) -> x == 1 && z == 1 ? SurfaceFlight.bounds(63, 63) : SurfaceFlight.UNKNOWN, 8, 263, 8, cubes::add);
        assertEquals(column(1, 1, 63, 63, 8, 263, 8), cubes, "columns not surveyed yet wait");
    }

    /** Review: a column's first tops remeshed every row from its lowest top to its highest, even 1,000 rows. */
    @Test void topRowsAreTheShadedRowsBoundedAndNoneOutsideTheWorld() {
        int[] tops = new int[256];
        java.util.Arrays.fill(tops, 63);
        long rows = SurfaceFlight.topRows(tops);
        assertEquals(2, SurfaceFlight.low(rows), "one under the ground's row");
        assertEquals(3, SurfaceFlight.high(rows), "none over the highest top: that is open sky");

        tops[17] = 16_000;
        rows = SurfaceFlight.topRows(tops);
        assertEquals(2, SurfaceFlight.low(rows));
        assertEquals(SurfaceFlight.TOP_ROWS, SurfaceFlight.high(rows) - SurfaceFlight.low(rows) + 1, "a build high over the ground: bounded");

        tops[17] = 63 + 16 * 5;
        rows = SurfaceFlight.topRows(tops);
        assertEquals(2, SurfaceFlight.low(rows));
        assertEquals(8, SurfaceFlight.high(rows), "rows between tops are shaded too");

        java.util.Arrays.fill(tops, CubeWorld.MIN_Y - 1);
        assertEquals(SurfaceFlight.UNKNOWN, SurfaceFlight.topRows(tops), "open sky everywhere");
        tops[0] = CubeWorld.MIN_Y;
        assertEquals(CubeWorld.MIN_Y >> 4, SurfaceFlight.low(SurfaceFlight.topRows(tops)), "never under the world");
    }
}
