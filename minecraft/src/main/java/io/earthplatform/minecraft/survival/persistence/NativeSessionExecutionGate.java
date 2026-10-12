package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;

/** External exact-object ownership. Never changes a player field to manufacture a freeze.
 * This first installer is fixture-only. ACTIVE native execution remains CLOSED: native TICK,
 * LOCATION, weather, damage and normal scheduling are not licensed by a transition receipt.
 * Entries are deliberately retained on failure/logout; replacing the player is not recovery.
 */
public final class NativeSessionExecutionGate {
    private static final Map<ServerPlayer,NativeSessionExecutionGate> OWNED = new IdentityHashMap<>();
    private static final Map<ServerLevel,NativeSessionExecutionGate> CARRIERS = new IdentityHashMap<>();
    private io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.Token installedAdmission;
    private ServerLevel carrier;
    private long suppressedScheduler, suppressedCarrier;
    private final ServerPlayer player;
    private final ServerGamePacketListenerImpl connection;
    private final Thread owner = Thread.currentThread();
    private final ServerLevel ordinary;
    private final Object registries, reloadable;
    private volatile boolean transitionHeld = true, earthExecutionClosed, failed;
    private Object claimant;
    private PlayerDataStorage saving;
    private int mutationDepth;
    private io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost.PlayerMovePermit playerMove;
    private Object teleportOwner;
    private int teleportId;
    private NativeSessionAckScope ackScope;
    private NativeSessionExecutionGate(ServerPlayer player) {
        this.player=Objects.requireNonNull(player); connection=Objects.requireNonNull(player.connection);
        ordinary=player.serverLevel(); registries=player.registryAccess(); reloadable=player.server.reloadableRegistries();
        requireIdentity();
    }
    public static NativeSessionExecutionGate installHeldFixture(ServerPlayer player) {
        if (!Boolean.getBoolean("earthplatform.nativeSessionBridge") || !Boolean.getBoolean("neoforge.gameTestServer")
                || !(player.server instanceof GameTestServer))
            throw new SurvivalRefusal("Native session bridge installer is default-off and GameTest-only");
        if(!(player.connection instanceof io.earthplatform.minecraft.mixin.SurvivalSessionNativeAccess)
                || !(player instanceof io.earthplatform.minecraft.mixin.SurvivalSessionEntityCacheAccess)
                || !(player.gameMode instanceof io.earthplatform.minecraft.mixin.SurvivalSessionModeAccess)
                || !(player.server.getPlayerList() instanceof io.earthplatform.minecraft.mixin.SurvivalSessionPlayerStorageAccess))
            throw new SurvivalRefusal("Required native session bridge mixins were not installed");
        NativeSessionExecutionGate gate=new NativeSessionExecutionGate(player);
        synchronized (OWNED) {
            if (OWNED.containsKey(player)) throw new SurvivalRefusal("Exact native player already has a session gate");
            OWNED.put(player,gate);
        }
        return gate;
    }
    /** Separate installed capability. A launch flag or a server class never mints this token. */
    public static NativeSessionExecutionGate installHeldInstalled(ServerPlayer player,
            io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.Token token) {
        Objects.requireNonNull(token).requireCurrent();
        if (!(player.connection instanceof io.earthplatform.minecraft.mixin.SurvivalSessionNativeAccess)
                || !(player instanceof io.earthplatform.minecraft.mixin.SurvivalSessionEntityCacheAccess)
                || !(player.gameMode instanceof io.earthplatform.minecraft.mixin.SurvivalSessionModeAccess)
                || !(player.server.getPlayerList() instanceof io.earthplatform.minecraft.mixin.SurvivalSessionPlayerStorageAccess))
            throw new SurvivalRefusal("Required installed native session mixins were not installed");
        var gate = new NativeSessionExecutionGate(player);
        synchronized (OWNED) {
            if (OWNED.containsKey(player)) throw new SurvivalRefusal("Exact installed native player already has an owner");
            token.claimGate(gate,player); gate.installedAdmission=token; OWNED.put(player,gate);
        }
        return gate;
    }
    public static boolean requireInstalledTransfer(ServerPlayer player,ServerLevel from,ServerLevel to) {
        var gate=find(player);
        if (gate==null || gate.installedAdmission==null) return false;
        gate.installedAdmission.requireTransfer(player,from,to); return true;
    }
    /** Exact retained owner only; cannot release or replace the failed gate. */
    public void failInstalled(io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.Token token) {
        if (Thread.currentThread()!=owner || token==null || installedAdmission!=token)
            throw new SurvivalRefusal("Foreign installed gate failure owner");
        fail();
    }
    /** IntegratedPlayerList serializes into its own cached tag before PlayerDataStorage. */
    public static boolean blocksPlayerListSave(ServerPlayer player) {
        var gate=find(player);
        return gate!=null && (gate.transitionHeld || gate.earthExecutionClosed || gate.failed)
                && !(Thread.currentThread()==gate.owner && gate.saving!=null && !gate.failed);
    }
    public static NativeSessionExecutionGate find(Player player) {
        synchronized (OWNED) { return OWNED.get(player); }
    }
    /** Safe at a packet HEAD on Netty: only object lookup and volatile reads, never native access. */
    public static boolean blocksNative(Player player) {
        NativeSessionExecutionGate gate=find(player);
        return gate!=null && (gate.transitionHeld || gate.earthExecutionClosed || gate.failed);
    }
    /** Negative capability only: installed A/B never qualifies GameTest gameplay diagnostics. */
    public static boolean holdsInstalledInspection(ServerPlayer player) {
        var gate=find(player);
        return gate!=null && gate.installedAdmission!=null && (gate.transitionHeld || gate.earthExecutionClosed || gate.failed);
    }
    public void bindCarrier(Object exact,ServerLevel level) {
        requireClaim(exact);requireHeld();
        synchronized(CARRIERS){if(carrier!=null || CARRIERS.containsKey(level))throw new SurvivalRefusal("Carrier execution hold already owned");carrier=level;CARRIERS.put(level,this);}
    }
    /** First-slice carrier execution remains fenced even after clean ordinary return. */
    public static boolean blocksCarrier(ServerLevel level) {synchronized(CARRIERS){return CARRIERS.containsKey(level);}}
    public static void observedCarrierSuppression(ServerLevel level){synchronized(CARRIERS){var gate=CARRIERS.get(level);if(gate!=null){gate.requireSuppressionOwner();gate.suppressedCarrier++;}}}
    public static void observedSchedulerSuppression(ServerPlayer player){var gate=find(player);if(gate!=null){gate.requireSuppressionOwner();gate.suppressedScheduler++;}}
    private void requireSuppressionOwner(){
        if(Thread.currentThread()!=owner)throw new SurvivalRefusal("Native suppression observed off its owner thread");
        // Failed/disconnected/removed exact players MUST remain cancellable. Never inspect their live profile here.
        synchronized(OWNED){if(OWNED.get(player)!=this)throw new SurvivalRefusal("Suppression gate was replaced");}
    }
    public record HoldEvidence(long schedulerRoots,long carrierRoots){}
    public HoldEvidence evidence(){requireIdentity();return new HoldEvidence(suppressedScheduler,suppressedCarrier);}
    public static void requireActionOpen(Player player) {
        if(blocksNative(player)){
            var gate=find(player);if(gate==null||gate.playerMove==null)throw new SurvivalRefusal("Exact native session execution gate is closed");
            gate.playerMove.requireActionEntry(gate);
        }
    }
    /** Read-only preflight for the exact ACTIVE Earth claimant; native roots remain held. */
    public void requirePlayerMoveAdmission(Object exact,ServerLevel level){
        requireClaim(exact);if(transitionHeld||!earthExecutionClosed||carrier!=level||player.serverLevel()!=level||failed)
            throw new SurvivalRefusal("Native MOVE requires exact settled ACTIVE Earth claimant");
    }
    public static boolean acceptsPlayerMove(ServerPlayer source){
        var gate=find(source);if(gate==null)return false;
        if(gate.installedAdmission!=null||gate.transitionHeld||!gate.earthExecutionClosed||gate.failed)return false;gate.requireIdentity();return true;
    }
    public void beginPlayerMove(io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost.PlayerMovePermit permit){
        if(playerMove!=null)throw new SurvivalRefusal("Reentrant native MOVE preparation");
        permit.requireCurrent(this);if(transitionHeld||!earthExecutionClosed||failed)throw new SurvivalRefusal("Native MOVE gate closed");playerMove=permit;
    }
    public void failPlayerMove(io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost.PlayerMovePermit permit){
        if(Thread.currentThread()!=owner||permit==null||playerMove!=permit)throw new SurvivalRefusal("Foreign native MOVE failure owner");fail();
    }
    public void endPlayerMove(io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost.PlayerMovePermit permit){
        if(Thread.currentThread()!=owner||permit==null||playerMove!=permit)throw new SurvivalRefusal("Foreign native MOVE release owner");playerMove=null;
    }
    /** Narrow retained-menu admission. The boolean never grants transition-close privilege.
     * A held save/return still refuses until the separately owned pre-transition drain is installed.
     */
    public static void requireRetainedMenuActionOpen(ServerPlayer player,
            io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host,boolean closing) {
        Objects.requireNonNull(player);Objects.requireNonNull(host);
        var gate=find(player);
        if(gate==null)return; // Unregistered component hosts retain their own exact identity checks.
        gate.requireClaim(host);host.requireTransitionHealthy(gate);
        if(gate.transitionHeld || !gate.earthExecutionClosed || gate.failed || gate.playerMove!=null
                || gate.carrier!=player.serverLevel() || gate.saving!=null || gate.mutationDepth!=0
                || gate.teleportOwner!=null || gate.ackScope!=null)
            throw new SurvivalRefusal("Retained native menu requires the exact settled ACTIVE Earth owner");
        var durable=host.transitionDurable(gate);
        if(durable==null || durable.session().phase()!=NativeSurvivalCheckpoint.SessionPhase.ACTIVE)
            throw new SurvivalRefusal("Retained native menu lacks its durable ACTIVE session");
    }
    /** Qualification of one exact host-retained proof; never changes blocksNative/requireActionOpen. */
    public static void requireServerBreakProofOpen(ServerPlayer player,
            io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host) {
        var gate = find(player);
        if (gate == null || !Boolean.getBoolean("earthplatform.nativeSessionBridge")
                || !Boolean.getBoolean("neoforge.gameTestServer") || player.getClass() != ServerPlayer.class
                || player.server.getClass() != GameTestServer.class)
            throw new SurvivalRefusal("Native support break proof is default-off and exact registered GameTest-only");
        gate.requireClaim(host);
        if (gate.transitionHeld || !gate.earthExecutionClosed || gate.failed || gate.playerMove != null || gate.carrier != player.serverLevel()
                || gate.saving != null || gate.mutationDepth != 0 || gate.teleportOwner != null || gate.ackScope != null)
            throw new SurvivalRefusal("Native support break requires the settled ACTIVE execution-closed session");
        host.requireServerBreakProofGate(gate);
    }

    /** Read-only exact registered claimant lookup; this does not open native execution. */
    public io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost miningHost() {
        requireIdentity();return claimant instanceof io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host?host:null;
    }
    public static void requireNativeMiningOpen(ServerPlayer player,
            io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host) {
        var gate=find(player);
        if(gate==null||!Boolean.getBoolean("earthplatform.nativeSessionBridge")||!Boolean.getBoolean("neoforge.gameTestServer")
                ||player.getClass()!=ServerPlayer.class||player.server.getClass()!=GameTestServer.class)
            throw new SurvivalRefusal("Native mining is default-off exact registered GameTest-only");
        gate.requireClaim(host);
        if(gate.transitionHeld||!gate.earthExecutionClosed||gate.failed||gate.playerMove!=null||gate.carrier!=player.serverLevel()
                ||gate.saving!=null||gate.mutationDepth!=0||gate.teleportOwner!=null||gate.ackScope!=null)
            throw new SurvivalRefusal("Native mining requires settled execution-closed ACTIVE session");
        host.requireNativeMiningGate(gate);
    }
    public static void failNativeMining(ServerPlayer player,
            io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host) {
        var gate=find(player);if(gate==null)throw new SurvivalRefusal("Missing mining failure gate");
        gate.requireClaim(host);gate.fail();
    }

    public static boolean blocksSave(Player player,PlayerDataStorage storage) {
        NativeSessionExecutionGate gate=find(player);
        if(gate==null)return false;
        // Observer scope is owner-thread-only. Nested saves are still rejected by the observer.
        return (gate.transitionHeld || gate.earthExecutionClosed || gate.failed)
                && !(Thread.currentThread()==gate.owner && gate.saving==storage && !gate.failed);
    }
    public void claim(Object exact) {
        requireHeld(); if(claimant!=null || exact==null)throw new SurvivalRefusal("Transition gate already claimed"); claimant=exact;
    }
    public void requireClaim(Object exact) { requireIdentity(); if(claimant!=exact || exact==null)throw new SurvivalRefusal("Foreign transition gate claimant"); }
    public ServerPlayer player(){requireIdentity();return player;}
    public ServerLevel ordinaryLevel(){requireIdentity();return ordinary;}
    public void requireIdentity() {
        if(Thread.currentThread()!=owner || !player.server.isSameThread() || player.getClass()!=ServerPlayer.class
                || player.connection!=connection || connection.getClass()!=ServerGamePacketListenerImpl.class || player.registryAccess()!=registries
                || player.server.reloadableRegistries()!=reloadable || player.hasDisconnected() || player.isRemoved()
                || !player.isAlive() || failed)
            throw new SurvivalRefusal("Native held player/connection/registry identity changed or failed");
    }
    public void requireHeld() { requireIdentity();if(!transitionHeld)throw new SurvivalRefusal("Exact transition hold is not installed"); }
    public void hold(Object exact) {requireClaim(exact);
        if(exact instanceof io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost host)host.requireMiningSettledBeforeHold(this);
        transitionHeld=true;}
    void enterEarth(Object exact){requireClaim(exact);requireHeld();earthExecutionClosed=true;}
    void restoredOrdinary(Object exact){requireClaim(exact);requireHeld();if(player.serverLevel()!=ordinary)throw new SurvivalRefusal("Wrong ordinary Level");earthExecutionClosed=false;}
    /** Prevalidated field writes only; ACTIVE retains the independent unqualified execution fence. */
    void releaseFields(){transitionHeld=false;}
    void fail(){failed=true;transitionHeld=true;}
    void beginNativeMutation(Object exact){requireClaim(exact);requireHeld();if(mutationDepth++!=0){fail();throw new SurvivalRefusal("Reentrant native session mutation");}}
    void endNativeMutation(Object exact){
        if(Thread.currentThread()!=owner || claimant!=exact || mutationDepth!=1){fail();throw new SurvivalRefusal("Native mutation scope changed");}mutationDepth=0;
    }
    NativeOrdinarySaveObserver.Completion saveOrdinary(NativeOrdinarySaveObserver.Authority authority) {
        requireHeld();
        if(saving==null || authority.player()!=player || authority.storage()!=saving)
            throw new SurvivalRefusal("Exact active native ordinary-save scope required");
        return installedAdmission==null ? NativeOrdinarySaveObserver.save(authority)
                : NativeOrdinarySaveObserver.save(authority,installedAdmission,this);
    }
    void beginSave(Object exact,PlayerDataStorage storage){requireClaim(exact);requireHeld();if(saving!=null){fail();throw new SurvivalRefusal("Nested bridge save");}saving=Objects.requireNonNull(storage);}
    void endSave(Object exact){if(Thread.currentThread()!=owner || claimant!=exact){fail();throw new SurvivalRefusal("Save scope owner changed");}saving=null;}
    void expectTeleport(Object operation,int id) {
        requireHeld();if(teleportOwner!=null)throw new SurvivalRefusal("Teleport acknowledgement already owned");
        teleportOwner=operation;teleportId=id;ackScope=new NativeSessionAckScope();
    }
    /** Called only by the exact pinned original native-handler wrapper. Netty scheduling retains vanilla behavior. */
    public static void runTeleportAck(ServerGamePacketListenerImpl receiver,ServerPlayer player,int id,Runnable original) {
        NativeSessionExecutionGate gate=find(player);
        if(gate==null){original.run();return;}
        try{
            // Object/field identity only, also before vanilla's off-thread queue handoff.
            NativeSessionAckScope.requireReceiver(receiver,gate.connection,player.connection,receiver.player,gate.player);
        }catch(RuntimeException | Error failure){gate.fail();throw failure;}
        // Native PacketUtils uses an exception to complete its normal Netty-to-owner handoff.
        // Delegate outside the scoped-failure catch: that scheduled handoff is not a failed ack.
        if(Thread.currentThread()!=gate.owner || gate.teleportOwner==null || id!=gate.teleportId){original.run();return;}
        try{gate.requireIdentity();gate.ackScope.run(()->{gate.requireIdentity();original.run();gate.requireIdentity();});}
        catch(RuntimeException | Error failure){gate.fail();throw failure;}
    }
    boolean consumeTeleportAck(Object operation,int id) {
        requireHeld();if(teleportOwner!=operation || teleportId!=id || ackScope==null)throw new SurvivalRefusal("Foreign native teleport receipt");
        if(!ackScope.succeeded())return false;teleportOwner=null;ackScope=null;return true;
    }
    public static void rejectForeignKinematicWrite(net.minecraft.world.entity.Entity entity) {
        if(!(entity instanceof ServerPlayer player))return;
        NativeSessionExecutionGate gate=find(player);
        if(gate!=null && blocksNative(player) && !(Thread.currentThread()==gate.owner && (gate.mutationDepth==1 || gate.ackScope!=null && gate.ackScope.permits()
                || gate.playerMove!=null&&io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.permitsKinematics(entity)))) {
            gate.fail();throw new SurvivalRefusal("Foreign native kinematic mutation attempted while exact player held");
        }
    }
    public boolean isNativeMutation(){return Thread.currentThread()==owner&&mutationDepth==1;}
}
