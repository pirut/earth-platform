package io.earthplatform.minecraft.lod;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.lod.RenderSubmissionProbe.Stage.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic clocks only: no graphics, sleep, VM settings or wall-time performance assertions. */
class NativePhaseProbeTest {
    static final String DETAIL="cube=3,-5,7/layer=2/firstUse=true";
    static final class Clock implements RenderSubmissionProbe.Clock {
        long wall=100,cpu=10,cost;int reads,utcReads,throwAt=-1;boolean missing,cpuError,utcError,wallError;
        public long nanos(){if(++reads==throwAt){if(wallError)throw new AssertionError("wall");throw new IllegalStateException("wall");}return wall;}
        public long cpuNanos(){wall+=cost;if(cpuError)throw new AssertionError("cpu");return missing?-1:cpu;}
        public long utcMillis(){utcReads++;if(utcError)throw new AssertionError("utc");return 1000;}
        public String cpuStatus(){return "TEST";}
        void advance(long wallDelta,long cpuDelta){wall+=wallDelta;cpu+=cpuDelta;}
    }
    static JsonObject stage(RenderSubmissionProbe p,RenderSubmissionProbe.Stage s){return p.diagnostics().getAsJsonObject("stages").getAsJsonObject(s.name());}
    static JsonObject sample(RenderSubmissionProbe p,RenderSubmissionProbe.Stage s){return stage(p,s).getAsJsonArray("worstElapsedSamples").get(0).getAsJsonObject();}
    static JsonObject batch(RenderSubmissionProbe p,RenderSubmissionProbe.Stage s){return sample(p,s).getAsJsonObject("nativeBatch");}
    static JsonObject row(JsonObject batch,RenderSubmissionProbe.Stage s){return batch.getAsJsonArray("phases").get(s.ordinal()-NATIVE_OWNER_CALL.ordinal()).getAsJsonObject();}
    static long phase(RenderSubmissionProbe p,RenderSubmissionProbe.Stage s){return p.startNativePhase(s,DETAIL,3,-5,7,2,48,4);}
    static long number(JsonObject j,String key){return j.get(key).getAsLong();}
    static long nativeTree(RenderSubmissionProbe p,Clock c,RenderSubmissionProbe.Stage parent,long leafWall,long leafCpu){
        long t=p.start(parent,1,48);long o=phase(p,NATIVE_OWNER_CALL),b=phase(p,NATIVE_VALIDATED_BODY),l=phase(p,NATIVE_DRAW_WITH_SHADER);
        c.advance(leafWall,leafCpu);p.finish(NATIVE_DRAW_WITH_SHADER,l);p.finish(NATIVE_VALIDATED_BODY,b);p.finish(NATIVE_OWNER_CALL,o);p.finish(parent,t);return t;
    }
    static void noOpenSpans(RenderSubmissionProbe p){for(var s:RenderSubmissionProbe.Stage.values())assertFalse(stage(p,s).get("spanOpen").getAsBoolean(),s.name());}

    @Test void exactNestedBracketsAndOnlyDisjointChildrenFeedResiduals(){
        var c=new Clock();c.cost=2;var p=new RenderSubmissionProbe(true,c);
        long parent=p.start(FAR_NATIVE,1,48);c.advance(3,1);
        long owner=phase(p,NATIVE_OWNER_CALL);c.advance(5,2);
        long body=phase(p,NATIVE_VALIDATED_BODY);c.advance(7,3);
        long leaf=phase(p,NATIVE_DRAW_WITH_SHADER);c.advance(11,4);p.finish(NATIVE_DRAW_WITH_SHADER,leaf);
        c.advance(13,5);p.finish(NATIVE_VALIDATED_BODY,body);c.advance(17,6);p.finish(NATIVE_OWNER_CALL,owner);
        c.advance(19,7);p.finish(FAR_NATIVE,parent);
        var s=sample(p,NATIVE_DRAW_WITH_SHADER);assertEquals(121,number(s,"wallBeforeCpuStart"));assertEquals(123,number(s,"wallStart"));
        assertEquals(134,number(s,"wallEnd"));assertEquals(136,number(s,"wallAfterCpuEnd"));
        assertEquals(11,number(s,"innerElapsedNanos"));assertEquals(15,number(s,"outerElapsedNanos"));
        assertEquals(parent,number(s,"nativeParentToken"));assertEquals(owner,number(s,"parentOwnerToken"));
        var b=batch(p,FAR_NATIVE);
        assertEquals(22,number(b,"parentMinusOwnerOuterNanos"));assertEquals(22,number(b,"ownerInnerMinusBodyOuterNanos"));assertEquals(20,number(b,"bodyInnerMinusLeafOuterNanos"));
        assertTrue(b.get("completeWallCoverage").getAsBoolean());assertTrue(b.get("completeCpuCoverage").getAsBoolean());
        var cpu=b.getAsJsonObject("cpuResiduals");assertEquals(8,number(cpu,"parentMinusOwnersNanos"));assertEquals(8,number(cpu,"ownersMinusBodiesNanos"));assertEquals(8,number(cpu,"bodiesMinusLeavesNanos"));
        assertEquals(1,number(row(b,NATIVE_OWNER_CALL),"calls"));assertEquals(1,number(row(b,NATIVE_VALIDATED_BODY),"calls"));
        assertEquals(9,b.getAsJsonArray("phases").size());
    }
    @Test void boundedValueLabelsAndDisplayedAuthorityAreCopiedIntoEveryChild(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);p.setContext(1,2,30,4,true,20,6,7);
        long token=nativeTree(p,c,NEAR_NATIVE,9,1);p.setContext(11,12,13,14,false,15,16,17);
        for(var phase:new RenderSubmissionProbe.Stage[]{NATIVE_OWNER_CALL,NATIVE_VALIDATED_BODY,NATIVE_DRAW_WITH_SHADER}){
            var s=sample(p,phase);assertEquals("NEAR_NATIVE",s.get("nativeDepthStage").getAsString());assertEquals(token,number(s,"nativeParentToken"));
            assertEquals(1,number(s,"frame"));assertEquals(2,number(s,"epoch"));assertEquals(30,number(s,"publication"));assertEquals(4,number(s,"resourceGeneration"));assertTrue(s.get("historical").getAsBoolean());
            assertEquals(20,number(s,"displayedPublication"));assertEquals(6,number(s,"presentedCoverageGeneration"));assertEquals(7,number(s,"presentedRevision"));
            assertEquals(3,number(s,"cubeX"));assertEquals(-5,number(s,"cubeY"));assertEquals(7,number(s,"cubeZ"));assertEquals(2,number(s,"layer"));assertEquals(48,number(s,"declaredVertices"));assertEquals(4,number(s,"attempt"));assertEquals(DETAIL,s.get("detail").getAsString());
        }
    }
    @Test void legacyContextDoesNotInheritPreviousDisplayedStamp(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);p.setContext(1,2,3,4,true,5,6,7);p.setContext(2,3,4,5,false);
        nativeTree(p,c,LEGACY_NATIVE,7,1);var s=sample(p,LEGACY_NATIVE);
        assertEquals(-1,number(s,"displayedPublication"));assertEquals(-1,number(s,"presentedCoverageGeneration"));assertEquals(-1,number(s,"presentedRevision"));
    }
    @Test void nativeTotalsAreCopiedEvenWhenChildrenAreNotGlobalSlowSamples(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);for(int i=0;i<3;i++)nativeTree(p,c,FAR_NATIVE,100+i,1);
        long parent=p.start(NEAR_NATIVE);c.advance(1000,0);
        long owner=phase(p,NATIVE_OWNER_CALL),body=phase(p,NATIVE_VALIDATED_BODY),leaf=phase(p,NATIVE_DRAW_WITH_SHADER);
        c.advance(1,1);p.finish(NATIVE_DRAW_WITH_SHADER,leaf);p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(NEAR_NATIVE,parent);
        assertEquals(1,number(row(batch(p,NEAR_NATIVE),NATIVE_DRAW_WITH_SHADER),"rawInnerElapsedNanos"));
        assertEquals(102,number(sample(p,NATIVE_DRAW_WITH_SHADER),"innerElapsedNanos"));
        nativeTree(p,c,LEGACY_NATIVE,2,1);
        assertEquals(1,number(row(batch(p,NEAR_NATIVE),NATIVE_DRAW_WITH_SHADER),"rawInnerElapsedNanos"));
        assertEquals(parent,number(sample(p,NEAR_NATIVE),"nativeParentToken"));
    }
    @Test void eachBatchResetsFixedTotalsAndCountsEverySequentialOwner(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);long parent=p.start(FAR_NATIVE,2,96);
        for(int i=0;i<2;i++){long o=phase(p,NATIVE_OWNER_CALL),b=phase(p,NATIVE_VALIDATED_BODY),v=phase(p,NATIVE_VALIDITY_CHECK);c.advance(2,1);p.finish(NATIVE_VALIDITY_CHECK,v);p.finish(NATIVE_VALIDATED_BODY,b);p.finish(NATIVE_OWNER_CALL,o);}
        p.finish(FAR_NATIVE,parent);assertEquals(2,number(row(batch(p,FAR_NATIVE),NATIVE_OWNER_CALL),"calls"));assertEquals(4,number(row(batch(p,FAR_NATIVE),NATIVE_VALIDITY_CHECK),"rawOuterElapsedNanos"));
        nativeTree(p,c,NEAR_NATIVE,1,1);assertEquals(1,number(row(batch(p,NEAR_NATIVE),NATIVE_OWNER_CALL),"calls"));assertEquals(0,number(row(batch(p,NEAR_NATIVE),NATIVE_VALIDITY_CHECK),"calls"));
    }
    @Test void rejectsMissingParentBadCountsLongLabelsAndIncorrectHierarchyBeforeOpening(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);
        assertThrows(IllegalStateException.class,()->phase(p,NATIVE_OWNER_CALL));assertThrows(IllegalArgumentException.class,()->p.start(NATIVE_OWNER_CALL));
        long parent=p.start(FAR_NATIVE);
        assertThrows(IllegalStateException.class,()->p.start(NEAR_NATIVE));assertThrows(IllegalStateException.class,()->p.start(LEGACY_NATIVE));
        assertThrows(IllegalArgumentException.class,()->p.startNativePhase(NATIVE_OWNER_CALL,"x".repeat(161)));
        assertThrows(NullPointerException.class,()->p.startNativePhase(NATIVE_OWNER_CALL,null));
        assertThrows(IllegalArgumentException.class,()->p.startNativePhase(NATIVE_OWNER_CALL,DETAIL,1,2,3,-2,4,5));
        assertThrows(IllegalArgumentException.class,()->p.startNativePhase(NATIVE_OWNER_CALL,DETAIL,1,2,3,1,-2,5));
        assertThrows(IllegalArgumentException.class,()->p.startNativePhase(NATIVE_OWNER_CALL,DETAIL,1,2,3,1,4,-2));
        assertThrows(IllegalArgumentException.class,()->p.startNativePhase(FAR_DH,DETAIL));
        assertThrows(IllegalStateException.class,()->phase(p,NATIVE_VALIDATED_BODY));assertThrows(IllegalStateException.class,()->phase(p,NATIVE_DRAW_WITH_SHADER));
        long owner=p.startNativePhase(NATIVE_OWNER_CALL,"x".repeat(160));long body=phase(p,NATIVE_VALIDATED_BODY),leaf=phase(p,NATIVE_DRAW_WITH_SHADER);
        assertThrows(IllegalStateException.class,()->phase(p,NATIVE_STATE_RESTORE));assertThrows(IllegalStateException.class,()->phase(p,NATIVE_OWNER_CALL));
        assertThrows(IllegalStateException.class,()->p.finish(NATIVE_VALIDATED_BODY,body));assertThrows(IllegalStateException.class,()->p.finish(FAR_NATIVE,parent));assertThrows(IllegalStateException.class,()->p.finish(NATIVE_DRAW_WITH_SHADER,leaf+1));
        p.finish(NATIVE_DRAW_WITH_SHADER,leaf);p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(FAR_NATIVE,parent);noOpenSpans(p);
        assertEquals(1,number(row(batch(p,FAR_NATIVE),NATIVE_OWNER_CALL),"starts"));
    }
    @Test void cpuUnavailableOrBackwardsPrecludesResidualsWithoutLosingRawWallSums(){
        for(boolean missing:new boolean[]{true,false}){
            var c=new Clock();var p=new RenderSubmissionProbe(true,c);long parent=p.start(FAR_NATIVE),owner=phase(p,NATIVE_OWNER_CALL),body=phase(p,NATIVE_VALIDATED_BODY),leaf=phase(p,NATIVE_DRAW_WITH_SHADER);
            c.advance(4,missing?0:-1);c.missing=missing;p.finish(NATIVE_DRAW_WITH_SHADER,leaf);c.missing=false;c.cpu=12;
            p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(FAR_NATIVE,parent);
            var b=batch(p,FAR_NATIVE);assertFalse(b.get("completeCpuCoverage").getAsBoolean());assertFalse(b.has("cpuResiduals"));assertTrue(b.get("completeWallCoverage").getAsBoolean());
            assertEquals(1,number(row(b,NATIVE_DRAW_WITH_SHADER),missing?"cpuUnavailableSamples":"cpuBackwardsSamples"));assertEquals(4,number(row(b,NATIVE_DRAW_WITH_SHADER),"rawInnerElapsedNanos"));
        }
    }
    @Test void negativeChildRawSumsAndResidualsRemainSigned(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);long parent=p.start(FAR_NATIVE),owner=phase(p,NATIVE_OWNER_CALL),body=phase(p,NATIVE_VALIDATED_BODY),leaf=phase(p,NATIVE_DRAW_WITH_SHADER);
        c.advance(-5,1);p.finish(NATIVE_DRAW_WITH_SHADER,leaf);c.advance(10,0);p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(FAR_NATIVE,parent);
        var b=batch(p,FAR_NATIVE);assertEquals(-5,number(row(b,NATIVE_DRAW_WITH_SHADER),"rawInnerElapsedNanos"));assertEquals(-5,number(row(b,NATIVE_DRAW_WITH_SHADER),"rawOuterElapsedNanos"));assertEquals(10,number(b,"bodyInnerMinusLeafOuterNanos"));assertFalse(b.get("completeWallCoverage").getAsBoolean());assertFalse(b.has("cpuResiduals"));
        // Different monotonicity anomaly: child has valid brackets longer than its enclosing body.
        c=new Clock();p=new RenderSubmissionProbe(true,c);parent=p.start(FAR_NATIVE);owner=phase(p,NATIVE_OWNER_CALL);body=phase(p,NATIVE_VALIDATED_BODY);leaf=phase(p,NATIVE_DRAW_WITH_SHADER);
        c.advance(10,1);p.finish(NATIVE_DRAW_WITH_SHADER,leaf);c.advance(-5,0);p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(FAR_NATIVE,parent);
        assertEquals(-5,number(batch(p,FAR_NATIVE),"bodyInnerMinusLeafOuterNanos"));
    }
    @Test void nestedSignedNanoTimeWrapRemainsValid(){
        var c=new Clock();c.wall=Long.MAX_VALUE-3;var p=new RenderSubmissionProbe(true,c);nativeTree(p,c,FAR_NATIVE,50,1);
        var b=batch(p,FAR_NATIVE);assertTrue(b.get("completeWallCoverage").getAsBoolean());assertEquals(50,number(row(b,NATIVE_DRAW_WITH_SHADER),"rawInnerElapsedNanos"));assertEquals(0,number(b,"bodyInnerMinusLeafOuterNanos"));
    }
    @Test void phaseTotalsAndResidualArithmeticSaturateExplicitly(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);long parent=p.start(FAR_NATIVE),owner=phase(p,NATIVE_OWNER_CALL),body=phase(p,NATIVE_VALIDATED_BODY);
        for(int i=0;i<3;i++){c.wall=0;long leaf=phase(p,NATIVE_DRAW_WITH_SHADER);c.wall=Long.MAX_VALUE/2;p.finish(NATIVE_DRAW_WITH_SHADER,leaf);}
        p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(FAR_NATIVE,parent);
        var b=batch(p,FAR_NATIVE);assertEquals(Long.MAX_VALUE,number(row(b,NATIVE_DRAW_WITH_SHADER),"rawOuterElapsedNanos"));assertTrue(b.get("countersSaturated").getAsBoolean());assertTrue(p.diagnostics().get("countersSaturated").getAsBoolean());assertFalse(b.get("completeCpuCoverage").getAsBoolean());assertFalse(b.has("cpuResiduals"));
    }
    @Test void runtimeOrErrorWallReadFaultsNeverPoisonLaterSpansOrReplaceDrawFailure(){
        for(boolean error:new boolean[]{false,true})for(int at=1;at<=4;at++){
            var c=new Clock();var p=new RenderSubmissionProbe(true,c);c.wallError=error;c.throwAt=at;var primary=new AssertionError("draw");
            var caught=assertThrows(AssertionError.class,()->{long t=p.start(FAR_NATIVE);try{throw primary;}finally{p.finish(FAR_NATIVE,t);}});assertSame(primary,caught);
            noOpenSpans(p);assertEquals(1,number(p.diagnostics(),"wallReadFailures"));assertEquals(1,number(stage(p,FAR_NATIVE),"measurementFailures"));
            nativeTree(p,c,NEAR_NATIVE,3,1);assertTrue(batch(p,NEAR_NATIVE).get("completeWallCoverage").getAsBoolean());
        }
    }
    @Test void childClockFaultCountsIncompleteCoverageAndStillAllowsRestoreLeaf(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);long parent=p.start(FAR_NATIVE),owner=phase(p,NATIVE_OWNER_CALL),body=phase(p,NATIVE_VALIDATED_BODY);
        c.throwAt=c.reads+1;long leaf=phase(p,NATIVE_DRAW_WITH_SHADER);c.advance(2,1);p.finish(NATIVE_DRAW_WITH_SHADER,leaf);
        long restore=phase(p,NATIVE_STATE_RESTORE);c.advance(3,1);p.finish(NATIVE_STATE_RESTORE,restore);p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(FAR_NATIVE,parent);
        var b=batch(p,FAR_NATIVE);assertEquals(1,number(row(b,NATIVE_DRAW_WITH_SHADER),"measurementFailures"));assertEquals(0,number(row(b,NATIVE_DRAW_WITH_SHADER),"rawInnerElapsedNanos"));assertEquals(3,number(row(b,NATIVE_STATE_RESTORE),"rawInnerElapsedNanos"));assertFalse(b.get("completeWallCoverage").getAsBoolean());noOpenSpans(p);
    }
    @Test void cpuAndUtcProviderErrorsAreUnavailableDiagnosticObservations(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);c.cpuError=true;c.utcError=true;nativeTree(p,c,FAR_NATIVE,7,1);
        assertEquals(8,number(p.diagnostics(),"cpuReadFailures"));assertEquals(4,number(p.diagnostics(),"utcReadFailures"));assertEquals(-1,number(sample(p,FAR_NATIVE),"observedUtcMillis"));assertFalse(batch(p,FAR_NATIVE).get("completeCpuCoverage").getAsBoolean());noOpenSpans(p);
    }
    @Test void tenThousandOrdinaryEqualBatchesAllocateNoRetainedRecordsOrLabels(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);
        for(int i=0;i<10000;i++)nativeTree(p,c,FAR_NATIVE,10,1);
        assertEquals(12,c.utcReads);assertEquals(12,number(p.diagnostics(),"retainedSampleSlots"));assertEquals(3,number(p.diagnostics(),"retainedNativeBatchSnapshotSlots"));assertEquals(27,number(p.diagnostics(),"retainedNativePhaseTotalRows"));
        assertEquals(10000,number(stage(p,NATIVE_DRAW_WITH_SHADER),"calls"));assertEquals(1,number(row(batch(p,FAR_NATIVE),NATIVE_DRAW_WITH_SHADER),"calls"));
    }
    @Test void declaredSnapshotsAndLogicalByteBoundsAreFixedAndSeparateFromRawSlots(){
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);
        for(var parent:new RenderSubmissionProbe.Stage[]{FAR_NATIVE,NEAR_NATIVE,LEGACY_NATIVE})for(int i=0;i<10;i++)nativeTree(p,c,parent,i+1,100);
        var d=p.diagnostics();assertEquals(68,number(d,"maxRetainedSamplesTotal"));assertEquals(36,number(d,"nativePhaseRawSlotCapacity"));assertEquals(12,number(d,"retainedNativeBatchSnapshotSlots"));assertEquals(108,number(d,"retainedNativePhaseTotalRows"));
        assertEquals(99,number(d,"reusableNativeBatchLongs"));assertEquals(792,number(d,"declaredReusableNativeAccumulatorBytes"));assertEquals(9504,number(d,"declaredMaxRetainedPhaseTotalLongBytes"));assertEquals(576,number(d,"declaredMaxRetainedNativeResidualLongBytes"));assertEquals(12480,number(d,"declaredMaxProbeReferencedDetailUtf16Bytes"));
    }
    @Test void disabledNativeApisAreNoOpsBeforeValidationAndAllocateNoState() throws Exception {
        var p=new RenderSubmissionProbe(false,null);assertEquals(0,p.startNativePhase(null,null));assertEquals(0,p.startNativePhase(null,null,0,0,0,-99,-99,-99));p.setContext(1,2,3,4,true,5,6,7);p.finish(null,999);
        for(String name:new String[]{"slots","stats","batch"}){Field f=RenderSubmissionProbe.class.getDeclaredField(name);f.setAccessible(true);assertNull(f.get(p));}
        assertEquals(0,number(p.diagnostics(),"retainedSampleSlots"));
    }
    @Test void nativeApisEnforceOwnerThreadBeforeMutatingContext() throws Exception {
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);long t=p.start(FAR_NATIVE);var failure=new AtomicReference<Throwable>();
        var other=new Thread(()->{try{phase(p,NATIVE_OWNER_CALL);}catch(Throwable e){failure.set(e);}});other.start();other.join();assertInstanceOf(IllegalStateException.class,failure.get());p.finish(FAR_NATIVE,t);noOpenSpans(p);
    }
    @Test void serialExhaustionRejectsBeforeOpeningAnything() throws Exception {
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);Field f=RenderSubmissionProbe.class.getDeclaredField("serial");f.setAccessible(true);f.setLong(p,Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->p.start(FAR_NATIVE));noOpenSpans(p);
        f.setLong(p,Long.MAX_VALUE-1);long t=p.start(FAR_NATIVE);assertThrows(ArithmeticException.class,()->phase(p,NATIVE_OWNER_CALL));p.finish(FAR_NATIVE,t);noOpenSpans(p);assertEquals(0,number(row(batch(p,FAR_NATIVE),NATIVE_OWNER_CALL),"starts"));
    }
}
