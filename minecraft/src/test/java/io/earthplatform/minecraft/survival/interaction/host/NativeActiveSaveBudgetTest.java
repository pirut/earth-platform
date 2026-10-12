package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Pure lease arithmetic. Does not qualify native readiness or real output consumption. */
class NativeActiveSaveBudgetTest {
    @Test void oldDurableAndNewCutMustFitSameUnchangedNativeAllowance(){
        int oldDurable=12*1024,available=256*1024-NativeActiveSaveBudget.CHAIN_BYTES-oldDurable;
        var lease=new NativeActiveSaveBudget(available,1,64,8);
        assertTrue(lease.charged()+oldDurable+NativeActiveSaveBudget.CHAIN_BYTES<256*1024);
        int required=NativeActiveSaveBudget.reservation(1,64,8);
        assertThrows(SurvivalRefusal.class,()->new NativeActiveSaveBudget(required,1,64,8));
        assertThrows(SurvivalRefusal.class,()->new NativeActiveSaveBudget(available,16,64,8));
    }
    @Test void failedOrIncompleteCaptureKeepsFullReservation(){
        var lease=new NativeActiveSaveBudget(256*1024,1,64,8);int charged=lease.charged();
        assertThrows(SurvivalRefusal.class,lease::requireRetirable);
        assertThrows(SurvivalRefusal.class,()->lease.finishCapture(Integer.MAX_VALUE));
        assertEquals(charged,lease.charged());assertDoesNotThrow(lease::requireScratch);
    }
    @Test void exactRetirementAllowsConsecutiveBoundedEpochs(){
        int durable=4096;
        for(int epoch=0;epoch<100;epoch++){
            var lease=new NativeActiveSaveBudget(256*1024-durable,1,64,8);
            lease.finishCapture(12*1024);assertEquals(12*1024+NativeActiveSaveBudget.SCRATCH,lease.charged());
            lease.requireRetirable();lease.retire();assertEquals(0,lease.charged());
            assertThrows(SurvivalRefusal.class,lease::requireRetirable);assertThrows(SurvivalRefusal.class,lease::requireScratch);
            durable=4096+epoch; // Only replacement CURRENT survives; no old epoch chain accumulates.
        }
    }
    @Test void transientWitnessAndEveryRosterWrapperArePrepaid(){
        int one=NativeActiveSaveBudget.reservation(1,1,1),two=NativeActiveSaveBudget.reservation(2,1,1);
        assertEquals(NativeActiveSaveBudget.IMAGE_SLOT+1024,two-one);
        assertTrue(NativeActiveSaveHoldWitness.LOGICAL_BYTES<=NativeActiveSaveBudget.SCALARS);
        assertThrows(SurvivalRefusal.class,()->NativeActiveSaveBudget.reservation(17,64,8));
        assertThrows(SurvivalRefusal.class,()->NativeActiveSaveBudget.reservation(1,65,8));
        assertThrows(SurvivalRefusal.class,()->NativeActiveSaveBudget.reservation(1,64,9));
    }
}
