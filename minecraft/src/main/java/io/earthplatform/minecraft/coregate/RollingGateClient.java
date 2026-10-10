package io.earthplatform.minecraft.coregate;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.lod.DhGeographicLodView;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.*;
import java.io.IOException;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/** Bounded rolling replica: only an acknowledged exact near/far pair may advance collision. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT)
public final class RollingGateClient {
    private static volatile Session current;
    private static ClientPacketListener lastConnection;
    private static long lastEpoch;
    private static String stopped="/earth_roam enter starts the rolling cubic engine gate";
    public static void handle(RollingGatePayload payload){
        var game=Minecraft.getInstance();
        if(!game.isSameThread()||!RollingGateFixture.enabled()||game.getSingleplayerServer()==null)throw new IllegalStateException("Integrated rolling gate required");
        var b=payload.open();try{
            int kind=b.readUnsignedByte();long epoch=b.readLong();
            if(kind==RollingGatePayload.METADATA){
                long publication=b.readLong();var world=CoreGatePayload.world(b);
                var palette=EnginePaletteContext.create(Objects.requireNonNull(game.level).registryAccess().registryOrThrow(Registries.BIOME));
                var profile=CoreGateProfile.read(b,palette);var metadata=RollingGateWire.readMetadata(b,world,epoch);end(b);
                if(lastConnection!=game.getConnection()){lastConnection=game.getConnection();lastEpoch=0;}
                if(metadata.initial()){
                    if(epoch<=lastEpoch)throw new IllegalArgumentException("Stale rolling epoch");
                    if(!closeCurrent("Replacing rolling view"))return;
                    lastEpoch=epoch;current=new Session(world,epoch,palette,profile);current.initialize();
                }
                var s=current;if(s==null||s.epoch!=epoch)return;
                if(!s.world.equals(world)||s.profile!=profile)throw new IllegalArgumentException("Changed rolling identity");
                synchronized(s.fence){s.metadata(publication,metadata);}return;
            }
            var s=current;if(s==null||s.epoch!=epoch)return;
            synchronized(s.fence){switch(kind){
                case RollingGatePayload.SNAPSHOT -> {long generation=b.readLong();var snap=EngineCubeSnapshot.readFrame(b,s.world,epoch,s.palette);end(b);s.snapshot(generation,snap);}
                case RollingGatePayload.READY -> {long publication=b.readLong();var ready=RollingGateWire.readReady(b,s.world,epoch);end(b);s.ready(publication,ready);}
                case RollingGatePayload.ALLOW -> {long publication=b.readLong(),resource=b.readLong();String digest=b.readUtf(64);end(b);s.allow(publication,resource,digest);}
                case RollingGatePayload.COMMITTED -> {long publication=b.readLong(),resource=b.readLong();String digest=b.readUtf(64);end(b);s.committed(publication,resource,digest);}
                case RollingGatePayload.EDIT_BEGIN -> {long publication=b.readLong(),x=b.readLong(),y=b.readLong(),z=b.readLong();end(b);s.beginEdit(publication,x,y,z);}
                case RollingGatePayload.EDIT_ACK -> {long publication=b.readLong();String status=b.readUtf(64);long revision=b.readLong();String detail=b.readUtf(512);end(b);s.ack(publication,status,revision,detail);}
                case RollingGatePayload.STOP -> {end(b);closeCurrent("Rolling server draining");}
                case RollingGatePayload.REJECT -> {String detail=b.readUtf(512);end(b);s.intentPending=false;s.status="Server rejected: "+detail;}
                default -> throw new IllegalArgumentException("Unknown rolling frame");
            }}
        }catch(RuntimeException failure){EarthPlatformMod.LOGGER.error("ROLLING_CLIENT_FAILED",failure);closeCurrent("Rolling stream failed: "+failure.getMessage());game.gui.getChat().addMessage(Component.literal(stopped));}finally{b.release();}
    }
    private static void end(FriendlyByteBuf b){if(b.isReadable())throw new IllegalArgumentException("Trailing rolling envelope bytes");}
    private static boolean closeCurrent(String reason){stopped=reason;var s=current;if(s==null)return true;try{s.close();current=null;return true;}catch(RuntimeException failure){stopped="Rolling cleanup pending; owners remain fenced";EarthPlatformMod.LOGGER.error(stopped,failure);return false;}}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){var s=current;if(s==null)return;var g=Minecraft.getInstance();s.input.observe(g.options.keyAttack.isDown(),g.options.keyUse.isDown());if(!s.valid()||s.guard.invalidationRequested())closeCurrent("Rolling view closed; /earth_roam leave then enter");}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){closeCurrent("Rolling disconnected");lastConnection=null;lastEpoch=0;}
    @SubscribeEvent public static void render(RenderLevelStageEvent e){if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;var s=current;if(s==null)return;try{s.render(e);}catch(RuntimeException failure){EarthPlatformMod.LOGGER.error("ROLLING_RENDER_FAILED",failure);closeCurrent("Rolling rendering failed: "+failure.getMessage());}}
    @SubscribeEvent public static void input(InputEvent.InteractionKeyMappingTriggered e){
        var s=current;var g=Minecraft.getInstance();if(s==null||!s.valid()||g.screen!=null||(!e.isAttack()&&!e.isUseItem()))return;
        e.setCanceled(true);e.setSwingHand(false);if(!s.input.claim(e.isUseItem()))return;
        if(!g.options.getCameraType().isFirstPerson()){s.status="Mouse editing requires first-person view";return;}
        if(!s.visible||s.pending||s.intentPending||!s.collision.ready())return;
        long now=System.nanoTime();if(now-s.lastIntent<250_000_000L)return;
        s.lastIntent=now;s.intentPending=true;s.status="Validating authoritative rolling ray";
        s.send(new RollingGateControlPayload(s.epoch,s.publication,s.guard.resourceGeneration(),RollingGateControlPayload.MOUSE,"",e.isUseItem()));
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post e){
        if(!RollingGateFixture.enabled())return;var g=Minecraft.getInstance();if(g.level==null||g.player==null||g.options.hideGui||!g.level.dimension().equals(SceneCommands.CARRIER))return;
        var s=current;var gg=e.getGuiGraphics();
        List<String> lines=s==null?List.of(stopped):List.of("ROLLING CUBIC CORE | "+s.profile.id()+" | native ground Y "+s.profile.anchorY(),
            "5×5×5 collision | resident "+s.replica.cubes().size()+"/213 | meshes "+s.published.size()+"+"+s.candidate.size()+" | fixed512m DH",
            "Center "+s.replica.currentCenter()+" | publication "+s.publication+" | pinned revision "+s.replica.revision(),
            s.status,"Mouse: original cube interior only | /earth_roam walk, fly, status, leave");
        int w=Math.max(30,gg.guiWidth()-12);List<net.minecraft.util.FormattedCharSequence> wrapped=new ArrayList<>();for(var line:lines)wrapped.addAll(g.font.split(Component.literal(line),w-10));
        int h=wrapped.size()*(g.font.lineHeight+2)+8,y=g.getDebugOverlay().showDebugScreen()?Math.max(6,gg.guiHeight()-h-6):6;
        gg.fill(6,y,6+w,y+h,0xDD101820);gg.enableScissor(6,y,6+w,Math.min(gg.guiHeight(),y+h));
        for(int i=0;i<wrapped.size();i++)gg.drawString(g.font,wrapped.get(i),11,y+4+i*(g.font.lineHeight+2),0xE5F0F6);gg.disableScissor();
        if(g.options.getCameraType().isFirstPerson()){int x=gg.guiWidth()/2,z=gg.guiHeight()/2;gg.fill(x-4,z,x+5,z+1,-1);gg.fill(x,z-4,x+1,z+5,-1);}gg.flush();if(s!=null)s.afterHud();
    }
    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Resources {@SubscribeEvent public static void register(RegisterClientReloadListenersEvent e){e.registerReloadListener((barrier,manager,prepare,apply,background,game)->{var s=current;if(s!=null)s.invalidate();return barrier.wait(null).thenRunAsync(()->closeCurrent("Resource reload closed rolling view; leave then enter"),game);});}}

    private static final class Session implements AutoCloseable {
        final Object fence=new Object();final WorldIdentity world;final long epoch;final EnginePaletteContext palette;final CoreGateProfile profile;
        final ClientLevel carrier;final ClientPacketListener connection;final CoreInputLatch input;final RollingGateReplica replica;final RenderPublicationGuard guard;
        final Map<CubePos,SceneMesh> published=new LinkedHashMap<>(),candidate=new LinkedHashMap<>();
        final Set<SceneMesh> owned=Collections.newSetFromMap(new IdentityHashMap<>());
        final RollingMeshWorkQueue<SceneMesh.Build,SceneMesh> meshWork;
        final WorkTiming farInitialization=new WorkTiming(),farCoverage=new WorkTiming(),pairInstallation=new WorkTiming(),updateWork=new WorkTiming();
        CoreCollisionBinding collision;DynamicTexture light;DhGeographicLodView far;
        boolean closed,disposed,visible,pending,edit,preparedSent,allowed,pairSent,intentPending,drawn;
        boolean closingPublicationPending,closingPreparedSent;int closingQueuedTargets=-1,closingActiveBuilds=-1,closingCompletedSlots=-1;
        RollingMeshWorkQueue.Phase lastWorkPhase=RollingMeshWorkQueue.Phase.IDLE;
        long sourceHeightCallbacks,sourceColorCallbacks,maxSourceHeightCallbacksPerUpdate,maxSourceColorCallbacksPerUpdate;
        long publication,coverageGeneration,acceptedSnapshots,peakResident,peakMeshes,peakMeshReservations,maxUpdateNanos,lastIntent,lastEditX=4,lastEditY,lastEditZ=4;int reports,screenshots;
        String digest="",status="Opening rolling replica",capture="";Vec3 camera=Vec3.ZERO;
        Session(WorldIdentity world,long epoch,EnginePaletteContext palette,CoreGateProfile profile){
            RenderSystem.assertOnRenderThread();var g=Minecraft.getInstance();this.world=world;this.epoch=epoch;this.palette=palette;this.profile=profile;lastEditY=profile.commandEdit().getY();
            carrier=Objects.requireNonNull(g.level);connection=Objects.requireNonNull(g.getConnection());input=new CoreInputLatch(g.options.keyAttack.isDown(),g.options.keyUse.isDown());
            if(!carrier.dimension().equals(SceneCommands.CARRIER)||!world.equals(RollingGateFixture.provider(palette,profile).identity()))throw new IllegalArgumentException("Rolling carrier/provider identity mismatch");
            replica=new RollingGateReplica(world,epoch,palette,profile);guard=new RenderPublicationGuard(replica.admission(),RollingCubePlan.MAX_STAGED_OWNERS);
            meshWork=new RollingMeshWorkQueue<>(new RollingMeshWorkQueue.Driver<SceneMesh.Build,SceneMesh>(){
                public SceneMesh.Build begin(CubePos pos){
                    // Reserve before the candidate can acquire CPU buffers or any GPU generation.
                    if(owned.size()>=RollingCubePlan.MAX_STAGED_MESH_GENERATIONS)throw new IllegalStateException("Native mesh generation reservation exhausted");
                    peakMeshReservations=Math.max(peakMeshReservations,owned.size()+1L);
                    return SceneMesh.begin(guard,Objects.requireNonNull(replica.cubes().get(pos)),new SceneNeighborhood(replica.cubes(),SceneRenderEnvelope.PLAYER_DIAGNOSTIC_PADDING));
                }
                public RollingMeshWorkQueue.StepStats step(SceneMesh.Build build,int maxCallbacks,long softNanos){
                    var v=build.step(maxCallbacks,softNanos);
                    return new RollingMeshWorkQueue.StepStats(v.blocks(),v.completedBlocks(),v.totalBlocks(),v.elapsedNanos(),v.budgetNanos(),v.overshootNanos(),v.maxBlockNanos(),v.complete());
                }
                public SceneMesh finish(SceneMesh.Build build){return build.finish();}
                public void closeBuild(SceneMesh.Build build){build.close();}
                public void closeMesh(SceneMesh mesh){mesh.close();}
            });
        }
        void initialize(){collision=new CoreCollisionBinding(carrier,Objects.requireNonNull(Minecraft.getInstance().player).getUUID(),epoch);light=new DynamicTexture(16,16,false);var pixels=Objects.requireNonNull(light.getPixels());for(int y=0;y<16;y++)for(int x=0;x<16;x++)pixels.setPixelRGBA(x,y,-1);light.upload();report("STARTED");}
        boolean valid(){var g=Minecraft.getInstance();return !closed&&g.level==carrier&&g.getConnection()==connection&&g.player!=null&&(g.player.isSpectator()||(g.gameMode!=null&&g.gameMode.getPlayerMode()==net.minecraft.world.level.GameType.ADVENTURE));}
        void invalidate(){synchronized(fence){visible=false;guard.requestInvalidation();if(far!=null)far.requestInvalidation();fenceCollision();}}
        void metadata(long id,RollingCubeActor.StageMetadata m){
            if(closed||guard.invalidationRequested())return;if(pending||id<=publication)throw new IllegalArgumentException("Overlapping/replayed rolling publication");
            capture="";drawn=false;replica.acceptMetadata(m);publication=id;pending=true;edit=false;preparedSent=allowed=pairSent=false;digest="";status="Staging adjacent native window; old collision retained";report("STAGING_"+id);
        }
        void snapshot(long generation,EngineCubeSnapshot snapshot){if(closed||guard.invalidationRequested())return;replica.acceptSnapshot(generation,snapshot);acceptedSnapshots++;peakResident=Math.max(peakResident,replica.cubes().size());}
        void ready(long id,RollingCubeActor.StageReady ready){
            if(!pending||id!=publication||!digest.isEmpty())throw new IllegalArgumentException("Unexpected rolling readiness");replica.acceptReady(ready);digest=replica.candidateManifestDigest();
            if(!candidate.isEmpty()||!meshWork.isDrained())throw new IllegalStateException("Previous candidate owns resources");
            var jobs=new ArrayList<CubePos>();
            for(var pos:replica.candidateTargets()){var old=published.get(pos);if(old!=null&&old.isCurrent())candidate.put(pos,old);else jobs.add(pos);}
            long expectedPublication=publication,expectedResource=guard.resourceGeneration();String expectedDigest=digest;
            meshWork.start(List.copyOf(jobs),expectedPublication,expectedResource,()->valid()&&pending&&!guard.invalidationRequested()&&publication==expectedPublication&&guard.resourceGeneration()==expectedResource&&digest.equals(expectedDigest));
            status="Slicing private native candidate; committed pair retained";
        }
        void beginEdit(long id,long x,long y,long z){
            if(pending||id<=publication||!RollingCubePlan.isEditableInterior(profile.target(),x,y,z))throw new IllegalArgumentException("Unexpected rolling edit barrier");
            capture="";drawn=false;publication=id;pending=true;edit=true;preparedSent=allowed=pairSent=false;digest="";intentPending=false;lastEditX=x;lastEditY=y;lastEditZ=z;
            replica.beginEdit();visible=false;fenceCollision();status="Durable interior edit pending; pair fenced";report("EDIT_PENDING_"+id);
        }
        void ack(long id,String result,long revision,String detail){if(!pending||!edit||id!=publication)throw new IllegalArgumentException("Stale rolling edit acknowledgement");replica.acknowledge(result,revision);status="Durable edit "+result+" revision "+revision+"; exact manifest pending";report("EDIT_ACK_"+publication);}
        void allow(long id,long resource,String exact){checkPair(id,resource,exact);if(!preparedSent||allowed||pairSent)throw new IllegalStateException("Unexpected pair allowance");allowed=true;fenceCollision();status="Server fenced; installing exact near/far pair";}
        void checkPair(long id,long resource,String exact){if(!pending||id!=publication||resource!=guard.resourceGeneration()||!digest.equals(exact)||digest.isEmpty()||guard.invalidationRequested())throw new IllegalArgumentException("Stale rolling pair acknowledgement");}
        void committed(long id,long resource,String exact){
            checkPair(id,resource,exact);if(!pairSent)throw new IllegalStateException("Commit precedes paired rendering");
            if(!edit)replica.commitStage(replica.stagedMetadata().generation());
            Map<CubePos,EngineCubeSnapshot> currentSnapshots=new LinkedHashMap<>();for(var p:replica.currentPositions125())currentSnapshots.put(p,Objects.requireNonNull(replica.cubes().get(p)).snapshot());
            collision.publish(world,epoch,palette,Map.copyOf(currentSnapshots));drawn=false;pending=false;edit=false;allowed=false;intentPending=false;status="READY: rolling collision125 + exact near/DH | revision "+replica.revision();capture="READY_"+publication;report(capture);
        }
        void send(RollingGateControlPayload p){var g=Minecraft.getInstance();g.execute(()->{if(g.getConnection()==connection)PacketDistributor.sendToServer(p);});}
        void fenceCollision(){var binding=collision;if(binding==null)return;var g=Minecraft.getInstance();g.execute(()->{binding.fence();if(current==this&&g.level==carrier&&g.player!=null){g.player.setOnGround(false);g.player.resetFallDistance();g.player.setDeltaMovement(Vec3.ZERO);}if(closed||guard.invalidationRequested())send(new RollingGateControlPayload(epoch,publication,guard.resourceGeneration(),RollingGateControlPayload.FENCED,"",false));reportFence(binding);});}
        void reportFence(CoreCollisionBinding binding){var j=new JsonObject();j.addProperty("stage","COLLISION_FENCED");j.addProperty("epoch",epoch);j.addProperty("publication",publication);j.addProperty("closed",closed);j.addProperty("disposed",disposed);j.addProperty("collisionOwners",binding.residentOwners());j.addProperty("collisionReady",binding.ready());write("collision-fenced-"+publication+"-"+(closed?"closed":"active"),j);EarthPlatformMod.LOGGER.info("ROLLING_CLIENT_COLLISION_FENCED epoch={} publication={} closed={} owners={}",epoch,publication,closed,binding.residentOwners());}
        void render(RenderLevelStageEvent e){synchronized(fence){
            if(!valid()||guard.invalidationRequested())return;camera=e.getCamera().getPosition();long start=System.nanoTime(),heightBefore=sourceHeightCallbacks,colorBefore=sourceColorCallbacks;
            try{
                if(pending&&!digest.isEmpty()&&!preparedSent)prepareCandidateWork();
                if(allowed&&!pairSent){long pairStart=System.nanoTime();try{installPair();}finally{pairInstallation.record(pairStart);}}
            }finally{long elapsed=updateWork.record(start);maxUpdateNanos=Math.max(maxUpdateNanos,elapsed);maxSourceHeightCallbacksPerUpdate=Math.max(maxSourceHeightCallbacksPerUpdate,sourceHeightCallbacks-heightBefore);maxSourceColorCallbacksPerUpdate=Math.max(maxSourceColorCallbacksPerUpdate,sourceColorCallbacks-colorBefore);}
            if(guard.invalidationRequested()||!visible)return;
            for(var m:published.values())if(!m.isCurrent())throw new IllegalStateException("Published native dependency stale");
            if(!far.coverageReady(coverageGeneration))throw new IllegalStateException("Far coverage does not match visible native batch");
            var projection=SceneSession.wideProjection(e.getProjectionMatrix());var view=new Matrix4f(e.getModelViewMatrix());RenderSystem.clear(256,Minecraft.ON_OSX);
            far.draw(new Matrix4f(projection).mul(view),camera.x,camera.y,camera.z,guard.resourceGeneration());
            if(guard.invalidationRequested())return;for(var m:published.values())if(!m.draw(view,projection,camera.x,camera.y,camera.z,light.getId())){if(guard.invalidationRequested())return;throw new IllegalStateException("Native paired draw rejected");}drawn=true;
        }}
        void prepareCandidateWork(){
            // Exactly one begin, callback slice, or finish/upload phase per AFTER_LEVEL frame.
            lastWorkPhase=meshWork.pump();
            meshWork.publishCompleted((pos,mesh)->{
                // Register ownership before either map insertion or a later fence may fail.
                owned.add(mesh);candidate.put(pos,mesh);peakMeshes=Math.max(peakMeshes,owned.size());
                if(owned.size()>RollingCubePlan.MAX_STAGED_MESH_GENERATIONS)throw new IllegalStateException("Native mesh generation cap exceeded");
            });
            if(meshWork.isDrained()){
                if(candidate.size()!=replica.candidateTargets().size())throw new IllegalStateException("Candidate queue drained before all targets were published");
                for(var m:candidate.values())if(!m.isCurrent())throw new IllegalStateException("Private native candidate stale");
                preparedSent=true;send(new RollingGateControlPayload(epoch,publication,guard.resourceGeneration(),RollingGateControlPayload.PREPARED,digest,false));status="Candidate prepared; waiting for server movement fence";
            }else status="Slicing native candidate: queued "+meshWork.queuedTargets()+", active "+meshWork.activeBuilds()+"; committed collision retained";
        }
        void installPair(){
            if(candidate.size()!=replica.candidateTargets().size())throw new IllegalStateException("Incomplete native candidate");
            for(var m:candidate.values())if(!m.isCurrent())throw new IllegalStateException("Candidate lost exact revision");
            if(!candidate.containsKey(profile.target()))throw new IllegalStateException("Edited central volume lost pinned native coverage");
            if(!meshWork.isDrained())throw new IllegalStateException("Native work remains before pair installation");
            if(far==null){long start=System.nanoTime();try{var sampler=new SceneSurfaceSampler(){public long surfaceY(long x,long z){sourceHeightCallbacks=Math.incrementExact(sourceHeightCallbacks);return profile.anchorY();}public int argb(long x,long z,long y){sourceColorCallbacks=Math.incrementExact(sourceColorCallbacks);return 0xFF808080;}public String identity(){return world.datasetVersion();}};far=new DhGeographicLodView(sampler,world,epoch,guard.resourceGeneration(),0,0);far.initialize();}finally{farInitialization.record(start);}}
            var ready=candidate.values().stream().filter(m->profile.distantMaskEligible(m.snapshot().position())).map(m->{var s=m.snapshot();return new SceneReadyCube(s.position(),world,epoch,s.leaseGeneration(),s.revision(),guard.resourceGeneration());}).toList();
            long next=Math.incrementExact(coverageGeneration),coverageStart=System.nanoTime();try{if(!far.replaceCoverage(next,ready))throw new IllegalStateException("Far refused bounded exact rolling coverage");coverageGeneration=next;}finally{farCoverage.record(coverageStart);}
            visible=false;
            Set<SceneMesh> keep=Collections.newSetFromMap(new IdentityHashMap<>());keep.addAll(candidate.values());closeExcept(keep);
            published.clear();published.putAll(candidate);candidate.clear();visible=true;pairSent=true;
            send(new RollingGateControlPayload(epoch,publication,guard.resourceGeneration(),RollingGateControlPayload.PAIR_READY,digest,false));status="Exact pair installed; waiting for authoritative collision commit";report("PAIR_READY_"+publication);
        }
        void closeExcept(Set<SceneMesh> keep){Throwable failure=null;for(var it=owned.iterator();it.hasNext();){var m=it.next();if(keep.contains(m))continue;try{m.close();it.remove();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}}if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;}
        Path directory(){return Path.of("earthplatform-evidence","rolling-core-gate");}
        void write(String suffix,JsonObject j){try{Files.createDirectories(directory());Files.writeString(directory().resolve("epoch-"+epoch+"-"+suffix.toLowerCase(Locale.ROOT)+".json"),j+"\n");}catch(IOException failure){EarthPlatformMod.LOGGER.warn("Rolling evidence write failed",failure);}}
        void report(String stage){
            if(reports++>=256&&!stage.equals("CLOSED"))return;var j=new JsonObject();j.addProperty("stage",stage);j.addProperty("epoch",epoch);j.addProperty("profile",profile.id());j.addProperty("publication",publication);j.addProperty("coverageGeneration",coverageGeneration);j.addProperty("resourceGeneration",guard.resourceGeneration());j.addProperty("world",world.toString());j.addProperty("residentCubes",disposed?0:replica.cubes().size());j.addProperty("peakResidentCubes",peakResident);j.addProperty("publishedNativeMeshes",published.size());j.addProperty("candidateNativeMeshes",candidate.size());j.addProperty("ownedNativeMeshes",owned.size());j.addProperty("peakOwnedNativeMeshes",peakMeshes);j.addProperty("nativeVertexBytes",owned.stream().mapToLong(m->(long)m.vertices()*32).sum());j.addProperty("nativeVertexByteCap",(long)RollingCubePlan.MAX_STAGED_MESH_GENERATIONS*SceneMesh.MAX_VERTEX_BYTES);j.addProperty("nativeSequentialIndexUpperBoundBytes",owned.stream().mapToLong(m->(long)m.vertices()/4*6*4).sum());j.addProperty("maxConcurrentCpuMeshBuilders",1);j.addProperty("ownedCpuMeshBuilders",meshWork.activeBuilds());j.addProperty("retainedCompletedMeshSlots",meshWork.completedSlots());j.addProperty("queuedNativeTargets",meshWork.queuedTargets());j.addProperty("lastNativeWorkPhase",lastWorkPhase.name());j.addProperty("closingQueuedNativeTargets",closingQueuedTargets);j.addProperty("closingActiveCpuBuilds",closingActiveBuilds);j.addProperty("closingRetainedMeshSlots",closingCompletedSlots);j.addProperty("closingPublicationPending",closingPublicationPending);j.addProperty("closingPreparedSent",closingPreparedSent);j.addProperty("peakNativeGenerationReservations",peakMeshReservations);j.addProperty("maxCpuBuilderLayerCapacityBytes",3L*SceneMesh.MAX_VERTEX_BYTES);j.addProperty("acceptedSnapshots",acceptedSnapshots);j.addProperty("targetRevision",disposed?-1:replica.revision());j.addProperty("publicationPending",pending);j.addProperty("pairedDrawVisible",visible);j.addProperty("collisionOwners",collision==null?0:collision.residentOwners());j.addProperty("collisionReady",collision!=null&&collision.ready());j.addProperty("closed",closed);j.addProperty("disposed",disposed);j.addProperty("maxCpuUpdateNanos",maxUpdateNanos);j.addProperty("nativeCameraX",camera.x);j.addProperty("nativeCameraY",camera.y);j.addProperty("nativeCameraZ",camera.z);j.addProperty("lastEditX",lastEditX);j.addProperty("lastEditY",lastEditY);j.addProperty("lastEditZ",lastEditZ);j.addProperty("editedCentralMeshPinned",true);j.addProperty("immutableOneBlockEditShell",true);j.addProperty("hardwareGpuPerformanceClaim",false);if(!disposed&&replica.currentCenter()!=null)j.addProperty("currentCenter",replica.currentCenter().toString());j.addProperty("farSourceHeightCallbacks",sourceHeightCallbacks);j.addProperty("farSourceColorCallbacks",sourceColorCallbacks);j.addProperty("maxFarSourceHeightCallbacksPerUpdate",maxSourceHeightCallbacksPerUpdate);j.addProperty("maxFarSourceColorCallbacksPerUpdate",maxSourceColorCallbacksPerUpdate);j.addProperty("farSourceCounterScope","Actual flat-fixture sampler calls, including initialize and coverage replacement; not distinct cells or DEM I/O");j.add("nativeWork",new com.google.gson.Gson().toJsonTree(meshWork.metrics()));j.addProperty("nativeCallbackBudgetNanos",RollingMeshWorkQueue.SOFT_SLICE_NANOS);j.addProperty("nativeCallbackHardLimit",RollingMeshWorkQueue.MAX_CALLBACKS);j.addProperty("nativeWorkSlicedOnOwnerThread",true);j.addProperty("timingClock","System.nanoTime owner-thread elapsed time; not CPU-time or GPU-duration measurement");j.addProperty("nativeFinishUploadSliced",false);j.addProperty("dhCoverageReplacementSliced",false);j.add("farInitializationTiming",farInitialization.json());j.add("farCoverageReplacementTiming",farCoverage.json());j.add("pairInstallationTiming",pairInstallation.json());j.add("updateWorkTiming",updateWork.json());if(far!=null)j.add("distantHorizons",far.diagnostics());write(stage,j);EarthPlatformMod.LOGGER.info("ROLLING_{} epoch={} publication={} residents={} meshes={} collision={}",stage,epoch,publication,disposed?0:replica.cubes().size(),owned.size(),collision==null?0:collision.residentOwners());
        }
        void afterHud(){if(!capture.isEmpty()&&(pending||!capture.equals("READY_"+publication))){capture="";return;}if(capture.isEmpty()||!drawn||screenshots>=12)return;String stage=capture;capture="";try(var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){Files.createDirectories(directory());image.writeToFile(directory().resolve("epoch-"+epoch+"-"+stage.toLowerCase(Locale.ROOT)+".png"));screenshots++;}catch(IOException failure){EarthPlatformMod.LOGGER.warn("Rolling screenshot failed",failure);}}
        public void close(){synchronized(fence){
            if(disposed)return;if(!closed){closingQueuedTargets=meshWork.queuedTargets();closingActiveBuilds=meshWork.activeBuilds();closingCompletedSlots=meshWork.completedSlots();closingPublicationPending=pending;closingPreparedSent=preparedSent;}closed=true;visible=false;guard.close();fenceCollision();Throwable failure=null;
            try{meshWork.close();}catch(RuntimeException|Error e){failure=e;}
            try{closeExcept(Set.of());}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            try{if(far!=null)far.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            try{if(light!=null){light.close();light=null;}}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;
            published.clear();candidate.clear();replica.close();disposed=true;report("CLOSED");
        }}
        private static final class WorkTiming {
            long calls,totalNanos,maxNanos;
            long record(long start){long elapsed=Math.max(0,System.nanoTime()-start);calls=calls==Long.MAX_VALUE?calls:calls+1;totalNanos=Long.MAX_VALUE-totalNanos<elapsed?Long.MAX_VALUE:totalNanos+elapsed;maxNanos=Math.max(maxNanos,elapsed);return elapsed;}
            JsonObject json(){var j=new JsonObject();j.addProperty("calls",calls);j.addProperty("totalNanos",totalNanos);j.addProperty("maxNanos",maxNanos);return j;}
        }

    }
}
