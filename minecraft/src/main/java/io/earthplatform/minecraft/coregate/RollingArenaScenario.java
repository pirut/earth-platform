package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure, bounded fixed-eight-cube acceptance HARNESS. Scripted edits are not manual
 * mouse evidence. The owner supplies exact server/client pair and durable values;
 * this runner owns no world, storage, renderer, input, screenshot or reload work.
 * Every action is issued once. Screenshot acknowledgement holds belong to the host.
 */
public final class RollingArenaScenario {
    public static final String INPUT_MODE = "SCRIPTED_HARNESS";
    public static final int CORNER_COUNT = 8, FULL_NEIGHBORHOOD = 64;
    public static final int MAX_TRANSCRIPT = 24, MAX_TRAVEL_STEPS = 16, MAX_ROUTE_HOPS = 4;
    public static final long STEP_TICKS = 2_400, STEP_NANOS = 120_000_000_000L;
    public static final long TRAVEL_TICKS = 12_000, TRAVEL_NANOS = 600_000_000_000L;
    /** Canonical CubePos ordering: X, then Y, then Z. No caller-defined catalogue. */
    public static final List<CubePos> ARENA_CUBES = List.of(
            new CubePos(0,8,0),new CubePos(0,8,1),new CubePos(0,9,0),new CubePos(0,9,1),
            new CubePos(1,8,0),new CubePos(1,8,1),new CubePos(1,9,0),new CubePos(1,9,1));
    public record Target(long x,long y,long z) { }
    public static final Target OUTSIDE_TARGET = new Target(32,144,15);
    public static Target corner(int index) {
        if(index<0||index>=CORNER_COUNT)throw new IllegalArgumentException("Fixed arena corner index required");
        CubePos cube=ARENA_CUBES.get(index);
        return new Target(cube.x()==0?15:16,cube.y()==8?143:144,cube.z()==0?15:16);
    }
    public enum Mode { EXERCISE, AFTER_RELOAD, AFTER_RESTART }
    public enum Status { RUNNING, WAITING_EXTERNAL_RELOAD, WAITING_EXTERNAL_RESTART, PASS, FAIL }
    public enum Outcome { NONE, APPLIED, REFUSED }
    public enum Kind { REMOVE_CORNER, TRAVEL_AWAY, RETURN, FILL_CORNER, OUTSIDE_REFUSAL }
    public record Action(long id,Kind kind,int cornerIndex) {
        public Action {
            Objects.requireNonNull(kind);
            boolean edit=kind==Kind.REMOVE_CORNER||kind==Kind.FILL_CORNER;
            if(id<=0||(edit?(cornerIndex<0||cornerIndex>=CORNER_COUNT):cornerIndex!=-1))
                throw new IllegalArgumentException("Positive action ID and exact fixed corner index required");
        }
        public Target target() {
            if(kind==Kind.OUTSIDE_REFUSAL)return OUTSIDE_TARGET;
            if(kind==Kind.REMOVE_CORNER||kind==Kind.FILL_CORNER)return corner(cornerIndex);
            throw new IllegalStateException("Travel has a center target, not an edit cell");
        }
    }
    /** A completed exact READY pair, never an intended, queued or packet-only hop. */
    public record TravelStep(CubePos center,long publication,long coverageGeneration,String queryIdentity,boolean exactReady) {
        public TravelStep { Objects.requireNonNull(center);bounded(queryIdentity); }
    }
    /**
     * Atomically assembled immutable value. queryIdentity binds the complete stable
     * source/catalogue and must exclude ephemeral epoch/session stamps. Revisions
     * and materials follow ARENA_CUBES order and come from durable compact capture.
     * neighborhoodResidentCount counts the actual native union of all 64 dependency
     * cubes, not the retained immutable capture or eight editable cube count.
     */
    public record Observation(String worldIdentity,String sourceIdentity,String sessionIdentity,String processIdentity,
            long reloadSequence,long epoch,long resourceGeneration,boolean exactReady,boolean pending,
            boolean queryComplete,boolean currentAuthority,long publication,long coverageGeneration,
            long indexGeneration,String indexRoot,String queryIdentity,String captureIdentity,String durableSetIdentity,
            CubePos center,List<Long> revisions,List<Integer> materials,int neighborhoodResidentCount,
            long completedActionId,Outcome outcome,List<TravelStep> travel,String failure) {
        public Observation {
            identity(worldIdentity);identity(sourceIdentity);identity(sessionIdentity);identity(processIdentity);
            bounded(indexRoot);bounded(queryIdentity);bounded(captureIdentity);bounded(durableSetIdentity);bounded(failure);
            if(reloadSequence<0||epoch<0||resourceGeneration<=0||completedActionId<0)
                throw new IllegalArgumentException("Explicit nonnegative process/reload/session evidence required");
            Objects.requireNonNull(center);Objects.requireNonNull(outcome);
            revisions=copyRevisions(revisions);materials=copyMaterials(materials);
            Objects.requireNonNull(travel);
            if(travel.size()>MAX_TRAVEL_STEPS)throw new IllegalArgumentException("Travel receipt bound");
            travel=List.copyOf(travel);
            if(neighborhoodResidentCount<0||neighborhoodResidentCount>FULL_NEIGHBORHOOD)
                throw new IllegalArgumentException("Actual native neighborhood count must be within full64");
        }
    }
    public record RecoveryExpectation(String worldIdentity,String sourceIdentity,String sessionIdentity,String processIdentity,
            long priorEpoch,long priorResourceGeneration,long priorReloadSequence,boolean reloadVerified,
            CubePos originalCenter,List<Long> revisions,List<Integer> materials,long indexGeneration,
            String indexRoot,String queryIdentity,String captureIdentity,String durableSetIdentity) {
        public RecoveryExpectation {
            identity(worldIdentity);identity(sourceIdentity);identity(sessionIdentity);identity(processIdentity);
            identity(indexRoot);identity(queryIdentity);identity(captureIdentity);identity(durableSetIdentity);
            Objects.requireNonNull(originalCenter);revisions=copyRevisions(revisions);materials=copyMaterials(materials);
            if(priorEpoch<0||priorResourceGeneration<=0||priorReloadSequence<0||indexGeneration<0)
                throw new IllegalArgumentException("Exact saved recovery expectation required");
        }
        static RecoveryExpectation from(Observation s,boolean verified) {
            return new RecoveryExpectation(s.worldIdentity(),s.sourceIdentity(),s.sessionIdentity(),s.processIdentity(),
                    s.epoch(),s.resourceGeneration(),s.reloadSequence(),verified,s.center(),s.revisions(),s.materials(),
                    s.indexGeneration(),s.indexRoot(),s.queryIdentity(),s.captureIdentity(),s.durableSetIdentity());
        }
    }
    /** Bounded immutable diagnostic clocks; elapsed subtraction supports nanoTime wrap. */
    public record Deadline(String policy,long stallTickLimit,long stallNanosLimit,long routeTickLimit,long routeNanosLimit,
            boolean travelActive,int acceptedTravelHops,int requiredTravelHops,long stallElapsedTicks,long stallElapsedNanos,
            long routeElapsedTicks,long routeElapsedNanos,List<TravelStep> acceptedTravel) {
        public Deadline {
            bounded(policy);acceptedTravel=List.copyOf(acceptedTravel);
            if(acceptedTravel.size()>MAX_ROUTE_HOPS||acceptedTravelHops!=acceptedTravel.size()
                    ||requiredTravelHops<0||requiredTravelHops>MAX_ROUTE_HOPS)
                throw new IllegalArgumentException("Bounded travel deadline diagnostics required");
        }
    }
    public record Receipt(String label,String inputMode,long tick,long elapsedNanos,Action action,Observation before,
            Observation after,Deadline deadline) {
        public Receipt { bounded(label);bounded(inputMode);Objects.requireNonNull(deadline); }
    }
    public record Report(Mode mode,Status status,String inputMode,int issuedActions,long ticks,String failure,
            RecoveryExpectation expectation,List<Receipt> transcript,Deadline deadline) {
        public Report {
            Objects.requireNonNull(mode);Objects.requireNonNull(status);bounded(inputMode);bounded(failure);
            transcript=List.copyOf(transcript);Objects.requireNonNull(deadline);
            if(transcript.size()>MAX_TRANSCRIPT)throw new IllegalArgumentException("Transcript bound");
        }
    }
    public interface Driver {
        Observation observe();
        /** Queue exactly once, then echo the action ID with its terminal outcome. */
        void issue(Action action);
        /** Host persists the receipt and owns bounded screenshot/ACK holds separately. */
        void checkpoint(Receipt receipt,boolean screenshotRequested);
        /** Fence once on FAIL; never repair, replay or initiate an external boundary. */
        void abort(String reason);
    }
    private static final int SCRIPT_LENGTH = 19;
    private final Thread owner=Thread.currentThread();
    private final Driver driver;
    private final Mode mode;
    private final CubePos original;
    private final ArrayList<Receipt> transcript=new ArrayList<>(MAX_TRANSCRIPT);
    private RecoveryExpectation expectation;
    private Status status=Status.RUNNING;
    private String failure="";
    private Observation scope,baseline,before,last;
    private Action action;
    private Outcome observedActionOutcome=Outcome.NONE;
    private List<TravelStep> acceptedTravel=List.of();
    private long ticks,stepTick,routeTick,startNanos,stepNanos,routeNanos,lastNanos,highestCompletedAction;
    private boolean started,busy;
    private int cursor,issued,requiredTravelHops;

    public RollingArenaScenario(Driver driver,Mode mode,CubePos original,RecoveryExpectation expected) {
        this.driver=Objects.requireNonNull(driver);this.mode=Objects.requireNonNull(mode);this.original=Objects.requireNonNull(original);
        if((mode==Mode.EXERCISE)!=(expected==null))
            throw new IllegalArgumentException("Explicit saved recovery expectation required only for external boundary checks");
        if(expected!=null&&!original.equals(expected.originalCenter()))
            throw new IllegalArgumentException("Recovery must use the saved original center");
        // Validate the entire fixed route before any action can be issued.
        original.offset(-4,0,0);expectation=expected;
    }
    public Status status(){return status;}
    /** A late host screenshot/ACK failure can fence even a waiting or completed run. */
    public void cancel(String reason){checkOwner();fail(reason);}
    public Report report(){checkOwner();return new Report(mode,status,INPUT_MODE,issued,ticks,failure,expectation,transcript,deadline());}
    public void tick(long nowNanos) {
        checkOwner();if(status!=Status.RUNNING)return;
        if(busy){fail("Reentrant scenario tick");return;}
        busy=true;
        try {
            if(!started){started=true;startNanos=stepNanos=lastNanos=nowNanos;}
            if(nowNanos-lastNanos<0)throw new IllegalStateException("Monotonic scenario clock regressed");
            lastNanos=nowNanos;ticks++;
            // Late READY activity cannot revive either an expired stall or route.
            if(travelAction()&&(ticks-routeTick>TRAVEL_TICKS||nowNanos-routeNanos>TRAVEL_NANOS))
                throw new IllegalStateException("Scenario travel absolute route deadline exceeded");
            if(ticks-stepTick>STEP_TICKS||nowNanos-stepNanos>STEP_NANOS)
                throw new IllegalStateException("Scenario stage deadline exceeded");
            Observation s=Objects.requireNonNull(driver.observe(),"Scenario observation");last=s;
            if(status!=Status.RUNNING)return;
            if(scope==null)scope=s;
            if(!s.failure().isEmpty())throw new IllegalStateException("Driver: "+s.failure());
            if(!sameScope(scope,s))throw new IllegalStateException("World/source/session/process/reload/epoch/resources changed outside an explicit external boundary");
            observeActionChannel(s);
            // A just-committed hop remains valid while the next center is pending.
            if(travelAction())observeTravelProgress(s,nowNanos);
            if(!ready(s))return;
            if(baseline==null){
                baseline=s;
                if(mode!=Mode.EXERCISE){
                    verifyRecovery(s);expectation=RecoveryExpectation.from(s,true);
                    receipt("RECONSTRUCTED",null,null,s,nowNanos);
                    if(status==Status.RUNNING)status=mode==Mode.AFTER_RELOAD?Status.WAITING_EXTERNAL_RESTART:Status.PASS;
                    return;
                }
                if(!s.center().equals(original)||s.neighborhoodResidentCount()!=FULL_NEIGHBORHOOD
                        ||s.materials().stream().anyMatch(m->m==0))
                    throw new IllegalStateException("Exercise requires original center, all eight solid corners and native full64 neighborhood; no baseline repair");
                if(s.completedActionId()!=0||s.outcome()!=Outcome.NONE||!s.travel().isEmpty())
                    throw new IllegalStateException("Exercise requires a fresh action receipt channel");
                receipt("INITIAL_READY",null,null,s,nowNanos);next(s,nowNanos);return;
            }
            if(action==null)throw new IllegalStateException("Missing action");
            if(s.completedActionId()<action.id())return;
            verifyAction(s);
            String label=action.kind().name()+((action.kind()==Kind.REMOVE_CORNER||action.kind()==Kind.FILL_CORNER)?"_"+action.cornerIndex():"");
            receipt(label,action,before,s,nowNanos);
            if(status!=Status.RUNNING)return;
            cursor++;
            if(cursor==SCRIPT_LENGTH){expectation=RecoveryExpectation.from(s,false);status=Status.WAITING_EXTERNAL_RELOAD;action=null;return;}
            next(s,nowNanos);
        }catch(RuntimeException|Error error){fail(error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage()));}
        finally{busy=false;}
    }
    private void observeActionChannel(Observation s) {
        if(s.completedActionId()<highestCompletedAction)throw new IllegalStateException("Action result ID regressed");
        highestCompletedAction=s.completedActionId();
        if(action==null)return;
        if(s.completedActionId()>action.id())throw new IllegalStateException("Unexpected later action result");
        if(s.completedActionId()<before.completedActionId())throw new IllegalStateException("Stale action result");
        if(s.completedActionId()==action.id()){
            if(s.outcome()==Outcome.NONE||(observedActionOutcome!=Outcome.NONE&&observedActionOutcome!=s.outcome()))
                throw new IllegalStateException("Completed action outcome missing or changed");
            observedActionOutcome=s.outcome();
        }
    }
    private void next(Observation s,long now) {
        if(status!=Status.RUNNING)return;
        before=s;stepTick=routeTick=ticks;stepNanos=routeNanos=now;
        acceptedTravel=List.of();requiredTravelHops=0;observedActionOutcome=Outcome.NONE;
        Kind kind;int index=-1;
        if(cursor<8){kind=Kind.REMOVE_CORNER;index=cursor;}
        else if(cursor==8)kind=Kind.TRAVEL_AWAY;
        else if(cursor==9)kind=Kind.RETURN;
        else if(cursor<18){kind=Kind.FILL_CORNER;index=cursor-10;}
        else kind=Kind.OUTSIDE_REFUSAL;
        action=new Action(++issued,kind,index);
        if(travelAction()){
            long hops=Math.abs((long)travelEnd().x()-before.center().x());
            if(before.center().y()!=original.y()||before.center().z()!=original.z()||hops!=MAX_ROUTE_HOPS)
                throw new IllegalStateException("Scenario requires exactly four adjacent travel hops");
            requiredTravelHops=(int)hops;
        }
        driver.issue(action);
    }
    private boolean travelAction(){return action!=null&&(action.kind()==Kind.TRAVEL_AWAY||action.kind()==Kind.RETURN);}
    private CubePos travelEnd(){return action.kind()==Kind.TRAVEL_AWAY?original.offset(-4,0,0):original;}
    private void observeTravelProgress(Observation s,long now) {
        if(s.travel().size()<acceptedTravel.size()||!s.travel().subList(0,acceptedTravel.size()).equals(acceptedTravel))
            throw new IllegalStateException("Travel committed prefix changed");
        verifyTravelPrefix(s.travel());
        if(!sameDurable(before,s))throw new IllegalStateException("Travel changed immutable eight revisions/materials or durable catalogue/capture authority");
        long pub=acceptedTravel.isEmpty()?before.publication():acceptedTravel.getLast().publication();
        long coverage=acceptedTravel.isEmpty()?before.coverageGeneration():acceptedTravel.getLast().coverageGeneration();
        if(s.publication()<pub||s.coverageGeneration()<coverage)
            throw new IllegalStateException("Travel committed publication or coverage regressed");
        if(s.travel().size()==acceptedTravel.size())return;
        TravelStep tail=s.travel().getLast();
        if(!tail.center().equals(s.center())||tail.publication()!=s.publication()||tail.coverageGeneration()!=s.coverageGeneration())
            throw new IllegalStateException("Travel new committed pair mismatch");
        if(s.completedActionId()==action.id()?s.outcome()!=Outcome.APPLIED:s.outcome()!=Outcome.NONE)
            throw new IllegalStateException("Travel progress has an inconsistent action outcome");
        if(!s.queryComplete()||!s.currentAuthority())return;
        acceptedTravel=List.copyOf(s.travel());stepTick=ticks;stepNanos=now;
    }
    private void verifyTravelPrefix(List<TravelStep> travel) {
        if(travel.size()>requiredTravelHops)throw new IllegalStateException("Travel exceeded the exact route length");
        CubePos end=travelEnd(),previous=before.center();long pub=before.publication(),coverage=before.coverageGeneration();
        int direction=Integer.compare(end.x(),previous.x());
        for(TravelStep step:travel){
            if(previous.equals(end)||(long)step.center().x()-previous.x()!=direction
                    ||step.center().y()!=original.y()||step.center().z()!=original.z()||!step.exactReady()
                    ||step.publication()<=pub||step.coverageGeneration()<=coverage||!step.queryIdentity().equals(before.queryIdentity()))
                throw new IllegalStateException("Travel skipped an exact adjacent READY paired center toward its target");
            previous=step.center();pub=step.publication();coverage=step.coverageGeneration();
        }
    }
    private void verifyAction(Observation s) {
        Kind kind=action.kind();boolean refusal=kind==Kind.OUTSIDE_REFUSAL;
        if(s.outcome()!=(refusal?Outcome.REFUSED:Outcome.APPLIED))throw new IllegalStateException("Unexpected action outcome for "+kind);
        var revisions=new ArrayList<>(before.revisions());var materials=new ArrayList<>(before.materials());
        boolean edit=kind==Kind.REMOVE_CORNER||kind==Kind.FILL_CORNER;
        if(edit){
            int i=action.cornerIndex();
            if(kind==Kind.REMOVE_CORNER&&materials.get(i)==0||kind==Kind.FILL_CORNER&&materials.get(i)!=0)
                throw new IllegalStateException("Scripted corner precondition changed");
            revisions.set(i,Math.incrementExact(revisions.get(i)));materials.set(i,kind==Kind.REMOVE_CORNER?0:2);
        }
        if(!s.revisions().equals(revisions)||!s.materials().equals(materials))
            throw new IllegalStateException("Exactly one target revision/material must change for "+kind);
        if(s.publication()<before.publication()||s.coverageGeneration()<before.coverageGeneration())
            throw new IllegalStateException("Publication or coverage regressed");
        if(edit){
            if(s.publication()<=before.publication()||s.indexGeneration()!=Math.incrementExact(before.indexGeneration())
                    ||s.indexRoot().equals(before.indexRoot())||s.queryIdentity().equals(before.queryIdentity())
                    ||s.captureIdentity().equals(before.captureIdentity())||s.durableSetIdentity().equals(before.durableSetIdentity()))
                throw new IllegalStateException("Edit lacks exactly one new complete durable index/root/query/capture identity");
        }else if(!sameDurable(before,s))throw new IllegalStateException("Non-edit changed durable authority");
        if(travelAction()){
            verifyTravel(s);
            int expected=kind==Kind.RETURN?FULL_NEIGHBORHOOD:0;
            if(s.neighborhoodResidentCount()!=expected)throw new IllegalStateException("Travel must retire or reacquire the native full64 neighborhood exactly");
        }else{
            if(!s.travel().isEmpty())throw new IllegalStateException("Unrequested travel receipt");
            if(!s.center().equals(before.center())||!s.center().equals(original)||s.neighborhoodResidentCount()!=FULL_NEIGHBORHOOD)
                throw new IllegalStateException("Corner edit/refusal must retain the original complete native neighborhood");
        }
        if(refusal&&(s.publication()!=before.publication()||s.coverageGeneration()!=before.coverageGeneration()))
            throw new IllegalStateException("Outside refusal changed paired publication/coverage authority");
    }
    private void verifyTravel(Observation s) {
        if(s.travel().isEmpty())throw new IllegalStateException("Missing adjacent-center travel proof");
        verifyTravelPrefix(s.travel());TravelStep tail=s.travel().getLast();
        if(!s.travel().equals(acceptedTravel)||s.travel().size()!=requiredTravelHops||!tail.center().equals(travelEnd())
                ||!s.center().equals(travelEnd())||tail.publication()!=s.publication()||tail.coverageGeneration()!=s.coverageGeneration())
            throw new IllegalStateException("Travel final exact READY pair mismatch");
    }
    private void verifyRecovery(Observation s) {
        if(!expectation.worldIdentity().equals(s.worldIdentity())||!expectation.sourceIdentity().equals(s.sourceIdentity())
                ||!expectation.revisions().equals(s.revisions())||!expectation.materials().equals(s.materials())
                ||expectation.indexGeneration()!=s.indexGeneration()||!expectation.indexRoot().equals(s.indexRoot())
                ||!expectation.queryIdentity().equals(s.queryIdentity())||!expectation.captureIdentity().equals(s.captureIdentity())
                ||!expectation.durableSetIdentity().equals(s.durableSetIdentity()))
            throw new IllegalStateException("Reconstruction differs from saved durable eight-cube expectation");
        if(mode==Mode.AFTER_RELOAD){
            if(!expectation.processIdentity().equals(s.processIdentity())||s.reloadSequence()<=expectation.priorReloadSequence()
                    ||expectation.sessionIdentity().equals(s.sessionIdentity())&&s.epoch()==expectation.priorEpoch()
                        &&s.resourceGeneration()==expectation.priorResourceGeneration())
                throw new IllegalStateException("Actual same-process resource reload and changed session/resource stamp not observed");
        }else if(!expectation.reloadVerified()||expectation.processIdentity().equals(s.processIdentity()))
            throw new IllegalStateException("Actual distinct JVM after verified reload not observed");
        if(!s.center().equals(original)||s.neighborhoodResidentCount()!=FULL_NEIGHBORHOOD||!s.travel().isEmpty())
            throw new IllegalStateException("Reconstruction requires original center and complete native full64 neighborhood");
    }
    private static boolean ready(Observation s) {
        return s.exactReady()&&!s.pending()&&s.queryComplete()&&s.currentAuthority()&&s.publication()>0
                &&s.coverageGeneration()>=0&&s.indexGeneration()>=0&&!s.indexRoot().isBlank()
                &&!s.queryIdentity().isBlank()&&!s.captureIdentity().isBlank()&&!s.durableSetIdentity().isBlank();
    }
    private static boolean sameScope(Observation a,Observation b) {
        return a.worldIdentity().equals(b.worldIdentity())&&a.sourceIdentity().equals(b.sourceIdentity())
                &&a.sessionIdentity().equals(b.sessionIdentity())&&a.processIdentity().equals(b.processIdentity())
                &&a.reloadSequence()==b.reloadSequence()&&a.epoch()==b.epoch()&&a.resourceGeneration()==b.resourceGeneration();
    }
    private static boolean sameDurable(Observation a,Observation b) {
        return a.revisions().equals(b.revisions())&&a.materials().equals(b.materials())&&a.indexGeneration()==b.indexGeneration()
                &&a.indexRoot().equals(b.indexRoot())&&a.queryIdentity().equals(b.queryIdentity())
                &&a.captureIdentity().equals(b.captureIdentity())&&a.durableSetIdentity().equals(b.durableSetIdentity());
    }
    private void receipt(String label,Action a,Observation previous,Observation current,long now) {
        if(transcript.size()>=MAX_TRANSCRIPT)throw new IllegalStateException("Scenario transcript bound");
        Receipt value=new Receipt(label,INPUT_MODE,ticks,now-startNanos,a,previous,current,deadline());
        transcript.add(value);driver.checkpoint(value,true);
    }
    private void fail(String reason) {
        if(status==Status.FAIL)return;
        status=Status.FAIL;failure=clip(reason==null?"Scenario failed":reason);
        if(transcript.size()<MAX_TRANSCRIPT)transcript.add(new Receipt(clip("FAIL: "+failure),INPUT_MODE,ticks,
                started?lastNanos-startNanos:0,action,before,last,deadline()));
        try{driver.abort(failure);}catch(RuntimeException|Error abort){failure=clip(failure+"; abort failed: "+abort.getClass().getSimpleName());}
    }
    private Deadline deadline() {
        boolean travel=travelAction();
        return new Deadline(travel?"EXACT_COMMITTED_HOP_STALL_AND_ABSOLUTE_ROUTE_CAP":"FIXED_STAGE",
                STEP_TICKS,STEP_NANOS,TRAVEL_TICKS,TRAVEL_NANOS,travel,acceptedTravel.size(),requiredTravelHops,
                started?ticks-stepTick:0,started?lastNanos-stepNanos:0,travel?ticks-routeTick:0,travel?lastNanos-routeNanos:0,acceptedTravel);
    }
    private static List<Long> copyRevisions(List<Long> value) {
        Objects.requireNonNull(value);
        if(value.size()!=CORNER_COUNT||value.stream().anyMatch(v->v==null||v<0))
            throw new IllegalArgumentException("Exactly eight nonnegative durable revisions required");
        return List.copyOf(value);
    }
    private static List<Integer> copyMaterials(List<Integer> value) {
        Objects.requireNonNull(value);
        if(value.size()!=CORNER_COUNT||value.stream().anyMatch(v->v==null||v<0||v>2))
            throw new IllegalArgumentException("Exactly eight supported target materials required");
        return List.copyOf(value);
    }
    private static void identity(String value){bounded(value);if(value.isBlank())throw new IllegalArgumentException("Explicit exact identity required");}
    private static void bounded(String value){Objects.requireNonNull(value);if(value.length()>512)throw new IllegalArgumentException("Bounded scenario metadata");}
    private static String clip(String value){return value.substring(0,Math.min(512,value.length()));}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Scenario owner thread required");}
}
