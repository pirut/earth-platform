package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL33C.*;

/** Diagnostic manually-authored quad using the actual native BLOCK VBO and solid terrain shader.
 * Not a baked-model or gameplay resource. Caller retains this object before initialize.
 */
public final class SceneNativeDepthFixture implements AutoCloseable {
    private ByteBufferBuilder storage;
    private MeshData cpu;
    private VertexBuffer buffer;
    private int vao,vbo;
    private DrawState pendingState;
    private ShaderUniforms pendingUniforms;
    private ShaderInstance pendingShader;
    private boolean closed;
    public void initialize(float halfWidth,float halfHeight,float z){
        RenderSystem.assertOnRenderThread();if(storage!=null||buffer!=null||closed)throw new IllegalStateException("Native fixture initialization state");
        storage=new ByteBufferBuilder(256);
        var builder=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.BLOCK);
        for(float[] p:new float[][]{{-halfWidth,-halfHeight,z},{halfWidth,-halfHeight,z},{halfWidth,halfHeight,z},{-halfWidth,halfHeight,z}})
            builder.addVertex(p[0],p[1],p[2]).setColor(0,255,0,255).setUv(0,0).setUv2(240,240).setNormal(0,0,1);
        cpu=builder.buildOrThrow();buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();vao=glGetInteger(GL_VERTEX_ARRAY_BINDING);
        try{var consumed=cpu;cpu=null;buffer.upload(consumed);vbo=glGetInteger(GL_ARRAY_BUFFER_BINDING);}
        finally{VertexBuffer.unbind();}
        storage.close();storage=null;
    }
    public void draw(Matrix4f modelView,Matrix4f projection,int whiteTexture,int whiteLight){
        RenderSystem.assertOnRenderThread();if(closed||buffer==null||pendingState!=null)throw new IllegalStateException("Native fixture unavailable");
        pendingState=new DrawState();pendingShader=Objects.requireNonNull(GameRenderer.getRendertypeSolidShader());pendingUniforms=new ShaderUniforms(pendingShader);
        Throwable primary=null;
        try{
            RenderSystem.disableBlend();RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL_LEQUAL);RenderSystem.depthMask(true);RenderSystem.disableCull();RenderSystem.colorMask(true,true,true,true);
            RenderSystem.setShaderColor(1,1,1,1);RenderSystem.setShaderFogStart(1_000_000);RenderSystem.setShaderFogEnd(2_000_000);
            RenderSystem.setShaderTexture(0,whiteTexture);RenderSystem.setShaderTexture(2,whiteLight);RenderSystem.setShader(()->pendingShader);
            clearChunkOffset(pendingShader.CHUNK_OFFSET);
            buffer.bind();buffer.drawWithShader(modelView,projection,pendingShader);
        }catch(RuntimeException|Error e){primary=e;throw e;}finally{
            try{restorePending();}catch(RuntimeException|Error cleanup){if(primary==null)throw cleanup;if(primary!=cleanup)primary.addSuppressed(cleanup);}
        }
    }
    /** ChunkOffset is a float vec3; integer literals select an incompatible Uniform buffer. */
    static void clearChunkOffset(Uniform uniform){if(uniform!=null)uniform.set(0.0f,0.0f,0.0f);}
    private void restorePending(){
        Throwable failure=null;
        if(pendingShader!=null)try{pendingShader.clear();if(pendingUniforms!=null)pendingUniforms.restore(pendingShader);
            if(pendingState!=null)for(int i=0;i<pendingState.shaderTextures.length;i++)pendingShader.setSampler("Sampler"+i,pendingState.shaderTextures[i]);
            pendingUniforms=null;pendingShader=null;
        }catch(RuntimeException|Error e){failure=e;}
        if(pendingState!=null)try{pendingState.restore();if(pendingShader==null)pendingState=null;}catch(RuntimeException|Error e){if(failure==null)failure=e;else if(failure!=e)failure.addSuppressed(e);}
        if(failure instanceof Error e)throw e;if(failure instanceof RuntimeException e)throw e;
    }
    public boolean deleted(){return closed&&buffer==null&&cpu==null&&storage==null&&pendingState==null&&pendingShader==null&&(vao==0||!glIsVertexArray(vao))&&(vbo==0||!glIsBuffer(vbo));}
    public void close(){
        RenderSystem.assertOnRenderThread();closed=true;Throwable failure=null;
        try{restorePending();}catch(RuntimeException|Error e){failure=e;}
        if(cpu!=null)try{cpu.close();cpu=null;}catch(RuntimeException|Error e){if(failure==null)failure=e;else if(failure!=e)failure.addSuppressed(e);}
        if(storage!=null)try{storage.close();storage=null;}catch(RuntimeException|Error e){if(failure==null)failure=e;else if(failure!=e)failure.addSuppressed(e);}
        if(buffer!=null)try{buffer.close();if(!buffer.isInvalid())throw new IllegalStateException("Native VBO cleanup incomplete");buffer=null;}catch(RuntimeException|Error e){if(failure==null)failure=e;else if(failure!=e)failure.addSuppressed(e);}
        if(failure instanceof Error e)throw e;if(failure instanceof RuntimeException e)throw e;
        if(!deleted())throw new IllegalStateException("Native fixture still owns resources");
    }
    private static class BufferBindings {
        final int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        void restore() {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        }
    }

    /** Restore both actual GL state and RenderSystem/GlStateManager's cached representation. */
    public static final class DrawState extends BufferBindings {
        final int program = glGetInteger(GL_CURRENT_PROGRAM);
        final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        final int depthFunction = glGetInteger(GL_DEPTH_FUNC);
        final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        final boolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE);
        final boolean[] colorMask = new boolean[4];
        final ShaderInstance shader = RenderSystem.getShader();
        final int[] textures = new int[12], shaderTextures = new int[12];
        final float[] color = RenderSystem.getShaderColor().clone();
        final float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        public DrawState() {
            try (var stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                glGetBooleanv(GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
            }
            for (int i = 0; i < textures.length; i++) {
                GlStateManager._activeTexture(GL_TEXTURE0 + i);
                textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
                shaderTextures[i] = RenderSystem.getShaderTexture(i);
            }
            GlStateManager._activeTexture(activeTexture);
        }
        @Override public void restore() {
            super.restore();
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            for (int i = 0; i < textures.length; i++) {
                RenderSystem.setShaderTexture(i, shaderTextures[i]);
                GlStateManager._activeTexture(GL_TEXTURE0 + i);
                GlStateManager._bindTexture(textures[i]);
            }
            GlStateManager._activeTexture(activeTexture);
            GlStateManager._glUseProgram(program);
            RenderSystem.depthFunc(depthFunction);
            RenderSystem.depthMask(depthMask);
            RenderSystem.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        }
    }

    /** A VBO draw changes native shader uniform objects as well as RenderSystem state. */
    private static final class ShaderUniforms {
        private static final String[] NAMES = {"ModelViewMat", "ProjMat", "TextureMat", "ScreenSize", "ColorModulator",
                "Light0_Direction", "Light1_Direction", "GlintAlpha", "FogStart", "FogEnd", "FogColor", "FogShape",
                "LineWidth", "GameTime", "ChunkOffset"};
        private final Map<Uniform, float[]> floats = new LinkedHashMap<>();
        private final Map<Uniform, int[]> ints = new LinkedHashMap<>();
        ShaderUniforms(ShaderInstance shader) {
            for (var name : NAMES) {
                Uniform uniform = shader.getUniform(name);
                if (uniform == null) continue;
                if (uniform.getType() <= 3) {
                    int[] values = new int[uniform.getCount()];
                    for (int i = 0; i < values.length; i++) values[i] = uniform.getIntBuffer().get(i);
                    ints.put(uniform, values);
                } else {
                    float[] values = new float[uniform.getCount()];
                    for (int i = 0; i < values.length; i++) values[i] = uniform.getFloatBuffer().get(i);
                    floats.put(uniform, values);
                }
            }
        }
        void restore(ShaderInstance shader) {
            GlStateManager._glUseProgram(shader.getId());
            for (var entry : floats.entrySet()) { entry.getKey().set(entry.getValue()); entry.getKey().upload(); }
            for (var entry : ints.entrySet()) {
                int[] values = entry.getValue();
                entry.getKey().setSafe(values[0], values.length > 1 ? values[1] : 0,
                        values.length > 2 ? values[2] : 0, values.length > 3 ? values[3] : 0);
                entry.getKey().upload();
            }
        }
    }
}
