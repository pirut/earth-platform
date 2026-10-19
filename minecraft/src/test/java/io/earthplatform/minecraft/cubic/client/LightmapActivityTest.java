package io.earthplatform.minecraft.cubic.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LightmapActivityTest {
    private static final Object WORLD = new Object();
    private static boolean sample(LightmapActivity activity, Object world, double x, float yaw, float pitch,
                                  int width, boolean settled, long now) {
        return activity.rotate(world, x, 100, 200, yaw, pitch, width, 960, settled, now);
    }

    @Test void requiresContinuousQuietInterval() {
        var activity = new LightmapActivity();
        assertFalse(sample(activity,WORLD,1,90,8,1708,true,0));
        assertFalse(sample(activity,WORLD,1,90,8,1708,true,LightmapActivity.QUIET_NANOS-1));
        assertTrue(sample(activity,WORLD,1,90,8,1708,true,LightmapActivity.QUIET_NANOS));
    }

    @Test void motionAndRotationReturnToOriginalImmediately() {
        var activity = new LightmapActivity();
        sample(activity,WORLD,1,90,8,1708,true,0);
        assertTrue(sample(activity,WORLD,1,90,8,1708,true,1_000_000_000L));
        assertFalse(sample(activity,WORLD,1.000001,90,8,1708,true,1_000_000_001L));
        assertFalse(sample(activity,WORLD,1.000001,91,8,1708,true,2_000_000_000L));
        assertFalse(sample(activity,WORLD,1.000001,91,9,1708,true,3_000_000_000L));
        assertTrue(sample(activity,WORLD,1.000001,91,9,1708,true,3_500_000_000L));
    }

    @Test void streamingRestartsTheWholeQuietInterval() {
        var activity = new LightmapActivity();
        sample(activity,WORLD,1,90,8,1708,true,0);
        assertTrue(sample(activity,WORLD,1,90,8,1708,true,1_000_000_000L));
        assertFalse(sample(activity,WORLD,1,90,8,1708,false,2_000_000_000L));
        assertFalse(sample(activity,WORLD,1,90,8,1708,true,2_499_999_999L));
        assertTrue(sample(activity,WORLD,1,90,8,1708,true,2_500_000_000L));
    }

    @Test void newWorldResizeAndResetCannotReuseOldReadiness() {
        var activity = new LightmapActivity();
        sample(activity,WORLD,1,90,8,1708,true,0);
        assertTrue(sample(activity,WORLD,1,90,8,1708,true,1_000_000_000L));
        assertFalse(sample(activity,new Object(),1,90,8,1708,true,2_000_000_000L));
        sample(activity,WORLD,1,90,8,1708,true,3_000_000_000L);
        assertFalse(sample(activity,WORLD,1,90,8,1280,true,4_000_000_000L));
        activity.reset();
        assertFalse(sample(activity,WORLD,1,90,8,1280,true,5_000_000_000L));
    }

    @Test void invalidInputsAndBackwardsClockFailClosed() {
        var activity = new LightmapActivity();
        sample(activity,WORLD,1,90,8,1708,true,100);
        assertFalse(sample(activity,WORLD,1,90,8,1708,true,99));
        assertFalse(sample(activity,WORLD,Double.NaN,90,8,1708,true,1_000_000_000L));
        assertFalse(sample(activity,WORLD,1,Float.NaN,8,1708,true,2_000_000_000L));
        assertFalse(sample(activity,WORLD,1,90,8,0,true,3_000_000_000L));
        assertFalse(sample(activity,null,1,90,8,1708,true,4_000_000_000L));
        assertFalse(sample(activity,WORLD,1,90,8,1708,true,5_000_000_000L));
    }
}
