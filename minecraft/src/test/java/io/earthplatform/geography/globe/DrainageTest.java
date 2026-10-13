package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DrainageTest {
    private static final int SIDE = 64;

    /** A valley down the middle column, falling to the south, between slopes rising five metres a pixel. */
    private static float[] valley() {
        float[] height = new float[SIDE * SIDE];
        for (int y = 0; y < SIDE; y++) for (int x = 0; x < SIDE; x++)
            height[y * SIDE + x] = 100 - y * 0.5f + Math.abs(x - SIDE / 2) * 5;
        return height;
    }

    @Test
    void waterGathersInTheValley() {
        float[] height = valley();
        int[] down = new int[height.length];
        int[] order = Drainage.flood(height, down);
        assertEquals(height.length, order.length);
        float[] area = new float[height.length];
        java.util.Arrays.fill(area, 1);
        for (int k = order.length - 1; k >= 0; k--) if (down[order[k]] >= 0) area[down[order[k]]] += area[order[k]];
        int floor = 40 * SIDE + SIDE / 2;
        for (int x = 1; x < SIDE - 1; x++) if (x != SIDE / 2) assertTrue(area[floor] > area[40 * SIDE + x], "the valley floor drains most at x " + x);
        assertEquals(floor + SIDE, down[floor], "the floor drains on downhill");
    }

    @Test
    void keepsValleysAndDropsFlats() {
        float[] height = valley();
        int[] down = new int[height.length];
        int[] order = Drainage.flood(height, down);
        float[] area = new float[height.length];
        java.util.Arrays.fill(area, 1);
        for (int k = order.length - 1; k >= 0; k--) if (down[order[k]] >= 0) area[down[order[k]]] += area[order[k]];
        boolean[] kept = Drainage.keep(height, down, order, area);
        assertTrue(kept[40 * SIDE + SIDE / 2], "a stream in the valley is kept");

        float[] flat = new float[SIDE * SIDE];
        java.util.Arrays.fill(flat, 3);
        int[] flatDown = new int[flat.length];
        int[] flatOrder = Drainage.flood(flat, flatDown);
        float[] flatArea = new float[flat.length];
        java.util.Arrays.fill(flatArea, 1);
        for (int k = flatOrder.length - 1; k >= 0; k--) if (flatDown[flatOrder[k]] >= 0) flatArea[flatDown[flatOrder[k]]] += flatArea[flatOrder[k]];
        boolean[] flatKept = Drainage.keep(flat, flatDown, flatOrder, flatArea);
        for (boolean k : flatKept) assertFalse(k, "a flat has no streams");
    }

    @Test
    void prunesSpursButKeepsLineEnds() {
        int span = 32;
        boolean[] on = new boolean[span * span];
        for (int x = 4; x < 28; x++) on[16 * span + x] = true;
        for (int y = 13; y < 16; y++) on[y * span + 15] = true;
        RiverMap.prune(on, span, RiverMap.SPUR);
        // The pixel touching the line stays: it is part of the line's corner, not the spur.
        for (int y = 13; y < 15; y++) assertFalse(on[y * span + 15], "the spur is gone at y " + y);
        assertTrue(on[16 * span + 4] && on[16 * span + 27], "the line keeps its ends");
    }

    @Test
    void roundTripsPixels() {
        for (double latitude : new double[]{52.2049, -3.3, 60}) {
            double y = Drainage.pixelY(latitude, Drainage.ZOOM);
            assertEquals(latitude, Drainage.latitude(y, Drainage.ZOOM), 1e-9);
        }
        assertEquals(0.1141, Drainage.longitude(Drainage.pixelX(0.1141, 13), 13), 1e-9);
    }
}
