package io.earthplatform.minecraft.lod;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay;
import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Exact installed-compiler output to real rounded GL storage. Retain before start and after every
 * failure until closeComplete. Completion proves physical/content lineage only: the parent still
 * needs its exact real frame/camera/native Capture/Iris/configuration lease before any draw.
 */
public final class SurvivalDhGpuPages implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final NativeDistanceCpuBudget cpu;
    private final SurvivalDhGpuLedger ledger;
    private final Operations gl;
    private final List<Entry> entries=new ArrayList<>();
    private final Completion completion=new Completion();
    private Source source;
    private SurvivalDhPageCompiler.Provenance provenance;
    private SurvivalDhGpuLedger.Batch reservation;
    private NativeDistanceCpuBudget.Reservation metadata,stagingCharge;
    private ByteBuffer vertices;
    private IntBuffer indices;
    private Operations.Bindings restoreTo;
    private int uploaded;
    private long issuedDrawCalls;
    private io.earthplatform.compat.dh.shader.SurvivalDhPassWitness completedDraw,liveDraw;
    private DhGeographicLodData.TileKey activeTile;
    private boolean started,prepared,operating,failed,completed,closed,closeComplete,vertexFreeTombstone,indexFreeTombstone,vertexAllocationUncertain,indexAllocationUncertain;
    public SurvivalDhGpuPages(NativeDistanceCpuBudget cpu){this(cpu,SurvivalDhGpuLedger.global(),new Operations());}
    SurvivalDhGpuPages(NativeDistanceCpuBudget cpu,SurvivalDhGpuLedger ledger,Operations gl){
        this.cpu=Objects.requireNonNull(cpu);this.ledger=Objects.requireNonNull(ledger);this.gl=Objects.requireNonNull(gl);
    }
    public void start(SurvivalDhPageCompiler.CompiledSet set){startSource(new CompiledSource(Objects.requireNonNull(set),cpu));}
    /** Diagnostic lifecycle seam only. No fake source can supply production provenance. */
    interface Source extends AutoCloseable {
        void claim();int tileCount();int vertexBytes(int tile);DhGeographicLodData.TileKey tileKey(int tile);
        void copyTo(int tile,ByteBuffer destination);boolean closeComplete();@Override void close();
    }
    private static final class CompiledSource implements Source {
        SurvivalDhPageCompiler.CompiledSet caller;
        final NativeDistanceCpuBudget cpu;
        SurvivalDhPageCompiler.CompiledSet.UploadOwner owned;
        CompiledSource(SurvivalDhPageCompiler.CompiledSet caller,NativeDistanceCpuBudget cpu){this.caller=caller;this.cpu=cpu;}
        public void claim(){owned=caller.transferToUpload(cpu);caller=null;}
        public int tileCount(){return owned.tileCount();}
        public int vertexBytes(int tile){return owned.vertexBytes(tile);}
        public DhGeographicLodData.TileKey tileKey(int tile){return owned.tileKey(tile);}
        public void copyTo(int tile,ByteBuffer destination){owned.copyTo(tile,destination);}
        public boolean closeComplete(){return owned==null||owned.closeComplete();}
        public void close(){if(owned!=null)owned.close();}
    }
    static class Operations {
        record Bindings(int vao,int array,int element){}
        void assertRenderThread(){RenderSystem.assertOnRenderThread();}
        long nanoTime(){return System.nanoTime();}
        Bindings capture(){return new Bindings(glGetInteger(GL_VERTEX_ARRAY_BINDING),glGetInteger(GL_ARRAY_BUFFER_BINDING),glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING));}
        void invalidate(){BufferUploader.invalidate();}
        void bindVertexArray(int id){GlStateManager._glBindVertexArray(id);}
        void bindBuffer(int target,int id){GlStateManager._glBindBuffer(target,id);}
        void restore(Bindings state){invalidate();bindVertexArray(state.vao);bindBuffer(GL_ARRAY_BUFFER,state.array);
            if(glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING)!=state.element)throw new IllegalStateException("Prior VAO element binding changed");}
        int create(int kind){return kind==0?glGenVertexArrays():glGenBuffers();}
        void allocate(int target,int bytes){glBufferData(target,bytes,GL_STATIC_DRAW);}
        void upload(ByteBuffer data){glBufferSubData(GL_ARRAY_BUFFER,0,data);}
        void upload(IntBuffer data){glBufferSubData(GL_ELEMENT_ARRAY_BUFFER,0,data);}
        int size(int target){return glGetBufferParameteri(target,GL_BUFFER_SIZE);}
        void enable(int attribute){glEnableVertexAttribArray(attribute);}
        int boundElement(){return glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);}
        void draw(int count){glDrawElements(GL_TRIANGLES,count,GL_UNSIGNED_INT,0L);}
        void integerAttribute(int index,int count,int type,int stride,long offset){glVertexAttribIPointer(index,count,type,stride,offset);}
        void attribute(int index,int count,int type,boolean normalized,int stride,long offset){glVertexAttribPointer(index,count,type,normalized,stride,offset);}
        boolean alive(int kind,int id){return kind==0?glIsVertexArray(id):glIsBuffer(id);}
        void delete(int kind,int id){if(kind==0)glDeleteVertexArrays(id);else glDeleteBuffers(id);}
        void check(String phase,int vao,int vbo,int ibo){StrictGlErrors.check("survival-dh."+phase,vao,vbo,ibo);}
        ByteBuffer allocateVertices(int bytes){return MemoryUtil.memAlloc(bytes);}
        IntBuffer allocateIndices(int count){return MemoryUtil.memAllocInt(count);}
        void beforeFreeVertices(){} void beforeFreeIndices(){}
        void freeVertices(ByteBuffer data){MemoryUtil.memFree(data);}
        void freeIndices(IntBuffer data){MemoryUtil.memFree(data);}
    }
    private static final class Entry implements DhGlUpload.Owner {
        final DhGeographicLodData.TileKey key;final int bytes;
        int vao,vbo,ibo;boolean unknownAllocation,deletionTombstone,uploaded,drained;
        Entry(DhGeographicLodData.TileKey key,int bytes){this.key=Objects.requireNonNull(key);this.bytes=bytes;}
        public int vao(){return vao;}public int vbo(){return vbo;}public int ibo(){return ibo;}
        public void vao(int id){if(vao!=id)throw new IllegalStateException("Unregistered DH VAO");}
        public void vbo(int id){if(vbo!=id)throw new IllegalStateException("Unregistered DH VBO");}
        public void ibo(int id){if(ibo!=id)throw new IllegalStateException("Unregistered DH IBO");}
        int name(int kind){return kind==0?vao:kind==1?vbo:ibo;}
        void name(int kind,int id){if(kind==0)vao=id;else if(kind==1)vbo=id;else ibo=id;}
    }
    void startSource(Source input){
        checkOwner();if(started||closed)throw new IllegalStateException("DH GPU start is single-use");started=true;operating=true;
        try{
            // Precharge the complete bounded owner/proof/key ledger before claiming or copying anything.
            metadata=cpu.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,8192L+4096L*DhNativeDistanceBudget.MAX_OWNERS);
            source=Objects.requireNonNull(input);source.claim();
            if(source instanceof CompiledSource real)provenance=real.owned.takeProvenance();
            int count=source.tileCount();if(count<1||count>DhNativeDistanceBudget.MAX_OWNERS)throw new IllegalArgumentException("Bounded exact DH set required");
            int[] quads=new int[count];int maxBytes=0;
            for(int i=0;i<count;i++){
                int bytes=source.vertexBytes(i);if(bytes<64||bytes%64!=0)throw new IllegalArgumentException("Complete DH quads required");
                DhNativeDistanceBudget.storage(bytes/64);quads[i]=bytes/64;maxBytes=Math.max(maxBytes,bytes);
                var key=source.tileKey(i);for(var prior:entries)if(prior.key.equals(key))throw new IllegalArgumentException("Duplicate DH tile");
                entries.add(new Entry(key,bytes));
            }
            reservation=ledger.reserve(this,quads); // Whole candidate preflight, no partial GL admission.
            stagingCharge=cpu.reserve(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD,Math.addExact(maxBytes,(long)maxBytes/64*24));
            vertexAllocationUncertain=true;vertices=gl.allocateVertices(maxBytes);if(vertices!=null)vertexAllocationUncertain=false;
            if(vertices==null||!vertices.isDirect()||vertices.capacity()!=maxBytes)throw new IllegalStateException("Different DH vertex staging allocation");
            indexAllocationUncertain=true;indices=gl.allocateIndices(maxBytes/64*6);if(indices!=null)indexAllocationUncertain=false;
            if(indices==null||!indices.isDirect()||indices.capacity()!=maxBytes/64*6)throw new IllegalStateException("Different DH index staging allocation");
            prepared=true;
        }catch(RuntimeException|Error error){failed=true;throw error;}finally{operating=false;}
    }
    public record Step(int tiles,int completedTiles,int totalTiles,long elapsedNanos,long budgetNanos,long overshootNanos,boolean complete){}
    public Step step(int maxTiles,long maxNanos){
        checkOwner();if(maxTiles<1||maxNanos<1)throw new IllegalArgumentException("Positive bounded upload slice required");
        if(!prepared||failed||closed||completed||operating)throw new IllegalStateException("DH upload is not resumable");
        operating=true;int initial=uploaded;long begin=gl.nanoTime();
        try{
            while(uploaded<entries.size()){
                Entry entry=entries.get(uploaded);vertices.clear().limit(entry.bytes);source.copyTo(uploaded,vertices);
                canonicalIndices(indices,entry.bytes/64);upload(entry);uploaded++;
                if(uploaded-initial>=maxTiles||elapsed(begin)>=maxNanos)break;
            }
            if(uploaded==entries.size()){
                drainStaging();source.close();if(!source.closeComplete())throw new IllegalStateException("DH compiled arrays did not drain");source=null;completed=true;
            }
            long elapsed=elapsed(begin);return new Step(uploaded-initial,uploaded,entries.size(),elapsed,maxNanos,Math.max(0,elapsed-maxNanos),completed);
        }catch(RuntimeException|Error error){failed=true;throw error;}finally{operating=false;}
    }
    private void upload(Entry entry){
        Throwable failure=null;
        try{
            check("upload.entry",entry);restoreTo=gl.capture();check("upload.capture",entry);gl.invalidate();
            DhGlUpload.uploadRounded(new UploadGl(entry),entry,vertices,indices);
        }catch(RuntimeException|Error error){failure=error;}
        try{restore();}catch(RuntimeException|Error error){failure=append(failure,error);}
        rethrow(failure);entry.uploaded=true;
    }
    private final class UploadGl implements DhGlUpload.Gl {
        private final Entry entry;private int buffers;
        UploadGl(Entry entry){this.entry=entry;}
        int create(int kind){
            entry.unknownAllocation=true; // Set BEFORE an invoked creation may allocate and then throw.
            int id=gl.create(kind);if(id==0)throw new IllegalStateException("Missing DH GL name");
            entry.name(kind,id);entry.unknownAllocation=false;return id;
        }
        public int genVertexArray(){return create(0);}public int genBuffer(){return create(++buffers);}
        public void bindVertexArray(int id){gl.bindVertexArray(id);}public void bindBuffer(int target,int id){gl.bindBuffer(target,id);}
        public void vertexData(ByteBuffer data){throw new UnsupportedOperationException("Rounded path required");}
        public void indexData(IntBuffer data){throw new UnsupportedOperationException("Rounded path required");}
        public void allocateVertexBytes(int bytes){gl.allocate(GL_ARRAY_BUFFER,bytes);}public void writeVertexData(ByteBuffer data){gl.upload(data);}
        public void allocateIndexBytes(int bytes){gl.allocate(GL_ELEMENT_ARRAY_BUFFER,bytes);}public void writeIndexData(IntBuffer data){gl.upload(data);}
        public int bufferSize(int target){return gl.size(target);}
        public void enableAttribute(int index){gl.enable(index);}
        public void integerAttribute(int index,int count,int type,int stride,long offset){gl.integerAttribute(index,count,type,stride,offset);}
        public void attribute(int index,int count,int type,boolean normalized,int stride,long offset){gl.attribute(index,count,type,normalized,stride,offset);}
        public void check(String phase,DhGlUpload.Owner ignored){SurvivalDhGpuPages.this.check(phase,entry);}
    }
    static void canonicalIndices(IntBuffer target,int quads){
        if(quads<1||quads>DhNativeDistanceBudget.MAX_PAGE_FACES||target==null||target.capacity()<quads*6)throw new IllegalArgumentException("Bounded DH index staging required");
        target.clear();for(int q=0;q<quads;q++){int v=q*4;target.put(v).put(v+1).put(v+2).put(v+2).put(v+3).put(v);}target.flip();
    }
    public Completion completion(){requireComplete();return completion;}
    public final class Completion {
        private Completion(){}
        public int tileCount(){requireComplete();return entries.size();}
        /** Sole public drawing ingress requires the private actual installed pass witness. */
        public void drawJoined(io.earthplatform.compat.dh.shader.SurvivalDhPassWitness witness){
            requireComplete();witness.beginPhysical(this);completedDraw=null;liveDraw=witness;operating=true;Throwable failure=null;
            try{
                try{
                    gl.check("draw.entry",0,0,0);restoreTo=gl.capture();gl.check("draw.capture",0,0,0);
                    for(Entry entry:entries){
                        activeTile=entry.key;witness.beforeRange(this);
                        for(int kind=0;kind<3;kind++){if(!gl.alive(kind,entry.name(kind)))throw new IllegalStateException("DH physical draw owner retired");check("draw.alive."+kind,entry);}
                        gl.invalidate();gl.bindVertexArray(entry.vao);gl.bindBuffer(GL_ARRAY_BUFFER,entry.vbo);
                        if(gl.boundElement()!=entry.ibo)throw new IllegalStateException("DH VAO element owner changed");check("draw.bind",entry);
                        witness.page(this,entry.key);
                        witness.beforeRange(this);gl.draw(Math.multiplyExact(entry.bytes/64,6));issuedDrawCalls=Math.incrementExact(issuedDrawCalls);check("draw.elements",entry);
                        witness.beforeRange(this);
                    }
                }catch(RuntimeException|Error error){failure=error;failed=true;}
                try{restore();}catch(RuntimeException|Error error){failure=append(failure,error);failed=true;}
                rethrow(failure);completedDraw=witness;
            }finally{activeTile=null;liveDraw=null;operating=false;witness.endPhysical();}
        }

        public DhGeographicLodData.TileKey tileKey(int tile){requireComplete();return entries.get(tile).key;}
        public void requireActiveTile(io.earthplatform.compat.dh.shader.SurvivalDhPassWitness witness,DhGeographicLodData.TileKey key){
            checkOwner();if(!operating||liveDraw!=witness||activeTile!=key||key==null||failed||closed)throw new IllegalStateException("Different exact physical DH tile operation");
        }
        public void forgetJoinedDraw(io.earthplatform.compat.dh.shader.SurvivalDhPassWitness witness){checkOwner();if(operating)throw new IllegalStateException("DH operation is still active");if(completedDraw==witness)completedDraw=null;}
        public void requireJoinedDrawComplete(io.earthplatform.compat.dh.shader.SurvivalDhPassWitness witness){
            checkOwner();if(operating||failed||closed||restoreTo!=null||completedDraw!=witness)throw new IllegalStateException("No completed physical DH draw for this exact invocation");
        }
        public void requirePhysicalComplete(){requireComplete();}
        public void restorePendingBindings(){checkOwner();if(operating)throw new IllegalStateException("DH physical operation has not returned");restore();}
        /** Authentic historical coverage/settings only, never a current configuration or frame lease. */
        public void requireAuthenticated(SurvivalDhCoverage coverage,SurvivalCompleteOverlay.FrameLatch frame){
            requireComplete();if(provenance==null)throw new IllegalStateException("Diagnostic DH source has no authentic producer proof");
            provenance.requireMatches(coverage,frame);
        }
        /** Pre-CAS exact lineage check. No CPU content currentness is asserted here. */
        public void requireAuthenticated(SurvivalDhCoverage coverage,SurvivalCompleteOverlay.PlanInput plan){
            requireComplete();if(provenance==null)throw new IllegalStateException("Diagnostic DH source has no authentic producer proof");
            provenance.requireMatches(coverage,plan);
        }
        public void requireCurrentConfiguration(){
            requireComplete();if(provenance==null)throw new IllegalStateException("Diagnostic DH source has no authentic producer proof");
            provenance.requireCurrentConfiguration();
        }
        /** Typed last-moment config check without re-entering this owner's physical operation. */
        public void requireDrawConfiguration(io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin.AcceptedFrame frame){
            checkOwner();frame.requireDhConfigurationOwner(this);
            if(!completed||failed||closed||provenance==null)throw new IllegalStateException("DH generation owner retired");
            provenance.requireCurrentConfiguration();
        }
        public String historicalGrassSideMode(){requireComplete();if(provenance==null)throw new IllegalStateException("Diagnostic source");return provenance.grassSideMode();}
        public String historicalDebugMode(){requireComplete();if(provenance==null)throw new IllegalStateException("Diagnostic source");return provenance.debugMode();}
    }
    private void requireComplete(){
        checkOwner();if(!completed||failed||closed||operating||source!=null||vertices!=null||indices!=null||stagingCharge!=null||restoreTo!=null)
            throw new IllegalStateException("DH set is not physically complete");
        try{for(Entry entry:entries){if(!entry.uploaded||entry.drained||entry.unknownAllocation||entry.deletionTombstone)throw new IllegalStateException("DH physical owner is unavailable");
            for(int kind=0;kind<3;kind++){if(!gl.alive(kind,entry.name(kind)))throw new IllegalStateException("DH GL storage is no longer live");check("complete.alive."+kind,entry);}}}
        catch(RuntimeException|Error error){failed=true;throw error;}
    }
    private void restore(){
        if(restoreTo==null)return;Throwable failure=null;
        try{gl.check("restore.before",0,0,0);}catch(RuntimeException|Error error){failure=error;}
        try{gl.restore(restoreTo);}catch(RuntimeException|Error error){failure=append(failure,error);}
        try{gl.check("restore.after",0,0,0);}catch(RuntimeException|Error error){failure=append(failure,error);}
        rethrow(failure);restoreTo=null;
    }
    private void drainStaging(){
        if(vertexFreeTombstone||indexFreeTombstone||vertexAllocationUncertain||indexAllocationUncertain)throw new IllegalStateException("Unknown native free retains charged tombstone");
        if(vertices!=null){gl.beforeFreeVertices();ByteBuffer pending=vertices;vertices=null;vertexFreeTombstone=true;gl.freeVertices(pending);pending=null;vertexFreeTombstone=false;}
        if(indices!=null){gl.beforeFreeIndices();IntBuffer pending=indices;indices=null;indexFreeTombstone=true;gl.freeIndices(pending);pending=null;indexFreeTombstone=false;}
        if(stagingCharge!=null){stagingCharge.releaseAfterPhysicalDrain();stagingCharge=null;}
    }
    private void closeEntry(Entry entry,int ordinal){
        if(entry.drained)return;Throwable failure=null;
        for(int kind=0;kind<3;kind++){
            int id=entry.name(kind);if(id==0)continue;
            // Numeric identity is consumed exactly once. Even a throwing delete may already reuse it.
            entry.name(kind,0);boolean certain=false;
            try{gl.delete(kind,id);check("close.delete."+kind,entry);boolean alive=gl.alive(kind,id);check("close.verify."+kind,entry);
                if(alive)throw new IllegalStateException("DH object survived deletion");certain=true;
            }catch(RuntimeException|Error error){failure=append(failure,error);}finally{if(!certain)entry.deletionTombstone=true;}
        }
        if(entry.unknownAllocation||entry.deletionTombstone)failure=append(failure,new IllegalStateException("Unknown DH allocation/deletion retains charged tombstone; no numeric retry"));
        if(failure==null){if(reservation!=null)reservation.releaseAfterPhysicalClose(ordinal);entry.drained=true;}
        rethrow(failure);
    }
    @Override public void close(){
        checkOwner();if(closeComplete)return;if(operating)throw new IllegalStateException("Retain DH owner until operation returns");closed=true;operating=true;
        try{
            Throwable failure=null;try{restore();}catch(RuntimeException|Error error){failure=error;}
            for(int i=0;i<entries.size();i++)try{closeEntry(entries.get(i),i);}catch(RuntimeException|Error error){failure=append(failure,error);}
            if(failure==null&&restoreTo==null){
                try{drainStaging();}catch(RuntimeException|Error error){failure=error;}
                if(failure==null&&source!=null)try{source.close();if(!source.closeComplete())throw new IllegalStateException("DH CPU ownership remains");source=null;}catch(RuntimeException|Error error){failure=error;}
                if(failure==null){entries.clear();completedDraw=null;if(provenance!=null){provenance.close();provenance=null;}
                    if(metadata!=null){metadata.releaseAfterPhysicalDrain();metadata=null;}reservation=null;closeComplete=true;}
            }
            rethrow(failure);
        }finally{operating=false;}
    }
    public long issuedDrawCalls(){checkOwner();return issuedDrawCalls;}
    public boolean closeComplete(){checkOwner();return closeComplete;}
    public long chargedGpuBytes(){checkOwner();return reservation==null?0:reservation.chargedBytes();}
    private long elapsed(long begin){return Math.max(0,gl.nanoTime()-begin);}
    private void check(String phase,Entry entry){gl.check(phase,entry.vao,entry.vbo,entry.ibo);}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("DH physical storage requires its exact render owner");gl.assertRenderThread();}
    private static Throwable append(Throwable first,Throwable next){if(first==null)return next;if(first!=next)try{first.addSuppressed(next);}catch(RuntimeException|Error ignored){}return first;}
    private static void rethrow(Throwable failure){if(failure instanceof Error error)throw error;if(failure instanceof RuntimeException error)throw error;}
}
