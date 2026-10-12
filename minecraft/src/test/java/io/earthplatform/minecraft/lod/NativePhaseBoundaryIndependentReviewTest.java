package io.earthplatform.minecraft.lod;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static io.earthplatform.minecraft.lod.RenderSubmissionProbe.Stage.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent adversarial review of fixed native-phase receipts. No GL or VM monitoring changes. */
class NativePhaseBoundaryIndependentReviewTest {
    private static final class Clock implements RenderSubmissionProbe.Clock {
        long wall=100,cpu=10,readCost;
        int wallReads,cpuReads,utcReads,failAt;
        String failKind="";
        boolean asError;
        void fault(String kind,int read) {
            if(kind.equals(failKind)&&read==failAt) {
                if(asError)throw new AssertionError("clock "+kind+" "+read);
                throw new IllegalStateException("clock "+kind+" "+read);
            }
        }
        public long nanos(){fault("wall",++wallReads);return wall;}
        public long cpuNanos(){fault("cpu",++cpuReads);wall+=readCost;return cpu;}
        public long utcMillis(){fault("utc",++utcReads);return 777;}
        public String cpuStatus(){return "INDEPENDENT_FAKE_CLOCK";}
    }
    private static JsonObject stage(RenderSubmissionProbe p,RenderSubmissionProbe.Stage s) {
        return p.diagnostics().getAsJsonObject("stages").getAsJsonObject(s.name());
    }
    private static JsonObject first(RenderSubmissionProbe p,RenderSubmissionProbe.Stage s) {
        return stage(p,s).getAsJsonArray("worstElapsedSamples").get(0).getAsJsonObject();
    }
    private static JsonObject phase(JsonObject batch,RenderSubmissionProbe.Stage wanted) {
        for(var value:batch.getAsJsonArray("phases")) {
            var row=value.getAsJsonObject();
            if(row.get("phase").getAsString().equals(wanted.name()))return row;
        }
        throw new AssertionError("Missing fixed phase row "+wanted);
    }
    private static void closed(RenderSubmissionProbe p) {
        for(var entry:p.diagnostics().getAsJsonObject("stages").entrySet())
            assertFalse(entry.getValue().getAsJsonObject().get("spanOpen").getAsBoolean(),entry.getKey());
    }
    private static long start(RenderSubmissionProbe p,RenderSubmissionProbe.Stage stage) {
        return p.startNativePhase(stage,"fixed label",-3,17,91,stage==NATIVE_VBO_BIND?2:-1,28,9);
    }
    private static long oneDraw(RenderSubmissionProbe p,Clock c,long parentOnly,long leafWall,long leafCpu) {
        long parent=p.start(NEAR_NATIVE,1,28);c.wall+=parentOnly;
        long owner=start(p,NATIVE_OWNER_CALL),body=start(p,NATIVE_VALIDATED_BODY),leaf=start(p,NATIVE_VBO_BIND);
        c.wall+=leafWall;c.cpu+=leafCpu;
        p.finish(NATIVE_VBO_BIND,leaf);p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);
        p.finish(NEAR_NATIVE,parent);return parent;
    }

    @Test void retainedParentUsesItsOwnChildTotalsEvenWhenNoneOfItsChildrenAreGlobalMaxima() {
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);
        p.setContext(11,2,200,4,true,100,33,8);
        long slow=oneDraw(p,c,10_000,1,1);
        for(int i=1;i<=4;i++) {
            p.setContext(11+i,2,201,4,false,201,34,9);
            oneDraw(p,c,0,100L*i,1);
        }
        var parent=first(p,NEAR_NATIVE);var totals=parent.getAsJsonObject("nativeBatch");
        assertEquals(slow,parent.get("token").getAsLong());
        assertEquals(1,phase(totals,NATIVE_VBO_BIND).get("rawInnerElapsedNanos").getAsLong());
        assertEquals(10_000,totals.get("parentMinusOwnerOuterNanos").getAsLong());
        assertEquals(0,totals.get("ownerInnerMinusBodyOuterNanos").getAsLong());
        assertEquals(0,totals.get("bodyInnerMinusLeafOuterNanos").getAsLong());
        assertEquals(200,parent.get("publication").getAsLong());
        assertEquals(100,parent.get("displayedPublication").getAsLong());
        assertEquals(33,parent.get("presentedCoverageGeneration").getAsLong());
        assertEquals(8,parent.get("presentedRevision").getAsLong());assertTrue(parent.get("historical").getAsBoolean());
        for(var value:stage(p,NATIVE_VBO_BIND).getAsJsonArray("worstElapsedSamples")) {
            var child=value.getAsJsonObject();assertNotEquals(slow,child.get("nativeParentToken").getAsLong());
            assertEquals("NEAR_NATIVE",child.get("nativeDepthStage").getAsString());
            assertEquals(-3,child.get("cubeX").getAsLong());assertEquals(17,child.get("cubeY").getAsLong());
            assertEquals(91,child.get("cubeZ").getAsLong());assertEquals(2,child.get("layer").getAsInt());
            assertEquals(28,child.get("declaredVertices").getAsLong());assertEquals(9,child.get("attempt").getAsLong());
            assertEquals(201,child.get("displayedPublication").getAsLong());assertFalse(child.get("historical").getAsBoolean());
        }
        closed(p);
    }

    @Test void nestedBracketsExcludeEachChildClockOverheadExactlyOnceAndCorrelateOwnerTokens() {
        var c=new Clock();c.readCost=3;var p=new RenderSubmissionProbe(true,c);
        long parentToken=oneDraw(p,c,0,20,5);
        var parent=first(p,NEAR_NATIVE);var owner=first(p,NATIVE_OWNER_CALL);
        var body=first(p,NATIVE_VALIDATED_BODY);var leaf=first(p,NATIVE_VBO_BIND);
        assertEquals(20,leaf.get("innerElapsedNanos").getAsLong());assertEquals(26,leaf.get("outerElapsedNanos").getAsLong());
        assertEquals(38,parent.get("innerElapsedNanos").getAsLong());assertEquals(44,parent.get("outerElapsedNanos").getAsLong());
        for(var sample:new JsonObject[]{owner,body,leaf}) {
            assertEquals(parentToken,sample.get("nativeParentToken").getAsLong());
            assertEquals(owner.get("token").getAsLong(),sample.get("parentOwnerToken").getAsLong());
        }
        var totals=parent.getAsJsonObject("nativeBatch");
        assertEquals(0,totals.get("parentMinusOwnerOuterNanos").getAsLong());
        assertEquals(0,totals.get("ownerInnerMinusBodyOuterNanos").getAsLong());
        assertEquals(0,totals.get("bodyInnerMinusLeafOuterNanos").getAsLong());
        assertTrue(totals.get("completeWallCoverage").getAsBoolean());assertTrue(totals.get("completeCpuCoverage").getAsBoolean());
        assertEquals(16,c.wallReads);assertEquals(8,c.cpuReads);closed(p);
    }

    @Test void signedNanoTimeWrapRemainsValidForParentAndEveryNestedPhase() {
        var c=new Clock();c.wall=Long.MAX_VALUE-5;var p=new RenderSubmissionProbe(true,c);oneDraw(p,c,0,20,5);
        for(var s:new RenderSubmissionProbe.Stage[]{NEAR_NATIVE,NATIVE_OWNER_CALL,NATIVE_VALIDATED_BODY,NATIVE_VBO_BIND}) {
            var sample=first(p,s);assertTrue(sample.get("wallEnd").getAsLong()<0);
            assertEquals(20,sample.get("innerElapsedNanos").getAsLong());assertEquals(0,stage(p,s).get("wallAnomalies").getAsLong());
        }
        assertTrue(first(p,NEAR_NATIVE).getAsJsonObject("nativeBatch").get("completeWallCoverage").getAsBoolean());closed(p);
    }

    @Test void signedCrossBracketRemainderIsPreservedInsteadOfClamped() {
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);long parent=p.start(NEAR_NATIVE);
        c.wall=110;long owner=start(p,NATIVE_OWNER_CALL);c.wall=120;long body=start(p,NATIVE_VALIDATED_BODY);
        c.wall=130;long leaf=start(p,NATIVE_VBO_BIND);c.wall=140;p.finish(NATIVE_VBO_BIND,leaf);
        c.wall=150;p.finish(NATIVE_VALIDATED_BODY,body);c.wall=160;p.finish(NATIVE_OWNER_CALL,owner);
        c.wall=120;p.finish(NEAR_NATIVE,parent);
        var totals=first(p,NEAR_NATIVE).getAsJsonObject("nativeBatch");
        assertEquals(-30,totals.get("parentMinusOwnerOuterNanos").getAsLong());
        assertEquals(20,totals.get("ownerInnerMinusBodyOuterNanos").getAsLong());
        assertEquals(20,totals.get("bodyInnerMinusLeafOuterNanos").getAsLong());closed(p);
    }

    @Test void invalidChildWallAndCpuBoundariesRemainExplicitInItsOwnParentTotals() {
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);long parent=p.start(NEAR_NATIVE);
        long owner=start(p,NATIVE_OWNER_CALL),body=start(p,NATIVE_VALIDATED_BODY);
        c.wall=120;c.cpu=40;long leaf=start(p,NATIVE_VBO_BIND);c.wall=110;c.cpu=30;p.finish(NATIVE_VBO_BIND,leaf);
        c.wall=150;c.cpu=60;p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(NEAR_NATIVE,parent);
        var totals=first(p,NEAR_NATIVE).getAsJsonObject("nativeBatch");var row=phase(totals,NATIVE_VBO_BIND);
        assertEquals(-10,row.get("rawInnerElapsedNanos").getAsLong());assertEquals(-10,row.get("rawOuterElapsedNanos").getAsLong());
        assertEquals(1,row.get("wallAnomalies").getAsLong());assertEquals(1,row.get("cpuBackwardsSamples").getAsLong());
        assertEquals(0,row.get("totalValidThreadCpuNanos").getAsLong());assertFalse(totals.get("completeWallCoverage").getAsBoolean());
        assertFalse(totals.get("completeCpuCoverage").getAsBoolean());assertFalse(totals.has("cpuResiduals"));closed(p);
    }

    @Test void unsupportedCpuNeverBecomesAZeroValuedCompleteCpuRemainder() {
        var c=new Clock();c.cpu=-1;var p=new RenderSubmissionProbe(true,c);oneDraw(p,c,0,20,0);
        var totals=first(p,NEAR_NATIVE).getAsJsonObject("nativeBatch");
        assertTrue(totals.get("completeWallCoverage").getAsBoolean());assertFalse(totals.get("completeCpuCoverage").getAsBoolean());
        assertFalse(totals.has("cpuResiduals"));assertEquals(1,phase(totals,NATIVE_VBO_BIND).get("cpuUnavailableSamples").getAsLong());closed(p);
    }

    @Test void everyClockProviderFaultPreservesHostPrimaryFailureAndClosesAllNestedSpans() {
        for(boolean error:new boolean[]{false,true})for(String kind:new String[]{"wall","cpu","utc"}) {
            int reads=kind.equals("wall")?16:kind.equals("cpu")?8:4;
            for(int at=1;at<=reads;at++) {
                var c=new Clock();c.failKind=kind;c.failAt=at;c.asError=error;var p=new RenderSubmissionProbe(true,c);
                var original=new IllegalArgumentException("native submission failed");
                assertSame(original,assertThrows(IllegalArgumentException.class,()->{
                    long parent=p.start(NEAR_NATIVE);
                    try {
                        long owner=start(p,NATIVE_OWNER_CALL);
                        try {
                            long body=start(p,NATIVE_VALIDATED_BODY);
                            try {
                                long leaf=start(p,NATIVE_VBO_BIND);
                                try {c.wall+=20;c.cpu+=5;throw original;}
                                finally {p.finish(NATIVE_VBO_BIND,leaf);}
                            } finally {p.finish(NATIVE_VALIDATED_BODY,body);}
                        } finally {p.finish(NATIVE_OWNER_CALL,owner);}
                    } finally {p.finish(NEAR_NATIVE,parent);}
                }),kind+" "+at+" Error="+error);
                closed(p);p.setContext(9,8,7,6,false,7,5,4);oneDraw(p,c,0,1,0);closed(p);
                assertEquals(1,p.diagnostics().get(kind+"ReadFailures").getAsLong());
            }
        }
    }

    @Test void invalidHierarchyAndWrongTokensDoNotDamageTheExactActiveStack() {
        var c=new Clock();var p=new RenderSubmissionProbe(true,c);
        assertThrows(IllegalStateException.class,()->start(p,NATIVE_OWNER_CALL));
        long parent=p.start(NEAR_NATIVE);long owner=start(p,NATIVE_OWNER_CALL),body=start(p,NATIVE_VALIDATED_BODY),leaf=start(p,NATIVE_VBO_BIND);
        assertThrows(IllegalStateException.class,()->p.start(FAR_NATIVE));
        assertThrows(IllegalStateException.class,()->start(p,NATIVE_STATE_CAPTURE));
        assertThrows(IllegalStateException.class,()->p.finish(NATIVE_VBO_BIND,leaf+1));
        assertThrows(IllegalStateException.class,()->p.finish(NATIVE_VALIDATED_BODY,body));
        assertThrows(IllegalStateException.class,()->p.finish(NEAR_NATIVE,parent));
        p.finish(NATIVE_VBO_BIND,leaf);p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(NEAR_NATIVE,parent);closed(p);
        long next=p.start(FAR_NATIVE);long rejected=start(p,NATIVE_OWNER_CALL);p.finish(NATIVE_OWNER_CALL,rejected);p.finish(FAR_NATIVE,next);
        var totals=first(p,FAR_NATIVE).getAsJsonObject("nativeBatch");assertEquals(1,phase(totals,NATIVE_OWNER_CALL).get("calls").getAsLong());
        assertEquals(0,phase(totals,NATIVE_VALIDATED_BODY).get("calls").getAsLong());closed(p);
    }

    @Test void saturatingChildSumsInvalidateCompletenessWithoutOverflowingOrAddingHistory() {
        var c=new Clock();c.wall=0;var p=new RenderSubmissionProbe(true,c);long parent=p.start(NEAR_NATIVE);
        long owner=start(p,NATIVE_OWNER_CALL),body=start(p,NATIVE_VALIDATED_BODY);
        for(int i=0;i<3;i++){long leaf=start(p,NATIVE_VBO_BIND);c.wall+=Long.MAX_VALUE/2;p.finish(NATIVE_VBO_BIND,leaf);}
        c.wall=1000;p.finish(NATIVE_VALIDATED_BODY,body);p.finish(NATIVE_OWNER_CALL,owner);p.finish(NEAR_NATIVE,parent);
        var totals=first(p,NEAR_NATIVE).getAsJsonObject("nativeBatch");
        assertEquals(Long.MAX_VALUE,phase(totals,NATIVE_VBO_BIND).get("rawInnerElapsedNanos").getAsLong());
        assertTrue(totals.get("countersSaturated").getAsBoolean());assertFalse(totals.get("completeWallCoverage").getAsBoolean());
        assertFalse(totals.get("completeCpuCoverage").getAsBoolean());assertFalse(totals.has("cpuResiduals"));closed(p);
    }

    @Test void disabledProbeHasNoClockOrThreadRequirementWhileEnabledPhasesRemainOwnerOnly() throws Exception {
        var p=new RenderSubmissionProbe(true,new Clock());var failure=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try{start(p,NATIVE_OWNER_CALL);}catch(Throwable error){failure.set(error);}});thread.start();thread.join();
        assertInstanceOf(IllegalStateException.class,failure.get());
        var disabled=new RenderSubmissionProbe(false,null);
        assertEquals(0,disabled.startNativePhase(null,null,0,0,0,-9,-9,-9));
        disabled.finish(null,-99);disabled.setContext(0,0,0,0,false,0,0,0);
        assertEquals(0,disabled.diagnostics().get("retainedSampleSlots").getAsInt());
    }
}
