package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.mixin.SurvivalSessionPlayerStorageAccess;
import io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.PlayerDataStorage;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/** Concrete Port for the default-off HELD native transition slice. Uses the staged host's sole
 * actual coordinator; native ordinary save uses the separately reviewed one-shot observer.
 * No production command or factory is registered. Non-RETURNED recovery remains fenced.
 */
public final class NativeSessionBridge implements SurvivalSessionTransitions.Port {
    private final NativeEffectDeliveryHost host;
    private final NativeSessionExecutionGate gate;
    private final ServerPlayer player;
    private final PlayerDataStorage storage;
    private final Executor io;
    private final int ordinaryBytes;
    private NativePreparedSessionChange prepared;
    private Session restored;
    private NativeSurvivalCheckpoint restoredFrom;
    private Object returnedSaveProvenance;
    private boolean ordinarySaveVerified;
    private final NativeSessionReleasePermit releasePermit=new NativeSessionReleasePermit();
    private NativeSurvivalCheckpoint releasedCheckpoint;
    private SavedActiveProof savedActiveProof;
    private record SavedActiveProof(NativeSurvivalCheckpoint checkpoint,NativeEffectDeliveryHost.TransitionReceipt receipt) {}
    private void invalidateRelease(){releasePermit.invalidate();}

    public NativeSessionBridge(NativeEffectDeliveryHost host,NativeSessionExecutionGate gate,Executor io,int ordinaryBytes) {
        this.host=Objects.requireNonNull(host);this.gate=Objects.requireNonNull(gate);this.io=Objects.requireNonNull(io);
        if(ordinaryBytes<1)throw new IllegalArgumentException("Bounded ordinary escrow required");this.ordinaryBytes=ordinaryBytes;
        player=gate.player();storage=((SurvivalSessionPlayerStorageAccess)player.server.getPlayerList()).earthplatform$playerStorage();
        requireHealthy();gate.requireHeld();
    }
    public Object authority(){invalidateRelease();return host;}
    public Binding binding(){invalidateRelease();return host.transitionBinding(gate);}
    public UUID ownerEpoch(){invalidateRelease();return host.transitionWorld(gate).ownerEpoch();}
    public void requireHealthy(){invalidateRelease();host.requireTransitionHealthy(gate);if(((SurvivalSessionPlayerStorageAccess)player.server.getPlayerList()).earthplatform$playerStorage()!=storage)
        throw new SurvivalRefusal("Exact ordinary player storage was replaced");}
    public NativeSurvivalCheckpoint durable(){invalidateRelease();return host.transitionDurable(gate);}
    public void hold(){invalidateRelease();gate.hold(host);host.requireActiveSaveHeldEpoch(gate);}
    public boolean heldAndQuiescent(){invalidateRelease();return host.transitionQuiescent(gate);}
    public SurvivalSessionTransitions.OrdinaryState captureOrdinary(){gate.requireHeld();
        var state=NativeSessionAdmission.captureOrdinary(player,binding(),ordinaryBytes);
        if(state.pose().velocityX()!=0 || state.pose().velocityY()!=0 || state.pose().velocityZ()!=0)
            throw new SurvivalRefusal("First held native bridge requires stationary ordinary escrow");
        if((state.pose().gameMode()!=net.minecraft.world.level.GameType.SURVIVAL && state.pose().gameMode()!=net.minecraft.world.level.GameType.CREATIVE)
                || state.pose().abilities().flying() || player.noPhysics || player.isInvisible())
            throw new SurvivalRefusal("First held native bridge requires visible nonflying Survival/Creative ordinary state and normal physics");
        return state;}
    public void requireOrdinaryExact(Session session){
        invalidateRelease();gate.requireHeld();NativeSessionAdmission.requireOrdinaryExact(player,binding(),session);
        var current=host.transitionDurable(gate);
        if(current!=null && current.session().phase()==SessionPhase.RETURNED && current.session().equals(session)){
            requireReturnedReleaseProof(current);
            releasePermit.mint(current,host.requireTransitionReceipt(gate,current),gate,SessionPhase.RETURNED,returnedSaveProvenance);
        }
    }
    public Boundary initialEarthBoundary(){invalidateRelease();return host.initialTransitionBoundary(gate);}
    public SurvivalSessionTransitions.PreparedChange prepareEarth(Boundary earth){
        requireHealthy();gate.requireHeld();restored=null;restoredFrom=null;ordinarySaveVerified=false;returnedSaveProvenance=null;
        if(!earth.items().isEmpty() || !earth.scheduledTicks().isEmpty())throw new SurvivalRefusal("Native owner restoration is not admitted by this first held bridge");
        prepared=NativePreparedSessionChange.enter(this,host,gate,earth);return prepared;
    }
    public SurvivalSessionTransitions.PreparedChange prepareOrdinary(Session session){
        requireHealthy();gate.requireHeld();host.requireEmptyReturnOwners(gate);
        prepared=NativePreparedSessionChange.leave(this,host,gate,session);return prepared;
    }
    public void requireInstalledEarthExact(NativeSurvivalCheckpoint active){
        requireHealthy();gate.requireHeld();host.requireTransitionReceipt(gate,active);
        if(prepared==null || !prepared.isSettled() || active.session().phase()!=SessionPhase.ACTIVE)
            throw new SurvivalRefusal("Installed Earth lacks its exact settled native transfer");
        var live=host.captureNativeState();var expected=active.boundary();
        if(!live.items().isEmpty() || !live.ticks().isEmpty()
                || !NativeInventorySnapshot.capture(player,ordinaryBytes).equals(expected.inventory())
                || !NativeFoodSnapshot.capture(player.getFoodData()).equals(expected.food())
                || !NativePlayerPoseSnapshot.capture(player).equals(expected.pose()))
            throw new SurvivalRefusal("Complete installed Earth native boundary changed");
        releasePermit.mint(active,host.requireTransitionReceipt(gate,active),gate,SessionPhase.ACTIVE,prepared);
    }
    public boolean returnReady(){invalidateRelease();return host.returnReady(gate);}
    public boolean activeSaveReady(){invalidateRelease();return host.activeSaveReady(gate);}
    public void requireSavedEarthExact(NativeSurvivalCheckpoint active){
        requireHealthy();gate.requireHeld();
        var receipt=host.requireSavedActiveEarthExact(gate,active);
        savedActiveProof=new SavedActiveProof(active,receipt);
        releasePermit.mint(active,receipt,gate,SessionPhase.ACTIVE,savedActiveProof);
    }
    public SurvivalSessionTransitions.CheckpointOperation beginCheckpoint(SurvivalSessionTransitions.CheckpointRequest request){
        requireHealthy();gate.requireHeld();
        if(request.session().phase()==SessionPhase.RETURNED && (!ordinarySaveVerified || returnedSaveProvenance==null
                || restoredFrom!=request.previous() || !request.session().id().equals(restored.id())))
            throw new SurvivalRefusal("RETURNED has no exact native ordinary-save completion");
        return host.beginTransitionCheckpoint(gate,request,io);
    }
    void requirePermit(NativeSurvivalCheckpoint permit,SessionPhase phase){
        requireHealthy();gate.requireHeld();if(permit==null || permit.session().phase()!=phase || durable()!=permit)
            throw new SurvivalRefusal("Native mutation requires exact CURRENT phase permit");host.requireTransitionReceipt(gate,permit);
    }
    void ordinaryRestored(Session session,NativeSurvivalCheckpoint returning){
        requirePermit(returning,SessionPhase.RETURNING);requireOrdinaryExact(session);
        restored=session;restoredFrom=returning;gate.restoredOrdinary(host);
    }
    public SurvivalSessionTransitions.SaveOperation saveOrdinary(Session session,NativeSurvivalCheckpoint returning){
        requirePermit(returning,SessionPhase.RETURNING);requireOrdinaryExact(session);
        if(prepared==null || !prepared.isSettled() || restoredFrom!=returning || !session.id().equals(restored.id()))
            throw new SurvivalRefusal("Ordinary restore has not settled for this exact RETURNING");
        var provenance=host.requireTransitionReceipt(gate,returning);
        var authority=new NativeOrdinarySaveObserver.Authority(){
            ServerPlayer player(){return player;}
            PlayerDataStorage storage(){return storage;}
            NativeSurvivalCheckpoint returning(){return returning;}
            Object checkpointProvenance(){return provenance;}
            void requireHeldAndCurrent(NativeSurvivalCheckpoint exact,Object exactProvenance){
                NativeSessionBridge.this.requirePermit(returning,SessionPhase.RETURNING);
                if(exact!=returning || exactProvenance!=provenance || host.requireTransitionReceipt(gate,returning)!=provenance
                        || restoredFrom!=returning || prepared==null || !prepared.isSettled())
                    throw new SurvivalRefusal("Ordinary save authority changed");requireOrdinaryExact(session);
            }
        };
        final NativeOrdinarySaveObserver.Completion completion;
        gate.beginSave(host,storage);
        try{completion=gate.saveOrdinary(authority);}finally{gate.endSave(host);}
        return new SurvivalSessionTransitions.SaveOperation(){
            boolean consumed;
            public SurvivalSessionTransitions.Result poll(){
                if(consumed)throw new SurvivalRefusal("Ordinary save completion already consumed");
                requirePermit(returning,SessionPhase.RETURNING);requireOrdinaryExact(session);
                if(completion.failure()!=null){consumed=true;return SurvivalSessionTransitions.Result.failed(completion.failure());}
                try{completion.requireSucceeded(authority);ordinarySaveVerified=true;returnedSaveProvenance=provenance;consumed=true;
                    return SurvivalSessionTransitions.Result.succeeded();}
                catch(RuntimeException | Error failure){consumed=true;return SurvivalSessionTransitions.Result.failed(failure);}
            }
        };
    }
    private void requireReturnedReleaseProof(NativeSurvivalCheckpoint current){
        if(!ordinarySaveVerified || returnedSaveProvenance==null || restoredFrom==null || restored==null || prepared==null || !prepared.isSettled()
                || current.session().phase()!=SessionPhase.RETURNED || !current.session().id().equals(restored.id())
                || current.boundary()!=restoredFrom.boundary() || current.generation()!=Math.incrementExact(restoredFrom.generation())
                || !current.session().ordinaryEscrow().equals(restored.ordinaryEscrow())
                || !current.session().ordinaryFood().equals(restored.ordinaryFood()) || !current.session().ordinaryPose().equals(restored.ordinaryPose()))
            throw new SurvivalRefusal("Native release lacks exact settled restore/save/RETURNED provenance");
    }
    /** Existing full pre-release validation privately mints this grant; no codec/callback work here.
     * Every other public Port call invalidates it. No callbacks or throwable work follow opening.
     */
    public void release(){
        gate.requireHeld();host.requireTransitionHealthy(gate);
        NativeSurvivalCheckpoint current=host.transitionDurable(gate);
        if(current==null || current==releasedCheckpoint)throw new SurvivalRefusal("Absent or replayed native release");
        Object proof;
        if(current.session().phase()==SessionPhase.RETURNED){requireReturnedReleaseProof(current);proof=returnedSaveProvenance;}
        else if(current.session().phase()==SessionPhase.ACTIVE && savedActiveProof!=null && savedActiveProof.checkpoint()==current)proof=savedActiveProof;
        else if(current.session().phase()==SessionPhase.ACTIVE && prepared!=null && prepared.isSettled())proof=prepared;
        else throw new SurvivalRefusal("Native release lacks exact settled phase proof");
        var receipt=host.requireTransitionReceipt(gate,current);
        // Recheck all held owners immediately before consuming the existing exact grant. The
        // subsequent saved-ACTIVE tail remains identity/field-only and the gate stays closed on failure.
        if(proof==savedActiveProof && host.requireSavedActiveEarthExact(gate,current)!=savedActiveProof.receipt())
            throw new SurvivalRefusal("Saved ACTIVE fresh release recheck changed its receipt");
        releasePermit.consume(current,receipt,gate,current.session().phase(),proof);
        if(proof==savedActiveProof)host.consumeSavedActiveRelease(gate,current,savedActiveProof.receipt());
        else host.retireTransitionReleaseHistory(gate,current,receipt);
        // Drop old transfer/save proof aliases before opening. CURRENT is the only durable lease.
        savedActiveProof=null;prepared=null;restored=null;restoredFrom=null;returnedSaveProvenance=null;ordinarySaveVerified=false;
        releasedCheckpoint=current;gate.releaseFields();
    }
}
