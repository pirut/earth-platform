package io.earthplatform.minecraft.coregate;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.coregate.RollingArenaScenario.*;
import static io.earthplatform.minecraft.coregate.RollingArenaScenarioTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure bounded travel, stale-proof, pending authority and recovery-deadline adversaries. */
class RollingArenaScenarioDeadlineTest {
    static final long SECOND=1_000_000_000L;
    static final class Rig {
        final FakeDriver d=new FakeDriver();final RollingArenaScenario r;final Action action;final long origin;
        long now;
        Rig(){this(Kind.TRAVEL_AWAY,0);}
        Rig(Kind kind,long base){
            d.auto=false;r=start(d);now=base;r.tick(now);
            while(d.actions.getLast().kind()!=kind){d.apply(d.actions.getLast());r.tick(++now);running();}
            action=d.actions.getLast();origin=now;d.ready=false;d.pending=true;
        }
        void tick(long elapsed){now=origin+elapsed;r.tick(now);}
        void hop(){
            d.center=d.center.offset(action.kind()==Kind.RETURN?1:-1,0,0);d.advance();
            var steps=new ArrayList<>(d.travel);steps.add(new TravelStep(d.center,d.pub,d.coverage,d.query,true));d.travel=steps;
        }
        void complete(){d.ready=true;d.pending=false;d.completed=action.id();d.outcome=Outcome.APPLIED;d.neighborhood=action.kind()==Kind.RETURN?64:0;}
        void running(){assertEquals(Status.RUNNING,r.status(),r.report().failure());}
        void failOnce(){
            assertEquals(Status.FAIL,r.status(),r.report().failure());assertEquals(1,d.aborts);int issued=d.actions.size();
            String failure=r.report().failure();r.tick(now+1);r.cancel("Must not replace failure");
            assertEquals(failure,r.report().failure());assertEquals(1,d.aborts);assertEquals(issued,d.actions.size());
            assertTrue(r.report().transcript().size()<=MAX_TRANSCRIPT);
        }
    }
    @Test void originalStallAndAbsoluteTravelCapsAreSeparateBoundedValues(){
        assertEquals(2_400,STEP_TICKS);assertEquals(120*SECOND,STEP_NANOS);
        assertEquals(12_000,TRAVEL_TICKS);assertEquals(600*SECOND,TRAVEL_NANOS);
        assertEquals(16,MAX_TRAVEL_STEPS);assertEquals(24,MAX_TRANSCRIPT);
        var x=new Rig();Deadline first=x.r.report().deadline();assertTrue(first.travelActive());
        assertEquals(4,first.requiredTravelHops());assertEquals(0,first.acceptedTravelHops());
        assertEquals(STEP_TICKS,first.stallTickLimit());assertEquals(STEP_NANOS,first.stallNanosLimit());
        assertEquals(TRAVEL_TICKS,first.routeTickLimit());assertEquals(TRAVEL_NANOS,first.routeNanosLimit());
        x.hop();x.tick(100*SECOND);x.running();assertEquals(0,first.acceptedTravelHops());
        assertTrue(first.acceptedTravel().isEmpty());assertEquals(1,x.r.report().deadline().acceptedTravelHops());
        assertThrows(UnsupportedOperationException.class,()->x.r.report().deadline().acceptedTravel().clear());
    }
    @Test void onlyStrictlyAdvancedExactReadyHopsRenewStallEvenWhileNextPairIsPending(){
        var x=new Rig();
        for(int i=1;i<=4;i++){
            x.hop();x.tick(i*100*SECOND);x.running();assertEquals(i,x.r.report().deadline().acceptedTravelHops());
            assertEquals(0,x.r.report().deadline().stallElapsedNanos());assertEquals(i*100*SECOND,x.r.report().deadline().routeElapsedNanos());
            assertEquals(9,x.d.actions.size());assertEquals(9,x.d.checkpoints.size());
        }
        x.complete();x.tick(400*SECOND+1);x.running();assertEquals(Kind.RETURN,x.d.actions.getLast().kind());
        Receipt last=x.d.checkpoints.getLast();assertEquals("TRAVEL_AWAY",last.label());
        assertEquals(4,last.deadline().acceptedTravelHops());assertEquals(last.after().travel(),last.deadline().acceptedTravel());
        assertEquals(0,x.r.report().deadline().acceptedTravelHops());
    }
    @Test void batchedValidPrefixRenewsOnceButDoesNotReplaceCurrentReadiness(){
        var x=new Rig();x.d.apply(x.action);x.tick(100*SECOND);x.running();
        assertEquals(4,x.r.report().deadline().acceptedTravelHops());assertEquals(9,x.d.actions.size());
        x.tick(150*SECOND);assertEquals(50*SECOND,x.r.report().deadline().stallElapsedNanos());
        x.complete();x.tick(150*SECOND+1);x.running();assertEquals(10,x.d.actions.size());
    }
    @Test void unreadyWithoutPendingFlagStillRecognizesCompletedHopProof(){
        var x=new Rig();x.d.pending=false;x.hop();x.tick(100*SECOND);x.running();
        assertEquals(1,x.r.report().deadline().acceptedTravelHops());assertFalse(x.d.ready);
    }
    @Test void returnMustCommitAllFourDirectionalHopsAndReacquireFull64(){
        for(int bad=0;bad<3;bad++){
            var x=new Rig(Kind.RETURN,0);
            for(int i=1;i<=4;i++){x.hop();x.tick(i*100*SECOND);x.running();}
            x.complete();if(bad==1)x.d.neighborhood=63;if(bad==2)x.d.center=x.d.center.offset(1,0,0);
            x.tick(400*SECOND+1);
            if(bad==0){x.running();assertEquals(Kind.FILL_CORNER,x.d.actions.getLast().kind());assertEquals(0,x.d.actions.getLast().cornerIndex());}
            else x.failOnce();
        }
    }
    @Test void travelAwayMustRetireAllNativeDependencyCubes(){
        var x=new Rig();for(int i=1;i<=4;i++){x.hop();x.tick(i);}
        x.complete();x.d.neighborhood=1;x.tick(5);x.failOnce();
    }
    @Test void wallExpiryPrecedesObservationAndCannotBeRescuedByLateReadyHop(){
        var x=new Rig();int[] observations={0};x.d.onObserve=()->observations[0]++;x.hop();x.tick(STEP_NANOS+1);
        x.failOnce();assertEquals(0,observations[0]);assertEquals(0,x.r.report().deadline().acceptedTravelHops());
    }
    @Test void exactStallBoundaryAcceptsProgressButRepeatedReadyPrefixCannotRenew(){
        var x=new Rig();x.hop();x.tick(STEP_NANOS);x.running();
        x.d.ready=true;x.d.pending=false;x.tick(2*STEP_NANOS);x.running();
        assertEquals(STEP_NANOS,x.r.report().deadline().stallElapsedNanos());
        x.tick(2*STEP_NANOS+1);x.failOnce();assertEquals(1,x.r.report().deadline().acceptedTravelHops());
    }
    @Test void packetPublicationActivityAndPlayerMotionCannotRenewRepeatedPrefix(){
        var x=new Rig();x.hop();x.tick(30*SECOND);
        x.d.center=x.d.center.offset(-1,0,0);x.d.pub+=20;x.d.coverage+=20;
        for(int i=1;i<=5;i++){x.tick((30+i*20)*SECOND);x.running();}
        assertEquals(1,x.r.report().deadline().acceptedTravelHops());
        x.tick(30*SECOND+STEP_NANOS+1);x.failOnce();
    }
    @Test void noPrefixPacketActivityCannotRenewOriginalTravelStall(){
        var x=new Rig();for(int i=1;i<=11;i++){x.d.pub++;x.d.coverage++;x.tick(i*10*SECOND);x.running();}
        x.tick(STEP_NANOS+1);x.failOnce();assertEquals(0,x.r.report().deadline().acceptedTravelHops());
    }
    @Test void incompleteOrRevokedQueryCannotRenewNewOtherwiseExactPrefix(){
        for(boolean incomplete:List.of(false,true)){
            var x=new Rig();x.hop();x.tick(30*SECOND);x.hop();
            if(incomplete)x.d.complete=false;else x.d.current=false;
            x.tick(60*SECOND);x.running();assertEquals(1,x.r.report().deadline().acceptedTravelHops());
            x.tick(30*SECOND+STEP_NANOS+1);x.failOnce();
        }
    }
    @Test void previouslyAcceptedHopFieldsCannotBeRewrittenOrRemoved(){
        for(int field=0;field<6;field++){
            var x=new Rig();x.hop();x.tick(SECOND);var old=x.d.travel.getFirst();x.hop();
            var changed=new TravelStep(field==0?old.center().offset(1,0,0):old.center(),old.publication()+(field==1?1:0),
                    old.coverageGeneration()+(field==2?1:0),field==3?"other":old.queryIdentity(),field!=4);
            x.d.travel=field==5?List.of():List.of(changed,x.d.travel.getLast());x.tick(2*SECOND);x.failOnce();
            assertEquals(List.of(old),x.r.report().deadline().acceptedTravel());
        }
    }
    @Test void skipWrongAxisDirectionStalePublicationOrUnreadyHopIsRejected(){
        for(int field=0;field<7;field++){
            var x=new Rig();x.hop();var old=x.d.travel.getFirst();
            var center=switch(field){case 0->old.center().offset(-1,0,0);case 1->old.center().offset(0,1,0);
                case 2->old.center().offset(0,0,1);case 3->old.center().offset(2,0,0);default->old.center();};
            var step=new TravelStep(center,field==4?old.publication()-1:old.publication(),
                    field==5?old.coverageGeneration()-1:old.coverageGeneration(),old.queryIdentity(),field!=6);
            x.d.travel=List.of(step);x.d.center=center;x.d.pub=step.publication();x.d.coverage=step.coverageGeneration();
            x.tick(SECOND);x.failOnce();assertEquals(0,x.r.report().deadline().acceptedTravelHops());
        }
    }
    @Test void eachNewHopMustAdvanceOverItsPredecessorNotOnlyRouteBaseline(){
        for(boolean publication:List.of(false,true)){
            var x=new Rig();x.hop();var a=x.d.travel.getFirst();x.hop();var b=x.d.travel.getLast();
            var bad=new TravelStep(b.center(),publication?a.publication():b.publication(),
                    publication?b.coverageGeneration():a.coverageGeneration(),b.queryIdentity(),true);
            x.d.travel=List.of(a,bad);x.d.pub=bad.publication();x.d.coverage=bad.coverageGeneration();
            x.tick(SECOND);x.failOnce();
        }
    }
    @Test void newPrefixTailMustMatchCurrentCommittedCenterPublicationAndCoverageEvenWhenUnready(){
        for(int field=0;field<3;field++)for(int authority=0;authority<3;authority++){
            var x=new Rig();x.hop();if(field==0)x.d.center=x.d.center.offset(-1,0,0);if(field==1)x.d.pub++;if(field==2)x.d.coverage++;
            if(authority==1)x.d.complete=false;if(authority==2)x.d.current=false;
            x.tick(SECOND);x.failOnce();
        }
    }
    @Test void everyDurableVectorAndCaptureFieldIsImmutableThroughoutPendingTravel(){
        for(int field=0;field<21;field++){
            var x=new Rig();x.hop();x.tick(SECOND);x.hop();
            if(field<8)x.d.revisions.set(field,x.d.revisions.get(field)+1);
            else if(field<16)x.d.materials.set(field-8,2);
            else switch(field){case 16->x.d.index++;case 17->x.d.root="other";case 18->x.d.query="other";
                case 19->x.d.capture="other";case 20->x.d.durable="other";}
            x.tick(2*SECOND);x.failOnce();assertEquals(9,x.d.actions.size());
        }
    }
    @Test void staleFutureAndRefusedTravelResultCannotRenewStall(){
        for(int field=0;field<3;field++){
            var x=new Rig();x.hop();if(field==0)x.d.completed=7;if(field==1)x.d.completed=10;
            if(field==2){x.d.completed=9;x.d.outcome=Outcome.REFUSED;}
            x.tick(SECOND);x.failOnce();assertEquals(0,x.r.report().deadline().acceptedTravelHops());
        }
    }
    @Test void finalFullPrefixCannotSubstituteForCurrentExactReadyPair(){
        for(int field=0;field<4;field++){
            var x=new Rig();for(int i=1;i<=4;i++){x.hop();x.tick(i*SECOND);}x.complete();
            switch(field){case 0->x.d.ready=false;case 1->x.d.pending=true;case 2->x.d.complete=false;case 3->x.d.current=false;}
            x.tick(5*SECOND);x.running();assertEquals(9,x.d.actions.size());
            x.tick(4*SECOND+STEP_NANOS+1);x.failOnce();
        }
    }
    @Test void absoluteWallCapRemainsFixedAfterAllFourHopRenewals(){
        var x=new Rig();for(int i=1;i<=4;i++){x.hop();x.tick(i*STEP_NANOS);x.running();}
        x.tick(TRAVEL_NANOS);x.running();int[] observations={0};x.d.onObserve=()->observations[0]++;x.complete();
        x.tick(TRAVEL_NANOS+1);x.failOnce();assertEquals(0,observations[0]);
        assertTrue(x.r.report().failure().contains("absolute route deadline"));
        assertEquals(4,x.r.report().deadline().acceptedTravelHops());assertEquals(TRAVEL_NANOS+1,x.r.report().deadline().routeElapsedNanos());
    }
    @Test void absoluteTickCapAndTickStallWorkEvenWithStationaryWallClock(){
        var x=new Rig();
        for(int hop=0;hop<4;hop++)for(int tick=1;tick<=STEP_TICKS;tick++){
            if(tick==STEP_TICKS)x.hop();x.r.tick(x.now);x.running();
        }
        for(int tick=0;tick<STEP_TICKS;tick++){x.r.tick(x.now);x.running();}
        x.complete();x.r.tick(x.now);x.failOnce();assertTrue(x.r.report().failure().contains("absolute route deadline"));
        var stalled=new Rig();for(int tick=0;tick<STEP_TICKS;tick++)stalled.r.tick(stalled.now);
        stalled.running();stalled.hop();stalled.r.tick(stalled.now);stalled.failOnce();assertEquals(0,stalled.r.report().deadline().acceptedTravelHops());
    }
    @Test void acceptedProgressAndAbsoluteRouteClockSurviveSignedNanoTimeWrap(){
        var x=new Rig(Kind.TRAVEL_AWAY,Long.MAX_VALUE-40*SECOND);
        for(int i=1;i<=4;i++){x.hop();x.tick(i*STEP_NANOS);x.running();}
        assertTrue(x.now<0);x.tick(TRAVEL_NANOS+1);x.failOnce();
        assertEquals(TRAVEL_NANOS+1,x.r.report().deadline().routeElapsedNanos());
    }
    @Test void signedTickCounterWrapPreservesElapsedStallAndRouteBudgets() throws Exception {
        var x=new Rig();
        for(String name:List.of("ticks","stepTick","routeTick")){
            var field=RollingArenaScenario.class.getDeclaredField(name);field.setAccessible(true);field.setLong(x.r,Long.MAX_VALUE-1);
        }
        x.r.tick(x.now);x.hop();x.r.tick(x.now);x.r.tick(x.now);x.running();
        assertTrue(x.r.report().ticks()<0);assertEquals(1,x.r.report().deadline().stallElapsedTicks());
        assertEquals(3,x.r.report().deadline().routeElapsedTicks());assertEquals(1,x.r.report().deadline().acceptedTravelHops());
    }
    @Test void routeCannotExceedFourOrRetainMoreThanSixteenIncomingReceipts(){
        for(boolean oversized:List.of(false,true)){
            var x=new Rig();if(oversized){x.hop();x.d.travel=java.util.Collections.nCopies(MAX_TRAVEL_STEPS+1,x.d.travel.getFirst());}
            else for(int i=0;i<5;i++)x.hop();
            x.tick(SECOND);x.failOnce();assertEquals(0,x.r.report().deadline().acceptedTravelHops());
        }
    }
    @Test void driverMutationCannotAlterPreviouslyAcceptedProofOrObservation(){
        var x=new Rig();x.hop();var values=new ArrayList<>(x.d.travel);x.d.travel=values;
        Observation snapshot=x.d.observe();x.tick(SECOND);values.clear();
        assertEquals(1,snapshot.travel().size());assertEquals(1,x.r.report().deadline().acceptedTravelHops());
        assertThrows(UnsupportedOperationException.class,()->snapshot.travel().clear());x.tick(2*SECOND);x.failOnce();
    }
    @Test void reentryOrCancellationAtObserveAndFinalCheckpointCannotIssueReturn(){
        for(boolean checkpoint:List.of(false,true))for(boolean reentry:List.of(false,true)){
            var x=new Rig();for(int i=1;i<=4;i++){x.hop();x.tick(i);}x.complete();
            Runnable stop=()->{if(reentry)x.r.tick(x.now);else x.r.cancel("Stopped");};
            if(checkpoint)x.d.onCheckpoint=stop;else x.d.onObserve=stop;
            x.tick(5);x.failOnce();assertEquals(9,x.d.actions.size());
        }
    }
    @Test void subsequentEditRetainsUnrenewableLegacyStageBudget(){
        var x=new Rig(Kind.RETURN,0);x.d.apply(x.action);x.complete();x.tick(1);x.running();
        assertEquals(Kind.FILL_CORNER,x.d.actions.getLast().kind());assertFalse(x.r.report().deadline().travelActive());
        x.d.ready=false;x.d.pending=true;x.tick(1+STEP_NANOS);x.running();x.tick(2+STEP_NANOS);x.failOnce();
        assertEquals("FIXED_STAGE",x.r.report().deadline().policy());
    }
    @Test void everyModeBoundsLoadingByOriginalStageDeadlineWithoutIssuingRecoveryWork(){
        var exerciseDriver=new FakeDriver();var exercise=start(exerciseDriver);finish(exercise);var saved=exercise.report().expectation();
        for(Mode mode:Mode.values()){
            var d=new FakeDriver();d.ready=false;
            var r=new RollingArenaScenario(d,mode,ORIGINAL,mode==Mode.EXERCISE?null:saved);
            r.tick(0);r.tick(STEP_NANOS+1);assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);
            assertFalse(r.report().deadline().travelActive());assertTrue(d.actions.isEmpty());
        }
    }
}
