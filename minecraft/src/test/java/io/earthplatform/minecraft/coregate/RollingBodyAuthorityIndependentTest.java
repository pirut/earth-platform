package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.scene.BodyNativeCompletionFixture;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.visual.NativeVisualStamp;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent value/authority adversaries. GPU receipts are explicitly modeled by the shared fixture. */
class RollingBodyAuthorityIndependentTest {
    @Test void fullResourceCheckRunsAtBothSliceEdgesAndItsCostIsInsideEveryReportedBudget() {
        try (var f=new DistanceBodyTestFixture()) {
            f.stage(DistanceBodyTestFixture.PIN);
            var calls=new AtomicInteger();var nanos=new AtomicLong();
            try (var owner=new RollingDistanceBodyBinding.Owner(f.replica,f.guard,()->{
                calls.incrementAndGet();nanos.addAndGet(17_000);return f.live.get();
            },nanos::get)) {
                var binding=owner.begin(f.publication,f.stamp,f.plan,f.replacement);
                calls.set(0);nanos.set(0);
                var first=binding.step();
                assertEquals(262_144,first.comparisonsThisSlice());
                assertEquals(2,calls.get(),"No repeated full capsule/model validation at each64-cell poll");
                assertEquals(17_000,first.preGuardNanos());assertEquals(17_000,first.postGuardNanos());
                assertEquals(34_000,first.elapsedNanos());assertEquals(0,first.overshootNanos());
                calls.set(0);nanos.set(0);
                var second=binding.step();
                assertEquals(249_856,second.comparisonsThisSlice());assertEquals(2,calls.get());
                assertEquals(512_000,second.completedComparisons());assertTrue(second.complete());
            }
        }
    }

    @Test void postSliceResourceInvalidationRejectsBeforeAnyCompletedProofCredit() {
        try (var f=new DistanceBodyTestFixture()) {
            f.stage(DistanceBodyTestFixture.PIN);var calls=new AtomicInteger();
            try (var owner=new RollingDistanceBodyBinding.Owner(f.replica,f.guard,()->{
                if(calls.incrementAndGet()==2)f.guard.requestInvalidation();return true;
            },()->0L)) {
                // Initialization has its own full checks. Arm only after the candidate is initialized.
                calls.set(-100);var binding=owner.begin(f.publication,f.stamp,f.plan,f.replacement);calls.set(0);
                assertThrows(IllegalStateException.class,binding::step);
                assertEquals(2,calls.get());assertEquals(0,binding.completedComparisons());
                assertFalse(binding.dependenciesCurrent());assertFalse(binding.complete());assertNull(binding.joined());
            }
        }
    }

    @Test void exactMapIsRecheckedAfterThePostSliceResourceCallbackEvenWhenHeadersAndValuesAreIdentical() throws Exception {
        try (var f=new DistanceBodyTestFixture()) {
            f.stage(DistanceBodyTestFixture.PIN);var calls=new AtomicInteger();
            var old=f.replica.cubes().get(DistanceBodyTestFixture.PIN).snapshot();
            var equalDifferentSnapshot=f.snapshot(old.position(),old.leaseGeneration(),old.revision(),-1,-1);
            assertNotSame(old,equalDifferentSnapshot);
            var sameHeaderDifferentCube=new SceneCube(equalDifferentSnapshot,f.palette);
            Map<CubePos,SceneCube> mutable=mutableCubes(f);
            try (var owner=new RollingDistanceBodyBinding.Owner(f.replica,f.guard,()->{
                if(calls.incrementAndGet()==2)mutable.put(old.position(),sameHeaderDifferentCube);return true;
            },()->0L)) {
                calls.set(-100);var binding=owner.begin(f.publication,f.stamp,f.plan,f.replacement);calls.set(0);
                assertThrows(IllegalStateException.class,binding::step);
                assertEquals(0,binding.completedComparisons());assertFalse(binding.dependenciesCurrent());
                assertFalse(binding.complete());assertNull(binding.joined());
            }
        }
    }

    @Test void unchangedCaptureRefusalsNeedFreshAuthorityAndNativeCompletionWhileReusingAll125ValueFacts() {
        // Both are genuine actor refusal statuses: no snapshot, capture, or certificate change occurs.
        for(String refusal:new String[]{"INVALID_STATE","NATIVE_BUDGET"})try(var f=new DistanceBodyTestFixture()) {
            f.stage(DistanceBodyTestFixture.PIN);f.join();f.install();
            var priorReady=f.replica.candidateReady();var prior=f.joined;var history=f.retainPresentation();
            var oldVisual=f.visual;var oldStamp=f.stamp;var oldCapture=f.capture;
            f.replica.beginEdit();assertFalse(prior.isPrepared());assertTrue(history.isPresentable());
            f.replica.acknowledge(refusal,priorReady.replacementCertificate().originalRevision());
            StageReady repeated=new StageReady(priorReady.world(),priorReady.epoch(),priorReady.generation(),
                    priorReady.candidateCenter(),priorReady.versions(),priorReady.replacementCertificate(),priorReady.pinned(),
                    priorReady.activeFocus(),priorReady.replacementSet(),priorReady.replacementCaptures(),
                    priorReady.arenaCertificate(),priorReady.arenaCapture(),priorReady.earthReplacementCapture());
            f.replica.acceptReady(repeated);
            assertSame(oldCapture,repeated.earthReplacementCapture());
            assertSame(priorReady.replacementCertificate(),repeated.replacementCertificate());
            var stamp=freshStamp(oldStamp);
            var candidate=f.owner.begin(f.publication+1,stamp,f.plan,f.replacement);
            assertEquals(125,candidate.reusedSnapshots());var slice=candidate.step();
            assertTrue(slice.complete());assertEquals(0,slice.comparisonsThisSlice());assertEquals(0,slice.completedComparisons());
            assertFalse(prior.isPrepared(),"Restoring equal content must not revive the superseded current capability");
            assertTrue(history.isPresentable());
            assertThrows(IllegalStateException.class,()->candidate.join(oldVisual),"A same-content old visual request cannot satisfy a fresh binding");
            try(var nextVisual=BodyNativeCompletionFixture.publication(f.plan,stamp,f.live::get)) {
                var next=candidate.join(nextVisual);assertTrue(next.isPrepared());assertFalse(next.isPublishedCurrent());
                history.close();f.owner.install(candidate);candidate.requireCommittedSnapshots();
                assertTrue(next.isPublishedCurrent());assertFalse(prior.isPublishedCurrent());assertFalse(history.isPresentable());
            }
        }
    }

    @Test void historicalResourceFailureIsStickyAndDoesNotReviveWhenTheSupplierReturnsTrueAgain() {
        try(var f=new DistanceBodyTestFixture()) {
            f.stage(DistanceBodyTestFixture.PIN);f.join();f.install();var history=f.retainPresentation();
            f.live.set(false);assertFalse(history.isPresentable());
            f.live.set(true);assertFalse(history.isPresentable());assertFalse(f.joined.permitsRetainedPresentation());
        }
    }

    private static NativeVisualStamp freshStamp(NativeVisualStamp p) {
        return new NativeVisualStamp(p.world(),p.storeIdentity(),p.sessionEpoch(),p.resourceGeneration(),p.visualGeneration()+1,
                p.durableRevision(),p.selectedDistanceChunks(),p.effectiveDistanceChunks(),p.serverDistanceLimit(),
                p.sourceIdentity(),p.detailIdentity(),p.materialPolicyIdentity(),p.capsuleIdentity(),p.captureIdentity());
    }
    @SuppressWarnings("unchecked") private static Map<CubePos,SceneCube> mutableCubes(DistanceBodyTestFixture f)throws Exception {
        Field field=RollingGateReplica.class.getDeclaredField("cubes");field.setAccessible(true);return (Map<CubePos,SceneCube>)field.get(f.replica);
    }
}
