package io.earthplatform.minecraft.scene;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.sun.jna.*;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.visual.*;
import io.earthplatform.minecraft.survival.render.*;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.CompiledPage;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.CompiledPage.PieceMetadata;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.Layer;
import io.earthplatform.minecraft.survival.render.SurvivalPageGeometry.MemberRange;
import java.lang.reflect.Field;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;
import static org.junit.jupiter.api.Assertions.*;
/** Actual EGL/production Operations with synthetic package-private source; no model or installed shader claim. */
class SurvivalNativeGpuPageActualGlTest {
 static final class Source implements SurvivalNativeGpuPage.Source {
  final List<ByteBuffer> buffers=new ArrayList<>(); final List<byte[]> expected=new ArrayList<>();
  final List<PieceMetadata> metadata=new ArrayList<>(); final NativeDistanceCpuBudget.Reservation charge;
  final SurvivalDistancePagePlan.Page page=new SurvivalDistancePagePlan.Page(new SurvivalVisualPage(new CubePos(-4,0,4)),9L,0,0);
  boolean claimed,borrowed,closed;
  Source(NativeDistanceCpuBudget cpu){
   assertEquals(32,DefaultVertexFormat.BLOCK.getVertexSize());
   charge=cpu.reserve(NativeDistanceCpuBudget.Pool.WORKER_UPLOAD,3*256L);
   try {for(int i=0;i<3;i++){
    var bytes=MemoryUtil.memAlloc(256);buffers.add(bytes);byte[] expectedBytes=new byte[256];
    for(int j=0;j<256;j++)expectedBytes[j]=(byte)(j*17+i*31);bytes.put(expectedBytes).flip();expected.add(expectedBytes);
    metadata.add(new PieceMetadata(Layer.values()[i],0,32,8,List.of(new MemberRange(0,0,4),new MemberRange(3,4,4))));
   }}catch(RuntimeException|Error e){close();throw e;}
  }
  public void claim(){if(claimed||closed)throw new IllegalStateException();claimed=true;}
  public SurvivalDistancePagePlan.Page page(){return page;} public long completedMemberMask(){return 9L;}
  public int pieceCount(){return 3;} public int rangeCount(int i){return 2;}
  public int vertexBytes(int i){return 256;} public int indexBytes(int i){return 48;}
  public PieceMetadata metadata(int i){return metadata.get(i);}
  public void withVertices(int i,CompiledPage.VertexUpload upload){if(!claimed||closed||borrowed)throw new IllegalStateException();borrowed=true;try{upload.upload(buffers.get(i).asReadOnlyBuffer(),DefaultVertexFormat.BLOCK);}finally{borrowed=false;}}
  public long retainedCpuBytes(){return closed?0:charge.bytes();}public boolean closeComplete(){return closed;}
  public void close(){if(borrowed)throw new IllegalStateException();if(!closed){for(var b:buffers)MemoryUtil.memFree(b);buffers.clear();closed=true;charge.releaseAfterPhysicalDrain();}}
 }
 static class Tracking extends SurvivalNativeGpuPage.Operations {
  final List<int[]> names=new ArrayList<>();String fail;boolean fired;
  @Override int create(int kind){int id=super.create(kind);if(kind==0)names.add(new int[3]);names.getLast()[kind]=id;return id;}
  @Override void check(String phase,int vao,int vbo,int ibo){if(!fired&&phase.equals(fail)){fired=true;glEnable(-1);}super.check(phase,vao,vbo,ibo);}
 }
 @Test void actualThreeLayerUploadExactPayloadMaskedDrawAndPhysicalDrain()throws Exception{
  try(var context=new Context();var host=new Host();var program=new DrawProgram()){
   var cpu=new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);var gpu=new NativeVisualBudget();var ops=new Tracking();var source=new Source(cpu);var page=new SurvivalNativeGpuPage(gpu,cpu,ops);
   try{
    page.startSource(source);assertEquals(3,gpu.evidence().pieces());assertThrows(IllegalStateException.class,page::completion);
    assertFalse(page.step(1,Long.MAX_VALUE).complete());host.assertBound();assertFalse(source.closed);
    assertFalse(page.step(1,Long.MAX_VALUE).complete());host.assertBound();assertFalse(source.closed);
    assertTrue(page.step(1,Long.MAX_VALUE).complete());host.assertBound();assertTrue(source.closed);
    assertEquals(3,page.completion().pieceCount());assertEquals(0,source.retainedCpuBytes());assertTrue(cpu.evidence().bytes()>0);
    for(int i=0;i<3;i++){
     int[] n=ops.names.get(i);glBindVertexArray(n[0]);glBindBuffer(GL_ARRAY_BUFFER,n[1]);
     var vertices=MemoryUtil.memAlloc(256);var indices=MemoryUtil.memAllocInt(12);
     try{glGetBufferSubData(GL_ARRAY_BUFFER,0,vertices);byte[] got=new byte[256];vertices.get(got);assertArrayEquals(source.expected.get(i),got);
      glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,n[2]);glGetBufferSubData(GL_ELEMENT_ARRAY_BUFFER,0,indices);int[] actual=new int[12];indices.get(actual);assertArrayEquals(new int[]{0,1,2,2,3,0,4,5,6,6,7,4},actual);
     }finally{MemoryUtil.memFree(vertices);MemoryUtil.memFree(indices);host.bind();}
     Layer layer=Layer.values()[i];assertEquals(2,primitives(()->assertEquals(new SurvivalNativeGpuPage.DrawStats(1,1),page.drawLayer(layer,8L))));host.assertBound();
     assertEquals(4,primitives(()->assertEquals(new SurvivalNativeGpuPage.DrawStats(1,2),page.drawLayer(layer,9L))));host.assertBound();
     assertEquals(0,primitives(()->assertEquals(new SurvivalNativeGpuPage.DrawStats(0,0),page.drawLayer(layer,2L))));host.assertBound();
    }
    assertEquals(6,page.issuedDrawCalls());page.close();host.assertBound();assertTrue(page.closeComplete());assertEquals(0,cpu.evidence().bytes());assertEquals(0,gpu.evidence().bytes());
    for(var n:ops.names){assertFalse(glIsVertexArray(n[0]));assertFalse(glIsBuffer(n[1]));assertFalse(glIsBuffer(n[2]));}
    System.out.println("ACTUAL_GL_SURVIVAL_PAGE layers=3 stride=32 exactBytes=true canonicalIndices=true maskedPrimitives=18 drawCalls=6 bindingsRestored=true physicalOwnersDrained=true");
   }finally{page.close();if(!source.closed)source.close();}
  }
 }
 @Test void actualUploadFailureRetainsWholePageCpuAndGpuUntilCleanup()throws Exception{
  try(var context=new Context();var host=new Host()){
   var cpu=new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);var gpu=new NativeVisualBudget();var ops=new Tracking();ops.fail="upload.index.bytes";var source=new Source(cpu);var page=new SurvivalNativeGpuPage(gpu,cpu,ops);
   try{page.startSource(source);var error=assertThrows(StrictGlErrors.Failure.class,()->page.step(1,Long.MAX_VALUE));assertEquals("native-distance.upload.index.bytes",error.phase());host.assertBound();assertFalse(source.closed);assertEquals(3,gpu.evidence().pieces());assertTrue(cpu.evidence().bytes()>source.retainedCpuBytes());assertThrows(IllegalStateException.class,page::completion);assertThrows(IllegalStateException.class,()->page.step(1,Long.MAX_VALUE));
    page.close();host.assertBound();assertTrue(source.closed);assertTrue(page.closeComplete());assertEquals(0,cpu.evidence().bytes());assertEquals(0,gpu.evidence().bytes());
    System.out.println("ACTUAL_GL_SURVIVAL_PAGE_FAILURE originalErrorRetained=true partialPageNeverReady=true cpuRetainedUntilGpuClose=true reservationsDrained=true");
   }finally{page.close();if(!source.closed)source.close();}
  }
 }
    private static final class DrawProgram implements AutoCloseable {
        final int program;
        DrawProgram(){int vertex=compile(GL_VERTEX_SHADER,"#version 330 core\nvoid main(){vec2 p[3]=vec2[3](vec2(-.5,-.5),vec2(.5,-.5),vec2(0,.5));gl_Position=vec4(p[gl_VertexID%3],0,1);}");int fragment=compile(GL_FRAGMENT_SHADER,"#version 330 core\nout vec4 color;void main(){color=vec4(1);}");program=glCreateProgram();try{glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),glGetProgramInfoLog(program));}finally{glDeleteShader(vertex);glDeleteShader(fragment);}glUseProgram(program);}
        static int compile(int kind,String source){int shader=glCreateShader(kind);glShaderSource(shader,source);glCompileShader(shader);assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),glGetShaderInfoLog(shader));return shader;}
        public void close(){glUseProgram(0);glDeleteProgram(program);}
    }
    private static int primitives(Runnable draw){int query=glGenQueries();try{glBeginQuery(GL_PRIMITIVES_GENERATED,query);try{draw.run();}finally{glEndQuery(GL_PRIMITIVES_GENERATED);}return glGetQueryObjectui(query,GL_QUERY_RESULT);}finally{glDeleteQueries(query);}}
    private static final class Host implements AutoCloseable {
        final int vao=glGenVertexArrays(),vbo=glGenBuffers(),ebo=glGenBuffers();
        Host(){bind();glBufferData(GL_ARRAY_BUFFER,64,GL_STATIC_DRAW);glBufferData(GL_ELEMENT_ARRAY_BUFFER,64,GL_STATIC_DRAW);StrictGlErrors.check("test.host.create");}
        void bind(){glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);}
        void assertBound(){assertEquals(vao,glGetInteger(GL_VERTEX_ARRAY_BINDING));assertEquals(vbo,glGetInteger(GL_ARRAY_BUFFER_BINDING));assertEquals(ebo,glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING));StrictGlErrors.check("test.host.bindings");}
        public void close(){glBindVertexArray(0);glBindBuffer(GL_ARRAY_BUFFER,0);glDeleteVertexArrays(vao);glDeleteBuffers(vbo);glDeleteBuffers(ebo);StrictGlErrors.check("test.host.close");}
    }
    private interface Egl extends Library {
        Egl API=Native.load("EGL",Egl.class);
        Pointer eglGetPlatformDisplay(int p,Pointer d,Pointer a);boolean eglInitialize(Pointer d,int[] major,int[] minor);boolean eglBindAPI(int api);boolean eglChooseConfig(Pointer d,int[] attrs,Pointer[] c,int capacity,int[] count);Pointer eglCreatePbufferSurface(Pointer d,Pointer c,int[] attrs);Pointer eglCreateContext(Pointer d,Pointer c,Pointer shared,int[] attrs);boolean eglMakeCurrent(Pointer d,Pointer draw,Pointer read,Pointer c);Pointer eglGetCurrentContext();boolean eglDestroyContext(Pointer d,Pointer c);boolean eglDestroySurface(Pointer d,Pointer s);boolean eglTerminate(Pointer d);boolean eglReleaseThread();int eglGetError();
    }
    private static final class Context implements AutoCloseable {
        final Egl egl=Egl.API;final Field renderThread;final Object previousThread;
        Pointer display,surface,context;boolean initialized,current;
        Context() throws Exception {
            renderThread=RenderSystem.class.getDeclaredField("renderThread");renderThread.setAccessible(true);previousThread=renderThread.get(null);
            assertNull(egl.eglGetCurrentContext());
            try {
                renderThread.set(null,Thread.currentThread());
                display=egl.eglGetPlatformDisplay(0x31DD,null,null);assertNotNull(display);require(egl.eglInitialize(display,new int[1],new int[1]));initialized=true;
                require(egl.eglBindAPI(0x30A2));Pointer[] configs=new Pointer[1];int[] count=new int[1];
                require(egl.eglChooseConfig(display,new int[]{0x3033,1,0x3040,8,0x3024,8,0x3023,8,0x3022,8,0x3038},configs,1,count));assertEquals(1,count[0]);
                surface=egl.eglCreatePbufferSurface(display,configs[0],new int[]{0x3057,16,0x3056,16,0x3038});assertNotNull(surface);
                context=egl.eglCreateContext(display,configs[0],null,new int[]{0x3098,4,0x30FB,5,0x30FD,1,0x3038});assertNotNull(context);
                require(egl.eglMakeCurrent(display,surface,surface,context));current=true;assertTrue(GL.createCapabilities().OpenGL45);
                StrictGlErrors.check("test.context.entry");
            } catch(Exception|Error error){try{close();}catch(Throwable cleanup){error.addSuppressed(cleanup);}throw error;}
        }
        void require(boolean value){if(!value)throw new AssertionError("EGL error "+Integer.toHexString(egl.eglGetError()));}
        public void close() throws Exception {
            Throwable failure=null;
            if(current){try{StrictGlErrors.check("test.context.exit");}catch(Throwable error){failure=error;}GL.setCapabilities(null);require(egl.eglMakeCurrent(display,null,null,null));current=false;}
            if(context!=null){require(egl.eglDestroyContext(display,context));context=null;}
            if(surface!=null){require(egl.eglDestroySurface(display,surface));surface=null;}
            if(initialized){require(egl.eglTerminate(display));initialized=false;}
            require(egl.eglReleaseThread());renderThread.set(null,previousThread);
            if(failure instanceof RuntimeException error)throw error;if(failure instanceof Error error)throw error;
        }
    }
}
