package io.earthplatform.minecraft.renderprobe;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.fixture.*;
import io.earthplatform.minecraft.network.*;
import io.earthplatform.minecraft.lod.DhNativeLodBridge;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Owns the transferred native client fixture/admission and all diagnostic GPU resources. */
public final class NativeRenderSession implements AutoCloseable {
    public static final String OPT_IN="earthplatform.experimental.nativeSparseRender";
    public static final String COEXISTENCE_OPT_IN="earthplatform.experimental.dhNativeCoexistence";
    private static volatile NativeRenderSession current;
    final NativeSparseBinding binding;
    final CubeStreamAdmission admission;
    final RenderPublicationGuard guard;
    final Map<CubePos,NativeCubeMesh> meshes=new LinkedHashMap<>();
    final Map<CubePos,EngineCubeSnapshot> snapshots=new LinkedHashMap<>();
    final DynamicTexture whiteLight;
    DhNativeLodBridge lod;
    private final ClientLevel level;
    private final net.minecraft.client.multiplayer.ClientPacketListener connection;
    private final int legacySectionSlots,legacyPostprocessingSlots;
    private boolean closed;
    private boolean resourcePackReloadRequested;
    private long draws,retirements;
    private long nearSwaps;
    private String pendingStatus="DRAW_PASS";
    private int framesUntilCapture=2;
    private long goldPixels,diamondPixels;
    private String screenshot="";
    NativeRenderSession(ClientLevel level,NativeSparseBinding binding,CubeStreamAdmission admission,EngineCubeSnapshot lower,EngineCubeSnapshot upper) {
        RenderSystem.assertOnRenderThread();this.level=level;this.binding=binding;this.admission=admission;guard=new RenderPublicationGuard(admission);
        connection=Objects.requireNonNull(Minecraft.getInstance().getConnection());
        var diagnostics=(io.earthplatform.minecraft.residency.SparseFacadeDiagnostics)binding.facade(0,0,net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
        legacySectionSlots=diagnostics.earthplatform$legacySectionSlots();legacyPostprocessingSlots=diagnostics.earthplatform$legacyPostprocessingSlots();
        NativeProbeChecks.require(legacySectionSlots==0&&legacyPostprocessingSlots==0,"rendering must not allocate tall column arrays");
        whiteLight=new DynamicTexture(16,16,false);
        try {
            var pixels=Objects.requireNonNull(whiteLight.getPixels());for(int y=0;y<16;y++)for(int x=0;x<16;x++)pixels.setPixelRGBA(x,y,-1);whiteLight.upload();
            snapshots.put(lower.position(),lower);snapshots.put(upper.position(),upper);
            if(Boolean.getBoolean(COEXISTENCE_OPT_IN)) {
                // Actual native readiness, not data admission, determines the near mask.
                meshes.put(lower.position(),NativeCubeMesh.build(guard,lower,binding.palette()));
                lod=new DhNativeLodBridge(admission,binding.palette(),List.copyOf(snapshots.values()));
            } else for(var snapshot:snapshots.values())meshes.put(snapshot.position(),NativeCubeMesh.build(guard,snapshot,binding.palette()));
        } catch(RuntimeException|Error failure) {
            for(var mesh:meshes.values())cleanup(failure,mesh::close);
            if(lod!=null)cleanup(failure,lod::close);
            cleanup(failure,whiteLight::close);cleanup(failure,guard::close);throw failure;
        }
    }
    public static NativeRenderSession takeOwnership(ClientLevel level,NativeSparseBinding binding,CubeStreamAdmission admission,EngineCubeSnapshot lower,EngineCubeSnapshot upper) {
        closeCurrent();return new NativeRenderSession(level,binding,admission,lower,upper);
    }
    public void open() {
        RenderSystem.assertOnRenderThread();current=this;
        try{Minecraft.getInstance().setScreen(new NativeRenderScreen(this));}
        catch(RuntimeException|Error failure){close();throw failure;}
    }
    public static void requestReloadInvalidation() {
        var session=current;if(session!=null){session.guard.requestInvalidation();if(session.lod!=null)session.lod.requestInvalidation();}
    }
    public static void closeCurrent(){RenderSystem.assertOnRenderThread();var session=current;if(session!=null)session.close();}
    boolean openForDraw(){return !closed && Minecraft.getInstance().getConnection()==connection && admission.view().state()==CubeStreamAdmission.State.OPEN;}
    boolean coexistence(){return lod!=null;}
    List<EngineCubeSnapshot> readyNearCoverage(){return meshes.values().stream().filter(mesh->guard.isCurrent(mesh.job)).map(mesh->mesh.job.snapshot()).toList();}
    String statusLine(){return "Native uploads "+guard.published()+" | stale rejected "+guard.rejected()+" | resources "+guard.resourceGeneration()+" | near swaps "+nearSwaps;}
    void didDraw(int width,int height,float centerY,float size) {
        draws++;
        if(framesUntilCapture>0&&--framesUntilCapture==0) {
            Path directory=Path.of("earthplatform-evidence","native-gpu");
            try(var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                goldPixels=0;diamondPixels=0;
                double sx=(double)image.getWidth()/width,sy=(double)image.getHeight()/height;
                // Read only the mesh centers, excluding colored headings and status text.
                int y0=Math.max(0,(int)((centerY-size*0.55)*sy)),y1=Math.min(image.getHeight(),(int)((centerY+size*0.55)*sy));
                for(int panel=0;panel<2;panel++) {
                    float centerX=width*(panel==0?0.25f:0.75f);
                    int x0=Math.max(0,(int)((centerX-size*0.55)*sx)),x1=Math.min(image.getWidth(),(int)((centerX+size*0.55)*sx));
                    for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++) {
                        int pixel=image.getPixelRGBA(x,y),red=pixel&255,green=(pixel>>>8)&255,blue=(pixel>>>16)&255;
                        if(panel==0&&red>100&&green>80&&blue<red*0.8)goldPixels++;
                        if(panel==1&&green>80&&blue>80&&red<green*0.9)diamondPixels++;
                    }
                }
                Files.createDirectories(directory);screenshot="epoch-"+admission.connectionEpoch()+"-"+pendingStatus.toLowerCase(Locale.ROOT)+".png";
                image.writeToFile(directory.resolve(screenshot));
                NativeProbeChecks.require(goldPixels>20&&diamondPixels>20,"actual framebuffer contains textured gold and diamond in the mesh regions");
            } catch(IOException failure){throw new UncheckedIOException(failure);}
            writeReport(pendingStatus);
            EarthPlatformMod.LOGGER.info("NATIVE_CUBE_GPU_{} epoch={} goldPixels={} diamondPixels={} nativeMeshes={} dhCoexistence={}",pendingStatus,admission.connectionEpoch(),goldPixels,diamondPixels,meshes.size(),coexistence());
        }
    }
    void retireAndReaddLower() {
        RenderSystem.assertOnRenderThread();if(closed)throw new IllegalStateException("Renderer closed");
        var lower=NativeSparseBinding.LOWER;var upper=NativeSparseBinding.UPPER;
        var old=snapshots.get(lower);var delayed=guard.prepare(old);
        admission.replaceInterest(List.of(upper));binding.cubes().retire(lower);var retiredMesh=meshes.remove(lower);if(retiredMesh!=null)retiredMesh.close();
        if(lod!=null)lod.retire(lower);
        NativeProbeChecks.verifyLowerRetired(binding);
        NativeProbeChecks.require(!guard.publish(delayed,()->{throw new AssertionError("Retired GPU job published");}),"retired GPU upload rejected");
        var interest=admission.replaceInterest(List.of(lower,upper));long lease=interest.requested().getFirst().generation();
        var replacement=EngineCubeSnapshot.capture(lower,old.world(),old.epoch(),lease,old.revision(),old.materialize(binding.palette()));
        var token=admission.admit(new CubeStreamAdmission.Header(replacement.world(),replacement.epoch(),lower,lease,replacement.revision()));
        NativeProbeChecks.require(token.accepted(),"replacement render owner admitted");
        NativeProbeChecks.require(admission.commit(token.token(),()->binding.cubes().installAdmittedSnapshot(replacement))==CubeStreamAdmission.Rejection.NONE,"replacement native owner installed");
        NativeProbeChecks.require(!guard.publish(delayed,()->{throw new AssertionError("ABA GPU job published");}),"old generation GPU upload rejected");
        snapshots.put(lower,replacement);
        if(retiredMesh!=null)meshes.put(lower,NativeCubeMesh.build(guard,replacement,binding.palette()));
        if(lod!=null)lod.replaceSnapshot(replacement);
        retirements++;
        NativeProbeChecks.observe(binding,admission.connectionEpoch(),"render-retire-reload");pendingStatus="RETIRE_READD_PASS";framesUntilCapture=2;
    }
    void invalidateAndRebuildResources() {
        RenderSystem.assertOnRenderThread();var old=guard.prepare(snapshots.get(NativeSparseBinding.UPPER));guard.invalidateResources();
        var nearPositions=List.copyOf(meshes.keySet());
        meshes.values().forEach(NativeCubeMesh::close);meshes.clear();
        NativeProbeChecks.require(!guard.publish(old,()->{throw new AssertionError("Old resource generation uploaded");}),"old render-resource job rejected");
        for(var position:nearPositions)meshes.put(position,NativeCubeMesh.build(guard,snapshots.get(position),binding.palette()));
        if(lod!=null)lod.invalidateAndRebuild(List.copyOf(snapshots.values()));
        pendingStatus="RESOURCE_GENERATION_PASS";framesUntilCapture=2;
    }
    void swapNearMesh() {
        RenderSystem.assertOnRenderThread();if(lod==null)return;
        NativeProbeChecks.require(meshes.size()==1,"coexistence owns exactly one near mesh");
        var old=meshes.keySet().iterator().next();var next=old.equals(NativeSparseBinding.LOWER)?NativeSparseBinding.UPPER:NativeSparseBinding.LOWER;
        meshes.remove(old).close();
        meshes.put(next,NativeCubeMesh.build(guard,snapshots.get(next),binding.palette()));
        nearSwaps++;pendingStatus="NEAR_MASK_SWAP_PASS";framesUntilCapture=2;
    }
    void reloadResourcePacks() {
        RenderSystem.assertOnRenderThread();if(closed||resourcePackReloadRequested)return;
        var delayed=guard.prepare(snapshots.get(NativeSparseBinding.UPPER));
        resourcePackReloadRequested=true;requestReloadInvalidation();
        NativeProbeChecks.require(!guard.publish(delayed,()->{throw new AssertionError("Reload-fenced job uploaded");}),"resource-pack reload fences old native jobs");
        writeReport("RESOURCE_PACK_RELOAD_FENCED");
        Minecraft.getInstance().reloadResourcePacks();
    }
    private void writeReport(String status) {
        JsonObject report=new JsonObject();report.addProperty("status",status);report.addProperty("epoch",admission.connectionEpoch());
        report.addProperty("scope","actual Minecraft baked-model/VBO/shader diagnostic screen; virtual cameras, fixed illumination; not a playable full-height world");
        report.addProperty("dhCoexistence",coexistence());report.addProperty("nearMeshSwaps",nearSwaps);
        report.addProperty("resourcePackReloadRequested",resourcePackReloadRequested);
        if(lod!=null)report.add("distantHorizonsBridge",lod.diagnostics());
        report.addProperty("gpuVendor",org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_VENDOR));
        report.addProperty("gpuRenderer",org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_RENDERER));
        report.addProperty("glVersion",org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_VERSION));
        report.addProperty("liveNativeClientLevel",level.getClass().getName());report.addProperty("residentCubes",binding.cubes().residentCount());report.addProperty("retiredSnapshots",binding.cubes().retiredCount());report.addProperty("sessionClosed",closed);
        report.addProperty("gpuMeshes",meshes.size());report.addProperty("draws",draws);report.addProperty("retireReaddOperations",retirements);
        report.addProperty("goldMeshRegionPixels",goldPixels);report.addProperty("diamondMeshRegionPixels",diamondPixels);report.addProperty("screenshot",screenshot);
        report.addProperty("nativeLowerY",NativeProbeChecks.LOWER_BLOCK.getY());report.addProperty("nativeUpperY",NativeProbeChecks.UPPER_BLOCK.getY());
        report.addProperty("legacySectionSlots",legacySectionSlots);report.addProperty("legacyPostprocessingSlots",legacyPostprocessingSlots);
        report.addProperty("acceptedUploads",guard.published());report.addProperty("rejectedStaleUploads",guard.rejected());report.addProperty("resourceGeneration",guard.resourceGeneration());
        var array=new com.google.gson.JsonArray();for(var mesh:meshes.values()) {
            JsonObject item=new JsonObject();item.addProperty("cubeY",mesh.job.position().y());item.addProperty("leaseGeneration",mesh.job.snapshot().leaseGeneration());
            item.addProperty("revision",mesh.job.snapshot().revision());item.addProperty("vertices",mesh.vertices);item.addProperty("bytes",mesh.bytes);item.addProperty("vertexCrc32c",mesh.crc);array.add(item);
        }report.add("meshes",array);
        try{Path directory=Path.of("earthplatform-evidence","native-gpu");Files.createDirectories(directory);Files.writeString(directory.resolve("epoch-"+admission.connectionEpoch()+"-"+status.toLowerCase(Locale.ROOT)+".json"),report+"\n");}
        catch(IOException error){throw new UncheckedIOException(error);}
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();if(closed)return;closed=true;
        Throwable failure=cleanup(null,guard::close);
        for(var mesh:meshes.values())failure=cleanup(failure,mesh::close);
        if(lod!=null)failure=cleanup(failure,lod::close);
        meshes.clear();failure=cleanup(failure,whiteLight::close);failure=cleanup(failure,admission::close);
        for(var position:snapshots.keySet())failure=cleanup(failure,()->{
            if(binding.cubes().presence(position)==io.earthplatform.minecraft.residency.SparseCubeStorage.Presence.RESIDENT)binding.cubes().retire(position);
        });
        snapshots.clear();failure=cleanup(failure,binding::beginDisposableClose);
        failure=cleanup(failure,()->{try{level.close();}catch(IOException error){throw new UncheckedIOException(error);}});
        if(current==this)current=null;
        if(failure instanceof RuntimeException error)throw error;
        if(failure instanceof Error error)throw error;
        writeReport("CLOSED");EarthPlatformMod.LOGGER.info("NATIVE_CUBE_GPU_CLOSED epoch={} meshes=0",admission.connectionEpoch());
    }
    private static Throwable cleanup(Throwable first,Runnable action) {
        try{action.run();}catch(RuntimeException|Error failure){if(first==null)return failure;first.addSuppressed(failure);}
        return first;
    }
}
