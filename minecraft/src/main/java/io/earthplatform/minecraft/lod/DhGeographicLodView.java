package io.earthplatform.minecraft.lod;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** Bounded source-provider far view. Readiness is attested by the caller's actual native mesh batch.
 * This class fences CPU command submission, not GPU completion. */
public final class DhGeographicLodView implements AutoCloseable {
    interface Mesh { int quads(); int bytes(); void close(); boolean deleted(); }
    interface State { void restore(); }
    interface Driver {
        void checkRenderThread(); String renderer(); State captureState(); void initialize();
        Mesh upload(DhGeographicLodData.TileKey key,List<DhTerrainGeometry.PageFace> faces);
        DrawStats draw(Map<DhGeographicLodData.TileKey,Mesh> meshes,Matrix4f matrix,double x,double y,double z);
        void close(); boolean deleted();
        default int pendingOwners(){return 0;}
    }
    record DrawStats(long drawCalls,long vertices,long submissionNanos) {}
    private final Thread owner=Thread.currentThread();
    private final Object fence=new Object();
    private final SceneSurfaceSampler sampler;
    private final WorldIdentity world;
    private final long epoch,resources;
    private final DhAdaptiveTerrainData source;
    private final Driver driver;
    private final String renderer;
    private Map<DhGeographicLodData.TileKey,Mesh> meshes=new LinkedHashMap<>();
    // Every owner remains reachable until deletion succeeds, including rejected staging resources.
    private final List<Mesh> retiring=new ArrayList<>();
    private List<SceneReadyCube> coverage=List.of();
    private long coverageGeneration=-1,refinementGeneration;
    private List<CubePos> refinementProfile=List.of();
    private String refinementIdentity="coarse";
    private boolean invalidated,closed,objectsDeleted,initializationStarted,initialized;
    private long drawCalls,submittedVertices,uploadedVertexBytes,rejectedCoverageChanges,coverageChanges,drawSubmissionNanos;
    private int currentFaces,clippedSourceFaces,peakResidentTileOwners,sampledCells,knownCells,unknownCells,sourceFaces,refinedCells,transitionFaces;
    private String lastFailure="";

    public DhGeographicLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long centerX,long centerZ) {
        this(sampler,world,epoch,resourceGeneration,centerX,centerZ,new OpenGlDriver());
    }
    DhGeographicLodView(SceneSurfaceSampler sampler,WorldIdentity world,long epoch,long resourceGeneration,long centerX,long centerZ,Driver driver) {
        this.driver=java.util.Objects.requireNonNull(driver);driver.checkRenderThread();
        this.sampler=java.util.Objects.requireNonNull(sampler);this.world=java.util.Objects.requireNonNull(world);
        if(epoch<0||resourceGeneration<1)throw new IllegalArgumentException("Valid scene epoch/resource generation required");
        this.epoch=epoch;resources=resourceGeneration;renderer=driver.renderer();
        source=new DhAdaptiveTerrainData(sampler,centerX,centerZ);
        require(world.datasetVersion().equals(source.identity()),"Near world identity must pin the exact shared source/appearance sampler");
    }
    /** Caller must retain this owner before initialization, so failed cleanup can be retried. */
    public void initialize() {
        checkOwner();driver.checkRenderThread();require(!closed&&!initializationStarted,"Far initialization state");initializationStarted=true;
        try {
            State state=driver.captureState();Throwable failure=null;
            try{driver.initialize();}catch(RuntimeException|Error error){failure=error;}
            try{state.restore();}catch(RuntimeException|Error error){failure=append(failure,error);}
            if(failure!=null)throw propagate(failure);
            require(replaceCoverage(0,List.of()),"Initial bounded far geometry publication failed: "+lastFailure);
            initialized=true;
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** A successful whole-batch handshake precedes exposing or retiring native meshes on the same owner thread. */
    public boolean replaceCoverage(long generation,List<SceneReadyCube> proposed) {
        checkOpen();java.util.Objects.requireNonNull(proposed);
        List<SceneReadyCube> ready=new ArrayList<>();HashSet<CubePos> positions=new HashSet<>();
        for(var cube:proposed) {
            if(ready.size()>=256||cube==null||!cube.world().equals(world)||cube.epoch()!=epoch||cube.resourceGeneration()!=resources
                    ||cube.leaseGeneration()<=0||cube.revision()<0||!positions.add(cube.position()))return reject("Invalid or overbudget actual native-ready batch");
            ready.add(cube);
        }
        List<SceneReadyCube> immutable=List.copyOf(ready);
        synchronized(fence) {
            if(invalidated||closed||!source.identity().equals(sampler.identity()))return reject("Scene identity/resources invalidated");
            if(generation==coverageGeneration)return immutable.equals(coverage)||reject("Same generation changed its native-ready batch");
            if(generation<0||generation<coverageGeneration)return reject("Stale near coverage generation");
        }
        final DhAdaptiveTerrainData.Batch batch;
        List<CubePos> proposedProfile=positions.isEmpty()?refinementProfile:positions.stream().sorted().toList();
        String proposedProfileIdentity=profileIdentity(proposedProfile);
        // Only pure geometry budget validation is an ordinary refusal. Driver failures must remain visible.
        try{batch=source.clip(List.copyOf(positions),proposedProfile);}catch(IllegalArgumentException budget){return reject(budget.getMessage());}
        Map<DhGeographicLodData.TileKey,Mesh> candidates=new LinkedHashMap<>();
        State state=driver.captureState();Throwable failure=null;long bytesUploaded=0;
        try {
            for(var entry:batch.tiles().entrySet()) {
                Mesh mesh=driver.upload(entry.getKey(),entry.getValue());candidates.put(entry.getKey(),mesh);bytesUploaded+=mesh.bytes();
                peakResidentTileOwners=Math.max(peakResidentTileOwners,meshes.size()+candidates.size()+retiring.size());
                require(peakResidentTileOwners<=2*DhGeographicLodData.MAX_TILES,"Current plus private staging far owner budget");
            }
        }catch(RuntimeException|Error error){failure=error;}
        // Restore and verify before publishing anything: this operation can itself fail.
        try{state.restore();}catch(RuntimeException|Error error){requestInvalidation();failure=append(failure,error);}
        if(failure!=null) {
            peakResidentTileOwners=Math.max(peakResidentTileOwners,meshes.size()+candidates.size()+retiring.size()+driver.pendingOwners());
            if(driver.pendingOwners()!=0)requestInvalidation();
            retiring.addAll(candidates.values());Throwable cleanup=drainRetirements();
            if(cleanup!=null){requestInvalidation();failure=append(failure,cleanup);}
            throw propagate(failure);
        }
        synchronized(fence) {
            if(invalidated||closed||generation<=coverageGeneration||!source.identity().equals(sampler.identity())) {
                retiring.addAll(candidates.values());Throwable cleanup=drainRetirements();
                if(cleanup!=null){invalidated=true;throw propagate(cleanup);}
                return reject("Far candidates became stale before publication");
            }
            retiring.addAll(meshes.values());meshes=candidates;coverage=immutable;coverageGeneration=generation;
            currentFaces=batch.faces();clippedSourceFaces=batch.clippedSourceFaces();
            sampledCells=batch.sampledCells();knownCells=batch.knownCells();unknownCells=batch.unknownCells();sourceFaces=batch.sourceFaces();refinedCells=batch.refinedCells();transitionFaces=batch.transitionFaces();
            refinementProfile=proposedProfile;if(!refinementIdentity.equals(proposedProfileIdentity)){refinementGeneration++;refinementIdentity=proposedProfileIdentity;}
            uploadedVertexBytes+=bytesUploaded;coverageChanges++;lastFailure="";
            Throwable cleanup=drainRetirements();
            // Commit is already visible. Cleanup failure is fatal/fenced, never a normal false refusal.
            if(cleanup!=null){invalidated=true;throw propagate(cleanup);}
            return true;
        }
    }
    public boolean coverageReady(long generation){checkOwner();synchronized(fence){return initialized&&!closed&&!invalidated&&coverageGeneration==generation&&source.identity().equals(sampler.identity());}}
    public void draw(Matrix4f matrix,double x,double y,double z,long resourceGeneration) {
        checkOwner();driver.checkRenderThread();
        synchronized(fence) {
            if(!initialized||closed||invalidated||resourceGeneration!=resources)return;
            if(!source.identity().equals(sampler.identity())){invalidated=true;return;}
            require(Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z),"Finite native scene camera required");
            State state=driver.captureState();Throwable failure=null;
            try{DrawStats stats=driver.draw(meshes,matrix,x,y,z);drawCalls+=stats.drawCalls();submittedVertices+=stats.vertices();drawSubmissionNanos=stats.submissionNanos();}
            catch(RuntimeException|Error error){failure=error;}
            try{state.restore();}catch(RuntimeException|Error error){failure=append(failure,error);}
            if(failure!=null){invalidated=true;throw propagate(failure);}
        }
    }
    public void requestInvalidation(){synchronized(fence){invalidated=true;}}
    public JsonObject diagnostics() {
        checkOwner();synchronized(fence){JsonObject out=new JsonObject();
            out.addProperty("scope","Actual source-bounded geographic height summaries and unchanged DH terrain shaders; visual-only native-coordinate scene");
            out.addProperty("sourceAppearanceIdentity",source.identity());out.addProperty("epoch",epoch);out.addProperty("resourceGeneration",resources);
            out.addProperty("radiusBlocks",DhGeographicLodData.RADIUS);out.addProperty("cellSizeBlocks",DhGeographicLodData.CELL_SIZE);
            out.addProperty("sampledCells",sampledCells);out.addProperty("knownCells",knownCells);out.addProperty("unknownCells",unknownCells);
            out.addProperty("refinedCells",refinedCells);out.addProperty("transitionFaces",transitionFaces);out.addProperty("refinementProfileIdentity",refinementIdentity);out.addProperty("refinementProfileGeneration",refinementGeneration);
            out.addProperty("sourceFaces",sourceFaces);out.addProperty("publishedFaces",currentFaces);out.addProperty("clippedSourceFaces",clippedSourceFaces);
            out.addProperty("retirementPending",retiring.size());out.addProperty("driverPartialOwners",driver.pendingOwners());out.addProperty("pageTileOwners",meshes.size());out.addProperty("nearReadyCubes",coverage.size());out.addProperty("coverageGeneration",coverageGeneration);
            out.addProperty("peakResidentPageTileOwners",peakResidentTileOwners);out.addProperty("maxPublishedPageTileOwners",64);out.addProperty("maxResidentIncludingPrivateStaging",128);
            out.addProperty("coverageChanges",coverageChanges);out.addProperty("rejectedCoverageChanges",rejectedCoverageChanges);out.addProperty("lastFailure",lastFailure);
            out.addProperty("drawCalls",drawCalls);out.addProperty("submittedVertices",submittedVertices);out.addProperty("uploadedVertexBytes",uploadedVertexBytes);
            out.addProperty("currentVertexBytes",meshes.values().stream().mapToLong(Mesh::bytes).sum());out.addProperty("lastCpuSubmissionNanos",drawSubmissionNanos);
            out.addProperty("hardwareGpuPerformanceClaim",false);out.addProperty("renderer",renderer);out.addProperty("stockDhModLoaded",false);
            out.addProperty("closed",closed);out.addProperty("invalidated",invalidated);out.addProperty("ownedGlObjectsDeleted",objectsDeleted);return out;}
    }
    private static String profileIdentity(List<CubePos> profile) {
        if(profile.isEmpty())return "coarse";
        String text=profile.stream().map(p->p.x()+","+p.z()).distinct().sorted().collect(java.util.stream.Collectors.joining(";"));
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private boolean reject(String reason){rejectedCoverageChanges++;lastFailure=reason;return false;}
    private Throwable drainRetirements() {
        Throwable failure=null;
        for(var iterator=retiring.iterator();iterator.hasNext();) {
            Mesh mesh=iterator.next();
            try{mesh.close();require(mesh.deleted(),"Far mesh deletion verification");iterator.remove();}
            catch(RuntimeException|Error error){failure=append(failure,error);}
        }
        return failure;
    }
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("DH geographic render owner required");}
    private void checkOpen(){checkOwner();driver.checkRenderThread();if(closed||!initializationStarted)throw new IllegalStateException("DH geographic renderer is closed or uninitialized");}
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException(reason);}
    private static Throwable append(Throwable first,Throwable next){if(next==null)return first;if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
    private static RuntimeException propagate(Throwable failure){if(failure instanceof Error error)throw error;return (RuntimeException)failure;}
    @Override public void close(){checkOwner();driver.checkRenderThread();synchronized(fence){
        if(closed&&objectsDeleted)return;closed=true;invalidated=true;
        retiring.addAll(meshes.values());meshes=new LinkedHashMap<>();coverage=List.of();currentFaces=0;
        Throwable failure=drainRetirements();
        try{driver.close();require(driver.deleted(),"Far global GL cleanup");}catch(RuntimeException|Error error){failure=append(failure,error);}
        objectsDeleted=retiring.isEmpty()&&driver.deleted();
        if(failure!=null)throw propagate(failure);
        require(objectsDeleted,"Geographic DH GL cleanup");
    }}

    /** Production driver uses the exact verified official quad builder and unchanged shaders. */
    private static final class OpenGlDriver implements Driver {
        private DhOfficialRuntime runtime;
        private int program,light,atlas;
        private final java.util.Set<Integer> shaders=new java.util.LinkedHashSet<>();
        private final List<GlMesh> incomplete=new ArrayList<>();
        public void checkRenderThread(){RenderSystem.assertOnRenderThread();}
        public String renderer(){checkRenderThread();return glGetString(GL_RENDERER);}
        public State captureState(){var state=new DhOffscreenGpuProbe.State();return()->{state.restore();require(state.matches(),"Geographic DH GL state restoration");};}
        public Mesh upload(DhGeographicLodData.TileKey key,List<DhTerrainGeometry.PageFace> faces) {
            require(incomplete.isEmpty(),"Earlier partial upload cleanup is pending; close before reuse");
            final byte[] bytes;
            try{bytes=runtime.buildRectangles(faces);}catch(Exception error){throw new IllegalStateException("Official DH geometry build",error);}
            GlMesh mesh=new GlMesh(bytes.length/64,bytes.length);
            // Retain ownership until the upload boundary successfully transfers the mesh.
            incomplete.add(mesh);
            try{uploadBuffer(mesh,bytes);incomplete.remove(mesh);return mesh;}
            catch(RuntimeException|Error failure){try{mesh.close();if(mesh.deleted())incomplete.remove(mesh);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        public DrawStats draw(Map<DhGeographicLodData.TileKey,Mesh> meshes,Matrix4f viewProjection,double cameraX,double cameraY,double cameraZ) {
            long started=System.nanoTime(),calls=0,vertices=0;
            glUseProgram(program);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,light);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,atlas);
            try(var stack=MemoryStack.stackPush()){var matrix=stack.mallocFloat(16);viewProjection.get(matrix);glUniformMatrix4fv(glGetUniformLocation(program,"uCombinedMatrix"),false,matrix);}
            for(var entry:meshes.entrySet()) {
                var key=entry.getKey();var mesh=(GlMesh)entry.getValue();
                double dx=key.originX()-cameraX,dy=(long)key.pageY()*2048-cameraY,dz=key.originZ()-cameraZ;
                if(Math.abs(dx)>8192||Math.abs(dy)>8192||Math.abs(dz)>8192)continue;
                glUniform3f(glGetUniformLocation(program,"uModelOffset"),(float)dx,(float)dy,(float)dz);
                glUniform1f(glGetUniformLocation(program,"uWorldYOffset"),key.pageY()*2048.0f);
                glBindVertexArray(mesh.vao);glDrawElements(GL_TRIANGLES,mesh.quads*6,GL_UNSIGNED_INT,0L);calls++;vertices+=mesh.quads*4L;
            }
            require(glGetError()==GL_NO_ERROR,"Actual DH geographic draw GL error");return new DrawStats(calls,vertices,System.nanoTime()-started);
        }
        public void close() {
            Throwable failure=null;
            for(var it=incomplete.iterator();it.hasNext();){GlMesh mesh=it.next();try{mesh.close();require(mesh.deleted(),"Partial far upload cleanup");it.remove();}catch(RuntimeException|Error e){failure=append(failure,e);}}
            try{if(program!=0){if(glGetInteger(GL_CURRENT_PROGRAM)==program)glUseProgram(0);glDeleteProgram(program);require(!glIsProgram(program),"Far program cleanup");program=0;}}catch(RuntimeException|Error e){failure=append(failure,e);}
            failure=append(failure,drainShaders());
            try{if(light!=0){glDeleteTextures(light);require(!glIsTexture(light),"Far light texture cleanup");light=0;}}catch(RuntimeException|Error e){failure=append(failure,e);}
            try{if(atlas!=0){glDeleteTextures(atlas);require(!glIsTexture(atlas),"Far atlas texture cleanup");atlas=0;}}catch(RuntimeException|Error e){failure=append(failure,e);}
            if(failure!=null)throw propagate(failure);
        }
        public boolean deleted(){return incomplete.isEmpty()&&shaders.isEmpty()&&program==0&&light==0&&atlas==0;}
        public int pendingOwners(){return incomplete.size();}
        private static final class GlMesh implements Mesh {
            final int quads,bytes;int vao,vbo,ibo;
            GlMesh(int quads,int bytes){this.quads=quads;this.bytes=bytes;}
            public int quads(){return quads;}public int bytes(){return bytes;}
            public boolean deleted(){return vao==0&&vbo==0&&ibo==0;}
            public void close(){Throwable failure=null;
                try{if(vao!=0){glDeleteVertexArrays(vao);require(!glIsVertexArray(vao),"Far VAO cleanup");vao=0;}}catch(RuntimeException|Error e){failure=append(failure,e);}
                try{if(vbo!=0){glDeleteBuffers(vbo);require(!glIsBuffer(vbo),"Far VBO cleanup");vbo=0;}}catch(RuntimeException|Error e){failure=append(failure,e);}
                try{if(ibo!=0){glDeleteBuffers(ibo);require(!glIsBuffer(ibo),"Far IBO cleanup");ibo=0;}}catch(RuntimeException|Error e){failure=append(failure,e);}
                if(failure!=null)throw propagate(failure);
            }
        }
    private static void uploadBuffer(GlMesh mesh,byte[] bytes) {
        mesh.vao=glGenVertexArrays();glBindVertexArray(mesh.vao);mesh.vbo=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,mesh.vbo);
        ByteBuffer data=MemoryUtil.memAlloc(bytes.length);java.nio.IntBuffer indexes=null;
        try{indexes=MemoryUtil.memAllocInt(mesh.quads*6);data.put(bytes).flip();glBufferData(GL_ARRAY_BUFFER,data,GL_STATIC_DRAW);
            for(int q=0;q<mesh.quads;q++){int b=q*4;indexes.put(b).put(b+1).put(b+2).put(b+2).put(b+3).put(b);}indexes.flip();
            mesh.ibo=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,mesh.ibo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indexes,GL_STATIC_DRAW);
        }finally{MemoryUtil.memFree(data);if(indexes!=null)MemoryUtil.memFree(indexes);}
        glEnableVertexAttribArray(0);glVertexAttribIPointer(0,4,GL_UNSIGNED_SHORT,16,0L);
        glEnableVertexAttribArray(1);glVertexAttribPointer(1,4,GL_UNSIGNED_BYTE,true,16,8L);
        glEnableVertexAttribArray(2);glVertexAttribIPointer(2,4,GL_UNSIGNED_BYTE,16,12L);
        require(glGetError()==GL_NO_ERROR,"DH geographic upload GL error");
    }
    public void initialize() {
        try{runtime=DhOfficialRuntime.open();}catch(Exception error){throw new IllegalStateException("Official DH scene runtime unavailable",error);}
        int vertex=0,fragment=0;Throwable failure=null;
        try {
            vertex=compile(GL_VERTEX_SHADER,runtime.shader(true));fragment=compile(GL_FRAGMENT_SHADER,runtime.shader(false));
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);
            glBindAttribLocation(program,0,"vPosition");glBindAttribLocation(program,1,"color");glBindAttribLocation(program,2,"irisData");glLinkProgram(program);
            require(glGetProgrami(program,GL_LINK_STATUS)==GL_TRUE,"Official geographic DH link: "+glGetProgramInfoLog(program));
            glDetachShader(program,vertex);glDetachShader(program,fragment);glActiveTexture(GL_TEXTURE0);light=glGenTextures();glBindTexture(GL_TEXTURE_2D,light);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);glPixelStorei(GL_UNPACK_ALIGNMENT,1);glPixelStorei(GL_UNPACK_ROW_LENGTH,0);glPixelStorei(GL_UNPACK_SKIP_ROWS,0);glPixelStorei(GL_UNPACK_SKIP_PIXELS,0);
            try(var stack=MemoryStack.stackPush()){var white=stack.malloc(16*16*4);while(white.hasRemaining())white.put((byte)255);white.flip();glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,16,16,0,GL_RGBA,GL_UNSIGNED_BYTE,white);}
            glActiveTexture(GL_TEXTURE1);atlas=glGenTextures();glBindTexture(GL_TEXTURE_2D,atlas);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);glUseProgram(program);
            glUniform1i(glGetUniformLocation(program,"uLightMap"),0);glUniform1i(glGetUniformLocation(program,"uBlockAtlas"),1);
            for(String n:List.of("uIsWhiteWorld","uNoiseEnabled","uDitherDhRendering"))glUniform1i(glGetUniformLocation(program,n),0);
            for(String n:List.of("uMircoOffset","uEarthRadius","uClipDistance"))glUniform1f(glGetUniformLocation(program,n),0);
            glUniform1f(glGetUniformLocation(program,"uFrameMod8"),-1);require(glGetError()==GL_NO_ERROR,"Geographic DH shader setup");
        }catch(java.io.IOException error){failure=new IllegalStateException("Official DH shader read",error);}
        catch(RuntimeException|Error error){failure=error;}
        finally{failure=append(failure,drainShaders());}
        if(failure!=null)throw propagate(failure);
    }
    private int compile(int kind,String source){int shader=glCreateShader(kind);require(shader!=0,"DH shader allocation");shaders.add(shader);glShaderSource(shader,source);glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)!=GL_TRUE)throw new IllegalStateException("Official DH scene shader: "+glGetShaderInfoLog(shader));return shader;}
    private Throwable drainShaders(){Throwable failure=null;for(var it=shaders.iterator();it.hasNext();){int shader=it.next();try{if(glIsShader(shader))glDeleteShader(shader);require(!glIsShader(shader),"Far temporary shader cleanup");it.remove();}catch(RuntimeException|Error error){failure=append(failure,error);}}return failure;}
    }
}
