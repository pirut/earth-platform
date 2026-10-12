package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit diagnostic HARNESS. Owns no world, GL, storage, player or renderer resources.
 * The driver must use the normal server-authoritative ray/edit and paired-publication paths.
 * One issue per action, no retry, and no automatic reload, restart or recovery.
 */
public final class RollingIndexedScenario {
    public static final int MAX_TRANSCRIPT = 18, MAX_TRAVEL_STEPS = 16;
    public static final long STEP_TICKS = 2_400, STEP_NANOS = 120_000_000_000L;
    // The scripted outbound route is five adjacent hops (original X+1 to X-4);
    // return is four. This is a functional software-GL accommodation, not a speed claim.
    public static final int MAX_ROUTE_HOPS = 5;
    public static final long TRAVEL_TICKS = STEP_TICKS * MAX_ROUTE_HOPS,
            TRAVEL_NANOS = STEP_NANOS * MAX_ROUTE_HOPS;
    public enum Mode { EXERCISE, AFTER_RELOAD, AFTER_RESTART }
    public enum Status { RUNNING, WAITING_EXTERNAL_RELOAD, WAITING_EXTERNAL_RESTART, PASS, FAIL }
    public enum Focus { ORIGINAL, SECOND }
    public enum Outcome { NONE, APPLIED, REFUSED }
    public enum Kind { STAGE_A_AIM, REMOVE_A, FOCUS_B, STAGE_B_AIM, REMOVE_B,
        TRAVEL_AWAY, RETURN, FILL_B, FOCUS_A, FILL_A, STAGE_OUTSIDE_AIM, OUTSIDE_EDIT_REFUSAL }
    public record Action(long id, Kind kind) {
        public Action { if(id<=0)throw new IllegalArgumentException("Positive action ID"); Objects.requireNonNull(kind); }
    }
    /** Each travel receipt represents a fully committed pair, not an intended/queued center. */
    public record TravelStep(CubePos center,long publication,long coverageGeneration,String queryIdentity,
                             boolean exactReady) {
        public TravelStep { Objects.requireNonNull(center); bounded(queryIdentity); }
    }
    public record Pose(double x,double y,double z,float yaw,float pitch) {
        public Pose { if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("Finite scenario pose"); }
    }
    /**
     * Snapshot assembled atomically by the owner. exactReady means current native + far +
     * server collision publication and COMPLETE query authority all match. Cell materials
     * are authoritative compact-capture values (AIR=0, STONE=1, DIORITE=2), not pixel colors.
     */
    public record Observation(String worldIdentity,String processIdentity,long reloadSequence,long epoch,long resourceGeneration,
            boolean exactReady,boolean pending,boolean queryComplete,boolean currentAuthority,
            long publication,long coverageGeneration,long indexGeneration,String indexRoot,
            String queryIdentity,String durableSetIdentity,Focus focus,long revisionA,long revisionB,
            int materialA,int materialB,int neighborhoodA,int neighborhoodB,CubePos center,Pose pose,
            long completedActionId,Outcome outcome,List<TravelStep> travel,String failure) {
        public Observation {
            bounded(worldIdentity);bounded(processIdentity);bounded(indexRoot);bounded(queryIdentity);bounded(durableSetIdentity);bounded(failure);
            if(processIdentity.isEmpty()||reloadSequence<0)throw new IllegalArgumentException("Explicit process/reload evidence required");
            Objects.requireNonNull(focus);Objects.requireNonNull(center);Objects.requireNonNull(pose);Objects.requireNonNull(outcome);
            Objects.requireNonNull(travel);if(travel.size()>MAX_TRAVEL_STEPS)throw new IllegalArgumentException("Travel receipt bound");
            travel=List.copyOf(travel);
            if(neighborhoodA<0||neighborhoodA>27||neighborhoodB<0||neighborhoodB>27)throw new IllegalArgumentException("Neighborhood count");
        }
    }
    public record RecoveryExpectation(String worldIdentity,String processIdentity,long priorEpoch,long priorResourceGeneration,
            long priorReloadSequence,boolean reloadVerified,long revisionA,long revisionB,int materialA,
            int materialB,long indexGeneration,String indexRoot,String queryIdentity,String durableSetIdentity) {
        public RecoveryExpectation {
            bounded(worldIdentity);bounded(processIdentity);bounded(indexRoot);bounded(queryIdentity);bounded(durableSetIdentity);
            if(worldIdentity.isEmpty()||processIdentity.isEmpty()||priorEpoch<0||priorResourceGeneration<=0||priorReloadSequence<0||indexRoot.isEmpty()||queryIdentity.isEmpty()||durableSetIdentity.isEmpty()
                    ||revisionA<0||revisionB<0||indexGeneration<0)throw new IllegalArgumentException("Exact recovery expectation required");
        }
        static RecoveryExpectation from(Observation s,boolean reloadVerified) {
            return new RecoveryExpectation(s.worldIdentity(),s.processIdentity(),s.epoch(),s.resourceGeneration(),s.reloadSequence(),reloadVerified,s.revisionA(),s.revisionB(),s.materialA(),s.materialB(),
                    s.indexGeneration(),s.indexRoot(),s.queryIdentity(),s.durableSetIdentity());
        }
    }
    /** Bounded value diagnostics; elapsed clocks use subtraction to preserve signed wrap. */
    public record Deadline(String policy,long stallTickLimit,long stallNanosLimit,long routeTickLimit,long routeNanosLimit,
            boolean travelActive,int acceptedTravelHops,int requiredTravelHops,long stallElapsedTicks,long stallElapsedNanos,
            long routeElapsedTicks,long routeElapsedNanos,List<TravelStep> acceptedTravel) {
        public Deadline {
            acceptedTravel=List.copyOf(acceptedTravel);
            if(acceptedTravel.size()>MAX_ROUTE_HOPS||acceptedTravelHops!=acceptedTravel.size()
                    ||requiredTravelHops<0||requiredTravelHops>MAX_ROUTE_HOPS)
                throw new IllegalArgumentException("Bounded travel deadline diagnostics required");
        }
    }
    public record Receipt(String label,String inputMode,long tick,long elapsedNanos,Action action,Observation before,Observation after,
                          Deadline deadline) { }
    public record Report(Mode mode,Status status,String inputMode,int issuedActions,long ticks,
                         String failure,RecoveryExpectation expectation,List<Receipt> transcript,Deadline deadline) {
        public Report { transcript=List.copyOf(transcript); }
    }
    public interface Driver {
        Observation observe();
        /** Queue once. A result must echo action.id, even a refused ray edit. */
        void issue(Action action);
        /** Persist a value receipt; screenshot request is serviced separately by the host. */
        void checkpoint(Receipt receipt,boolean screenshotRequested);
        /** Fence the diagnostic session once. Must not retry or repair the scenario. */
        void abort(String reason);
    }
    private static final List<Kind> SCRIPT=List.of(Kind.STAGE_A_AIM,Kind.REMOVE_A,Kind.FOCUS_B,
            Kind.STAGE_B_AIM,Kind.REMOVE_B,Kind.TRAVEL_AWAY,Kind.RETURN,Kind.STAGE_B_AIM,
            Kind.FILL_B,Kind.FOCUS_A,Kind.STAGE_A_AIM,Kind.FILL_A,Kind.STAGE_OUTSIDE_AIM,
            Kind.OUTSIDE_EDIT_REFUSAL);
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
    private List<TravelStep> acceptedTravel=List.of();
    private long ticks,stepTick,routeTick,startNanos,stepNanos,routeNanos,lastNanos;
    private boolean started,busy;
    private int cursor,issued,requiredTravelHops;
    public RollingIndexedScenario(Driver driver,Mode mode,CubePos original,RecoveryExpectation expected) {
        this.driver=Objects.requireNonNull(driver);this.mode=Objects.requireNonNull(mode);this.original=Objects.requireNonNull(original);
        if((mode==Mode.EXERCISE)!=(expected==null))throw new IllegalArgumentException("Explicit recovery receipt required only for external-boundary checks");
        expectation=expected;
    }
    public Status status(){return status;}
    /** Explicit external checkpoint failure can fence even a completed/waiting run. */
    public void cancel(String reason){checkOwner();fail(reason);}
    public Report report(){checkOwner();return new Report(mode,status,"SCRIPTED_HARNESS",issued,ticks,failure,expectation,transcript,deadline());}
    /** Caller supplies monotonic owner time; signed nanoTime wrap is supported for bounded intervals. */
    public void tick(long nowNanos) {
        checkOwner();if(status!=Status.RUNNING)return;
        if(busy){fail("Reentrant scenario tick");return;}
        busy=true;
        try {
            if(!started){started=true;startNanos=stepNanos=lastNanos=nowNanos;}
            if(nowNanos-lastNanos<0)throw new IllegalStateException("Monotonic scenario clock regressed");
            lastNanos=nowNanos;ticks++;
            // Check before observing: even valid late progress cannot revive an expired budget.
            if(travelAction()&&(ticks-routeTick>TRAVEL_TICKS||nowNanos-routeNanos>TRAVEL_NANOS))
                throw new IllegalStateException("Scenario travel absolute route deadline exceeded");
            if(ticks-stepTick>STEP_TICKS||nowNanos-stepNanos>STEP_NANOS)throw new IllegalStateException("Scenario stage deadline exceeded");
            Observation s=Objects.requireNonNull(driver.observe(),"Scenario observation");last=s;
            if(status!=Status.RUNNING)return;
            if(scope==null)scope=s;
            if(!s.failure().isEmpty())throw new IllegalStateException("Driver: "+s.failure());
            if(!sameScope(scope,s))throw new IllegalStateException("World/session/epoch/resource changed outside an explicit external boundary");
            // The host records a committed READY hop, then moves the feet to the next goal.
            // That same observation can already be pending/not exactReady for the next pair.
            if(travelAction())observeTravelProgress(s,nowNanos);
            if(!ready(s))return;
            if(baseline==null){
                baseline=s;
                if(mode!=Mode.EXERCISE){verifyRecovery(s);expectation=RecoveryExpectation.from(s,true);receipt("RECONSTRUCTED",null,null,s,nowNanos,true);
                    if(status==Status.RUNNING)status=mode==Mode.AFTER_RELOAD?Status.WAITING_EXTERNAL_RESTART:Status.PASS;return;}
                if(s.focus()!=Focus.ORIGINAL||s.materialA()!=1||s.materialB()!=1||s.neighborhoodA()!=27||s.neighborhoodB()!=27)
                    throw new IllegalStateException("Exercise requires both stone seam cells, both complete neighborhoods and original focus");
                if(!s.center().equals(original))throw new IllegalStateException("Exercise starts at original native center");
                if(s.completedActionId()!=0||s.outcome()!=Outcome.NONE)throw new IllegalStateException("Exercise requires a fresh action receipt channel");
                receipt("INITIAL_READY",null,null,s,nowNanos,true);next(s,nowNanos);return;
            }
            if(action==null)throw new IllegalStateException("Missing action");
            if(s.completedActionId()>action.id())throw new IllegalStateException("Unexpected later action result");
            if(s.completedActionId()<action.id()||s.outcome()==Outcome.NONE)return;
            verifyAction(s);
            receipt(action.kind().name(),action,before,s,nowNanos,true);
            if(status!=Status.RUNNING)return;
            cursor++;
            if(cursor==SCRIPT.size()) {expectation=RecoveryExpectation.from(s,false);status=Status.WAITING_EXTERNAL_RELOAD;action=null;return;}
            next(s,nowNanos);
        }catch(RuntimeException|Error error){fail(error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage()));}
        finally{busy=false;}
    }
    private void next(Observation s,long now) {
        if(status!=Status.RUNNING)return;
        before=s;stepTick=routeTick=ticks;stepNanos=routeNanos=now;acceptedTravel=List.of();requiredTravelHops=0;
        action=new Action(++issued,SCRIPT.get(cursor));
        if(travelAction()){
            long hops=Math.abs((long)travelEnd().x()-before.center().x());
            if(before.center().y()!=original.y()||before.center().z()!=original.z()||hops<1||hops>MAX_ROUTE_HOPS)
                throw new IllegalStateException("Scenario travel route exceeds the fixed adjacent-hop bound");
            requiredTravelHops=(int)hops;
        }
        driver.issue(action);
    }
    private boolean travelAction(){return action!=null&&(action.kind()==Kind.TRAVEL_AWAY||action.kind()==Kind.RETURN);}
    private CubePos travelEnd(){return action.kind()==Kind.TRAVEL_AWAY?new CubePos(Math.subtractExact(original.x(),4),original.y(),original.z()):original;}
    private void observeTravelProgress(Observation s,long now) {
        // Compare old receipts even while pending/unready; shortening or rewriting a prior
        // proof is never harmless activity and may not wait for final publication to fail.
        if(s.travel().size()<acceptedTravel.size()||!s.travel().subList(0,acceptedTravel.size()).equals(acceptedTravel))
            throw new IllegalStateException("Travel committed prefix changed");
        verifyTravelPrefix(s.travel());
        if(s.revisionA()!=before.revisionA()||s.revisionB()!=before.revisionB()
                ||s.materialA()!=before.materialA()||s.materialB()!=before.materialB()||s.focus()!=before.focus()
                ||s.indexGeneration()!=before.indexGeneration()||!s.indexRoot().equals(before.indexRoot())
                ||!s.queryIdentity().equals(before.queryIdentity())||!s.durableSetIdentity().equals(before.durableSetIdentity()))
            throw new IllegalStateException("Travel changed immutable revision/material/focus or durable query authority");
        long pub=acceptedTravel.isEmpty()?before.publication():acceptedTravel.getLast().publication();
        long coverage=acceptedTravel.isEmpty()?before.coverageGeneration():acceptedTravel.getLast().coverageGeneration();
        if(s.publication()<pub||s.coverageGeneration()<coverage)
            throw new IllegalStateException("Travel committed publication or coverage regressed");
        if(s.completedActionId()<before.completedActionId()||s.completedActionId()>action.id())
            throw new IllegalStateException("Travel action receipt is stale or unexpectedly later");
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
                throw new IllegalStateException("Travel skipped an exact adjacent paired center toward its target");
            previous=step.center();pub=step.publication();coverage=step.coverageGeneration();
        }
    }
    private void verifyAction(Observation s) {
        Kind k=action.kind();boolean refusal=k==Kind.OUTSIDE_EDIT_REFUSAL;
        if(s.outcome()!=(refusal?Outcome.REFUSED:Outcome.APPLIED))throw new IllegalStateException("Unexpected action outcome for "+k);
        long a=before.revisionA(),b=before.revisionB();int ma=before.materialA(),mb=before.materialB();
        Focus focus=before.focus();
        switch(k){
            case REMOVE_A -> {a=Math.incrementExact(a);ma=0;}
            case REMOVE_B -> {b=Math.incrementExact(b);mb=0;}
            case FILL_A -> {a=Math.incrementExact(a);ma=2;}
            case FILL_B -> {b=Math.incrementExact(b);mb=2;}
            case FOCUS_A -> focus=Focus.ORIGINAL;
            case FOCUS_B -> focus=Focus.SECOND;
            default -> { }
        }
        if(s.revisionA()!=a||s.revisionB()!=b||s.materialA()!=ma||s.materialB()!=mb||s.focus()!=focus)
            throw new IllegalStateException("Unexpected revision/material/focus result for "+k);
        if(s.publication()<before.publication()||s.coverageGeneration()<before.coverageGeneration()
                ||s.indexGeneration()<before.indexGeneration())throw new IllegalStateException("Publication or index generation regressed");
        boolean changed=k==Kind.REMOVE_A||k==Kind.REMOVE_B||k==Kind.FILL_A||k==Kind.FILL_B;
        if(changed){
            if(s.publication()<=before.publication()||s.indexGeneration()<=before.indexGeneration()
                    ||s.indexRoot().equals(before.indexRoot())||s.queryIdentity().equals(before.queryIdentity())
                    ||s.durableSetIdentity().equals(before.durableSetIdentity()))throw new IllegalStateException("Edit did not produce fresh durable query authority");
        }else if(s.indexGeneration()!=before.indexGeneration()||!s.indexRoot().equals(before.indexRoot())
                ||!s.queryIdentity().equals(before.queryIdentity())||!s.durableSetIdentity().equals(before.durableSetIdentity()))
            throw new IllegalStateException("Non-edit changed durable query authority");
        if(k==Kind.STAGE_A_AIM||k==Kind.STAGE_B_AIM||k==Kind.STAGE_OUTSIDE_AIM){
            double offset=k==Kind.STAGE_B_AIM?16.5:15.5;
            double zOffset=k==Kind.STAGE_OUTSIDE_AIM?17.5:12.5;
            Pose pose=s.pose();
            if(Math.abs(pose.x()-(original.minBlockX()+offset))>.01||Math.abs(pose.y()-(original.minBlockY()+1))>.01
                    ||Math.abs(pose.z()-(original.minBlockZ()+zOffset))>.01||Math.abs(pose.yaw())>.01||Math.abs(pose.pitch()-31)>.01)
                throw new IllegalStateException("Controlled ray aim pose mismatch");
        }
        if(k==Kind.FOCUS_A||k==Kind.FOCUS_B){
            if(!s.center().equals(before.center())||s.publication()<=before.publication())throw new IllegalStateException("Focus must commit at the same center");
        }
        if(k==Kind.TRAVEL_AWAY||k==Kind.RETURN){
            verifyTravel(s,travelEnd());
            if(k==Kind.TRAVEL_AWAY&&(s.neighborhoodA()!=0||s.neighborhoodB()!=0))throw new IllegalStateException("Both full neighborhoods must retire");
            if(k==Kind.RETURN&&(s.neighborhoodA()!=27||s.neighborhoodB()!=27))throw new IllegalStateException("Both complete neighborhoods must reacquire");
        }else if(!s.travel().isEmpty())throw new IllegalStateException("Unrequested travel receipt");
    }
    private void verifyTravel(Observation s,CubePos end) {
        if(s.travel().isEmpty())throw new IllegalStateException("Missing adjacent-center travel proof");
        verifyTravelPrefix(s.travel());TravelStep tail=s.travel().getLast();
        if(!s.travel().equals(acceptedTravel)||s.travel().size()!=requiredTravelHops||!tail.center().equals(end)||!s.center().equals(end)
                ||tail.publication()!=s.publication()||tail.coverageGeneration()!=s.coverageGeneration())
            throw new IllegalStateException("Travel final pair mismatch");
    }
    private void verifyRecovery(Observation s) {
        if(!expectation.worldIdentity().equals(s.worldIdentity())||expectation.revisionA()!=s.revisionA()||expectation.revisionB()!=s.revisionB()
                ||expectation.materialA()!=s.materialA()||expectation.materialB()!=s.materialB()||expectation.indexGeneration()!=s.indexGeneration()
                ||!expectation.indexRoot().equals(s.indexRoot())||!expectation.queryIdentity().equals(s.queryIdentity())
                ||!expectation.durableSetIdentity().equals(s.durableSetIdentity()))throw new IllegalStateException("Reconstruction differs from saved durable expectation");
        if(mode==Mode.AFTER_RELOAD){
            if(!expectation.processIdentity().equals(s.processIdentity())||s.reloadSequence()<=expectation.priorReloadSequence()
                    ||(s.epoch()==expectation.priorEpoch()&&s.resourceGeneration()==expectation.priorResourceGeneration()))
                throw new IllegalStateException("Actual resource reload and new session/resource boundary not observed");
        }else if(!expectation.reloadVerified()||expectation.processIdentity().equals(s.processIdentity()))
            throw new IllegalStateException("Actual new JVM after verified reload not observed");
        if(s.neighborhoodA()!=27||s.neighborhoodB()!=27)throw new IllegalStateException("Reconstruction requires both complete neighborhoods");
    }
    private static boolean ready(Observation s) {
        return s.exactReady()&&!s.pending()&&s.queryComplete()&&s.currentAuthority()&&s.publication()>0
                &&s.coverageGeneration()>=0&&s.indexGeneration()>=0&&!s.indexRoot().isEmpty()
                &&!s.queryIdentity().isEmpty()&&!s.durableSetIdentity().isEmpty();
    }
    private static boolean sameScope(Observation a,Observation b) {
        return a.worldIdentity().equals(b.worldIdentity())&&a.processIdentity().equals(b.processIdentity())&&a.reloadSequence()==b.reloadSequence()
                &&a.epoch()==b.epoch()&&a.resourceGeneration()==b.resourceGeneration();
    }
    private void receipt(String label,Action a,Observation previous,Observation current,long now,boolean screenshot) {
        if(transcript.size()>=MAX_TRANSCRIPT)throw new IllegalStateException("Scenario transcript bound");
        Receipt receipt=new Receipt(label,"SCRIPTED_HARNESS",ticks,now-startNanos,a,previous,current,deadline());transcript.add(receipt);
        driver.checkpoint(receipt,screenshot);
    }
    private void fail(String reason) {
        if(status==Status.FAIL)return;
        status=Status.FAIL;failure=reason==null?"Scenario failed":reason.substring(0,Math.min(512,reason.length()));
        if(transcript.size()<MAX_TRANSCRIPT)transcript.add(new Receipt("FAIL: "+failure,"SCRIPTED_HARNESS",ticks,started?lastNanos-startNanos:0,action,before,last,deadline()));
        try{driver.abort(failure);}catch(RuntimeException|Error abort){failure=clip(failure+"; abort failed: "+abort.getClass().getSimpleName());}
    }
    private Deadline deadline(){
        boolean travel=travelAction();
        return new Deadline(travel?"EXACT_COMMITTED_HOP_STALL_AND_ABSOLUTE_ROUTE_CAP":"FIXED_STAGE",
                STEP_TICKS,STEP_NANOS,TRAVEL_TICKS,TRAVEL_NANOS,travel,acceptedTravel.size(),requiredTravelHops,
                started?ticks-stepTick:0,started?lastNanos-stepNanos:0,travel?ticks-routeTick:0,travel?lastNanos-routeNanos:0,acceptedTravel);
    }
    private static String clip(String value){return value.substring(0,Math.min(512,value.length()));}
    private static void bounded(String s){Objects.requireNonNull(s);if(s.length()>512)throw new IllegalArgumentException("Bounded scenario metadata");}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Scenario owner thread required");}
}
