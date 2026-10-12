package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.coregate.RollingArenaScenario.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure scripted receipts only: no actual Minecraft input, pixels, reload or JVM evidence. */
class RollingArenaScenarioTest {
    static final CubePos ORIGINAL=new CubePos(0,9,0);
    static final class FakeDriver implements Driver {
        String world="world",source="fixed-source",session="session1",process="jvm1";
        String root="root0",query="query0",capture="capture0",durable="durable0",failure="";
        long reload,epoch=1,resources=1,pub=1,coverage=1,index,completed;
        List<Long> revisions=new ArrayList<>(List.of(0L,3L,6L,9L,12L,15L,18L,21L));
        List<Integer> materials=new ArrayList<>(List.of(1,2,1,2,1,2,1,2));
        CubePos center=ORIGINAL;int neighborhood=64,aborts;
        boolean ready=true,pending,complete=true,current=true,auto=true;
        Outcome outcome=Outcome.NONE;List<TravelStep> travel=List.of();
        final List<Action> actions=new ArrayList<>();final List<Receipt> checkpoints=new ArrayList<>();
        Runnable onObserve=()->{},onIssue=()->{},onCheckpoint=()->{},onAbort=()->{};
        @Override public Observation observe(){
            onObserve.run();return new Observation(world,source,session,process,reload,epoch,resources,ready,pending,
                    complete,current,pub,coverage,index,root,query,capture,durable,center,revisions,materials,neighborhood,
                    completed,outcome,travel,failure);
        }
        @Override public void issue(Action action){
            actions.add(action);travel=List.of();outcome=Outcome.NONE;onIssue.run();if(auto)apply(action);
        }
        void apply(Action action){
            completed=action.id();outcome=Outcome.APPLIED;travel=List.of();
            switch(action.kind()){
                case REMOVE_CORNER,FILL_CORNER -> {
                    int i=action.cornerIndex();revisions.set(i,Math.incrementExact(revisions.get(i)));
                    materials.set(i,action.kind()==Kind.REMOVE_CORNER?0:2);advance();index++;
                    root="root"+index;query="query"+index;capture="capture"+index;durable="durable"+index;
                }
                case TRAVEL_AWAY,RETURN -> {
                    CubePos end=action.kind()==Kind.TRAVEL_AWAY?ORIGINAL.offset(-4,0,0):ORIGINAL;
                    var steps=new ArrayList<TravelStep>();
                    while(!center.equals(end)){
                        center=center.offset(Integer.compare(end.x(),center.x()),0,0);advance();
                        steps.add(new TravelStep(center,pub,coverage,query,true));
                    }
                    travel=List.copyOf(steps);neighborhood=action.kind()==Kind.RETURN?64:0;
                }
                case OUTSIDE_REFUSAL -> outcome=Outcome.REFUSED;
            }
        }
        void advance(){pub++;coverage++;}
        @Override public void checkpoint(Receipt receipt,boolean screenshot){assertTrue(screenshot);checkpoints.add(receipt);onCheckpoint.run();}
        @Override public void abort(String reason){aborts++;onAbort.run();}
        void reload(){reload++;epoch++;resources++;session="session"+resources;center=ORIGINAL;travel=List.of();}
    }
    static RollingArenaScenario start(FakeDriver d){return new RollingArenaScenario(d,Mode.EXERCISE,ORIGINAL,null);}
    static void finish(RollingArenaScenario r){for(int i=0;i<100&&r.status()==Status.RUNNING;i++)r.tick(i);}
    static FakeDriver completedExercise(){var d=new FakeDriver();var r=start(d);finish(r);assertEquals(Status.WAITING_EXTERNAL_RELOAD,r.status(),r.report().failure());return d;}

    @Test void fixedEightSortedCubesAndTargetsCrossAllThreeSeams(){
        assertEquals(8,ARENA_CUBES.size());assertEquals(ARENA_CUBES.stream().sorted().toList(),ARENA_CUBES);
        var targets=new ArrayList<Target>();
        for(int i=0;i<8;i++){
            Target p=corner(i);targets.add(p);assertEquals(ARENA_CUBES.get(i),CubePos.fromBlock(p.x(),p.y(),p.z()));
            assertTrue(p.x()==15||p.x()==16);assertTrue(p.y()==143||p.y()==144);assertTrue(p.z()==15||p.z()==16);
            assertEquals(p,new Action(i+1,Kind.REMOVE_CORNER,i).target());
            assertEquals(p,new Action(i+1,Kind.FILL_CORNER,i).target());
        }
        assertEquals(8,targets.stream().distinct().count());assertEquals(new Target(32,144,15),OUTSIDE_TARGET);
        assertEquals(OUTSIDE_TARGET,new Action(19,Kind.OUTSIDE_REFUSAL,-1).target());
        assertThrows(IllegalArgumentException.class,()->corner(-1));assertThrows(IllegalArgumentException.class,()->corner(8));
        assertThrows(IllegalArgumentException.class,()->new Action(1,Kind.REMOVE_CORNER,-1));
        assertThrows(IllegalArgumentException.class,()->new Action(1,Kind.TRAVEL_AWAY,0));
        assertThrows(IllegalArgumentException.class,()->new Action(0,Kind.FILL_CORNER,0));
        assertThrows(UnsupportedOperationException.class,()->ARENA_CUBES.clear());
    }
    @Test void bundledSequenceCapturesArbitraryInitialVectorAndIssuesEveryActionOnce(){
        var d=new FakeDriver();var initial=List.copyOf(d.revisions);var r=start(d);finish(r);
        assertEquals(Status.WAITING_EXTERNAL_RELOAD,r.status(),r.report().failure());
        assertEquals(19,d.actions.size());assertEquals(20,d.checkpoints.size());assertEquals(20,r.report().transcript().size());
        assertEquals(initial,r.report().transcript().getFirst().after().revisions());
        for(int i=0;i<8;i++){
            assertEquals(new Action(i+1,Kind.REMOVE_CORNER,i),d.actions.get(i));
            assertEquals(new Action(i+11,Kind.FILL_CORNER,i),d.actions.get(i+10));
            assertEquals(initial.get(i)+2,d.revisions.get(i));assertEquals(2,d.materials.get(i));
        }
        assertEquals(Kind.TRAVEL_AWAY,d.actions.get(8).kind());assertEquals(Kind.RETURN,d.actions.get(9).kind());
        assertEquals(Kind.OUTSIDE_REFUSAL,d.actions.getLast().kind());assertEquals(16,d.index);
        assertFalse(r.report().expectation().reloadVerified());assertEquals(ORIGINAL,r.report().expectation().originalCenter());
        assertTrue(r.report().transcript().stream().allMatch(v->INPUT_MODE.equals(v.inputMode())));
        assertTrue(r.report().transcript().size()<=MAX_TRANSCRIPT);r.tick(1_000);assertEquals(19,d.actions.size());
    }
    @Test void savedExpectationNeedsRealReloadThenDistinctJvmWithIdenticalDurableValues(){
        var d=new FakeDriver();var r=start(d);finish(r);var saved=r.report().expectation();
        d.reload();var reload=new RollingArenaScenario(d,Mode.AFTER_RELOAD,ORIGINAL,saved);reload.tick(0);
        assertEquals(Status.WAITING_EXTERNAL_RESTART,reload.status(),reload.report().failure());
        var verified=reload.report().expectation();assertTrue(verified.reloadVerified());assertEquals(d.session,verified.sessionIdentity());
        assertEquals(d.reload,verified.priorReloadSequence());assertEquals(saved.revisions(),verified.revisions());
        d.process="jvm2";d.session="restart-session";d.epoch=1;d.resources=1;d.reload=0;
        var restart=new RollingArenaScenario(d,Mode.AFTER_RESTART,ORIGINAL,verified);restart.tick(0);
        assertEquals(Status.PASS,restart.status(),restart.report().failure());assertEquals(19,d.actions.size());
    }
    @Test void noSilentBaselineRepairOfAnAirCornerOrIncompleteNativeNeighborhood(){
        for(int which=0;which<9;which++){
            var d=new FakeDriver();if(which<8)d.materials.set(which,0);else d.neighborhood=63;
            var r=start(d);r.tick(0);assertEquals(Status.FAIL,r.status());assertTrue(d.actions.isEmpty());assertEquals(1,d.aborts);
        }
    }
    @Test void readyOldResultDoesNotReissueAndPendingCompletionCannotAdvance(){
        var d=new FakeDriver();d.auto=false;var r=start(d);r.tick(0);
        for(int i=1;i<20;i++)r.tick(i);assertEquals(1,d.actions.size());assertEquals(Status.RUNNING,r.status());
        d.apply(d.actions.getLast());d.pending=true;r.tick(20);assertEquals(1,d.actions.size());
        r.tick(STEP_NANOS+1);assertEquals(Status.FAIL,r.status());assertEquals(1,d.actions.size());
    }
    @Test void allFourReadinessConditionsAreRequiredAtEveryCompletedAction(){
        for(int field=0;field<4;field++){
            var d=new FakeDriver();var r=start(d);r.tick(0);
            switch(field){case 0->d.ready=false;case 1->d.pending=true;case 2->d.complete=false;case 3->d.current=false;}
            r.tick(1);assertEquals(1,d.actions.size());assertEquals(Status.RUNNING,r.status());
            r.tick(STEP_NANOS+1);assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);
        }
    }
    @Test void scopeIsLatchedBeforeInitialReadinessAndCannotChangeWhilePending(){
        for(int field=0;field<7;field++){
            var d=new FakeDriver();d.ready=false;var r=start(d);r.tick(0);
            switch(field){case 0->d.world="other";case 1->d.source="other";case 2->d.session="other";
                case 3->d.process="other";case 4->d.reload++;case 5->d.epoch++;case 6->d.resources++;}
            r.tick(1);assertEquals(Status.FAIL,r.status(),"scope "+field);assertTrue(d.actions.isEmpty());
        }
    }
    @Test void everyNonTargetRevisionAndMaterialMustStayExact(){
        for(int field=0;field<16;field++){
            var d=new FakeDriver();var r=start(d);r.tick(0);
            if(field<8)d.revisions.set(field,d.revisions.get(field)+1);
            else d.materials.set(field-8,field==8?1:0);
            r.tick(1);assertEquals(Status.FAIL,r.status(),"field "+field);assertEquals(1,d.actions.size());
        }
    }
    @Test void everyEditRequiresFreshExactCompleteIndexAndCaptureAuthority(){
        for(int field=0;field<8;field++){
            var d=new FakeDriver();var r=start(d);r.tick(0);
            switch(field){case 0->d.index=0;case 1->d.index=2;case 2->d.root="root0";case 3->d.query="query0";
                case 4->d.capture="capture0";case 5->d.durable="durable0";case 6->d.pub=1;case 7->d.coverage=0;}
            r.tick(1);assertEquals(Status.FAIL,r.status(),"field "+field);assertEquals(1,d.actions.size());
        }
    }
    @Test void outsideRefusalPreservesAllDurableAndPairedAuthority(){
        for(int field=0;field<11;field++){
            var d=new FakeDriver();var r=start(d);for(int i=0;i<19;i++)r.tick(i);
            assertEquals(Kind.OUTSIDE_REFUSAL,d.actions.getLast().kind());
            switch(field){case 0->d.index++;case 1->d.root="other";case 2->d.query="other";case 3->d.capture="other";
                case 4->d.durable="other";case 5->d.pub++;case 6->d.coverage++;case 7->d.center=d.center.offset(1,0,0);
                case 8->d.revisions.set(7,d.revisions.get(7)+1);case 9->d.materials.set(7,0);case 10->d.outcome=Outcome.APPLIED;}
            r.tick(19);assertEquals(Status.FAIL,r.status(),"field "+field);assertEquals(19,d.actions.size());assertEquals(1,d.aborts);
        }
    }
    @Test void actionResultIdsAndTerminalOutcomesCannotRegressWhileUnready(){
        for(int field=0;field<4;field++){
            var d=new FakeDriver();var r=start(d);r.tick(0);d.ready=false;r.tick(1);
            switch(field){case 0->d.completed=0;case 1->d.completed=2;case 2->d.outcome=Outcome.REFUSED;case 3->d.outcome=Outcome.NONE;}
            r.tick(2);assertEquals(Status.FAIL,r.status());assertEquals(1,d.actions.size());
        }
    }
    @Test void fixedStageTickAndWallDeadlinesAndNanoTimeWrapAreBounded(){
        var d=new FakeDriver();d.pending=true;var r=start(d);
        for(int i=0;i<=STEP_TICKS;i++)r.tick(0);assertEquals(Status.FAIL,r.status());assertTrue(d.actions.isEmpty());
        d=new FakeDriver();d.ready=false;r=start(d);long base=Long.MAX_VALUE-20;r.tick(base);r.tick(base+30);
        assertEquals(Status.RUNNING,r.status());r.tick(base+STEP_NANOS+1);assertEquals(Status.FAIL,r.status());
    }
    @Test void monotonicClockRegressionFencesOnceAndRetainsOriginalFailure(){
        var d=new FakeDriver();d.ready=false;var r=start(d);r.tick(10);r.tick(9);
        assertEquals(Status.FAIL,r.status());String failure=r.report().failure();r.tick(20);r.cancel("later");
        assertEquals(failure,r.report().failure());assertEquals(1,d.aborts);
    }
    @Test void driverExceptionsAtObserveIssueCheckpointAndAbortAreTerminalOnce(){
        for(int where=0;where<3;where++){
            var d=new FakeDriver();Runnable bad=()->{throw new IllegalStateException("injected");};
            if(where==0)d.onObserve=bad;else if(where==1)d.onIssue=bad;else d.onCheckpoint=bad;
            d.onAbort=bad;var r=start(d);r.tick(0);assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);
            assertTrue(r.report().failure().contains("injected"));assertTrue(r.report().failure().contains("abort failed"));
            r.tick(1);assertEquals(1,d.aborts);assertTrue(r.report().transcript().getLast().label().startsWith("FAIL:"));
        }
    }
    @Test void reentrantCallbacksCannotIssueAnotherActionOrOverwriteFailure(){
        for(int where=0;where<4;where++){
            var d=new FakeDriver();d.auto=false;var r=start(d);
            Runnable again=()->r.tick(0);
            if(where==0)d.onObserve=again;else if(where==1)d.onIssue=again;else if(where==2)d.onCheckpoint=again;
            else {d.onObserve=()->r.cancel("cancelled");d.onAbort=()->{r.cancel("again");r.tick(0);};}
            r.tick(0);assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);assertTrue(d.actions.size()<=1);
        }
    }
    @Test void reentrantFinalAndRecoveryCheckpointsCannotClaimWaitingOrPass(){
        var d=new FakeDriver();var r=start(d);for(int i=0;i<19;i++)r.tick(i);
        d.onCheckpoint=()->r.tick(19);r.tick(19);assertEquals(Status.FAIL,r.status());assertEquals(19,d.actions.size());
        var other=new FakeDriver();var exercise=start(other);finish(exercise);other.reload();
        var reload=new RollingArenaScenario(other,Mode.AFTER_RELOAD,ORIGINAL,exercise.report().expectation());
        other.onCheckpoint=()->reload.tick(0);reload.tick(0);assertEquals(Status.FAIL,reload.status());
    }
    @Test void hostMayFenceACompletedWaitingRunForMissingScreenshotAcknowledgement(){
        var d=new FakeDriver();var r=start(d);finish(r);r.cancel("Screenshot acknowledgement timed out");
        assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);assertEquals(21,r.report().transcript().size());
        r.tick(1_000);assertEquals(19,d.actions.size());assertEquals(1,d.aborts);
    }
    @Test void repeatedCommandSessionOnlyReloadCountOnlyAndForeignProcessCannotFakeReload(){
        for(int variant=0;variant<4;variant++){
            var d=new FakeDriver();var r=start(d);finish(r);var expected=r.report().expectation();
            if(variant==1){d.session="session2";d.resources++;}
            if(variant==2)d.reload++;
            if(variant==3){d.reload();d.process="jvm2";}
            var check=new RollingArenaScenario(d,Mode.AFTER_RELOAD,ORIGINAL,expected);check.tick(0);
            assertEquals(Status.FAIL,check.status(),"variant "+variant);assertEquals(19,d.actions.size());
        }
    }
    @Test void everySavedDurableValueMustSurviveRealReloadWithoutRebaselining(){
        for(int field=0;field<23;field++){
            var d=new FakeDriver();var r=start(d);finish(r);var expected=r.report().expectation();d.reload();
            if(field<8)d.revisions.set(field,d.revisions.get(field)+1);
            else if(field<16)d.materials.set(field-8,0);
            else switch(field){case 16->d.world="other";case 17->d.source="other";case 18->d.index++;
                case 19->d.root="other";case 20->d.query="epoch-stamped-query";case 21->d.capture="other";case 22->d.durable="other";}
            var reload=new RollingArenaScenario(d,Mode.AFTER_RELOAD,ORIGINAL,expected);reload.tick(0);
            assertEquals(Status.FAIL,reload.status(),"field "+field);assertTrue(reload.report().failure().contains("Reconstruction differs"));
        }
    }
    @Test void restartNeedsVerifiedReloadAndDistinctJvmNotJustDifferentSession(){
        for(boolean verified:List.of(false,true)){
            var d=new FakeDriver();var exercise=start(d);finish(exercise);RecoveryExpectation expected=exercise.report().expectation();
            if(verified){d.reload();var reload=new RollingArenaScenario(d,Mode.AFTER_RELOAD,ORIGINAL,expected);reload.tick(0);expected=reload.report().expectation();d.session="another";}
            else d.process="jvm2";
            var restart=new RollingArenaScenario(d,Mode.AFTER_RESTART,ORIGINAL,expected);restart.tick(0);
            assertEquals(Status.FAIL,restart.status());assertEquals(19,d.actions.size());
        }
    }
    @Test void recoveryRequiresSavedOriginalCenterAndActualFull64Residency(){
        var d=new FakeDriver();var r=start(d);finish(r);d.reload();var expected=r.report().expectation();
        assertThrows(IllegalArgumentException.class,()->new RollingArenaScenario(d,Mode.AFTER_RELOAD,ORIGINAL.offset(1,0,0),expected));
        d.neighborhood=63;var reload=new RollingArenaScenario(d,Mode.AFTER_RELOAD,ORIGINAL,expected);reload.tick(0);assertEquals(Status.FAIL,reload.status());
        d.neighborhood=64;d.center=ORIGINAL.offset(1,0,0);reload=new RollingArenaScenario(d,Mode.AFTER_RELOAD,ORIGINAL,expected);reload.tick(0);assertEquals(Status.FAIL,reload.status());
    }
    @Test void observationAndSavedExpectationDefensivelyCopyEveryCollection(){
        var d=new FakeDriver();var snapshot=d.observe();d.revisions.set(0,99L);d.materials.set(0,0);
        assertEquals(0L,snapshot.revisions().getFirst());assertEquals(1,snapshot.materials().getFirst());
        assertThrows(UnsupportedOperationException.class,()->snapshot.revisions().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.materials().clear());
        var fresh=new FakeDriver();var r=start(fresh);finish(r);var saved=r.report().expectation();fresh.revisions.set(0,99L);
        assertEquals(2L,saved.revisions().getFirst());assertThrows(UnsupportedOperationException.class,()->saved.materials().clear());
        assertThrows(UnsupportedOperationException.class,()->r.report().transcript().clear());
    }
    @Test void malformedCollectionsMetadataAndMissingSavedReceiptAreRejected(){
        var d=new FakeDriver();d.revisions=List.of(0L);assertThrows(IllegalArgumentException.class,d::observe);
        d=new FakeDriver();d.materials=List.of(1);assertThrows(IllegalArgumentException.class,d::observe);
        d=new FakeDriver();d.revisions.set(2,-1L);assertThrows(IllegalArgumentException.class,d::observe);
        d=new FakeDriver();d.materials.set(2,3);assertThrows(IllegalArgumentException.class,d::observe);
        d=new FakeDriver();d.source="x".repeat(513);assertThrows(IllegalArgumentException.class,d::observe);
        d=new FakeDriver();d.session="";assertThrows(IllegalArgumentException.class,d::observe);
        d=new FakeDriver();d.neighborhood=65;assertThrows(IllegalArgumentException.class,d::observe);
        var finalDriver=new FakeDriver();assertThrows(IllegalArgumentException.class,()->new RollingArenaScenario(finalDriver,Mode.AFTER_RELOAD,ORIGINAL,null));
    }
    @Test void eachOfSixteenEditsRequiresItsOwnExactTargetIncrementAndOutcome(){
        for(int actionIndex=0;actionIndex<18;actionIndex++){
            if(actionIndex==8||actionIndex==9)continue;
            var d=new FakeDriver();var r=start(d);for(int tick=0;tick<=actionIndex;tick++)r.tick(tick);
            var action=d.actions.getLast();assertTrue(action.kind()==Kind.REMOVE_CORNER||action.kind()==Kind.FILL_CORNER);
            d.revisions.set(action.cornerIndex(),d.revisions.get(action.cornerIndex())+1);
            r.tick(actionIndex+1);assertEquals(Status.FAIL,r.status(),"action "+actionIndex);assertEquals(actionIndex+1,d.actions.size());
        }
    }
    @Test void incompleteQueryAndRevokedAuthorityNeverEstablishInitialBaseline(){
        for(boolean complete:List.of(false,true)){
            var d=new FakeDriver();d.complete=complete;d.current=!complete;var r=start(d);r.tick(0);
            assertTrue(d.actions.isEmpty());assertTrue(r.report().transcript().isEmpty());
            r.tick(STEP_NANOS+1);assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);
        }
    }
    @Test void restartAlsoChecksEveryRevisionRatherThanTrustingOnlyReloadVerified(){
        for(int corner=0;corner<8;corner++){
            var d=new FakeDriver();var exercise=start(d);finish(exercise);d.reload();
            var reload=new RollingArenaScenario(d,Mode.AFTER_RELOAD,ORIGINAL,exercise.report().expectation());reload.tick(0);
            d.process="jvm2";d.revisions.set(corner,d.revisions.get(corner)+1);
            var restart=new RollingArenaScenario(d,Mode.AFTER_RESTART,ORIGINAL,reload.report().expectation());restart.tick(0);
            assertEquals(Status.FAIL,restart.status());assertTrue(restart.report().failure().contains("Reconstruction differs"));
        }
    }
    @Test void fatalDriverErrorAndReentrantAbortRemainSingleTerminalFailure(){
        var d=new FakeDriver();var r=start(d);d.onObserve=()->{throw new AssertionError("fatal driver");};
        d.onAbort=()->{r.cancel("reentrant abort");throw new AssertionError("abort");};r.tick(0);
        assertEquals(Status.FAIL,r.status());assertEquals(1,d.aborts);assertTrue(r.report().failure().contains("fatal driver"));
        r.tick(1);assertEquals(1,d.aborts);assertTrue(d.actions.isEmpty());
    }
    @Test void nonTravelActionCannotSmuggleACommittedMovementReceipt(){
        var d=new FakeDriver();var r=start(d);r.tick(0);
        d.travel=List.of(new TravelStep(ORIGINAL,d.pub,d.coverage,d.query,true));r.tick(1);
        assertEquals(Status.FAIL,r.status());assertEquals(1,d.actions.size());assertTrue(r.report().failure().contains("Unrequested travel"));
    }
    @Test void longExceptionMessagesRemainBoundedAndCancellationNeverAddsRepeatedReceipts(){
        var d=new FakeDriver();var r=start(d);d.onObserve=()->{throw new IllegalStateException("x".repeat(2_000));};
        r.tick(0);assertEquals(Status.FAIL,r.status());assertEquals(512,r.report().failure().length());
        assertTrue(r.report().transcript().getLast().label().length()<=512);
        int count=r.report().transcript().size();for(int i=0;i<30;i++)r.cancel("another failure");
        assertEquals(count,r.report().transcript().size());assertEquals(1,d.aborts);
    }
    @Test void ownerThreadFenceRejectsWorkWithoutIssuing() throws Exception {
        var d=new FakeDriver();var r=start(d);var failed=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t=new Thread(()->{try{r.tick(0);}catch(Throwable error){failed.set(error);}});t.start();t.join();
        assertInstanceOf(IllegalStateException.class,failed.get());assertTrue(d.actions.isEmpty());r.tick(0);assertEquals(1,d.actions.size());
    }
}
