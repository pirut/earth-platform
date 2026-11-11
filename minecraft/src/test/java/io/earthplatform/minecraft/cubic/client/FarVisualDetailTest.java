package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.far.Far;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class FarVisualDetailTest {
    @Test void cruiseSmoothingNeverReachesTheFourDetailedBlockLevels() {
        for (int configured = 1; configured <= Far.MAX_LEVEL; configured++) {
            assertTrue(FarAtmosphere.smoothFrom(configured, false) > Far.EDIT_LEVELS);
            assertTrue(FarAtmosphere.smoothFrom(configured, true) > Far.EDIT_LEVELS);
        }
        assertEquals(8, FarAtmosphere.smoothFrom(8, false));
        assertEquals(5, FarAtmosphere.smoothFrom(8, true));
    }
    @Test void extremeZoomKeepsItsNarrowConeButBoundsExtraTerrainWork() {
        var view = FarZoom.at(32, 1, 0, 8000, 0, 8000, 0, 0, null);
        assertNotNull(view);
        assertEquals(8, view.zoom);
        assertEquals(view.limited(2), view.limited(4).limited(2));
        assertTrue(FarZoom.MAX_COLUMNS <= 2048);
    }
    @Test void cruiseSmoothHasHysteresisAndZoomDoesNotTriggerRemeshes() {
        assertFalse(FarAtmosphere.cruise(2000, false, false));
        assertTrue(FarAtmosphere.cruise(2300, false, false));
        assertTrue(FarAtmosphere.cruise(2000, true, false));
        assertFalse(FarAtmosphere.cruise(1500, true, false));
        assertTrue(FarAtmosphere.cruise(10000, true, true));
    }
    @Test void zoomRequestsStartEarlyWithoutStarvingOrdinaryCoverageOrDuplicatingKeys() {
        LongArrayList base = new LongArrayList(), zoom = new LongArrayList(), merged = new LongArrayList();
        for (int i = 0; i < 2000; i++) base.add(i);
        for (int i = 1500; i < 3000; i++) zoom.add(i);
        FarRequestOrder.merge(base, zoom, merged);
        assertEquals(3000, merged.size());
        assertEquals(3000, new LongOpenHashSet(merged).size());
        for (int i = 0; i < 32; i++) assertEquals(i, merged.getLong(i));
        assertEquals(1500, merged.getLong(32));
        assertEquals(32, merged.getLong(33));
        assertEquals(33, merged.getLong(34));
        assertEquals(1501, merged.getLong(35));
    }

    @Test void emptyZoomLeavesNormalOrderAloneAndEmptyWorldStillLoadsTheFocus() {
        LongArrayList values = LongArrayList.of(4, 7, 9), merged = new LongArrayList();
        FarRequestOrder.merge(values, new LongArrayList(), merged); assertEquals(values, merged);
        FarRequestOrder.merge(new LongArrayList(), values, merged); assertEquals(values, merged);
    }

    @Test void highCameraRefinesNearbyPeaksInsteadOfUsingTheValleyBelowAsTheirDistance() {
        FarZoom zoom = FarZoom.at(2, 1, 0, 5000, 0, 5000, 0, 0, null);
        assertNotNull(zoom);
        FarZoom.Box peak = box(4900, 5100), valley = box(0, 50);
        assertTrue(zoom.refined(2, 0, 5, peak));
        assertFalse(zoom.refined(2, 0, 5, valley));
        assertFalse(zoom.refined(1, 0, 5, peak), "cannot refine below the finest far level");
    }

    @Test void limitingZoomRepeatedlyKeepsTheOriginalViewCone() {
        FarZoom view = FarZoom.at(16, 1.2, 100, 800, 100, 700, 15, 30, null);
        assertNotNull(view);
        assertEquals(view.limited(2), view.limited(4).limited(2));
    }

    @Test void atmosphereRampsSmoothlyAboveTerrainAndCanBeDisabled() {
        String old = System.getProperty("earthplatform.far.atmosphere");
        try {
            System.setProperty("earthplatform.far.atmosphere", "true");
            assertEquals(0, FarAtmosphere.blend(-100)); assertEquals(0, FarAtmosphere.blend(2000));
            assertEquals(0.5f, FarAtmosphere.blend(4000)); assertEquals(1, FarAtmosphere.blend(6000));
            assertEquals(0, FarAtmosphere.blend(Double.NaN));
            float before = 0;
            for (int y = 0; y <= 10000; y += 10) { float blend = FarAtmosphere.blend(y); assertTrue(blend >= before); before = blend; }
            System.setProperty("earthplatform.far.atmosphere", "false"); assertEquals(0, FarAtmosphere.blend(10000));
        } finally { if (old == null) System.clearProperty("earthplatform.far.atmosphere"); else System.setProperty("earthplatform.far.atmosphere", old); }
    }

    @Test void preciseProjectionKeepsFovAndSeparatesFacesThatCollapseInGameDepth() {
        Matrix4f game = new Matrix4f().perspective((float) Math.toRadians(7), 16f / 9, 0.05f, FarRenderer.DEPTH_FAR);
        Matrix4f precise = FarPlainDepth.projection(game);
        assertEquals(game.m00(), precise.m00()); assertEquals(game.m11(), precise.m11());
        assertEquals(depth(game, 10000), depth(game, 10002), "game depth cannot reliably separate two-block far voxels");
        assertTrue(depth(precise, 10000) < depth(precise, 10002));
    }

    private static float depth(Matrix4f projection, float distance) {
        Vector4f clip = projection.transform(new Vector4f(0, 0, -distance, 1));
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static FarZoom.Box box(double low, double high) {
        return new FarZoom.Box() {
            public double lowY() { return low; } public double highY() { return high; } public double voxel() { return Far.voxel(2); }
        };
    }
}
