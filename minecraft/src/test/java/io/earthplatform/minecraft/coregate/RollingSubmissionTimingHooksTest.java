package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.lod.DhLodLayout;
import io.earthplatform.minecraft.lod.RenderSubmissionProbe;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Attribution must observe, rather than weaken, the existing depth transaction. */
class RollingSubmissionTimingHooksTest {
    static Matrix4f camera(){return new Matrix4f().setPerspective(1.2f,1.7f,.1f,256);}
    static final class Driver implements RollingDepthComposition.Driver {
        final List<String> events=new ArrayList<>();
        final RollingDepthComposition.State state=new RollingDepthComposition.State(.2,.8,false,.37,true,13,29,101,73,true,24);
        RuntimeException captureFailure,clearFailure,applyFailure,restoreFailure;
        public void checkOwner(){}
        public RollingDepthComposition.State capture(){events.add("capture");if(captureFailure!=null)throw captureFailure;return state;}
        public void clearDepth(){events.add("clear");if(clearFailure!=null)throw clearFailure;}
        public void apply(RollingDepthComposition.Pass pass){events.add("apply:"+pass.name());if(applyFailure!=null)throw applyFailure;}
        public void restore(RollingDepthComposition.State saved){assertSame(state,saved);events.add("restore");if(restoreFailure!=null)throw restoreFailure;}
    }
    static long calls(RenderSubmissionProbe probe,String stage){return probe.diagnostics().getAsJsonObject("stages").getAsJsonObject(stage).get("calls").getAsLong();}
    static void closedSpans(RenderSubmissionProbe probe){for(var stage:probe.diagnostics().getAsJsonObject("stages").entrySet())assertFalse(stage.getValue().getAsJsonObject().get("spanOpen").getAsBoolean(),stage.getKey());}

    @Test void optionalProbePreservesExactDriverCallSequenceAcrossBothLayouts(){
        for(var layout:DhLodLayout.values()){
            var baseline=new Driver();try(var composition=new RollingDepthComposition(baseline)){assertTrue(composition.draw(camera(),layout,p->{baseline.events.add("render:"+p.name());return true;}));}
            var measured=new Driver();var probe=new RenderSubmissionProbe(true);probe.setContext(3,4,5,6,false);
            try(var composition=new RollingDepthComposition(measured)){composition.setSubmissionProbe(probe);assertTrue(composition.draw(camera(),layout,p->{measured.events.add("render:"+p.name());return true;}));}
            assertEquals(baseline.events,measured.events);assertEquals(1,calls(probe,"DEPTH_SETUP"));assertEquals(1,calls(probe,"DEPTH_RESTORE"));closedSpans(probe);
        }
    }
    @Test void setupCaptureFailureIsMeasuredWithoutInventingRestoreOwner(){
        var d=new Driver();var probe=new RenderSubmissionProbe(true);var failure=new IllegalArgumentException("capture");d.captureFailure=failure;
        try(var c=new RollingDepthComposition(d)){c.setSubmissionProbe(probe);assertSame(failure,assertThrows(IllegalArgumentException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,p->true)));assertFalse(c.diagnostics().get("restorePending").getAsBoolean());}
        assertEquals(List.of("capture"),d.events);assertEquals(1,calls(probe,"DEPTH_SETUP"));assertEquals(0,calls(probe,"DEPTH_RESTORE"));closedSpans(probe);
    }
    @Test void clearApplyAndCallbackFailuresRetainPrimaryCauseAndMeasureRestoration(){
        for(String phase:List.of("clear","apply","callback")){
            var d=new Driver();var probe=new RenderSubmissionProbe(true);var failure=new IllegalStateException(phase);if(phase.equals("clear"))d.clearFailure=failure;if(phase.equals("apply"))d.applyFailure=failure;
            try(var c=new RollingDepthComposition(d)){c.setSubmissionProbe(probe);assertSame(failure,assertThrows(IllegalStateException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,p->{throw failure;})));assertTrue(c.diagnostics().get("failed").getAsBoolean());}
            assertEquals(1,calls(probe,"DEPTH_SETUP"));assertEquals(1,calls(probe,"DEPTH_RESTORE"));closedSpans(probe);
        }
    }
    @Test void restoreFailureRetainsExactTargetButCloseRetriesDoNotBecomeFrameSamples(){
        var d=new Driver();var probe=new RenderSubmissionProbe(true);var failure=new IllegalStateException("restore");d.restoreFailure=failure;var c=new RollingDepthComposition(d);c.setSubmissionProbe(probe);
        assertSame(failure,assertThrows(IllegalStateException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,p->true)));assertTrue(c.diagnostics().get("restorePending").getAsBoolean());
        assertSame(failure,assertThrows(IllegalStateException.class,c::close));assertEquals(1,calls(probe,"DEPTH_RESTORE"));d.restoreFailure=null;c.close();
        assertEquals(3,c.diagnostics().get("restoreAttempts").getAsLong());assertFalse(c.diagnostics().get("restorePending").getAsBoolean());assertEquals(1,calls(probe,"DEPTH_RESTORE"));closedSpans(probe);
    }
    @Test void callbackAndRestoreFailuresRemainOrderedIncludingIdenticalThrowable(){
        for(boolean same:List.of(false,true)){
            var d=new Driver();var probe=new RenderSubmissionProbe(true);var primary=new IllegalStateException("callback");var secondary=same?primary:new IllegalArgumentException("restore");d.restoreFailure=secondary;
            var c=new RollingDepthComposition(d);c.setSubmissionProbe(probe);assertSame(primary,assertThrows(IllegalStateException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,p->{throw primary;})));
            assertEquals(same?0:1,primary.getSuppressed().length);if(!same)assertSame(secondary,primary.getSuppressed()[0]);d.restoreFailure=null;c.close();closedSpans(probe);
        }
    }
    @Test void optionalProbeCannotBeInstalledMidframeAfterSuccessOrAfterFailure(){
        var probe=new RenderSubmissionProbe(true);var d=new Driver();try(var c=new RollingDepthComposition(d)){
            c.setSubmissionProbe(probe);assertTrue(c.draw(camera(),DhLodLayout.LONG_4096,p->{assertThrows(IllegalStateException.class,()->c.setSubmissionProbe(probe));return true;}));assertThrows(IllegalStateException.class,()->c.setSubmissionProbe(probe));}
        var failed=new RollingDepthComposition(new Driver());assertThrows(IllegalStateException.class,()->failed.draw(camera(),DhLodLayout.LONG_4096,p->{throw new IllegalStateException();}));assertThrows(IllegalStateException.class,()->failed.setSubmissionProbe(probe));failed.close();assertThrows(IllegalStateException.class,()->failed.setSubmissionProbe(probe));
    }
    @Test void refusedPassStillCompletesSetupAndRestoreAttribution(){
        var d=new Driver();var probe=new RenderSubmissionProbe(true);try(var c=new RollingDepthComposition(d)){c.setSubmissionProbe(probe);assertFalse(c.draw(camera(),DhLodLayout.LONG_4096,p->false));}
        assertEquals(List.of("capture","clear","apply:far","restore"),d.events);assertEquals(1,calls(probe,"DEPTH_SETUP"));assertEquals(1,calls(probe,"DEPTH_RESTORE"));closedSpans(probe);
    }
    @Test void disabledProbePreservesDefaultPathWithNoRetainedSamples(){
        var probe=new RenderSubmissionProbe(false);var d=new Driver();try(var c=new RollingDepthComposition(d)){c.setSubmissionProbe(probe);assertTrue(c.draw(camera(),DhLodLayout.LONG_4096,p->true));}
        assertEquals(0,probe.diagnostics().get("retainedSampleSlots").getAsInt());assertTrue(probe.diagnostics().getAsJsonObject("stages").isEmpty());
    }
}
