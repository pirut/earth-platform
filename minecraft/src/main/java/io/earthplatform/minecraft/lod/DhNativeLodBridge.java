package io.earthplatform.minecraft.lod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL33C.*;

/** Actual admitted native snapshots -> page-keyed actual-DH VBOs -> unchanged official terrain shaders. */
public final class DhNativeLodBridge implements AutoCloseable {
    public static final String OPT_IN="earthplatform.experimental.dhNativeCoexistence";
    private final Thread owner=Thread.currentThread();
    private final EnginePaletteContext palette;
    private final DhOfficialRuntime runtime;
    private final DhPageOwnership ownership;
    private final Map<CubePos,Mesh> meshes=new LinkedHashMap<>();
    private final Map<CubePos,EngineCubeSnapshot> snapshots=new LinkedHashMap<>();
    private final Map<CubePos,DhPageOwnership.Job> retiredJobs=new LinkedHashMap<>();
    private int program,light,atlas;
    private boolean closed,objectsDeleted;
    private long draws,maskedDraws,uploadedBytes,retirementChecks,resourceChecks;
    private final String renderer,version;
    private static final class Mesh implements AutoCloseable {
        final DhPageOwnership.Job job;
        final int quads,sourceBlocks,bytes;
        int vao,vbo,ibo;
        Mesh(DhPageOwnership.Job job,int quads,int sourceBlocks,int bytes){this.job=job;this.quads=quads;this.sourceBlocks=sourceBlocks;this.bytes=bytes;}
        @Override public void close(){if(vao!=0)glDeleteVertexArrays(vao);if(vbo!=0)glDeleteBuffers(vbo);if(ibo!=0)glDeleteBuffers(ibo);}
        boolean deleted(){return (vao==0||!glIsVertexArray(vao))&&(vbo==0||!glIsBuffer(vbo))&&(ibo==0||!glIsBuffer(ibo));}
    }

    public DhNativeLodBridge(CubeStreamAdmission admission,EnginePaletteContext palette,List<EngineCubeSnapshot> initial) {
        RenderSystem.assertOnRenderThread();
        if(!Boolean.getBoolean(OPT_IN))throw new IllegalStateException("Explicit native/DH coexistence opt-in required");
        if(initial.isEmpty()||initial.size()>2)throw new IllegalArgumentException("Two-source DH fixture budget");
        this.palette=java.util.Objects.requireNonNull(palette);
        var namespace=new DhPageOwnership.Namespace(admission.world(),UUID.randomUUID(),"earthplatform:native_sparse_fixture",
                "native-cartesian-diagnostic","1","native-block-metres",1,2048);
        ownership=new DhPageOwnership(admission,namespace);
        renderer=glGetString(GL_RENDERER);version=glGetString(GL_VERSION);
        try {runtime=DhOfficialRuntime.open();}
        catch(Exception failure){ownership.close();throw new IllegalStateException("Pinned isolated DH runtime could not open",failure);}
        var state=new DhOffscreenGpuProbe.State();
        try {
            initializeShaders();
            for(var snapshot:initial)replaceSnapshot(snapshot);
        } catch(RuntimeException|Error failure) {
            state.restore();
            try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;
        } finally {state.restore();}
    }
    public void replaceSnapshot(EngineCubeSnapshot snapshot) {
        checkOpen();
        if(!meshes.containsKey(snapshot.position())&&meshes.size()>=2)throw new IllegalStateException("Page owner budget exceeded");
        var job=ownership.prepare(snapshot);
        var summary=DhSnapshotMesher.summarize(snapshot,palette);
        if(summary.pageY()!=job.key().pageY())throw new IllegalStateException("Snapshot/page identity mismatch");
        byte[] bytes=summary.faces().isEmpty()?new byte[0]:runtime.build(summary.faces());
        Mesh candidate=new Mesh(job,bytes.length/64,summary.sourceBlocks(),bytes.length);
        var state=new DhOffscreenGpuProbe.State();
        boolean installed=false;
        try {
            if(!ownership.publish(job,()->upload(candidate,bytes)))throw new IllegalStateException("DH candidate became stale before publication");
            Mesh old=meshes.put(snapshot.position(),candidate);snapshots.put(snapshot.position(),snapshot);installed=true;
            if(old!=null){old.close();require(old.deleted(),"Replaced DH VBO cleanup");}
            uploadedBytes+=bytes.length;
            var retired=retiredJobs.remove(snapshot.position());
            if(retired!=null) {
                require(!ownership.publish(retired,()->{throw new AssertionError("Old lease DH VBO published after re-add");}),"DH ABA rejection");
                retirementChecks++;
            }
        } finally {state.restore();if(!installed)candidate.close();require(state.matches(),"DH upload GL state restoration");}
    }
    /** Returns true only when a current, unmasked nonempty far mesh was actually drawn. */
    public boolean draw(CubePos cube,Collection<EngineCubeSnapshot> currentPublishedNativeMeshes,Matrix4f viewProjection,
                        double cameraX,double cameraY,double cameraZ) {
        checkOpen();Mesh mesh=meshes.get(cube);if(mesh==null)return false;
        if(!ownership.shouldDrawLod(mesh.job,currentPublishedNativeMeshes)){maskedDraws++;return false;}
        if(mesh.quads==0)return false;
        long pageOrigin=mesh.job.key().pageY()*2048L;
        float x=relative(cube.minBlockX(),cameraX),y=relative(pageOrigin,cameraY),z=relative(cube.minBlockZ(),cameraZ);
        var state=new DhOffscreenGpuProbe.State();
        try {
            glUseProgram(program);glBindVertexArray(mesh.vao);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,light);
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,atlas);
            glUniform3f(glGetUniformLocation(program,"uModelOffset"),x,y,z);
            glUniform1f(glGetUniformLocation(program,"uWorldYOffset"),(float)pageOrigin);
            try(var stack=MemoryStack.stackPush()) {
                var matrix=stack.mallocFloat(16);viewProjection.get(matrix);glUniformMatrix4fv(glGetUniformLocation(program,"uCombinedMatrix"),false,matrix);
            }
            if(!ownership.drawIfCurrent(mesh.job,currentPublishedNativeMeshes,()->{
                glDrawElements(GL_TRIANGLES,mesh.quads*6,GL_UNSIGNED_INT,0L);draws++;
                require(glGetError()==GL_NO_ERROR,"DH terrain draw OpenGL error");
            }))return false;
        } finally {state.restore();require(state.matches(),"DH draw GL state restoration");}
        return true;
    }
    public void retire(CubePos cube) {
        checkOpen();Mesh old=meshes.remove(cube);snapshots.remove(cube);
        if(old==null)return;
        retiredJobs.put(cube,old.job);old.close();require(old.deleted(),"Retired DH VBO cleanup");
        while(retiredJobs.size()>2)retiredJobs.remove(retiredJobs.keySet().iterator().next());
        require(!ownership.publish(old.job,()->{throw new AssertionError("Retired DH VBO published");}),"Retired lease rejection");
        retirementChecks++;
    }
    public void invalidateAndRebuild(Collection<EngineCubeSnapshot> latest) {
        checkOpen();if(latest.isEmpty()||latest.size()>2)throw new IllegalArgumentException("Two-source rebuild budget");
        var old=meshes.values().stream().map(mesh->mesh.job).toList();
        ownership.invalidateResources();
        for(var mesh:meshes.values()){mesh.close();require(mesh.deleted(),"Resource-generation VBO cleanup");}
        meshes.clear();snapshots.clear();retiredJobs.clear();
        for(var job:old) {
            require(!ownership.publish(job,()->{throw new AssertionError("Old resource generation DH VBO published");}),"DH resource generation rejection");resourceChecks++;
        }
        for(var snapshot:latest)replaceSnapshot(snapshot);
    }
    public void requestInvalidation(){ownership.requestInvalidation();}
    public JsonObject diagnostics() {
        checkOwner();JsonObject out=new JsonObject();
        out.addProperty("scope","Actual admitted native snapshot geometry, isolated official DH LodQuadBuilder and unchanged terrain shaders; bounded coexistence fixture");
        out.addProperty("dhArtifactSha256",DhOfficialRuntime.ARTIFACT_SHA256);out.addProperty("stockDhModLoaded",false);
        out.addProperty("worldIntegratedPlayable",false);out.addProperty("upstreamPoolLifetime","one shared isolated runtime/daemon recycler per Minecraft process");
        out.addProperty("renderer",renderer);out.addProperty("glVersion",version);out.addProperty("closed",closed);out.addProperty("ownedGlObjectsDeleted",objectsDeleted);
        out.addProperty("cachedPageOwners",meshes.size());out.addProperty("draws",draws);out.addProperty("maskedDraws",maskedDraws);out.addProperty("uploadedVertexBytes",uploadedBytes);
        out.addProperty("acceptedUploads",ownership.published());out.addProperty("rejectedJobs",ownership.rejected());out.addProperty("resourceGeneration",ownership.resourceGeneration());
        out.addProperty("atomicDrawSubmissions",ownership.drawn());out.addProperty("fencedDrawRejections",ownership.rejectedDraws());
        out.addProperty("retirementRejectionChecks",retirementChecks);out.addProperty("resourceRejectionChecks",resourceChecks);
        JsonArray pages=new JsonArray();for(var mesh:meshes.values()) {
            var key=mesh.job.key();JsonObject p=new JsonObject();p.addProperty("cubeX",mesh.job.cube().x());p.addProperty("cubeY",mesh.job.cube().y());p.addProperty("cubeZ",mesh.job.cube().z());
            p.addProperty("pageY",key.pageY());p.addProperty("pageOriginY",key.pageY()*2048L);p.addProperty("detail",key.detail());
            p.addProperty("sourceBlocks",mesh.sourceBlocks);p.addProperty("actualDhQuads",mesh.quads);p.addProperty("vertexBytes",mesh.bytes);
            p.addProperty("leaseGeneration",mesh.job.snapshot().leaseGeneration());p.addProperty("revision",mesh.job.snapshot().revision());pages.add(p);
        }out.add("pages",pages);return out;
    }
    private static void upload(Mesh mesh,byte[] bytes) {
        if(bytes.length==0)return;
        mesh.vao=glGenVertexArrays();glBindVertexArray(mesh.vao);mesh.vbo=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,mesh.vbo);
        ByteBuffer data=org.lwjgl.system.MemoryUtil.memAlloc(bytes.length);
        java.nio.IntBuffer indexes=null;
        try {
            indexes=org.lwjgl.system.MemoryUtil.memAllocInt(mesh.quads*6);
            data.put(bytes).flip();glBufferData(GL_ARRAY_BUFFER,data,GL_STATIC_DRAW);
            for(int q=0;q<mesh.quads;q++){int b=q*4;indexes.put(b).put(b+1).put(b+2).put(b+2).put(b+3).put(b);}indexes.flip();
            mesh.ibo=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,mesh.ibo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indexes,GL_STATIC_DRAW);
        } finally {org.lwjgl.system.MemoryUtil.memFree(data);if(indexes!=null)org.lwjgl.system.MemoryUtil.memFree(indexes);}
        glEnableVertexAttribArray(0);glVertexAttribIPointer(0,4,GL_UNSIGNED_SHORT,16,0L);
        glEnableVertexAttribArray(1);glVertexAttribPointer(1,4,GL_UNSIGNED_BYTE,true,16,8L);
        glEnableVertexAttribArray(2);glVertexAttribIPointer(2,4,GL_UNSIGNED_BYTE,16,12L);
        require(glGetError()==GL_NO_ERROR,"DH actual VBO upload OpenGL error");
    }
    private void initializeShaders() {
        int vertex=0,fragment=0;
        try {
            vertex=compile(GL_VERTEX_SHADER,runtime.shader(true));fragment=compile(GL_FRAGMENT_SHADER,runtime.shader(false));
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);
            glBindAttribLocation(program,0,"vPosition");glBindAttribLocation(program,1,"color");glBindAttribLocation(program,2,"irisData");glLinkProgram(program);
            require(glGetProgrami(program,GL_LINK_STATUS)==GL_TRUE,"Official DH link: "+glGetProgramInfoLog(program));
            glDetachShader(program,vertex);glDetachShader(program,fragment);
            glActiveTexture(GL_TEXTURE0);light=glGenTextures();glBindTexture(GL_TEXTURE_2D,light);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);glPixelStorei(GL_UNPACK_ALIGNMENT,1);glPixelStorei(GL_UNPACK_ROW_LENGTH,0);glPixelStorei(GL_UNPACK_SKIP_ROWS,0);glPixelStorei(GL_UNPACK_SKIP_PIXELS,0);
            try(var stack=MemoryStack.stackPush()){var white=stack.malloc(16*16*4);while(white.hasRemaining())white.put((byte)255);white.flip();glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,16,16,0,GL_RGBA,GL_UNSIGNED_BYTE,white);}
            glActiveTexture(GL_TEXTURE1);atlas=glGenTextures();glBindTexture(GL_TEXTURE_2D,atlas);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
            glUseProgram(program);glUniform1i(glGetUniformLocation(program,"uLightMap"),0);glUniform1i(glGetUniformLocation(program,"uBlockAtlas"),1);
            for(String n:List.of("uIsWhiteWorld","uNoiseEnabled","uDitherDhRendering"))glUniform1i(glGetUniformLocation(program,n),0);
            for(String n:List.of("uMircoOffset","uEarthRadius","uClipDistance"))glUniform1f(glGetUniformLocation(program,n),0);
            glUniform1f(glGetUniformLocation(program,"uFrameMod8"),-1);
            require(glGetError()==GL_NO_ERROR,"DH shader setup OpenGL error");
        } catch(java.io.IOException failure){throw new IllegalStateException("Official DH shader input failed",failure);}
        finally{if(vertex!=0)glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment);}
    }
    private static int compile(int kind,String source) {
        int shader=glCreateShader(kind);glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)!=GL_TRUE){String error=glGetShaderInfoLog(shader);glDeleteShader(shader);throw new IllegalStateException("Official DH compile: "+error);}return shader;
    }
    private static float relative(long origin,double camera) {
        if(!Double.isFinite(camera))throw new IllegalArgumentException("Finite camera required");
        double delta=(double)origin-camera;if(Math.abs(delta)>4096)throw new IllegalArgumentException("Bounded diagnostic camera");return(float)delta;
    }
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("DH renderer owner thread required");}
    private void checkOpen(){checkOwner();RenderSystem.assertOnRenderThread();if(closed)throw new IllegalStateException("DH bridge closed");}
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    @Override public void close() {
        checkOwner();RenderSystem.assertOnRenderThread();if(closed)return;closed=true;ownership.close();objectsDeleted=true;
        for(var mesh:meshes.values()){mesh.close();objectsDeleted&=mesh.deleted();}meshes.clear();snapshots.clear();retiredJobs.clear();
        if(program!=0)glDeleteProgram(program);if(light!=0)glDeleteTextures(light);if(atlas!=0)glDeleteTextures(atlas);
        objectsDeleted&=(program==0||!glIsProgram(program))&&(light==0||!glIsTexture(light))&&(atlas==0||!glIsTexture(atlas));
        require(objectsDeleted,"DH bridge owned GL object deletion");
    }
}
