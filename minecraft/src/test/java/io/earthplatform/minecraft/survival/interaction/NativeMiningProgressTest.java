package io.earthplatform.minecraft.survival.interaction;

import io.earthplatform.minecraft.survival.world.CubeCell;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.survival.interaction.NativeMiningProgress.Status.*;

class NativeMiningProgressTest {
    private static final CubeCell TARGET = CubeCell.of(-12, 8849, 3);

    @Test void heldProgressUsesElapsedServerTicksAndNativeStopTolerance() {
        var progress = new NativeMiningProgress();
        assertEquals(.1F, progress.start(TARGET, 50, .1F).fraction());
        assertEquals(.1F, progress.tick(50, .1F, true).fraction());
        assertEquals(.6F, progress.tick(55, .1F, true).fraction(), .00001F);
        assertEquals(READY, progress.stop(TARGET, 56, .1F, true).status());
        assertEquals(IDLE, progress.stop(TARGET, 56, .1F, true).status());
    }

    @Test void earlyStopFinishesOnceAtNativeDelayedThreshold() {
        var progress = new NativeMiningProgress();
        progress.start(TARGET, 10, .1F);
        assertEquals(PROGRESS, progress.stop(TARGET, 12, .1F, true).status());
        assertEquals(NativeMiningProgress.Phase.DELAYED, progress.phase());
        assertEquals(PROGRESS, progress.tick(18, .1F, true).status());
        assertEquals(READY, progress.tick(19, .1F, true).status());
        assertEquals(IDLE, progress.tick(20, .1F, true).status());
    }

    @Test void abortAndReachOrRevisionInvalidationCannotLaterBreak() {
        var progress = new NativeMiningProgress();
        progress.start(TARGET, 0, .01F);
        progress.stop(TARGET, 1, .01F, true);
        assertEquals(CANCELLED, progress.cancel().status());
        assertEquals(IDLE, progress.tick(200, 1, true).status());
        progress.start(TARGET, 201, .1F);
        assertEquals(CANCELLED, progress.tick(202, .1F, false).status());
        assertEquals(IDLE, progress.stop(TARGET, 210, 1, true).status());
    }

    @Test void signedTargetsSeparatedByPackedYPeriodCannotCompleteEachOther() {
        var progress = new NativeMiningProgress();
        CubeCell lower = CubeCell.of(2, -2049, 7), upper = CubeCell.of(2, 2047, 7);
        progress.start(lower, 0, .1F);
        assertEquals(IDLE, progress.stop(upper, 100, 1, true).status());
        assertEquals(lower, progress.target());
        assertEquals(READY, progress.stop(lower, 100, 1, true).status());
    }

    @Test void repeatedPacketsCannotAccumulateExtraProgress() {
        var progress = new NativeMiningProgress();
        progress.start(TARGET, 2, .01F);
        for (int i = 0; i < 100; i++) assertEquals(.01F, progress.tick(2, .01F, true).fraction());
        assertThrows(IllegalArgumentException.class, () -> progress.tick(1, .01F, true));
        assertThrows(IllegalArgumentException.class, () -> progress.tick(3, Float.NaN, true));
        assertEquals(READY, new NativeMiningProgress().start(TARGET, 0, 1).status());
    }
}
