package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.enginebridge.AnalyticNativeCubeProvider;
import io.earthplatform.minecraft.enginebridge.NativeCubeActor;
import io.earthplatform.minecraft.enginebridge.NativeRayEditPlanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneCommands;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Integrated operator gate: server-owned generation/store and actual bounded network cube frames. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID)
public final class CoreGateCommands {
    public static final String OPT_IN="earthplatform.experimental.authoritativeCoreGate";
    public static final CubePos CENTER=new CubePos(0,8,0),TARGET=new CubePos(0,8,0);
    public static final long EDIT_X=4,EDIT_Y=129,EDIT_Z=4;
    private static final AtomicLong EPOCH=new AtomicLong();
    private static Session session;
    private static final CoreRecoverySlot<ServerPlayer> RECOVERY=new CoreRecoverySlot<>();
    // Process-wide CPU-only pool: old-world jobs retain their physical slot until they settle.
    // Idle workers time out; one running and one queued job bound copied immutable snapshot inputs.
    private static final CoreRayWorkQueue RAY_WORKER=new CoreRayWorkQueue();
    private record PendingRay(CompletableFuture<NativeRayEditPlanner.Result> result,AtomicBoolean cancelled,Vec3 eye,Vec3 look){}

    private static final class Session {
        final CoreGateProfile profile;final MinecraftServer server;final UUID player;final NativeCubeActor actor;final CoreReturnPoint origin;final long epoch;final EnginePaletteContext palette;
        final CoreCollisionHandshake collisionHandshake;final CorePhysicsTrace physics=new CorePhysicsTrace();int physicsReports;CoreCollisionBinding collision;boolean walking;
        final Map<CubePos,EngineCubeSnapshot> snapshots=new HashMap<>();boolean stopping,editPending,intentReceived,feedbackSent;PendingRay ray;long lastIntentNanos,lastFeedbackNanos;
        Session(ServerPlayer p,NativeCubeActor a,CoreReturnPoint o,long e,EnginePaletteContext palette,CoreGateProfile profile){this.profile=profile;server=p.server;player=p.getUUID();actor=a;origin=o;epoch=e;this.palette=palette;collisionHandshake=new CoreCollisionHandshake(e);}
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent e){e.getDispatcher().register(Commands.literal("earth_core").requires(s->s.hasPermission(2))
        .then(Commands.literal("enter").executes(c->enter(c.getSource().getPlayerOrException(),CoreGateProfile.BASE))
            .then(Commands.literal("abyss").executes(c->enter(c.getSource().getPlayerOrException(),CoreGateProfile.ABYSS)))
            .then(Commands.literal("summit").executes(c->enter(c.getSource().getPlayerOrException(),CoreGateProfile.SUMMIT))))
        .then(Commands.literal("walk").executes(c->walk(c.getSource().getPlayerOrException(),true)))
        .then(Commands.literal("fly").executes(c->walk(c.getSource().getPlayerOrException(),false)))
        .then(Commands.literal("leave").executes(c->leave(c.getSource().getPlayerOrException())))
        .then(Commands.literal("edit").executes(c->edit(c.getSource().getPlayerOrException(),false)))
        .then(Commands.literal("erase").executes(c->edit(c.getSource().getPlayerOrException(),true)))
        .then(Commands.literal("status").executes(c->status(c.getSource().getPlayerOrException()))));}
    private static boolean allowed(ServerPlayer p){return !RollingGateFixture.enabled()&&Boolean.getBoolean(OPT_IN)&&p.server.isSingleplayer()&&!p.server.isDedicatedServer();}
    private static int enter(ServerPlayer p,CoreGateProfile profile){
        if(!allowed(p)){say(p,"Explicit integrated core-gate opt-in required.");return 0;}
        if(profile!=CoreGateProfile.BASE&&!CoreGateFixture.fullHeight()){say(p,"Endpoint profiles require the full-height player opt-in.");return 0;}
        if(CoreGateFixture.fullHeight()&&!CoreGateFixture.collision()){say(p,"Full-height gate requires the collision opt-in.");return 0;}
        if(p.server.isHardcore()){say(p,"The diagnostic player gate does not support hardcore worlds.");return 0;}
        if(!p.isAlive()){say(p,"Respawn before entering the diagnostic player gate.");return 0;}
        if(Boolean.getBoolean(SceneCommands.OPT_IN)){say(p,"Core gate and Earth visual scene opt-ins are mutually exclusive; launch the isolated core profile.");return 0;}
        if(p.level().dimension().equals(SceneCommands.CARRIER)){say(p,"Return to a normal dimension before opening a new core host.");return 0;}
        if(CoreReturnPoint.remembered(p).isPresent()){say(p,"Previous carrier recovery is unresolved; use /earth_core leave before entering again.");return 0;}
        if(session!=null){say(p,"Previous core host must finish draining; use /earth_core leave, then enter.");return 0;}
        var carrier=p.server.getLevel(SceneCommands.CARRIER);if(carrier==null){say(p,"Fresh disposable world with the empty carrier is required.");return 0;}
        var palette=EnginePaletteContext.create(p.registryAccess().registryOrThrow(Registries.BIOME));
        var provider=CoreGateFixture.provider(palette,profile);
        long epoch=EPOCH.incrementAndGet();
        var origin=new CoreReturnPoint(p.level().dimension(),p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot(),p.gameMode.getGameModeForPlayer());
        var actor=NativeCubeActor.open(p.server.getWorldPath(LevelResource.ROOT).resolve(CoreGateFixture.directory(profile)),provider.identity(),provider,palette,epoch,profile.center());
        session=new Session(p,actor,origin,epoch,palette,profile);origin.remember(p);
        if(CoreGateFixture.collision())session.collision=CoreGateFixture.fullHeight()?new CoreCollisionBinding(carrier,p.getUUID(),epoch):new CoreCollisionBinding(carrier);
        actor.closed().thenAccept(result->EarthPlatformMod.LOGGER.info("CORE_GATE_ACTOR_DRAIN epoch={} successful={} detail={}",epoch,result.successful(),result.detail()));
        p.setGameMode(GameType.SPECTATOR);reset(p);p.teleportTo(carrier,profile.entry().x,profile.entry().y,profile.entry().z,Set.of(),0,31);
        if(p.serverLevel()!=carrier){actor.close();session.stopping=true;boolean restored=restoreOrigin(p,origin);say(p,restored?"Another mod cancelled carrier travel; original state restored.":"Carrier travel and safe return were cancelled; recovery record retained.");return 0;}
        say(p,"Core engine: server generation + durable store + streamed cubes. Fixed 125-cube box, 27 native meshes, 512m DH. "+(CoreGateFixture.collision()?"Wait for READY, then /earth_core walk for the bounded native collision gate.":"Spectator carrier; no player collision yet."));return 1;
    }
    private static int edit(ServerPlayer p,boolean air){
        var s=session;if(!owns(p,s)||s.stopping||s.editPending||s.ray!=null){say(p,"Core host unavailable or one edit is already pending.");return 0;}
        if(!p.level().dimension().equals(SceneCommands.CARRIER)){say(p,"Core edits require its active carrier.");return 0;}
        var snap=s.snapshots.get(s.profile.target());if(snap==null||s.snapshots.size()!=125){say(p,"Target is unknown; edit rejected.");return 0;}
        int state=Block.BLOCK_STATE_REGISTRY.getId((air?Blocks.AIR:Blocks.DIORITE).defaultBlockState());
        var request=new NativeCubeActor.EditRequest(snap.world(),s.epoch,s.profile.target(),snap.leaseGeneration(),snap.revision(),s.profile.commandEdit().getX(),s.profile.commandEdit().getY(),s.profile.commandEdit().getZ(),state);
        return submitEdit(p,s,request);
    }
    private static int submitEdit(ServerPlayer p,Session s,NativeCubeActor.EditRequest request){
        var result=s.actor.tryEdit(request);
        if(result!=NativeCubeActor.Admission.QUEUED){rejectIntent(p,s,"Edit not admitted: "+result);return 0;}
        s.editPending=true;s.collisionHandshake.invalidate();if(s.collision!=null){s.collision.fence();reset(p);}send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.EDIT_BEGIN);b.writeLong(s.epoch);b.writeLong(request.x());b.writeLong(request.y());b.writeLong(request.z());}));
        say(p,"Edit admitted; waiting for durable save before acknowledging or drawing the new revision.");return 1;
    }
    public static void mouseIntent(ServerPlayer p,CoreGateIntentPayload intent){
        var s=session;if(!owns(p,s)||intent.epoch()!=s.epoch||s.stopping)return;
        if(!p.createCommandSourceStack().hasPermission(2)||!validMode(p,s)||!p.level().dimension().equals(SceneCommands.CARRIER)){rejectIntent(p,s,"Active operator core carrier required");return;}
        if(s.editPending||s.ray!=null||s.snapshots.size()!=125){rejectIntent(p,s,"Known cube box or previous edit is not ready");return;}
        long now=System.nanoTime();if(s.intentReceived&&now-s.lastIntentNanos<250_000_000L){rejectIntent(p,s,"Interaction rate limit");return;}s.intentReceived=true;s.lastIntentNanos=now;
        var snapshots=Map.copyOf(s.snapshots);var target=snapshots.get(s.profile.target());var eye=p.getEyePosition();var look=p.getViewVector(1);var body=p.getBoundingBox();
        var palette=s.palette;long queryEpoch=s.epoch;var cancelled=new AtomicBoolean();
        try{
            var future=RAY_WORKER.submit(cancelled,()->NativeRayEditPlanner.plan(target.world(),queryEpoch,palette,snapshots,eye,look,8,intent.place()?NativeRayEditPlanner.Action.PLACE_DIORITE:NativeRayEditPlanner.Action.REMOVE,s.profile.target(),body));
            s.ray=new PendingRay(future,cancelled,eye,look);
        }catch(RejectedExecutionException busy){rejectIntent(p,s,"Bounded ray worker is busy");}
    }
    private static void pumpRay(ServerPlayer p,Session s){
        var pending=s.ray;if(pending==null||!pending.result().isDone())return;s.ray=null;
        if(s.stopping||p==null)return;
        try{
            var result=pending.result().join();
            if(result instanceof NativeRayEditPlanner.Rejected rejected){rejectIntent(p,s,rejected.detail());return;}
            var plan=(NativeRayEditPlanner.Plan)result;
            boolean active=owns(p,s)&&!s.stopping&&p.createCommandSourceStack().hasPermission(2)&&validMode(p,s)&&p.level().dimension().equals(SceneCommands.CARRIER);
            String refusal=CoreIntentCommitGate.evaluate(active,pending.eye(),pending.look(),p.getEyePosition(),p.getViewVector(1),p.getBoundingBox(),plan,s.snapshots);
            if(refusal!=null){rejectIntent(p,s,refusal);return;}
            var request=plan.request();
            EarthPlatformMod.LOGGER.info("CORE_GATE_SERVER_RAY epoch={} target={},{},{} expectedRevision={} dependencies={}",s.epoch,request.x(),request.y(),request.z(),request.expectedRevision(),plan.dependencies().stamps().size());
            submitEdit(p,s,request);
        }catch(CompletionException|CancellationException failed){rejectIntent(p,s,"Ray preparation failed or was cancelled");EarthPlatformMod.LOGGER.warn("Core ray preparation failed",failed);}
    }
    private static void rejectIntent(ServerPlayer p,Session s,String detail){
        String bounded=detail.length()>512?detail.substring(0,512):detail;
        // Always resolve the local client's one pending intention. Only chat is throttled;
        // public-server ingress/packet flood protection is outside this integrated gate.
        send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.INTENT_REJECTED);b.writeLong(s.epoch);b.writeUtf(bounded,512);}));
        long now=System.nanoTime();if(s.feedbackSent&&now-s.lastFeedbackNanos<250_000_000L)return;
        s.feedbackSent=true;s.lastFeedbackNanos=now;say(p,"Core interaction rejected: "+bounded);
    }
    private static boolean validMode(ServerPlayer p,Session s){return p.isSpectator()||(s.walking&&CoreGateFixture.collision()&&p.gameMode.getGameModeForPlayer()==GameType.ADVENTURE);}
    private static int walk(ServerPlayer p,boolean enable){
        var s=session;if(!owns(p,s)||s.stopping||s.collision==null||!p.level().dimension().equals(SceneCommands.CARRIER)){say(p,"Explicit collision profile and active core session required");return 0;}
        if(enable){
            if(s.editPending||!s.collisionHandshake.ready()||!s.collision.ready()){say(p,"Walking waits for matched authoritative collision and client mesh readiness");return 0;}
            // Reset to a known clear landing corridor; no body is inserted into edited terrain.
            var query=new io.earthplatform.minecraft.enginebridge.NativeKnownCubeQueries(s.snapshots.get(s.profile.target()).world(),s.epoch,s.palette,Map.copyOf(s.snapshots));
            var box=s.profile.walkEntryBody();
            var result=query.overlaps(box,stamp->{var v=s.snapshots.get(stamp.position());return v!=null&&stamp.equals(io.earthplatform.minecraft.enginebridge.NativeKnownCubeQueries.CubeStamp.of(v));});
            if(result.status()!=io.earthplatform.minecraft.enginebridge.NativeKnownCubeQueries.Status.CLEAR){say(p,"Known walk entry body is occupied or unavailable");return 0;}
            p.setGameMode(GameType.ADVENTURE);if(p.gameMode.getGameModeForPlayer()!=GameType.ADVENTURE){say(p,"Another mod cancelled Adventure mode; walk gate was not activated");return 0;}s.walking=true;p.noPhysics=false;p.getAbilities().flying=false;p.onUpdateAbilities();reset(p);p.teleportTo(p.serverLevel(),s.profile.entry().x,s.profile.entry().y,s.profile.entry().z,Set.of(),0,15);
            say(p,"Native walk gate: vanilla collision/support solver, known cubes only. Unknown/reload/edit states fence motion. No survival/material/tick compatibility claim.");
        }else{p.setGameMode(GameType.SPECTATOR);if(!p.isSpectator()){say(p,"Another mod cancelled spectator mode; walk state retained");return 0;}s.walking=false;p.noPhysics=true;reset(p);say(p,"Spectator flight restored; native collision data remains admitted");}
        return 1;
    }
    public static void collisionFrame(ServerPlayer p,CoreGateCollisionPayload frame){
        var s=session;if(!owns(p,s)||s.stopping||s.collision==null||!p.level().dimension().equals(SceneCommands.CARRIER))return;
        var target=s.snapshots.get(s.profile.target());long revision=target==null?-1:target.revision();
        if(!s.collisionHandshake.accept(frame,revision,s.editPending))return;
        if(!frame.ready()){s.collision.fence();reset(p);return;}
        if(s.snapshots.size()!=125){s.collisionHandshake.invalidate();s.collision.fence();return;}
        s.collision.publish(target.world(),s.epoch,s.palette,Map.copyOf(s.snapshots));
        EarthPlatformMod.LOGGER.info("CORE_GATE_COLLISION_READY epoch={} revision={} generation={} owners={}",s.epoch,revision,frame.generation(),s.collision.residentOwners());
    }
    private static int status(ServerPlayer p){var s=session;if(!owns(p,s)){say(p,"No core session.");return 0;}var snap=s.snapshots.get(s.profile.target());if(s.collision!=null)reportPhysics(s,p,"STATUS");say(p,"Server epoch="+s.epoch+" received="+s.snapshots.size()+"/125 target durable revision="+(snap==null?"unknown":snap.revision())+" editPending="+s.editPending+" draining="+s.stopping);return 1;}
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
    private static int leave(ServerPlayer p){var s=session;if(!owns(p,s))return recoverOrphan(p);stop(s,p);return restoreOrigin(p,s.origin)?1:0;}
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){
        if(RollingGateFixture.enabled())return;
        if(e.getEntity() instanceof ServerPlayer p){var s=session;if(owns(p,s)){stop(s,p);if(p.level().dimension().equals(SceneCommands.CARRIER))restoreOrigin(p,s.origin);}releaseDisconnectedSafety(p);}
    }
    @SubscribeEvent public static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e){
        if(e.getEntity() instanceof ServerPlayer p&&session==null)recoverOrphan(p);
    }
    @SubscribeEvent public static void clonePlayer(net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone e){
        if(!(e.getEntity() instanceof ServerPlayer p)||!allowed(p))return;
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
        pumpRecovery(e.getServer());
        var s=session;if(s!=null&&s.server==e.getServer()){var p=s.server.getPlayerList().getPlayer(s.player);stop(s,p);if(p!=null&&p.level().dimension().equals(SceneCommands.CARRIER))restoreOrigin(p,s.origin);}
    }
    private static void stop(Session s,ServerPlayer p){stop(s,p,p!=null&&p.isAlive());}
    private static void stop(Session s,ServerPlayer p,boolean restoreSafeMode){if(s.stopping)return;if(s.collision!=null&&p!=null)reportPhysics(s,p,"PRE_CLOSE");s.stopping=true;if(restoreSafeMode&&p!=null&&s.walking&&p.gameMode.getGameModeForPlayer()==GameType.ADVENTURE){s.walking=false;p.setGameMode(GameType.SPECTATOR);reset(p);}s.collisionHandshake.invalidate();if(s.collision!=null)s.collision.fence();if(s.ray!=null){s.ray.cancelled().set(true);s.ray=null;}s.actor.close();if(p!=null)send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.STOP);b.writeLong(s.epoch);}));}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        pumpRecovery(e.getServer());
        var s=session;if(s==null||e.getServer()!=s.server)return;var p=s.server.getPlayerList().getPlayer(s.player);
        if(p!=null&&!p.isAlive())stop(s,p,false);
        else if(p==null||!p.level().dimension().equals(SceneCommands.CARRIER)||!validMode(p,s))stop(s,p);
        else if(CoreGateFixture.fullHeight()&&s.collision!=null&&!s.collision.preventsStockVoidCheck(p.getUUID(),p.getBoundingBox())){stop(s,p);restoreOrigin(p,s.origin);say(p,"Native diagnostic safety envelope exceeded; gate closed and safe return requested.");}
        if(!s.stopping)pumpRay(p,s);
        for(var event:s.actor.pollEvents(2)){
            if(event instanceof NativeCubeActor.ReadyMetadata m){if(s.stopping||p==null)continue;send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.START);CoreGatePayload.world(b,m.world());b.writeLong(m.epoch());b.writeBoolean(CoreGateFixture.fullHeight());if(CoreGateFixture.fullHeight())s.profile.write(b,s.palette);CoreGatePayload.position(b,m.center());b.writeVarInt(m.leases().size());for(var l:m.leases()){CoreGatePayload.position(b,l.position());b.writeLong(l.generation());}b.writeVarInt(m.renderTargets().size());for(var target:m.renderTargets())CoreGatePayload.position(b,target);}));}
            else if(event instanceof NativeCubeActor.Snapshot v){var snapshot=v.snapshot();s.snapshots.put(snapshot.position(),snapshot);if(!s.stopping&&p!=null)send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.SNAPSHOT);b.writeLong(s.epoch);snapshot.write(b);}));}
            else if(event instanceof NativeCubeActor.EditAck ack){s.editPending=false;EarthPlatformMod.LOGGER.info("CORE_GATE_EDIT_ACK epoch={} status={} revision={} target={},{},{}",s.epoch,ack.status(),ack.revision(),ack.request().x(),ack.request().y(),ack.request().z());if(!s.stopping&&p!=null){send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.EDIT_ACK);b.writeLong(s.epoch);b.writeUtf(ack.status().name(),64);b.writeLong(ack.revision());b.writeUtf(ack.detail(),512);}));say(p,"Core edit "+ack.status()+"; durable revision "+ack.revision());}}
            else if(event instanceof NativeCubeActor.Failure f){EarthPlatformMod.LOGGER.error("CORE_GATE_FAILURE {}",f.detail());if(p!=null)say(p,"Core host failed: "+f.detail());stop(s,p);}
        }
        if(!s.stopping&&s.collision!=null&&s.walking&&p!=null){var v=s.snapshots.get(s.profile.target());s.physics.record(p,s.collision.ready(),v==null?-1:v.revision(),s.collisionHandshake.generation());}
        if(s.stopping&&s.actor.closed().isDone()){EarthPlatformMod.LOGGER.info("CORE_GATE_SERVER_CLOSED epoch={} successful={}",s.epoch,s.actor.closed().join().successful());releaseCollision(s);s.snapshots.clear();session=null;}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){var recovery=RECOVERY.current();if(recovery!=null&&recovery.player().server==e.getServer())RECOVERY.clear(recovery);var s=session;if(s!=null&&s.server==e.getServer()){if(s.ray!=null){s.ray.cancelled().set(true);s.ray=null;}s.actor.close();releaseCollision(s);s.snapshots.clear();session=null;/* actor retains itself through physical drain */}}
    private static void releaseCollision(Session s){
        if(s.collision==null)return;var p=s.server.getPlayerList().getPlayer(s.player);
        boolean retainBoundary=p!=null&&!p.isRemoved()&&p.level().dimension().equals(SceneCommands.CARRIER)&&(!p.isSpectator()||s.collision.safetyOwnedBy(p.getUUID()));
        if(retainBoundary)s.collision.fence();else s.collision.detach();
        EarthPlatformMod.LOGGER.info("CORE_GATE_COLLISION_CLOSED epoch={} residentOwners={} retainedZeroOwnerSafetyBoundary={}",s.epoch,s.collision.residentOwners(),retainBoundary);
    }
    private static void reportPhysics(Session s,ServerPlayer p,String stage){
        if(!stage.equals("PRE_CLOSE")&&s.physicsReports>=8)return;
        var target=s.snapshots.get(s.profile.target());long revision=target==null?-1:target.revision();
        s.physics.record(p,s.collision.ready(),revision,s.collisionHandshake.generation());
        var j=new com.google.gson.JsonObject();j.addProperty("scope","Bounded native collision-only movement; vanilla material, fluids, survival and full-height player hooks incomplete");j.addProperty("epoch",s.epoch);j.addProperty("profile",s.profile.id());j.addProperty("groundAnchorY",s.profile.anchorY());j.addProperty("carrierHeight",p.level().getHeight());j.addProperty("carrierSectionCount",p.level().getSectionsCount());j.addProperty("stage",stage);j.addProperty("revision",revision);j.addProperty("walking",s.walking);j.addProperty("collisionOwners",s.collision.residentOwners());j.addProperty("collisionReady",s.collision.ready());j.add("last512PhysicsTicks",s.physics.snapshot());
        try{var dir=java.nio.file.Path.of("earthplatform-evidence","authoritative-core-gate");java.nio.file.Files.createDirectories(dir);java.nio.file.Files.writeString(dir.resolve("server-epoch-"+s.epoch+"-physics-"+(s.physicsReports++)+"-"+stage.toLowerCase(java.util.Locale.ROOT)+".json"),j+"\n");}catch(java.io.IOException failure){EarthPlatformMod.LOGGER.warn("Collision diagnostic write failed",failure);}
        EarthPlatformMod.LOGGER.info("CORE_GATE_PHYSICS epoch={} stage={} revision={} feetY={} onGround={} collisionReady={} owners={}",s.epoch,stage,revision,p.getY(),p.onGround(),s.collision.ready(),s.collision.residentOwners());
    }
    private static boolean owns(ServerPlayer p,Session s){return allowed(p)&&s!=null&&s.player.equals(p.getUUID())&&s.server==p.server;}
    private static void send(ServerPlayer p,CoreGatePayload payload){PacketDistributor.sendToPlayer(p,payload);}
    private static void say(ServerPlayer p,String message){p.sendSystemMessage(Component.literal(message));}
    private static void reset(ServerPlayer p){p.setOnGround(false);p.resetFallDistance();p.setDeltaMovement(Vec3.ZERO);}
    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
    public static final class Payloads {@SubscribeEvent public static void register(RegisterPayloadHandlersEvent e){var r=e.registrar("authoritative-core-gate-4");r.playToClient(CoreGatePayload.TYPE,CoreGatePayload.CODEC,(p,c)->CoreGateClient.handle(p));r.playToServer(CoreGateIntentPayload.TYPE,CoreGateIntentPayload.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)mouseIntent(player,p);});r.playToServer(CoreGateCollisionPayload.TYPE,CoreGateCollisionPayload.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)collisionFrame(player,p);});}}
}
