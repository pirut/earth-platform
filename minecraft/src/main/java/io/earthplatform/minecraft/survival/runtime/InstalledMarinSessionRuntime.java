package io.earthplatform.minecraft.survival.runtime;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.host.*;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost;
import io.earthplatform.minecraft.survival.persistence.*;
import io.earthplatform.minecraft.survival.source.*;
import io.earthplatform.minecraft.survival.world.*;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/** One exact installed owner graph. The callback audit stub currently refuses before creation.
 * After approval this uses the existing logged-in player, original native connection, stageHeld,
 * transition controller and checkpoint coordinator. No synthetic actor, fake ACK or second login.
 * ACTIVE here is HELD INSPECTION. Native gameplay roots remain closed.
 */
public final class InstalledMarinSessionRuntime {
    private static final Map<MinecraftServer,InstalledMarinSessionRuntime> SERVERS=new IdentityHashMap<>();
    private static final Map<ServerPlayer,InstalledMarinSessionRuntime> PLAYERS=new IdentityHashMap<>();
    private static final int NATIVE_BYTES=256*1024;
    private static final Limits CHECKPOINT_LIMITS=new Limits(64,16,16,8,4*1024*1024);
    private static final SurvivalCheckpointStore.Limits STORE_LIMITS=new SurvivalCheckpointStore.Limits(1,4*1024*1024,4*1024*1024,4096);
    private final InstalledMarinSessionAdmission.Token admission;
    private final ServerPlayer player;
    private final ServerGamePacketListenerImpl connection;
    private final AtomicReference<Input> inbox=new AtomicReference<>();
    private volatile ControlIdentity control;
    private InstalledMarinSessionStoreIdentity.Opened identity;
    private NativeDistanceCpuBudget.Reservation metadataCharge, worldCharge, checkpointCharge, ordinarySaveCharge;
    private SurvivalCarrierTicketOwner tickets;
    private NativeSurvivalWorld world;
    private SurvivalPalette palette;
    private SurvivalCheckpointStore store;
    private SurvivalCheckpointCodec codec;
    private ThreadPoolExecutor writer;
    private NativeSessionExecutionGate gate;
    private NativeEffectDeliveryHost host;
    private SurvivalLevelBinding binding;
    private SurvivalCheckpointCoordinator checkpoints;
    private NativeSessionBridge bridge;
    private SurvivalSessionTransitions transitions;
    private Throwable firstFailure, lastCheckpointFailure;
    private long inputSequence;
    private SurvivalSessionTransitions.State lastReported;
    private boolean preparing, stopping, storeClosed, ticketsReleased;
    private record ControlIdentity(UUID session, UUID epoch, long nextSequence) {}
    private record Input(ControlIdentity identity, InstalledSessionControlProtocol.Action operation, long sequence) {}
    private InstalledMarinSessionRuntime(InstalledMarinSessionAdmission.Token admission) {
        this.admission=admission; player=admission.player(); connection=player.connection;
    }

    /** Explicit entry port. The coordinator supplies the one already-charged scene source owner.
     * It must not load another definition/source copy for simulation or scene capture.
     */
    public static void requestEntry(ServerPlayer actualPlayer, SurvivalSceneSourceAssets exactAssets) throws IOException {
        InstalledMarinSessionAdmission.requireLaunch();
        final InstalledMarinSessionRuntime existing;
        synchronized (SERVERS) { existing=SERVERS.get(actualPlayer.server); }
        if (existing!=null) { existing.reenter(actualPlayer,exactAssets); return; }
        var approval=InstalledMarinSessionAdmission.prepare(actualPlayer,exactAssets);
        var runtime=new InstalledMarinSessionRuntime(approval);
        // Retain the owner before opening store/writer/ticket resources. A failed prefix is never replaced.
        synchronized (SERVERS) {
            if (SERVERS.putIfAbsent(actualPlayer.server,runtime)!=null) throw new SurvivalRefusal("Installed server owner already retained");
        }
        synchronized (PLAYERS) { PLAYERS.put(actualPlayer,runtime); }
        try { runtime.prepare(); }
        catch (IOException | RuntimeException | Error failure) { runtime.fail(failure); throw failure; }
    }
    private void prepare() throws IOException {
        admission.requireCurrent();
        var carrier=admission.carrier(); var assets=admission.assets();
        var root=player.server.getWorldPath(LevelResource.ROOT);
        if (InstalledMarinSessionStoreIdentity.requiresStartupRecovery(root,MarinPlayerEnvironmentV2.PROFILE,player.getUUID()))
            throw new SurvivalRefusal("Existing installed save requires explicit startup recovery; no old escrow was restored");
        var budget=NativeDistanceCpuBudget.global();
        metadataCharge=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,2L<<20);
        ordinarySaveCharge=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,24L<<20);
        admission.retainOrdinarySaveCharge(ordinarySaveCharge);
        worldCharge=budget.reserve(NativeDistanceCpuBudget.Pool.SOURCE,16L<<20);
        checkpointCharge=budget.reserve(NativeDistanceCpuBudget.Pool.CAPSULE,12L<<20);
        palette=new SurvivalPalette(assets.source().palette().context());
        var definition=assets.source().definition(); var envelope=definition.envelope();
        long seed=admission.nativeSeed();
        world=new NativeSurvivalWorld(MarinPlayerEnvironmentV2.worldIdentity(palette.identity(),seed),palette,
                new NativeSurvivalWorld.Bounds(new CubePos(envelope.minCenterX(),envelope.minCenterY(),envelope.minCenterZ()),
                        new CubePos(envelope.maxCenterXExclusive()-1,envelope.maxCenterYExclusive()-1,envelope.maxCenterZExclusive()-1)),
                new NativeSurvivalWorld.Limits(64,64,8,64,8192,128,128,262144,8,64));
        // Reuse the identical immutable definition already owned by the single charged source.
        var source=new MarinNativeCubeSource(definition,palette);
        var center=CubePos.fromBlock(MarinSurvivalDefinition.ANCHOR_X,135,MarinSurvivalDefinition.ANCHOR_Z);
        for (int x=-1;x<=1;x++) for (int y=-1;y<=1;y++) for (int z=-1;z<=1;z++) {
            var pos=center.offset(x,y,z); world.admitGenerated(pos,source.generate(pos,new CancellationToken()));
        }
        var packs=SurvivalDataPackIdentity.capture(player.server.getResourceManager(),palette.identity(),admission.artifacts().digest(),
                new SurvivalDataPackIdentity.Limits(16,20000,30000,4194304,134217728));
        var spec=new InstalledMarinSessionStoreIdentity.Spec(MarinPlayerEnvironmentV2.PROFILE,world.world(),
                MarinPlayerEnvironmentV2.sourceIdentity(palette.identity(),seed),palette.identity(),packs.digest(),
                player.getUUID(),carrier.dimension().location(),seed);
        identity=InstalledMarinSessionStoreIdentity.open(root,spec);
        codec=new SurvivalCheckpointCodec(identity.binding(),CHECKPOINT_LIMITS,carrier.registryAccess(),palette,world.bounds());
        store=new SurvivalCheckpointStore(identity.checkpointDirectory(),codec.storageIdentity(),STORE_LIMITS);
        if (store.load().isPresent()) throw new SurvivalRefusal("Installed restart recovery is not implemented; refusing a fresh world over CURRENT");
        writer=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),
                task->{var thread=new Thread(null,task,"installed-marin-checkpoint-writer",512L*1024);thread.setDaemon(true);return thread;},
                new ThreadPoolExecutor.AbortPolicy());
        tickets=new SurvivalCarrierTicketOwner(admission); preparing=true; tickets.acquire();
        player.sendSystemMessage(Component.literal("Preparing held Earth inspection. Ordinary play stays unchanged until entry is ready."));
    }
    private void stage() throws IOException {
        admission.requireCurrent(); tickets.saveAndSeal();
        // Last pure ordinary check occurs after asynchronous native transport preparation.
        var ordinary=admission.captureOrdinary();
        NativeSessionAdmission.requireOrdinaryExact(player,identity.binding(),ordinary);
        var empty=new NativeInventorySnapshot(Collections.nCopies(NativeInventorySnapshot.SLOTS,
                NativeStackSnapshot.capture(ItemStack.EMPTY,player.registryAccess())),0);
        var food=new NativeFoodSnapshot(20,0,5F,0F);
        var pose=new NativePlayerPoseSnapshot(admission.carrier().dimension().location(),MarinSurvivalDefinition.ANCHOR_X+0.5D,135D,
                MarinSurvivalDefinition.ANCHOR_Z+0.5D,0F,0F,0D,0D,0D,0F,true,GameType.SURVIVAL,null,
                new NativePlayerPoseSnapshot.AbilityState(false,false,false,false,true,0.05F,0.1F));
        Boundary initial=new Boundary(admission.carrier().getGameTime(),empty,food,pose,List.of(),List.of());
        gate=NativeSessionExecutionGate.installHeldInstalled(player,admission);
        host=NativeEffectDeliveryHost.stageHeld(admission.carrier(),player,world,player.registryAccess(),
                new NativeEffectDeliveryHost.Limits(16,16,32,1),CHECKPOINT_LIMITS,ordinary,NATIVE_BYTES,gate,initial);
        binding=new SurvivalLevelBinding(admission.carrier(),world,host.actions(),NativeDryMutationRouter.forOwnedHost(host,4096));
        ((SurvivalLevelAccess)admission.carrier()).earthplatform$bindSurvival(binding);
        tickets.bind(binding); host.attachBinding(binding);
        checkpoints=new SurvivalCheckpointCoordinator(host.actions(),store,STORE_LIMITS,codec,checkpointCharge);
        host.attachCheckpointCoordinator(checkpoints);
        bridge=new NativeSessionBridge(host,gate,writer,NATIVE_BYTES);
        transitions=new SurvivalSessionTransitions(bridge);
        transitions.enter(); preparing=false;
    }
    private void reenter(ServerPlayer samePlayer, SurvivalSceneSourceAssets sameAssets) {
        requireHealthy(); admission.requireCurrent();
        if (samePlayer!=player || sameAssets!=admission.assets() || transitions==null
                || transitions.state()!=SurvivalSessionTransitions.State.RETURNED || stopping)
            throw new SurvivalRefusal("Reentry requires the same returned installed player, source and retained owner graph");
        admission.captureOrdinary(); // Current ordinary inventory, never the preceding session escrow.
        transitions.enter(); publishControl();
    }
    /** Called by a reviewed exact IntegratedServer owner-tick hook, including its paused route.
     * No manual Level tick, blocking wait, worker-thread native access or fabricated packet receipt.
     */
    public static void ownerTick(MinecraftServer server) {
        final InstalledMarinSessionRuntime runtime; synchronized (SERVERS) { runtime=SERVERS.get(server); }
        if (runtime==null) return;
        runtime.tick();
    }
    private void tick() {
        if (!player.server.isSameThread()) throw new SurvivalRefusal("Installed transition poll is off native owner thread");
        // Integrated paused ticks retain getTickCount(); still permit the one-slot STATUS/RETURN lane.
        if (firstFailure!=null) { consumeFailedStatus(); return; }
        try {
            admission.requireCurrent();
            if (stopping) { pollFinalStop(); return; }
            consumeInput();
            if (preparing) { if (tickets.ready()) stage(); return; }
            if (transitions==null) return;
            transitions.tick();
            ControlIdentity beforeControl=control; publishControl();
            if (control!=null && (beforeControl==null || !beforeControl.session().equals(control.session()))) sendControls();
            Throwable checkpointFailure=transitions.checkpointFailure();
            if (checkpointFailure!=null && checkpointFailure!=lastCheckpointFailure) {
                player.sendSystemMessage(Component.literal("The exact frozen checkpoint write failed. The player remains held; only that retained checkpoint can be retried."));
                sendControls();
            }
            lastCheckpointFailure=checkpointFailure;
            var state=transitions.state();
            if (state==SurvivalSessionTransitions.State.FAILED || state==SurvivalSessionTransitions.State.RECOVERY_REQUIRED)
                throw new SurvivalRefusal("Installed transition retained "+state+": "+transitions.failure());
            if (state!=lastReported && (state==SurvivalSessionTransitions.State.ACTIVE || state==SurvivalSessionTransitions.State.RETURNED)) {
                lastReported=state;
                player.sendSystemMessage(Component.literal(state==SurvivalSessionTransitions.State.ACTIVE
                        ? "Earth is held for inspection. Walking, mining and ordinary gameplay ticks remain unavailable."
                        : "Returned to ordinary play on the same connection. Earth and the current ordinary save are durable."));
                sendControls();
            }
        } catch (IOException | RuntimeException | Error failure) { fail(failure); }
    }
    /** Coordinator-only server-thread port. Do not hand live inputs to a source/render worker.
     * Stage C must retire its complete renderer/worker generation before invoking native return;
     * this A/B runtime does not itself install a scene or claim such a retirement proof.
     */
    record ScenePort(SurvivalLevelBinding binding, SurvivalCheckpointCoordinator checkpoints,
                     SurvivalSceneSourceAssets assets, InstalledMarinSessionAdmission.SceneCompatibility compatibility) {}
    static ScenePort scenePort(ServerPlayer samePlayer) {
        final InstalledMarinSessionRuntime runtime; synchronized (PLAYERS) { runtime=PLAYERS.get(samePlayer); }
        if (runtime==null) throw new SurvivalRefusal("No installed scene session owner");
        runtime.requireHealthy(); runtime.admission.requireCurrent();
        if (runtime.stopping || runtime.transitions==null || runtime.transitions.state()!=SurvivalSessionTransitions.State.ACTIVE)
            throw new SurvivalRefusal("Installed scene port requires the exact settled held ACTIVE owner");
        return new ScenePort(runtime.binding,runtime.checkpoints,runtime.admission.assets(),
                InstalledMarinSessionAdmission.sceneCompatibility(runtime.admission,runtime.world,runtime.checkpoints));
    }

    /** Separates logical writer/store/ticket completion from the engine's later physical shutdown.
     * Runtime/world/binding/source aliases and their charges remain retained through process life.
     */
    public record CloseStatus(boolean requested, boolean writerTerminated, boolean storeClosed,
                              boolean ownedTicketsReleased, boolean nativeLevelClosureVerified) {}
    public static CloseStatus requestFinalStop(ServerPlayer samePlayer) {
        final InstalledMarinSessionRuntime runtime; synchronized (PLAYERS) { runtime=PLAYERS.get(samePlayer); }
        if (runtime==null) throw new SurvivalRefusal("No installed owner exists for final shutdown");
        runtime.requireHealthy(); runtime.admission.requireCurrent();
        if (runtime.transitions==null || runtime.transitions.state()!=SurvivalSessionTransitions.State.RETURNED)
            throw new SurvivalRefusal("Final shutdown requires the exact clean RETURNED owner; unfinished owners remain retained");
        if (!runtime.stopping) { runtime.stopping=true; runtime.writer.shutdown(); }
        return runtime.closeStatus();
    }
    public static CloseStatus finalStopStatus(ServerPlayer samePlayer) {
        final InstalledMarinSessionRuntime runtime; synchronized (PLAYERS) { runtime=PLAYERS.get(samePlayer); }
        if (runtime==null || !samePlayer.server.isSameThread()) throw new SurvivalRefusal("Final shutdown status requires exact native owner thread");
        return runtime.closeStatus();
    }
    private CloseStatus closeStatus() {
        return new CloseStatus(stopping,writer!=null && writer.isTerminated(),storeClosed,ticketsReleased,false);
    }
    private void pollFinalStop() throws IOException {
        if (!writer.isTerminated()) return;
        if (transitions.state()!=SurvivalSessionTransitions.State.RETURNED || checkpoints.state()!=SurvivalCheckpointCoordinator.State.IDLE
                || checkpoints.pendingTicket().isPresent() || world.hasPendingCheckpoint() || world.hasActiveTransaction()
                || host.actions().hasCurrentAction() || host.actions().fenced() || world.hasUncheckpointedActions()
                || !world.dirtyPositions().isEmpty() || world.pinCount()!=0 || player.serverLevel()!=admission.ordinary())
            throw new SurvivalRefusal("Final installed session owners have not genuinely drained");
        if (!ticketsReleased) {
            if (!tickets.physicalWritesSettled()) return;
            tickets.releaseForFinalStop(); ticketsReleased=true;
        }
        if (!storeClosed) { store.close(); storeClosed=true; }
        // No implicit binding detach, collection clear or budget reset: native Level/I/O close
        // and retained source/capture aliases are separate lifetimes and have not been proved here.
    }
    private void publishControl() {
        UUID session=transitions.sessionId();
        if (session==null) return;
        ControlIdentity old=control;
        if (old==null || !old.session().equals(session)) inputSequence=0;
        control=new ControlIdentity(session,world.ownerEpoch(),Math.incrementExact(inputSequence));
    }
    /** Packet HEAD ingress: only exact object equality, immutable parsing and a single CAS slot.
     * This method never dispatches arbitrary commands and does not touch native player fields off-thread.
     */
    public static boolean offerControl(ServerGamePacketListenerImpl receiver,ServerPlayer player,String command) {
        final InstalledMarinSessionRuntime runtime; synchronized (PLAYERS) { runtime=PLAYERS.get(player); }
        if (runtime==null || receiver!=runtime.connection || player!=runtime.player || command==null
                || command.length()>160 || !command.startsWith("earthsurvival_control ")) return false;
        var frame=InstalledSessionControlProtocol.parse(command);
        if (frame==null) return true;
        ControlIdentity expected=runtime.control;
        if (expected==null || !frame.session().equals(expected.session()) || !frame.epoch().equals(expected.epoch())
                || frame.sequence()!=expected.nextSequence()) return true;
        runtime.inbox.compareAndSet(null,new Input(expected,frame.action(),frame.sequence()));
        return true;
    }
    private void consumeInput() {
        Input input=inbox.getAndSet(null); if (input==null) return;
        ControlIdentity current=control;
        if (current==null || !current.equals(input.identity()) || input.sequence()!=Math.incrementExact(inputSequence)) return;
        inputSequence=input.sequence(); publishControl();
        if (input.operation()==InstalledSessionControlProtocol.Action.STATUS) { sendControls(); return; }
        if (input.operation()==InstalledSessionControlProtocol.Action.RETRY_CHECKPOINT) {
            if (transitions!=null && transitions.checkpointFailure()!=null) transitions.retryCheckpoint();
            else player.sendSystemMessage(Component.literal("There is no exact retained checkpoint failure to retry."));
            sendControls(); return;
        }
        if (transitions==null || transitions.state()!=SurvivalSessionTransitions.State.ACTIVE) {
            player.sendSystemMessage(Component.literal("Return is available after held entry settles.")); sendControls(); return;
        }
        try { transitions.leave(); sendControls(); }
        catch (SurvivalRefusal refusal) {
            if (transitions.state()!=SurvivalSessionTransitions.State.ACTIVE || !transitions.allowsEarthActions()) throw refusal;
            player.sendSystemMessage(Component.literal(refusal.getMessage())); sendControls();
        }
    }
    private void sendControls() {
        ControlIdentity value=control; if (value==null) return;
        var status=Component.literal("Status").withStyle(style->style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,command("status",value))));
        var leave=Component.literal("Return to ordinary").withStyle(style->style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,command("return",value))));
        var message=Component.literal("Held Earth: "+(firstFailure==null?transitions.state():"FAILED, exact owner retained")+". ").append(status).append(" | ").append(leave);
        if (firstFailure==null && transitions.checkpointFailure()!=null)
            message.append(" | ").append(Component.literal("Retry this checkpoint").withStyle(style->style.withClickEvent(
                    new ClickEvent(ClickEvent.Action.RUN_COMMAND,command("retry_checkpoint",value)))));
        player.sendSystemMessage(message);
    }
    private static String command(String action,ControlIdentity value) {
        return "/earthsurvival_control "+action+" "+value.session()+" "+value.epoch()+" "+value.nextSequence();
    }
    private void consumeFailedStatus() {
        if (player.hasDisconnected() || player.connection!=connection) { inbox.set(null); return; }
        Input input=inbox.getAndSet(null);
        if (input!=null && input.operation()==InstalledSessionControlProtocol.Action.STATUS && input.identity().equals(control)
                && input.sequence()==Math.incrementExact(inputSequence)) {
            inputSequence=input.sequence(); publishControl(); sendControls();
        }
    }
    private void fail(Throwable failure) {
        if (firstFailure==null) firstFailure=failure;
        if (gate!=null) gate.failInstalled(admission);
        // Store, writer, tickets, world, source, native player and every failed prefix remain retained.
        if (!player.hasDisconnected()) player.sendSystemMessage(Component.literal("Held Earth setup/session refused: "+firstFailure.getMessage()+". Exact owners are retained; no automatic recovery was attempted."));
    }
    private void requireHealthy() { if (firstFailure!=null) throw new SurvivalRefusal("Exact failed installed session is retained: "+firstFailure); }
}
