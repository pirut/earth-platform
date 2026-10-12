package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL33C.*;

/** Actual pinned native VertexBuffer deletion in surfaceless EGL. Iris transformation is proven by the installed GUI, not this test. */
class SurvivalNativePageParityGlTest {
    @Test void actualNativeDeleteRestoresNonzeroHostVaoVboAndEbo() throws Exception {
        try(var context=new Context();var host=new Host()) {
            var unprotected=new VertexBuffer(VertexBuffer.Usage.STATIC);
            unprotected.close();
            assertTrue(unprotected.isInvalid());
            if(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux"))
                assertEquals(0,glGetInteger(GL_ARRAY_BUFFER_BINDING),"Pinned Linux deletion actually clobbers the array binding");
            host.bind();
            var bindings=new SurvivalNativePageParityProbe.ReferenceBindings(false);
            var buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);buffer.bind();
            var close=new SurvivalNativePageParityProbe.ReferenceCleanup(buffer::close,bindings::restore,
                    ()->StrictGlErrors.check("test.delete.before"),()->StrictGlErrors.check("test.delete.after"),
                    ()->StrictGlErrors.check("test.restore.before"),()->StrictGlErrors.check("test.restore.after"));
            close.close();assertTrue(buffer.isInvalid());assertTrue(close.complete());host.assertBound();
            close.close();host.assertBound();
            System.out.println("ACTUAL_NATIVE_VERTEX_BUFFER_DELETE nonzeroHostVaoVboEboRestored=true linuxArrayClobberExercised=true");
        }
    }

    @Test void actualDriverErrorsAtEveryBoundaryRetainCleanupAndExactFirstFailure() throws Exception {
        try(var context=new Context();var host=new Host()) {
            for(String phase:List.of("delete.before","delete.after","restore.before","restore.after")) {
                host.bind();var bindings=new SurvivalNativePageParityProbe.ReferenceBindings(false);
                var buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);buffer.bind();
                var fired=new boolean[1];var deletes=new int[1];
                java.util.function.Consumer<String> check=label->{
                    if(label.equals(phase)&&!fired[0]){fired[0]=true;glEnable(-1);}
                    StrictGlErrors.check("test."+label);
                };
                var close=new SurvivalNativePageParityProbe.ReferenceCleanup(()->{deletes[0]++;buffer.close();},bindings::restore,
                        ()->check.accept("delete.before"),()->check.accept("delete.after"),
                        ()->check.accept("restore.before"),()->check.accept("restore.after"));
                var error=assertThrows(StrictGlErrors.Failure.class,close::close);
                assertEquals("test."+phase,error.phase());assertEquals(GL_INVALID_ENUM,error.errorCode());
                assertFalse(close.complete());assertTrue(buffer.isInvalid());host.assertBound();
                close.close();assertTrue(close.complete());assertEquals(1,deletes[0],"A clean retry must not repeat successful native deletion");host.assertBound();
                StrictGlErrors.check("test.retry.exit");
            }
        }
    }

    @Test void failedPhysicalCloseRetriesWithoutLosingRestorationAndPreservesPrimary() {
        var order=new ArrayList<String>();var fault=new IllegalStateException("partial native close");
        var fail=new boolean[]{true};var restoreFault=new IllegalStateException("restore failure");
        var close=new SurvivalNativePageParityProbe.ReferenceCleanup(()->{order.add("delete");if(fail[0])throw fault;},
                ()->{order.add("restore");if(fail[0])throw restoreFault;},()->order.add("entry"),()->order.add("after"),
                ()->order.add("restore.entry"),()->order.add("restore.exit"));
        assertSame(fault,assertThrows(IllegalStateException.class,close::close));
        assertArrayEquals(new Throwable[]{restoreFault},fault.getSuppressed());assertFalse(close.complete());
        assertEquals(List.of("entry","delete","after","restore.entry","restore","restore.exit"),order);
        fail[0]=false;close.close();assertTrue(close.complete());assertEquals(12,order.size());
    }

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
