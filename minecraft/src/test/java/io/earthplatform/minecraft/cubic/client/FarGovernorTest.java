package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarGovernorTest {
    private long now;

    /** Runs the governor for some seconds at a frame rate; returns the steps it ends on. */
    private int run(FarGovernor governor, int seconds, double rate, int target, boolean capped, int steps, int most) {
        long end = now + seconds * 1000L;
        double at = now;
        while (at < end) {
            at += 1000.0 / rate;
            now = (long) at;
            steps = governor.frame(now, target, capped, steps, most);
        }
        return steps;
    }

    @Test void slowFramesStepDownOnceTheyLast() {
        FarGovernor governor = new FarGovernor();
        // One slow window is a hitch, not a reason.
        assertEquals(0, run(governor, 2, 40, 60, false, 0, 3));
        assertEquals(0, run(governor, 6, 60, 60, false, 0, 3));
        // Slow for good: down a step, then it looks away while the new columns come in, then down again.
        int steps = run(governor, 6, 40, 60, false, 0, 3);
        assertEquals(1, steps);
        steps = run(governor, 5, 40, 60, false, steps, 3);
        assertEquals(1, steps);
        steps = run(governor, 60, 40, 60, false, steps, 3);
        // Never below Fast.
        assertEquals(3, steps);
    }

    @Test void holdsStillInsideTheBand() {
        FarGovernor governor = new FarGovernor();
        // Between slow and headroom, uncapped: nothing to do either way.
        assertEquals(1, run(governor, 300, 58, 60, false, 1, 3));
        assertEquals(1, run(governor, 300, 66, 60, false, 1, 3));
    }

    @Test void stepsBackUpWithHeadroomOrAtTheCap() {
        FarGovernor governor = new FarGovernor();
        // With room to spare, a step up after twenty seconds of it.
        assertEquals(2, run(governor, 15, 90, 60, false, 2, 3));
        assertEquals(1, run(governor, 10, 90, 60, false, 2, 3));
        FarGovernor capped = new FarGovernor();
        now = 0;
        // Capped at the rate asked for, holding the cap is all there is to see.
        assertEquals(2, run(capped, 15, 60, 60, true, 2, 3));
        assertEquals(1, run(capped, 10, 60, 60, true, 2, 3));
    }

    @Test void aTrialTakenBackWaitsLongerNextTime() {
        FarGovernor governor = new FarGovernor();
        int steps = 1, ups = 0;
        long firstUp = -1, secondUp = -1, thirdUp = -1;
        // A computer that holds 90 a step down and 40 at the player's choice.
        for (int second = 0; second < 400; second++) {
            int before = steps;
            steps = run(governor, 1, steps == 1 ? 90 : 40, 60, false, steps, 3);
            if (steps < before) {
                ups++;
                if (ups == 1) firstUp = now; else if (ups == 2) secondUp = now; else if (ups == 3) thirdUp = now;
            }
            assertTrue(steps <= 1);
        }
        assertTrue(ups >= 3);
        // Each failed trial doubles the wait before the next, so it tries less and less often.
        assertTrue(thirdUp - secondUp > secondUp - firstUp + 15_000, (secondUp - firstUp) + " then " + (thirdUp - secondUp));
        assertTrue(ups <= 5, "tried " + ups + " times in 400 s");
    }

    @Test void resetForgetsSlowWindows() {
        FarGovernor governor = new FarGovernor();
        assertEquals(0, run(governor, 3, 40, 60, false, 0, 3));
        governor.reset();
        assertEquals(0, run(governor, 3, 40, 60, false, 0, 3));
    }
}
