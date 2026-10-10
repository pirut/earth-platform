package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.enginebridge.AnalyticNativeCubeProvider;
import io.earthplatform.minecraft.enginebridge.NativeCubeActor;
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
    private record ReturnPoint(ResourceKey<Level> dimension,double x,double y,double z,float yaw,float pitch,GameType mode){}
    private static final class Session {
        final MinecraftServer server;final UUID player;final NativeCubeActor actor;final ReturnPoint origin;final long epoch;
        final Map<CubePos,EngineCubeSnapshot> snapshots=new HashMap<>();boolean stopping,editPending;
        Session(ServerPlayer p,NativeCubeActor a,ReturnPoint o,long e){server=p.server;player=p.getUUID();actor=a;origin=o;epoch=e;}
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent e){e.getDispatcher().register(Commands.literal("earth_core").requires(s->s.hasPermission(2))
        .then(Commands.literal("enter").executes(c->enter(c.getSource().getPlayerOrException())))
        .then(Commands.literal("leave").executes(c->leave(c.getSource().getPlayerOrException())))
        .then(Commands.literal("edit").executes(c->edit(c.getSource().getPlayerOrException(),false)))
        .then(Commands.literal("erase").executes(c->edit(c.getSource().getPlayerOrException(),true)))
        .then(Commands.literal("status").executes(c->status(c.getSource().getPlayerOrException()))));}
    private static boolean allowed(ServerPlayer p){return Boolean.getBoolean(OPT_IN)&&p.server.isSingleplayer()&&!p.server.isDedicatedServer();}
    private static int enter(ServerPlayer p){
        if(!allowed(p)){say(p,"Explicit integrated core-gate opt-in required.");return 0;}
        if(Boolean.getBoolean(SceneCommands.OPT_IN)){say(p,"Core gate and Earth visual scene opt-ins are mutually exclusive; launch the isolated core profile.");return 0;}
        if(p.level().dimension().equals(SceneCommands.CARRIER)){say(p,"Return to a normal dimension before opening a new core host.");return 0;}
        if(session!=null){say(p,"Previous core host must finish draining; use /earth_core leave, then enter.");return 0;}
        var carrier=p.server.getLevel(SceneCommands.CARRIER);if(carrier==null){say(p,"Fresh disposable world with the empty carrier is required.");return 0;}
        var palette=EnginePaletteContext.create(p.registryAccess().registryOrThrow(Registries.BIOME));
        var provider=new AnalyticNativeCubeProvider(0,AnalyticNativeCubeProvider.Config.flat(128),palette);
        long epoch=EPOCH.incrementAndGet();
        var origin=new ReturnPoint(p.level().dimension(),p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot(),p.gameMode.getGameModeForPlayer());
        var actor=NativeCubeActor.open(p.server.getWorldPath(LevelResource.ROOT).resolve("earthplatform-core-gate"),provider.identity(),provider,palette,epoch,CENTER);
        session=new Session(p,actor,origin,epoch);
        actor.closed().thenAccept(result->EarthPlatformMod.LOGGER.info("CORE_GATE_ACTOR_DRAIN epoch={} successful={} detail={}",epoch,result.successful(),result.detail()));
        p.setGameMode(GameType.SPECTATOR);reset(p);p.teleportTo(carrier,4.5,133.0,-3.5,Set.of(),0,35);
        if(p.serverLevel()!=carrier){actor.close();session.stopping=true;p.setGameMode(origin.mode());say(p,"Another mod cancelled carrier travel.");return 0;}
        say(p,"Core engine: server generation + durable store + streamed cubes. Fixed 125-cube box, 27 native meshes, 512m DH. Spectator carrier; no player collision yet.");return 1;
    }
    private static int edit(ServerPlayer p,boolean air){
        var s=session;if(!owns(p,s)||s.stopping||s.editPending){say(p,"Core host unavailable or one edit is already pending.");return 0;}
        if(!p.level().dimension().equals(SceneCommands.CARRIER)){say(p,"Core edits require its active carrier.");return 0;}
        var snap=s.snapshots.get(TARGET);if(snap==null||s.snapshots.size()!=125){say(p,"Target is unknown; edit rejected.");return 0;}
        int state=Block.BLOCK_STATE_REGISTRY.getId((air?Blocks.AIR:Blocks.DIORITE).defaultBlockState());
        var request=new NativeCubeActor.EditRequest(snap.world(),s.epoch,TARGET,snap.leaseGeneration(),snap.revision(),EDIT_X,EDIT_Y,EDIT_Z,state);
        var result=s.actor.tryEdit(request);
        if(result!=NativeCubeActor.Admission.QUEUED){say(p,"Edit not admitted: "+result);return 0;}
        s.editPending=true;send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.EDIT_BEGIN);b.writeLong(s.epoch);}));
        say(p,"Edit admitted; waiting for durable save before acknowledging or drawing the new revision.");return 1;
    }
    private static int status(ServerPlayer p){var s=session;if(!owns(p,s)){say(p,"No core session.");return 0;}var snap=s.snapshots.get(TARGET);say(p,"Server epoch="+s.epoch+" received="+s.snapshots.size()+"/125 target durable revision="+(snap==null?"unknown":snap.revision())+" editPending="+s.editPending+" draining="+s.stopping);return 1;}
    private static int leave(ServerPlayer p){var s=session;if(!owns(p,s))return 0;stop(s,p);var o=s.origin;var target=p.server.getLevel(o.dimension());if(target!=null){reset(p);p.teleportTo(target,o.x(),o.y(),o.z(),Set.of(),o.yaw(),o.pitch());if(p.serverLevel()==target)p.setGameMode(o.mode());}return 1;}
    private static void stop(Session s,ServerPlayer p){if(s.stopping)return;s.stopping=true;s.actor.close();if(p!=null)send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.STOP);b.writeLong(s.epoch);}));}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        var s=session;if(s==null||e.getServer()!=s.server)return;var p=s.server.getPlayerList().getPlayer(s.player);
        if(p==null||!p.level().dimension().equals(SceneCommands.CARRIER)||!p.isSpectator())stop(s,p);
        for(var event:s.actor.pollEvents(2)){
            if(event instanceof NativeCubeActor.ReadyMetadata m){if(s.stopping||p==null)continue;send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.START);CoreGatePayload.world(b,m.world());b.writeLong(m.epoch());CoreGatePayload.position(b,m.center());b.writeVarInt(m.leases().size());for(var l:m.leases()){CoreGatePayload.position(b,l.position());b.writeLong(l.generation());}b.writeVarInt(m.renderTargets().size());for(var target:m.renderTargets())CoreGatePayload.position(b,target);}));}
            else if(event instanceof NativeCubeActor.Snapshot v){var snapshot=v.snapshot();s.snapshots.put(snapshot.position(),snapshot);if(!s.stopping&&p!=null)send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.SNAPSHOT);b.writeLong(s.epoch);snapshot.write(b);}));}
            else if(event instanceof NativeCubeActor.EditAck ack){s.editPending=false;EarthPlatformMod.LOGGER.info("CORE_GATE_EDIT_ACK epoch={} status={} revision={} target={},{},{}",s.epoch,ack.status(),ack.revision(),ack.request().x(),ack.request().y(),ack.request().z());if(!s.stopping&&p!=null){send(p,CoreGatePayload.create(b->{b.writeByte(CoreGatePayload.EDIT_ACK);b.writeLong(s.epoch);b.writeUtf(ack.status().name(),64);b.writeLong(ack.revision());b.writeUtf(ack.detail(),512);}));say(p,"Core edit "+ack.status()+"; durable revision "+ack.revision());}}
            else if(event instanceof NativeCubeActor.Failure f){EarthPlatformMod.LOGGER.error("CORE_GATE_FAILURE {}",f.detail());if(p!=null)say(p,"Core host failed: "+f.detail());stop(s,p);}
        }
        if(s.stopping&&s.actor.closed().isDone()){EarthPlatformMod.LOGGER.info("CORE_GATE_SERVER_CLOSED epoch={} successful={}",s.epoch,s.actor.closed().join().successful());s.snapshots.clear();session=null;}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){var s=session;if(s!=null&&s.server==e.getServer()){s.actor.close();s.snapshots.clear();session=null;/* actor retains itself through physical drain */}}
    private static boolean owns(ServerPlayer p,Session s){return allowed(p)&&s!=null&&s.player.equals(p.getUUID())&&s.server==p.server;}
    private static void send(ServerPlayer p,CoreGatePayload payload){PacketDistributor.sendToPlayer(p,payload);}
    private static void say(ServerPlayer p,String message){p.sendSystemMessage(Component.literal(message));}
    private static void reset(ServerPlayer p){p.setOnGround(false);p.resetFallDistance();p.setDeltaMovement(Vec3.ZERO);}
    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
    public static final class Payloads {@SubscribeEvent public static void register(RegisterPayloadHandlersEvent e){e.registrar("authoritative-core-gate-1").playToClient(CoreGatePayload.TYPE,CoreGatePayload.CODEC,(p,c)->CoreGateClient.handle(p));}}
}
