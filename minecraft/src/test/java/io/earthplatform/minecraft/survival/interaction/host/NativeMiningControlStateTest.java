package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.world.CubeCell;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeMiningControlStateTest {
    @Test void snapshotsRetainSimultaneousHeldDelayedTargetsAndUnclampedStages() {
        var held=CubeCell.of(4,134,7);var delayed=CubeCell.of(5,133,7);
        var state=new NativeMiningControlState.Mode(true,23,held,31,true,delayed,11,12);
        assertTrue(state.destroying());assertTrue(state.delayed());assertEquals(held,state.target());
        assertEquals(delayed,state.delayedTarget());assertEquals(12,state.lastSentStage());
    }
    @Test void nativeIntegerWrapIsRepresentableWithoutSyntheticElapsedTime() {
        var target=CubeCell.of(4,134,7);
        var before=new NativeMiningControlState.Mode(true,Integer.MAX_VALUE,target,Integer.MAX_VALUE,false,CubeCell.of(0,0,0),0,0);
        var after=new NativeMiningControlState.Mode(true,Integer.MAX_VALUE,target,Integer.MIN_VALUE,false,CubeCell.of(0,0,0),0,0);
        assertEquals(before.ticks()+1,after.ticks());
    }
    @Test void fullSignedCoordinatesDoNotAliasInControlState() {
        var lower=CubeCell.of(2,-2049,7);var upper=CubeCell.of(2,2047,7);
        assertNotEquals(lower,upper);
        var first=new NativeMiningControlState.Mode(true,0,lower,0,false,lower,0,0);
        var second=new NativeMiningControlState.Mode(true,0,upper,0,false,upper,0,0);
        assertNotEquals(first,second);
    }
    @Test void dataDoesNotClaimCancelClearsDelayedNativeState() {
        var target=CubeCell.of(4,134,7);
        var nativeAbortImage=new NativeMiningControlState.Mode(false,7,target,9,true,target,7,2);
        assertFalse(nativeAbortImage.destroying());assertTrue(nativeAbortImage.delayed());
    }
}
