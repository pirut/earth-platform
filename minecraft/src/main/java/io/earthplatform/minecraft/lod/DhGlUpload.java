package io.earthplatform.minecraft.lod;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Objects;
import static org.lwjgl.opengl.GL33C.*;

/** Exact production upload order with injectable operations; caller retains the owner until handoff. */
public final class DhGlUpload {
    public interface Owner {
        int vao();int vbo();int ibo();void vao(int id);void vbo(int id);void ibo(int id);
    }
    public interface Gl {
        int genVertexArray();int genBuffer();void bindVertexArray(int id);void bindBuffer(int target,int id);
        void vertexData(ByteBuffer vertices);void indexData(IntBuffer indices);void enableAttribute(int index);
        void integerAttribute(int index,int count,int type,int stride,long offset);
        void attribute(int index,int count,int type,boolean normalized,int stride,long offset);
        void check(String phase,Owner owner);
        default void allocateVertexBytes(int bytes){throw new UnsupportedOperationException("Rounded storage unavailable");}
        default void writeVertexData(ByteBuffer vertices){throw new UnsupportedOperationException("Rounded storage unavailable");}
        default void allocateIndexBytes(int bytes){throw new UnsupportedOperationException("Rounded storage unavailable");}
        default void writeIndexData(IntBuffer indices){throw new UnsupportedOperationException("Rounded storage unavailable");}
        default int bufferSize(int target){throw new UnsupportedOperationException("Storage size readback unavailable");}
    }
    public static void upload(Gl gl,Owner owner,ByteBuffer vertices,IntBuffer indices){upload(gl,owner,vertices,indices,false);}
    public static void uploadRounded(Gl gl,Owner owner,ByteBuffer vertices,IntBuffer indices){upload(gl,owner,vertices,indices,true);}
    private static void upload(Gl gl,Owner owner,ByteBuffer vertices,IntBuffer indices,boolean rounded){
        Objects.requireNonNull(gl);Objects.requireNonNull(owner);Objects.requireNonNull(vertices);Objects.requireNonNull(indices);
        if(owner.vao()!=0||owner.vbo()!=0||owner.ibo()!=0)throw new IllegalStateException("Fresh retained upload owner required");
        int bytes=vertices.remaining();
        if(bytes<=0||bytes>4096*64||bytes%64!=0||indices.remaining()!=bytes/64*6)throw new IllegalArgumentException("Bounded complete DH vertex/index payload required");
        gl.check("far.upload.entry",owner);
        owner.vao(gl.genVertexArray());gl.check("far.upload.vao.allocate",owner);
        gl.bindVertexArray(owner.vao());gl.check("far.upload.vao.bind",owner);
        owner.vbo(gl.genBuffer());gl.check("far.upload.vbo.allocate",owner);
        gl.bindBuffer(GL_ARRAY_BUFFER,owner.vbo());gl.check("far.upload.vbo.bind",owner);
        if(rounded){var storage=DhNativeDistanceBudget.storage(bytes/64);
            gl.allocateVertexBytes(storage.vertexBytes());gl.check("far.upload.vertex.allocate-rounded",owner);
            requireSize(gl,owner,GL_ARRAY_BUFFER,storage.vertexBytes(),"far.upload.vertex.size");
            gl.writeVertexData(vertices);
        }else gl.vertexData(vertices);
        gl.check("far.upload.vertex.data",owner);
        owner.ibo(gl.genBuffer());gl.check("far.upload.ibo.allocate",owner);
        gl.bindBuffer(GL_ELEMENT_ARRAY_BUFFER,owner.ibo());gl.check("far.upload.ibo.bind",owner);
        if(rounded){var storage=DhNativeDistanceBudget.storage(bytes/64);
            gl.allocateIndexBytes(storage.indexBytes());gl.check("far.upload.index.allocate-rounded",owner);
            requireSize(gl,owner,GL_ELEMENT_ARRAY_BUFFER,storage.indexBytes(),"far.upload.index.size");
            gl.writeIndexData(indices);
        }else gl.indexData(indices);
        gl.check("far.upload.index.data",owner);
        gl.enableAttribute(0);gl.check("far.upload.attribute0.enable",owner);
        gl.integerAttribute(0,4,GL_UNSIGNED_SHORT,16,0L);gl.check("far.upload.attribute0.pointer",owner);
        gl.enableAttribute(1);gl.check("far.upload.attribute1.enable",owner);
        gl.attribute(1,4,GL_UNSIGNED_BYTE,true,16,8L);gl.check("far.upload.attribute1.pointer",owner);
        gl.enableAttribute(2);gl.check("far.upload.attribute2.enable",owner);
        gl.integerAttribute(2,4,GL_UNSIGNED_BYTE,16,12L);gl.check("far.upload.attribute2.pointer",owner);
    }
    private static void requireSize(Gl gl,Owner owner,int target,int expected,String phase){
        int actual=gl.bufferSize(target);gl.check(phase,owner);
        if(actual!=expected)throw new IllegalStateException("Actual rounded DH buffer allocation differs at "+phase+": "+actual+" != "+expected);
    }
    private DhGlUpload() { }
}
