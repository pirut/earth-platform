package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.coregate.RollingIndexedScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic pure harness tests. They are not actual mouse, travel, screenshot or restart evidence. */
class RollingIndexedScenarioTest {
    static final CubePos ORIGINAL=new CubePos(0,8,0);
    static final class DriverImpl implements Driver {
        String process="process1";long reload;
        String world="world",root="root0",query="query0",set="set0",error="";
        long epoch=1,resource=1,pub=1,coverage=1,index,a,b,completed;
        int ma=1,mb=1,na=27,nb=27,aborts;
        Focus focus=Focus.ORIGINAL;Outcome outcome=Outcome.NONE;CubePos center=ORIGINAL;
        Pose pose=new Pose(8.5,129,8.5,0,31);
        boolean ready=true,pending,complete=true,current=true,auto=true;
        List<TravelStep> travel=List.of();
        final List<Action> actions=new ArrayList<>();final List<Receipt> receipts=new ArrayList<>();
        Runnable onObserve=()->{},onIssue=()->{},onCheckpoint=()->{};
        @Override public Observation observe(){onObserve.run();return new Observation(world,process,reload,epoch,resource,ready,pending,complete,current,pub,coverage,index,root,query,set,focus,a,b,ma,mb,na,nb,center,pose,completed,outcome,travel,error);}
        @Override public void issue(Action action){actions.add(action);onIssue.run();if(auto)apply(action);}
        void apply(Action action){
            travel=List.of();completed=action.id();outcome=Outcome.APPLIED;
            switch(action.kind()){
                case REMOVE_A -> {a++;ma=0;edit();}
                case REMOVE_B -> {b++;mb=0;edit();}
                case FILL_A -> {a++;ma=2;edit();}
                case FILL_B -> {b++;mb=2;edit();}
                case FOCUS_A -> {focus=Focus.ORIGINAL;advance();}
                case FOCUS_B -> {focus=Focus.SECOND;advance();}
                case STAGE_A_AIM -> {pose=new Pose(15.5,129,12.5,0,31);center=ORIGINAL;na=nb=27;advance();}
                case STAGE_B_AIM -> {pose=new Pose(16.5,129,12.5,0,31);center=new CubePos(1,8,0);na=nb=27;advance();}
                case STAGE_OUTSIDE_AIM -> {pose=new Pose(15.5,129,17.5,0,31);center=new CubePos(0,8,1);na=nb=27;advance();}
                case TRAVEL_AWAY,RETURN -> {
                    int target=action.kind()==Kind.RETURN?0:-4;var steps=new ArrayList<TravelStep>();
                    while(center.x()!=target){center=new CubePos(center.x()+Integer.signum(target-center.x()),8,0);advance();steps.add(new TravelStep(center,pub,coverage,query,true));}
                    travel=List.copyOf(steps);na=nb=target==0?27:0;
                }
                case OUTSIDE_EDIT_REFUSAL -> outcome=Outcome.REFUSED;
            }
        }
        void edit(){advance();index++;root="root"+index;query="query"+index;set="set"+index;}
        void advance(){pub++;coverage++;}
        @Override public void checkpoint(Receipt r,boolean screenshot){assertTrue(screenshot);receipts.add(r);onCheckpoint.run();}
        @Override public void abort(String reason){aborts++;}
    }
    static RollingIndexedScenario start(DriverImpl d){return new RollingIndexedScenario(d,Mode.EXERCISE,ORIGINAL,null);}
    static void finish(RollingIndexedScenario r){for(int i=0;i<100&&r.status()==Status.RUNNING;i++)r.tick(i*1_000_000L);}
    @Test void completeBundledSequenceIssuesOnceAndRequiresExplicitReloadThenRestartChecks(){
        var d=new DriverImpl();var r=start(d);finish(r);assertEquals(Status.WAITING_EXTERNAL_RELOAD,r.status(),r.report().failure());
        assertEquals(14,d.actions.size());assertEquals(15,r.report().transcript().size());assertEquals(2,d.a);assertEquals(2,d.b);assertEquals(2,d.ma);assertEquals(2,d.mb);
        assertTrue(r.report().transcript().stream().allMatch(t->t.inputMode().equals("SCRIPTED_HARNESS")));
        var saved=r.report().expectation();d.epoch=2;d.resource=3;d.reload=1;d.na=d.nb=27;d.center=ORIGINAL;d.travel=List.of();
        var reload=new RollingIndexedScenario(d,Mode.AFTER_RELOAD,ORIGINAL,saved);reload.tick(0);assertEquals(Status.WAITING_EXTERNAL_RESTART,reload.status());
        d.epoch=1;d.resource=1;d.process="process2";d.reload=0;var restart=new RollingIndexedScenario(d,Mode.AFTER_RESTART,ORIGINAL,reload.report().expectation());restart.tick(0);assertEquals(Status.PASS,restart.status());
        assertEquals(14,d.actions.size(),"External boundaries are never initiated by the runner");
    }
    @Test void readyButOldActionResultDoesNotDuplicateIssue(){var d=new DriverImpl();d.auto=false;var r=start(d);r.tick(0);for(int i=1;i<20;i++)r.tick(i);assertEquals(1,d.actions.size());assertEquals(Status.RUNNING,r.status());}
    @Test void initialLoadingIsBoundedAndScopeIsLatchedBeforeReadiness(){var d=new DriverImpl();d.ready=false;var r=start(d);r.tick(0);d.epoch++;r.tick(1);assertEquals(Status.FAIL,r.status());assertEquals(0,d.actions.size());assertEquals(1,d.aborts);}
    @Test void pendingDoesNotCountAsReadyAndTickDeadlineFences(){var d=new DriverImpl();d.pending=true;var r=start(d);for(int i=0;i<=STEP_TICKS;i++)r.tick(i);assertEquals(Status.FAIL,r.status());assertTrue(r.report().failure().contains("deadline"));assertEquals(0,d.actions.size());}
    @Test void wallDeadlineWorksAcrossSignedNanoTimeWrap(){var d=new DriverImpl();d.ready=false;var r=start(d);long start=Long.MAX_VALUE-20;r.tick(start);r.tick(start+30);assertEquals(Status.RUNNING,r.status());r.tick(start+STEP_NANOS+1);assertEquals(Status.FAIL,r.status());}
    @Test void clockRegressionFailsWithoutRetry(){var d=new DriverImpl();d.ready=false;var r=start(d);r.tick(10);r.tick(9);assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);r.tick(20);assertEquals(1,d.aborts);}
    @Test void incompleteQueryOrRevokedAuthorityNeverStartsActions(){for(int mode=0;mode<2;mode++){var d=new DriverImpl();if(mode==0)d.complete=false;else d.current=false;var r=start(d);r.tick(0);assertTrue(d.actions.isEmpty());r.tick(STEP_NANOS+1);assertEquals(Status.FAIL,r.status());}}
    @Test void incorrectAimReceiptCannotAuthorizeFollowingEdit(){var d=new DriverImpl();var r=start(d);r.tick(0);d.pose=new Pose(15.5,129,12.5,0,60);r.tick(1);assertEquals(Status.FAIL,r.status());assertEquals(1,d.actions.size());}
    @Test void unexpectedRevisionOnAimFails(){var d=new DriverImpl();var r=start(d);r.tick(0);d.a++;r.tick(1);assertEquals(Status.FAIL,r.status());}
    @Test void staleOrFutureActionReceiptNeverAdvances(){var d=new DriverImpl();d.auto=false;var r=start(d);r.tick(0);d.completed=2;d.outcome=Outcome.APPLIED;r.tick(1);assertEquals(Status.FAIL,r.status());assertEquals(1,d.actions.size());}
    @Test void wrongRefusalOutcomeFailsWithoutSilentRepair(){var d=new DriverImpl();var r=start(d);for(int i=0;i<14;i++)r.tick(i);assertEquals(Kind.OUTSIDE_EDIT_REFUSAL,d.actions.getLast().kind());d.outcome=Outcome.APPLIED;r.tick(14);assertEquals(Status.FAIL,r.status());assertEquals(14,d.actions.size());}
    @Test void skippedTravelCenterIsRejectedEvenIfFinalNeighborhoodsAreGone(){var d=new DriverImpl();var r=start(d);for(int i=0;i<6;i++)r.tick(i);assertEquals(Kind.TRAVEL_AWAY,d.actions.getLast().kind());d.travel=List.of(d.travel.getLast());r.tick(6);assertEquals(Status.FAIL,r.status());assertTrue(r.report().failure().contains("adjacent"));}
    @Test void unreadyIntermediateTravelReceiptIsRejected(){var d=new DriverImpl();var r=start(d);for(int i=0;i<6;i++)r.tick(i);var t=d.travel.getFirst();var list=new ArrayList<>(d.travel);list.set(0,new TravelStep(t.center(),t.publication(),t.coverageGeneration(),t.queryIdentity(),false));d.travel=List.copyOf(list);r.tick(6);assertEquals(Status.FAIL,r.status());}
    @Test void nonEditCannotChangeIndexSnapshot(){var d=new DriverImpl();var r=start(d);r.tick(0);d.index++;r.tick(1);assertEquals(Status.FAIL,r.status());}
    @Test void editMustAdvanceIndexRootAndCompleteQuery(){var d=new DriverImpl();var r=start(d);r.tick(0);r.tick(1);assertEquals(Kind.REMOVE_A,d.actions.getLast().kind());d.root="root0";r.tick(2);assertEquals(Status.FAIL,r.status());}
    @Test void failedObservationIssueAndCheckpointAbortOnceAndRetainFailure(){for(int phase=0;phase<3;phase++){var d=new DriverImpl();Runnable bad=()->{throw new IllegalStateException("injected");};if(phase==0)d.onObserve=bad;else if(phase==1)d.onIssue=bad;else d.onCheckpoint=bad;var r=start(d);r.tick(0);assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);assertTrue(r.report().failure().contains("injected"));assertTrue(r.report().transcript().getLast().label().startsWith("FAIL:"));}}
    @Test void reentrantFinalCheckpointCannotOverwriteFailureWithWaitingState(){var d=new DriverImpl();var r=start(d);for(int i=0;i<14;i++)r.tick(i);d.onCheckpoint=()->r.tick(14);r.tick(14);assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);}
    @Test void cancellationAfterWaitingPreservesFailureAndCannotResumeAutomatically(){var d=new DriverImpl();var r=start(d);finish(r);r.cancel("Screenshot acknowledgement timed out");assertEquals(Status.FAIL,r.status());r.tick(100);assertEquals(1,d.aborts);assertTrue(r.report().failure().contains("Screenshot"));}
    @Test void reconstructionRefusesDifferentDurableContent(){var d=new DriverImpl();var r=start(d);finish(r);var saved=r.report().expectation();d.na=d.nb=27;d.root="another";var replay=new RollingIndexedScenario(d,Mode.AFTER_RESTART,ORIGINAL,saved);replay.tick(0);assertEquals(Status.FAIL,replay.status());}
    @Test void metadataAndReceiptCollectionsAreBoundedImmutableValues(){var d=new DriverImpl();var r=start(d);finish(r);assertThrows(UnsupportedOperationException.class,()->r.report().transcript().clear());assertTrue(r.report().transcript().size()<=MAX_TRANSCRIPT);assertThrows(IllegalArgumentException.class,()->new Pose(Double.NaN,0,0,0,0));assertThrows(IllegalArgumentException.class,()->new RollingIndexedScenario(d,Mode.AFTER_RESTART,ORIGINAL,null));}
    @Test void repeatedCommandCannotPretendToBeReloadOrRestart(){var d=new DriverImpl();var r=start(d);finish(r);var expected=r.report().expectation();d.na=d.nb=27;d.center=ORIGINAL;d.travel=List.of();for(var mode:List.of(Mode.AFTER_RELOAD,Mode.AFTER_RESTART)){var check=new RollingIndexedScenario(d,mode,ORIGINAL,expected);check.tick(0);assertEquals(Status.FAIL,check.status());}}
    @Test void newSessionWithoutActualReloadReceiptFails(){var d=new DriverImpl();var r=start(d);finish(r);d.na=d.nb=27;d.epoch++;var check=new RollingIndexedScenario(d,Mode.AFTER_RELOAD,ORIGINAL,r.report().expectation());check.tick(0);assertEquals(Status.FAIL,check.status());}

    @Test void completedActionWithoutExactAuthorityCannotIssueTheNextAction(){
        for(int mode=0;mode<4;mode++){
            var d=new DriverImpl();var r=start(d);r.tick(0);assertEquals(1,d.actions.size());
            if(mode==0)d.complete=false;else if(mode==1)d.current=false;else if(mode==2)d.pending=true;else d.ready=false;
            r.tick(1);assertEquals(Status.RUNNING,r.status());assertEquals(1,d.actions.size());
            r.tick(STEP_NANOS+1);assertEquals(Status.FAIL,r.status());assertEquals(1,d.actions.size());assertEquals(1,d.aborts);
        }
    }
    @Test void everyDurableRecoveryFieldIsExactAcrossTheRealReloadBoundary(){
        for(int field=0;field<8;field++){
            var d=new DriverImpl();var exercise=start(d);finish(exercise);var saved=exercise.report().expectation();
            d.epoch++;d.resource++;d.reload++;d.na=d.nb=27;d.center=ORIGINAL;d.travel=List.of();
            switch(field){case 0->d.index++;case 1->d.root="foreign-root";case 2->d.query="foreign-query";
                case 3->d.set="foreign-set";case 4->d.a++;case 5->d.b++;case 6->d.ma=0;case 7->d.mb=0;default->throw new AssertionError();}
            var reload=new RollingIndexedScenario(d,Mode.AFTER_RELOAD,ORIGINAL,saved);reload.tick(0);
            assertEquals(Status.FAIL,reload.status(),"field "+field);assertEquals(14,d.actions.size());
            assertTrue(reload.report().failure().contains("Reconstruction differs"));
        }
    }
    @Test void ownerThreadFenceRejectsTickWithoutIssuingWork()throws Exception{
        var d=new DriverImpl();var r=start(d);var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread other=new Thread(()->{try{r.tick(0);}catch(Throwable problem){failure.set(problem);}});other.start();other.join();
        assertInstanceOf(IllegalStateException.class,failure.get());assertTrue(d.actions.isEmpty());
        r.tick(0);assertEquals(1,d.actions.size());assertEquals(Status.RUNNING,r.status());
    }

}
