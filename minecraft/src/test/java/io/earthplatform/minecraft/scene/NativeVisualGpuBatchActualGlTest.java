package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.minecraft.visual.NativePageKey;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import org.objectweb.asm.*;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL33C.*;

/** Executes the production batch callback with pinned ShaderInstance and Uniform implementations.
 * The host lifecycle, capsule provenance, and Window singleton are explicit test substitutes.
 * EGL, compiled shader programs, uniform buffers/uploads, indexed pieces and batch cleanup are real.
 * This is not a claim of installed Iris/Photon stage or baked-model/capsule parity.
 */
public final class NativeVisualGpuBatchActualGlTest {
    private static final Unsafe UNSAFE=unsafe();
    private static final NativePageKey KEY=new NativePageKey(0,0,0);
    private static final List<Spec> SPECS=List.of(
        new Spec("ModelViewMat","matrix4x4",16),new Spec("ProjMat","matrix4x4",16),new Spec("TextureMat","matrix4x4",16),
        new Spec("ScreenSize","float",2),new Spec("ColorModulator","float",4),new Spec("Light0_Direction","float",3),
        new Spec("Light1_Direction","float",3),new Spec("GlintAlpha","float",1),new Spec("FogStart","float",1),
        new Spec("FogEnd","float",1),new Spec("FogColor","float",4),new Spec("FogShape","int",1),
        new Spec("LineWidth","float",1),new Spec("GameTime","float",1),new Spec("ChunkOffset","float",3));
    private record Spec(String name,String type,int count){}
    private static Unsafe unsafe(){try{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return(Unsafe)f.get(null);}catch(Exception e){throw new AssertionError(e);}}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void set(Object target,String name,Object value)throws Exception{field(target.getClass(),name).set(target,value);}

    @Test void productionChunkOffsetInstructionUsesRealPinnedFloatStorage()throws Exception{
        var descriptors=new ArrayList<String>();
        try(var stream=NativeVisualGpuBatch.class.getResourceAsStream("NativeVisualGpuBatch.class")){
            assertNotNull(stream);new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){
                    return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean itf){
                        if(owner.equals("com/mojang/blaze3d/shaders/Uniform")&&name.equals("set"))descriptors.add(descriptor);
                    }};
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        assertEquals(List.of("(FFF)V"),descriptors,"The actual callback must select the float overload");
        try(var u=new Uniform("ChunkOffset",Uniform.UT_FLOAT3,3,null)){
            assertNull(u.getIntBuffer());u.set(7.5f,-2.25f,19.0f);
            // Resolve and execute precisely the descriptor found in the production bytecode.
            var methodType=java.lang.invoke.MethodType.fromMethodDescriptorString(descriptors.getFirst(),Uniform.class.getClassLoader());
            Uniform.class.getMethod("set",methodType.parameterArray()).invoke(u,0.0f,0.0f,0.0f);
            assertArrayEquals(new float[3],floats(u));
        }
    }

    @Test void actualFullAndMaskedCallbacksRestoreUniformsAndBindingsInMainAndShadowContexts()throws Exception{
        try(var context=new EglContext();var globals=new Globals()){
            for(boolean shadow:new boolean[]{false,true})try(var f=new Fixture(shadow,null)){
                int full=primitives(()->f.draw(false));assertEquals(4,full);
                assertEquals(1,f.batch.evidence().fullDrawCalls());assertEquals(1,f.batch.evidence().submittedPieces());
                f.assertRestored();
                int masked=primitives(()->f.draw(true));assertEquals(2,masked);
                assertEquals(1,f.batch.evidence().rangeDrawCalls());assertEquals(2,f.batch.evidence().passes());
                assertEquals(2,f.shader.defaults);assertEquals(2,f.shader.applies);assertEquals(4,f.shader.clears);
                assertEquals(2,f.draws);assertEquals(2,f.restores);f.assertRestored();
                System.out.println("ACTUAL_GL_NATIVE_BATCH stage="+(shadow?"shadow":"main")+" realUniforms="+SPECS.size()+" fullPrimitives="+full+" maskedPrimitives="+masked+" actualDefaultApplyClear=true restored=true installedIrisPhoton=NOT_RUN");
            }
        }
    }

    @Test void actualCallbackFailuresRestoreAndFenceEveryStageAndRetainFailedRestoration()throws Exception{
        try(var context=new EglContext();var globals=new Globals()){
            for(boolean shadow:new boolean[]{false,true})for(String phase:List.of("setup","setup-gl","apply","apply-gl","draw-gl","clear","clear-gl","authority","restore","restore-gl","cleanup-clear"))try(var f=new Fixture(shadow,phase)){
                Throwable error=assertThrows(RuntimeException.class,()->f.draw(false));
                if(phase.endsWith("-gl")){var gl=assertInstanceOf(StrictGlErrors.Failure.class,error);assertEquals(GL_INVALID_ENUM,gl.errorCode());}
                else assertSame(f.marker,error);
                assertTrue(f.batch.evidence().failed());assertThrows(IllegalStateException.class,()->f.draw(false));
                if(phase.equals("restore")||phase.equals("restore-gl")||phase.equals("cleanup-clear")){
                    assertTrue(f.batch.evidence().pendingRestore());f.batch.close();assertFalse(f.batch.evidence().pendingRestore());assertTrue(f.batch.closeComplete());assertEquals(phase.equals("restore")?2:1,f.hostWrites);
                }else{assertFalse(f.batch.evidence().pendingRestore());assertEquals(1,f.restores);}
                f.assertRestored();
                if(phase.equals("draw-gl")||phase.startsWith("clear")||phase.equals("authority")||phase.startsWith("restore")||phase.equals("cleanup-clear"))assertEquals(1,f.batch.evidence().drawCalls());
                else assertEquals(0,f.batch.evidence().drawCalls());
                System.out.println("ACTUAL_GL_NATIVE_BATCH_FAILURE stage="+(shadow?"shadow":"main")+" phase="+phase+" originalFailure=true restored=true fenced=true");
            }
        }
    }

    @Test void negativeControlRepeatedInnerRestoreCorruptsAlreadyRestoredHostSnapshot()throws Exception{
        try(var context=new EglContext();var globals=new Globals()){
            for(boolean shadow:new boolean[]{false,true})try(var f=new Fixture(shadow,"cleanup-clear")){
                assertSame(f.marker,assertThrows(RuntimeException.class,()->f.draw(false)));
                assertTrue(f.batch.evidence().pendingRestore());assertEquals(1,f.hostWrites);f.assertRestored();
                // Test-only negative control: remove only the completed-host fence after a real
                // inner cleanup exception. The next call executes the original retry sequence.
                Object pending=field(NativeVisualGpuBatch.class,"pending").get(f.batch);
                Runnable restore=(Runnable)field(SceneGlRestoreOwner.class,"restore").get(pending);
                Object scope=null;for(Field capture:restore.getClass().getDeclaredFields()){
                    if(capture.getType().getName().equals(NativeVisualGpuBatch.class.getName()+"$Scope")){
                        capture.setAccessible(true);scope=capture.get(restore);break;
                    }
                }
                assertNotNull(scope);Field completed=field(scope.getClass(),"hostRestored");assertEquals(true,completed.get(scope));completed.set(scope,false);
                f.batch.close();assertTrue(f.batch.closeComplete());assertEquals(2,f.restores);assertEquals(1,f.hostWrites);
                assertTrue(samplers(f.shader).containsKey("Sampler7"),"Broken retry resurrects a key the host had removed");
                assertArrayEquals(f.savedFloats.get(f.shader.CHUNK_OFFSET),gpuFloats(f.shader,f.shader.CHUNK_OFFSET));
                assertFalse(Arrays.equals(f.savedGpuFloats.get(f.shader.CHUNK_OFFSET),gpuFloats(f.shader,f.shader.CHUNK_OFFSET)),"Broken retry overwrites the independently retained GPU value");
                assertEquals(false,field(Uniform.class,"dirty").get(f.shader.CHUNK_OFFSET));assertTrue(f.originalChunkDirty);
                System.out.println("ACTUAL_GL_NATIVE_BATCH_NEGATIVE stage="+(shadow?"shadow":"main")+" disabledHostRestoreFence=true unexpectedSamplerKey=true pendingCpuUploaded=true dirtyStateLost=true originalRetryDefectReproduced=true");
            }
        }
    }

    @Test void unrelatedEntryGlErrorNeverRunsHostSetupOrDraw()throws Exception{
        try(var context=new EglContext();var globals=new Globals()){
            for(boolean shadow:new boolean[]{false,true})try(var f=new Fixture(shadow,null)){
                glEnable(-1);var error=assertThrows(StrictGlErrors.Failure.class,()->f.draw(false));
                assertEquals("native-distance.batch.entry",error.phase());assertEquals(GL_INVALID_ENUM,error.errorCode());
                assertFalse(f.snapshotTaken);assertEquals(0,f.shader.defaults);assertEquals(0,f.shader.applies);assertEquals(0,f.draws);assertEquals(0,f.restores);
                assertFalse(f.batch.evidence().failed());assertFalse(f.batch.evidence().pendingRestore());f.assertRestored();
            }
        }
    }

    private static final class Globals implements AutoCloseable {
        final Field renderThread=field(RenderSystem.class,"renderThread"),instance=field(Minecraft.class,"instance");
        final Object priorThread=renderThread.get(null),priorMinecraft=instance.get(null);
        final ShaderInstance priorShader;final SceneMesh.DrawState priorState;final Vector3f[] lights=(Vector3f[])field(RenderSystem.class,"shaderLightDirections").get(null);
        final Vector3f light0=lights[0],light1=lights[1];
        Globals()throws Exception{
            renderThread.set(null,Thread.currentThread());priorShader=RenderSystem.getShader();
            var window=(Window)UNSAFE.allocateInstance(Window.class);window.setWidth(16);window.setHeight(16);
            var mc=(Minecraft)UNSAFE.allocateInstance(Minecraft.class);field(Minecraft.class,"window").set(mc,window);instance.set(null,mc);
            RenderSystem.setShaderLights(new Vector3f(1,2,3),new Vector3f(4,5,6));
            // Each context starts with default GL state. Empty texture names also reset MC's cache.
            for(int i=0;i<12;i++){GlStateManager._activeTexture(GL_TEXTURE0+i);GlStateManager._bindTexture(0);}
            GlStateManager._activeTexture(GL_TEXTURE0);GlStateManager._glUseProgram(0);GlStateManager._glBindVertexArray(0);GlStateManager._glBindBuffer(GL_ARRAY_BUFFER,0);priorState=new SceneMesh.DrawState();
        }
        public void close()throws Exception{priorState.restore();RenderSystem.setShader(()->priorShader);lights[0]=light0;lights[1]=light1;instance.set(null,priorMinecraft);renderThread.set(null,priorThread);}
    }

    private static final class Fixture implements AutoCloseable {
        final boolean shadow;final String fault;boolean fired;final RuntimeException marker=new IllegalStateException("injected batch phase");
        final TrackingShader shader;final NativeVisualGpuBatch batch=new NativeVisualGpuBatch();final NativeVisualBudget budget=new NativeVisualBudget();
        final NativeVisualGpuPiece piece;final NativeVisualGpuStorage storage;final Map<Uniform,float[]> savedFloats=new LinkedHashMap<>();final Map<Uniform,int[]> savedInts=new LinkedHashMap<>();final Map<Uniform,float[]> savedGpuFloats=new LinkedHashMap<>();
        final Map<String,Object> originalSamplers;final boolean originalDirty,originalChunkDirty;
        final int priorVao,priorVbo,priorTexture,priorProgram,framebuffer,renderbuffer;final int[] priorViewport={1,2,11,12};
        final Matrix4f view,projection;final SceneNativeShaderContract.ShaderPass host;int draws,restores,hostWrites,currentChecks;boolean snapshotTaken,hostRestoreDone;
        Fixture(boolean shadow,String fault)throws Exception{
            this.shadow=shadow;this.fault=fault;
            shader=new TrackingShader(this);assertEquals(Uniform.UT_FLOAT3,shader.CHUNK_OFFSET.getType());assertNull(shader.CHUNK_OFFSET.getIntBuffer());
            assertEquals(Uniform.UT_INT1,shader.FOG_SHAPE.getType());assertNull(shader.FOG_SHAPE.getFloatBuffer());
            view=new Matrix4f().translation(shadow?-.125f:.125f,0,0);projection=shadow?new Matrix4f().setOrtho(-2,2,-2,2,.1f,100):new Matrix4f().setPerspective((float)Math.toRadians(60),1,.1f,100);
            GlStateManager._glUseProgram(shader.getId());
            for(Spec spec:SPECS){Uniform u=Objects.requireNonNull(shader.getUniform(spec.name),spec.name);
                if(u.getType()<=3){u.setSafe(7,11,13,17);u.upload();savedInts.put(u,ints(u));}
                else{float[] value=new float[u.getCount()];for(int i=0;i<value.length;i++)value[i]=(i+1)*.03125f;u.set(value);u.upload();savedFloats.put(u,floats(u));savedGpuFloats.put(u,gpuFloats(shader,u));}
            }
            if("cleanup-clear".equals(fault)){shader.CHUNK_OFFSET.set(8.5f,9.25f,10.75f);savedFloats.put(shader.CHUNK_OFFSET,floats(shader.CHUNK_OFFSET));}
            originalSamplers=new HashMap<>(samplers(shader));assertFalse(originalSamplers.containsKey("Sampler7"));
            originalDirty=(boolean)field(ShaderInstance.class,"dirty").get(shader);originalChunkDirty=(boolean)field(Uniform.class,"dirty").get(shader.CHUNK_OFFSET);
            priorProgram=priorProgram();GlStateManager._glUseProgram(priorProgram);RenderSystem.setShader(()->null);
            priorVao=glGenVertexArrays();priorVbo=glGenBuffers();GlStateManager._glBindVertexArray(priorVao);GlStateManager._glBindBuffer(GL_ARRAY_BUFFER,priorVbo);glBufferData(GL_ARRAY_BUFFER,64,GL_STATIC_DRAW);
            priorTexture=glGenTextures();GlStateManager._activeTexture(GL_TEXTURE3);GlStateManager._bindTexture(priorTexture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
            RenderSystem.setShaderTexture(0,priorTexture);RenderSystem.setShaderColor(.2f,.3f,.4f,.5f);RenderSystem.setShaderFogStart(3);RenderSystem.setShaderFogEnd(97);
            RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL_LEQUAL);RenderSystem.depthMask(false);RenderSystem.disableCull();RenderSystem.disableBlend();RenderSystem.colorMask(true,false,true,false);
            glViewport(priorViewport[0],priorViewport[1],priorViewport[2],priorViewport[3]);
            framebuffer=glGenFramebuffers();renderbuffer=glGenRenderbuffers();glBindRenderbuffer(GL_RENDERBUFFER,renderbuffer);glRenderbufferStorage(GL_RENDERBUFFER,GL_RGBA8,16,16);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,renderbuffer);assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));glBindFramebuffer(GL_FRAMEBUFFER,0);
            storage=new NativeVisualGpuStorage(budget,new NativeVisualGpuStorage.Operations(){
                @Override void invalidate(){com.mojang.blaze3d.vertex.BufferUploader.invalidate();}
                @Override void bindVertexArray(int id){GlStateManager._glBindVertexArray(id);}
                @Override void bindBuffer(int target,int id){GlStateManager._glBindBuffer(target,id);}
                @Override void draw(int count,long offset){assertDuringDraw();super.draw(count,offset);draws++;if("draw-gl".equals(fault)&&!fired){fired=true;glEnable(-1);}}
            });
            ByteBuffer vertices=MemoryUtil.memAlloc(8*12);IntBuffer indices=MemoryUtil.memAllocInt(12);
            try{for(int q=0;q<2;q++)for(float[] p:new float[][]{{-.5f,-.5f,0},{.5f,-.5f,0},{.5f,.5f,0},{-.5f,.5f,0}})vertices.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]);vertices.flip();indices.put(new int[]{0,1,2,2,3,0,4,5,6,6,7,4}).flip();storage.reserve(vertices.remaining(),indices.remaining()*4);storage.upload(vertices,indices,DefaultVertexFormat.POSITION::setupBufferState);}finally{MemoryUtil.memFree(vertices);MemoryUtil.memFree(indices);}
            piece=NativeVisualPublicationIndependentTest.part(0,2,true,NativeVisualPublicationIndependentTest.HASH,new NativeVisualCpuPiece.MemberRange(0,0,24576,0,1),new NativeVisualCpuPiece.MemberRange(1,0,24576,1,1)).physicalOwner();
            set(piece,"storage",storage);set(piece,"indexCount",12);set(piece,"key",KEY);var completionConstructor=NativeVisualGpuPiece.Completion.class.getDeclaredConstructors()[0];completionConstructor.setAccessible(true);set(piece,"completion",completionConstructor.newInstance(piece));
            host=new SceneNativeShaderContract.ShaderPass(){
                public SceneNativeShaderContract.Capture capture(){return SceneNativeShaderContract.defaultPath();}
                public RenderType layer(){return RenderType.solid();}public ShaderInstance shader(){return shader;}public boolean isCurrent(){return true;}
                public Runnable captureState(){snapshotTaken=true;hostRestoreDone=false;return Fixture.this::restoreHost;}
                public void setup(){assertTrue(snapshotTaken);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);glViewport(0,0,16,16);RenderSystem.setShaderColor(.7f,.6f,.5f,1);RenderSystem.depthMask(true);RenderSystem.colorMask(true,true,true,true);trip("setup");}
            };
            StrictGlErrors.check("batch.fixture.ready");
        }
        void restoreHost(){
            restores++;if(hostRestoreDone)return;hostWrites++;
            GlStateManager._glUseProgram(shader.getId());glUniform3fv(shader.CHUNK_OFFSET.getLocation(),savedGpuFloats.get(shader.CHUNK_OFFSET));
            try{samplers(shader).clear();samplers(shader).putAll(originalSamplers);field(ShaderInstance.class,"dirty").set(shader,originalDirty);field(Uniform.class,"dirty").set(shader.CHUNK_OFFSET,originalChunkDirty);}catch(Exception e){throw new AssertionError(e);}
            glBindFramebuffer(GL_FRAMEBUFFER,0);glViewport(priorViewport[0],priorViewport[1],priorViewport[2],priorViewport[3]);
            GlStateManager._glUseProgram(priorProgram);trip("restore");hostRestoreDone=true;
        }
        void trip(String phase){if(fired)return;if(phase.equals(fault)){fired=true;throw marker;}if((phase+"-gl").equals(fault)){fired=true;glEnable(-1);}}
        void draw(boolean masked){currentChecks=0;Runnable authority=()->{if(++currentChecks==2)trip("authority");};if(masked)batch.draw(host,List.of(piece),Map.of(KEY,1L),view,projection,.25,.125,3,authority);else batch.draw(host,List.of(piece),view,projection,.25,.125,3,authority);}
        void assertDuringDraw(){
            assertEquals(shader.getId(),glGetInteger(GL_CURRENT_PROGRAM));assertEquals(framebuffer,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
            assertArrayEquals(new float[3],gpuFloats(shader,shader.CHUNK_OFFSET));assertArrayEquals(new float[3],floats(shader.CHUNK_OFFSET));
            assertArrayEquals(new Matrix4f(view).translate(-.25f,-.125f,-3).get(new float[16]),gpuFloats(shader,shader.MODEL_VIEW_MATRIX));assertArrayEquals(projection.get(new float[16]),gpuFloats(shader,shader.PROJECTION_MATRIX));
            assertArrayEquals(new float[]{16,16},gpuFloats(shader,shader.SCREEN_SIZE));assertEquals(RenderSystem.getShaderFogShape().getIndex(),gpuInts(shader,shader.FOG_SHAPE)[0]);
        }
        void assertRestored(){
            for(var e:savedFloats.entrySet()){assertArrayEquals(e.getValue(),floats(e.getKey()),e.getKey().getName()+" CPU");assertArrayEquals(savedGpuFloats.get(e.getKey()),gpuFloats(shader,e.getKey()),e.getKey().getName()+" GPU");}
            for(var e:savedInts.entrySet()){assertArrayEquals(e.getValue(),ints(e.getKey()));assertArrayEquals(e.getValue(),gpuInts(shader,e.getKey()));}
            assertEquals(priorProgram,glGetInteger(GL_CURRENT_PROGRAM));assertEquals(priorVao,glGetInteger(GL_VERTEX_ARRAY_BINDING));assertEquals(priorVbo,glGetInteger(GL_ARRAY_BUFFER_BINDING));
            assertEquals(GL_TEXTURE3,glGetInteger(GL_ACTIVE_TEXTURE));assertEquals(priorTexture,glGetInteger(GL_TEXTURE_BINDING_2D));assertEquals(priorTexture,RenderSystem.getShaderTexture(0));assertNull(RenderSystem.getShader());
            try{assertEquals(originalSamplers,samplers(shader));assertEquals(originalDirty,field(ShaderInstance.class,"dirty").get(shader));assertEquals(originalChunkDirty,field(Uniform.class,"dirty").get(shader.CHUNK_OFFSET));}catch(Exception e){throw new AssertionError(e);}
            assertEquals(0,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));assertEquals(0,glGetInteger(GL_READ_FRAMEBUFFER_BINDING));int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);assertArrayEquals(priorViewport,viewport);
            assertEquals(GL_LEQUAL,glGetInteger(GL_DEPTH_FUNC));assertFalse(glGetBoolean(GL_DEPTH_WRITEMASK));assertTrue(glIsEnabled(GL_DEPTH_TEST));assertFalse(glIsEnabled(GL_CULL_FACE));assertFalse(glIsEnabled(GL_BLEND));int[] mask=new int[4];glGetIntegerv(GL_COLOR_WRITEMASK,mask);assertArrayEquals(new int[]{1,0,1,0},mask);
            assertArrayEquals(new float[]{.2f,.3f,.4f,.5f},RenderSystem.getShaderColor());assertEquals(3,RenderSystem.getShaderFogStart());assertEquals(97,RenderSystem.getShaderFogEnd());StrictGlErrors.check("batch.fixture.restored");
        }
        public void close(){batch.close();piece.close();assertTrue(piece.closeComplete());assertEquals(0,budget.evidence().bytes());shader.clear();RenderSystem.setShader(()->null);GlStateManager._glBindVertexArray(0);GlStateManager._glBindBuffer(GL_ARRAY_BUFFER,0);glDeleteVertexArrays(priorVao);glDeleteBuffers(priorVbo);GlStateManager._activeTexture(GL_TEXTURE3);GlStateManager._bindTexture(0);glDeleteTextures(priorTexture);RenderSystem.setShaderTexture(0,0);GlStateManager._activeTexture(GL_TEXTURE0);glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(framebuffer);glBindRenderbuffer(GL_RENDERBUFFER,0);glDeleteRenderbuffers(renderbuffer);shader.close();glDeleteProgram(priorProgram);StrictGlErrors.check("batch.fixture.closed");}
    }

    private static final class TrackingShader extends ShaderInstance {
        final Fixture fixture;int defaults,applies,clears;
        TrackingShader(Fixture f)throws Exception{super(resources(),ResourceLocation.fromNamespaceAndPath("earthplatform","batch_actual_gl"),DefaultVertexFormat.POSITION);fixture=f;}
        @Override public void setDefaultUniforms(VertexFormat.Mode mode,Matrix4f view,Matrix4f projection,Window window){super.setDefaultUniforms(mode,view,projection,window);defaults++;}
        @Override public void apply(){super.apply();applies++;fixture.trip("apply");}
        @Override public void clear(){super.clear();clears++;if(clears==2)fixture.trip("cleanup-clear");fixture.trip("clear");}
    }
    private static int priorProgram(){
        int vertex=compile(GL_VERTEX_SHADER,"#version 330 core\nvoid main(){gl_Position=vec4(0,0,0,1);}");
        int fragment=compile(GL_FRAGMENT_SHADER,"#version 330 core\nout vec4 color;void main(){color=vec4(1);}");
        int program=glCreateProgram();try{glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),glGetProgramInfoLog(program));return program;}finally{glDeleteShader(vertex);glDeleteShader(fragment);}
    }
    private static int compile(int type,String source){int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),glGetShaderInfoLog(shader));return shader;}
    private static ResourceProvider resources(){
        var json=new StringBuilder("{\"vertex\":\"earthplatform:batch_actual_gl\",\"fragment\":\"earthplatform:batch_actual_gl\",\"samplers\":[],\"uniforms\":[");
        for(Spec s:SPECS){if(json.charAt(json.length()-1)!='[')json.append(',');json.append("{\"name\":\"").append(s.name).append("\",\"type\":\"").append(s.type).append("\",\"count\":").append(s.count).append(",\"values\":[1]}");}json.append("]}");
        String vertex="#version 330 core\nin vec3 Position;uniform mat4 ModelViewMat;uniform mat4 ProjMat;uniform mat4 TextureMat;uniform vec3 ChunkOffset;void main(){gl_Position=ProjMat*ModelViewMat*vec4(Position+ChunkOffset,1);gl_Position.x+=TextureMat[0][0]*0.000001;}";
        String fragment="#version 330 core\nout vec4 color;uniform vec2 ScreenSize;uniform vec4 ColorModulator;uniform vec3 Light0_Direction;uniform vec3 Light1_Direction;uniform float GlintAlpha;uniform float FogStart;uniform float FogEnd;uniform vec4 FogColor;uniform int FogShape;uniform float LineWidth;uniform float GameTime;void main(){float s=ScreenSize.x+ScreenSize.y+dot(Light0_Direction,vec3(1))+dot(Light1_Direction,vec3(1))+GlintAlpha+FogStart+FogEnd+float(FogShape)+LineWidth+GameTime;color=ColorModulator+FogColor*0.000001+vec4(s*0.000001);}";
        var files=Map.of("shaders/core/batch_actual_gl.json",json.toString(),"shaders/core/batch_actual_gl.vsh",vertex,"shaders/core/batch_actual_gl.fsh",fragment);
        return location->{String source=files.get(location.getPath());return source==null?Optional.empty():Optional.of(new Resource(null,()->new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))){@Override public String sourcePackId(){return "native-batch-actual-gl-test";}});};
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> samplers(ShaderInstance shader)throws Exception{return(Map<String,Object>)field(ShaderInstance.class,"samplerMap").get(shader);}
    private static float[] floats(Uniform u){float[] result=new float[u.getCount()];var buffer=u.getFloatBuffer().duplicate().clear();for(int i=0;i<result.length;i++)result[i]=buffer.get(i);return result;}
    private static int[] ints(Uniform u){int[] result=new int[u.getCount()];var buffer=u.getIntBuffer().duplicate().clear();for(int i=0;i<result.length;i++)result[i]=buffer.get(i);return result;}
    private static float[] gpuFloats(ShaderInstance shader,Uniform u){float[] result=new float[u.getCount()];glGetUniformfv(shader.getId(),u.getLocation(),result);return result;}
    private static int[] gpuInts(ShaderInstance shader,Uniform u){int[] result=new int[u.getCount()];glGetUniformiv(shader.getId(),u.getLocation(),result);return result;}
    private static int primitives(Runnable draw){int query=glGenQueries();try{glBeginQuery(GL_PRIMITIVES_GENERATED,query);try{draw.run();}finally{glEndQuery(GL_PRIMITIVES_GENERATED);}return glGetQueryObjectui(query,GL_QUERY_RESULT);}finally{glDeleteQueries(query);}}
    private interface Egl extends Library {
        Egl API=Native.load("EGL",Egl.class);
        Pointer eglGetPlatformDisplay(int platform,Pointer device,Pointer attributes);boolean eglInitialize(Pointer display,int[] major,int[] minor);boolean eglBindAPI(int api);boolean eglChooseConfig(Pointer display,int[] attrs,Pointer[] configs,int capacity,int[] count);Pointer eglCreatePbufferSurface(Pointer display,Pointer config,int[] attrs);Pointer eglCreateContext(Pointer display,Pointer config,Pointer shared,int[] attrs);boolean eglMakeCurrent(Pointer display,Pointer draw,Pointer read,Pointer context);Pointer eglGetCurrentContext();boolean eglDestroyContext(Pointer display,Pointer context);boolean eglDestroySurface(Pointer display,Pointer surface);boolean eglTerminate(Pointer display);boolean eglReleaseThread();int eglGetError();
    }
    private static final class EglContext implements AutoCloseable {
        final Egl egl=Egl.API;Pointer display,surface,context;boolean initialized,current;
        EglContext(){assertNull(egl.eglGetCurrentContext());try{display=egl.eglGetPlatformDisplay(0x31DD,null,null);assertNotNull(display);require(egl.eglInitialize(display,new int[1],new int[1]));initialized=true;require(egl.eglBindAPI(0x30A2));Pointer[] configs=new Pointer[1];int[] count=new int[1];require(egl.eglChooseConfig(display,new int[]{0x3033,1,0x3040,8,0x3024,8,0x3023,8,0x3022,8,0x3038},configs,1,count));assertEquals(1,count[0]);surface=egl.eglCreatePbufferSurface(display,configs[0],new int[]{0x3057,16,0x3056,16,0x3038});assertNotNull(surface);context=egl.eglCreateContext(display,configs[0],null,new int[]{0x3098,4,0x30FB,5,0x30FD,1,0x3038});assertNotNull(context);require(egl.eglMakeCurrent(display,surface,surface,context));current=true;assertTrue(GL.createCapabilities().OpenGL45);StrictGlErrors.check("batch.context.entry");System.out.println("ACTUAL_GL_BATCH_CONTEXT "+glGetString(GL_VERSION)+" renderer="+glGetString(GL_RENDERER));}catch(RuntimeException|Error failure){try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}}
        void require(boolean okay){if(!okay)throw new AssertionError("EGL error "+Integer.toHexString(egl.eglGetError()));}
        public void close(){Throwable f=null;if(current){try{StrictGlErrors.check("batch.context.exit");}catch(Throwable e){f=e;}GL.setCapabilities(null);require(egl.eglMakeCurrent(display,null,null,null));current=false;}if(context!=null){require(egl.eglDestroyContext(display,context));context=null;}if(surface!=null){require(egl.eglDestroySurface(display,surface));surface=null;}if(initialized){require(egl.eglTerminate(display));initialized=false;}require(egl.eglReleaseThread());if(f instanceof RuntimeException e)throw e;if(f instanceof Error e)throw e;}
    }
}
