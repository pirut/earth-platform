package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A zoomed view that changes how much it zooms plans at once; one that only pans, or whose zoom only
 * wavers, waits for the gate. Each case runs the ticks as {@link FarRenderer#tick} does: the view from
 * {@link FarZoom#at}, handed to {@link FarRenderer.PlanGate}, then asked whether the tick plans.
 */
class FarZoomReplanTest {
    /** A tick that plans on its own (a turn of the plan's ticks), so the ticks after it start clean. */
    private static final int FIRST = 100;

    @Test void zoomingInFromUnzoomedPlansAtOnce() {
        assertEquals(List.of(FIRST + 1), plans(zooms(1, 2)));
    }

    @Test void changingMagnificationPlansAtOnce() {
        assertEquals(List.of(FIRST + 1, FIRST + 2), plans(zooms(2, 32, 2)), "2x to 32x and back must not wait for the pan gate");
    }

    @Test void panningWaitsForTheGate() {
        assertEquals(List.of(FIRST + 4), plans(new double[] {2, 2, 2, 2, 2}, new float[] {0, 30, 30, 30, 30}),
                "a yaw-only change plans once 4 ticks have passed");
    }

    @Test void magnificationWaveringAtAStepWaitsForTheGate() {
        // 2x is a rounding edge: the rounded zoom flips every tick, so each tick's view differs.
        double[] zooms = new double[9];
        for (int i = 0; i < zooms.length; i++) zooms[i] = i % 2 == 0 ? 2.000001 : 1.999999;
        FarZoom low = view(1.999999, 0, null), high = view(2.000001, 0, low);
        assertNotSame(low, high, "the wavering crosses the zoom's rounding");
        assertEquals(List.of(FIRST + 4, FIRST + 8), plans(zooms), "wavering plans as a turn does, once every 4 ticks");
    }

    @Test void magnificationWaveringAtTheLeastZoomWaitsForTheGate() {
        double[] zooms = new double[9];
        for (int i = 0; i < zooms.length; i++) zooms[i] = FarZoom.MIN * (i % 2 == 0 ? 1.000001 : 0.999999);
        assertEquals(List.of(), plans(zooms), "a view zoomed stays zoomed just under the least zoom, so it does not zoom in afresh");
    }

    @Test void zoomingOutPastTheHoldUnzooms() {
        FarZoom zoomed = view(FarZoom.MIN, 0, null);
        assertNotNull(zoomed);
        assertNotNull(view(FarZoom.MIN * 0.9, 0, zoomed), "a tenth under the least zoom is within the hold");
        assertNull(view(1.2, 0, zoomed), "well under the least zoom is unzoomed");
        assertNull(view(FarZoom.MIN * 0.9, 0, null), "the hold keeps a zoomed view, it does not start one");
    }

    @Test void creepingMagnificationPlansAtOnceEachWholeStep() {
        // Two fifths of a half step of two a tick: the third tick has zoomed a whole step since the last plan, then the sixth.
        double[] zooms = new double[7];
        for (int i = 0; i < zooms.length; i++) zooms[i] = Math.pow(2, (2 + 0.4 * i) / 2);
        assertEquals(List.of(FIRST + 3, FIRST + 6), plans(zooms));
    }

    private static double[] zooms(double... zooms) { return zooms; }

    @Nullable private static FarZoom view(double zoom, float yaw, @Nullable FarZoom previous) {
        return FarZoom.at(zoom, 1, 0, 5000, 0, 5000, yaw, 0, previous);
    }

    private static List<Integer> plans(double[] zooms) { return plans(zooms, new float[zooms.length]); }

    /**
     * The ticks after {@link #FIRST} that plan, with the view at {@code zooms[i]} times and yaw
     * {@code yaws[i]} at tick {@code FIRST + i}; a zoom under {@link FarZoom#MIN} is unzoomed.
     */
    private static List<Integer> plans(double[] zooms, float[] yaws) {
        FarRenderer.PlanGate gate = new FarRenderer.PlanGate();
        FarZoom view = null;
        List<Integer> plans = new ArrayList<>();
        for (int i = 0; i < zooms.length; i++) {
            FarZoom before = view;
            view = view(zooms[i], yaws[i], before);
            gate.zoom(before, view);
            if (gate.due(FIRST + i) && i > 0) plans.add(FIRST + i);
        }
        return plans;
    }
}
