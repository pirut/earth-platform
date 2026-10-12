package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual Plan's delivery latch. Not native GameMode or packet semantic evidence. */
class NativeMiningDeliveryLatchTest {
    @Test void acceptedDeliveryCanBeginAndFinishExactlyOnce() {
        var latch=new NativeMiningDeliveryLatch<Object>();var receipt=new Object();
        latch.accept(receipt);latch.begin(receipt);latch.finish(receipt);
        assertThrows(SurvivalRefusal.class,()->latch.begin(receipt));
    }
    @Test void equalReceiptValuesCannotSubstituteForExactIdentity() {
        var latch=new NativeMiningDeliveryLatch<String>();var first=new String("receipt");var copy=new String("receipt");
        latch.accept(first);assertEquals(first,copy);assertNotSame(first,copy);
        assertThrows(SurvivalRefusal.class,()->latch.begin(copy));
        assertThrows(SurvivalRefusal.class,()->latch.begin(first));
    }
    @Test void finishWithoutInstallationIsRefusedAndSticky() {
        var latch=new NativeMiningDeliveryLatch<Object>();var receipt=new Object();latch.accept(receipt);
        assertThrows(SurvivalRefusal.class,()->latch.finish(receipt));
        assertThrows(SurvivalRefusal.class,()->latch.begin(receipt));
    }
    @Test void caughtForeignLaneRefusalStillPoisonsOuterCommit() {
        var latch=new NativeMiningDeliveryLatch<Object>();var caught=new SurvivalRefusal("foreign MOVE reentry");
        try {latch.poison(caught);throw caught;}catch(SurvivalRefusal ignored) {}
        assertSame(caught,assertThrows(SurvivalRefusal.class,latch::requireHealthy));
        assertSame(caught,assertThrows(SurvivalRefusal.class,()->latch.accept(new Object())));
    }
    @Test void failedAcceptedPrefixCannotBeRetriedOrReplaced() {
        var latch=new NativeMiningDeliveryLatch<Object>();var receipt=new Object();latch.accept(receipt);latch.begin(receipt);
        var failure=new IllegalStateException("transport prefix failed");latch.poison(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,()->latch.begin(receipt)));
        assertSame(failure,assertThrows(IllegalStateException.class,()->latch.accept(new Object())));
        assertSame(failure,assertThrows(IllegalStateException.class,()->latch.finish(receipt)));
    }
    @Test void firstFailureSurvivesSecondaryFailure() {
        var latch=new NativeMiningDeliveryLatch<Object>();var first=new AssertionError("first");latch.poison(first);
        latch.poison(new IllegalStateException("secondary"));assertSame(first,assertThrows(AssertionError.class,latch::requireHealthy));
    }
}
