package io.earthplatform.minecraft.coregate;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent fault review of callback reentry across ticket acquisition and validation. */
class RollingPresentationReentrancyReviewTest {
    private static final class Ticket { boolean closed; }
    private static final class Driver implements RollingEditPresentation.Driver<Ticket,Ticket> {
        final List<Ticket> allocated=new ArrayList<>();
        Runnable nativeCallback=()->{},farCallback=()->{},nativeRead=()->{},farRead=()->{};
        int farReads,nativeCloses,farCloses;
        Runnable nativeClose=()->{},farClose=()->{};
        public Ticket captureNative(){var t=new Ticket();allocated.add(t);nativeCallback.run();return t;}
        public Ticket captureFar(){var t=new Ticket();allocated.add(t);farCallback.run();return t;}
        public boolean nativeCurrent(Ticket t){nativeRead.run();return !t.closed;}
        public boolean farCurrent(Ticket t){farReads++;farRead.run();return !t.closed;}
        public void closeNative(Ticket t){nativeCloses++;nativeClose.run();t.closed=true;}public void closeFar(Ticket t){farCloses++;farClose.run();t.closed=true;}
    }
    private static RollingEditPresentation.Stamp stamp(long publication){return new RollingEditPresentation.Stamp(1,publication,1,publication,publication-1,1,"a".repeat(64));}
    @Test void retirementDuringNativeAcquisitionCannotAdmitANestedCaptureOrLoseItsOwner(){
        assertNestedCaptureRejected(true);
    }
    @Test void retirementDuringFarAcquisitionCannotAdmitANestedCaptureOrLoseItsOwner(){
        assertNestedCaptureRejected(false);
    }
    private static void assertNestedCaptureRejected(boolean nativeSide){
        var driver=new Driver();var pair=new RollingEditPresentation<>(driver);
        Runnable callback=()->{
            driver.nativeCallback=()->{};driver.farCallback=()->{};
            pair.retire();
            assertThrows(IllegalStateException.class,()->pair.capture(stamp(2)),"Cancelled acquisition still owns its call stack until the returned ticket is registered");
        };
        if(nativeSide)driver.nativeCallback=callback;else driver.farCallback=callback;
        try{assertThrows(IllegalStateException.class,()->pair.capture(stamp(1)));}
        finally{pair.close();}
        assertTrue(driver.allocated.stream().allMatch(t->t.closed),"Every returned owner must remain reachable for cleanup");
    }
    @Test void nativeValidationRetirementStopsBeforeFarPredicateReceivesAReleasedTicket(){
        var driver=new Driver();var pair=new RollingEditPresentation<>(driver);pair.capture(stamp(1));int farReads=driver.farReads;
        driver.nativeRead=pair::retire;
        assertFalse(pair.current());assertEquals(farReads,driver.farReads);assertEquals(0,pair.retainedTicketOwners());pair.close();
    }
    @Test void farValidationRetirementCannotClaimThePairStillExists(){
        var driver=new Driver();var pair=new RollingEditPresentation<>(driver);pair.capture(stamp(1));driver.farRead=pair::retire;
        assertFalse(pair.current());assertEquals(0,pair.retainedTicketOwners());assertTrue(driver.allocated.stream().allMatch(t->t.closed));pair.close();
    }
    @Test void recursiveRetirementFromBothCloseCallbacksDoesNotRepeatDestruction(){
        var driver=new Driver();var pair=new RollingEditPresentation<>(driver);pair.capture(stamp(1));
        driver.nativeClose=pair::retire;driver.farClose=pair::retire;
        pair.retire();assertEquals(1,driver.nativeCloses);assertEquals(1,driver.farCloses);
        assertEquals(0,pair.retainedTicketOwners());assertNull(pair.stamp());pair.close();
    }
    @Test void failedRecursiveRetirementKeepsOnlyTheFailedOwnerAndRejectsNestedCapture(){
        var driver=new Driver();var pair=new RollingEditPresentation<>(driver);pair.capture(stamp(1));
        driver.nativeClose=()->{pair.retire();assertThrows(IllegalStateException.class,()->pair.capture(stamp(2)));throw new IllegalStateException("native cleanup retry");};
        driver.farClose=pair::retire;
        assertThrows(IllegalStateException.class,pair::retire);assertEquals(1,driver.nativeCloses);assertEquals(1,driver.farCloses);
        assertEquals(1,pair.retainedTicketOwners());assertFalse(pair.current());
        driver.nativeClose=()->{};pair.retire();assertEquals(2,driver.nativeCloses);assertEquals(1,driver.farCloses);
        assertEquals(0,pair.retainedTicketOwners());assertTrue(driver.allocated.stream().allMatch(t->t.closed));pair.close();
    }

}
