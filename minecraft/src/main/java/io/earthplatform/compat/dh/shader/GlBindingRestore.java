package io.earthplatform.compat.dh.shader;

import java.util.Objects;
import static org.lwjgl.opengl.GL33C.*;

/** Exact VAO/array binding snapshot with strict capture/restore checks and retry ownership. */
public final class GlBindingRestore {
    public interface Gl {
        int vao(); int array(); void bindVao(int id); void bindArray(int id);
        void check(String phase,int capturedVao,int capturedArray);
    }
    private static final Gl OPENGL=new Gl(){
        public int vao(){return glGetInteger(GL_VERTEX_ARRAY_BINDING);}public int array(){return glGetInteger(GL_ARRAY_BUFFER_BINDING);}
        public void bindVao(int id){glBindVertexArray(id);}public void bindArray(int id){glBindBuffer(GL_ARRAY_BUFFER,id);}
        public void check(String phase,int vao,int array){StrictGlErrors.check(phase,vao,array,0);}
    };
    private final Gl gl;private final int vao,array;private boolean restored;
    private GlBindingRestore(Gl gl,int vao,int array){this.gl=gl;this.vao=vao;this.array=array;}
    public static GlBindingRestore capture(){return capture(OPENGL);}
    public static GlBindingRestore capture(Gl gl){
        Objects.requireNonNull(gl);gl.check("far.bindings.capture.entry",0,0);
        int vao=gl.vao();gl.check("far.bindings.capture.vao",vao,0);
        int array=gl.array();gl.check("far.bindings.capture.array",vao,array);
        return new GlBindingRestore(gl,vao,array);
    }
    public void restore(){
        if(restored)return;
        gl.check("far.bindings.restore.entry",vao,array);
        gl.bindVao(vao);gl.check("far.bindings.restore.vao",vao,array);
        gl.bindArray(array);gl.check("far.bindings.restore.array",vao,array);
        int actualVao=gl.vao(),actualArray=gl.array();gl.check("far.bindings.restore.verify",vao,array);
        if(actualVao!=vao||actualArray!=array)throw new IllegalStateException("Shader-owned binding restoration failed");
        restored=true;
    }
    public boolean restored(){return restored;}
}
