package io.earthplatform.minecraft.scene;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.lod.DhGeographicLodView;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.residency.SparseFacadeException;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Owns a visual-only sparse view without handing a full-height level to stock Minecraft rendering. */
public final class SceneSession implements AutoCloseable {
    private final Object frameFence=new Object();
    private final ClientLevel carrier;
    private final ClientPacketListener connection;
    private final SceneTerrain terrain;
    private final EnginePaletteContext palette;
    private final WorldIdentity world;
    private final CubeStreamAdmission admission;
    private final RenderPublicationGuard guard;
    private final Map<CubePos,SceneCube> cubes=new LinkedHashMap<>();
    private final Map<CubePos,SceneMesh> meshes=new LinkedHashMap<>();
    private final ArrayDeque<CubeStreamAdmission.Lease> loads=new ArrayDeque<>();
    private final ArrayDeque<CubePos> builds=new ArrayDeque<>();
    private DynamicTexture light;
    private DhGeographicLodView far;
    private SceneInterestPlan plan;
    private long frames,plans,coverageGeneration,completedBatches,retiredOwners,generatedCubes;
    private long maxUpdateNanos,maxDrawNanos,lastUpdateNanos,lastDrawNanos;
    private int attemptedX=Integer.MIN_VALUE,attemptedZ=Integer.MIN_VALUE,attemptedY=Integer.MIN_VALUE,maxResident;
    private int savedScreenshots;
    private long screenshotAfterFrame=-1;
    private String screenshotReason="",lastScreenshot="";
    private boolean nearVisible,closed,disposed,deferred,farRecorded;
    private String status="Loading source-linked far terrain";
    private Vec3 camera=Vec3.ZERO;
    public SceneSession(SceneStartPayload start) {
        RenderSystem.assertOnRenderThread();var game=Minecraft.getInstance();
        carrier=Objects.requireNonNull(game.level);connection=Objects.requireNonNull(game.getConnection());
        if(!carrier.dimension().equals(SceneCommands.CARRIER)||game.getSingleplayerServer()==null)throw new IllegalStateException("Integrated empty carrier required");
        terrain=new SceneTerrain();if(!terrain.identity().equals(start.sourceIdentity()))throw new IllegalStateException("Server/client source identity mismatch");
        palette=EnginePaletteContext.create(carrier.registryAccess().registryOrThrow(Registries.BIOME));world=terrain.world(palette);
        admission=new CubeStreamAdmission(world,start.epoch(),SceneInterestPlan.MAX_OWNERS);guard=new RenderPublicationGuard(admission,SceneInterestPlan.MAX_OWNERS);
    }
    /** Called only after the client controller retains this owner, including failed initialization. */
    public void initialize() {
        RenderSystem.assertOnRenderThread();if(closed||light!=null)throw new IllegalStateException("Scene initialization state");
        light=new DynamicTexture(16,16,false);
        var pixels=Objects.requireNonNull(light.getPixels());for(int y=0;y<16;y++)for(int x=0;x<16;x++)pixels.setPixelRGBA(x,y,-1);light.upload();
        camera=Objects.requireNonNull(Minecraft.getInstance().player).getEyePosition();
        far=new DhGeographicLodView(terrain,world,epoch(),guard.resourceGeneration(),(long)Math.floor(camera.x),(long)Math.floor(camera.z));
        far.initialize();
        writeReport("STARTED");
    }
    public boolean stillValid() {
        var game=Minecraft.getInstance();return !closed&&game.level==carrier&&game.getConnection()==connection
                &&carrier.dimension().equals(SceneCommands.CARRIER)&&game.player!=null&&game.player.isSpectator();
    }
    public void requestInvalidation(){synchronized(frameFence){guard.requestInvalidation();var distant=far;if(distant!=null)distant.requestInvalidation();}}
    public boolean invalidated(){return guard.invalidationRequested();}
    public long epoch(){return admission.connectionEpoch();}
    public String status(){return status;}
    public int resident(){return cubes.size();}
    public int nearCount(){return nearVisible?meshes.size():0;}
    public int targetCount(){return plan==null?0:plan.renderTargets().size();}
    public boolean nearVisible(){return nearVisible;}
    public Vec3 camera(){return camera;}
    public boolean naturalRelief(){return terrain.naturalRelief();}
    public void render(RenderLevelStageEvent event) {
        synchronized(frameFence){renderFrame(event);}
    }
    /** Reload is linearized against the complete paired far/near frame, not individual draw calls. */
    private void renderFrame(RenderLevelStageEvent event) {
        RenderSystem.assertOnRenderThread();if(!stillValid()||invalidated())return;
        frames++;camera=event.getCamera().getPosition();
        long started=System.nanoTime();updateWork();lastUpdateNanos=System.nanoTime()-started;maxUpdateNanos=Math.max(maxUpdateNanos,lastUpdateNanos);
        if(invalidated())return;
        ensureVisibleCoverage();
        Matrix4f projection=wideProjection(event.getProjectionMatrix());
        Matrix4f view=new Matrix4f(event.getModelViewMatrix());
        // Only this empty visual carrier uses the alternate projection/depth domain.
        RenderSystem.clear(256,Minecraft.ON_OSX);
        started=System.nanoTime();
        far.draw(new Matrix4f(projection).mul(view),camera.x,camera.y,camera.z,guard.resourceGeneration());
        if(nearVisible&&far.coverageReady(coverageGeneration)) {
            for(var mesh:meshes.values()) {
                if(!mesh.draw(view,projection,camera.x,camera.y,camera.z,light.getId())) {
                    if(invalidated())return;
                    throw new IllegalStateException("A visible near batch lost a dependency without a far unmask transaction");
                }
            }
        }
        lastDrawNanos=System.nanoTime()-started;maxDrawNanos=Math.max(maxDrawNanos,lastDrawNanos);
        if(!farRecorded&&frames>=2){farRecorded=true;screenshotReason="FAR_VISIBLE";screenshotAfterFrame=frames;}
    }
    private void ensureVisibleCoverage() {
        if(!nearVisible)return;
        boolean invalid=false;
        for(var mesh:meshes.values()) {
            var p=mesh.snapshot().position();
            if(!mesh.isCurrent()||Math.abs(p.minBlockX()-camera.x)>512||Math.abs(p.minBlockY()-camera.y)>512||Math.abs(p.minBlockZ()-camera.z)>512){invalid=true;break;}
        }
        if(!invalid)return;
        long next=Math.incrementExact(coverageGeneration);
        if(!far.replaceCoverage(next,List.of()))throw new IllegalStateException("Cannot safely unmask a stale or out-of-view native batch");
        coverageGeneration=next;nearVisible=false;
        for(var mesh:meshes.values())mesh.close();meshes.clear();deferred=true;
        status="Native batch deferred; known far terrain remains unmasked";
    }
    /** Preserve the engine's FOV/aspect/zoom while using one depth range for both custom near and far. */
    public static Matrix4f wideProjection(Matrix4f original) {
        if(Math.abs(original.m23()+1)>0.0001f||Math.abs(original.m33())>0.0001f)throw new IllegalArgumentException("Expected the native perspective camera projection");
        float near=0.05f,far=2048f;
        return new Matrix4f(original).m22(-(far+near)/(far-near)).m32(-(2*far*near)/(far-near));
    }
    private void updateWork() {
        int cx=CubePos.section((long)Math.floor(camera.x)),cy=CubePos.section((long)Math.floor(camera.y)),cz=CubePos.section((long)Math.floor(camera.z));
        if(cx!=attemptedX||cy!=attemptedY||cz!=attemptedZ) {
            attemptedX=cx;attemptedY=cy;attemptedZ=cz;
            SceneInterestPlan next=null;
            try{next=SceneInterestPlan.around(terrain,camera.x,camera.y,camera.z);}
            catch(MissingTerrainException|IllegalArgumentException missing){status="Near deferred: "+missing.getMessage();deferred=true;}
            // Driver/commit/cleanup exceptions are never ordinary source-budget deferrals.
            if(next!=null)replacePlan(next);
        }
        if(plan==null||deferred)return;
        long until=System.nanoTime()+4_000_000L;
        int loaded=0;
        while(!loads.isEmpty()&&loaded<2&&(loaded==0||System.nanoTime()<until)) {
            var lease=loads.removeFirst();
            try {
                var snapshot=terrain.snapshot(lease.position(),palette,world,admission.connectionEpoch(),lease.generation());
                var token=admission.admit(new CubeStreamAdmission.Header(world,admission.connectionEpoch(),lease.position(),lease.generation(),snapshot.revision()));
                if(!token.accepted())throw new IllegalStateException("Generated native view snapshot rejected: "+token.rejection());
                var cube=new SceneCube(snapshot,palette);
                if(admission.commit(token.token(),()->cubes.put(lease.position(),cube))!=CubeStreamAdmission.Rejection.NONE)
                    throw new IllegalStateException("Native view owner publication became stale");
                generatedCubes++;loaded++;maxResident=Math.max(maxResident,cubes.size());
            } catch(MissingTerrainException missing) {deferred=true;status="Unknown source halo: "+missing.getMessage();writeReport("NEAR_DEFERRED");return;}
        }
        if(!loads.isEmpty()){status="Loading native source/halo: "+cubes.size()+"/"+plan.owners().size();return;}
        if(!builds.isEmpty()) {
            var position=builds.removeFirst();
            try {
                var mesh=SceneMesh.build(guard,Objects.requireNonNull(cubes.get(position)),new SceneNeighborhood(cubes));
                meshes.put(position,mesh);status="Compiling native models: "+meshes.size()+"/"+plan.renderTargets().size();
            }catch(SparseFacadeException unknown){deferred=true;status="Native mesh deferred: "+unknown.getMessage();writeReport("NEAR_DEFERRED");return;}
            // One native mesh compilation per frame; far terrain remains fully visible while the batch is private.
        }
        if(builds.isEmpty()&&!nearVisible) {
            var coverage=new ArrayList<SceneReadyCube>();
            for(var mesh:meshes.values()) {
                if(!mesh.isCurrent())throw new IllegalStateException("Private native candidate lost its halo before batch commit");
                var snapshot=mesh.snapshot();coverage.add(new SceneReadyCube(snapshot.position(),world,snapshot.epoch(),snapshot.leaseGeneration(),snapshot.revision(),guard.resourceGeneration()));
            }
            long next=Math.incrementExact(coverageGeneration);
            if(far.replaceCoverage(next,coverage)) {
                coverageGeneration=next;nearVisible=true;completedBatches++;status=meshes.isEmpty()?"Far-only: camera outside 128m near radius":"Native near + page-aware DH ready";
                writeReport("BATCH_READY");if(savedScreenshots<6){screenshotReason="NEAR_FAR_READY";screenshotAfterFrame=frames+2;}
            } else {deferred=true;status="Far clipping budget deferred the new native batch";}
        }
    }
    private void replacePlan(SceneInterestPlan next) {
        if(nearVisible) {
            long generation=Math.incrementExact(coverageGeneration);
            if(!far.replaceCoverage(generation,List.of()))throw new IllegalArgumentException("Far unmask transaction unavailable");
            coverageGeneration=generation;nearVisible=false;
        }
        for(var mesh:meshes.values())mesh.close();meshes.clear();builds.clear();loads.clear();
        var change=admission.replaceInterest(next.owners());
        for(var retired:change.retired()){cubes.remove(retired.lease().position());retiredOwners++;}
        loads.addAll(SceneLoadQueue.rebuild(next.owners(),admission.view(),cubes.keySet()));builds.addAll(new TreeSet<>(next.renderTargets()));
        plan=next;plans++;deferred=false;
        if(cubes.size()>SceneInterestPlan.MAX_OWNERS)throw new IllegalStateException("Native cube budget escaped");
    }
    public void afterHud() {
        if(closed||invalidated()||screenshotAfterFrame<0||frames<screenshotAfterFrame||savedScreenshots>=6)return;
        screenshotAfterFrame=-1;Path directory=evidenceDirectory();
        String filename="epoch-"+epoch()+"-plan-"+plans+"-"+screenshotReason.toLowerCase(Locale.ROOT)+".png";
        try(var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            Files.createDirectories(directory);image.writeToFile(directory.resolve(filename));lastScreenshot=filename;savedScreenshots++;
        }catch(IOException failure){throw new UncheckedIOException(failure);}
        writeReport(screenshotReason);
        EarthPlatformMod.LOGGER.info("SPARSE_TERRAIN_VIEW_{} epoch={} nativeCamera={} nearOwners={} nativeMeshes={} actualDh=true",screenshotReason,epoch(),camera,cubes.size(),nearCount());
    }
    private Path evidenceDirectory(){return Path.of("earthplatform-evidence","sparse-terrain-view");}
    private void writeReport(String stage) {
        JsonObject out=new JsonObject();out.addProperty("stage",stage);out.addProperty("epoch",epoch());out.addProperty("scope","Native-coordinate spectator terrain view in an empty stock-height carrier; source-linked cubic native models + actual DH shaders; visual only, no cube collision/edit/save");
        out.addProperty("sourceAppearanceIdentity",terrain.identity());out.addProperty("sourceUrl",terrain.sourceUrl());out.addProperty("verticalDatum",terrain.datum());out.addProperty("naturalReliefEnabled",terrain.naturalRelief());
        out.addProperty("horizontalProjection","global_equirectangular_v1; one projected metre per block, not a uniform global ground-metre guarantee");
        out.addProperty("nativeCameraX",camera.x);out.addProperty("nativeCameraY",camera.y);out.addProperty("nativeCameraZ",camera.z);
        out.addProperty("carrierMinY",carrier.getMinBuildHeight());out.addProperty("carrierHeight",carrier.getHeight());out.addProperty("fullHeightLevelInstalledInVanillaRenderer",false);
        out.addProperty("residentCubes",cubes.size());out.addProperty("residentLimit",256);out.addProperty("maxResidentCubes",maxResident);out.addProperty("meshCandidates",meshes.size());out.addProperty("nearDrawReady",nearCount());out.addProperty("nearBatchVisible",nearVisible);
        out.addProperty("nativeVertexBytes",meshes.values().stream().mapToLong(SceneMesh::bytes).sum());out.addProperty("nativeVertices",meshes.values().stream().mapToLong(SceneMesh::vertices).sum());
        out.addProperty("pendingOwnerLoads",loads.size());out.addProperty("activeAdmissionLeases",admission.view().metrics().activeLeases());out.addProperty("loadedAdmissionLeases",admission.view().metrics().loadedLeases());out.addProperty("pendingMeshBuilds",builds.size());out.addProperty("plans",plans);out.addProperty("generatedCubes",generatedCubes);out.addProperty("retiredOwners",retiredOwners);out.addProperty("completedNearBatches",completedBatches);
        out.addProperty("coverageGeneration",coverageGeneration);out.addProperty("resourceGeneration",guard.resourceGeneration());out.addProperty("frames",frames);out.addProperty("lastCpuUpdateNanos",lastUpdateNanos);out.addProperty("maxCpuUpdateNanos",maxUpdateNanos);out.addProperty("lastCpuDrawNanos",lastDrawNanos);out.addProperty("maxCpuDrawNanos",maxDrawNanos);
        out.addProperty("hardwareGpuPerformanceClaim",false);out.addProperty("closed",closed);out.addProperty("disposed",disposed);out.addProperty("status",status);out.addProperty("screenshot",lastScreenshot);if(far!=null)out.add("distantHorizons",far.diagnostics());
        try {Files.createDirectories(evidenceDirectory());Files.writeString(evidenceDirectory().resolve("epoch-"+epoch()+"-"+stage.toLowerCase(Locale.ROOT)+".json"),out+"\n");}
        catch(IOException failure){throw new UncheckedIOException(failure);}
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();if(disposed)return;closed=true;guard.close();nearVisible=false;Throwable failure=null;
        for(var iterator=meshes.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();try{entry.getValue().close();iterator.remove();}
            catch(RuntimeException|Error error){failure=append(failure,error);}
        }
        if(far!=null)failure=cleanup(failure,far::close);
        if(light!=null)failure=cleanup(failure,light::close);
        failure=cleanup(failure,admission::close);cubes.clear();loads.clear();builds.clear();
        status=failure==null?"Closed: all session owners retired":"Cleanup pending: retained failed render owners";
        if(failure instanceof RuntimeException error)throw error;if(failure instanceof Error error)throw error;
        disposed=true;writeReport("CLOSED");EarthPlatformMod.LOGGER.info("SPARSE_TERRAIN_VIEW_CLOSED epoch={} nativeOwners=0 meshes=0",epoch());
    }
    private static Throwable append(Throwable first,Throwable error){if(first==null)return error;first.addSuppressed(error);return first;}
    private static Throwable cleanup(Throwable first,Runnable close){try{close.run();}catch(RuntimeException|Error error){if(first==null)return error;first.addSuppressed(error);}return first;}
}
