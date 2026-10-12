package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import java.util.Objects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Owner-thread, constant-space body-frontier permit. This does not certify visual coverage or replace native collision. */
final class RollingMotionGuard {
    enum State { BOOTSTRAP_HOLD, OPEN, OVERLAP, FRONTIER_HOLD, COMMIT_HOLD, RECOVER_ONCE, FAILED_HOLD }
    /** Deliberately separate from the legacy walking displacement bound. Applies to all rolling modes. */
    static final double MAX_TRANSLATION_PER_AXIS = 4;
    /** Safety watchdog only; packaged-client latency qualification remains required. */
    static final long CORRECTION_ACK_TIMEOUT_NANOS = 10_000_000_000L;
    static final long COMMIT_TIMEOUT_NANOS = 60_000_000_000L;
    record Authority(long epoch,long actorGeneration,long publication,String digest,long frame,
                     long resources,String source) {
        Authority { if(epoch<0||actorGeneration<1||publication<1||frame<1||resources<0)throw new IllegalArgumentException("Invalid body authority"); Objects.requireNonNull(digest);Objects.requireNonNull(source); }
    }
    record Pose(AABB body,Vec3 feet,boolean spectator,boolean noPhysics,Authority authority) {
        Pose { if(!finiteBody(body)||feet==null||!finite(feet))throw new IllegalArgumentException("Invalid pose"); }
    }
    record Reservation(CubePos current,CubePos candidate,long generation,Pose anchor) {}
    record Pair(long epoch,long publication,long actorGeneration,CubePos candidate,long resources,String digest) {}
    interface Certification { boolean valid(Pose pose); }
    private final Thread owner=Thread.currentThread();
    private final long epoch;
    private final AABB envelope;
    private State state=State.BOOTSTRAP_HOLD;
    private Authority authority;
    private CubePos center;
    private Pose accepted;
    private Reservation reservation;
    private Vec3 latestIntent;
    private CubePos visualGoal;
    private boolean visualFrontier;
    private Pair pair;
    private long recoveredPublication=-1,deadline,permitId=1,rejections,recoveries,lastVisualIntent;
    private int correctionId;
    private boolean correctionPending;
    private String reason="bootstrap";

    RollingMotionGuard(long epoch,AABB envelope,Pose entry,CubePos initial) {
        this.epoch=epoch;this.envelope=Objects.requireNonNull(envelope);this.accepted=Objects.requireNonNull(entry);
        if(!inside(entry.body(),envelope)||!RollingWindowAdmission.contains(entry.body(),initial))throw new IllegalArgumentException("Entry outside body frontier");
        latestIntent=entry.feet();reservation=new Reservation(initial,initial,1,null);
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Motion permit requires game owner thread");}
    State state(){return state;} String reason(){return reason;} long permitId(){return permitId;}
    long rejections(){return rejections;}long recoveries(){return recoveries;}
    Pose accepted(){return accepted;} Reservation reservation(){return reservation;} Authority authority(){return authority;}
    void enableVisualFrontier(){owned();visualFrontier=true;}
    CubePos visualGoal(){return visualGoal==null?(center==null?reservation.current():center):visualGoal;}
    boolean visualCameraKnown(AABB body,CubePos target){return !visualFrontier||RollingVisualFrontier.eyeInside(target,body);}
    Vec3 latestIntent(){return latestIntent;} boolean correctionPending(){return correctionPending;}
    boolean translating(){return !correctionPending&&(state==State.OPEN||state==State.OVERLAP||state==State.FRONTIER_HOLD);}
    void fail(String why){owned();state=State.FAILED_HOLD;reason=why;permitId++;}
    boolean expired(long now){return deadline!=0&&now-deadline>=0;}
    boolean reserve(CubePos candidate,long generation,Certification certify) {
        owned();if(reservation!=null||authority==null||!translating())return false;
        if(!RollingCubePlan.adjacent(center,candidate)||generation!=authority.actorGeneration()+1)throw new IllegalArgumentException("Reservation must be exact adjacent generation");
        if(!eligible(accepted,center,candidate)||!visualCameraKnown(accepted.body(),candidate)||!certify.valid(accepted))return false;
        reservation=new Reservation(center,candidate,generation,accepted);state=State.OVERLAP;reason="reserved overlap";permitId++;return true;
    }
    void requireMetadata(CubePos current,CubePos candidate,long generation) {
        owned();if(reservation==null||!reservation.current().equals(current)||!reservation.candidate().equals(candidate)||reservation.generation()!=generation)throw new IllegalStateException("Actor stage has no exact pre-request reservation");
    }
    void cancelUnstartedReservation(){owned();if(pair!=null)throw new IllegalStateException("Cannot cancel a publication");reservation=null;state=State.OPEN;reason="request refused";permitId++;}
    boolean admit(Pose actual,Vec3 delta,Certification certify,boolean captureIntent) {
        owned();if(!bounded(actual.body(),delta))return reject("unsupported movement");
        if(captureIntent&&delta.lengthSqr()>0&&inside(accepted.body().move(delta),envelope))latestIntent=accepted.feet().add(delta);
        if(delta.lengthSqr()==0)return state!=State.FAILED_HOLD;
        if(!translating())return reject("translation held");
        if(!sameMode(actual,accepted)||!sameBody(actual.body(),accepted.body())||!Objects.equals(actual.authority(),authority)||!certify.valid(accepted))return reject("stale accepted pose");
        AABB swept=actual.body().minmax(actual.body().move(delta));
        if(!frontier(swept)||visualFrontier&&(!RollingVisualFrontier.motionInside(center,actual.body(),delta)||visualCandidate()!=null&&!RollingVisualFrontier.motionInside(visualCandidate(),actual.body(),delta)))return reject("body frontier");
        return true;
    }
    /** An adjacent client request can advance preparation while predicted translation is held. */
    boolean visualIntent(long requestEpoch,long publication,long resources,long intentId,CubePos desired){
        owned();if(state==State.FAILED_HOLD||authority==null||requestEpoch!=epoch||publication!=authority.publication()||resources!=authority.resources()||intentId<=lastVisualIntent||desired==null||desired.equals(center)||!RollingCubePlan.adjacent(center,desired)||reservation!=null||visualGoal!=null||!visualCameraKnown(accepted.body(),desired))return false;
        lastVisualIntent=intentId;visualGoal=desired;return true;
    }
    private boolean reject(String why){rejections++;reason=why;if(state==State.OPEN||state==State.OVERLAP)state=State.FRONTIER_HOLD;return false;}
    boolean admitBody(Pose proposed,Certification certify) {
        owned();
        if(!sameMode(proposed,accepted)||!Objects.equals(proposed.authority(),authority))return false;
        if(!translating())return sameBody(proposed.body(),accepted.body());
        var delta=proposed.feet().subtract(accepted.feet());
        return bounded(accepted.body(),delta)&&frontier(accepted.body().minmax(proposed.body()))&&certify.valid(proposed);
    }
    boolean observe(Pose actual,Certification certify) {
        owned();
        if(!Objects.equals(actual.authority(),authority)||!sameMode(actual,accepted))return false;
        if(!translating())return sameBody(actual.body(),accepted.body());
        if(!frontier(actual.body())||!certify.valid(actual))return false;
        accepted=actual;return true;
    }
    /** Explicit command-driven mode change is admitted only with fresh current-body certification. */
    void modeChanged(Pose pose,Certification certify){owned();if(!translating()||!frontier(pose.body())||!certify.valid(pose))throw new IllegalStateException("Mode change lacks body authority");accepted=pose;if(reservation!=null)reservation=new Reservation(reservation.current(),reservation.candidate(),reservation.generation(),pose);permitId++;}
    void beginCommit(Pair exact,Pose actual,Certification certify,long now) {
        owned();requirePair(exact);
        if(state==State.FAILED_HOLD)throw new IllegalStateException("Terminal motion hold");
        if(correctionPending||!sameMode(actual,accepted)||!visualCameraKnown(actual.body(),exact.candidate())||!eligible(actual,exact.candidate(),exact.candidate())||(!bootstrap()&&!certify.valid(actual)))throw new IllegalStateException("Commit pose unavailable");
        if(!bootstrap()&&!Objects.equals(actual.authority(),authority))throw new IllegalStateException("Commit frame changed");
        accepted=actual;pair=exact;state=State.COMMIT_HOLD;reason="exact pair commit";deadline=now+COMMIT_TIMEOUT_NANOS;permitId++;
    }
    boolean bootstrap(){return authority==null;}
    /** Exactly one old accepted anchor, never a clamped or manufactured destination. */
    Pose recover(Pair exact,Pose current,Certification certify,long now) {
        owned();requirePair(exact);
        if(state==State.FAILED_HOLD||!Objects.equals(current.authority(),authority)||recoveredPublication==exact.publication()||reservation==null||reservation.anchor()==null||correctionPending)throw new IllegalStateException("Recovery anchor unavailable or already used");
        var anchor=reservation.anchor();
        if(!Objects.equals(anchor.authority(),authority)||!sameMode(anchor,current)||!eligible(anchor,reservation.current(),reservation.candidate())||!certify.valid(anchor))throw new IllegalStateException("Recovery anchor failed exact revalidation");
        recoveredPublication=exact.publication();recoveries++;pair=exact;accepted=anchor;state=State.RECOVER_ONCE;reason="one exact overlap correction";deadline=now+CORRECTION_ACK_TIMEOUT_NANOS;permitId++;return anchor;
    }
    private void requirePair(Pair exact){
        if(exact.epoch()!=epoch||exact.publication()<1||reservation==null||exact.actorGeneration()!=reservation.generation()||!exact.candidate().equals(reservation.candidate()))throw new IllegalStateException("Pair differs from reserved transition");
        if(pair!=null&&!pair.equals(exact))throw new IllegalStateException("Pair changed while held");
    }
    void correctionSent(int id,long now){owned();if(correctionPending)throw new IllegalStateException("One correction may be outstanding");correctionPending=true;correctionId=id;if(deadline==0)deadline=now+CORRECTION_ACK_TIMEOUT_NANOS;}
    boolean acknowledge(int id,Pose actual,boolean vanillaSettled){
        owned();if(!correctionPending||id!=correctionId||!vanillaSettled||!sameMode(actual,accepted)||!Objects.equals(actual.authority(),authority)||!sameBody(actual.body(),accepted.body()))return false;
        correctionPending=false;
        if(state!=State.RECOVER_ONCE&&state!=State.COMMIT_HOLD)deadline=0;
        return true;
    }
    void committed(Authority next,CubePos candidate,Pose pose,Certification certify) {
        owned();if(state!=State.COMMIT_HOLD||pair==null||next.epoch()!=epoch||next.actorGeneration()!=pair.actorGeneration()||next.publication()!=pair.publication()||!next.digest().equals(pair.digest())||next.resources()!=pair.resources()||!candidate.equals(pair.candidate())||correctionPending)throw new IllegalStateException("Commit authority differs from held exact pair");
        if(!RollingWindowAdmission.contains(pose.body(),candidate)||!inside(pose.body(),envelope)||!certify.valid(pose))throw new IllegalStateException("Committed pose is not known");
        authority=next;center=candidate;accepted=pose;visualGoal=null;reservation=null;pair=null;deadline=0;state=State.OPEN;reason="body pair ready";permitId++;
    }
    /** Same-generation edits retain a known pose, but invalidate old frame/anchor before mutation. */
    void editHold(long generation,CubePos current){owned();if(reservation!=null||!translating()||authority==null||generation!=authority.actorGeneration())throw new IllegalStateException("Edit overlaps movement stage");reservation=new Reservation(current,current,generation,null);state=State.COMMIT_HOLD;reason="edit frame invalidated";permitId++;}
    private boolean eligible(Pose p,CubePos a,CubePos b){return inside(p.body(),envelope)&&RollingWindowAdmission.contains(p.body(),a)&&RollingWindowAdmission.contains(p.body(),b);}
    private CubePos visualCandidate(){return reservation==null?visualGoal:reservation.candidate();}
    private boolean frontier(AABB body){return inside(body,envelope)&&center!=null&&window(body,center)&&(reservation==null||window(body,reservation.candidate()))&&(!visualFrontier||visualCameraKnown(body,center)&&(visualCandidate()==null||visualCameraKnown(body,visualCandidate())));}
    private static boolean window(AABB box,CubePos c){return box.minX>=c.minBlockX()-32&&box.maxX<=c.minBlockX()+48&&box.minY>=c.minBlockY()-32&&box.maxY<=c.minBlockY()+48&&box.minZ>=c.minBlockZ()-32&&box.maxZ<=c.minBlockZ()+48;}
    static boolean inside(AABB a,AABB b){return a.minX>=b.minX&&a.maxX<=b.maxX&&a.minY>=b.minY&&a.maxY<=b.maxY&&a.minZ>=b.minZ&&a.maxZ<=b.maxZ;}
    static boolean sameBody(AABB a,AABB b){return Math.abs(a.minX-b.minX)<1e-7&&Math.abs(a.minY-b.minY)<1e-7&&Math.abs(a.minZ-b.minZ)<1e-7&&Math.abs(a.maxX-b.maxX)<1e-7&&Math.abs(a.maxY-b.maxY)<1e-7&&Math.abs(a.maxZ-b.maxZ)<1e-7;}
    private static boolean sameMode(Pose a,Pose b){return a.spectator()==b.spectator()&&a.noPhysics()==b.noPhysics();}
    static boolean finiteBody(AABB b){return b!=null&&Double.isFinite(b.minX)&&Double.isFinite(b.minY)&&Double.isFinite(b.minZ)&&Double.isFinite(b.maxX)&&Double.isFinite(b.maxY)&&Double.isFinite(b.maxZ)&&b.getXsize()>0&&b.getXsize()<=1&&b.getYsize()>0&&b.getYsize()<=2&&b.getZsize()>0&&b.getZsize()<=1;}
    static boolean bounded(AABB b,Vec3 d){return finiteBody(b)&&d!=null&&finite(d)&&Math.abs(d.x)<=MAX_TRANSLATION_PER_AXIS&&Math.abs(d.y)<=MAX_TRANSLATION_PER_AXIS&&Math.abs(d.z)<=MAX_TRANSLATION_PER_AXIS;}
    private static boolean finite(Vec3 v){return Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
}
