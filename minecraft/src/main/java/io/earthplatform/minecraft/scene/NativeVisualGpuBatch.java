package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import java.util.List;
import java.util.Map;
import io.earthplatform.minecraft.visual.NativePageKey;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/** One retained restoration scope per actual Iris stage, shared across the eligible native pages. */
public final class NativeVisualGpuBatch implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private SceneGlRestoreOwner pending;
    private boolean failed,closed;
    private long passes,submittedPieces,culledPieces,drawCalls,fullDrawCalls,rangeDrawCalls;
    public record Evidence(long passes,long submittedPieces,long culledPieces,boolean pendingRestore,boolean failed,long drawCalls,long fullDrawCalls,long rangeDrawCalls){}
    public Evidence evidence(){checkOwner();return new Evidence(passes,submittedPieces,culledPieces,pending!=null,failed,drawCalls,fullDrawCalls,rangeDrawCalls);}

    public void draw(SceneNativeShaderContract.ShaderPass host,List<NativeVisualGpuPiece> pieces,
                     Matrix4f view,Matrix4f projection,double cameraX,double cameraY,double cameraZ,Runnable requireCurrent) {
        drawInternal(host,pieces,null,view,projection,cameraX,cameraY,cameraZ,requireCurrent);
    }
    public void draw(SceneNativeShaderContract.ShaderPass host,List<NativeVisualGpuPiece> pieces,Map<NativePageKey,Long> memberMasks,
                     Matrix4f view,Matrix4f projection,double cameraX,double cameraY,double cameraZ,Runnable requireCurrent){
        drawInternal(host,pieces,Map.copyOf(Objects.requireNonNull(memberMasks)),view,projection,cameraX,cameraY,cameraZ,requireCurrent);
    }
    private void drawInternal(SceneNativeShaderContract.ShaderPass host,List<NativeVisualGpuPiece> pieces,Map<NativePageKey,Long> memberMasks,
                     Matrix4f view,Matrix4f projection,double cameraX,double cameraY,double cameraZ,Runnable requireCurrent){
        checkOwner();Objects.requireNonNull(host);Objects.requireNonNull(pieces);Objects.requireNonNull(requireCurrent);
        if(closed||failed||pending!=null)throw new IllegalStateException("Native batch is fenced or awaiting restoration");
        requireCurrent.run();if(!host.isCurrent())throw new IllegalStateException("Native shader stage is stale");
        if(host.layer()!=RenderType.solid())return; // The admitted opaque palette has no other pass.
        if(!Double.isFinite(cameraX)||!Double.isFinite(cameraY)||!Double.isFinite(cameraZ))throw new IllegalArgumentException("Finite native camera required");
        ShaderInstance shader=Objects.requireNonNull(host.shader());
        NativeVisualCapsuleCapture.Lease resource=pieces.isEmpty()?null:pieces.getFirst().lease();
        if(resource!=null){resource.requireCurrent();if(!resource.shaderContract().matches(host.capture()))throw new IllegalStateException("Native piece shader capture differs from actual stage");}
        if(resource!=null)resource.shaderContract().requireFormat(shader.getVertexFormat(),shader.getVertexFormat().getVertexSize());
        for(var piece:pieces){piece.completion().requirePhysicalCurrent();if(piece.lease()!=resource)throw new IllegalStateException("Mixed native resource owners");}
        StrictGlErrors.check("native-distance.batch.entry");
        final Runnable hostRestore=Objects.requireNonNull(host.captureState());
        // Store a cleanup obligation before any subsequent vanilla capture or shader mutation can fail.
        final Scope scope=new Scope(hostRestore,shader);
        pending=new SceneGlRestoreOwner(scope::restore,()->StrictGlErrors.check("native-distance.batch.restore.before"),()->StrictGlErrors.check("native-distance.batch.restore.after"));
        Throwable failure=null;
        try {
            scope.previous=new SceneMesh.DrawState();StrictGlErrors.check("native-distance.batch.capture");
            scope.uniforms=new SceneMesh.ShaderUniforms(shader);
            host.setup();RenderSystem.setShader(()->shader);
            // Borrowed vanilla/Iris ChunkOffset is float3; integer literals select an incompatible buffer.
            if(shader.CHUNK_OFFSET!=null)shader.CHUNK_OFFSET.set(0.0f,0.0f,0.0f);
            StrictGlErrors.check("native-distance.batch.setup");
            FrustumIntersection frustum=new FrustumIntersection(new Matrix4f(projection).mul(view));
            for(var piece:pieces){
                piece.completion().requirePhysicalCurrent();
                var key=piece.key();float x=offset(key.minX(),cameraX),y=offset(key.minY(),cameraY),z=offset(key.minZ(),cameraZ);
                if(!frustum.testAab(x,y,z,x+64,y+64,z+64)){culledPieces++;continue;}
                Long selected=memberMasks==null?Long.valueOf(-1L):memberMasks.get(key);
                if(selected==null)throw new IllegalArgumentException("Native drawn page lacks exact member authority");
                if(piece.selectedQuads(selected)==0)continue;
                piece.bind();
                shader.setDefaultUniforms(VertexFormat.Mode.QUADS,new Matrix4f(view).translate(x,y,z),projection,Minecraft.getInstance().getWindow());
                shader.apply();StrictGlErrors.check("native-distance.batch.apply");
                var before=piece.drawEvidence();
                try{piece.drawMasked(selected);submittedPieces++;}
                finally{var after=piece.drawEvidence();long full=after.fullDrawCalls()-before.fullDrawCalls(),range=after.rangeDrawCalls()-before.rangeDrawCalls();fullDrawCalls+=full;rangeDrawCalls+=range;drawCalls+=full+range;}
                shader.clear();StrictGlErrors.check("native-distance.batch.clear");
            }
            requireCurrent.run();if(resource!=null)resource.requireCurrent();if(!host.isCurrent())throw new IllegalStateException("Native stage changed after batch");passes++;
        }catch(RuntimeException|Error e){failure=e;failed=true;}
        try{restore();}catch(RuntimeException|Error e){failure=SceneBuildStepper.append(failure,e);failed=true;}
        SceneBuildStepper.rethrow(failure);
    }
    private static float offset(long origin,double camera){double value=origin-camera;if(!Double.isFinite(value)||Math.abs(value)>16384)throw new IllegalStateException("Native local page exceeds distance precision envelope");return(float)value;}
    private void restore(){if(pending!=null){pending.restore();if(!pending.pending())pending=null;}}
    private static final class Scope {
        private final Runnable hostRestore;
        private final ShaderInstance shader;
        private SceneMesh.DrawState previous;
        private SceneMesh.ShaderUniforms uniforms;
        private boolean hostRestored;
        Scope(Runnable restore,ShaderInstance shader){hostRestore=restore;this.shader=shader;}
        void restore(){
            // The host restore is authoritative and idempotent. Once it succeeds, an outer
            // diagnostic retry must not overwrite its exact CPU/GPU/cache snapshot again.
            if(hostRestored)return;
            Throwable failure=null;
            try{shader.clear();}catch(RuntimeException|Error e){failure=e;}
            if(uniforms!=null)try{uniforms.restore(shader);}catch(RuntimeException|Error e){failure=SceneBuildStepper.append(failure,e);}
            if(previous!=null){
                for(int i=0;i<previous.shaderTextures.length;i++)try{shader.setSampler("Sampler"+i,previous.shaderTextures[i]);}catch(RuntimeException|Error e){failure=SceneBuildStepper.append(failure,e);}
                try{previous.restore();}catch(RuntimeException|Error e){failure=SceneBuildStepper.append(failure,e);}
            }
            try{hostRestore.run();hostRestored=true;}catch(RuntimeException|Error e){failure=SceneBuildStepper.append(failure,e);}
            SceneBuildStepper.rethrow(failure);
        }
    }
    @Override public void close(){checkOwner();closed=true;restore();}
    public boolean closeComplete(){checkOwner();return closed&&pending==null;}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native batch requires render owner");}
}
