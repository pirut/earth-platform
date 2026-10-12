package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.coregate.RollingIndexedScenario.*;
import static io.earthplatform.minecraft.coregate.RollingIndexedScenarioTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure deadline/receipt tests; successful software-GL timing and GUI acceptance remain separate. */
class RollingScenarioTravelProgressTest {
    private static final long SECOND=1_000_000_000L;
    private static final class TravelRig {
        final DriverImpl d=new DriverImpl();
        final RollingIndexedScenario runner;
        final Action action;
        final long origin;
        TravelRig(Kind kind,long base){
            d.auto=false;runner=start(d);runner.tick(base);long now=base;
            while(d.actions.getLast().kind()!=kind){
                d.apply(d.actions.getLast());runner.tick(++now);
                assertEquals(Status.RUNNING,runner.status(),runner.report().failure());
            }
            action=d.actions.getLast();origin=now;d.travel=List.of();d.outcome=Outcome.NONE;d.ready=false;d.pending=true;
        }
        void hop(){
            d.center=new CubePos(d.center.x()+(action.kind()==Kind.TRAVEL_AWAY?-1:1),ORIGINAL.y(),ORIGINAL.z());
            d.advance();var steps=new ArrayList<>(d.travel);steps.add(new TravelStep(d.center,d.pub,d.coverage,d.query,true));d.travel=List.copyOf(steps);
        }
        void tick(long elapsed){runner.tick(origin+elapsed);}
        void complete(){d.ready=true;d.pending=false;d.completed=action.id();d.outcome=Outcome.APPLIED;d.na=d.nb=action.kind()==Kind.TRAVEL_AWAY?0:27;}
        void running(){assertEquals(Status.RUNNING,runner.status(),runner.report().failure());}
    }

    @Test void pendingCommittedHopsExtendOnlyTheStallClockBeyondTheOriginalWholeStageLimit(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,0);
        for(int hop=1;hop<=5;hop++){
            r.hop();r.tick(hop*100*SECOND);r.running();
            Deadline budget=r.runner.report().deadline();
            assertEquals(hop,budget.acceptedTravelHops());assertEquals(5,budget.requiredTravelHops());
            assertEquals(0,budget.stallElapsedNanos());assertEquals(hop*100*SECOND,budget.routeElapsedNanos());
            assertEquals(6,r.d.actions.size(),"Pending progress must not complete the action");
        }
        r.complete();r.tick(500*SECOND+1);r.running();
        assertEquals(Kind.RETURN,r.d.actions.getLast().kind());
        Receipt receipt=r.runner.report().transcript().getLast();
        assertEquals("TRAVEL_AWAY",receipt.label());assertEquals(5,receipt.after().travel().size());
        assertEquals(5,receipt.deadline().acceptedTravelHops());assertEquals(500*SECOND+1,receipt.deadline().routeElapsedNanos());
        assertEquals(receipt.after().travel(),receipt.deadline().acceptedTravel());
        assertThrows(UnsupportedOperationException.class,()->receipt.deadline().acceptedTravel().clear());
        assertEquals(0,r.runner.report().deadline().acceptedTravelHops(),"New route owns a fresh prefix");
    }

    @Test void sameTickMovementWithoutPendingFlagStillRecognizesCommittedPrefix(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,0);r.d.pending=false;r.hop();r.tick(100*SECOND);r.running();
        assertFalse(r.d.ready);assertEquals(1,r.runner.report().deadline().acceptedTravelHops());
    }

    @Test void delayedNextClientWitnessDoesNotInvalidateOrRenewAnUnchangedPrefix(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,0);r.hop();r.tick(100*SECOND);
        r.d.center=new CubePos(-1,8,0);r.d.pub++; // server commit, old exact client witness retained
        r.tick(150*SECOND);r.running();
        assertEquals(1,r.runner.report().deadline().acceptedTravelHops());
        assertEquals(50*SECOND,r.runner.report().deadline().stallElapsedNanos());
        r.d.coverage++;var steps=new ArrayList<>(r.d.travel);
        steps.add(new TravelStep(r.d.center,r.d.pub,r.d.coverage,r.d.query,true));r.d.travel=List.copyOf(steps);
        r.tick(200*SECOND);r.running();
        assertEquals(2,r.runner.report().deadline().acceptedTravelHops());assertEquals(0,r.runner.report().deadline().stallElapsedNanos());
    }

    @Test void aBatchedValidPrefixCanRenewOnceButCannotCompleteWithoutFinalExactReadiness(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,0);r.d.apply(r.action);r.tick(100*SECOND);r.running();
        assertEquals(5,r.runner.report().deadline().acceptedTravelHops());assertEquals(6,r.d.actions.size());
        r.tick(150*SECOND);assertEquals(50*SECOND,r.runner.report().deadline().stallElapsedNanos());
        r.complete();r.tick(150*SECOND+1);r.running();assertEquals(7,r.d.actions.size());
    }

    @Test void returnHasFourDirectionalHopsAndRequiresBothReacquiredNeighborhoods(){
        for(boolean full:List.of(false,true)){
            var r=new TravelRig(Kind.RETURN,0);
            assertEquals(4,r.runner.report().deadline().requiredTravelHops());
            for(int hop=1;hop<=4;hop++){r.hop();r.tick(hop*100*SECOND);r.running();}
            r.complete();if(!full)r.d.nb=26;r.tick(400*SECOND+1);
            assertEquals(full?Status.RUNNING:Status.FAIL,r.runner.status());
            if(full){assertEquals(Kind.STAGE_B_AIM,r.d.actions.getLast().kind());assertEquals(4,r.runner.report().transcript().getLast().deadline().acceptedTravelHops());}
            else assertTrue(r.runner.report().failure().contains("neighborhoods"));
        }
    }

    @Test void absoluteWallCapExpiresDespiteFiveValidRecentHops(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,0);
        for(int hop=1;hop<=5;hop++){r.hop();r.tick(hop*119*SECOND);r.running();}
        r.tick(TRAVEL_NANOS);r.running();
        assertEquals(5*SECOND,r.runner.report().deadline().stallElapsedNanos());
        r.tick(TRAVEL_NANOS+1);
        assertEquals(Status.FAIL,r.runner.status());assertTrue(r.runner.report().failure().contains("absolute route deadline"));
        Deadline budget=r.runner.report().deadline();
        assertEquals(5,budget.acceptedTravelHops());assertEquals(TRAVEL_NANOS+1,budget.routeElapsedNanos());
        assertEquals(5,r.runner.report().transcript().getLast().after().travel().size());assertEquals(1,r.d.aborts);
    }

    @Test void absoluteTickCapExpiresDespiteFiveValidRecentHops(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,0);
        for(long elapsed=1;elapsed<=TRAVEL_TICKS;elapsed++){
            if(elapsed%(STEP_TICKS-1)==0)r.hop();r.tick(elapsed);r.running();
        }
        assertEquals(5,r.runner.report().deadline().acceptedTravelHops());
        assertEquals(5,r.runner.report().deadline().stallElapsedTicks());
        r.tick(TRAVEL_TICKS+1);assertEquals(Status.FAIL,r.runner.status());
        assertTrue(r.runner.report().failure().contains("absolute route deadline"));
        assertEquals(TRAVEL_TICKS+1,r.runner.report().deadline().routeElapsedTicks());
    }

    @Test void expirationPrecedesObservationAndCannotBeRepairedByAnOtherwiseValidLateHop(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,0);int[] observations={0};r.d.onObserve=()->observations[0]++;
        r.hop();r.tick(STEP_NANOS+1);
        assertEquals(Status.FAIL,r.runner.status());assertEquals(0,observations[0]);
        assertEquals(0,r.runner.report().deadline().acceptedTravelHops());assertEquals(1,r.d.aborts);
    }

    @Test void exactStallBoundaryAcceptsProgressButRepeatedReceiptsNeverRenew(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,0);r.hop();r.tick(STEP_NANOS);r.running();
        r.tick(2*STEP_NANOS);r.running();
        assertEquals(STEP_NANOS,r.runner.report().deadline().stallElapsedNanos());
        r.tick(2*STEP_NANOS+1);assertEquals(Status.FAIL,r.runner.status());
        assertEquals(1,r.runner.report().deadline().acceptedTravelHops());
    }

    @Test void travelWallClocksAndRouteLimitWorkAcrossSignedNanoTimeWrap(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,Long.MAX_VALUE-10);
        for(int hop=1;hop<=5;hop++){r.hop();r.tick(hop*119*SECOND);r.running();}
        r.tick(TRAVEL_NANOS+1);assertEquals(Status.FAIL,r.runner.status());
        assertEquals(TRAVEL_NANOS+1,r.runner.report().deadline().routeElapsedNanos());
        assertTrue(r.runner.report().deadline().stallElapsedNanos()<STEP_NANOS);
    }

    @Test void subsequentNonTravelStageKeepsItsOriginalUnrenewableDeadline(){
        var r=new TravelRig(Kind.RETURN,0);r.d.apply(r.action);r.complete();r.tick(1);r.running();
        assertEquals(Kind.STAGE_B_AIM,r.d.actions.getLast().kind());
        r.d.travel=List.of();r.d.outcome=Outcome.NONE;r.d.ready=false;r.d.pending=true;
        assertFalse(r.runner.report().deadline().travelActive());assertEquals("FIXED_STAGE",r.runner.report().deadline().policy());
        r.tick(1+STEP_NANOS);r.running();r.tick(2+STEP_NANOS);
        assertEquals(Status.FAIL,r.runner.status());assertEquals(STEP_NANOS,r.runner.report().deadline().stallNanosLimit());
        assertEquals(STEP_TICKS,r.runner.report().deadline().stallTickLimit());
    }

    @Test void aFailedPrefixRewritePreservesThePreviouslyAcceptedImmutableProof(){
        var r=new TravelRig(Kind.TRAVEL_AWAY,0);r.hop();r.tick(1);List<TravelStep> accepted=r.d.travel;
        TravelStep step=accepted.getFirst();r.d.travel=List.of(new TravelStep(step.center(),step.publication()+1,step.coverageGeneration(),step.queryIdentity(),true));
        r.tick(2);assertEquals(Status.FAIL,r.runner.status());
        assertEquals(accepted,r.runner.report().deadline().acceptedTravel());
        assertEquals(accepted,r.runner.report().transcript().getLast().deadline().acceptedTravel());
        assertNotEquals(accepted,r.runner.report().transcript().getLast().after().travel());
    }
}
