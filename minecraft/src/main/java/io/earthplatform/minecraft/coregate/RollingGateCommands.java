package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.EarthPlatformMod;
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
    private record PendingRay(CompletableFuture<NativeRayEditPlanner.Result> result,
                              AtomicBoolean cancelled, Vec3 eye, Vec3 look) {}

    private static final class Session {
        final CoreGateProfile profile;
        final MinecraftServer server;
        final UUID player;
        final RollingCubeActor actor;
        final CoreReturnPoint origin;
        final long epoch;
        final EnginePaletteContext palette;
        final WorldIdentity world;
        final RollingPublicationHandshake handshake;
        final CorePhysicsTrace physics = new CorePhysicsTrace();
        final Map<CubePos, EngineCubeSnapshot> snapshots = new HashMap<>();
        CoreCollisionBinding collision;
        RollingCubeActor.StageMetadata stage;
        RollingCubeActor.StageReady pendingManifest, committedManifest;
        CubePos committedCenter, latestGoal;
        PendingRay ray;
        boolean stopping, walking, editPending, editPublication, intentReceived, feedbackSent, waitingForBody;
        long lastIntentNanos, lastFeedbackNanos;
        int physicsReports;
        Session(ServerPlayer p, RollingCubeActor actor, CoreReturnPoint origin, long epoch,
                EnginePaletteContext palette, CoreGateProfile profile, WorldIdentity world) {
            this.profile=profile; this.server=p.server; this.player=p.getUUID(); this.actor=actor;
            this.origin=origin; this.epoch=epoch; this.palette=palette; this.world=world;
            this.handshake=new RollingPublicationHandshake(epoch);
        }
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("earth_roam").requires(s -> s.hasPermission(2))
            .then(Commands.literal("enter").executes(c -> enter(c.getSource().getPlayerOrException(), CoreGateProfile.BASE))
                .then(Commands.literal("abyss").executes(c -> enter(c.getSource().getPlayerOrException(), CoreGateProfile.ABYSS)))
                .then(Commands.literal("summit").executes(c -> enter(c.getSource().getPlayerOrException(), CoreGateProfile.SUMMIT))))
            .then(Commands.literal("walk").executes(c -> walk(c.getSource().getPlayerOrException(), true)))
            .then(Commands.literal("fly").executes(c -> walk(c.getSource().getPlayerOrException(), false)))
            .then(Commands.literal("leave").executes(c -> leave(c.getSource().getPlayerOrException())))
            .then(Commands.literal("status").executes(c -> status(c.getSource().getPlayerOrException()))));
    }

    private static boolean allowed(ServerPlayer p) {
        return RollingGateFixture.enabled() && Boolean.getBoolean(CoreGateCommands.OPT_IN)
            && CoreGateFixture.collision() && CoreGateFixture.fullHeight()
            && p.server.isSingleplayer() && !p.server.isDedicatedServer();
    }

    private static int enter(ServerPlayer p, CoreGateProfile profile) {
        if (!allowed(p)) { say(p, "Rolling gate requires all four explicit integrated core, collision, full-height and rolling opt-ins."); return 0; }
        if (p.server.isHardcore()) { say(p, "The diagnostic player gate does not support hardcore worlds."); return 0; }
        if (!p.isAlive()) { say(p, "Respawn before entering the rolling player gate."); return 0; }
        if (Boolean.getBoolean(SceneCommands.OPT_IN)) { say(p, "Launch the isolated rolling profile without the Earth visual scene opt-in."); return 0; }
        if (p.level().dimension().equals(SceneCommands.CARRIER)) { say(p, "Return to a normal dimension before opening a rolling host."); return 0; }
        if (CoreReturnPoint.remembered(p).isPresent()) { say(p, "Previous carrier recovery is unresolved; use /earth_roam leave first."); return 0; }
        if (session != null) { say(p, "Previous rolling host must finish draining; leave before entering again."); return 0; }
        var carrier=p.server.getLevel(SceneCommands.CARRIER);
        if (carrier == null) { say(p, "A fresh disposable world with the empty carrier is required."); return 0; }
        var palette=EnginePaletteContext.create(p.registryAccess().registryOrThrow(Registries.BIOME));
        var provider=RollingGateFixture.provider(palette, profile);
        long epoch=EPOCH.incrementAndGet();
        var origin=new CoreReturnPoint(p.level().dimension(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.gameMode.getGameModeForPlayer());
        RollingCubeActor actor;
        try { actor=RollingCubeActor.open(p.server.getWorldPath(LevelResource.ROOT).resolve(RollingGateFixture.directory(profile)), provider.identity(), provider, palette, epoch, profile.center()); }
        catch (RuntimeException failed) { EarthPlatformMod.LOGGER.warn("Rolling host admission failed", failed); say(p, "Rolling host could not open; wait for old owners to drain."); return 0; }
        var s=new Session(p, actor, origin, epoch, palette, profile, provider.identity());
        try { s.collision=new CoreCollisionBinding(carrier, p.getUUID(), epoch); }
        catch (RuntimeException failed) { actor.close(); EarthPlatformMod.LOGGER.warn("Rolling collision attachment failed", failed); say(p, "Another collision owner still holds the carrier."); return 0; }
        session=s; origin.remember(p);
        actor.closed().thenAccept(result -> EarthPlatformMod.LOGGER.info("ROLLING_GATE_ACTOR_DRAIN epoch={} successful={} detail={}", epoch, result.successful(), result.detail()));
        p.setGameMode(GameType.SPECTATOR); reset(p);
        p.teleportTo(carrier, profile.entry().x, profile.entry().y, profile.entry().z, Set.of(), 0, 31);
        if (p.serverLevel()!=carrier || !p.isSpectator()) {
            stop(s,p); boolean restored=restoreOrigin(p,origin);
            say(p,restored ? "Carrier entry was cancelled; original state restored." : "Carrier entry and safe return were cancelled; recovery record retained."); return 0;
        }
        say(p,"Rolling native gate: moving 125-cube collision window, 27 native targets plus one fixed edit pin. Wait for paired READY, then /earth_roam walk. Diagnostic X/Z safety limit is ±512m.");
        return 1;
    }

    public static void control(ServerPlayer p, RollingGateControlPayload frame) {
        var s=session;
        if (!owns(p,s) || s.stopping || frame.epoch()!=s.epoch) return;
        if (frame.kind()==RollingGateControlPayload.FENCED) {
            stop(s,p,false); say(p,"Rolling resources changed; collision fenced. Use /earth_roam leave, then enter again."); return;
        }
        if (!p.isAlive() || !p.level().dimension().equals(SceneCommands.CARRIER) || !validMode(p,s)) return;
        try {
            if (frame.kind()==RollingGateControlPayload.MOUSE) { mouseIntent(p,s,frame); return; }
            if (s.pendingManifest==null || !manifestMatches(s,s.pendingManifest)) return;
            if (frame.kind()==RollingGateControlPayload.PREPARED) {
                if (s.handshake.prepared(frame.epoch(),frame.generation(),frame.resourceGeneration(),frame.digest())) tryGrant(p,s);
            } else if (frame.kind()==RollingGateControlPayload.PAIR_READY
                    && s.handshake.pairReady(frame.epoch(),frame.generation(),frame.resourceGeneration(),frame.digest())) {
                if (!bodyInsideWindow(p.getBoundingBox(),s.pendingManifest.candidateCenter()))
                    throw new IllegalStateException("Player escaped candidate after server collision fence");
                if (s.editPublication) finishPublication(p,s);
                else if (s.actor.commitPrepared(s.pendingManifest.generation())!=RollingCubeActor.Admission.QUEUED)
                    throw new IllegalStateException("Exact actor commit was not admitted");
            }
        } catch (RuntimeException failed) { fail(s,p,"Paired publication failed",failed); }
    }

    private static void tryGrant(ServerPlayer p, Session s) {
        if (s.handshake.state()!=RollingPublicationHandshake.State.PREPARED || s.pendingManifest==null) return;
        if (!manifestMatches(s,s.pendingManifest)) throw new IllegalStateException("Prepared manifest no longer authoritative");
        if (!bodyInsideWindow(p.getBoundingBox(),s.pendingManifest.candidateCenter())) {
            if (!s.waitingForBody) {
                s.waitingForBody=true;
                say(p,"Prepared window is waiting for your body to return inside its known 125 cubes; the previous view remains active.");
                EarthPlatformMod.LOGGER.info("ROLLING_GATE_WAIT_BODY epoch={} publication={} candidate={} feet={}",s.epoch,s.handshake.publication(),s.pendingManifest.candidateCenter(),p.position());
            }
            return;
        }
        s.waitingForBody=false;
        // Never let a new visual pair coexist with old authoritative collision.
        s.collision.fence(); reset(p); s.handshake.grant(); sendPair(p,s,RollingGatePayload.ALLOW);
    }

    /** Contains the complete positive finite body, not merely its feet or the permanent edit pin. */
    static boolean bodyInsideWindow(AABB body, CubePos center) {
        return RollingWindowAdmission.contains(body,center);
    }

    private static Map<CubePos,EngineCubeSnapshot> windowSnapshots(Session s, CubePos center) {
        if (center==null) throw new IllegalStateException("No committed window");
        var result=new HashMap<CubePos,EngineCubeSnapshot>(125);
        for (var pos:RollingCubePlan.stationary(s.profile.center(),center).currentOwners()) {
            var snapshot=s.snapshots.get(pos);
            if (snapshot==null || !s.world.equals(snapshot.world()) || snapshot.epoch()!=s.epoch)
                throw new IllegalStateException("Authoritative moving window incomplete");
            result.put(pos,snapshot);
        }
        return Map.copyOf(result);
    }

    private static void finishPublication(ServerPlayer p,Session s) {
        var ready=s.pendingManifest;
        if (ready==null || !manifestMatches(s,ready) || !bodyInsideWindow(p.getBoundingBox(),ready.candidateCenter()))
            throw new IllegalStateException("Cannot publish collision outside exact known candidate");
        var collision=windowSnapshots(s,ready.candidateCenter());
        s.collision.publish(s.world,s.epoch,s.palette,collision);
        s.committedCenter=ready.candidateCenter(); s.committedManifest=ready;
        var keep=new HashSet<CubePos>(); for(var version:ready.versions())keep.add(version.position());
        s.snapshots.keySet().retainAll(keep);
        s.handshake.committed(); sendPair(p,s,RollingGatePayload.COMMITTED);
        var diagnostics=s.actor.diagnostics();
        EarthPlatformMod.LOGGER.info("ROLLING_GATE_COMMITTED epoch={} publication={} actorGeneration={} center={} owners={} collisionOwners={} digest={} residentCubes={} physicalProviders={} queuedGeneration={} queuedEvents={} queuedCommands={} snapshotReferences={} actorDiagnostics={}",
            s.epoch,s.handshake.publication(),ready.generation(),s.committedCenter,s.snapshots.size(),s.collision.residentOwners(),s.handshake.digest(),
            diagnostics.residentCubes(),diagnostics.physicalProviders(),diagnostics.queuedGeneration(),diagnostics.queuedEvents(),diagnostics.queuedCommands(),diagnostics.retainedSnapshotReferenceSlots(),diagnostics);
        boolean first=ready.generation()==1 && !s.editPublication;
        s.stage=null; s.pendingManifest=null; s.editPublication=false; s.editPending=false; s.waitingForBody=false;
        if(first)say(p,"Rolling pair READY. Native collision owns exactly 125 current cubes; /earth_roam walk is available.");
    }

    private static void sendPair(ServerPlayer p, Session s, int kind) {
        send(p,RollingGatePayload.create(b -> { b.writeByte(kind); b.writeLong(s.epoch); b.writeLong(s.handshake.publication()); b.writeLong(s.handshake.resourceGeneration()); b.writeUtf(s.handshake.digest(),64); }));
    }

    private static boolean manifestMatches(Session s, RollingCubeActor.StageReady ready) {
        if(!s.world.equals(ready.world()) || ready.epoch()!=s.epoch)return false;
        var expected=RollingCubePlan.stationary(s.profile.center(),ready.candidateCenter()).committedOwners();
        if(ready.versions().size()!=expected.size())return false;
        for(int i=0;i<expected.size();i++) {
            var version=ready.versions().get(i); var snapshot=s.snapshots.get(version.position());
            if(!expected.get(i).equals(version.position()) || snapshot==null || !s.world.equals(snapshot.world())
                || snapshot.epoch()!=s.epoch || snapshot.leaseGeneration()!=version.leaseGeneration() || snapshot.revision()!=version.revision())return false;
        }
        return true;
    }

    private static void mouseIntent(ServerPlayer p,Session s,RollingGateControlPayload intent) {
        if(!p.createCommandSourceStack().hasPermission(2) || !s.handshake.acceptsMouse(intent.epoch(),intent.generation(),intent.resourceGeneration())) { rejectIntent(p,s,"No exact committed operator pair"); return; }
        if(s.editPending || s.ray!=null || s.committedCenter==null || s.actor.diagnostics().lifecycle()!=RollingCubeActor.Lifecycle.OPEN) { rejectIntent(p,s,"Rolling stage or previous edit is not ready"); return; }
        long now=System.nanoTime();
        if(s.intentReceived && now-s.lastIntentNanos<250_000_000L) { rejectIntent(p,s,"Interaction rate limit"); return; }
        s.intentReceived=true; s.lastIntentNanos=now;
        var snapshots=windowSnapshots(s,s.committedCenter);
        if(!snapshots.containsKey(s.profile.target())) { rejectIntent(p,s,"Original editable cube is outside the current collision window"); return; }
        var eye=p.getEyePosition(); var look=p.getViewVector(1); var body=p.getBoundingBox(); var cancelled=new AtomicBoolean();
        try {
            var future=RAY_WORKER.submit(cancelled,()->NativeRayEditPlanner.plan(s.world,s.epoch,s.palette,snapshots,eye,look,8,intent.place()?NativeRayEditPlanner.Action.PLACE_DIORITE:NativeRayEditPlanner.Action.REMOVE,s.profile.target(),body));
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
                && p.createCommandSourceStack().hasPermission(2) && validMode(p,s) && p.level().dimension().equals(SceneCommands.CARRIER);
            String refusal=CoreIntentCommitGate.evaluate(active,pending.eye(),pending.look(),p.getEyePosition(),p.getViewVector(1),p.getBoundingBox(),plan,windowSnapshots(s,s.committedCenter));
            if(refusal!=null) { rejectIntent(p,s,refusal); return; }
            var request=plan.request();
            if(!RollingCubePlan.isEditableInterior(s.profile.target(),request.x(),request.y(),request.z())) { rejectIntent(p,s,"Original cube boundary shell is immutable; only local 1..14 may change"); return; }
            // Clear an old coalesced goal BEFORE edit admission. Keep holding this center until the
            // edit's paired publication completes, including after the durable ACK is consumed.
            s.actor.requestCenter(s.committedCenter);
            var edit=new RollingCubeActor.EditRequest(request.world(),request.epoch(),request.position(),request.leaseGeneration(),request.expectedRevision(),request.x(),request.y(),request.z(),request.stateId());
            var admitted=s.actor.tryEdit(edit);
            if(admitted!=RollingCubeActor.Admission.QUEUED) { rejectIntent(p,s,"Edit not admitted: "+admitted); return; }
            s.editPending=true; s.editPublication=true; long publication=s.handshake.begin();
            s.collision.fence(); reset(p);
            send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.EDIT_BEGIN);b.writeLong(s.epoch);b.writeLong(publication);b.writeLong(edit.x());b.writeLong(edit.y());b.writeLong(edit.z());}));
            EarthPlatformMod.LOGGER.info("ROLLING_GATE_EDIT_BEGIN epoch={} publication={} target={},{},{} revision={}",s.epoch,publication,edit.x(),edit.y(),edit.z(),edit.expectedRevision());
        } catch(CompletionException|CancellationException failed) { rejectIntent(p,s,"Ray preparation failed or was cancelled"); }
    }

    private static void rejectIntent(ServerPlayer p,Session s,String detail) {
        String bounded=detail.length()>512?detail.substring(0,512):detail;
        send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.REJECT);b.writeLong(s.epoch);b.writeUtf(bounded,512);}));
        long now=System.nanoTime();if(s.feedbackSent && now-s.lastFeedbackNanos<250_000_000L)return;
        s.feedbackSent=true;s.lastFeedbackNanos=now;say(p,"Rolling interaction rejected: "+bounded);
    }

    private static boolean validMode(ServerPlayer p,Session s) { return p.isSpectator() || s.walking && p.gameMode.getGameModeForPlayer()==GameType.ADVENTURE; }
    private static int walk(ServerPlayer p,boolean enable) {
        var s=session;
        if(!owns(p,s)||s.stopping||!p.level().dimension().equals(SceneCommands.CARRIER)) { say(p,"An active rolling session is required.");return 0; }
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
        return 1;
    }
    private static int status(ServerPlayer p) {
        var s=session;if(!owns(p,s)) { say(p,"No rolling session.");return 0; }
        var target=s.snapshots.get(s.profile.target());reportPhysics(s,p,"STATUS");
        var d=s.actor.diagnostics();
        say(p,"Rolling epoch="+s.epoch+" publication="+s.handshake.publication()+" state="+s.handshake.state()+" center="+s.committedCenter+" candidate="+(s.pendingManifest==null?d.candidateCenter():s.pendingManifest.candidateCenter())+" retained="+s.snapshots.size()+"/213 collision="+s.collision.residentOwners()+"/125 pinRevision="+(target==null?"unknown":target.revision())+" waitingBody="+s.waitingForBody+" editPending="+s.editPending+" draining="+s.stopping);
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
    private static int leave(ServerPlayer p){var s=session;if(!owns(p,s))return recoverOrphan(p);stop(s,p);return restoreOrigin(p,s.origin)?1:0;}
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){
        if(!RollingGateFixture.enabled())return;
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
    private static void stop(Session s,ServerPlayer p) { stop(s,p,p!=null&&p.isAlive()); }
    private static void stop(Session s,ServerPlayer p,boolean restoreSafeMode) {
        if(s.stopping)return;
        if(p!=null)reportPhysics(s,p,"PRE_CLOSE");
        s.stopping=true;
        if(restoreSafeMode&&p!=null&&s.walking&&p.gameMode.getGameModeForPlayer()==GameType.ADVENTURE) {
            s.walking=false;p.setGameMode(GameType.SPECTATOR);reset(p);
        }
        s.handshake.close();s.collision.fence();
        if(s.ray!=null) { s.ray.cancelled().set(true);s.ray=null; }
        s.actor.close();
        if(p!=null)send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.STOP);b.writeLong(s.epoch);}));
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post e) {
        if(!RollingGateFixture.enabled())return;
        pumpRecovery(e.getServer());
        var s=session;if(s==null||e.getServer()!=s.server)return;
        var p=s.server.getPlayerList().getPlayer(s.player);
        if(p!=null&&!p.isAlive())stop(s,p,false);
        else if(p==null||!p.level().dimension().equals(SceneCommands.CARRIER)||!validMode(p,s))stop(s,p);
        else if(!s.collision.preventsStockVoidCheck(p.getUUID(),p.getBoundingBox())) {
            stop(s,p);restoreOrigin(p,s.origin);say(p,"Native diagnostic safety envelope exceeded; gate closed and safe return requested.");
        }
        try {
            if(!s.stopping) {
                s.latestGoal=RollingCubePlan.centerFromFeet(p.getX(),p.getY(),p.getZ());
                // The latest feet are observed every tick. An edit barrier temporarily holds actor
                // goals at the committed center, until its same-generation revision pair commits.
                s.actor.requestCenter(s.editPublication?s.committedCenter:s.latestGoal);
                pumpRay(p,s);
            }
            for(var event:s.actor.pollEvents(2)) {
                if(event instanceof RollingCubeActor.Failure f) { fail(s,p,f.detail(),null);continue; }
                if(s.stopping||p==null)continue;
                handleEvent(p,s,event);
            }
            if(!s.stopping&&p!=null) {
                tryGrant(p,s);
                if(s.walking) {
                    var target=s.snapshots.get(s.profile.target());
                    s.physics.record(p,s.collision.ready(),target==null?-1:target.revision(),s.handshake.committedPublication());
                }
            }
        } catch(RuntimeException failed) { fail(s,p,"Rolling controller invariant failed",failed); }
        if(s.stopping&&s.actor.closed().isDone()) {
            EarthPlatformMod.LOGGER.info("ROLLING_GATE_SERVER_CLOSED epoch={} successful={}",s.epoch,s.actor.closed().join().successful());
            releaseCollision(s);s.snapshots.clear();s.pendingManifest=null;s.committedManifest=null;s.stage=null;session=null;
        }
    }

    private static void handleEvent(ServerPlayer p,Session s,RollingCubeActor.Event event) {
        if(event instanceof RollingCubeActor.StageMetadata m) {
            RollingGateWire.validateMetadata(m);
            if(s.handshake.pending()||s.editPublication||s.stage!=null||!s.world.equals(m.world())||s.epoch!=m.epoch()
                    ||!s.profile.center().equals(m.pinCenter())||m.initial()!=(s.committedManifest==null)
                    ||m.generation()!=(s.committedManifest==null?1:s.committedManifest.generation()+1)
                    ||s.committedCenter!=null&&!s.committedCenter.equals(m.currentCenter()))
                throw new IllegalStateException("Unexpected rolling metadata identity or publication overlap");
            if(m.currentLeases().size()!=s.snapshots.size())throw new IllegalStateException("Current leases differ from retained snapshots");
            for(var lease:m.currentLeases()) {
                var snapshot=s.snapshots.get(lease.position());
                if(snapshot==null||snapshot.leaseGeneration()!=lease.leaseGeneration())throw new IllegalStateException("Current lease changed before stage");
            }
            if(s.ray!=null) { s.ray.cancelled().set(true);s.ray=null;rejectIntent(p,s,"Window began staging before ray admission"); }
            s.stage=m;long publication=s.handshake.begin();
            send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.METADATA);b.writeLong(s.epoch);b.writeLong(publication);CoreGatePayload.world(b,m.world());s.profile.write(b,s.palette);RollingGateWire.writeMetadata(b,m);}));
            EarthPlatformMod.LOGGER.info("ROLLING_GATE_STAGE epoch={} publication={} actorGeneration={} current={} candidate={} added={} retired={}",s.epoch,publication,m.generation(),m.currentCenter(),m.candidateCenter(),m.newPositions().size(),m.retiredPositions().size());
        } else if(event instanceof RollingCubeActor.Snapshot v) {
            var snapshot=v.snapshot();
            if(!s.world.equals(snapshot.world())||snapshot.epoch()!=s.epoch)throw new IllegalStateException("Wrong snapshot source identity");
            if(s.editPublication) {
                var previous=s.snapshots.get(snapshot.position());
                if(!s.editPending||s.committedManifest==null||v.generation()!=s.committedManifest.generation()
                        ||!snapshot.position().equals(s.profile.target())||previous==null
                        ||previous.leaseGeneration()!=snapshot.leaseGeneration()||snapshot.revision()!=previous.revision()+1)
                    throw new IllegalStateException("Unexpected durable edit replacement");
            } else {
                if(s.stage==null||v.generation()!=s.stage.generation()||!s.stage.newPositions().contains(snapshot.position())
                        ||s.snapshots.containsKey(snapshot.position()))throw new IllegalStateException("Unexpected staged snapshot");
                var lease=s.stage.candidateLeases().stream().filter(l->l.position().equals(snapshot.position())).findFirst().orElseThrow();
                if(lease.leaseGeneration()!=snapshot.leaseGeneration())throw new IllegalStateException("Staged lease mismatch");
            }
            s.snapshots.put(snapshot.position(),snapshot);
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
            finishPublication(p,s);
        } else if(event instanceof RollingCubeActor.EditAck ack) {
            if(!s.editPublication||!s.editPending||s.committedManifest==null)throw new IllegalStateException("Unexpected edit acknowledgement");
            send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.EDIT_ACK);b.writeLong(s.epoch);b.writeLong(s.handshake.publication());b.writeUtf(ack.status().name(),64);b.writeLong(ack.revision());b.writeUtf(bounded(ack.detail()),512);}));
            EarthPlatformMod.LOGGER.info("ROLLING_GATE_EDIT_ACK epoch={} publication={} status={} revision={}",s.epoch,s.handshake.publication(),ack.status(),ack.revision());
            if(ack.status()==RollingCubeActor.EditStatus.SAVE_FAILED||ack.status()==RollingCubeActor.EditStatus.CLOSING) {
                fail(s,p,"Durable edit failed; session fenced",null);return;
            }
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
            var ready=new RollingCubeActor.StageReady(s.world,s.epoch,s.committedManifest.generation(),s.committedCenter,versions);
            publishReady(p,s,ready);
            say(p,"Rolling edit "+ack.status()+"; durable revision "+ack.revision()+". Waiting for the paired native/DH revision.");
        }
    }

    private static void publishReady(ServerPlayer p,Session s,RollingCubeActor.StageReady ready) {
        if(!manifestMatches(s,ready))throw new IllegalStateException("Exact ready manifest does not match authoritative snapshots");
        String digest=RollingGateWire.manifestDigest(ready);s.pendingManifest=ready;s.handshake.ready(digest);
        send(p,RollingGatePayload.create(b->{b.writeByte(RollingGatePayload.READY);b.writeLong(s.epoch);b.writeLong(s.handshake.publication());RollingGateWire.writeReady(b,ready);}));
    }
    private static String bounded(String detail) { return detail.length()>512?detail.substring(0,512):detail; }
    private static void fail(Session s,ServerPlayer p,String detail,Throwable failure) {
        EarthPlatformMod.LOGGER.error("ROLLING_GATE_FAILURE epoch={} detail={}",s.epoch,detail,failure);
        if(p!=null&&!s.stopping)say(p,"Rolling host failed: "+bounded(detail)+". Use /earth_roam leave for safe recovery.");
        stop(s,p);
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent e) {
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
        var target=s.snapshots.get(s.profile.target());long revision=target==null?-1:target.revision();
        s.physics.record(p,s.collision.ready(),revision,s.handshake.committedPublication());
        var j=new com.google.gson.JsonObject();
        j.addProperty("scope","Bounded rolling diagnostic collision only; no survival, fluids or general Level compatibility");
        j.addProperty("epoch",s.epoch);j.addProperty("profile",s.profile.id());j.addProperty("stage",stage);
        j.addProperty("publication",s.handshake.publication());j.addProperty("handshake",s.handshake.state().name());
        j.addProperty("committedCenter",String.valueOf(s.committedCenter));j.addProperty("latestGoal",String.valueOf(s.latestGoal));
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
            registrar.playToClient(RollingGatePayload.TYPE,RollingGatePayload.CODEC,(p,c)->RollingGateClient.handle(p));
            registrar.playToServer(RollingGateControlPayload.TYPE,RollingGateControlPayload.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer player)control(player,p);});
        }
    }
}
