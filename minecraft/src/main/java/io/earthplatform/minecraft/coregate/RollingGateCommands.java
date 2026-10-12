package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.enginebridge.BoundedProviderPreparation;
import io.earthplatform.minecraft.enginebridge.NativeKnownCubeQueries;
import io.earthplatform.minecraft.enginebridge.NativeRayEditPlanner;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneCommands;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Integrated, bounded rolling diagnostic gate. Only this server thread publishes collision. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID)
public final class RollingGateCommands {
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final CoreRecoverySlot<ServerPlayer> RECOVERY = new CoreRecoverySlot<>();
    private static final CoreRayWorkQueue RAY_WORKER = new CoreRayWorkQueue();
    private static Session session;
    /** Owner-thread state only. The background source captures none of these mutable owners. */
    private static PendingOpen pendingOpen;
    private static PendingPlayable pendingPlayable;
    private static PendingDistance pendingDistance;
    private static NativeDistanceProgressWatchdog.Checkpoint lastDistanceProgress;
    private static final class PendingDistance {
        final Session old;final ServerPlayer player;final ServerGamePacketListenerImpl connection;
        final Vec3 position;final GameType mode;final boolean noPhysics,walking;final NativeDistanceRestartGate gate;
        final NativeDistanceProgressState progress;
        boolean preparing,failureReported;long retryAfterNanos;
        PendingDistance(Session old,ServerPlayer player,NativeDistanceChangePayload request){
            this.old=old;this.player=player;connection=player.connection;position=player.position();
            mode=player.gameMode.getGameModeForPlayer();noPhysics=player.noPhysics;walking=old.walking;gate=new NativeDistanceRestartGate(request);
            progress=old.continuation&&old.committedManifest==null&&old.continuationProgress!=null?old.continuationProgress:new NativeDistanceProgressState(request,true,0,false,player.server.getTickCount());
            if(old.continuation&&old.committedManifest==null&&old.continuationPosition!=null&&position.distanceToSqr(old.continuationPosition)>=1e-12)progress.watchdog.fail("held-position-changed");
            progress.request(request);
        }
    }
    private record PendingPlayable(MinecraftServer server,UUID player,ServerGamePacketListenerImpl connection,
            ResourceKey<Level> dimension,GameType mode,Vec3 position,long challenge,long deadlineNanos,PendingDistance continuation,long serial) {}
    private record PendingOpen(MinecraftServer server, UUID player, ServerPlayer requestedPlayer,
                               ServerGamePacketListenerImpl connection, ResourceKey<Level> sourceDimension,
                               GameType sourceMode, long epoch, EnginePaletteContext palette,
                               BoundedProviderPreparation.Ticket<RollingEarthPreparation.Prepared> ticket, boolean alpine,PendingDistance continuation) {}
    private record PreparationEvidence(String workerThread,long elapsedNanos) {
        static PreparationEvidence analytic(){return new PreparationEvidence("analytic-no-source-bootstrap",0);}
    }
    private record PendingRay(CompletableFuture<NativeRayEditPlanner.Result> result,
                              AtomicBoolean cancelled, Vec3 eye, Vec3 look) {}

    private static final class Session {
        final RollingSessionDescriptor descriptor;
        final PreparationEvidence preparation;
        final MinecraftServer server;
        final UUID player;
        final ServerGamePacketListenerImpl connection;
        long distancePredecessorWireEpoch,distancePredecessorWireSerial;
        NativeDistanceProgressState continuationProgress;Vec3 continuationPosition;
        boolean distanceCleanupBlocked;
        final RollingCubeActor actor;
        final CoreReturnPoint origin;
        final long epoch;
        final EnginePaletteContext palette;
        final WorldIdentity world;
        final RollingPublicationHandshake handshake;
        final boolean replacementEnabled;
        long replacementDeclines;
        final CorePhysicsTrace physics = new CorePhysicsTrace();
        final Map<CubePos, EngineCubeSnapshot> snapshots = new HashMap<>();
        CoreCollisionBinding collision;
        RollingCubeActor.StageMetadata stage;
        RollingCubeActor.StageReady pendingManifest, committedManifest;
        CubePos committedCenter, latestGoal, editTarget;
        RollingFarPublication.Center desiredFarCenter, pendingFarCenter, publishedFarCenter;
        PendingRay ray;
        boolean continuation, stopping, walking, editPending, editPublication, intentReceived, feedbackSent, waitingForBody, motionCommitQueued, motionActorCommitted;
        long lastIntentNanos, lastFeedbackNanos;
        int physicsReports;
        RollingArenaScenario arenaScenario;RollingArenaScenario.Action arenaAction;
        RollingArenaScenario.Outcome arenaOutcome=RollingArenaScenario.Outcome.NONE;
        final List<RollingArenaScenario.TravelStep> arenaTravel=new ArrayList<>();
        RollingIndexedScenario scenario;RollingIndexedScenario.Action scenarioAction;
        RollingIndexedScenario.Outcome scenarioOutcome=RollingIndexedScenario.Outcome.NONE;
        final List<RollingIndexedScenario.TravelStep> scenarioTravel=new ArrayList<>();
        java.nio.file.Path scenarioRoot,scenarioDirectory;
        RollingScenarioClientWitness scenarioClientWitness;
        long scenarioCompletedAction,scenarioActionPublication,scenarioCaptureStarted;
        int scenarioCheckpoints,scenarioScreenshotAcks;boolean scenarioActionStarted,scenarioTerminalWritten,scenarioWriting;
        String scenarioCapture="",scenarioFailure="";RollingScenarioCheckpoint scenarioCheckpoint;CubePos scenarioTravelGoal,scenarioTravelNext;
        Session(ServerPlayer p, RollingCubeActor actor, CoreReturnPoint origin, long epoch,
                EnginePaletteContext palette, RollingSessionDescriptor descriptor, PreparationEvidence preparation) {
            this.descriptor=descriptor; this.preparation=preparation; this.server=p.server; this.player=p.getUUID(); this.connection=p.connection; this.actor=actor;
            this.origin=origin; this.epoch=epoch; this.palette=palette; this.world=descriptor.world();
            this.handshake=new RollingPublicationHandshake(epoch);
            this.replacementEnabled=descriptor.alpineMode()||descriptor.arenaMode()||!descriptor.naturalRelief()&&Boolean.getBoolean(RollingGateFixture.REPLACEMENT_OPT_IN);
            this.desiredFarCenter=RollingFarPublication.center(descriptor,descriptor.initialCenter());
        }
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("cubic")
            .then(Commands.literal("enter").executes(c->requestPlayable(c.getSource().getPlayerOrException())))
            .then(Commands.literal("leave").executes(c->leave(c.getSource().getPlayerOrException())))
            .then(Commands.literal("walk").executes(c->walk(c.getSource().getPlayerOrException(),true)))
            .then(Commands.literal("fly").executes(c->walk(c.getSource().getPlayerOrException(),false)))
            .then(Commands.literal("verify").requires(c->c.hasPermission(2))
                .then(Commands.literal("run").executes(c->arenaScenario(c.getSource().getPlayerOrException(),RollingArenaScenario.Mode.EXERCISE)))
                .then(Commands.literal("after_reload").executes(c->arenaScenario(c.getSource().getPlayerOrException(),RollingArenaScenario.Mode.AFTER_RELOAD)))
                .then(Commands.literal("after_restart").executes(c->arenaScenario(c.getSource().getPlayerOrException(),RollingArenaScenario.Mode.AFTER_RESTART))))
            .then(Commands.literal("status").executes(c->{var p=c.getSource().getPlayerOrException();say(p,io.earthplatform.compat.dh.EarlyPlayableAdmission.explanation());return status(p);})));
        e.getDispatcher().register(Commands.literal("earth")
            .then(Commands.literal("enter").executes(c->{var p=c.getSource().getPlayerOrException();if(!io.earthplatform.compat.PlayableProfile.selection().earth()){say(p,"Select the finite-everest-shader-v1 installation profile and restart before entering Earth.");return 0;}return requestPlayable(p);}))
            .then(Commands.literal("leave").executes(c->leave(c.getSource().getPlayerOrException())))
            .then(Commands.literal("walk").executes(c->walk(c.getSource().getPlayerOrException(),true)))
            .then(Commands.literal("fly").executes(c->walk(c.getSource().getPlayerOrException(),false)))
            .then(Commands.literal("status").executes(c->status(c.getSource().getPlayerOrException()))));
        e.getDispatcher().register(Commands.literal("earth_roam").requires(s -> s.hasPermission(2))
            .then(Commands.literal("enter").executes(c -> enter(c.getSource().getPlayerOrException(), CoreGateProfile.BASE))
                .then(Commands.literal("abyss").executes(c -> enter(c.getSource().getPlayerOrException(), CoreGateProfile.ABYSS)))
                .then(Commands.literal("summit").executes(c -> enter(c.getSource().getPlayerOrException(), CoreGateProfile.SUMMIT)))
                .then(Commands.literal("earth").executes(c -> enterEarth(c.getSource().getPlayerOrException()))))
            .then(Commands.literal("focus").then(Commands.literal("original").executes(c -> focus(c.getSource().getPlayerOrException(),false))).then(Commands.literal("second").executes(c -> focus(c.getSource().getPlayerOrException(),true))))
            .then(Commands.literal("walk").executes(c -> walk(c.getSource().getPlayerOrException(), true)))
            .then(Commands.literal("fly").executes(c -> walk(c.getSource().getPlayerOrException(), false)))
            .then(Commands.literal("leave").executes(c -> leave(c.getSource().getPlayerOrException())))
            .then(Commands.literal("scenario12")
                .then(Commands.literal("run").executes(c->scenario(c.getSource().getPlayerOrException(),RollingIndexedScenario.Mode.EXERCISE)))
                .then(Commands.literal("after_reload").executes(c->scenario(c.getSource().getPlayerOrException(),RollingIndexedScenario.Mode.AFTER_RELOAD)))
                .then(Commands.literal("after_restart").executes(c->scenario(c.getSource().getPlayerOrException(),RollingIndexedScenario.Mode.AFTER_RESTART))))
            .then(Commands.literal("status").executes(c -> status(c.getSource().getPlayerOrException()))));
    }

    private static int requestPlayable(ServerPlayer p) {
        if(!io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled()){say(p,io.earthplatform.compat.dh.EarlyPlayableAdmission.explanation());return 0;}
        if(!mayEnter(p))return 0;
        if(pendingPlayable!=null){say(p,"A playable entry check is already pending");return 0;}
        long challenge=EPOCH.incrementAndGet();pendingPlayable=new PendingPlayable(p.server,p.getUUID(),p.connection,p.level().dimension(),p.gameMode.getGameModeForPlayer(),p.position(),challenge,System.nanoTime()+60_000_000_000L,null,0);
        PacketDistributor.sendToPlayer(p,new PlayableEntryPayload(challenge,PlayableEntryPayload.REQUEST,0,""));
        say(p,"Checking installed shaders and block resources. Stay still while the landscape opens.");return 1;
    }
    private static void playableEntryReply(ServerPlayer p,PlayableEntryPayload reply) {
        var request=pendingPlayable;
        if(reply.kind()==PlayableEntryPayload.REQUEST||request==null||request.server()!=p.server||!request.player().equals(p.getUUID())||request.connection()!=p.connection||reply.challenge()!=request.challenge())return;
        pendingPlayable=null;
        if(request.continuation()!=null){
            var pending=request.continuation();
            pending.progress.serverTick(p.server.getTickCount(),NativeDistanceProgressState.serverClock(p.server,pending.progress.watchdog.episode()));
            if(pending.progress.watchdog.failed()){distanceFailed(pending,p,pending.progress.watchdog.failure());return;}
            if(pendingDistance!=pending||pending.gate.latest().serial()!=request.serial())return;
            if(reply.kind()!=PlayableEntryPayload.READY){distanceFailed(pending,p,"Fresh shader/model preflight refused: "+reply.reason());return;}
            if(pending.progress.watchdog.failed()||!distanceOwnerCurrent(pending,p)||!pending.gate.ready()){distanceFailed(pending,p,"Continuation changed during shader/model preflight");return;}
            pending.progress.credit(NativeDistanceProgressWatchdog.Lane.PREFLIGHT,1);
            try{
                var palette=pending.old.palette;
                var ticket=BoundedProviderPreparation.submit(new BoundedProviderPreparation.Stamp(request.challenge(),1),cancel->RollingAlpinePreparation.prepare(palette,cancel::throwIfCancelled));
                try{
                    pendingOpen=new PendingOpen(p.server,p.getUUID(),p,p.connection,p.level().dimension(),pending.mode,request.challenge(),palette,ticket,true,pending);
                }catch(RuntimeException|Error registrationFailed){ticket.close();throw registrationFailed;}
            }catch(RejectedExecutionException busy){pending.preparing=false;pending.retryAfterNanos=System.nanoTime()+250_000_000L;}
            catch(RuntimeException failure){distanceFailed(pending,p,"Fresh source preparation could not start");}
            return;
        }
        if(reply.kind()!=PlayableEntryPayload.READY){say(p,"Playable entry blocked: "+reply.reason());return;}
        if(System.nanoTime()-request.deadlineNanos()>0||!request.dimension().equals(p.level().dimension())||request.mode()!=p.gameMode.getGameModeForPlayer()||request.position().distanceToSqr(p.position())>1e-12||!mayEnter(p)){say(p,"Entry state changed during preflight; retry /cubic enter from the ordinary world");return;}
        var palette=EnginePaletteContext.create(p.registryAccess().registryOrThrow(Registries.BIOME));
        if(io.earthplatform.compat.PlayableProfile.selection().earth()){beginEarthPreparation(p,palette,request.challenge(),true);return;}
        var descriptor=RollingSessionDescriptor.arena(palette);var provider=new io.earthplatform.minecraft.enginebridge.NativeArenaCubeProvider(descriptor.arenaSource(),palette);
        open(p,palette,provider,descriptor,request.challenge(),new PreparationEvidence("fixed-bounded-arena-source",0));
    }

    private record ArenaScenarioRecovery(RollingArenaScenario.RecoveryExpectation expectation,String jvm,long epoch,String phase) {}
    private static boolean arenaScenarioEnabled(Session s){return s.descriptor.arenaMode()&&io.earthplatform.compat.PlayableDiagnostics.enabled();}
    private static boolean arenaScenarioReady(Session s){return !s.stopping&&!s.handshake.pending()&&!s.editPending&&s.ray==null&&s.collision.ready()&&s.committedManifest!=null&&s.committedManifest.arenaCertificate()!=null&&RollingScenarioClientWitness.current(s.scenarioClientWitness,s.epoch,s.handshake.committedPublication(),s.handshake.resourceGeneration(),s.handshake.committedDigest());}
    private static int arenaScenario(ServerPlayer p,RollingArenaScenario.Mode mode){
        var s=session;
        if(!owns(p,s)||!arenaScenarioEnabled(s)||s.arenaScenario!=null||!p.createCommandSourceStack().hasPermission(2)){say(p,"Arena verification requires an idle playable session, operator permission and the separate QA diagnostic profile.");return 0;}
        if(!arenaScenarioReady(s)||!s.committedCenter.equals(s.descriptor.initialCenter())){say(p,"Wait for exact client READY at the original entry center before starting verification.");return 0;}
        try{
            var certificate=s.committedManifest.arenaCertificate();
            s.scenarioRoot=java.nio.file.Path.of("earthplatform-evidence","arena-scenario",certificate.storeIdentity());
            s.scenarioDirectory=s.scenarioRoot.resolve("run-"+SCENARIO_JVM+"-"+s.epoch+"-"+mode.name().toLowerCase(Locale.ROOT));
            RollingArenaScenario.RecoveryExpectation expectation=null;
            if(mode!=RollingArenaScenario.Mode.EXERCISE){
                var path=s.scenarioRoot.resolve("external-boundary-expectation.json");
                if(java.nio.file.Files.size(path)>16384)throw new IllegalArgumentException("Oversized arena recovery expectation");
                var saved=new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(path),ArenaScenarioRecovery.class);
                if(saved==null||saved.expectation()==null)throw new IllegalArgumentException("Missing exact arena recovery expectation");
                if(mode==RollingArenaScenario.Mode.AFTER_RELOAD&&(!SCENARIO_JVM.equals(saved.jvm())||s.epoch==saved.epoch()||!saved.phase().equals("WAITING_EXTERNAL_RELOAD")))throw new IllegalArgumentException("Reload checkpoint requires a new session in the same JVM");
                if(mode==RollingArenaScenario.Mode.AFTER_RESTART&&(SCENARIO_JVM.equals(saved.jvm())||!saved.phase().equals("WAITING_EXTERNAL_RESTART")))throw new IllegalArgumentException("Restart checkpoint requires prior verified reload and different JVM");
                expectation=saved.expectation();
            }
            java.nio.file.Files.createDirectories(s.scenarioDirectory);
            walk(p,false);scenarioPose(s,p,15.5,149,12.5,0,55);
            s.arenaScenario=new RollingArenaScenario(new RollingArenaScenario.Driver(){
                public RollingArenaScenario.Observation observe(){return observeArenaScenario(s,p);}
                public void issue(RollingArenaScenario.Action action){if(s.arenaAction!=null&&s.scenarioCompletedAction<s.arenaAction.id())throw new IllegalStateException("Unfinished arena action");s.arenaAction=action;s.scenarioActionStarted=false;s.arenaOutcome=RollingArenaScenario.Outcome.NONE;s.arenaTravel.clear();}
                public void checkpoint(RollingArenaScenario.Receipt receipt,boolean screenshot){
                    try{
                        if(s.scenarioCheckpoints>=RollingArenaScenario.MAX_TRANSCRIPT)throw new IllegalStateException("Arena checkpoint bound");
                        String label="arena27-"+(s.scenarioCheckpoints++)+"-"+receipt.label().toLowerCase(Locale.ROOT);
                        java.nio.file.Files.writeString(s.scenarioDirectory.resolve(label+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(receipt)+"\n");
                        if(screenshot){if(!s.scenarioCapture.isEmpty())throw new IllegalStateException("One screenshot checkpoint at a time");s.scenarioCapture=label;s.scenarioCheckpoint=new RollingScenarioCheckpoint(s.epoch,s.handshake.committedPublication(),s.handshake.resourceGeneration(),s.scenarioClientWitness.coverage(),s.handshake.committedDigest(),label);s.scenarioCaptureStarted=System.nanoTime();send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.SCENARIO_CHECKPOINT);b.writeLong(s.epoch);b.writeLong(s.handshake.committedPublication());b.writeLong(s.handshake.resourceGeneration());b.writeLong(s.scenarioClientWitness.coverage());b.writeUtf(s.handshake.committedDigest(),64);b.writeUtf(label,64);}));}
                    }catch(java.io.IOException failure){throw new IllegalStateException("Arena evidence write failed",failure);}
                }
                public void abort(String reason){s.scenarioFailure=reason;s.collision.fence();writeArenaScenario(s);stop(s,p);}
            },mode,s.descriptor.initialCenter(),expectation);
            writeArenaScenario(s);say(p,"SCRIPTED HARNESS "+mode+" started. Controlled actor edits and travel; keep hands off until the external checkpoint.");return 1;
        }catch(RuntimeException|java.io.IOException failure){say(p,"Arena verification refused: "+failure.getMessage());return 0;}
    }
    private static RollingArenaScenario.Observation observeArenaScenario(Session s,ServerPlayer p){
        var ready=Objects.requireNonNull(s.committedManifest);var certificate=Objects.requireNonNull(ready.arenaCertificate());var query=certificate.queryAttestation();var capture=ready.arenaCapture();
        var revisions=RollingArenaScenario.ARENA_CUBES.stream().map(certificate::revision).toList();
        var materials=java.util.stream.IntStream.range(0,8).mapToObj(i->{var t=RollingArenaScenario.corner(i);return capture.materialAt(t.x(),t.y(),t.z());}).toList();
        return new RollingArenaScenario.Observation(s.descriptor.fingerprint(),s.descriptor.arenaSource().identity(),s.descriptor.fingerprint()+":"+s.epoch,SCENARIO_JVM,s.scenarioClientWitness.reloadSequence(),s.epoch,s.handshake.resourceGeneration(),
                arenaScenarioReady(s)&&s.committedCenter.equals(RollingCubePlan.centerFromFeet(p.getX(),p.getY(),p.getZ())),s.handshake.pending()||s.editPending||s.ray!=null,query.completeCatalogue(),s.actor.replacementCurrent(certificate.identity()),s.handshake.committedPublication(),s.scenarioClientWitness.coverage(),query.snapshotGeneration(),query.snapshotRootIdentity(),query.identity(),capture.contentIdentity(),certificate.durableIdentity(),s.committedCenter,revisions,materials,(int)s.descriptor.arenaDefinition().dependencies().stream().filter(s.snapshots::containsKey).count(),s.scenarioCompletedAction,s.arenaOutcome,List.copyOf(s.arenaTravel),s.scenarioFailure);
    }
    private static void pumpArenaScenario(Session s,ServerPlayer p){
        if(s.arenaScenario==null)return;
        if(!s.scenarioCapture.isEmpty()&&System.nanoTime()-s.scenarioCaptureStarted>RollingArenaScenario.STEP_NANOS){s.arenaScenario.cancel("Screenshot checkpoint acknowledgement deadline");writeArenaScenario(s);return;}
        if(s.arenaScenario.status()==RollingArenaScenario.Status.RUNNING&&s.scenarioCapture.isEmpty()&&s.arenaAction!=null&&arenaScenarioReady(s)){
            var action=s.arenaAction;
            if(!s.scenarioActionStarted){
                s.scenarioActionStarted=true;s.scenarioActionPublication=s.handshake.committedPublication();
                switch(action.kind()){
                    case REMOVE_CORNER,FILL_CORNER -> arenaHarnessEdit(s,p,action);
                    case TRAVEL_AWAY,RETURN -> {s.scenarioTravelGoal=action.kind()==RollingArenaScenario.Kind.TRAVEL_AWAY?s.descriptor.initialCenter().offset(-4,0,0):s.descriptor.initialCenter();arenaTravelNext(s,p);}
                    case OUTSIDE_REFUSAL -> {
                        var t=action.target();
                        var result=NativeRayEditPlanner.planArena(s.world,s.epoch,s.palette,windowSnapshots(s,s.committedCenter),new Vec3(t.x()+.5,t.y()+5,t.z()+.5),new Vec3(0,-1,0),8,NativeRayEditPlanner.Action.REMOVE,s.descriptor.arenaDefinition(),null);
                        if(!(result instanceof NativeRayEditPlanner.Rejected rejected)||rejected.reason()!=NativeRayEditPlanner.RejectionReason.OUTSIDE_EDITABLE_CUBE)throw new IllegalStateException("Expected exact arena boundary ray refusal");
                        s.arenaOutcome=RollingArenaScenario.Outcome.REFUSED;
                    }
                }
            }else switch(action.kind()){
                case REMOVE_CORNER,FILL_CORNER,OUTSIDE_REFUSAL -> {if(s.arenaOutcome!=RollingArenaScenario.Outcome.NONE)s.scenarioCompletedAction=action.id();}
                case TRAVEL_AWAY,RETURN -> {
                    if(s.scenarioTravelNext!=null&&s.committedCenter.equals(s.scenarioTravelNext)&&s.handshake.committedPublication()>s.scenarioActionPublication){
                        if(s.arenaTravel.size()>=RollingArenaScenario.MAX_ROUTE_HOPS)throw new IllegalStateException("Arena travel receipt bound");
                        s.arenaTravel.add(new RollingArenaScenario.TravelStep(s.committedCenter,s.handshake.committedPublication(),s.scenarioClientWitness.coverage(),s.committedManifest.arenaCertificate().queryAttestation().identity(),true));
                        if(s.committedCenter.equals(s.scenarioTravelGoal)){s.scenarioCompletedAction=action.id();s.arenaOutcome=RollingArenaScenario.Outcome.APPLIED;}else arenaTravelNext(s,p);
                    }
                }
            }
        }
        var before=s.arenaScenario.status();s.arenaScenario.tick(System.nanoTime());
        if(s.arenaScenario.status()!=before||s.arenaScenario.status()!=RollingArenaScenario.Status.RUNNING&&s.scenarioCapture.isEmpty()&&!s.scenarioTerminalWritten)writeArenaScenario(s);
    }
    private static void arenaTravelNext(Session s,ServerPlayer p){
        int delta=Integer.compare(s.scenarioTravelGoal.x(),s.committedCenter.x());if(delta==0)throw new IllegalStateException("Arena travel ended without receipt");
        s.scenarioTravelNext=s.committedCenter.offset(delta,0,0);s.scenarioActionPublication=s.handshake.committedPublication();
        scenarioPose(s,p,s.scenarioTravelNext.minBlockX()+8,149,12.5,0,55);
    }
    private static void arenaHarnessEdit(Session s,ServerPlayer p,RollingArenaScenario.Action action){
        var t=action.target();var position=CubePos.fromBlock(t.x(),t.y(),t.z());var snapshot=s.snapshots.get(position);
        if(snapshot==null||!s.descriptor.arenaDefinition().insideWindow(s.committedCenter)||!s.descriptor.permitsEdit(position,t.x(),t.y(),t.z()))throw new IllegalStateException("Harness edit lacks current full arena neighborhood");
        if(action.kind()==RollingArenaScenario.Kind.FILL_CORNER&&p.getBoundingBox().intersects(new AABB(t.x(),t.y(),t.z(),t.x()+1,t.y()+1,t.z()+1)))throw new IllegalStateException("Harness fill intersects player");
        int state=net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId((action.kind()==RollingArenaScenario.Kind.REMOVE_CORNER?net.minecraft.world.level.block.Blocks.AIR:net.minecraft.world.level.block.Blocks.DIORITE).defaultBlockState());
        s.actor.requestCenter(s.committedCenter);
        var edit=new RollingCubeActor.EditRequest(s.world,s.epoch,position,snapshot.leaseGeneration(),snapshot.revision(),t.x(),t.y(),t.z(),state);
        if(s.actor.tryEdit(edit)!=RollingCubeActor.Admission.QUEUED)throw new IllegalStateException("Harness actor edit admission refused");
        s.editTarget=position;s.editPending=true;s.editPublication=true;long publication=s.handshake.begin();s.collision.fence();reset(p);
        send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.EDIT_BEGIN);b.writeLong(s.epoch);b.writeLong(publication);b.writeLong(edit.x());b.writeLong(edit.y());b.writeLong(edit.z());}));
        EarthPlatformMod.LOGGER.info("ARENA_HARNESS_EDIT_BEGIN epoch={} action={} target={},{},{} revision={}",s.epoch,action.id(),t.x(),t.y(),t.z(),snapshot.revision());
    }
    private static void writeArenaScenario(Session s){
        if(s.arenaScenario==null||s.scenarioDirectory==null||s.scenarioWriting)return;s.scenarioWriting=true;
        try{
            var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();var report=s.arenaScenario.report();var json=gson.toJsonTree(report).getAsJsonObject();
            json.addProperty("jvmIdentity",SCENARIO_JVM);json.addProperty("screenshotAcknowledgements",s.scenarioScreenshotAcks);json.addProperty("screenshotPending",!s.scenarioCapture.isEmpty());
            json.addProperty("scope","SCRIPTED_HARNESS exact actor edits, synthetic server ray boundary refusal and controlled adjacent-center travel. No manual mouse/keyboard claim. Resource reload and process restart are external actions.");
            java.nio.file.Files.writeString(s.scenarioDirectory.resolve("report.json"),gson.toJson(json)+"\n");
            if(report.expectation()!=null&&s.scenarioCapture.isEmpty()&&(report.status()==RollingArenaScenario.Status.WAITING_EXTERNAL_RELOAD||report.status()==RollingArenaScenario.Status.WAITING_EXTERNAL_RESTART))java.nio.file.Files.writeString(s.scenarioRoot.resolve("external-boundary-expectation.json"),gson.toJson(new ArenaScenarioRecovery(report.expectation(),SCENARIO_JVM,s.epoch,report.status().name()))+"\n");
            if(report.status()!=RollingArenaScenario.Status.RUNNING&&s.scenarioCapture.isEmpty())s.scenarioTerminalWritten=true;
        }catch(java.io.IOException|RuntimeException failure){EarthPlatformMod.LOGGER.error("ARENA_SCENARIO_EVIDENCE_FAILED",failure);s.scenarioFailure="Arena evidence write failed";s.scenarioTerminalWritten=false;s.arenaScenario.cancel(s.scenarioFailure);}
        finally{s.scenarioWriting=false;}
    }

    private record ScenarioRecovery(RollingIndexedScenario.RecoveryExpectation expectation,String jvm,long epoch,String phase) {}
    private static final String SCENARIO_JVM=UUID.randomUUID().toString();
    private static int scenario(ServerPlayer p,RollingIndexedScenario.Mode mode){
        var s=session;
        if(!owns(p,s)||s.stopping||!s.descriptor.indexedWorkspaces()||!Boolean.getBoolean(RollingGateFixture.INDEXED_SCENARIO_OPT_IN)
                ||s.scenario!=null){say(p,"Scenario requires an idle indexed READY session and explicit diagnostic opt-in.");return 0;}
        // Refuse before evidence paths, player movement or runner creation. A server commit can
        // precede its exact client receipt; only that receipt supplies the actual reload sequence.
        if(!scenarioReady(s)){say(p,"Scenario waits for the exact current client READY receipt and idle collision pair. Wait for client READY, then retry the command.");return 0;}
        try{
            var set=s.committedManifest.replacementSet();
            s.scenarioRoot=java.nio.file.Path.of("earthplatform-evidence","indexed-scenario",set.storeIdentity());
            s.scenarioDirectory=s.scenarioRoot.resolve("run-"+SCENARIO_JVM+"-"+s.epoch+"-"+mode.name().toLowerCase(Locale.ROOT));
            java.nio.file.Files.createDirectories(s.scenarioDirectory);
            RollingIndexedScenario.RecoveryExpectation expectation=null;
            if(mode!=RollingIndexedScenario.Mode.EXERCISE){
                var path=s.scenarioRoot.resolve("external-boundary-expectation.json");
                if(java.nio.file.Files.size(path)>8192)throw new IllegalArgumentException("Oversized scenario expectation");
                var saved=new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(path),ScenarioRecovery.class);
                if(saved==null||saved.expectation()==null)throw new IllegalArgumentException("Missing exact scenario expectation");
                if(mode==RollingIndexedScenario.Mode.AFTER_RELOAD&&(!SCENARIO_JVM.equals(saved.jvm())||s.epoch==saved.epoch()||!saved.phase().equals("WAITING_EXTERNAL_RELOAD")))throw new IllegalArgumentException("Reload checkpoint requires a new session in the same JVM");
                if(mode==RollingIndexedScenario.Mode.AFTER_RESTART&&(SCENARIO_JVM.equals(saved.jvm())||!saved.phase().equals("WAITING_EXTERNAL_RESTART")))throw new IllegalArgumentException("Restart checkpoint requires the explicit prior phase and a different JVM");
                expectation=saved.expectation();
            }
            walk(p,false);scenarioPose(s,p,15.5,s.descriptor.anchorY()+1,12.5,0,31);
            s.scenario=new RollingIndexedScenario(new RollingIndexedScenario.Driver(){
                public RollingIndexedScenario.Observation observe(){return scenarioObserve(s,p);}
                public void issue(RollingIndexedScenario.Action action){if(s.scenarioAction!=null&&s.scenarioCompletedAction<s.scenarioAction.id())throw new IllegalStateException("Unfinished scenario action");s.scenarioAction=action;s.scenarioActionStarted=false;s.scenarioOutcome=RollingIndexedScenario.Outcome.NONE;s.scenarioTravel.clear();}
                public void checkpoint(RollingIndexedScenario.Receipt receipt,boolean screenshot){
                    try{
                        String label="scenario12-"+(s.scenarioCheckpoints++)+"-"+receipt.label().toLowerCase(Locale.ROOT);
                        java.nio.file.Files.writeString(s.scenarioDirectory.resolve(label+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(receipt)+"\n");
                        if(screenshot){if(!s.scenarioCapture.isEmpty())throw new IllegalStateException("Only one screenshot checkpoint may be pending");s.scenarioCapture=label;s.scenarioCheckpoint=new RollingScenarioCheckpoint(s.epoch,s.handshake.committedPublication(),s.handshake.resourceGeneration(),s.scenarioClientWitness.coverage(),s.handshake.committedDigest(),label);s.scenarioCaptureStarted=System.nanoTime();send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.SCENARIO_CHECKPOINT);b.writeLong(s.epoch);b.writeLong(s.handshake.committedPublication());b.writeLong(s.handshake.resourceGeneration());b.writeLong(s.scenarioClientWitness.coverage());b.writeUtf(s.handshake.committedDigest(),64);b.writeUtf(label,64);}));}
                    }catch(java.io.IOException failure){throw new IllegalStateException("Scenario receipt write failed",failure);}
                }
                public void abort(String reason){s.scenarioFailure=reason;s.collision.fence();writeScenario(s);stop(s,p);}
            },mode,s.descriptor.initialCenter(),expectation);
            writeScenario(s);say(p,"SCRIPTED HARNESS started: "+mode+". Do not move or edit until the external-boundary checkpoint.");return 1;
        }catch(RuntimeException|java.io.IOException failure){say(p,"Scenario refused: "+failure.getMessage());return 0;}
    }
    private static void scenarioPose(Session s,ServerPlayer p,double x,double y,double z,float yaw,float pitch){
        var body=new AABB(x-.3,y,z-.3,x+.3,y+1.8,z+.3);if(!s.descriptor.containsBody(body))throw new IllegalArgumentException("Scenario pose exceeds body envelope");
        reset(p);p.teleportTo(s.server.getLevel(SceneCommands.CARRIER),x,y,z,Set.of(),yaw,pitch);
    }
    private static boolean scenarioReady(Session s){return !s.stopping&&!s.handshake.pending()&&!s.editPending&&s.ray==null&&s.collision.ready()&&s.committedManifest!=null&&RollingScenarioClientWitness.current(s.scenarioClientWitness,s.epoch,s.handshake.committedPublication(),s.handshake.resourceGeneration(),s.handshake.committedDigest())
            &&(s.committedManifest.pinned()||!RollingCubePlan.stationary(activeFocus(s),s.committedCenter,false).currentTargets().contains(activeFocus(s)));}
    private static RollingIndexedScenario.Observation scenarioObserve(Session s,ServerPlayer p){
        var ready=s.committedManifest;var set=ready==null?null:ready.replacementSet();var query=set==null?null:set.queryAttestation();
        var a=s.descriptor.workspaces().original();var b=s.descriptor.workspaces().second();
        boolean current=set!=null&&s.actor.replacementCurrent(set.identity());
        int ma=-1,mb=-1;
        if(ready!=null&&ready.replacementCaptures().size()==2){ma=ready.replacementCaptures().get(0).materialAt(a.minBlockX()+15,s.descriptor.anchorY(),a.minBlockZ()+15);mb=ready.replacementCaptures().get(1).materialAt(b.minBlockX(),s.descriptor.anchorY(),b.minBlockZ()+15);}
        return new RollingIndexedScenario.Observation(s.descriptor.fingerprint(),SCENARIO_JVM,s.scenarioClientWitness.reloadSequence(),s.epoch,Math.max(1,s.handshake.resourceGeneration()),scenarioReady(s)&&s.committedCenter.equals(RollingCubePlan.centerFromFeet(p.getX(),p.getY(),p.getZ())),s.handshake.pending()||s.editPending||s.ray!=null,
                query!=null&&query.completeCatalogue(),current,s.handshake.committedPublication(),s.scenarioClientWitness.coverage(),query==null?0:query.snapshotGeneration(),query==null?"":query.snapshotRootIdentity(),query==null?"":query.identity(),set==null?"":set.durableIdentity(),
                activeFocus(s).equals(a)?RollingIndexedScenario.Focus.ORIGINAL:RollingIndexedScenario.Focus.SECOND,set==null?0:set.region(a).revision(),set==null?0:set.region(b).revision(),ma,mb,
                (int)s.descriptor.workspaces().dependencies(a).stream().filter(s.snapshots::containsKey).count(),(int)s.descriptor.workspaces().dependencies(b).stream().filter(s.snapshots::containsKey).count(),s.committedCenter==null?s.descriptor.initialCenter():s.committedCenter,
                new RollingIndexedScenario.Pose(p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot()),s.scenarioCompletedAction,s.scenarioOutcome,List.copyOf(s.scenarioTravel),s.scenarioFailure);
    }
    private static void pumpScenario(Session s,ServerPlayer p){
        if(s.scenario==null)return;
        if(!s.scenarioCapture.isEmpty()&&System.nanoTime()-s.scenarioCaptureStarted>RollingIndexedScenario.STEP_NANOS){s.scenario.cancel("Screenshot checkpoint acknowledgement deadline");writeScenario(s);return;}
        if(s.scenario.status()==RollingIndexedScenario.Status.RUNNING&&s.scenarioCapture.isEmpty()&&s.scenarioAction!=null&&scenarioReady(s)){
            var action=s.scenarioAction;
            if(!s.scenarioActionStarted){
                s.scenarioActionStarted=true;s.scenarioActionPublication=s.handshake.committedPublication();
                switch(action.kind()){
                    case STAGE_A_AIM -> scenarioPose(s,p,15.5,s.descriptor.anchorY()+1,12.5,0,31);
                    case STAGE_B_AIM -> scenarioPose(s,p,16.5,s.descriptor.anchorY()+1,12.5,0,31);
                    case STAGE_OUTSIDE_AIM -> scenarioPose(s,p,15.5,s.descriptor.anchorY()+1,17.5,0,31);
                    case FOCUS_A -> {if(focus(p,false)!=1)throw new IllegalStateException("Harness focus A refused");}
                    case FOCUS_B -> {if(focus(p,true)!=1)throw new IllegalStateException("Harness focus B refused");}
                    case REMOVE_A,REMOVE_B,FILL_A,FILL_B,OUTSIDE_EDIT_REFUSAL -> mouseIntent(p,s,new RollingGateControlPayload(s.epoch,s.handshake.committedPublication(),s.handshake.resourceGeneration(),RollingGateControlPayload.MOUSE,"",action.kind()==RollingIndexedScenario.Kind.FILL_A||action.kind()==RollingIndexedScenario.Kind.FILL_B));
                    case TRAVEL_AWAY,RETURN -> {s.scenarioTravelGoal=action.kind()==RollingIndexedScenario.Kind.TRAVEL_AWAY?s.descriptor.initialCenter().offset(-4,0,0):s.descriptor.initialCenter();scenarioTravelNext(s,p);}
                }
            }else switch(action.kind()){
                case STAGE_A_AIM,STAGE_B_AIM,STAGE_OUTSIDE_AIM -> {var actual=RollingCubePlan.centerFromFeet(p.getX(),p.getY(),p.getZ());if(s.committedCenter.equals(actual))scenarioComplete(s,RollingIndexedScenario.Outcome.APPLIED);}
                case FOCUS_A,FOCUS_B -> {if(s.handshake.committedPublication()>s.scenarioActionPublication)scenarioComplete(s,RollingIndexedScenario.Outcome.APPLIED);}
                case REMOVE_A,REMOVE_B,FILL_A,FILL_B,OUTSIDE_EDIT_REFUSAL -> {if(s.scenarioOutcome!=RollingIndexedScenario.Outcome.NONE)scenarioComplete(s,s.scenarioOutcome);}
                case TRAVEL_AWAY,RETURN -> {
                    if(s.scenarioTravelNext!=null&&s.committedCenter.equals(s.scenarioTravelNext)&&s.handshake.committedPublication()>s.scenarioActionPublication){
                        if(s.scenarioTravel.size()>=RollingIndexedScenario.MAX_TRAVEL_STEPS)throw new IllegalStateException("Harness travel receipt bound");
                        s.scenarioTravel.add(new RollingIndexedScenario.TravelStep(s.committedCenter,s.handshake.committedPublication(),s.scenarioClientWitness.coverage(),s.committedManifest.replacementSet().queryAttestation().identity(),true));
                        if(s.committedCenter.equals(s.scenarioTravelGoal))scenarioComplete(s,RollingIndexedScenario.Outcome.APPLIED);else scenarioTravelNext(s,p);
                    }
                }
            }
        }
        var before=s.scenario.status();s.scenario.tick(System.nanoTime());
        if(s.scenario.status()!=before||s.scenario.status()!=RollingIndexedScenario.Status.RUNNING&&s.scenarioCapture.isEmpty()&&!s.scenarioTerminalWritten)writeScenario(s);
    }
    private static void scenarioTravelNext(Session s,ServerPlayer p){
        int delta=Integer.compare(s.scenarioTravelGoal.x(),s.committedCenter.x());if(delta==0)throw new IllegalStateException("Harness travel ended without receipt");
        s.scenarioTravelNext=s.committedCenter.offset(delta,0,0);s.scenarioActionPublication=s.handshake.committedPublication();
        scenarioPose(s,p,s.scenarioTravelNext.minBlockX()+8,s.descriptor.anchorY()+10,s.descriptor.initialCenter().minBlockZ()+8,0,31);
    }
    private static void scenarioComplete(Session s,RollingIndexedScenario.Outcome outcome){s.scenarioCompletedAction=s.scenarioAction.id();s.scenarioOutcome=outcome;}
    private static void scenarioReceipt(ServerPlayer p,RollingScenarioReceiptPayload receipt){
        var s=session;if(!owns(p,s)||s.stopping||!(arenaScenarioEnabled(s)||s.descriptor.indexedWorkspaces()&&Boolean.getBoolean(RollingGateFixture.INDEXED_SCENARIO_OPT_IN)))return;
        var witness=new RollingScenarioClientWitness(receipt.epoch(),receipt.publication(),receipt.resources(),receipt.coverage(),receipt.reloadSequence(),receipt.digest());
        if(!RollingScenarioClientWitness.current(witness,s.epoch,s.handshake.committedPublication(),s.handshake.resourceGeneration(),s.handshake.committedDigest()))return;
        s.scenarioClientWitness=witness;
        if(!receipt.checkpoint().isEmpty()&&receipt.checkpoint().equals(s.scenarioCapture)){
            if(s.scenarioCheckpoint==null||!s.scenarioCheckpoint.matches(receipt.epoch(),receipt.publication(),receipt.resources(),receipt.coverage(),receipt.digest(),receipt.checkpoint())){if(s.scenario!=null)s.scenario.cancel("Screenshot ACK differs from exact requested checkpoint");if(s.arenaScenario!=null)s.arenaScenario.cancel("Screenshot ACK differs from exact requested checkpoint");return;}
            s.scenarioCapture="";s.scenarioCheckpoint=null;s.scenarioScreenshotAcks++;if(s.arenaScenario!=null)writeArenaScenario(s);else writeScenario(s);
        }
    }
    private static void writeScenario(Session s){
        if(s.scenario==null||s.scenarioDirectory==null||s.scenarioWriting)return;
        s.scenarioWriting=true;
        try{
            var gson=new com.google.gson.GsonBuilder().setPrettyPrinting().create();var report=s.scenario.report();var json=gson.toJsonTree(report).getAsJsonObject();
            json.addProperty("jvmIdentity",SCENARIO_JVM);json.addProperty("screenshotAcknowledgements",s.scenarioScreenshotAcks);json.addProperty("screenshotPending",!s.scenarioCapture.isEmpty());
            json.addProperty("scope","SCRIPTED_HARNESS ray edits and controlled adjacent-center travel; no ordinary keyboard/mouse claim. Resource reload and process restart are external root actions.");
            java.nio.file.Files.writeString(s.scenarioDirectory.resolve("report.json"),gson.toJson(json)+"\n");
            if(report.expectation()!=null&&s.scenarioCapture.isEmpty()&&(report.status()==RollingIndexedScenario.Status.WAITING_EXTERNAL_RELOAD||report.status()==RollingIndexedScenario.Status.WAITING_EXTERNAL_RESTART))
                java.nio.file.Files.writeString(s.scenarioRoot.resolve("external-boundary-expectation.json"),gson.toJson(new ScenarioRecovery(report.expectation(),SCENARIO_JVM,s.epoch,report.status().name()))+"\n");
            if(report.status()!=RollingIndexedScenario.Status.RUNNING&&s.scenarioCapture.isEmpty())s.scenarioTerminalWritten=true;
        }catch(java.io.IOException|RuntimeException failure){
            EarthPlatformMod.LOGGER.error("INDEXED_SCENARIO_EVIDENCE_FAILED",failure);s.scenarioFailure="Evidence write failed";s.scenarioTerminalWritten=false;s.scenario.cancel(s.scenarioFailure);
        }finally{s.scenarioWriting=false;}
    }

    private static CubePos activeFocus(Session s){return s.committedManifest!=null&&s.committedManifest.activeFocus()!=null?s.committedManifest.activeFocus():s.descriptor.target();}
    private static String replacementIdentity(RollingCubeActor.StageReady ready){return ready.arenaCertificate()!=null?ready.arenaCertificate().identity():ready.replacementSet()!=null?ready.replacementSet().identity():ready.replacementCertificate()==null?null:ready.replacementCertificate().identity();}
    private static long replacementRevision(RollingCubeActor.StageReady ready,CubePos focus){return ready.arenaCertificate()!=null?ready.arenaCertificate().revision(focus):ready.replacementSet()!=null?ready.replacementSet().region(focus).revision():ready.replacementCertificate()==null?-1:ready.replacementCertificate().originalRevision();}
    private static int focus(ServerPlayer p,boolean second){
        var s=session;if(!owns(p,s)||s.stopping||!s.descriptor.twoWorkspaces()){say(p,"Two-workspace focus requires the explicit analytic gate");return 0;}
        if(s.handshake.pending()||s.editPending||s.ray!=null||s.committedManifest==null||s.committedManifest.replacementSet()==null){say(p,"Wait for a complete paired replacement set before changing focus");return 0;}
        var target=second?s.descriptor.workspaces().second():s.descriptor.workspaces().original();
        if(!reserveSameCenter(s)){say(p,"Workspace focus waits for body motion authority");return 0;}
        var result=s.actor.requestFocus(target,s.committedManifest.generation(),s.committedManifest.replacementSet().identity());
        if(result!=RollingCubeActor.Admission.QUEUED)s.collision.motion().cancelUnstartedReservation();
        say(p,"Workspace focus "+target+": "+result+(s.descriptor.adjacentWorkspaces()?". Both neighborhoods must already fit the current window; wait for READY.":". Move within one cube of it after the previous pin retires, then retry if needed."));return result==RollingCubeActor.Admission.QUEUED?1:0;
    }

    private static boolean allowed(ServerPlayer p) {
        return (io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled()||RollingGateFixture.enabled() && Boolean.getBoolean(CoreGateCommands.OPT_IN))
            && CoreGateFixture.collision() && CoreGateFixture.fullHeight()
            && p.server.isSingleplayer() && !p.server.isDedicatedServer();
    }

    private static boolean mayEnter(ServerPlayer p) {
        if (!allowed(p)) { say(p, "Rolling gate requires all four explicit integrated core, collision, full-height and rolling opt-ins."); return false; }
        if (p.server.isHardcore()) { say(p, "The diagnostic player gate does not support hardcore worlds."); return false; }
        if (!p.isAlive() || p.isRemoved() || p.connection==null || p.connection.player!=p
                || p.server.getPlayerList().getPlayer(p.getUUID())!=p) { say(p, "Respawn and reconnect before entering the rolling player gate."); return false; }
        if (Boolean.getBoolean(SceneCommands.OPT_IN)) { say(p, "Launch the isolated rolling profile without the Earth visual scene opt-in."); return false; }
        if (p.level().dimension().equals(SceneCommands.CARRIER)) { say(p, "Return to a normal dimension before opening a rolling host."); return false; }
        if (CoreReturnPoint.remembered(p).isPresent() || RECOVERY.current()!=null) { say(p, "Previous carrier recovery is unresolved; use /earth_roam leave first."); return false; }
        if (session != null) { say(p, "Previous rolling host must finish draining; leave before entering again."); return false; }
        if (pendingOpen != null) { say(p, "Earth source preparation is already pending; use /earth_roam leave to cancel it."); return false; }
        if (p.server.getLevel(SceneCommands.CARRIER) == null) { say(p, "A fresh disposable world with the empty carrier is required."); return false; }
        return true;
    }

    private static int enter(ServerPlayer p, CoreGateProfile profile) {
        if(io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled()&&!Boolean.getBoolean(RollingGateFixture.OPT_IN)){say(p,"Use /cubic enter for the finite landscape");return 0;}
        if(!mayEnter(p))return 0;
        var palette=EnginePaletteContext.create(p.registryAccess().registryOrThrow(Registries.BIOME));
        var provider=RollingGateFixture.provider(palette,profile);
        var descriptor=RollingSessionDescriptor.analytic(profile,palette,
                Boolean.getBoolean(RollingGateFixture.BOUNDARY_OPT_IN) && Boolean.getBoolean(RollingGateFixture.REPLACEMENT_OPT_IN)
                ? io.earthplatform.minecraft.enginebridge.RollingEditPolicy.FULL_CUBE_V1 : io.earthplatform.minecraft.enginebridge.RollingEditPolicy.INTERIOR_V1,Boolean.getBoolean(RollingGateFixture.TWO_WORKSPACE_OPT_IN),Boolean.getBoolean(RollingGateFixture.ADJACENT_WORKSPACE_OPT_IN),Boolean.getBoolean(RollingGateFixture.INDEXED_WORKSPACE_OPT_IN));
        return open(p,palette,provider,descriptor,EPOCH.incrementAndGet(),PreparationEvidence.analytic());
    }

    private static int enterEarth(ServerPlayer p) {
        if(io.earthplatform.compat.PlayableProfile.selection().earth())return requestPlayable(p);
        if(!Boolean.getBoolean(RollingEarthPreparation.OPT_IN)) {
            say(p,"Earth entry requires the explicit earthPlayerGate opt-in.");return 0;
        }
        if(!mayEnter(p))return 0;
        var palette=EnginePaletteContext.create(p.registryAccess().registryOrThrow(Registries.BIOME));
        return beginEarthPreparation(p,palette,EPOCH.incrementAndGet(),false);
    }

    private static int beginEarthPreparation(ServerPlayer p,EnginePaletteContext palette,long epoch,boolean alpine) {
        try {
            // This closure captures ONLY the immutable palette. Source preparation never observes
            // a player, server, Level or mutable owner and cannot publish callbacks to this class.
            var ticket=BoundedProviderPreparation.submit(new BoundedProviderPreparation.Stamp(epoch,1),
                    cancel->alpine?RollingAlpinePreparation.prepare(palette,cancel::throwIfCancelled):RollingEarthPreparation.prepare(palette,cancel::throwIfCancelled));
            try {
                pendingOpen=new PendingOpen(p.server,p.getUUID(),p,p.connection,p.level().dimension(),
                        p.gameMode.getGameModeForPlayer(),epoch,palette,ticket,alpine,null);
            } catch(RuntimeException|Error registrationFailed) {
                ticket.close();throw registrationFailed;
            }
        } catch(RejectedExecutionException busy) {
            EarthPlatformMod.LOGGER.info("ROLLING_EARTH_PREPARATION_REJECTED epoch={} counters={}",epoch,BoundedProviderPreparation.processDiagnostics());
            say(p,"Earth source preparation is busy while older work drains; retry /earth_roam enter earth shortly.");return 0;
        }
        EarthPlatformMod.LOGGER.info("ROLLING_EARTH_PREPARATION_BEGIN epoch={} ownerThread={} counters={}",epoch,Thread.currentThread().getName(),BoundedProviderPreparation.processDiagnostics());
        say(p,"Preparing the bounded Earth source in the background. Your current world and mode remain active; /earth_roam leave cancels entry.");return 1;
    }

    /** Validated again on the owner before claiming. In particular, a reconnect cannot inherit a ticket. */
    private static boolean pendingCurrent(PendingOpen pending,ServerPlayer p) {
        if(pending.continuation()!=null)return pendingOpen==pending&&pending.epoch()==EPOCH.get()&&pendingDistance==pending.continuation()
                &&distanceOwnerCurrent(pending.continuation(),p)&&pending.continuation().gate.ready();
        return pendingOpen==pending && pending.epoch()==EPOCH.get() && session==null && RECOVERY.current()==null
                && p!=null && p==pending.requestedPlayer() && p.server==pending.server()
                && p.getUUID().equals(pending.player()) && p.connection==pending.connection()
                && p.connection!=null && p.connection.player==p && p.server.getPlayerList().getPlayer(p.getUUID())==p
                && p.isAlive() && !p.isRemoved() && allowed(p) && !p.server.isHardcore()
                && (pending.alpine()?io.earthplatform.compat.PlayableProfile.selection().earth()&&io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled():Boolean.getBoolean(RollingEarthPreparation.OPT_IN)) && !Boolean.getBoolean(SceneCommands.OPT_IN)
                && p.level().dimension().equals(pending.sourceDimension())
                && p.gameMode.getGameModeForPlayer()==pending.sourceMode()
                && !p.level().dimension().equals(SceneCommands.CARRIER) && CoreReturnPoint.remembered(p).isEmpty();
    }

    private static void cancelPending(PendingOpen pending,String reason) {
        if(pending==null || pendingOpen!=pending)return;
        pendingOpen=null;pending.ticket().close();
        EarthPlatformMod.LOGGER.info("ROLLING_EARTH_PREPARATION_CANCEL epoch={} reason={} physicallySettled={} counters={}",
                pending.epoch(),reason,pending.ticket().physicalSettled(),BoundedProviderPreparation.processDiagnostics());
    }

    private static void cancelPendingPlayer(ServerPlayer p,String reason) {
        if(pendingDistance!=null&&pendingDistance.old.server==p.server&&pendingDistance.old.player.equals(p.getUUID())){pendingDistance.gate.fail();pendingDistance.progress.watchdog.fail(reason);lastDistanceProgress=pendingDistance.progress.checkpoint();pendingDistance=null;}
        if(pendingPlayable!=null&&pendingPlayable.server()==p.server&&pendingPlayable.player().equals(p.getUUID()))pendingPlayable=null;
        var pending=pendingOpen;
        if(pending!=null && pending.server()==p.server && pending.player().equals(p.getUUID()))cancelPending(pending,reason);
    }

    private static void pumpOpen(MinecraftServer server) {
        var pending=pendingOpen;if(pending==null || pending.server()!=server)return;
        var p=server.getPlayerList().getPlayer(pending.player());
        if(!pendingCurrent(pending,p)) { cancelPending(pending,"owner, epoch, world, mode or recovery changed");return; }
        var ticket=pending.ticket();
        if(ticket.state()==BoundedProviderPreparation.State.PENDING)return;
        if(ticket.state()!=BoundedProviderPreparation.State.READY) {
            var failure=ticket.failure().orElse(null);cancelPending(pending,"preparation failed or cancelled");
            EarthPlatformMod.LOGGER.warn("ROLLING_EARTH_PREPARATION_FAILED epoch={} counters={}",pending.epoch(),BoundedProviderPreparation.processDiagnostics(),failure);
            if(pending.continuation()!=null)distanceFailed(pending.continuation(),p,"Fresh source preparation failed or was cancelled");
            else say(p,"Earth source preparation failed; your current world was not changed. Check the server log and retry entry.");return;
        }
        var prepared=ticket.takeReady(new BoundedProviderPreparation.Stamp(EPOCH.get(),1));
        if(prepared.isEmpty()) { cancelPending(pending,"current epoch could not claim prepared result");if(pending.continuation()!=null)distanceFailed(pending.continuation(),p,"Fresh source ownership changed before claim");return; }
        pendingOpen=null;
        try {
            var ready=prepared.get();ready.descriptor().requirePreparedWorld(ready.provider().identity());
            var continuation=pending.continuation();
            if(continuation!=null&&!continuation.old.descriptor.samePreparedSource(ready.descriptor()))throw new IllegalStateException("Distance continuation source/pin/store changed");
            if(continuation!=null)continuation.progress.credit(NativeDistanceProgressWatchdog.Lane.SOURCE_READY,1);
            var descriptor=continuation==null?ready.descriptor():ready.descriptor().resumeAt(continuation.position);
            int opened=open(p,pending.palette(),ready.provider(),descriptor,pending.epoch(),
                    new PreparationEvidence(ready.workerThread(),ready.elapsedNanos()),continuation);
            if(continuation!=null){if(opened!=1)distanceFailed(continuation,p,"Fresh distance session admission failed");else pendingDistance=null;}
        } catch(RuntimeException failed) {
            EarthPlatformMod.LOGGER.warn("Earth prepared host admission failed",failed);
            if(pending.continuation()!=null)distanceFailed(pending.continuation(),p,"Earth continuation admission failed");
            else say(p,"Earth host admission failed; retry after existing owners drain.");
        } finally { ticket.close(); }
    }

    private static boolean distanceOwnerCurrent(PendingDistance pending,ServerPlayer p){
        return pendingDistance==pending&&session==pending.old&&p!=null&&p==pending.player&&p.server==pending.old.server
                &&p.connection==pending.connection&&p.connection!=null&&p.connection.player==p
                &&p.server.getPlayerList().getPlayer(p.getUUID())==p&&p.isAlive()&&!p.isRemoved()&&allowed(p)
                &&p.level().dimension().equals(SceneCommands.CARRIER)&&p.gameMode.getGameModeForPlayer()==pending.mode
                &&p.noPhysics==pending.noPhysics&&p.position().distanceToSqr(pending.position)<1e-12
                &&CoreReturnPoint.remembered(p).filter(pending.old.origin::equals).isPresent();
    }
    private static void nativeDistanceChange(ServerPlayer p,NativeDistanceChangePayload payload){
        var s=session;if(!owns(p,s)||s.connection!=p.connection||!s.descriptor.alpineMode())return;
        var activeProgress=pendingDistance!=null&&pendingDistance.old==s?pendingDistance.progress:s.continuationProgress;
        if(activeProgress!=null){activeProgress.serverTick(p.server.getTickCount(),NativeDistanceProgressState.serverClock(p.server,activeProgress.watchdog.episode()));if(activeProgress.watchdog.failed()){if(pendingDistance!=null)distanceFailed(pendingDistance,p,activeProgress.watchdog.failure());else failDistanceProgress(s,p,activeProgress.watchdog.failure());return;}}
        boolean openingEpoch=NativeDistanceRestartGate.openingEpoch(s.epoch,s.distancePredecessorWireEpoch,s.distancePredecessorWireSerial,
                s.continuation&&s.handshake.committedPublication()==0,payload);
        var pending=pendingDistance;
        boolean pendingEpoch=pending!=null&&pending.old==s&&payload.epoch()==pending.gate.latest().epoch();
        if(payload.kind()==NativeDistanceChangePayload.FAILED&&s.continuationProgress!=null&&s.continuationProgress.request.sameSelection(payload)){
            failDistanceProgress(s,p,"client-continuation-refused");return;
        }
        if(!openingEpoch&&!pendingEpoch)return;
        if(payload.kind()==NativeDistanceChangePayload.REQUEST){
            if(s.distanceCleanupBlocked||pending!=null&&pending.gate.failed()){PacketDistributor.sendToPlayer(p,payload.failed());return;}
            if(pending==null){
                if(s.stopping||s.collision.motion()==null||!validMode(p,s)||!p.level().dimension().equals(SceneCommands.CARRIER))return;
                // The authoritative body is frozen before any old actor/source owner is released.
                if(!RollingMotionGuard.sameBody(p.getBoundingBox(),s.collision.motion().accepted().body())){fail(s,p,"Distance change lacks exact held body",null);return;}
                pending=new PendingDistance(s,p,payload);pendingDistance=pending;s.stopping=true;
                s.handshake.close();s.collision.motion().fail("native distance change");s.collision.fence();reset(p);
                if(s.ray!=null){s.ray.cancelled().set(true);s.ray=null;}
                s.actor.close();
            }else{
                if(pending.old!=s||!distanceOwnerCurrent(pending,p)||!pending.gate.request(payload))return;
                // A newer selection consumes all prior receipts, even when it returns to the old value.
                if(pendingOpen!=null&&pendingOpen.continuation()==pending)cancelPending(pendingOpen,"newer native distance selection");
                if(pendingPlayable!=null&&pendingPlayable.continuation()==pending)pendingPlayable=null;
                pending.preparing=false;pending.progress.request(payload);
            }
            PacketDistributor.sendToPlayer(p,pending.gate.latest().drain());return;
        }
        if(pending==null||pending.old!=s||!distanceOwnerCurrent(pending,p))return;
        if(payload.kind()==NativeDistanceChangePayload.CLIENT_DRAINED)pending.gate.clientDrained(payload);
        else if(payload.kind()==NativeDistanceChangePayload.FAILED&&pending.gate.latest().sameSelection(payload))distanceFailed(pending,p,"Client distance cleanup or admission failed");
    }
    private static void nativeDistanceProgress(ServerPlayer p,NativeDistanceProgressPayload payload){
        var s=session;if(payload.server()||!owns(p,s)||s.connection!=p.connection)return;
        var pending=pendingDistance;var progress=pending!=null&&pending.old==s?pending.progress:s.continuationProgress;
        if(progress==null||!progress.matchesRequest(payload))return;
        progress.serverTick(p.server.getTickCount(),NativeDistanceProgressState.serverClock(p.server,progress.watchdog.episode())); // A queued packet on the expiry tick cannot revive the prior tick's lease.
        if(progress.watchdog.failed()){if(pending!=null)distanceFailed(pending,p,progress.watchdog.failure());else failDistanceProgress(s,p,progress.watchdog.failure());return;}
        if(payload.actorEpoch()!=progress.actorEpoch)return;
        if(pending!=null){
            if(!distanceOwnerCurrent(pending,p)||payload.resourceGeneration()!=0||payload.publication()!=0||!payload.digest().isEmpty()||!payload.planIdentity().isEmpty())return;
        }else{
            if(s.committedManifest!=null||s.stopping||payload.resourceGeneration()!=1||payload.publication()!=s.handshake.publication())return;
            if(!payload.digest().isEmpty()&&!payload.digest().equals(s.handshake.digest()))return;
            if(payload.digest().isEmpty()&&!payload.planIdentity().isEmpty())return;
        }
        long[] counts=payload.completed();
        if(payload.planIdentity().isEmpty())for(var lane:new NativeDistanceProgressWatchdog.Lane[]{NativeDistanceProgressWatchdog.Lane.SOURCE_COLUMNS,NativeDistanceProgressWatchdog.Lane.PLAN_TILES,NativeDistanceProgressWatchdog.Lane.HALO_ROWS,NativeDistanceProgressWatchdog.Lane.FACE_SCANS,NativeDistanceProgressWatchdog.Lane.PRODUCED_PIECES,NativeDistanceProgressWatchdog.Lane.UPLOADED_PIECES})if(counts[lane.ordinal()]!=0)return;
        if(!progress.header(payload.actorEpoch(),payload.resourceGeneration(),payload.publication(),payload.digest(),payload.planIdentity()))return;
        progress.accept(payload);
        if(progress.watchdog.failed()){if(pending!=null)distanceFailed(pending,p,progress.watchdog.failure());else failDistanceProgress(s,p,progress.watchdog.failure());}
    }
    /** Server owns the watchdog; the exact local pause timeline supplies unpaused wall time. Ticks identify ACKs only. */
    private static void distanceProgressTick(MinecraftServer server){
        var s=session;if(s==null||s.server!=server)return;
        var pending=pendingDistance;var progress=pending!=null&&pending.old==s?pending.progress:s.continuationProgress;
        if(progress==null)return;
        progress.serverTick(server.getTickCount(),NativeDistanceProgressState.serverClock(server,progress.watchdog.episode()));
        var p=server.getPlayerList().getPlayer(s.player);
        if(p==null||p.connection!=s.connection)progress.watchdog.fail("disconnected");
        if(progress.watchdog.failed()){if(pending!=null)distanceFailed(pending,p,progress.watchdog.failure());else failDistanceProgress(s,p,progress.watchdog.failure());return;}
        if(progress.checkpointDue(progress.watchdog.activeNanos(),false))EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_PROGRESS_CHECKPOINT {}",new com.google.gson.Gson().toJson(progress.checkpoint()));
        if(progress.ackDue(System.nanoTime())){var packet=progress.packet(server.getTickCount());if(packet==null){if(pending!=null)distanceFailed(pending,p,progress.watchdog.failure());else failDistanceProgress(s,p,progress.watchdog.failure());return;}PacketDistributor.sendToPlayer(p,packet);}
    }
    private static void failDistanceProgress(Session s,ServerPlayer p,String reason){
        var progress=s.continuationProgress;if(progress==null)return;
        progress.watchdog.fail(reason);lastDistanceProgress=progress.checkpoint();
        if(!progress.failureReported){progress.failureReported=true;EarthPlatformMod.LOGGER.error("NATIVE_DISTANCE_PROGRESS_FAILED {}",new com.google.gson.Gson().toJson(lastDistanceProgress));if(p!=null){PacketDistributor.sendToPlayer(p,progress.request.failed());say(p,"Native distance rebuild blocked: "+reason+". Movement stays held; /earth leave returns safely.");}}
        s.distanceCleanupBlocked=true;stop(s,p,false);
    }
    private static void distanceFailed(PendingDistance pending,ServerPlayer p,String reason){
        pending.gate.fail();pending.progress.watchdog.fail(reason);lastDistanceProgress=pending.progress.checkpoint();
        if(pendingPlayable!=null&&pendingPlayable.continuation()==pending)pendingPlayable=null;
        if(pendingOpen!=null&&pendingOpen.continuation()==pending)cancelPending(pendingOpen,reason);
        if(!pending.failureReported){pending.failureReported=true;EarthPlatformMod.LOGGER.error("NATIVE_DISTANCE_PROGRESS_FAILED {}",new com.google.gson.Gson().toJson(lastDistanceProgress));EarthPlatformMod.LOGGER.error("NATIVE_DISTANCE_RESTART_BLOCKED epoch={} serial={} reason={}",pending.old.epoch,pending.gate.latest().serial(),reason);
            if(p!=null){PacketDistributor.sendToPlayer(p,pending.gate.latest().failed());say(p,"Native distance rebuild is blocked: "+reason+". Movement remains held and your return record is retained; /earth leave returns safely.");}}
    }
    private static void pumpDistance(MinecraftServer server){
        var pending=pendingDistance;if(pending==null||pending.old.server!=server)return;
        var p=server.getPlayerList().getPlayer(pending.old.player);
        if(!distanceOwnerCurrent(pending,p)){
            distanceFailed(pending,p,"Owner, held position or mode changed");
            if(p==null){pendingDistance=null;stop(pending.old,null,false);} // Existing owner-tick drain retains the actor until physical close.
            return;
        }
        if(pending.progress.watchdog.failed()){distanceFailed(pending,p,pending.progress.watchdog.failure());return;}
        if(pending.gate.failed())return;
        if(pending.old.actor.closed().isDone()){
            boolean successful=pending.old.actor.closed().join().successful();pending.gate.serverDrained(successful);if(successful)pending.progress.credit(NativeDistanceProgressWatchdog.Lane.SERVER_DRAINED,1);
            if(!successful){distanceFailed(pending,p,"Old authoritative actor did not close successfully");return;}
        }
        if(!pending.gate.ready()||pending.preparing||System.nanoTime()<pending.retryAfterNanos)return;
        try{
            long epoch=EPOCH.incrementAndGet();
            pendingPlayable=new PendingPlayable(server,p.getUUID(),p.connection,p.level().dimension(),pending.mode,p.position(),epoch,0,pending,pending.gate.latest().serial());
            pending.preparing=true;if(!pending.progress.header(epoch,0,0,"","")){distanceFailed(pending,p,"Continuation preparation identity changed");return;}
            PacketDistributor.sendToPlayer(p,new PlayableEntryPayload(epoch,PlayableEntryPayload.REQUEST,0,""));
        }catch(RejectedExecutionException busy){/* An older source ticket retains its physical slot until settled. Retry bounded by the same hold. */}
        catch(RuntimeException failure){distanceFailed(pending,p,"Fresh immutable source preparation failed");}
    }

    /** Owner-thread admission is the only path that may attach collision, change mode or teleport. */
    private static int open(ServerPlayer p,EnginePaletteContext palette,io.earthplatform.core.CubeProvider provider,
            RollingSessionDescriptor descriptor,long epoch,PreparationEvidence preparation) {
        return open(p,palette,provider,descriptor,epoch,preparation,null);
    }
    private static int open(ServerPlayer p,EnginePaletteContext palette,io.earthplatform.core.CubeProvider provider,
            RollingSessionDescriptor descriptor,long epoch,PreparationEvidence preparation,PendingDistance continuation) {
        WorldIdentity providerWorld=provider instanceof io.earthplatform.minecraft.enginebridge.NativeArenaCubeProvider arena?arena.identity():provider instanceof RollingGateFixture.Provider wrapped?wrapped.identity():null;
        if(providerWorld==null)throw new IllegalArgumentException("Explicit prepared native provider required");
        descriptor.requirePreparedWorld(providerWorld);descriptor.requireAllowedWindow(descriptor.initialCenter());
        if(pendingOpen!=null || RECOVERY.current()!=null || !p.isAlive() || p.isRemoved()
                || p.connection==null || p.connection.player!=p || p.server.getPlayerList().getPlayer(p.getUUID())!=p)return 0;
        if(continuation==null){if(session!=null||CoreReturnPoint.remembered(p).isPresent()||p.level().dimension().equals(SceneCommands.CARRIER))return 0;}
        else if(pendingDistance!=continuation||!distanceOwnerCurrent(continuation,p)||!continuation.gate.ready()
                ||!continuation.old.actor.closed().isDone()||!continuation.old.actor.closed().join().successful())return 0;
        var carrier=p.server.getLevel(SceneCommands.CARRIER);
        if(carrier==null) { say(p,"The empty carrier is no longer available; entry cancelled.");return 0; }
        var origin=continuation==null?new CoreReturnPoint(p.level().dimension(),p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot(),p.gameMode.getGameModeForPlayer()):continuation.old.origin;
        RollingCubeActor actor;
        try {
            var directory=p.server.getWorldPath(LevelResource.ROOT).resolve(descriptor.saveDirectory());
            var policy=descriptor.alpineMode()?new RollingCubeActor.ReplacementPolicy(descriptor.world().datasetVersion(),io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.MATERIAL_POLICY,io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.DETAIL_POLICY,1,descriptor.editPolicy()):descriptor.arenaMode()?RollingArenaQueryGuard.replacementPolicy(descriptor):!descriptor.naturalRelief()&&(descriptor.editPolicy()==io.earthplatform.minecraft.enginebridge.RollingEditPolicy.FULL_CUBE_V1||Boolean.getBoolean(RollingGateFixture.REPLACEMENT_OPT_IN))?new RollingCubeActor.ReplacementPolicy(descriptor.sourceFingerprint(),io.earthplatform.minecraft.lod.DhReplacementInput.ANALYTIC_MATERIAL_POLICY,RollingGateFixture.REPLACEMENT_DETAIL_POLICY,1,descriptor.editPolicy()):null;
            actor=descriptor.arenaMode()?RollingCubeActor.openArena(directory,providerWorld,provider,palette,epoch,descriptor.initialCenter(),policy,descriptor.arenaDefinition(),descriptor.arenaSource().identity(),descriptor.lodLayout().identity(),new io.earthplatform.minecraft.lod.DhArenaAdmission(descriptor.arenaSource()))
                    :descriptor.alpineMode()?RollingCubeActor.resumeEarth(directory,providerWorld,provider,palette,epoch,descriptor.pinCenter(),descriptor.initialCenter(),policy,descriptor.world().datasetVersion(),descriptor.lodLayout().identity(),new io.earthplatform.minecraft.lod.DhNativeDistanceReplacementAdmission())
                    :descriptor.indexedWorkspaces()?RollingCubeActor.openIndexed(directory,providerWorld,provider,palette,epoch,descriptor.initialCenter(),policy,descriptor.flatPolicy(),descriptor.workspaces())
                    :RollingCubeActor.open(directory,providerWorld,provider,palette,epoch,descriptor.initialCenter(),policy,descriptor.twoWorkspaces()?descriptor.flatPolicy():null,descriptor.twoWorkspaces()?descriptor.workspaces():null);
        }
        catch(RuntimeException failed) { EarthPlatformMod.LOGGER.warn("Rolling host admission failed",failed);say(p,"Rolling host could not open; wait for old owners to drain.");return 0; }
        var s=new Session(p,actor,origin,epoch,palette,descriptor,preparation);
        try { s.collision=new CoreCollisionBinding(carrier,p.getUUID(),epoch,descriptor); }
        catch(RuntimeException failed) { actor.close();EarthPlatformMod.LOGGER.warn("Rolling collision attachment failed",failed);say(p,"Another collision owner still holds the carrier.");return 0; }
        s.continuation=continuation!=null;
        if(continuation!=null){s.continuationProgress=continuation.progress;s.continuationPosition=continuation.position;}
        if(continuation!=null){s.walking=continuation.walking;s.distancePredecessorWireEpoch=continuation.gate.latest().epoch();s.distancePredecessorWireSerial=continuation.gate.latest().serial();continuation.old.snapshots.clear();continuation.old.pendingManifest=null;continuation.old.committedManifest=null;continuation.old.stage=null;}
        session=s;origin.remember(p);
        actor.closed().thenAccept(result->EarthPlatformMod.LOGGER.info("ROLLING_GATE_ACTOR_DRAIN epoch={} successful={} detail={}",epoch,result.successful(),result.detail()));
        EarthPlatformMod.LOGGER.info("ROLLING_GATE_OPEN epoch={} descriptor={} sourceFingerprint={} preparationThread={} preparationNanos={} preparationCounters={}",
                epoch,descriptor,descriptor.sourceFingerprint(),preparation.workerThread(),preparation.elapsedNanos(),BoundedProviderPreparation.processDiagnostics());
        if(continuation!=null){
            reset(p);p.noPhysics=continuation.noPhysics;s.collision.attachMotion(p);if(nativeDistanceMode(s))s.collision.motion().enableVisualFrontier();
            say(p,"Rebuilding the full selected native distance at your held position. Movement resumes after the exact native/DH pair is ready.");return 1;
        }
        p.setGameMode(GameType.SPECTATOR);reset(p);
        // Arena entry frames the northern hill and foreground arch at the unchanged spawn.
        // Other profiles retain their measured 31-degree neighboring-ground-cell edit angle.
        p.teleportTo(carrier,descriptor.entry().x,descriptor.entry().y,descriptor.entry().z,Set.of(),entryYaw(descriptor),entryPitch(descriptor));
        if(p.serverLevel()!=carrier || !p.isSpectator()) {
            stop(s,p);boolean restored=restoreOrigin(p,origin);
            say(p,restored?"Carrier entry was cancelled; original state restored.":"Carrier entry and safe return were cancelled; recovery record retained.");return 0;
        }
        p.noPhysics=true;s.collision.attachMotion(p);if(nativeDistanceMode(s))s.collision.motion().enableVisualFrontier();
        if(descriptor.alpineMode()){say(p,"Opening real Everest terrain. The HUD shows the finite exploration and persistent build boundaries. Source elevations remain in metres; local alpine detail is explicitly procedural. Walking begins after exact readiness. /earth leave returns safely.");return 1;}
        if(descriptor.arenaMode()){say(p,"Opening the 512m landscape. The white-marked 32m arena supports persistent digging and diorite building; outside it terrain is read-only. Walking starts after exact readiness. /cubic leave returns safely.");return 1;}
        say(p,"Rolling native gate: moving 125-cube collision window, 27 native targets plus one fixed edit pin. Wait for paired READY, then /earth_roam walk. "
                +(descriptor.naturalRelief()?"Earth X/Z safety limit is ±128m around the source anchor. ":"Diagnostic X/Z safety limit is ±512m. ")
                +"Far layout="+descriptor.lodLayout().id()+", radius="+descriptor.lodLayout().radiusBlocks()+"m, snap="+descriptor.lodLayout().snapBlocks()+"m.");
        return 1;
    }

    static float entryYaw(RollingSessionDescriptor descriptor) { return descriptor.alpineMode()?RollingAlpinePreparation.entryYaw():descriptor.arenaMode()?-150:0; }
    static float entryPitch(RollingSessionDescriptor descriptor) { return descriptor.alpineMode()?RollingAlpinePreparation.entryPitch():descriptor.arenaMode()?-8:31; }

    static boolean nativeDistanceMode(Session s){return s.descriptor.alpineMode()&&s.descriptor.playerFacing();}

    public static void visualFrontierIntent(ServerPlayer p,RollingVisualFrontierIntentPayload intent){
        var s=session;
        if(!owns(p,s)||s.stopping||!nativeDistanceMode(s)||s.collision.motion()==null||!s.collision.exactMotionOwner(p)||!p.isAlive()||!validMode(p,s)||!p.level().dimension().equals(SceneCommands.CARRIER)
                ||s.committedCenter==null||intent.epoch()!=s.epoch||intent.committedPublication()!=s.handshake.committedPublication()||intent.resourceGeneration()!=s.handshake.resourceGeneration())return;
        try{s.descriptor.requireAllowedTransition(s.committedCenter,intent.desiredCenter());}catch(IllegalArgumentException invalid){return;}
        s.collision.motion().visualIntent(intent.epoch(),intent.committedPublication(),intent.resourceGeneration(),intent.intentId(),intent.desiredCenter());
    }

    public static void control(ServerPlayer p, RollingGateControlPayload frame) {
        var s=session;
        if (!owns(p,s) || s.stopping || frame.epoch()!=s.epoch) return;
        if(s.continuationProgress!=null){s.continuationProgress.serverTick(p.server.getTickCount(),NativeDistanceProgressState.serverClock(p.server,s.continuationProgress.watchdog.episode()));if(s.continuationProgress.watchdog.failed()){failDistanceProgress(s,p,s.continuationProgress.watchdog.failure());return;}}
        if (frame.kind()==RollingGateControlPayload.FENCED) {
            stop(s,p,false); say(p,"Rolling resources changed; collision fenced. Use /earth_roam leave, then enter again."); return;
        }
        if (!p.isAlive() || !p.level().dimension().equals(SceneCommands.CARRIER) || !validMode(p,s)) return;
        try {
            if (frame.kind()==RollingGateControlPayload.REPLACEMENT_DECLINED) { replacementDeclined(p,s,frame); return; }
            if (frame.kind()==RollingGateControlPayload.MOUSE) { mouseIntent(p,s,frame); return; }
            if (!authoritativePairMatches(s)) return;
            if (frame.kind()==RollingGateControlPayload.PREPARED) {
                if (s.handshake.prepared(frame.epoch(),frame.generation(),frame.resourceGeneration(),frame.digest())) {if(s.continuationProgress!=null)s.continuationProgress.credit(NativeDistanceProgressWatchdog.Lane.PREPARED_PAIR,1);tryGrant(p,s);}
            } else if (frame.kind()==RollingGateControlPayload.PAIR_READY
                    && s.handshake.pairReady(frame.epoch(),frame.generation(),frame.resourceGeneration(),frame.digest())) {
                tryCommitReady(p,s);
            }
        } catch (RuntimeException failed) { fail(s,p,"Paired publication failed",failed); }
    }

    private static RollingMotionGuard.Pair motionPair(Session s){return new RollingMotionGuard.Pair(s.epoch,s.handshake.publication(),s.pendingManifest.generation(),s.pendingManifest.candidateCenter(),s.handshake.resourceGeneration(),s.handshake.digest());}
    private static boolean preparedBodyKnown(Session s,RollingMotionGuard.Pose pose) {
        // Publication-boundary-only validation; never decode snapshots in a movement callback.
        var snapshots=windowSnapshots(s,s.pendingManifest.candidateCenter());
        return CoreCollisionBinding.preparedBodyKnown(s.world,s.epoch,s.palette,snapshots,pose.body(),
                permitOccupiedBodyAtBootstrap(s.collision.motion().bootstrap(),s.continuation,pose.spectator(),pose.noPhysics()));
    }
    /** Occupancy policy only: preparedBodyKnown must still certify every body cell from the exact native window. */
    static boolean permitOccupiedBodyAtBootstrap(boolean bootstrap,boolean validatedContinuation,boolean spectator,boolean noPhysics){
        return (!bootstrap||validatedContinuation)&&spectator&&noPhysics;
    }
    private static void tryGrant(ServerPlayer p, Session s) {
        if (s.handshake.state()!=RollingPublicationHandshake.State.PREPARED || s.pendingManifest==null) return;
        if (!authoritativePairMatches(s)) throw new IllegalStateException("Prepared native/far pair no longer authoritative");
        var motion=s.collision.motion();var pair=motionPair(s);
        if(motion.correctionPending())return;
        if(!motion.visualCameraKnown(p.getBoundingBox(),s.pendingManifest.candidateCenter()))return; // Client detects an impossible candidate before PREPARED and performs one bounded same-pose rebuild.
        if (!bodyInsideWindow(p.getBoundingBox(),s.pendingManifest.candidateCenter())) {
            var anchor=motion.recover(pair,s.collision.pose(p),s.collision::certify,System.nanoTime());
            s.waitingForBody=true;s.collision.correctMotion(p);
            if(!RollingMotionGuard.sameBody(anchor.body(),p.getBoundingBox())||!motion.correctionPending())throw new IllegalStateException("Exact overlap correction was cancelled");
            EarthPlatformMod.LOGGER.warn("ROLLING_BODY_RECOVER_ONCE epoch={} publication={} candidate={} anchor={}",s.epoch,s.handshake.publication(),pair.candidate(),anchor.feet());
            return;
        }
        var pose=s.collision.pose(p);
        if(!preparedBodyKnown(s,pose))throw new IllegalStateException("Prepared body is unknown or walking entry is occupied");
        motion.beginCommit(pair,pose,s.editPublication?ignored->true:s.collision::certify,System.nanoTime());
        s.waitingForBody=false;
        // COMMIT_HOLD survives collision invalidation and applies to spectators too.
        s.collision.fence(); reset(p); s.handshake.grant(); sendPair(p,s,RollingGatePayload.ALLOW);
    }

    /** In-flight move packets may request a correction after ALLOW; acknowledgment must settle before release. */
    private static void tryCommitReady(ServerPlayer p,Session s){
        if(s.handshake.state()!=RollingPublicationHandshake.State.PAIR_READY||s.pendingManifest==null||s.collision.motion().correctionPending())return;
        if(!authoritativePairMatches(s)||!s.collision.motion().visualCameraKnown(p.getBoundingBox(),s.pendingManifest.candidateCenter())||!bodyInsideWindow(p.getBoundingBox(),s.pendingManifest.candidateCenter()))throw new IllegalStateException("Held ready pair changed before commit");
        if(s.editPublication||s.motionActorCommitted){finishPublication(p,s);return;}
        if(!s.motionCommitQueued){
            if(s.actor.commitPrepared(s.pendingManifest.generation(),replacementIdentity(s.pendingManifest))!=RollingCubeActor.Admission.QUEUED)throw new IllegalStateException("Exact actor commit was not admitted");
            s.motionCommitQueued=true;
        }
    }

    /** Contains the complete positive finite body, not merely its feet or the permanent edit pin. */
    static boolean bodyInsideWindow(AABB body, CubePos center) {
        return RollingWindowAdmission.contains(body,center);
    }

    private static Map<CubePos,EngineCubeSnapshot> windowSnapshots(Session s, CubePos center) {
        if (center==null) throw new IllegalStateException("No committed window");
        s.descriptor.requireAllowedWindow(center);
        var result=new HashMap<CubePos,EngineCubeSnapshot>(125);
        for (var pos:RollingCubePlan.stationary(activeFocus(s),center).currentOwners()) {
            var snapshot=s.snapshots.get(pos);
            if (snapshot==null || !s.world.equals(snapshot.world()) || snapshot.epoch()!=s.epoch)
                throw new IllegalStateException("Authoritative moving window incomplete");
            result.put(pos,snapshot);
        }
        return Map.copyOf(result);
    }

    private static void finishPublication(ServerPlayer p,Session s) {
        var ready=s.pendingManifest;
        if (ready==null || !authoritativePairMatches(s) || !s.collision.motion().visualCameraKnown(p.getBoundingBox(),ready.candidateCenter()) || !bodyInsideWindow(p.getBoundingBox(),ready.candidateCenter()))
            throw new IllegalStateException("Cannot publish collision outside exact known candidate");
        authorizePublication(s.actor,s.handshake,ready,s.replacementEnabled,s.descriptor.arenaMode());
        var collision=windowSnapshots(s,ready.candidateCenter());
        s.collision.publish(s.world,s.epoch,s.palette,collision);
        s.committedCenter=ready.candidateCenter(); s.committedManifest=ready; s.publishedFarCenter=s.pendingFarCenter;
        var keep=new HashSet<CubePos>(); for(var version:ready.versions())keep.add(version.position());
        s.snapshots.keySet().retainAll(keep);
        var motionAuthority=s.collision.authority(ready.generation(),s.handshake.publication(),s.handshake.digest(),s.handshake.resourceGeneration());
        s.collision.motion().committed(motionAuthority,ready.candidateCenter(),s.collision.pose(p,motionAuthority),s.collision::certify);
        s.handshake.committed(); sendPair(p,s,RollingGatePayload.COMMITTED);
        var diagnostics=s.actor.diagnostics();
        EarthPlatformMod.LOGGER.info("ROLLING_GATE_COMMITTED epoch={} publication={} actorGeneration={} center={} owners={} collisionOwners={} digest={} residentCubes={} physicalProviders={} queuedGeneration={} queuedEvents={} queuedCommands={} snapshotReferences={} actorDiagnostics={}",
            s.epoch,s.handshake.publication(),ready.generation(),s.committedCenter,s.snapshots.size(),s.collision.residentOwners(),s.handshake.digest(),
            diagnostics.residentCubes(),diagnostics.physicalProviders(),diagnostics.queuedGeneration(),diagnostics.queuedEvents(),diagnostics.queuedCommands(),diagnostics.retainedSnapshotReferenceSlots(),diagnostics);
        if(s.descriptor.twoWorkspaces())EarthPlatformMod.LOGGER.info("ROLLING_REPLACEMENT_SET_COMMITTED epoch={} publication={} focus={} pinned={} set={} durable={} regions={}",s.epoch,s.handshake.publication(),ready.activeFocus(),ready.pinned(),ready.replacementSet().identity(),ready.replacementSet().durableIdentity(),ready.replacementSet().regions().stream().map(r->r.position()+":"+r.revision()).toList());
        if(s.descriptor.alpineMode())EarthPlatformMod.LOGGER.info("EARTH_REPLACEMENT_OWNERS epoch={} publication={} diagnostics={} capture={}",s.epoch,s.handshake.publication(),s.actor.earthDiagnostics(),ready.earthReplacementCapture().contentIdentity());
        if(s.replacementEnabled)EarthPlatformMod.LOGGER.info("ROLLING_REPLACEMENT_COMMITTED epoch={} publication={} pinned={} originalResident={} certificate={} durableRevision={}",
                s.epoch,s.handshake.publication(),ready.pinned(),s.snapshots.containsKey(activeFocus(s)),ready.replacementCertificate()==null?"NONE":ready.replacementCertificate().identity(),ready.replacementCertificate()==null?-1:ready.replacementCertificate().originalRevision());
        boolean first=ready.generation()==1 && !s.editPublication;
        s.stage=null; s.pendingManifest=null; s.pendingFarCenter=null; s.editPublication=false; s.editPending=false; s.waitingForBody=false;s.motionCommitQueued=false;s.motionActorCommitted=false;
        if(first&&s.continuation){if(s.continuationProgress!=null){lastDistanceProgress=s.continuationProgress.checkpoint();EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_PROGRESS_COMPLETED {}",new com.google.gson.Gson().toJson(lastDistanceProgress));s.continuationProgress=null;}s.distancePredecessorWireEpoch=0;s.distancePredecessorWireSerial=0;say(p,"Native render distance rebuilt; your position, mode and original editable cube are preserved.");}
        else if(first&&s.descriptor.alpineMode()){walk(p,true);say(p,"Everest ready. Left click digs; right click places diorite inside the displayed original-cube interior. Surrounding real terrain is read-only. /earth fly, walk, status or leave.");}
        else if(first&&s.descriptor.arenaMode()){walk(p,true);say(p,"Landscape ready. Left click digs, right click places diorite inside X/Z[0,32), Y[128,160). White markers show the arena. Explore to ±256m; /cubic leave returns.");}
        else if(first)say(p,"Rolling pair READY. Native collision owns exactly 125 current cubes; /earth_roam walk is available.");
    }

    /**
     * Complete actor authority before collision and COMMITTED become visible. An edit keeps the
     * rolling generation but issues a fresh certificate; arena authority must commit that exact
     * certificate too. Initial/movement commits have already consumed actor Committed, so this
     * authorization is idempotent for their certificate. Retirement still waits for a later move.
     * The caller has independently validated the ready manifest, pair digest and body containment.
     */
    static void authorizePublication(RollingCubeActor actor,RollingPublicationHandshake handshake,
            RollingCubeActor.StageReady ready,boolean replacementEnabled,boolean arenaMode) {
        if(handshake.state()!=RollingPublicationHandshake.State.PAIR_READY)
            throw new IllegalStateException("Exact paired acknowledgement required before actor authorization");
        if(arenaMode&&(!replacementEnabled||ready==null||ready.arenaCertificate()==null))
            throw new IllegalStateException("Arena publication requires enabled exact arena authority");
        if(replacementEnabled&&replacementIdentity(ready)!=null){
            var admission=actor.authorizePinRetirement(ready.generation(),replacementIdentity(ready));
            if(admission!=RollingCubeActor.Admission.QUEUED)throw new IllegalStateException("Committed replacement authorization rejected: "+admission);
        }else if(replacementEnabled&&!arenaMode){
            var admission=actor.retainPinWithoutReplacement(ready.generation());
            if(admission!=RollingCubeActor.Admission.QUEUED)throw new IllegalStateException("Pinned fallback authorization rejected: "+admission);
        }
    }

    private static void sendPair(ServerPlayer p, Session s, int kind) {
        send(p,RollingGatePayload.create(b -> { b.writeByte(kind); b.writeLong(s.epoch); b.writeLong(s.handshake.publication()); b.writeLong(s.handshake.resourceGeneration()); b.writeUtf(s.handshake.digest(),64); }));
    }

    /** Re-derive from the authoritative ready record before any client proof can advance the pair. */
    private static boolean authoritativePairMatches(Session s) {
        var ready=s.pendingManifest;
        return ready!=null && manifestMatches(s,ready)
                && RollingFarPublication.center(s.descriptor,ready.candidateCenter()).equals(s.pendingFarCenter)
                && RollingFarPublication.digest(s.descriptor,ready).equals(s.handshake.digest());
    }

    private static boolean manifestMatches(Session s, RollingCubeActor.StageReady ready) {
        s.descriptor.requireAllowedWindow(ready.candidateCenter());
        if(s.descriptor.alpineMode())RollingEarthReplacementWire.require(ready.replacementCertificate(),ready.earthReplacementCapture());
        if(!s.world.equals(ready.world()) || ready.epoch()!=s.epoch)return false;
        CubePos focus=ready.activeFocus()==null?activeFocus(s):ready.activeFocus();
        if(s.descriptor.arenaMode()){
            RollingArenaQueryGuard.require(s.descriptor,ready.arenaCertificate());ready.arenaCertificate().verifyCapture(ready.arenaCapture());
            if(ready.pinned()||ready.activeFocus()!=null||!s.actor.replacementCurrent(ready.arenaCertificate().identity()))return false;
        }else if(ready.arenaCertificate()!=null)return false;
        if(s.descriptor.twoWorkspaces()){
            if(ready.replacementSet()==null)return false;
            RollingIndexedQueryGuard.require(s.descriptor,ready.replacementSet(),ready.candidateCenter());
            if(ready.replacementSet()==null||!s.descriptor.workspaces().equals(ready.replacementSet().workspaces())||!s.descriptor.workspaces().contains(focus)
                    ||!s.actor.replacementCurrent(ready.replacementSet().identity()))return false;
        }else if(ready.replacementSet()!=null)return false;
        var expected=RollingCubePlan.stationary(focus,ready.candidateCenter(),ready.pinned()).committedOwners();
        if(!ready.pinned()&&replacementIdentity(ready)==null)return false;
        if(s.stage!=null&&s.stage.candidatePinned()!=ready.pinned())return false;
        if(ready.versions().size()!=expected.size())return false;
        for(int i=0;i<expected.size();i++) {
            var version=ready.versions().get(i); var snapshot=s.snapshots.get(version.position());
            if(!expected.get(i).equals(version.position()) || snapshot==null || !s.world.equals(snapshot.world())
                || snapshot.epoch()!=s.epoch || snapshot.leaseGeneration()!=version.leaseGeneration() || snapshot.revision()!=version.revision())return false;
        }
        return true;
    }

    private static void replacementDeclined(ServerPlayer p,Session s,RollingGateControlPayload frame) {
        var ready=s.pendingManifest;
        if(s.descriptor.alpineMode()||s.descriptor.arenaMode()||s.descriptor.twoWorkspaces()||!s.replacementEnabled||ready==null||ready.replacementCertificate()==null
                ||!ready.pinned()||s.committedManifest!=null&&!s.committedManifest.pinned()
                ||s.stage!=null&&!s.stage.currentPinned()||!authoritativePairMatches(s))
            throw new IllegalStateException("Replacement refusal cannot uncover an unpinned edited volume");
        var fallback=new RollingCubeActor.StageReady(ready.world(),ready.epoch(),ready.generation(),ready.candidateCenter(),ready.versions(),null,true);
        String nextDigest=RollingFarPublication.digest(s.descriptor,fallback);
        long previous=s.handshake.publication();
        long next=s.handshake.retryReady(frame.epoch(),frame.generation(),frame.resourceGeneration(),frame.digest(),nextDigest);
        s.pendingManifest=fallback;s.replacementDeclines++;
        send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.REPLACEMENT_FALLBACK);b.writeLong(s.epoch);b.writeLong(previous);b.writeLong(next);RollingGateWire.writeReady(b,fallback);}));
        EarthPlatformMod.LOGGER.info("ROLLING_REPLACEMENT_DECLINED epoch={} previousPublication={} fallbackPublication={} pinRetained=true",s.epoch,previous,next);
    }

    private static void mouseIntent(ServerPlayer p,Session s,RollingGateControlPayload intent) {
        if((!s.descriptor.playerFacing()&&!p.createCommandSourceStack().hasPermission(2)) || !s.handshake.acceptsMouse(intent.epoch(),intent.generation(),intent.resourceGeneration())) { rejectIntent(p,s,"No exact committed operator pair"); return; }
        if(s.descriptor.alpineMode()&&(s.committedCenter==null||!io.earthplatform.core.CubeTickets.box(s.committedCenter,1,1,27).contains(s.descriptor.target()))){rejectIntent(p,s,"Earth editable cube is outside the nearby native window; return to the marked edit area");return;}
        if(!s.descriptor.arenaMode()&&s.committedManifest!=null&&!s.committedManifest.pinned()){if(reserveSameCenter(s)&&s.actor.requestPinRestore()!=RollingCubeActor.Admission.QUEUED)s.collision.motion().cancelUnstartedReservation();rejectIntent(p,s,"Restoring original edit dependencies; retry after READY");return;}
        if(s.editPending || s.ray!=null || s.committedCenter==null || s.actor.diagnostics().lifecycle()!=RollingCubeActor.Lifecycle.OPEN) { rejectIntent(p,s,"Rolling stage or previous edit is not ready"); return; }
        long now=System.nanoTime();
        if(s.intentReceived && now-s.lastIntentNanos<250_000_000L) { rejectIntent(p,s,"Interaction rate limit"); return; }
        s.intentReceived=true; s.lastIntentNanos=now;
        var snapshots=windowSnapshots(s,s.committedCenter);
        if(s.descriptor.arenaMode()?!s.descriptor.arenaDefinition().insideWindow(s.committedCenter):!snapshots.containsKey(activeFocus(s))) { rejectIntent(p,s,"Active editable cube is outside the current collision window"); return; }
        var eye=p.getEyePosition(); var look=p.getViewVector(1); var body=p.getBoundingBox(); var cancelled=new AtomicBoolean();
        try {
            var future=RAY_WORKER.submit(cancelled,()->s.descriptor.arenaMode()?NativeRayEditPlanner.planArena(s.world,s.epoch,s.palette,snapshots,eye,look,8,intent.place()?NativeRayEditPlanner.Action.PLACE_DIORITE:NativeRayEditPlanner.Action.REMOVE,s.descriptor.arenaDefinition(),body):NativeRayEditPlanner.plan(s.world,s.epoch,s.palette,snapshots,eye,look,8,intent.place()?NativeRayEditPlanner.Action.PLACE_DIORITE:NativeRayEditPlanner.Action.REMOVE,activeFocus(s),body));
            s.ray=new PendingRay(future,cancelled,eye,look);
        } catch(RejectedExecutionException busy) { rejectIntent(p,s,"Bounded ray worker is busy"); }
    }

    private static void pumpRay(ServerPlayer p,Session s) {
        var pending=s.ray; if(pending==null || !pending.result().isDone())return; s.ray=null;
        if(s.stopping || p==null)return;
        try {
            var result=pending.result().join();
            if(result instanceof NativeRayEditPlanner.Rejected rejected) { rejectIntent(p,s,rejected.detail()); return; }
            var plan=(NativeRayEditPlanner.Plan)result;
            boolean active=owns(p,s) && !s.stopping && !s.handshake.pending() && !s.editPending && p.isAlive()
                && (s.descriptor.playerFacing()||p.createCommandSourceStack().hasPermission(2)) && validMode(p,s) && p.level().dimension().equals(SceneCommands.CARRIER);
            String refusal=CoreIntentCommitGate.evaluate(active,pending.eye(),pending.look(),p.getEyePosition(),p.getViewVector(1),p.getBoundingBox(),plan,windowSnapshots(s,s.committedCenter));
            if(refusal!=null) { rejectIntent(p,s,refusal); return; }
            var request=plan.request();
            if(!s.descriptor.permitsEdit(s.descriptor.arenaMode()?request.position():activeFocus(s),request.x(),request.y(),request.z())) { rejectIntent(p,s,"Outside original cube edit policy: "+s.descriptor.editPolicy().id()); return; }
            // Clear an old coalesced goal BEFORE edit admission. Keep holding this center until the
            // edit's paired publication completes, including after the durable ACK is consumed.
            if(s.collision.motion().reservation()!=null){rejectIntent(p,s,"Reserved rolling step is not ready");return;}
            s.descriptor.requireAllowedWindow(s.committedCenter);s.actor.requestCenter(s.committedCenter);
            var edit=new RollingCubeActor.EditRequest(request.world(),request.epoch(),request.position(),request.leaseGeneration(),request.expectedRevision(),request.x(),request.y(),request.z(),request.stateId());
            var admitted=s.actor.tryEdit(edit);
            if(admitted!=RollingCubeActor.Admission.QUEUED) { rejectIntent(p,s,"Edit not admitted: "+admitted); return; }
            s.collision.motion().editHold(s.committedManifest.generation(),s.committedCenter);
            s.editTarget=edit.position();s.editPending=true; s.editPublication=true; long publication=s.handshake.begin();
            s.collision.fence(); reset(p);
            send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.EDIT_BEGIN);b.writeLong(s.epoch);b.writeLong(publication);b.writeLong(edit.x());b.writeLong(edit.y());b.writeLong(edit.z());}));
            EarthPlatformMod.LOGGER.info("ROLLING_GATE_EDIT_BEGIN epoch={} publication={} target={},{},{} revision={}",s.epoch,publication,edit.x(),edit.y(),edit.z(),edit.expectedRevision());
        } catch(CompletionException|CancellationException failed) { rejectIntent(p,s,"Ray preparation failed or was cancelled"); }
    }

    private static void rejectIntent(ServerPlayer p,Session s,String detail) {
        String bounded=detail.length()>512?detail.substring(0,512):detail;
        if(s.scenarioAction!=null&&s.scenarioActionStarted&&s.scenarioCompletedAction<s.scenarioAction.id()){
            s.scenarioOutcome=RollingIndexedScenario.Outcome.REFUSED;
            if(s.scenarioAction.kind()!=RollingIndexedScenario.Kind.OUTSIDE_EDIT_REFUSAL||!bounded.contains("OUTSIDE_EDITABLE_CUBE"))s.scenarioFailure="Unexpected harness edit refusal: "+bounded;
        }
        send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.REJECT);b.writeLong(s.epoch);b.writeUtf(bounded,512);}));
        long now=System.nanoTime();if(s.feedbackSent && now-s.lastFeedbackNanos<250_000_000L)return;
        s.feedbackSent=true;s.lastFeedbackNanos=now;say(p,"Rolling interaction rejected: "+bounded);
    }

    private static boolean validMode(ServerPlayer p,Session s) { return p.isSpectator() || s.walking && p.gameMode.getGameModeForPlayer()==GameType.ADVENTURE; }
    private static int walk(ServerPlayer p,boolean enable) {
        var s=session;
        if(!owns(p,s)||s.stopping||!p.level().dimension().equals(SceneCommands.CARRIER)) { say(p,"An active rolling session is required.");return 0; }
        if(s.collision.motion()!=null&&!s.collision.motion().translating()){say(p,"Mode changes wait for the held body pair");return 0;}
        if(enable) {
            if(s.handshake.pending() || s.handshake.committedPublication()==0 || !s.collision.ready()) { say(p,"Walking waits for the exact native/DH and authoritative collision pair.");return 0; }
            var snapshots=windowSnapshots(s,s.committedCenter);
            var query=new NativeKnownCubeQueries(s.world,s.epoch,s.palette,snapshots);
            var result=query.overlaps(p.getBoundingBox(),stamp->{var v=snapshots.get(stamp.position());return v!=null&&stamp.equals(NativeKnownCubeQueries.CubeStamp.of(v));});
            if(result.status()!=NativeKnownCubeQueries.Status.CLEAR) { say(p,"Your current body is occupied or outside the known window; fly to a clear position first.");return 0; }
            p.setGameMode(GameType.ADVENTURE);
            if(p.gameMode.getGameModeForPlayer()!=GameType.ADVENTURE) { say(p,"Another mod cancelled Adventure mode; walking was not activated.");return 0; }
            s.walking=true;p.noPhysics=false;p.getAbilities().flying=false;p.onUpdateAbilities();reset(p);
            say(p,"Native walking enabled. Unknown boundaries stop motion while the next window prepares.");
        } else {
            p.setGameMode(GameType.SPECTATOR);
            if(!p.isSpectator()) { say(p,"Another mod cancelled spectator mode; walk state retained.");return 0; }
            s.walking=false;p.noPhysics=true;reset(p);say(p,"Spectator flight restored.");
        }
        s.collision.motion().modeChanged(s.collision.pose(p),s.collision::certify);
        return 1;
    }
    private static int status(ServerPlayer p) {
        var pending=pendingOpen;
        if(pending!=null && pending.server()==p.server && pending.player().equals(p.getUUID())) {
            say(p,"Earth preparation epoch="+pending.epoch()+" state="+pending.ticket().state()+" counters="+BoundedProviderPreparation.processDiagnostics());return 1;
        }
        var s=session;if(!owns(p,s)) { say(p,"No rolling session.");return 0; }
        var target=s.snapshots.get(activeFocus(s));reportPhysics(s,p,"STATUS");
        var d=s.actor.diagnostics();
        if(s.descriptor.alpineMode()){
            var ready=s.committedManifest;var certificate=ready==null?null:ready.replacementCertificate();
            say(p,"Everest "+s.handshake.state()+"; original edit revision "+(certificate==null?"pending":certificate.originalRevision())+". Source anchor 7314.399947 m, local detail -0.011741 m, ground Y7314. 512 m exploration, 2 km far terrain; original-cube interior edits only.");
            EarthPlatformMod.LOGGER.info("EARTH_REPLACEMENT_STATUS epoch={} publication={} diagnostics={} certificate={} capture={}",s.epoch,s.handshake.publication(),s.actor.earthDiagnostics(),certificate==null?"NONE":certificate.identity(),ready==null||ready.earthReplacementCapture()==null?"NONE":ready.earthReplacementCapture().contentIdentity());
            return 1;
        }
        say(p,"Rolling epoch="+s.epoch+" publication="+s.handshake.publication()+" state="+s.handshake.state()+" center="+s.committedCenter+" candidate="+(s.pendingManifest==null?d.candidateCenter():s.pendingManifest.candidateCenter())+" retained="+s.snapshots.size()+"/213 collision="+s.collision.residentOwners()+"/125 pinRevision="+(target==null?"unknown":target.revision())+" waitingBody="+s.waitingForBody+" editPending="+s.editPending+" draining="+s.stopping+" descriptor="+s.descriptor.fingerprint()+" source="+s.descriptor.sourceFingerprint()+" preparationThread="+s.preparation.workerThread()+" preparationMs="+(s.preparation.elapsedNanos()/1_000_000)+" preparationCounters="+BoundedProviderPreparation.processDiagnostics()+" farPolicy="+RollingFarPublication.POLICY_VERSION+" farRadius="+s.descriptor.lodLayout().radiusBlocks()+" farSnap="+s.descriptor.lodLayout().snapBlocks()+" farLayout="+s.descriptor.lodLayout().id()+" farCenterAlignment="+s.descriptor.lodLayout().centerAlignmentBlocks()+" desiredFarCenter="+s.desiredFarCenter+" pendingFarCenter="+s.pendingFarCenter+" publishedFarCenter="+s.publishedFarCenter);
        return 1;
    }
    private static boolean restoreOrigin(ServerPlayer p,CoreReturnPoint origin){
        if(!p.isAlive()||p.connection==null||p.connection.player!=p)return false;
        var target=p.server.getLevel(origin.dimension());if(target==null)return false;
        p.setGameMode(GameType.SPECTATOR);reset(p);p.teleportTo(target,origin.x(),origin.y(),origin.z(),Set.of(),origin.yaw(),origin.pitch());
        if(p.serverLevel()!=target||Math.abs(p.getX()-origin.x())>1e-7||Math.abs(p.getY()-origin.y())>1e-7||Math.abs(p.getZ()-origin.z())>1e-7)return false;var mode=p.server.isHardcore()?GameType.SPECTATOR:origin.mode();p.setGameMode(mode);if(p.gameMode.getGameModeForPlayer()!=mode)return false;CoreReturnPoint.clear(p);releaseRecoveredSafety(p);return true;
    }
    private static void releaseRecoveredSafety(ServerPlayer p){
        if(p.level().dimension().equals(SceneCommands.CARRIER))return;
        var carrier=p.server.getLevel(SceneCommands.CARRIER);if(carrier==null)return;
        var view=((io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess)carrier).earthplatform$coreCollisionView();
        if(view instanceof CoreCollisionBinding binding&&binding.safetyOwnedBy(p.getUUID())&&(session==null||session.stopping))binding.detach();
    }
    private static void releaseDisconnectedSafety(ServerPlayer p){
        var carrier=p.server.getLevel(SceneCommands.CARRIER);if(carrier==null)return;
        var view=((io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess)carrier).earthplatform$coreCollisionView();
        if(view instanceof CoreCollisionBinding binding&&binding.safetyOwnedBy(p.getUUID()))binding.detach();
    }
    private static int recoverOrphan(ServerPlayer p){
        if(!allowed(p)||!p.isAlive())return 0;
        var remembered=CoreReturnPoint.remembered(p);
        if(remembered.isPresent()){
            if(restoreOrigin(p,remembered.get())){say(p,"Recovered the saved pre-carrier position and mode");return 1;}
            p.setGameMode(GameType.SPECTATOR);reset(p);say(p,"Saved carrier return was cancelled; recovery record retained");return 0;
        }
        if(!p.level().dimension().equals(SceneCommands.CARRIER))return 0;
        p.setGameMode(GameType.SPECTATOR);reset(p);
        var target=p.server.overworld();var pos=target.getSharedSpawnPos();p.teleportTo(target,pos.getX()+.5,pos.getY()+1,pos.getZ()+.5,Set.of(),target.getSharedSpawnAngle(),0);
        if(p.serverLevel()==target&&p.isSpectator()){CoreReturnPoint.clear(p);releaseRecoveredSafety(p);say(p,"No valid carrier return record; recovered safely to Overworld spectator");return 1;}
        say(p,"Carrier return was cancelled; spectator safety remains active");return 0;
    }
    private static int leave(ServerPlayer p){if(pendingDistance!=null&&pendingDistance.player==p){pendingDistance.progress.watchdog.fail("leave-command");lastDistanceProgress=pendingDistance.progress.checkpoint();var old=pendingDistance.old;long wireEpoch=pendingDistance.gate.latest().epoch();if(pendingPlayable!=null&&pendingPlayable.continuation()==pendingDistance)pendingPlayable=null;if(pendingOpen!=null&&pendingOpen.continuation()==pendingDistance)cancelPending(pendingOpen,"leave during distance rebuild");pendingDistance=null;send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.STOP);b.writeLong(wireEpoch);}));stop(old,p);return restoreOrigin(p,old.origin)?1:0;}if(pendingPlayable!=null&&pendingPlayable.server()==p.server&&pendingPlayable.player().equals(p.getUUID())){pendingPlayable=null;say(p,"Playable entry cancelled");return 1;}var pending=pendingOpen;if(pending!=null&&pending.server()==p.server&&pending.player().equals(p.getUUID())){cancelPending(pending,"leave command");say(p,"Earth entry cancelled; your world and mode were not changed.");return 1;}var s=session;if(!owns(p,s))return recoverOrphan(p);stop(s,p);return restoreOrigin(p,s.origin)?1:0;}
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){
        if(e.getEntity() instanceof ServerPlayer p){cancelPendingPlayer(p,"logout");if(!RollingGateFixture.enabled())return;var s=session;if(owns(p,s)){stop(s,p);if(p.level().dimension().equals(SceneCommands.CARRIER))restoreOrigin(p,s.origin);}releaseDisconnectedSafety(p);}
    }
    @SubscribeEvent public static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e){
        if(e.getEntity() instanceof ServerPlayer p){
            cancelPendingPlayer(p,"login or connection replacement");if(session==null)recoverOrphan(p);
        }
    }
    @SubscribeEvent public static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e){
        if(e.getEntity() instanceof ServerPlayer p)cancelPendingPlayer(p,"death");
    }
    @SubscribeEvent public static void clonePlayer(net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone e){
        if(!(e.getEntity() instanceof ServerPlayer p))return;
        cancelPendingPlayer(p,e.isWasDeath()?"death clone":"player replacement");if(!allowed(p))return;
        CoreReturnPoint.copyRecovery(e.getOriginal().getPersistentData(),p.getPersistentData());
        var s=session;if(e.isWasDeath()&&s!=null&&s.server==p.server&&s.player.equals(p.getUUID())){
            // Death/drop policy has already run. Drain without rewriting the dying player's mode.
            stop(s,e.getOriginal() instanceof ServerPlayer original?original:null,false);
        }
    }
    @SubscribeEvent public static void respawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent e){
        if(!(e.getEntity() instanceof ServerPlayer p)||!allowed(p))return;
        var remembered=CoreReturnPoint.remembered(p);if(remembered.isEmpty())return;
        // The respawn event runs before connection.player is replaced. Never teleport here.
        if(!RECOVERY.offer(p.getUUID(),p,remembered.get(),old->old.isRemoved()||!old.isAlive()))
            EarthPlatformMod.LOGGER.warn("Recovery slot busy; validated player-owned return record retained");
    }
    private static void pumpRecovery(MinecraftServer server){
        var pending=RECOVERY.current();if(pending==null||pending.player().server!=server)return;
        var p=pending.player();
        if(p.isRemoved()||!p.isAlive()||!RECOVERY.matches(pending,CoreReturnPoint.remembered(p))){RECOVERY.clear(pending);return;}
        if(p.connection==null||p.connection.player!=p||server.getPlayerList().getPlayer(p.getUUID())!=p)return;
        RECOVERY.clear(pending);
        if(!restoreOrigin(p,pending.origin())){p.setGameMode(GameType.SPECTATOR);reset(p);say(p,"Native gate ended at death; safe return was cancelled, recovery record retained");}
        else say(p,"Native gate ended at death; original location and mode restored");
    }
    @SubscribeEvent public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent e){
        if(pendingDistance!=null&&pendingDistance.old.server==e.getServer())pendingDistance=null;
        if(pendingPlayable!=null&&pendingPlayable.server()==e.getServer())pendingPlayable=null;
        var pending=pendingOpen;if(pending!=null&&pending.server()==e.getServer())cancelPending(pending,"server stopping");
        pumpRecovery(e.getServer());
        var s=session;if(s!=null&&s.server==e.getServer()){var p=s.server.getPlayerList().getPlayer(s.player);stop(s,p);if(p!=null&&p.level().dimension().equals(SceneCommands.CARRIER))restoreOrigin(p,s.origin);}
    }
    private static void stop(Session s,ServerPlayer p) { stop(s,p,p!=null&&p.isAlive()); }
    private static void stop(Session s,ServerPlayer p,boolean restoreSafeMode) {
        if(s.continuationProgress!=null&&!s.continuationProgress.watchdog.failed()){s.continuationProgress.watchdog.fail("session-closing");lastDistanceProgress=s.continuationProgress.checkpoint();}
        if(s.arenaScenario!=null&&(s.arenaScenario.status()==RollingArenaScenario.Status.RUNNING||!s.scenarioCapture.isEmpty()&&s.arenaScenario.status()!=RollingArenaScenario.Status.FAIL)){s.arenaScenario.cancel("Session closed before arena scenario/checkpoint completion");writeArenaScenario(s);}
        if(s.scenario!=null&&(s.scenario.status()==RollingIndexedScenario.Status.RUNNING||!s.scenarioCapture.isEmpty()&&s.scenario.status()!=RollingIndexedScenario.Status.FAIL)){
            s.scenario.cancel("Session closed before scenario/checkpoint completion");writeScenario(s);
        }
        if(p!=null&&s.distancePredecessorWireEpoch>0&&s.distancePredecessorWireEpoch!=s.epoch)
            send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.STOP);b.writeLong(s.distancePredecessorWireEpoch);}));
        if(s.stopping)return;
        if(p!=null)reportPhysics(s,p,"PRE_CLOSE");
        s.stopping=true;
        if(restoreSafeMode&&p!=null&&s.walking&&p.gameMode.getGameModeForPlayer()==GameType.ADVENTURE) {
            s.walking=false;p.setGameMode(GameType.SPECTATOR);reset(p);
        }
        s.handshake.close();if(s.collision.motion()!=null)s.collision.motion().fail("session closing");s.collision.fence();
        if(s.ray!=null) { s.ray.cancelled().set(true);s.ray=null; }
        s.actor.close();
        if(p!=null)send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.STOP);b.writeLong(s.epoch);}));
    }

    private static boolean reserveSameCenter(Session s){
        return s.committedManifest!=null&&!s.handshake.pending()&&s.collision.motion().reserve(s.committedCenter,s.committedManifest.generation()+1,s.collision::certify);
    }
    @SubscribeEvent public static void motionInvariant(ServerTickEvent.Pre e){
        var s=session;if(s==null||s.stopping||s.server!=e.getServer()||s.collision.motion()==null)return;
        var p=s.server.getPlayerList().getPlayer(s.player);if(p==null||p.level().dimension()!=SceneCommands.CARRIER)return;
        if(p.isPassenger()||p.getCamera()!=p)s.collision.motion().fail("Unaudited vehicle or spectator camera");
        try{s.collision.observeMotion(p);}catch(RuntimeException invalid){fail(s,p,"Body invariant failed",invalid);}
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post e) {
        distanceProgressTick(e.getServer());
        var entry=pendingPlayable;if(entry!=null&&entry.server()==e.getServer()&&((entry.continuation()==null?System.nanoTime()-entry.deadlineNanos()>0:entry.continuation().progress.watchdog.failed())||e.getServer().getPlayerList().getPlayer(entry.player())==null)){pendingPlayable=null;var player=e.getServer().getPlayerList().getPlayer(entry.player());if(entry.continuation()!=null)distanceFailed(entry.continuation(),player,"Fresh shader/model preflight timed out");else if(player!=null)say(player,"Playable preflight timed out; no world or mode was changed");}
        pumpOpen(e.getServer());
        if(pendingDistance!=null&&pendingDistance.old.server==e.getServer()){pumpDistance(e.getServer());return;}
        if(!RollingGateFixture.enabled())return;
        pumpRecovery(e.getServer());
        var s=session;if(s==null||e.getServer()!=s.server)return;
        var p=s.server.getPlayerList().getPlayer(s.player);
        if(s.continuationProgress!=null&&!s.stopping&&s.committedManifest==null&&s.continuationProgress.watchdog.failed()){
            failDistanceProgress(s,p,s.continuationProgress.watchdog.failure());
        }
        if(p!=null&&!p.isAlive())stop(s,p,false);
        else if(p==null||!p.level().dimension().equals(SceneCommands.CARRIER)||!validMode(p,s))stop(s,p);
        else if(!s.collision.preventsStockVoidCheck(p.getUUID(),p.getBoundingBox())) {
            stop(s,p);restoreOrigin(p,s.origin);say(p,"Native diagnostic safety envelope exceeded; gate closed and safe return requested.");
        }
        try {
            if(!s.stopping) {
                var motion=s.collision.motion();
                if(motion.expired(System.nanoTime())||motion.state()==RollingMotionGuard.State.FAILED_HOLD)throw new IllegalStateException("Body motion hold failed or exceeded its absolute watchdog: "+motion.reason());
                var intent=motion.latestIntent();s.latestGoal=nativeDistanceMode(s)?motion.visualGoal():RollingCubePlan.centerFromFeet(intent.x,intent.y,intent.z);
                s.desiredFarCenter=RollingFarPublication.center(s.descriptor,s.latestGoal);
                // The actor sees only an already-reserved adjacent center, never the distant goal.
                if(motion.reservation()==null&&!s.handshake.pending()&&s.committedManifest!=null){
                    if(!s.descriptor.arenaMode()&&!s.committedManifest.pinned()
                            &&RollingCubePlan.stationary(activeFocus(s),s.committedCenter,false).currentTargets().contains(activeFocus(s))){
                        if(reserveSameCenter(s)&&s.actor.requestPinRestore()!=RollingCubeActor.Admission.QUEUED)s.collision.motion().cancelUnstartedReservation();
                    }else{
                        var next=RollingCubePlan.nextStep(s.committedCenter,s.latestGoal);
                        s.descriptor.requireAllowedTransition(s.committedCenter,next);
                        if(!next.equals(s.committedCenter)&&motion.reserve(next,s.committedManifest.generation()+1,s.collision::certify)){
                            if(s.actor.requestCenter(next)!=RollingCubeActor.Admission.QUEUED)throw new IllegalStateException("Reserved adjacent request refused");
                        }
                    }
                }
                pumpRay(p,s);
            }
            for(var event:s.actor.pollEvents(2)) {
                if(event instanceof RollingCubeActor.Failure f) { fail(s,p,f.detail(),null);continue; }
                if(s.stopping||p==null)continue;
                handleEvent(p,s,event);
            }
            if(!s.stopping&&p!=null) {
                tryGrant(p,s);tryCommitReady(p,s);
                pumpScenario(s,p);pumpArenaScenario(s,p);
                if(s.walking) {
                    var target=s.snapshots.get(activeFocus(s));
                    s.physics.record(p,s.collision.ready(),target==null?(s.committedManifest==null?-1:replacementRevision(s.committedManifest,activeFocus(s))):target.revision(),s.handshake.committedPublication());
                }
            }
        } catch(RuntimeException failed) { fail(s,p,"Rolling controller invariant failed",failed); }
        if(s.stopping&&s.actor.closed().isDone()&&s.actor.closed().join().successful()
                &&(!s.distanceCleanupBlocked||p==null||!p.level().dimension().equals(SceneCommands.CARRIER))) {
            EarthPlatformMod.LOGGER.info("ROLLING_GATE_SERVER_CLOSED epoch={} successful={}",s.epoch,s.actor.closed().join().successful());
            releaseCollision(s);s.snapshots.clear();s.pendingManifest=null;s.committedManifest=null;s.stage=null;session=null;
        }
    }

    private static void handleEvent(ServerPlayer p,Session s,RollingCubeActor.Event event) {
        if(event instanceof RollingCubeActor.StageMetadata m) {
            RollingGateWire.validateMetadata(m);
            s.collision.motion().requireMetadata(m.currentCenter(),m.candidateCenter(),m.generation());
            s.descriptor.requireAllowedTransition(m.currentCenter(),m.candidateCenter());
            if(s.handshake.pending()||s.editPublication||s.stage!=null||!s.world.equals(m.world())||s.epoch!=m.epoch()
                    ||!activeFocus(s).equals(m.currentFocus())||!(s.descriptor.twoWorkspaces()?s.descriptor.workspaces().contains(m.pinCenter()):s.descriptor.center().equals(m.pinCenter()))||m.initial()!=(s.committedManifest==null)
                    ||m.generation()!=(s.committedManifest==null?1:s.committedManifest.generation()+1)
                    ||s.committedCenter!=null&&!s.committedCenter.equals(m.currentCenter()))
                throw new IllegalStateException("Unexpected rolling metadata identity or publication overlap");
            RollingFocusTransition.requireAllowed(s.descriptor,m,s.committedManifest);
            if(m.currentLeases().size()!=s.snapshots.size())throw new IllegalStateException("Current leases differ from retained snapshots");
            for(var lease:m.currentLeases()) {
                var snapshot=s.snapshots.get(lease.position());
                if(snapshot==null||snapshot.leaseGeneration()!=lease.leaseGeneration())throw new IllegalStateException("Current lease changed before stage");
            }
            if(s.ray!=null) { s.ray.cancelled().set(true);s.ray=null;rejectIntent(p,s,"Window began staging before ray admission"); }
            s.stage=m;long publication=s.handshake.begin();if(s.continuationProgress!=null)s.continuationProgress.header(s.epoch,1,publication,"","");
            send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.METADATA);b.writeLong(s.epoch);b.writeLong(publication);CoreGatePayload.world(b,m.world());s.descriptor.write(b);RollingGateWire.writeMetadata(b,m);}));
            EarthPlatformMod.LOGGER.info("ROLLING_GATE_STAGE epoch={} publication={} actorGeneration={} current={} candidate={} added={} retired={}",s.epoch,publication,m.generation(),m.currentCenter(),m.candidateCenter(),m.newPositions().size(),m.retiredPositions().size());
        } else if(event instanceof RollingCubeActor.EarthBootstrapSnapshot v) {
            var snapshot=v.snapshot();
            if(!s.descriptor.earthResumeBootstrap()||s.stage==null||!s.stage.initial()||v.generation()!=1
                    ||!snapshot.world().equals(s.world)||snapshot.epoch()!=s.epoch)throw new IllegalStateException("Unadmitted Earth resume bootstrap");
            if(s.continuationProgress!=null)s.continuationProgress.increment(NativeDistanceProgressWatchdog.Lane.ACTOR_SNAPSHOTS);
            send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.EARTH_BOOTSTRAP_SNAPSHOT);b.writeLong(s.epoch);b.writeLong(v.generation());snapshot.write(b);}));
        } else if(event instanceof RollingCubeActor.Snapshot v) {
            var snapshot=v.snapshot();
            if(!s.world.equals(snapshot.world())||snapshot.epoch()!=s.epoch)throw new IllegalStateException("Wrong snapshot source identity");
            if(s.editPublication) {
                var previous=s.snapshots.get(snapshot.position());
                if(!s.editPending||s.committedManifest==null||v.generation()!=s.committedManifest.generation()
                        ||!snapshot.position().equals(s.editTarget)||previous==null
                        ||previous.leaseGeneration()!=snapshot.leaseGeneration()||snapshot.revision()!=previous.revision()+1)
                    throw new IllegalStateException("Unexpected durable edit replacement");
            } else {
                if(s.stage==null||v.generation()!=s.stage.generation()||!s.stage.newPositions().contains(snapshot.position())
                        ||s.snapshots.containsKey(snapshot.position()))throw new IllegalStateException("Unexpected staged snapshot");
                var lease=s.stage.candidateLeases().stream().filter(l->l.position().equals(snapshot.position())).findFirst().orElseThrow();
                if(lease.leaseGeneration()!=snapshot.leaseGeneration())throw new IllegalStateException("Staged lease mismatch");
            }
            s.snapshots.put(snapshot.position(),snapshot);
            if(s.continuationProgress!=null)s.continuationProgress.increment(NativeDistanceProgressWatchdog.Lane.ACTOR_SNAPSHOTS);
            if(s.snapshots.size()>RollingCubePlan.MAX_STAGED_OWNERS)throw new IllegalStateException("Server snapshot bound exceeded");
            send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.SNAPSHOT);b.writeLong(s.epoch);b.writeLong(v.generation());snapshot.write(b);}));
        } else if(event instanceof RollingCubeActor.StageReady ready) {
            if(s.stage==null||s.editPublication||ready.generation()!=s.stage.generation()
                    ||!ready.candidateCenter().equals(s.stage.candidateCenter()))throw new IllegalStateException("Unexpected stage readiness");
            publishReady(p,s,ready);
        } else if(event instanceof RollingCubeActor.Committed committed) {
            if(s.editPublication||s.pendingManifest==null||s.handshake.state()!=RollingPublicationHandshake.State.PAIR_READY
                    ||!s.world.equals(committed.world())||committed.epoch()!=s.epoch
                    ||committed.generation()!=s.pendingManifest.generation()||!committed.center().equals(s.pendingManifest.candidateCenter()))
                throw new IllegalStateException("Unexpected actor commit");
            if(!s.motionCommitQueued||s.motionActorCommitted)throw new IllegalStateException("Unrequested or duplicate actor commit");
            s.motionActorCommitted=true;tryCommitReady(p,s);
        } else if(event instanceof RollingCubeActor.EditAck ack) {
            if(!s.editPublication||!s.editPending||s.committedManifest==null)throw new IllegalStateException("Unexpected edit acknowledgement");
            send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.EDIT_ACK);b.writeLong(s.epoch);b.writeLong(s.handshake.publication());b.writeUtf(ack.status().name(),64);b.writeLong(ack.revision());b.writeUtf(bounded(ack.detail()),512);}));
            EarthPlatformMod.LOGGER.info("ROLLING_GATE_EDIT_ACK epoch={} publication={} status={} revision={}",s.epoch,s.handshake.publication(),ack.status(),ack.revision());
            if(ack.status()==RollingCubeActor.EditStatus.SAVE_FAILED||ack.status()==RollingCubeActor.EditStatus.CLOSING) {
                fail(s,p,"Durable edit failed; session fenced",null);return;
            }
            if(s.scenarioAction!=null&&s.scenarioActionStarted&&s.scenarioCompletedAction<s.scenarioAction.id())s.scenarioOutcome=ack.applied()?RollingIndexedScenario.Outcome.APPLIED:RollingIndexedScenario.Outcome.REFUSED;
            if(s.arenaAction!=null&&s.scenarioActionStarted&&s.scenarioCompletedAction<s.arenaAction.id())s.arenaOutcome=ack.applied()?RollingArenaScenario.Outcome.APPLIED:RollingArenaScenario.Outcome.REFUSED;
            if(ack.applied()) {
                var replacement=s.snapshots.get(ack.request().position());
                if(replacement==null||replacement.revision()!=ack.revision()||replacement.leaseGeneration()!=ack.request().leaseGeneration())
                    throw new IllegalStateException("Durable ACK lacks exact replacement");
            }
            var versions=new ArrayList<RollingCubeActor.CubeVersion>(s.committedManifest.versions().size());
            for(var old:s.committedManifest.versions()) {
                var snapshot=s.snapshots.get(old.position());
                if(snapshot==null||snapshot.leaseGeneration()!=old.leaseGeneration())throw new IllegalStateException("Edit lost a committed lease");
                versions.add(new RollingCubeActor.CubeVersion(old.position(),snapshot.leaseGeneration(),snapshot.revision()));
            }
            var ready=RollingEditAcknowledgement.ready(s.committedManifest,s.editTarget,versions,ack);
            publishReady(p,s,ready);
            say(p,"Rolling edit "+ack.status()+"; durable revision "+ack.revision()+". Waiting for the paired native/DH revision.");
        }
    }

    private static void publishReady(ServerPlayer p,Session s,RollingCubeActor.StageReady ready) {
        if(!manifestMatches(s,ready))throw new IllegalStateException("Exact ready manifest does not match authoritative snapshots");
        var center=RollingFarPublication.center(s.descriptor,ready.candidateCenter());
        String digest=RollingFarPublication.digest(s.descriptor,ready);
        s.pendingManifest=ready;s.pendingFarCenter=center;s.handshake.ready(digest);
        send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.READY);b.writeLong(s.epoch);b.writeLong(s.handshake.publication());RollingGateWire.writeReady(b,ready);}));
    }
    private static String bounded(String detail) { return detail.length()>512?detail.substring(0,512):detail; }
    private static void fail(Session s,ServerPlayer p,String detail,Throwable failure) {
        EarthPlatformMod.LOGGER.error("ROLLING_GATE_FAILURE epoch={} detail={}",s.epoch,detail,failure);
        stop(s,p);
        if(p!=null&&p.level().dimension().equals(SceneCommands.CARRIER)){
            boolean returned=restoreOrigin(p,s.origin);
            say(p,returned?"Rolling host stopped safely: "+bounded(detail):"Rolling host stopped; safe return was cancelled. Movement remains held and your return record is retained.");
        }
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent e) {
        if(pendingDistance!=null&&pendingDistance.old.server==e.getServer())pendingDistance=null;
        if(pendingPlayable!=null&&pendingPlayable.server()==e.getServer())pendingPlayable=null;
        var pending=pendingOpen;if(pending!=null&&pending.server()==e.getServer())cancelPending(pending,"server stopped");
        var recovery=RECOVERY.current();if(recovery!=null&&recovery.player().server==e.getServer())RECOVERY.clear(recovery);
        var s=session;
        if(s!=null&&s.server==e.getServer()) {
            if(s.ray!=null) { s.ray.cancelled().set(true);s.ray=null; }
            s.handshake.close();s.actor.close();releaseCollision(s);s.snapshots.clear();
            s.pendingManifest=null;s.committedManifest=null;s.stage=null;session=null;
            // The actor retains itself until every physical provider/save owner drains.
        }
    }
    private static void releaseCollision(Session s) {
        var p=s.server.getPlayerList().getPlayer(s.player);
        boolean retainBoundary=p!=null&&!p.isRemoved()&&p.level().dimension().equals(SceneCommands.CARRIER)
            &&(!p.isSpectator()||s.collision.safetyOwnedBy(p.getUUID()));
        if(retainBoundary)s.collision.fence();else s.collision.detach();
        EarthPlatformMod.LOGGER.info("ROLLING_GATE_COLLISION_CLOSED epoch={} residentOwners={} retainedZeroOwnerSafetyBoundary={}",s.epoch,s.collision.residentOwners(),retainBoundary);
    }
    private static void reportPhysics(Session s,ServerPlayer p,String stage) {
        if(!stage.equals("PRE_CLOSE")&&s.physicsReports>=8)return;
        var target=s.snapshots.get(activeFocus(s));long revision=target==null?(s.committedManifest==null?-1:replacementRevision(s.committedManifest,activeFocus(s))):target.revision();
        s.physics.record(p,s.collision.ready(),revision,s.handshake.committedPublication());
        var j=new com.google.gson.JsonObject();
        j.addProperty("scope","Bounded rolling diagnostic collision only; no survival, fluids or general Level compatibility");
        j.addProperty("epoch",s.epoch);j.addProperty("profile",s.descriptor.id());j.addProperty("stage",stage);
        j.addProperty("descriptorFingerprint",s.descriptor.fingerprint());j.addProperty("sourceFingerprint",s.descriptor.sourceFingerprint());
        j.add("descriptor",new com.google.gson.Gson().toJsonTree(s.descriptor));
        j.addProperty("sourcePreparationThread",s.preparation.workerThread());j.addProperty("sourcePreparationNanos",s.preparation.elapsedNanos());
        j.add("sourcePreparationCounters",new com.google.gson.Gson().toJsonTree(BoundedProviderPreparation.processDiagnostics()));
        j.addProperty("publication",s.handshake.publication());j.addProperty("handshake",s.handshake.state().name());
        j.addProperty("committedCenter",String.valueOf(s.committedCenter));j.addProperty("latestGoal",String.valueOf(s.latestGoal));
        j.addProperty("farPolicyVersion",RollingFarPublication.POLICY_VERSION);
        j.addProperty("farRadius",s.descriptor.lodLayout().radiusBlocks());j.addProperty("farSnap",s.descriptor.lodLayout().snapBlocks());
        j.addProperty("farLayout",s.descriptor.lodLayout().id());j.addProperty("farLayoutIdentity",s.descriptor.lodLayout().identity());
        j.addProperty("farCenterAlignment",s.descriptor.lodLayout().centerAlignmentBlocks());
        j.add("desiredFarCenter",new com.google.gson.Gson().toJsonTree(s.desiredFarCenter));
        j.add("pendingFarCenter",new com.google.gson.Gson().toJsonTree(s.pendingFarCenter));
        j.add("publishedFarCenter",new com.google.gson.Gson().toJsonTree(s.publishedFarCenter));
        j.addProperty("pairDigest",s.handshake.digest());j.addProperty("publishedPairDigest",s.handshake.committedDigest());
        j.addProperty("waitingForBody",s.waitingForBody);j.addProperty("revision",revision);j.addProperty("walking",s.walking);
        j.addProperty("retainedSnapshotCoordinates",s.snapshots.size());j.addProperty("collisionOwners",s.collision.residentOwners());
        j.addProperty("collisionReady",s.collision.ready());j.add("actorDiagnostics",new com.google.gson.Gson().toJsonTree(s.actor.diagnostics()));j.add("last512PhysicsTicks",s.physics.snapshot());
        try {
            var dir=java.nio.file.Path.of("earthplatform-evidence","rolling-core-gate");java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("server-epoch-"+s.epoch+"-physics-"+(s.physicsReports++)+"-"+stage.toLowerCase(Locale.ROOT)+".json"),j+"\n");
        } catch(java.io.IOException failed) { EarthPlatformMod.LOGGER.warn("Rolling physics diagnostic write failed",failed); }
    }
    private static boolean owns(ServerPlayer p,Session s) { return allowed(p)&&s!=null&&s.player.equals(p.getUUID())&&s.server==p.server; }
    private static void send(ServerPlayer p,RollingGatePayload payload) { PacketDistributor.sendToPlayer(p,payload); }
    private static void say(ServerPlayer p,String message) { p.sendSystemMessage(Component.literal(message)); }
    private static void reset(ServerPlayer p) { p.setOnGround(false);p.resetFallDistance();p.setDeltaMovement(Vec3.ZERO); }

    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
    public static final class Payloads {
        @SubscribeEvent public static void register(RegisterPayloadHandlersEvent e) {
            var registrar=e.registrar("rolling-player-gate-1");
            registrar.playBidirectional(NativeDistanceProgressPayload.TYPE,NativeDistanceProgressPayload.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)nativeDistanceProgress(player,p);else RollingGateClient.nativeDistanceProgress(p);});
            registrar.playBidirectional(NativeDistanceChangePayload.TYPE,NativeDistanceChangePayload.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)nativeDistanceChange(player,p);else RollingGateClient.nativeDistanceChange(p);});
            registrar.playBidirectional(PlayableEntryPayload.TYPE,PlayableEntryPayload.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)playableEntryReply(player,p);else RollingGateClient.playableEntry(p);});
            registrar.playToClient(RollingGatePayload.TYPE,RollingGatePayload.CODEC,(p,c)->RollingGateClient.handle(p));
            registrar.playToServer(RollingScenarioReceiptPayload.TYPE,RollingScenarioReceiptPayload.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)scenarioReceipt(player,p);});
            registrar.playToServer(RollingVisualFrontierIntentPayload.TYPE,RollingVisualFrontierIntentPayload.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)visualFrontierIntent(player,p);});
            registrar.playToServer(RollingGateControlPayload.TYPE,RollingGateControlPayload.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)control(player,p);});
        }
    }
}
