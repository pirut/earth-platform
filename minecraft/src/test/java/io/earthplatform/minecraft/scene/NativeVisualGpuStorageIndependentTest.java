package io.earthplatform.minecraft.scene;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated EGL tests of production numeric storage. No Minecraft/Photon capsule parity claim. */
public final class NativeVisualGpuStorageIndependentTest {
    private static final class Tracking extends NativeVisualGpuStorage.Operations {
        String failPhase;boolean fired,throwQuery;int deletes,restores;final int[] names=new int[3];final List<String> draws=new ArrayList<>();
        Tracking(){} Tracking(String phase){failPhase=phase;}
        @Override int create(int kind){int id=super.create(kind);names[kind]=id;return id;}
        @Override void draw(int count,long byteOffset){super.draw(count,byteOffset);draws.add(count+":"+byteOffset);}
        @Override void delete(int kind,int id){deletes++;super.delete(kind,id);}
        @Override boolean alive(int kind,int id){if(throwQuery){throwQuery=false;throw new IllegalStateException("injected query exception");}return super.alive(kind,id);}
        @Override void restore(Bindings prior){restores++;super.restore(prior);}
        @Override void check(String phase,int vao,int vbo,int ibo){
            if(!fired&&phase.equals(failPhase)){fired=true;glEnable(-1);}
            super.check(phase,vao,vbo,ibo);
        }
    }
    private static final class Payload implements AutoCloseable {
        final ByteBuffer vertices;final IntBuffer indices;
        Payload(int quads){vertices=MemoryUtil.memAlloc(quads*208);indices=MemoryUtil.memAllocInt(quads*6);for(int i=0;i<vertices.capacity();i++)vertices.put((byte)(i*37+11));vertices.flip();for(int i=0;i<indices.capacity();i++)indices.put(i%(quads*4));indices.flip();}
        @Override public void close(){MemoryUtil.memFree(vertices);MemoryUtil.memFree(indices);}
    }
    private static NativeVisualGpuStorage upload(NativeVisualBudget budget,Tracking gl,Payload p){
        var storage=new NativeVisualGpuStorage(budget,gl);storage.reserve(p.vertices.remaining(),p.indices.remaining()*4);storage.upload(p.vertices,p.indices,()->{});return storage;
    }
    @Test void actualRoundedStorageMatchesChargedBytesAndExactPayload(){
        try(var context=new EglContext();var payload=new Payload(5041)){
            var budget=new NativeVisualBudget();var ops=new Tracking();var s=upload(budget,ops,payload);var e=s.evidence();
            assertEquals((1<<20)+128*1024+4096,budget.evidence().bytes());
            glBindVertexArray(e.vao());glBindBuffer(GL_ARRAY_BUFFER,e.vbo());assertEquals(1<<20,glGetBufferParameteri(GL_ARRAY_BUFFER,GL_BUFFER_SIZE));
            ByteBuffer actual=MemoryUtil.memAlloc(payload.vertices.remaining());try{glGetBufferSubData(GL_ARRAY_BUFFER,0,actual);assertEquals(payload.vertices,actual);}finally{MemoryUtil.memFree(actual);}
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,e.ibo());assertEquals(128*1024,glGetBufferParameteri(GL_ELEMENT_ARRAY_BUFFER,GL_BUFFER_SIZE));
            IntBuffer indices=MemoryUtil.memAllocInt(payload.indices.remaining());try{glGetBufferSubData(GL_ELEMENT_ARRAY_BUFFER,0,indices);assertEquals(payload.indices,indices);}finally{MemoryUtil.memFree(indices);}
            StrictGlErrors.check("independent.native.payload");s.close();assertTrue(s.closeComplete());assertEquals(0,budget.evidence().bytes());assertEquals(0,s.chargedBytes());assertFalse(glIsVertexArray(e.vao()));assertFalse(glIsBuffer(e.vbo()));assertFalse(glIsBuffer(e.ibo()));
            System.out.println("ACTUAL_GL_NATIVE_PAYLOAD vertex=1048528 vertexStorage=1048576 index=120984 indexStorage=131072 charge=1183744");
        }
    }
    @Test void everyUploadBoundaryFailsClosedRestoresAndRetainsChargeUntilPhysicalClose(){
        List<String> phases=List.of("upload.entry","upload.capture","upload.allocate.vao","upload.allocate.vbo","upload.allocate.ibo","upload.bind.vertex","upload.vertex.storage","upload.vertex.size","upload.vertex.bytes","upload.bind.index","upload.index.storage","upload.index.size","upload.index.bytes","upload.layout","upload.restore.before","upload.restore.after");
        try(var context=new EglContext();var payload=new Payload(1)){
            int priorVao=glGenVertexArrays(),priorVbo=glGenBuffers(),priorIbo=glGenBuffers();glBindVertexArray(priorVao);glBindBuffer(GL_ARRAY_BUFFER,priorVbo);glBufferData(GL_ARRAY_BUFFER,64,GL_STATIC_DRAW);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,priorIbo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,64,GL_STATIC_DRAW);
            try{for(String phase:phases){
                var budget=new NativeVisualBudget();var ops=new Tracking(phase);var s=new NativeVisualGpuStorage(budget,ops);s.reserve(208,24);
                var error=assertThrows(StrictGlErrors.Failure.class,()->s.upload(payload.vertices,payload.indices,()->{}));assertEquals("native-distance."+phase,error.phase());assertEquals(GL_INVALID_ENUM,error.errorCode());assertEquals(List.of(GL_INVALID_ENUM),error.errorCodes());assertTrue(ops.fired);
                assertThrows(IllegalStateException.class,s::requireDrawable);assertEquals(135168,budget.evidence().bytes());assertFalse(s.closeComplete());
                assertEquals(priorVao,glGetInteger(GL_VERTEX_ARRAY_BINDING));assertEquals(priorVbo,glGetInteger(GL_ARRAY_BUFFER_BINDING));assertEquals(priorIbo,glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING));
                s.close();assertTrue(s.closeComplete());assertEquals(0,budget.evidence().pieces());for(int kind=0;kind<3;kind++)assertFalse(kind==0?glIsVertexArray(ops.names[kind]):glIsBuffer(ops.names[kind]));
                s.close();StrictGlErrors.check("independent.native.afterFault");
            }}finally{glDeleteVertexArrays(priorVao);glDeleteBuffers(priorVbo);glDeleteBuffers(priorIbo);}
            System.out.println("ACTUAL_GL_NATIVE_BOUNDARIES tested="+phases.size()+" firstErrorPreserved=true restore=true physicalCloseBeforeRelease=true");
        }
    }
    @Test void layoutCallbackExceptionRestoresBindingsAndCannotReleaseBeforeCleanup(){
        try(var context=new EglContext();var payload=new Payload(1)){
            var budget=new NativeVisualBudget();var ops=new Tracking();var s=new NativeVisualGpuStorage(budget,ops);s.reserve(208,24);var marker=new IllegalStateException("injected layout callback");var thrown=assertThrows(IllegalStateException.class,()->s.upload(payload.vertices,payload.indices,()->{throw marker;}));assertSame(marker,thrown);assertEquals(1,ops.restores);assertThrows(IllegalStateException.class,s::requireDrawable);assertEquals(1,budget.evidence().pieces());s.close();assertEquals(3,ops.deletes);assertEquals(0,budget.evidence().pieces());
        }
    }
    @Test void actualDeleteErrorClearsDeadNamesAndChargesUntilCleanRetry(){
        try(var context=new EglContext();var payload=new Payload(1)){
            var budget=new NativeVisualBudget();var ops=new Tracking();var s=upload(budget,ops,payload);int[] old=ops.names.clone();ops.failPhase="close.delete.0";
            var error=assertThrows(StrictGlErrors.Failure.class,s::close);assertEquals("native-distance.close.delete.0",error.phase());
            assertEquals(3,ops.deletes);assertEquals(0,s.evidence().vao());assertEquals(0,s.evidence().vbo());assertEquals(0,s.evidence().ibo());assertEquals(1,budget.evidence().pieces());
            int replacement=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,replacement);glBufferData(GL_ARRAY_BUFFER,16,GL_STATIC_DRAW);
            try{s.close();assertEquals(3,ops.deletes,"Retry must never delete old numeric names");assertTrue(glIsBuffer(replacement));assertTrue(s.closeComplete());assertEquals(0,budget.evidence().bytes());}finally{glDeleteBuffers(replacement);}
            System.out.println("ACTUAL_GL_NATIVE_DELETE firstError=true allIndependentDeletes=true noRetryOfDeletedNames=true old="+Arrays.toString(old)+" replacement="+replacement);
        }
    }
    @Test void failedPostDeleteVerificationLeavesOnlyChargedTombstoneNeverNumericRetry(){
        try(var context=new EglContext();var payload=new Payload(1)){
            var budget=new NativeVisualBudget();var ops=new Tracking();var s=upload(budget,ops,payload);ops.failPhase="close.verify.1";
            assertThrows(StrictGlErrors.Failure.class,s::close);var e=s.evidence();assertTrue(e.deletionTombstone());assertEquals(0,e.vao());assertEquals(0,e.vbo());assertEquals(0,e.ibo());assertEquals(3,ops.deletes);
            assertThrows(IllegalStateException.class,s::close);assertEquals(3,ops.deletes);assertEquals(135168,budget.evidence().bytes());assertFalse(s.closeComplete());
            System.out.println("ACTUAL_GL_NATIVE_TOMBSTONE deadObservationBeforeFault=true numericNames=0 charged=135168 retriesDeleteNothing=true");
        }
    }
    @Test void thrownLivenessQueryCannotRetainPotentiallyReusedNumericName(){
        try(var context=new EglContext();var payload=new Payload(1)){
            var budget=new NativeVisualBudget();var ops=new Tracking();var s=upload(budget,ops,payload);ops.throwQuery=true;
            assertThrows(IllegalStateException.class,s::close);assertTrue(s.evidence().deletionTombstone());assertEquals(0,s.evidence().vao());assertEquals(3,ops.deletes);assertThrows(IllegalStateException.class,s::close);assertEquals(3,ops.deletes);assertEquals(1,budget.evidence().pieces());
        }
    }
    @Test void failedRestoreRetainsIndependentCleanupObligationUntilRetry(){
        try(var context=new EglContext();var payload=new Payload(1)){
            var budget=new NativeVisualBudget();var ops=new Tracking("upload.restore.after");var s=new NativeVisualGpuStorage(budget,ops);s.reserve(208,24);
            assertThrows(StrictGlErrors.Failure.class,()->s.upload(payload.vertices,payload.indices,()->{}));assertTrue(s.evidence().pendingRestore());assertEquals(1,ops.restores);assertEquals(1,budget.evidence().pieces());
            ops.failPhase="upload.restore.before";ops.fired=false;assertThrows(StrictGlErrors.Failure.class,s::close);assertTrue(s.evidence().pendingRestore());assertEquals(2,ops.restores);assertEquals(3,ops.deletes);assertEquals(1,budget.evidence().pieces());
            s.close();assertEquals(3,ops.restores);assertEquals(3,ops.deletes);assertEquals(0,budget.evidence().pieces());assertTrue(s.closeComplete());
        }
    }
    private static final class DrawProgram implements AutoCloseable {
        final int program;
        DrawProgram(){int vertex=compile(GL_VERTEX_SHADER,"#version 330 core\nvoid main(){vec2 p[3]=vec2[3](vec2(-.5,-.5),vec2(.5,-.5),vec2(0,.5));gl_Position=vec4(p[gl_VertexID%3],0,1);}");int fragment=compile(GL_FRAGMENT_SHADER,"#version 330 core\nout vec4 color;void main(){color=vec4(1);}");program=glCreateProgram();try{glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),glGetProgramInfoLog(program));}finally{glDeleteShader(vertex);glDeleteShader(fragment);}glUseProgram(program);}
        static int compile(int kind,String source){int shader=glCreateShader(kind);glShaderSource(shader,source);glCompileShader(shader);assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),glGetShaderInfoLog(shader));return shader;}
        public void close(){glUseProgram(0);glDeleteProgram(program);}
    }
    private static int primitives(Runnable draw){int query=glGenQueries();try{glBeginQuery(GL_PRIMITIVES_GENERATED,query);try{draw.run();}finally{glEndQuery(GL_PRIMITIVES_GENERATED);}return glGetQueryObjectui(query,GL_QUERY_RESULT);}finally{glDeleteQueries(query);}}
    @Test void actualIndexSubrangesUseByteOffsetsAndRejectEveryOutOfBoundsRange(){
        try(var context=new EglContext();var payload=new Payload(3);var program=new DrawProgram()){
            var budget=new NativeVisualBudget();var ops=new Tracking();var s=upload(budget,ops,payload);s.bind();assertEquals(2,primitives(()->s.drawRange(6,6)));assertEquals(List.of("6:24"),ops.draws);assertEquals(1,s.drawCalls());
            for(int[] range:new int[][]{{0,0},{6,-1},{3,0},{6,1},{6,18},{12,12},{Integer.MAX_VALUE,0},{6,Integer.MAX_VALUE}})assertThrows(IllegalArgumentException.class,()->s.drawRange(range[0],range[1]));assertEquals(List.of("6:24"),ops.draws);assertEquals(1,s.drawCalls());s.close();assertEquals(0,budget.evidence().bytes());
            System.out.println("ACTUAL_GL_NATIVE_INDEX_RANGE indices=6 firstIndex=6 byteOffset=24 primitives=2 invalidRangesDrawNothing=true");
        }
    }
    @Test void actualMaskedMemberDrawsCoalesceAndNeverSubmitUnselectedQuads()throws Exception{
        try(var context=new EglContext();var payload=new Payload(5);var program=new DrawProgram()){
            var budget=new NativeVisualBudget();var ops=new Tracking();var storage=upload(budget,ops,payload);
            var piece=NativeVisualPublicationIndependentTest.part(0,5,true,NativeVisualPublicationIndependentTest.HASH,
                new NativeVisualCpuPiece.MemberRange(0,0,24576,0,1),new NativeVisualCpuPiece.MemberRange(1,0,24576,1,1),new NativeVisualCpuPiece.MemberRange(2,0,24576,2,1),new NativeVisualCpuPiece.MemberRange(3,0,24576,3,0),new NativeVisualCpuPiece.MemberRange(4,0,24576,3,1),new NativeVisualCpuPiece.MemberRange(5,0,24576,4,1)).physicalOwner();
            NativeVisualPublicationIndependentTest.set(piece,"indexCount",30);NativeVisualPublicationIndependentTest.set(piece,"storage",storage);piece.bind();
            assertEquals(4,primitives(()->piece.draw((1L<<1)|(1L<<2))));assertEquals(List.of("12:24"),ops.draws);assertEquals(0,piece.drawEvidence().fullDrawCalls());assertEquals(1,piece.drawEvidence().rangeDrawCalls());
            ops.draws.clear();assertEquals(6,primitives(()->piece.draw((1L<<0)|(1L<<2)|(1L<<4))));assertEquals(List.of("6:0","12:48"),ops.draws);assertEquals(3,piece.drawEvidence().rangeDrawCalls());
            ops.draws.clear();assertEquals(10,primitives(()->piece.draw(-1L)));assertEquals(List.of("30:0"),ops.draws);assertEquals(1,piece.drawEvidence().fullDrawCalls());
            ops.draws.clear();assertEquals(0,primitives(()->piece.draw(0)));piece.draw(1L<<63);assertTrue(ops.draws.isEmpty());assertEquals(4,storage.drawCalls());
            ops.failPhase="draw.elements";assertThrows(StrictGlErrors.Failure.class,()->piece.draw(1L));assertEquals(4,piece.drawEvidence().rangeDrawCalls(),"Actual draw issued before its diagnostic fault must remain counted");assertEquals(5,storage.drawCalls());piece.close();assertTrue(piece.closeComplete());assertEquals(0,budget.evidence().bytes());
            System.out.println("ACTUAL_GL_NATIVE_MEMBER_MASK contiguousRangesCoalesced=true emptyRangesSkipped=true fullPieceOneDraw=true excludedMembersSubmitted=false issuedFaultDrawCounted=true");
        }
    }
    @Test void reservationAdmissionPrecedesAnyPhysicalAllocationAndIsGlobal(){
        var budget=new NativeVisualBudget();var held=new ArrayList<NativeVisualBudget.Reservation>();for(int i=0;i<1536;i++)held.add(budget.reserve(208,24));
        var ops=new Tracking();var s=new NativeVisualGpuStorage(budget,ops);assertThrows(IllegalStateException.class,()->s.reserve(208,24));assertArrayEquals(new int[3],ops.names);assertEquals(1536,budget.evidence().pieces());s.close();held.forEach(NativeVisualBudget.Reservation::releaseAfterPhysicalClose);assertEquals(0,budget.evidence().bytes());
    }
    @Test void secondThreadCannotReleaseAReservationOrTouchPhysicalStorage()throws Exception{
        var budget=new NativeVisualBudget();var s=new NativeVisualGpuStorage(budget,new Tracking());s.reserve(208,24);var failure=new AtomicReference<Throwable>();Thread other=new Thread(()->{try{s.close();}catch(Throwable error){failure.set(error);}});other.start();other.join();assertInstanceOf(IllegalStateException.class,failure.get());assertEquals(1,budget.evidence().pieces());s.close();assertEquals(0,budget.evidence().pieces());
    }
    private interface Egl extends Library {
        Egl API=Native.load("EGL",Egl.class);
        Pointer eglGetPlatformDisplay(int p,Pointer d,Pointer a);boolean eglInitialize(Pointer d,int[] major,int[] minor);boolean eglBindAPI(int api);boolean eglChooseConfig(Pointer d,int[] attrs,Pointer[] c,int capacity,int[] count);Pointer eglCreatePbufferSurface(Pointer d,Pointer c,int[] attrs);Pointer eglCreateContext(Pointer d,Pointer c,Pointer shared,int[] attrs);boolean eglMakeCurrent(Pointer d,Pointer draw,Pointer read,Pointer c);Pointer eglGetCurrentContext();boolean eglDestroyContext(Pointer d,Pointer c);boolean eglDestroySurface(Pointer d,Pointer s);boolean eglTerminate(Pointer d);boolean eglReleaseThread();int eglGetError();
    }
    private static final class EglContext implements AutoCloseable {
        final Egl egl=Egl.API;Pointer display,surface,context;boolean initialized,current;
        EglContext(){assertNull(egl.eglGetCurrentContext());try{display=egl.eglGetPlatformDisplay(0x31DD,null,null);assertNotNull(display);require(egl.eglInitialize(display,new int[1],new int[1]));initialized=true;require(egl.eglBindAPI(0x30A2));Pointer[] configs=new Pointer[1];int[] count=new int[1];require(egl.eglChooseConfig(display,new int[]{0x3033,1,0x3040,8,0x3024,8,0x3023,8,0x3022,8,0x3038},configs,1,count));assertEquals(1,count[0]);surface=egl.eglCreatePbufferSurface(display,configs[0],new int[]{0x3057,16,0x3056,16,0x3038});assertNotNull(surface);context=egl.eglCreateContext(display,configs[0],null,new int[]{0x3098,4,0x30FB,5,0x30FD,1,0x3038});assertNotNull(context);require(egl.eglMakeCurrent(display,surface,surface,context));current=true;assertTrue(GL.createCapabilities().OpenGL45);StrictGlErrors.check("independent.native.context.entry");System.out.println("ACTUAL_GL_CONTEXT "+glGetString(GL_VERSION)+" renderer="+glGetString(GL_RENDERER));}catch(RuntimeException|Error failure){try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void require(boolean okay){if(!okay)throw new AssertionError("EGL error "+Integer.toHexString(egl.eglGetError()));}
        public void close(){Throwable f=null;if(current){try{StrictGlErrors.check("independent.native.context.exit");}catch(Throwable error){f=error;}GL.setCapabilities(null);require(egl.eglMakeCurrent(display,null,null,null));current=false;}if(context!=null){require(egl.eglDestroyContext(display,context));context=null;}if(surface!=null){require(egl.eglDestroySurface(display,surface));surface=null;}if(initialized){require(egl.eglTerminate(display));initialized=false;}require(egl.eglReleaseThread());if(f instanceof RuntimeException error)throw error;if(f instanceof Error error)throw error;}
    }
}
