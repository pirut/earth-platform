package io.earthplatform.compat.dh.shader;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.opengl.GL33C.*;

/** Fail-closed GL boundary. On failure, at most16 reads preserve observed flags; always throws.
 * No error is silently cleared to allow rendering to continue. Context is sampled only after failure. */
public final class StrictGlErrors {
    public record Bindings(int ownedVao,int ownedVbo,int ownedIbo,int program,int drawFramebuffer,
                           int readFramebuffer,int vao,int arrayBuffer,int elementBuffer,
                           boolean programAlive,boolean vaoAlive,boolean arrayAlive,boolean elementAlive) { }
    public interface Gl { int error(); Bindings bindings(int ownedVao,int ownedVbo,int ownedIbo); }
    public static final class Failure extends IllegalStateException {
        private final String phase; private final int errorCode; private final Bindings bindings;
        private final int ownedVao,ownedVbo,ownedIbo;
        private final List<Integer> errorCodes;private final boolean errorCollectionExhausted;
        Failure(String phase,int errorCode,Bindings bindings,int vao,int vbo,int ibo,List<Integer> codes,boolean exhausted) {
            super("Strict GL failure phase="+phase+" error=0x"+Integer.toHexString(errorCode)+" ("+name(errorCode)+") ownedVao="+vao+" ownedVbo="+vbo+" ownedIbo="+ibo+" bindings="+bindings+" observedErrorCodes="+codes+" errorCollectionExhaustedBeforeContext="+exhausted);
            this.phase=phase;this.errorCode=errorCode;this.bindings=bindings;ownedVao=vao;ownedVbo=vbo;ownedIbo=ibo;errorCodes=List.copyOf(codes);errorCollectionExhausted=exhausted;
        }
        public int ownedVao(){return ownedVao;}public int ownedVbo(){return ownedVbo;}public int ownedIbo(){return ownedIbo;}
        public List<Integer> errorCodes(){return errorCodes;}public boolean errorCollectionExhausted(){return errorCollectionExhausted;}
        public boolean remainingErrorFlagsUnknown(){return !errorCollectionExhausted;}
        public String phase(){return phase;} public int errorCode(){return errorCode;} public Bindings bindings(){return bindings;}
    }
    private static final Gl OPENGL=new Gl(){
        public int error(){return glGetError();}
        public Bindings bindings(int ownVao,int ownVbo,int ownIbo){
            int program=glGetInteger(GL_CURRENT_PROGRAM),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            int vao=glGetInteger(GL_VERTEX_ARRAY_BINDING),array=glGetInteger(GL_ARRAY_BUFFER_BINDING),element=glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
            return new Bindings(ownVao,ownVbo,ownIbo,program,draw,read,vao,array,element,
                    program==0||glIsProgram(program),vao==0||glIsVertexArray(vao),array==0||glIsBuffer(array),element==0||glIsBuffer(element));
        }
    };
    public static void check(String phase){check(phase,0,0,0);}
    public static void check(String phase,int vao,int vbo,int ibo){check(OPENGL,phase,vao,vbo,ibo);}
    /** Injectable operation seam; production always uses the owner-thread OpenGL implementation. */
    public static void check(Gl gl,String phase,int vao,int vbo,int ibo){
        Objects.requireNonNull(gl);Objects.requireNonNull(phase);
        if(phase.isEmpty()||phase.length()>96)throw new IllegalArgumentException("Bounded GL phase label required");
        int code=gl.error();if(code==GL_NO_ERROR)return;
        List<Integer> codes=new ArrayList<>(16);codes.add(code);boolean exhausted=false;Throwable collectionFailure=null;
        try{for(int reads=1;reads<16;reads++){int next=gl.error();if(next==GL_NO_ERROR){exhausted=true;break;}codes.add(next);}}
        catch(RuntimeException|Error failure){collectionFailure=failure;}
        Bindings context=null;Throwable contextFailure=null;
        try{context=gl.bindings(vao,vbo,ibo);}catch(RuntimeException|Error failure){contextFailure=failure;}
        Failure failure=new Failure(phase,code,context,vao,vbo,ibo,codes,exhausted);
        if(collectionFailure!=null)failure.addSuppressed(collectionFailure);
        if(contextFailure!=null&&contextFailure!=collectionFailure)failure.addSuppressed(contextFailure);
        throw failure;
    }
    private static String name(int code){return switch(code){case 0x500->"INVALID_ENUM";case 0x501->"INVALID_VALUE";case 0x502->"INVALID_OPERATION";case 0x505->"OUT_OF_MEMORY";case 0x506->"INVALID_FRAMEBUFFER_OPERATION";case 0x507->"CONTEXT_LOST";default->"UNKNOWN";};}
    private StrictGlErrors() { }
}
