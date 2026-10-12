package io.earthplatform.minecraft.coregate;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingEditPresentationTest {
    private static final class Ticket { boolean live = true; int closes; }
    private static final class Driver implements RollingEditPresentation.Driver<Ticket, Ticket> {
        Ticket n, f; boolean nCurrent = true, fCurrent = true, failNClose, failFClose;
        boolean failNCapture, failFCapture, throwNCurrent, throwFCurrent;
        Runnable onNative = () -> {}, onFar = () -> {}, onNCurrent = () -> {};
        public Ticket captureNative() { if (failNCapture) throw new IllegalStateException("native capture"); n = new Ticket(); onNative.run(); return n; }
        public Ticket captureFar() { if (failFCapture) throw new IllegalStateException("far capture"); f = new Ticket(); onFar.run(); return f; }
        public boolean nativeCurrent(Ticket t) { onNCurrent.run(); if (throwNCurrent) throw new AssertionError("native identity"); return nCurrent && t.live; }
        public boolean farCurrent(Ticket t) { if (throwFCurrent) throw new AssertionError("far identity"); return fCurrent && t.live; }
        public void closeNative(Ticket t) { t.closes++; if (failNClose) throw new IllegalStateException("native close"); t.live = false; }
        public void closeFar(Ticket t) { t.closes++; if (failFClose) throw new AssertionError("far close"); t.live = false; }
    }
    private static RollingEditPresentation.Stamp stamp(long publication) { return new RollingEditPresentation.Stamp(1,publication,1,publication,publication-1,27,"a".repeat(64)); }
    @Test void frozenDrawKeepsItsOriginalRevisionAndNeverChangesTheStamp() {
        var d=new Driver();var p=new RollingEditPresentation<>(d);var s=stamp(2);p.capture(s);
        assertSame(s,p.stamp());assertTrue(p.draw((n,f)->n==d.n&&f==d.f));assertEquals(1,p.draws());
        assertEquals(1,p.stamp().revision());assertEquals(2,p.retainedTicketOwners());p.close();assertFalse(p.frozen());
    }
    @Test void retirementReleasesBothThenAllowsNextRevisionWithoutAccumulation() {
        var d=new Driver();var p=new RollingEditPresentation<>(d);
        for(int i=1;i<=100;i++){p.capture(stamp(i));assertEquals(2,p.retainedTicketOwners());p.retire();assertEquals(0,p.retainedTicketOwners());assertNull(p.stamp());}
        assertEquals(100,p.captures());p.close();
    }
    @Test void secondCaptureCannotReplaceAnOwnedPresentation() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));assertThrows(IllegalStateException.class,()->p.capture(stamp(2)));assertEquals(stamp(1),p.stamp());p.close();}
    @Test void nativeCaptureFailureOwnsNothingButStillRequiresExplicitRetirement() {var d=new Driver();d.failNCapture=true;var p=new RollingEditPresentation<>(d);assertThrows(IllegalStateException.class,()->p.capture(stamp(1)));assertEquals(0,p.retainedTicketOwners());assertFalse(p.frozen());assertThrows(IllegalStateException.class,()->p.capture(stamp(2)));p.retire();d.failNCapture=false;p.capture(stamp(2));p.close();}
    @Test void farCaptureFailureRetainsNativeForCleanup() {var d=new Driver();d.failFCapture=true;var p=new RollingEditPresentation<>(d);assertThrows(IllegalStateException.class,()->p.capture(stamp(1)));assertEquals(1,p.retainedTicketOwners());assertFalse(p.frozen());p.close();assertEquals(1,d.n.closes);assertEquals(0,p.retainedTicketOwners());}
    @Test void failedCleanupAttemptsBothAndKeepsOnlyFailedOwners() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));d.failNClose=true;assertThrows(IllegalStateException.class,p::close);assertEquals(1,d.f.closes);assertEquals(1,p.retainedTicketOwners());assertFalse(p.current());d.failNClose=false;p.close();assertEquals(2,d.n.closes);assertEquals(1,d.f.closes);assertEquals(0,p.retainedTicketOwners());}
    @Test void bothCleanupFailuresAreRetainedAndSuppressedThenRetryWorks() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));d.failNClose=d.failFClose=true;var e=assertThrows(IllegalStateException.class,p::retire);assertEquals(1,e.getSuppressed().length);assertEquals(2,p.retainedTicketOwners());assertFalse(p.current());d.failNClose=d.failFClose=false;p.retire();assertEquals(0,p.retainedTicketOwners());}
    @Test void explicitCancellationBeforeDrawPreventsAllSubmissions() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));p.invalidate();var calls=new AtomicInteger();assertFalse(p.draw((n,f)->{calls.incrementAndGet();return true;}));assertEquals(0,calls.get());assertEquals(2,p.retainedTicketOwners());p.close();}
    @Test void reentrantCancellationInsideDrawFailsPostcheck() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));assertFalse(p.draw((n,f)->{p.invalidate();return true;}));assertEquals(0,p.draws());p.close();}
    @Test void reentrantRetirementInsideDrawCannotAttestSuccess() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));assertFalse(p.draw((n,f)->{p.retire();return true;}));assertEquals(0,p.draws());assertEquals(0,p.retainedTicketOwners());p.close();}
    @Test void falseSubmissionRevokesPresentation() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));assertFalse(p.draw((n,f)->false));assertFalse(p.current());p.close();}
    @Test void failingSubmissionRevokesWithoutDiscardingOwners() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));assertThrows(AssertionError.class,()->p.draw((n,f)->{throw new AssertionError("GL");}));assertFalse(p.current());assertEquals(2,p.retainedTicketOwners());p.close();}
    @Test void staleNativeIdentityRevokesAndCannotRevive() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));d.nCurrent=false;assertFalse(p.current());d.nCurrent=true;assertFalse(p.current());p.close();}
    @Test void staleFarIdentityRevokesAndCannotRevive() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));d.fCurrent=false;assertFalse(p.current());d.fCurrent=true;assertFalse(p.current());p.close();}
    @Test void sourceGetterErrorsFenceAndRetainForCleanup() {for(boolean nativeSide:new boolean[]{true,false}){var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));d.throwNCurrent=nativeSide;d.throwFCurrent=!nativeSide;assertThrows(AssertionError.class,p::current);d.throwNCurrent=d.throwFCurrent=false;assertFalse(p.current());assertEquals(2,p.retainedTicketOwners());p.close();}}
    @Test void staleAcquisitionNeverBecomesDrawable() {var d=new Driver();d.fCurrent=false;var p=new RollingEditPresentation<>(d);assertThrows(IllegalStateException.class,()->p.capture(stamp(1)));assertEquals(2,p.retainedTicketOwners());assertFalse(p.current());p.close();}
    @Test void cancellationDuringFirstCaptureRetainsReturnedOwner() {var d=new Driver();var p=new RollingEditPresentation<>(d);d.onNative=p::invalidate;assertThrows(IllegalStateException.class,()->p.capture(stamp(1)));assertEquals(1,p.retainedTicketOwners());assertNull(d.f);p.close();}
    @Test void cancellationDuringSecondCaptureRetainsBothReturnedOwners() {var d=new Driver();var p=new RollingEditPresentation<>(d);d.onFar=p::invalidate;assertThrows(IllegalStateException.class,()->p.capture(stamp(1)));assertEquals(2,p.retainedTicketOwners());p.close();}
    @Test void closeDuringCaptureCannotLeakTheReturnedOwnerOrReopen() {var d=new Driver();var p=new RollingEditPresentation<>(d);d.onNative=p::close;assertThrows(IllegalStateException.class,()->p.capture(stamp(1)));assertEquals(1,p.retainedTicketOwners());p.close();assertEquals(0,p.retainedTicketOwners());assertThrows(IllegalStateException.class,()->p.capture(stamp(2)));}
    @Test void captureValidationCannotReentrantlyCancelAndThenPublish() {var d=new Driver();var p=new RollingEditPresentation<>(d);d.onNCurrent=p::invalidate;assertThrows(IllegalStateException.class,()->p.capture(stamp(1)));assertFalse(p.current());p.close();}
    @Test void closeIsTerminalAndIdempotent() {var d=new Driver();var p=new RollingEditPresentation<>(d);p.capture(stamp(1));p.close();p.close();assertEquals(1,d.n.closes);assertEquals(1,d.f.closes);assertThrows(IllegalStateException.class,()->p.capture(stamp(2)));assertFalse(p.current());}
    @Test void noPresentationAtBootstrapHasNoVisualOrAuthorityClaim() {var p=new RollingEditPresentation<>(new Driver());assertFalse(p.frozen());assertFalse(p.current());assertFalse(p.draw((n,f)->true));assertEquals(0,p.retainedTicketOwners());assertNull(p.stamp());p.close();}
    @Test void stampEnforcesBoundedNonemptyMeshSetAndNonnegativeIdentities() {assertThrows(IllegalArgumentException.class,()->new RollingEditPresentation.Stamp(1,1,1,1,0,29,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->new RollingEditPresentation.Stamp(1,1,1,1,0,0,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->new RollingEditPresentation.Stamp(-1,1,1,1,0,1,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->new RollingEditPresentation.Stamp(1,0,1,1,0,1,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->new RollingEditPresentation.Stamp(1,1,0,1,0,1,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->new RollingEditPresentation.Stamp(1,1,1,0,0,1,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->new RollingEditPresentation.Stamp(1,1,1,1,-1,1,"a".repeat(64)));}
    @Test void operationsRejectAnotherThread() throws Exception {var p=new RollingEditPresentation<>(new Driver());var errors=new AtomicInteger();var t=new Thread(()->{try{p.capture(stamp(1));}catch(IllegalStateException e){errors.incrementAndGet();}try{p.close();}catch(IllegalStateException e){errors.incrementAndGet();}});t.start();t.join();assertEquals(2,errors.get());p.close();}
}
