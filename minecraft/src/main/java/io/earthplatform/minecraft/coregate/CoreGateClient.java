package io.earthplatform.minecraft.coregate;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.enginebridge.AnalyticNativeCubeProvider;
import io.earthplatform.minecraft.lod.DhGeographicLodView;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.*;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.joml.Matrix4f;

/** Actual admitted server snapshots; no client terrain generation or client-local save authority. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT)
public final class CoreGateClient {
    private static volatile Session current;
    private static ClientPacketListener lastConnection;
    private static long lastEpoch;
    private static String stopped="/earth_core enter starts the authoritative cubic engine gate";
    public static void handle(CoreGatePayload payload){
        var game=Minecraft.getInstance();
        if(!game.isSameThread()||!Boolean.getBoolean(CoreGateCommands.OPT_IN)||game.getSingleplayerServer()==null)throw new IllegalStateException("Integrated core gate required");
        var buffer=payload.open();try{
            int kind=buffer.readUnsignedByte();
            if(kind==CoreGatePayload.START){
                if(lastConnection!=game.getConnection()){lastConnection=game.getConnection();lastEpoch=0;}
                WorldIdentity world=CoreGatePayload.world(buffer);long epoch=buffer.readLong();CubePos center=CoreGatePayload.position(buffer);
                int count=buffer.readVarInt();if(count!=125)throw new IllegalArgumentException("Expected bounded fixed125 metadata");
                List<CubePos> owners=new ArrayList<>(count);List<Long> leases=new ArrayList<>(count);
                for(int i=0;i<count;i++){owners.add(CoreGatePayload.position(buffer));leases.add(buffer.readLong());}
                int targetCount=buffer.readVarInt();if(targetCount!=27)throw new IllegalArgumentException("Expected fixed27 mesh targets");
                List<CubePos> targets=new ArrayList<>(targetCount);for(int i=0;i<targetCount;i++)targets.add(CoreGatePayload.position(buffer));
                requireEnd(buffer);
                if(epoch<=lastEpoch)throw new IllegalArgumentException("Stale core connection epoch");
                if(!closeCurrent("Replacing core view"))return;
                lastEpoch=epoch;current=new Session(world,epoch,center,owners,leases,targets);current.initialize();return;
            }
            long epoch=buffer.readLong();var session=current;
            if(session==null||session.epoch!=epoch)return; // Retired session packets can never reopen owners.
            synchronized(session.fence){
                switch(kind){
                    case CoreGatePayload.SNAPSHOT -> session.accept(EngineCubeSnapshot.readFrame(buffer,session.world,epoch,session.palette));
                    case CoreGatePayload.EDIT_BEGIN -> {long x=buffer.readLong(),y=buffer.readLong(),z=buffer.readLong();requireEnd(buffer);session.beginEdit(x,y,z);}
                    case CoreGatePayload.EDIT_ACK -> {String status=buffer.readUtf(64);long revision=buffer.readLong();String detail=buffer.readUtf(512);requireEnd(buffer);session.ack(status,revision,detail);}
                    case CoreGatePayload.INTENT_REJECTED -> {String detail=buffer.readUtf(512);requireEnd(buffer);session.intentPending=false;session.status="Server ray rejected: "+detail;}
                    case CoreGatePayload.STOP -> {requireEnd(buffer);closeCurrent("Server core host draining");}
                    default -> throw new IllegalArgumentException("Unknown core frame kind");
                }
            }
        }catch(RuntimeException failure){EarthPlatformMod.LOGGER.error("CORE_GATE_CLIENT_FAILED",failure);closeCurrent("Core stream rejected: "+failure.getMessage());game.gui.getChat().addMessage(Component.literal(stopped));}finally{buffer.release();}
    }
    private static void requireEnd(FriendlyByteBuf b){if(b.isReadable())throw new IllegalArgumentException("Trailing core envelope bytes");}
    private static boolean closeCurrent(String reason){stopped=reason;var s=current;if(s==null)return true;try{s.close();current=null;return true;}catch(RuntimeException failure){stopped="Core cleanup pending; retained owners are fenced";EarthPlatformMod.LOGGER.error(stopped,failure);return false;}}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){var s=current;if(s!=null){var game=Minecraft.getInstance();s.inputLatch.observe(game.options.keyAttack.isDown(),game.options.keyUse.isDown());if(s.intentPending&&!s.paused&&System.nanoTime()-s.lastIntentNanos>3_000_000_000L){s.intentPending=false;s.status="Server intent response delayed; release before retrying";}}if(s!=null&&(!s.valid()||s.guard.invalidationRequested()))closeCurrent("Core view closed; /earth_core leave then enter rebuilds");}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){closeCurrent("Core disconnected");lastConnection=null;lastEpoch=0;}
    @SubscribeEvent public static void render(RenderLevelStageEvent e){if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;var s=current;if(s==null)return;try{s.render(e);}catch(RuntimeException failure){EarthPlatformMod.LOGGER.error("CORE_GATE_RENDER_FAILED",failure);closeCurrent("Core rendering stopped: "+failure.getMessage());}}
    @SubscribeEvent public static void input(InputEvent.InteractionKeyMappingTriggered event){
        var s=current;var game=Minecraft.getInstance();if(s==null||!s.valid()||game.screen!=null||(!event.isAttack()&&!event.isUseItem()))return;
        event.setCanceled(true);event.setSwingHand(false);
        if(!s.inputLatch.claim(event.isUseItem()))return;
        if(!game.options.getCameraType().isFirstPerson()){s.status="Core mouse editing requires first-person view";return;}
        if(!s.visible||s.paused||s.rebuildQueued||s.intentPending)return;
        long now=System.nanoTime();if(s.intentSent&&now-s.lastIntentNanos<250_000_000L)return;
        s.intentSent=true;s.lastIntentNanos=now;s.intentPending=true;s.status="Validating authoritative server ray";
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new CoreGateIntentPayload(s.epoch,event.isUseItem()));
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post e){var game=Minecraft.getInstance();if(game.level==null||game.player==null||game.options.hideGui||!game.level.dimension().equals(SceneCommands.CARRIER))return;var s=current;if(s==null){if(CoreGateFixture.collision()&&((io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess)game.level).earthplatform$coreCollisionView()!=null)e.getGuiGraphics().drawString(game.font,"Native collision fenced: /earth_core leave then enter",8,8,0xFFFFAA66);return;}var g=e.getGuiGraphics();
        List<String> lines=List.of("CUBIC CORE | server-owned generation / store / streamed snapshots", "Fixed125 cubes +27 native meshes | 512m DH | fixed light | "+(CoreGateFixture.collision()?"/earth_core walk or fly":"spectator"),(s.collision==null?s.status:s.collision.boundaryStatus()+" | "+s.status),"Mouse: left remove / right diorite | revision="+s.revision()+" | feetY="+String.format(java.util.Locale.ROOT,"%.3f",game.player.getY())+" grounded="+game.player.onGround()+" | /earth_core leave");
        int h=lines.size()*(game.font.lineHeight+2)+8,y=game.getDebugOverlay().showDebugScreen()?g.guiHeight()-h-6:6;
        int w=Math.min(g.guiWidth()-12,lines.stream().mapToInt(game.font::width).max().orElse(0)+10);g.fill(6,y,6+w,y+h,0xDD101820);g.enableScissor(6,y,6+w,y+h);
        for(int i=0;i<lines.size();i++)g.drawString(game.font,lines.get(i),11,y+4+i*(game.font.lineHeight+2),i==2?0xF2D178:0xE5F0F6);g.disableScissor();if(game.options.getCameraType().isFirstPerson()){int cx=g.guiWidth()/2,cy=g.guiHeight()/2;g.fill(cx-5,cy-1,cx+6,cy+2,0xFF101820);g.fill(cx-1,cy-5,cx+2,cy+6,0xFF101820);g.fill(cx-4,cy,cx+5,cy+1,0xFFFFFFFF);g.fill(cx,cy-4,cx+1,cy+5,0xFFFFFFFF);}g.flush();s.afterHud();}
    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Resources {@SubscribeEvent public static void register(RegisterClientReloadListenersEvent e){e.registerReloadListener((barrier,manager,prepare,apply,background,game)->{var s=current;if(s!=null)s.invalidate();return barrier.wait(null).thenRunAsync(()->closeCurrent("Resource reload closed core view; leave and enter to rebuild"),game);});}}

    private static final class Session implements AutoCloseable {
        final Object fence=new Object();final CoreInputLatch inputLatch;final ClientLevel carrier;final ClientPacketListener connection;
        final WorldIdentity world;final long epoch;final EnginePaletteContext palette;
        final CoreGateReplica replica;final CubeStreamAdmission admission;final RenderPublicationGuard guard;
        final List<CubePos> targets;final Map<CubePos,SceneCube> cubes;final Map<CubePos,SceneMesh> meshes=new LinkedHashMap<>();
        final ArrayDeque<CubePos> builds=new ArrayDeque<>();
        DynamicTexture light;DhGeographicLodView far;CoreCollisionBinding collision;boolean closed,disposed,paused,visible,rebuildQueued,reported,intentPending,intentSent;
        long frames,coverageGeneration,acceptedSnapshots,ackRevision=-1,maxUpdateNanos,lastIntentNanos,lastEditX=4,lastEditY=129,lastEditZ=4;int screenshots;String status="Receiving authoritative native cubes",capture="";Vec3 camera=Vec3.ZERO;
        Session(WorldIdentity world,long epoch,CubePos center,List<CubePos> owners,List<Long> leases,List<CubePos> targets){
            RenderSystem.assertOnRenderThread();var game=Minecraft.getInstance();carrier=Objects.requireNonNull(game.level);connection=Objects.requireNonNull(game.getConnection());inputLatch=new CoreInputLatch(game.options.keyAttack.isDown(),game.options.keyUse.isDown());
            if(!carrier.dimension().equals(SceneCommands.CARRIER)||!center.equals(CoreGateCommands.CENTER))throw new IllegalArgumentException("Core carrier/center mismatch");
            palette=EnginePaletteContext.create(carrier.registryAccess().registryOrThrow(Registries.BIOME));
            var expected=CoreGateFixture.provider(palette).identity();
            if(!world.equals(expected))throw new IllegalArgumentException("Authoritative generator/store/palette identity mismatch");
            var expectedOwners=CubeTickets.box(center,2,2,125).stream().sorted().toList();
            var expectedTargets=CubeTickets.box(center,1,1,27).stream().sorted().toList();
            if(!owners.equals(expectedOwners)||!targets.equals(expectedTargets))throw new IllegalArgumentException("Unexpected fixed3D interest metadata");
            this.world=world;this.epoch=epoch;this.targets=List.copyOf(targets);
            replica=new CoreGateReplica(world,epoch,palette,owners,leases,targets);admission=replica.admission();cubes=replica.cubes();guard=new RenderPublicationGuard(admission,125);
        }
        void initialize(){if(CoreGateFixture.collision())collision=new CoreCollisionBinding(carrier);light=new DynamicTexture(16,16,false);var image=Objects.requireNonNull(light.getPixels());for(int y=0;y<16;y++)for(int x=0;x<16;x++)image.setPixelRGBA(x,y,-1);light.upload();report("STARTED");}
        boolean valid(){var g=Minecraft.getInstance();return !closed&&g.level==carrier&&g.getConnection()==connection&&g.player!=null&&(g.player.isSpectator()||(collision!=null&&g.gameMode!=null&&g.gameMode.getPlayerMode()==net.minecraft.world.level.GameType.ADVENTURE));}
        void invalidate(){synchronized(fence){fenceCollision();guard.requestInvalidation();if(far!=null)far.requestInvalidation();visible=false;}}
        void accept(EngineCubeSnapshot snapshot){
            if(closed||guard.invalidationRequested())return;
            replica.accept(snapshot);
            acceptedSnapshots++;status="Streamed "+cubes.size()+"/125 native cubes";
            if(cubes.size()==125&&!paused&&!rebuildQueued&&!visible)queueRebuild();
        }
        void beginEdit(long x,long y,long z){if(!CubePos.fromBlock(x,y,z).equals(CoreGateCommands.TARGET))throw new IllegalArgumentException("Edit target exceeds pinned native cube");lastEditX=x;lastEditY=y;lastEditZ=z;fenceCollision();intentPending=false;replica.beginEdit();paused=true;visible=false;status="Durable edit pending; near/far pair fenced";report("EDIT_PENDING");}
        void ack(String result,long revision,String detail){
            replica.acknowledge(result,revision);ackRevision=replica.acknowledgedRevision();
            paused=false;status="Edit "+result+" revision "+revision+"; rebuilding exact native coverage";
            if(cubes.size()==125)queueRebuild();report("ACK_"+result);
        }
        void queueRebuild(){fenceCollision();visible=false;clearMeshes();if(far!=null){far.close();far=null;}builds.clear();builds.addAll(targets);rebuildQueued=true;reported=false;}
        void fenceCollision(){
            if(collision==null)return;var game=Minecraft.getInstance();var binding=collision;
            // Native movement and collision publication are serialized on the game owner thread.
            // Background asset invalidation immediately fences rendering; current immutable
            // collision data remains authoritative until this queued owner-thread close.
            game.execute(()->{binding.fence();if(current==this&&game.level==carrier&&game.getConnection()==connection&&((io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess)carrier).earthplatform$coreCollisionView()==binding&&game.player!=null){game.player.setOnGround(false);game.player.resetFallDistance();game.player.setDeltaMovement(Vec3.ZERO);}notifyCollision(false);});
        }
        void notifyCollision(boolean ready){
            if(collision==null)return;var payload=new CoreGateCollisionPayload(epoch,revision(),coverageGeneration,ready);var game=Minecraft.getInstance();
            game.execute(()->{if(game.getConnection()==connection)net.neoforged.neoforge.network.PacketDistributor.sendToServer(payload);});
        }
        void publishCollision(){
            if(collision==null)return;
            Map<CubePos,EngineCubeSnapshot> snapshots=new HashMap<>();for(var entry:cubes.entrySet())snapshots.put(entry.getKey(),entry.getValue().snapshot());
            collision.publish(world,epoch,palette,Map.copyOf(snapshots));notifyCollision(true);
        }
        long revision(){var cube=cubes.get(CoreGateCommands.TARGET);return cube==null?-1:cube.snapshot().revision();}
        void render(RenderLevelStageEvent e){synchronized(fence){
            if(!valid()||guard.invalidationRequested())return;frames++;camera=e.getCamera().getPosition();
            if(Math.max(Math.abs(camera.x),Math.max(Math.abs(camera.z),Math.abs(camera.y-128)))>400){status="Outside fixed core gate; return to its known working set";return;}
            long start=System.nanoTime();
            if(!paused&&rebuildQueued){
                if(!builds.isEmpty()){var p=builds.removeFirst();var mesh=SceneMesh.build(guard,Objects.requireNonNull(cubes.get(p)),new SceneNeighborhood(cubes));meshes.put(p,mesh);status="Compiling authoritative native meshes "+meshes.size()+"/27";}
                if(builds.isEmpty()){
                    var sampler=new SceneSurfaceSampler(){public long surfaceY(long x,long z){return 128;}public int argb(long x,long z,long y){return 0xFF808080;}public String identity(){return world.datasetVersion();}};
                    far=new DhGeographicLodView(sampler,world,epoch,guard.resourceGeneration(),0,0);far.initialize();
                    var ready=meshes.values().stream().map(m->{var s=m.snapshot();if(!m.isCurrent())throw new IllegalStateException("Native candidate stale");return new SceneReadyCube(s.position(),world,epoch,s.leaseGeneration(),s.revision(),guard.resourceGeneration());}).toList();
                    coverageGeneration++;if(!far.replaceCoverage(coverageGeneration,ready))throw new IllegalStateException("Far refused exact authoritative coverage");
                    // Every permitted edit lies in TARGET, which must stay covered by a real current mesh.
                    if(!meshes.containsKey(CoreGateCommands.TARGET)||!meshes.get(CoreGateCommands.TARGET).isCurrent())throw new IllegalStateException("Edited volume lost its pinned native coverage");
                    rebuildQueued=false;visible=true;publishCollision();status="READY: server cubes + durable revision "+revision()+" + exact near/far mask";capture="READY_REV_"+revision();report(capture);
                }
            }
            maxUpdateNanos=Math.max(maxUpdateNanos,System.nanoTime()-start);
            if(!visible||paused)return;
            for(var mesh:meshes.values())if(!mesh.isCurrent())throw new IllegalStateException("Current paired frame lost a native dependency");
            if(!far.coverageReady(coverageGeneration))throw new IllegalStateException("Paired far generation mismatch");
            var projection=SceneSession.wideProjection(e.getProjectionMatrix());var view=new Matrix4f(e.getModelViewMatrix());RenderSystem.clear(256,Minecraft.ON_OSX);
            far.draw(new Matrix4f(projection).mul(view),camera.x,camera.y,camera.z,guard.resourceGeneration());
            for(var mesh:meshes.values())if(!mesh.draw(view,projection,camera.x,camera.y,camera.z,light.getId()))throw new IllegalStateException("Near draw rejected after paired far submission");
            reported=true;
        }}
        void afterHud(){if(capture.isEmpty()||!reported||screenshots>=6)return;String stage=capture;capture="";try(var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){Files.createDirectories(directory());image.writeToFile(directory().resolve("epoch-"+epoch+"-"+stage.toLowerCase(Locale.ROOT)+".png"));screenshots++;report(stage);}catch(IOException failure){throw new UncheckedIOException(failure);}}
        Path directory(){return Path.of("earthplatform-evidence","authoritative-core-gate");}
        void report(String stage){var j=new JsonObject();j.addProperty("stage",stage);j.addProperty("epoch",epoch);j.addProperty("collisionOwners",collision==null?0:collision.residentOwners());j.addProperty("nativeCollisionFrameReady",collision!=null&&collision.ready());j.addProperty("scope","Server-owned generation/durable store/network snapshots; fixed native coverage; optional bounded collision-only walk gate; full survival and material/tick compatibility incomplete");j.addProperty("world",world.toString());j.addProperty("residentCubes",cubes.size());j.addProperty("nativeMeshes",meshes.size());j.addProperty("nativeVertices",meshes.values().stream().mapToInt(SceneMesh::vertices).sum());j.addProperty("acceptedNetworkSnapshots",acceptedSnapshots);j.addProperty("targetRevision",revision());j.addProperty("lastDurableAckRevision",ackRevision);j.addProperty("lastEditX",lastEditX);j.addProperty("lastEditY",lastEditY);j.addProperty("lastEditZ",lastEditZ);j.addProperty("targetStateId",cubes.containsKey(CoreGateCommands.TARGET)?Block.BLOCK_STATE_REGISTRY.getId(cubes.get(CoreGateCommands.TARGET).state(CubePos.local(lastEditX),CubePos.local(lastEditY),CubePos.local(lastEditZ))):-1);j.addProperty("coverageGeneration",coverageGeneration);j.addProperty("pausedForDurability",paused);j.addProperty("pairedDrawVisible",visible);j.addProperty("fixedEditedVolumePinnedToActualNativeCoverage",true);j.addProperty("editAwareDistant3DSummaryAvailable",false);j.addProperty("nativeCameraX",camera.x);j.addProperty("nativeCameraY",camera.y);j.addProperty("nativeCameraZ",camera.z);j.addProperty("maxCpuUpdateNanos",maxUpdateNanos);j.addProperty("closed",closed);j.addProperty("disposed",disposed);j.addProperty("hardwareGpuPerformanceClaim",false);if(far!=null)j.add("distantHorizons",far.diagnostics());
            try{Files.createDirectories(directory());Files.writeString(directory().resolve("epoch-"+epoch+"-"+stage.toLowerCase(Locale.ROOT)+".json"),j+"\n");}catch(IOException e){throw new UncheckedIOException(e);}EarthPlatformMod.LOGGER.info("CORE_GATE_{} epoch={} revision={} nativeOwners={} meshes={}",stage,epoch,revision(),cubes.size(),meshes.size());}
        void clearMeshes(){Throwable failure=null;for(var it=meshes.entrySet().iterator();it.hasNext();){var entry=it.next();try{entry.getValue().close();it.remove();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}}if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;}
        public void close(){synchronized(fence){if(disposed)return;closed=true;visible=false;fenceCollision();guard.close();Throwable failure=null;try{clearMeshes();}catch(RuntimeException|Error e){failure=e;}try{if(far!=null)far.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}try{if(light!=null)light.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}replica.close();builds.clear();if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;disposed=true;report("CLOSED");}}
    }
}
