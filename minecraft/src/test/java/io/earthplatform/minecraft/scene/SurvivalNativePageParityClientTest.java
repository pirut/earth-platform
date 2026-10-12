package io.earthplatform.minecraft.scene;

import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executable host ownership/scheduling policy. These tests do not execute a loaded renderer or GL. */
class SurvivalNativePageParityClientTest {
    @Test void finalSuccessWaitsForItsExactAnchorExitAndSameFramePost() {
        var control=new SurvivalNativePageParityClient.Control(1);
        assertTrue(control.attempt(42,()->{},()->{}));assertTrue(control.successAwaitingFrame());
        assertThrows(IllegalStateException.class,()->control.framePost(42));
        assertThrows(IllegalStateException.class,()->control.anchorExit(43));
        assertTrue(control.successAwaitingFrame());control.anchorExit(42);
        assertThrows(IllegalStateException.class,()->control.framePost(43));
        assertFalse(control.attempt(42,()->fail("repeat anchor must not rebuild"),()->{}));
        assertFalse(control.attempt(43,()->fail("later frame must not rebuild"),()->{}));
        control.framePost(42);assertTrue(control.finalFrameComplete);assertFalse(control.successAwaitingFrame());
    }
    @Test void delayedFrameErrorOverturnsCompletedGeometryAndCannotBecomePass() {
        var control=new SurvivalNativePageParityClient.Control(1);
        control.attempt(42,()->{},()->{});control.anchorExit(42);
        var error=new IllegalStateException("GL error after restored owner is reused by Iris");control.fail(error);
        control.framePost(42);assertSame(error,control.failure);assertFalse(control.finalFrameComplete);
        assertFalse(control.successAwaitingFrame());assertFalse(control.attempt(43,()->fail("failed terminal cannot restart"),()->{}));
    }
    @Test void oneCasePerFrameAndFiniteCompletionCannotRestart() {
        var control=new SurvivalNativePageParityClient.Control(7);
        var work=new AtomicInteger();var cleanup=new AtomicInteger();
        for(int i=0;i<7;i++) {
            assertTrue(control.attempt(i,work::incrementAndGet,cleanup::incrementAndGet));
            assertFalse(control.attempt(i,work::incrementAndGet,cleanup::incrementAndGet));
        }
        assertEquals(7,work.get());assertEquals(7,cleanup.get());assertEquals(7,control.completed);
        assertTrue(control.terminal);assertNull(control.failure);assertFalse(control.cleanupPending);
        assertFalse(control.attempt(8,work::incrementAndGet,cleanup::incrementAndGet));
    }
    @Test void reentrantFrameCannotStartNewWorkWhileOwnerIsActive() {
        var control=new SurvivalNativePageParityClient.Control(2);
        assertTrue(control.attempt(1,()-> {
            assertTrue(control.active);assertTrue(control.cleanupPending);
            assertFalse(control.attempt(2,()->fail("reentrant work"),()->fail("reentrant cleanup")));
        },()->assertTrue(control.active)));
        assertEquals(1,control.completed);assertFalse(control.terminal);
    }
    @Test void firstWorkFaultStopsMatrixAndStillDrainsItsOwner() {
        var control=new SurvivalNativePageParityClient.Control(231);
        var fault=new IllegalStateException("native byte mismatch");var cleanup=new AtomicInteger();
        assertTrue(control.attempt(1,()->{throw fault;},cleanup::incrementAndGet));
        assertSame(fault,control.failure);assertEquals(0,control.completed);assertEquals(1,cleanup.get());
        assertTrue(control.terminal);assertFalse(control.cleanupPending);
        assertFalse(control.attempt(2,()->fail("must never continue after mismatch"),()->{}));
    }
    @Test void cleanupFaultRetainsResponsibilityAndSuccessfulRetryNeverRestartsMatrix() {
        var control=new SurvivalNativePageParityClient.Control(7);
        var cleanupCalls=new AtomicInteger();var fault=new IllegalStateException("physical delete failed");
        Runnable cleanup=()->{if(cleanupCalls.incrementAndGet()<3)throw fault;};
        control.attempt(1,()->{},cleanup);
        assertTrue(control.cleanupPending);assertTrue(control.terminal);assertEquals(0,control.completed);
        assertFalse(control.attempt(2,()->fail("new native owner after failed cleanup"),cleanup));
        control.retryCleanup(cleanup);assertTrue(control.cleanupPending);
        control.retryCleanup(cleanup);assertFalse(control.cleanupPending);
        assertSame(fault,control.failure);assertTrue(control.terminal);
        assertFalse(control.attempt(3,()->fail("cleanup success is not restart authority"),cleanup));
    }
    @Test void resourceInvalidationDuringWorkCannotBeReportedAsSuccessful() {
        var control=new SurvivalNativePageParityClient.Control(7);
        var cleanup=new AtomicInteger();
        control.attempt(1,()->control.fail(new IllegalStateException("resource generation changed")),cleanup::incrementAndGet);
        assertTrue(control.terminal);assertEquals(0,control.completed);assertEquals(1,cleanup.get());
        assertNotNull(control.failure);assertFalse(control.cleanupPending);
    }
    @Test void eligibilityWatchCountsOnlyDistinctLoadedWorldFramesAndExpiresOnce() {
        var watch=new SurvivalNativePageParityClient.EligibilityWatch(3);
        assertFalse(watch.observe(1,false,false));assertEquals(0,watch.missed);
        assertFalse(watch.observe(2,true,false));assertEquals(1,watch.missed);
        assertFalse(watch.observe(2,true,false));assertEquals(1,watch.missed);
        assertFalse(watch.observe(3,true,false));
        assertTrue(watch.observe(4,true,false));assertTrue(watch.expired);
        assertFalse(watch.observe(5,true,false));assertEquals(3,watch.missed);
    }
    @Test void oneEligibleFrameResetsFiniteWatchAndExpiredWatchCannotRestart() {
        var watch=new SurvivalNativePageParityClient.EligibilityWatch(2);
        assertFalse(watch.observe(1,true,false));
        assertFalse(watch.observe(2,true,true));assertEquals(0,watch.missed);
        assertFalse(watch.observe(3,true,false));assertTrue(watch.observe(4,true,false));
        assertFalse(watch.observe(5,true,true));assertTrue(watch.expired);assertEquals(2,watch.missed);
        assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityClient.EligibilityWatch(0));
        assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityClient.EligibilityWatch(601));
    }
    @Test void initialServerCatchupWaitIsBoundedAndCannotHideLaterOrUnsupportedDistanceChanges() {
        assertTrue(SurvivalNativePageParityClient.waitingForInitialServerDistance(false,true,12,8));
        for(int selected:List.of(1,33))assertFalse(SurvivalNativePageParityClient.waitingForInitialServerDistance(false,true,selected,2));
        assertFalse(SurvivalNativePageParityClient.waitingForInitialServerDistance(false,true,12,12));
        assertFalse(SurvivalNativePageParityClient.waitingForInitialServerDistance(false,true,12,13));
        assertFalse(SurvivalNativePageParityClient.waitingForInitialServerDistance(false,true,12,0));
        assertFalse(SurvivalNativePageParityClient.waitingForInitialServerDistance(false,false,12,8));
        assertFalse(SurvivalNativePageParityClient.waitingForInitialServerDistance(true,true,12,8));
        var watch=new SurvivalNativePageParityClient.EligibilityWatch(2);
        assertFalse(watch.observe(1,true,!SurvivalNativePageParityClient.waitingForInitialServerDistance(false,true,12,8)));
        assertTrue(watch.observe(2,true,!SurvivalNativePageParityClient.waitingForInitialServerDistance(false,true,12,8)));
    }
    @Test void levelConnectionModelAtlasAndResourceEpochAllFenceWithObjectIdentity() {
        Object level=new Object(),connection=new Object(),models=new Object(),atlas=new Object();
        var fence=new SurvivalNativePageParityClient.Fence(level,connection,models,atlas,3);
        assertTrue(fence.same(new SurvivalNativePageParityClient.Fence(level,connection,models,atlas,3)));
        assertFalse(fence.same(null));
        assertFalse(fence.same(new SurvivalNativePageParityClient.Fence(new Object(),connection,models,atlas,3)));
        assertFalse(fence.same(new SurvivalNativePageParityClient.Fence(level,new Object(),models,atlas,3)));
        assertFalse(fence.same(new SurvivalNativePageParityClient.Fence(level,connection,new Object(),atlas,3)));
        assertFalse(fence.same(new SurvivalNativePageParityClient.Fence(level,connection,models,new Object(),3)));
        assertFalse(fence.same(new SurvivalNativePageParityClient.Fence(level,connection,models,atlas,4)));
        assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityClient.Fence(level,connection,models,atlas,0));
    }
    @Test void exactSharedIndexChargeIsRetainedAndLargerOrUnverifiableStorageRefuses() {
        var budget=new NativeVisualBudget();
        var reservation=budget.reserve(1,SurvivalNativePageParityClient.SHARED_INDEX_BYTES);
        assertEquals(462_848,reservation.chargedBytes());assertEquals(1,budget.evidence().pieces());
        assertEquals(462_848,budget.evidence().bytes());assertFalse(reservation.released());
        SurvivalNativePageParityClient.validateStorage(19,393_216,4,true);
        SurvivalNativePageParityClient.validateStorage(19,98_304,2,true);
        assertThrows(IllegalStateException.class,()->SurvivalNativePageParityClient.validateStorage(0,393_216,4,true));
        assertThrows(IllegalStateException.class,()->SurvivalNativePageParityClient.validateStorage(19,393_217,4,true));
        assertThrows(IllegalStateException.class,()->SurvivalNativePageParityClient.validateStorage(19,196_607,4,true));
        assertThrows(IllegalStateException.class,()->SurvivalNativePageParityClient.validateStorage(19,393_216,4,false));
        assertThrows(IllegalStateException.class,()->SurvivalNativePageParityClient.validateStorage(19,393_216,1,true));
        // Logical test reservation only; no GL context or process buffer exists in this test.
        reservation.releaseAfterPhysicalClose();assertEquals(0,budget.evidence().bytes());
    }
    @Test void explicitLayoutAndFiniteCountsRejectFallbackOrUnboundedModes() {
        assertEquals(SurvivalNativePageParityClient.Layout.DEFAULT32,SurvivalNativePageParityClient.Layout.parse("default32"));
        assertEquals(SurvivalNativePageParityClient.Layout.IRIS52,SurvivalNativePageParityClient.Layout.parse("iris52"));
        for(String value:List.of("","auto","default","IRIS52"))assertThrows(IllegalArgumentException.class,()->SurvivalNativePageParityClient.Layout.parse(value));
        assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityClient.Control(0));
        assertThrows(IllegalArgumentException.class,()->new SurvivalNativePageParityClient.Control(232));
        assertEquals(7,SurvivalNativePageParityClient.Case.values().length);
    }
    @Test void slicedDigestIgnoresRawPaddingHashButDetectsNativeAttributesMemberAndCounts() {
        var comparison=new SurvivalNativePageParityProbe.Comparison(0,SurvivalNativePageCompiler.Layer.SOLID,4,"raw-a","raw-b","attributes","attributes");
        String digest=SurvivalNativePageParityClient.semanticDigest(report(List.of(comparison)));
        assertEquals(digest,SurvivalNativePageParityClient.semanticDigest(report(List.of(
                new SurvivalNativePageParityProbe.Comparison(0,SurvivalNativePageCompiler.Layer.SOLID,4,"raw-c","raw-d","attributes","attributes")))));
        for(var changed:List.of(
                new SurvivalNativePageParityProbe.Comparison(1,SurvivalNativePageCompiler.Layer.SOLID,4,"raw-a","raw-b","attributes","attributes"),
                new SurvivalNativePageParityProbe.Comparison(0,SurvivalNativePageCompiler.Layer.SOLID,8,"raw-a","raw-b","attributes","attributes"),
                new SurvivalNativePageParityProbe.Comparison(0,SurvivalNativePageCompiler.Layer.SOLID,4,"raw-a","raw-b","attributes","changed")))
            assertNotEquals(digest,SurvivalNativePageParityClient.semanticDigest(report(List.of(changed))));
    }
    private static SurvivalNativePageParityProbe.Report report(List<SurvivalNativePageParityProbe.Comparison> comparisons) {
        var padding=new ArrayList<Integer>();for(int i=12;i<32;i++)padding.add(i);
        var schema=new SurvivalNativePageParityProbe.Schema(32,List.of(new SurvivalNativePageParityProbe.Element("Position",0,12)),padding);
        return new SurvivalNativePageParityProbe.Report("SYNTHETIC_TEST",false,schema,1,27,1,1,128,24,1,1,
                List.of(),comparisons,1,1,1,1,true,true,true,
                new SurvivalNativePageParityProbe.ReservationEvidence(1,1,1,1,4,128,4,128));
    }
}
