package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure predicate regressions. The registered fixture separately proves actual async carrier drift. */
class NativeActiveSaveClockTest {
    @Test void synchronousCaptureRequiresExactTimeUntilBoundaryAndTicketInstallation() {
        assertEquals(92,check(92,92,92,false,0));
        assertThrows(SurvivalRefusal.class,()->check(92,92,93,false,0));
        assertThrows(SurvivalRefusal.class,()->check(92,92,91,false,0));
        assertThrows(SurvivalRefusal.class,()->check(92,93,93,false,0));
    }
    @Test void asyncCarrierCanAdvanceWithoutRetimestampingTheCapturedBoundary() {
        long captured=92,last=captured;
        for(long observed:new long[]{92,93,94,94,107,Long.MAX_VALUE})last=check(captured,last,observed,true,0);
        assertEquals(92,captured);assertEquals(Long.MAX_VALUE,last);
    }
    @Test void regressionFromLastObservationRefusesEvenAboveOriginalCapture() {
        long last=check(92,92,107,true,0);
        var failure=assertThrows(SurvivalRefusal.class,()->check(92,last,106,true,0));
        assertTrue(failure.getMessage().contains("captured=92 last=107 current=106 installed=true"));
        assertThrows(SurvivalRefusal.class,()->check(92,107,92,true,0));
        assertThrows(SurvivalRefusal.class,()->check(92,107,91,true,0));
        assertThrows(SurvivalRefusal.class,()->check(92,Long.MAX_VALUE,Long.MIN_VALUE,true,0));
    }
    @Test void negativeAndCorruptClockLineageRefusesRatherThanNormalizing() {
        assertThrows(SurvivalRefusal.class,()->check(-1,-1,0,true,0));
        assertThrows(SurvivalRefusal.class,()->check(92,91,93,true,0));
        assertEquals(0,check(0,0,0,false,0));
    }
    @Test void scheduledOwnersRefuseInBothTemporalPhasesIncludingUnchangedClock() {
        for(boolean installed:new boolean[]{false,true})for(int owners:new int[]{-1,1,16,Integer.MAX_VALUE}) {
            assertThrows(SurvivalRefusal.class,()->check(92,92,92,installed,owners));
            assertThrows(SurvivalRefusal.class,()->check(92,92,107,installed,owners));
        }
    }
    private static long check(long captured,long last,long now,boolean installed,int owners) {
        return NativeEffectDeliveryHost.checkedActiveSaveCarrierTime(captured,last,now,installed,owners);
    }
}
