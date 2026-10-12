package io.earthplatform.minecraft.scene;

import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Objects;
import static org.lwjgl.opengl.GL33C.*;

/** Numeric GL ownership only. No capsule, readiness, publication, or Minecraft-world authority. */
final class NativeVisualGpuStorage implements AutoCloseable {
    /** Actual OpenGL operations. Production overrides only the vanilla cached binding adapter. */
    static class Operations {
        record Bindings(int vao,int array) {}
        Bindings capture(){return new Bindings(glGetInteger(GL_VERTEX_ARRAY_BINDING),glGetInteger(GL_ARRAY_BUFFER_BINDING));}
        void invalidate(){}
        void bindVertexArray(int id){glBindVertexArray(id);}
        void bindBuffer(int target,int id){glBindBuffer(target,id);}
        void restore(Bindings prior){invalidate();bindVertexArray(prior.vao);bindBuffer(GL_ARRAY_BUFFER,prior.array);}
        int create(int kind){return kind==0?glGenVertexArrays():glGenBuffers();}
        void allocate(int target,int bytes){glBufferData(target,bytes,GL_STATIC_DRAW);}
        int size(int target){return glGetBufferParameteri(target,GL_BUFFER_SIZE);}
        void upload(int target,ByteBuffer bytes){glBufferSubData(target,0,bytes);}
        void upload(int target,IntBuffer bytes){glBufferSubData(target,0,bytes);}
        void draw(int count,long byteOffset){glDrawElements(GL_TRIANGLES,count,GL_UNSIGNED_INT,byteOffset);}
        void delete(int kind,int id){if(kind==0)glDeleteVertexArrays(id);else glDeleteBuffers(id);}
        boolean alive(int kind,int id){return kind==0?glIsVertexArray(id):glIsBuffer(id);}
        void check(String phase,int vao,int vbo,int ibo){StrictGlErrors.check("native-distance."+phase,vao,vbo,ibo);}
    }
    record Evidence(int vao,int vbo,int ibo,boolean uploaded,boolean pendingRestore,boolean deletionTombstone,boolean closeComplete){}
    private final Thread owner=Thread.currentThread();
    private final NativeVisualBudget budget;
    private final Operations gl;
    private NativeVisualBudget.Reservation reservation;
    private SceneGlRestoreOwner restoration;
    private int vertexPayload,indexPayload,vao,vbo,ibo;
    private long issuedDrawCalls;
    private boolean started,uploaded,failed,closed,closeComplete,unverifiableDeletion;
    NativeVisualGpuStorage(NativeVisualBudget budget,Operations gl){this.budget=Objects.requireNonNull(budget);this.gl=Objects.requireNonNull(gl);}
    void reserve(int vertexPayload,int indexPayload){
        checkOwner();if(reservation!=null||started||closed)throw new IllegalStateException("Native storage reservation is single-use");
        reservation=budget.reserve(vertexPayload,indexPayload);this.vertexPayload=vertexPayload;this.indexPayload=indexPayload;
    }
    void upload(ByteBuffer vertices,IntBuffer indices,Runnable layout){
        checkOwner();if(started||closed||reservation==null)throw new IllegalStateException("Native storage requires its single reserved upload");started=true;
        Throwable failure=null;
        try{
            Objects.requireNonNull(layout);Objects.requireNonNull(vertices);Objects.requireNonNull(indices);
            if(vertices.remaining()!=vertexPayload||Math.multiplyExact(indices.remaining(),4)!=indexPayload)throw new IllegalArgumentException("Native storage payload changed after reservation");
            check("upload.entry");var prior=gl.capture();check("upload.capture");
            restoration=new SceneGlRestoreOwner(()->gl.restore(prior),()->check("upload.restore.before"),()->check("upload.restore.after"));
            gl.invalidate();
            vao=gl.create(0);check("upload.allocate.vao");if(vao==0)throw new IllegalStateException("Missing native VAO");
            vbo=gl.create(1);check("upload.allocate.vbo");if(vbo==0)throw new IllegalStateException("Missing native VBO");
            ibo=gl.create(2);check("upload.allocate.ibo");if(ibo==0)throw new IllegalStateException("Missing native IBO");
            gl.bindVertexArray(vao);gl.bindBuffer(GL_ARRAY_BUFFER,vbo);check("upload.bind.vertex");
            gl.allocate(GL_ARRAY_BUFFER,reservation.vertexAllocationBytes());check("upload.vertex.storage");
            if(gl.size(GL_ARRAY_BUFFER)!=reservation.vertexAllocationBytes())throw new IllegalStateException("Native vertex allocation differs from charged storage");
            check("upload.vertex.size");gl.upload(GL_ARRAY_BUFFER,vertices);check("upload.vertex.bytes");
            gl.bindBuffer(GL_ELEMENT_ARRAY_BUFFER,ibo);check("upload.bind.index");
            gl.allocate(GL_ELEMENT_ARRAY_BUFFER,reservation.indexAllocationBytes());check("upload.index.storage");
            if(gl.size(GL_ELEMENT_ARRAY_BUFFER)!=reservation.indexAllocationBytes())throw new IllegalStateException("Native index allocation differs from charged storage");
            check("upload.index.size");gl.upload(GL_ELEMENT_ARRAY_BUFFER,indices);check("upload.index.bytes");
            layout.run();check("upload.layout");
        }catch(RuntimeException|Error error){failure=error;failed=true;}
        try{restore();}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);failed=true;}
        SceneBuildStepper.rethrow(failure);uploaded=true;
    }
    void bind(){requireDrawable();gl.invalidate();gl.bindVertexArray(vao);check("draw.bind");}
    void draw(int count){requireDrawable();if(count<1||(long)count*4!=indexPayload)throw new IllegalArgumentException("Native index count differs from uploaded payload");drawRange(count,0);}
    void drawRange(int count,int firstIndex){
        requireDrawable();int available=indexPayload/4;
        if(count<1||firstIndex<0||count%6!=0||firstIndex%6!=0||firstIndex>available||count>available-firstIndex)
            throw new IllegalArgumentException("Native quad index range escapes uploaded storage");
        gl.draw(count,Math.multiplyExact((long)firstIndex,Integer.BYTES));if(issuedDrawCalls<Long.MAX_VALUE)issuedDrawCalls++;check("draw.elements");
    }
    void requireDrawable(){checkOwner();if(!uploaded||failed||closed||restoration!=null)throw new IllegalStateException("Native numeric storage is not live and complete");}
    long drawCalls(){checkOwner();return issuedDrawCalls;}
    long chargedBytes(){checkOwner();return reservation==null||reservation.released()?0:reservation.chargedBytes();}
    Evidence evidence(){checkOwner();return new Evidence(vao,vbo,ibo,uploaded&&!failed&&!closed,restoration!=null,unverifiableDeletion,closeComplete);}
    private void restore(){if(restoration!=null){restoration.restore();if(!restoration.pending())restoration=null;}}
    private void clearName(int kind){if(kind==0)vao=0;else if(kind==1)vbo=0;else ibo=0;}
    private void delete(int kind){
        int id=kind==0?vao:kind==1?vbo:ibo;if(id==0)return;Throwable failure=null;
        try{gl.delete(kind,id);check("close.delete."+kind);}catch(RuntimeException|Error error){failure=error;}
        boolean observed=false,alive=false;
        try{
            alive=gl.alive(kind,id);observed=true;
            // Discard an observed-dead numeric name BEFORE a diagnostic can fail. A subsequent
            // allocation may reuse the name; a retry must never delete that unrelated allocation.
            if(!alive)clearName(kind);
            try{check("close.verify."+kind);}catch(RuntimeException|Error verification){if(!alive)unverifiableDeletion=true;throw verification;}
            if(alive&&failure==null)failure=new IllegalStateException("Native object survived deletion");
        }catch(RuntimeException|Error error){
            // An unavailable liveness query gives no safe retry identity. Retain only a charged
            // tombstone, not a numeric name that may already have been deleted and reused.
            if(!observed){clearName(kind);unverifiableDeletion=true;}
            failure=SceneBuildStepper.append(failure,error);
        }
        SceneBuildStepper.rethrow(failure);
    }
    @Override public void close(){
        checkOwner();if(closeComplete)return;closed=true;Throwable failure=null;
        try{restore();}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);}
        for(int kind=0;kind<3;kind++)try{delete(kind);}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);}
        if(unverifiableDeletion&&failure==null)failure=new IllegalStateException("Native deletion verification failed; retain charged tombstone without reusing its numeric name");
        if(failure==null&&vao==0&&vbo==0&&ibo==0&&restoration==null){if(reservation!=null)reservation.releaseAfterPhysicalClose();closeComplete=true;}
        SceneBuildStepper.rethrow(failure);
    }
    boolean closeComplete(){checkOwner();return closeComplete;}
    private void check(String phase){gl.check(phase,vao,vbo,ibo);}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native numeric storage requires its render owner");}
}
