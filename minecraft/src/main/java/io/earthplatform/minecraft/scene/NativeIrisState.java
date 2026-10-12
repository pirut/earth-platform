package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import java.util.function.IntSupplier;
import java.util.Map;
import io.earthplatform.minecraft.mixin.NativeIrisShaderInstanceAccessor;
import io.earthplatform.minecraft.mixin.NativeIrisUniformAccessor;
import io.earthplatform.minecraft.mixin.NativeIrisBlendStateAccessor;
import io.earthplatform.minecraft.mixin.NativeIrisExtendedShaderAccessor;
import io.earthplatform.minecraft.mixin.NativeIrisSamplerBindingAccessor;
import io.earthplatform.minecraft.mixin.NativeIrisPipelineStateAccessor;
import io.earthplatform.minecraft.mixin.NativeIrisProgramSamplersAccessor;
import io.earthplatform.minecraft.mixin.NativeIrisProgramUniformsAccessor;
import io.earthplatform.minecraft.mixin.NativeIrisTextureTrackerAccessor;
import io.earthplatform.minecraft.mixin.NativeIrisTextureStateAccessor;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import net.irisshaders.iris.mixin.GlStateManagerAccessor;
import net.irisshaders.iris.pbr.TextureTracker;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL21C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40C.*;
import static org.lwjgl.opengl.GL42C.*;

/** Bounded snapshot of the binding state touched by the pinned ExtendedShader path.
 * It deliberately rejects a nested active Iris owner: arbitrary listener topology cannot
 * be recovered by re-applying somebody else's shader. No pipeline/FBO/texture is owned. */
final class NativeIrisState implements Runnable {
    private static final NativeShaderCleanupOwner CLEANUP=new NativeShaderCleanupOwner();
    private static final int MAX_TEXTURE_UNITS=64,MAX_IMAGE_UNITS=64,MAX_DRAW_BUFFERS=16;
    private static final int[] TARGETS={GL_TEXTURE_1D,GL_TEXTURE_2D,GL_TEXTURE_3D,GL_TEXTURE_CUBE_MAP,
            GL_TEXTURE_1D_ARRAY,GL_TEXTURE_2D_ARRAY,GL_TEXTURE_RECTANGLE,GL_TEXTURE_BUFFER,
            GL_TEXTURE_2D_MULTISAMPLE,GL_TEXTURE_2D_MULTISAMPLE_ARRAY,GL_TEXTURE_CUBE_MAP_ARRAY};
    private static final int[] BINDINGS={GL_TEXTURE_BINDING_1D,GL_TEXTURE_BINDING_2D,GL_TEXTURE_BINDING_3D,
            GL_TEXTURE_BINDING_CUBE_MAP,GL_TEXTURE_BINDING_1D_ARRAY,GL_TEXTURE_BINDING_2D_ARRAY,
            GL_TEXTURE_BINDING_RECTANGLE,GL_TEXTURE_BINDING_BUFFER,GL_TEXTURE_BINDING_2D_MULTISAMPLE,
            GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY,GL_TEXTURE_BINDING_CUBE_MAP_ARRAY};
    private static final int[] IMAGE_FIELDS={GL_IMAGE_BINDING_NAME,GL_IMAGE_BINDING_LEVEL,
            GL_IMAGE_BINDING_LAYERED,GL_IMAGE_BINDING_LAYER,GL_IMAGE_BINDING_ACCESS,GL_IMAGE_BINDING_FORMAT};
    private final Thread owner=Thread.currentThread();
    private final IrisRenderingPipeline pipeline;
    private final NativeIrisPipelineStateAccessor pipelineState;
    private final NativeIrisTextureTrackerAccessor tracker;
    private final ShaderInstance shader,selectedShader;
    private final NativeIrisShaderInstanceAccessor selectedState;
    private final boolean shaderDirty;
    private final SamplerValues shaderSamplers;
    private final int program,vao,arrayBuffer,activeTexture,drawFramebuffer,readFramebuffer,depthFunc;
    private final int[] viewport=new int[4],shaderTextures=new int[12];
    private final int[][] textures,images,blendFactors,colorMasks;
    private final int[] samplers,textureCache;
    private final Object[] textureStates;
    private final boolean[] touchedTextures;
    private final boolean[] blendEnabled;
    private final boolean depth,depthMask,cull,tessellation,mainBound,originalBlendEnable;
    private final BlendMode originalBlend;
    private final int normalTexture,specularTexture;
    private final float alphaTest,fogStart,fogEnd;
    private final float[] shaderColor;
    private static final String[] UNIFORM_NAMES={"ModelViewMat","ProjMat","TextureMat","ScreenSize","ColorModulator",
            "Light0_Direction","Light1_Direction","GlintAlpha","FogStart","FogEnd","FogColor","FogShape",
            "LineWidth","GameTime","ChunkOffset","ModelViewMatInverse","ProjMatInverse","NormalMat"};
    private final UniformValue[] uniforms=new UniformValue[UNIFORM_NAMES.length];
    private boolean restored;

    static Runnable capture(IrisRenderingPipeline pipeline,ShaderInstance shader){return CLEANUP.capture(()->new NativeIrisState(pipeline,shader));}
    static void retryCleanup(){CLEANUP.retry();}
    static boolean hasRetainedCleanup(){return CLEANUP.retained();}
    private NativeIrisState(IrisRenderingPipeline pipeline,ShaderInstance selectedShader) {
        RenderSystem.assertOnRenderThread();
        if(!GL.getCapabilities().OpenGL40)throw new IllegalStateException("Native Iris state scope requires OpenGL 4.0 indexed blend state");
        GlErrors.check("Pre-existing GL errors before native state capture",org.lwjgl.opengl.GL11C::glGetError);
        if(NativeIrisProgramSamplersAccessor.earthplatform$active()!=null
                ||NativeIrisProgramUniformsAccessor.earthplatform$active()!=null||BlendModeStorage.isBlendLocked()
                ||DepthColorStorage.isDepthColorLocked())
            throw new IllegalStateException("Native hook is nested inside an active Iris state owner");
        this.pipeline=pipeline;this.selectedShader=selectedShader;
        selectedState=(NativeIrisShaderInstanceAccessor)selectedShader;shaderDirty=selectedState.earthplatform$dirty();
        shaderSamplers=new SamplerValues(selectedState.earthplatform$samplerMap());
        pipelineState=(NativeIrisPipelineStateAccessor)pipeline;
        tracker=(NativeIrisTextureTrackerAccessor)TextureTracker.INSTANCE;
        if(tracker.earthplatform$locked())throw new IllegalStateException("Native hook entered a texture callback");
        int units=bounded(SamplerLimits.get().getMaxTextureUnits(),3,MAX_TEXTURE_UNITS,"texture units");
        textureStates=(Object[])GlStateManagerAccessor.getTEXTURES();
        if(units>textureStates.length)
            throw new IllegalStateException("Iris sampler limits exceed transformed Minecraft texture cache");
        var extended=(NativeIrisExtendedShaderAccessor)selectedShader;
        // Pinned ProgramImages.Builder assigns contiguous units starting at zero, one for
        // each actual binding; the hardware's unused combined capacity is irrelevant.
        int imageUnits=bounded(extended.earthplatform$images().getActiveImages(),0,MAX_IMAGE_UNITS,"used image units");
        if(imageUnits>IrisRenderSystem.getMaxImageUnits())throw new IllegalStateException("Iris image bindings exceed hardware limit");
        touchedTextures=new boolean[units];touchedTextures[0]=true;touchedTextures[1]=true;touchedTextures[2]=true;
        var bindings=((NativeIrisProgramSamplersAccessor)extended.earthplatform$samplers()).earthplatform$bindings();
        if(bindings.size()>units)throw new IllegalStateException("Iris sampler binding budget exceeded");
        for(var binding:bindings)touchedTextures[bounded(((NativeIrisSamplerBindingAccessor)binding).earthplatform$unit(),0,units-1,"sampler unit")]=true;
        int buffers=bounded(SamplerLimits.get().getMaxDrawBuffers(),1,MAX_DRAW_BUFFERS,"draw buffers");
        textures=new int[units][TARGETS.length];samplers=new int[units];textureCache=new int[units];images=new int[imageUnits][IMAGE_FIELDS.length];
        blendFactors=new int[buffers][4];blendEnabled=new boolean[buffers];colorMasks=new int[buffers][4];
        program=glGetInteger(GL_CURRENT_PROGRAM);vao=glGetInteger(GL_VERTEX_ARRAY_BINDING);arrayBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        activeTexture=glGetInteger(GL_ACTIVE_TEXTURE);
        touchedTextures[bounded(activeTexture-GL_TEXTURE0,0,units-1,"active texture unit")]=true;
        drawFramebuffer=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);glGetIntegerv(GL_VIEWPORT,viewport);
        depth=glIsEnabled(GL_DEPTH_TEST);depthMask=glGetBoolean(GL_DEPTH_WRITEMASK);depthFunc=glGetInteger(GL_DEPTH_FUNC);cull=glIsEnabled(GL_CULL_FACE);
        shader=RenderSystem.getShader();shaderColor=RenderSystem.getShaderColor().clone();
        fogStart=RenderSystem.getShaderFogStart();fogEnd=RenderSystem.getShaderFogEnd();
        for(int i=0;i<shaderTextures.length;i++)shaderTextures[i]=RenderSystem.getShaderTexture(i);
        mainBound=pipelineState.earthplatform$isMainBound();normalTexture=pipeline.getCurrentNormalTexture();specularTexture=pipeline.getCurrentSpecularTexture();
        alphaTest=CapturedRenderingState.INSTANCE.getCurrentAlphaTest();tessellation=ImmediateState.usingTessellation;
        originalBlendEnable=NativeIrisBlendStateAccessor.earthplatform$enabled();originalBlend=NativeIrisBlendStateAccessor.earthplatform$blend();
        // Raw active-texture queries leave the MC/Iris caches untouched; even capture failure
        // restores the sole temporary selector mutation before propagating.
        try {
            for(int i=0;i<units;i++) {
                glActiveTexture(GL_TEXTURE0+i);
                if(touchedTextures[i])for(int j=0;j<TARGETS.length;j++)textures[i][j]=glGetInteger(BINDINGS[j]);
                samplers[i]=glGetIntegeri(GL_SAMPLER_BINDING,i);
                textureCache[i]=((NativeIrisTextureStateAccessor)textureStates[i]).earthplatform$binding();
            }
        } finally {glActiveTexture(activeTexture);}
        for(int i=0;i<imageUnits;i++)for(int j=0;j<IMAGE_FIELDS.length;j++)images[i][j]=glGetIntegeri(IMAGE_FIELDS[j],i);
        for(int i=0;i<buffers;i++) {
            blendEnabled[i]=glIsEnabledi(GL_BLEND,i);
            blendFactors[i][0]=glGetIntegeri(GL_BLEND_SRC_RGB,i);blendFactors[i][1]=glGetIntegeri(GL_BLEND_DST_RGB,i);
            blendFactors[i][2]=glGetIntegeri(GL_BLEND_SRC_ALPHA,i);blendFactors[i][3]=glGetIntegeri(GL_BLEND_DST_ALPHA,i);
            // Query boolean indexed state through the int-valued generic indexed getter.
            glGetIntegeri_v(GL_COLOR_WRITEMASK,i,colorMasks[i]);
        }
        for(int i=0;i<UNIFORM_NAMES.length;i++) {
            Uniform uniform=selectedShader.getUniform(UNIFORM_NAMES[i]);
            if(uniform!=null)uniforms[i]=new UniformValue(uniform,selectedShader.getId());
        }
        GlErrors.check("Native state capture GL errors",org.lwjgl.opengl.GL11C::glGetError);
    }
    @Override public void run() {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Native GL restoration requires its capture thread");
        if(restored)return;
        // Each category and each binding is attempted even when another cleanup fails.
        Cleanup cleanup=new Cleanup();
        GlErrors.Report[] entryErrors=new GlErrors.Report[1];
        cleanup.run(()->{
            entryErrors[0]=GlErrors.read(org.lwjgl.opengl.GL11C::glGetError);
            entryErrors[0].requireClear("Pre-existing GL errors at native restoration entry, before restoration writes");
        });
        cleanup.run(ProgramUniforms::clearActiveUniforms);cleanup.run(ProgramSamplers::clearActiveSamplers);
        cleanup.run(BlendModeStorage::restoreBlend);
        cleanup.run(()->NativeIrisBlendStateAccessor.earthplatform$locked(false));
        cleanup.run(()->tracker.earthplatform$locked(true));
        try {
            cleanup.run(()->RenderSystem.setShader(()->shader));
            cleanup.run(()->RenderSystem.setShaderColor(shaderColor[0],shaderColor[1],shaderColor[2],shaderColor[3]));
            cleanup.run(()->RenderSystem.setShaderFogStart(fogStart));cleanup.run(()->RenderSystem.setShaderFogEnd(fogEnd));
            for(int i=0;i<shaderTextures.length;i++){final int unit=i;cleanup.run(()->RenderSystem.setShaderTexture(unit,shaderTextures[unit]));}
        } finally {cleanup.run(()->tracker.earthplatform$locked(false));}
        cleanup.run(()->CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alphaTest));
        cleanup.run(()->ImmediateState.usingTessellation=tessellation);
        cleanup.run(()->pipelineState.earthplatform$normalTexture(normalTexture));
        cleanup.run(()->pipelineState.earthplatform$specularTexture(specularTexture));
        cleanup.run(()->{glUseProgram(selectedShader.getId());GlStateManager._glUseProgram(selectedShader.getId());});
        // Retain ALL base and derived values, not just the stack-local SceneMesh cleanup.
        // If an inner upload failed, this owner can repeat both CPU assignment and GPU upload.
        for(var uniform:uniforms)if(uniform!=null)cleanup.run(uniform::restore);
        shaderSamplers.restore(cleanup);
        cleanup.run(()->selectedState.earthplatform$dirty(shaderDirty));
        for(int i=0;i<textures.length;i++) {
            final int unit=i;
            if(touchedTextures[unit]) {
            cleanup.run(()->{glActiveTexture(GL_TEXTURE0+unit);GlStateManager._activeTexture(GL_TEXTURE0+unit);});
            // Force a correct real 2D bind and its Minecraft cache, then restore every target:
            // Iris's DSA bind-to-zero can clear ALL targets on a unit, not just texture2D.
            cleanup.run(()->GlStateManager._bindTexture(0));cleanup.run(()->GlStateManager._bindTexture(textures[unit][1]));
            for(int j=0;j<TARGETS.length;j++){final int target=j;cleanup.run(()->glBindTexture(TARGETS[target],textures[unit][target]));}
            }
            cleanup.run(()->((NativeIrisTextureStateAccessor)textureStates[unit]).earthplatform$binding(textureCache[unit]));
            cleanup.run(()->{glBindSampler(unit,samplers[unit]);IrisRenderSystem.bindSamplerToUnit(unit,0);IrisRenderSystem.bindSamplerToUnit(unit,samplers[unit]);});
        }
        cleanup.run(()->{glActiveTexture(activeTexture);GlStateManager._activeTexture(activeTexture);});
        for(int i=0;i<images.length;i++){final int unit=i;cleanup.run(()->{int[] v=images[unit];IrisRenderSystem.bindImageTexture(unit,v[0],v[1],v[2]!=0,v[3],v[4],v[5]);});}
        cleanup.run(()->{glDepthFunc(depthFunc);RenderSystem.depthFunc(depthFunc);});cleanup.run(()->{glDepthMask(depthMask);RenderSystem.depthMask(depthMask);});
        cleanup.run(()->{if(depth){glEnable(GL_DEPTH_TEST);RenderSystem.enableDepthTest();}else {glDisable(GL_DEPTH_TEST);RenderSystem.disableDepthTest();}});
        cleanup.run(()->{if(cull){glEnable(GL_CULL_FACE);RenderSystem.enableCull();}else {glDisable(GL_CULL_FACE);RenderSystem.disableCull();}});
        cleanup.run(()->{int[] v=blendFactors[0];RenderSystem.blendFuncSeparate(v[0],v[1],v[2],v[3]);});
        cleanup.run(()->{if(blendEnabled[0])RenderSystem.enableBlend();else RenderSystem.disableBlend();});
        cleanup.run(()->{int[] m=colorMasks[0];RenderSystem.colorMask(m[0]!=0,m[1]!=0,m[2]!=0,m[3]!=0);});
        for(int i=0;i<blendFactors.length;i++) {final int buffer=i;
            cleanup.run(()->{int[] v=blendFactors[buffer];glBlendFuncSeparatei(buffer,v[0],v[1],v[2],v[3]);});
            cleanup.run(()->{if(blendEnabled[buffer])glEnablei(GL_BLEND,buffer);else glDisablei(GL_BLEND,buffer);});
            cleanup.run(()->{int[] m=colorMasks[buffer];glColorMaski(buffer,m[0]!=0,m[1]!=0,m[2]!=0,m[3]!=0);});
        }
        cleanup.run(()->NativeIrisBlendStateAccessor.earthplatform$enabled(originalBlendEnable));
        cleanup.run(()->NativeIrisBlendStateAccessor.earthplatform$blend(originalBlend));
        cleanup.run(()->{glBindFramebuffer(GL_DRAW_FRAMEBUFFER,drawFramebuffer);GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER,drawFramebuffer);});
        cleanup.run(()->{glBindFramebuffer(GL_READ_FRAMEBUFFER,readFramebuffer);GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER,readFramebuffer);});
        cleanup.run(()->pipelineState.earthplatform$isMainBound(mainBound));
        cleanup.run(()->{glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);});
        cleanup.run(BufferUploader::invalidate);cleanup.run(()->GlStateManager._glBindVertexArray(vao));
        cleanup.run(()->GlStateManager._glBindBuffer(GL_ARRAY_BUFFER,arrayBuffer));
        cleanup.run(()->{glUseProgram(program);GlStateManager._glUseProgram(program);});
        cleanup.run(()->GlErrors.check(entryErrors[0]!=null&&entryErrors[0].drained()
                ?"Native restoration GL errors after a drained entry queue"
                :"GL errors after native restoration; entry queue could not be fully verified",
                org.lwjgl.opengl.GL11C::glGetError));
        cleanup.run(this::verifyRestoredBindings);
        cleanup.run(()->GlErrors.check("GL errors observed after native restoration readback",org.lwjgl.opengl.GL11C::glGetError));
        cleanup.finish();
        restored=true;
    }
    private void verifyRestoredBindings() {
        shaderSamplers.verify();
        if(selectedState.earthplatform$dirty()!=shaderDirty)throw new IllegalStateException("Native shader dirty-state restoration mismatch");
        for(var uniform:uniforms)if(uniform!=null)uniform.verify(selectedShader.getId());
        requireEqual(program,glGetInteger(GL_CURRENT_PROGRAM),"program");
        requireEqual(vao,glGetInteger(GL_VERTEX_ARRAY_BINDING),"vertex array");
        requireEqual(arrayBuffer,glGetInteger(GL_ARRAY_BUFFER_BINDING),"array buffer");
        requireEqual(drawFramebuffer,glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),"draw framebuffer");
        requireEqual(readFramebuffer,glGetInteger(GL_READ_FRAMEBUFFER_BINDING),"read framebuffer");
        requireEqual(activeTexture,glGetInteger(GL_ACTIVE_TEXTURE),"active texture");
        int[] values=new int[4];glGetIntegerv(GL_VIEWPORT,values);
        for(int i=0;i<4;i++)requireEqual(viewport[i],values[i],"viewport");
        requireEqual(depthFunc,glGetInteger(GL_DEPTH_FUNC),"depth function");
        requireEqual(depthMask?1:0,glGetBoolean(GL_DEPTH_WRITEMASK)?1:0,"depth mask");
        requireEqual(depth?1:0,glIsEnabled(GL_DEPTH_TEST)?1:0,"depth enable");
        requireEqual(cull?1:0,glIsEnabled(GL_CULL_FACE)?1:0,"cull enable");
        // Raw selector readback never alters the Minecraft cache and is always restored.
        try {
            for(int i=0;i<textures.length;i++) {
                if(touchedTextures[i]) {
                    glActiveTexture(GL_TEXTURE0+i);
                    for(int j=0;j<TARGETS.length;j++)requireEqual(textures[i][j],glGetInteger(BINDINGS[j]),"texture binding");
                }
                requireEqual(samplers[i],glGetIntegeri(GL_SAMPLER_BINDING,i),"sampler binding");
                requireEqual(textureCache[i],((NativeIrisTextureStateAccessor)textureStates[i]).earthplatform$binding(),"Minecraft texture cache");
            }
        } finally {glActiveTexture(activeTexture);}
        for(int i=0;i<images.length;i++)for(int j=0;j<IMAGE_FIELDS.length;j++)requireEqual(images[i][j],glGetIntegeri(IMAGE_FIELDS[j],i),"image binding");
        int[] blendNames={GL_BLEND_SRC_RGB,GL_BLEND_DST_RGB,GL_BLEND_SRC_ALPHA,GL_BLEND_DST_ALPHA};
        for(int i=0;i<blendFactors.length;i++) {
            requireEqual(blendEnabled[i]?1:0,glIsEnabledi(GL_BLEND,i)?1:0,"indexed blend enable");
            for(int j=0;j<4;j++)requireEqual(blendFactors[i][j],glGetIntegeri(blendNames[j],i),"indexed blend factors");
            glGetIntegeri_v(GL_COLOR_WRITEMASK,i,values);
            for(int j=0;j<4;j++)requireEqual(colorMasks[i][j],values[j],"indexed color mask");
        }
        if(pipelineState.earthplatform$isMainBound()!=mainBound||pipeline.getCurrentNormalTexture()!=normalTexture
                ||pipeline.getCurrentSpecularTexture()!=specularTexture||tracker.earthplatform$locked()
                ||BlendModeStorage.isBlendLocked()||NativeIrisProgramSamplersAccessor.earthplatform$active()!=null
                ||NativeIrisProgramUniformsAccessor.earthplatform$active()!=null)
            throw new IllegalStateException("Native Iris state-cache restoration mismatch");
    }
    private static void requireEqual(int expected,int actual,String binding) {
        if(expected!=actual)throw new IllegalStateException("Native restoration readback mismatch for "+binding+": expected "+expected+", actual "+actual);
    }
    /** Exactly the twelve keys written by pinned ShaderInstance.setDefaultUniforms. */
    static final class SamplerValues {
        private final Map<String,Object> map;
        private final Object[] values=new Object[12];
        private final boolean[] present=new boolean[12];
        SamplerValues(Map<String,Object> map) {
            this.map=java.util.Objects.requireNonNull(map);
            for(int i=0;i<12;i++){String key="Sampler"+i;present[i]=map.containsKey(key);values[i]=map.get(key);}
        }
        void restore(Cleanup cleanup) {
            for(int i=0;i<12;i++){final int unit=i;cleanup.run(()->{String key="Sampler"+unit;if(present[unit])map.put(key,values[unit]);else map.remove(key);});}
        }
        void verify() {
            for(int i=0;i<12;i++){String key="Sampler"+i;
                if(map.containsKey(key)!=present[i]||map.get(key)!=values[i])throw new IllegalStateException("Native shader sampler-map restoration mismatch: "+key);}
        }
    }
    private static final class UniformValue {
        private final Uniform uniform;
        private final NativeIrisUniformAccessor state;
        private final float[] floats,gpuFloats;
        private final int[] ints,gpuInts;
        private final int type,location,position,limit;
        private final boolean dirty;
        UniformValue(Uniform uniform,int program) {
            this.uniform=uniform;state=(NativeIrisUniformAccessor)uniform;dirty=state.earthplatform$dirty();
            int count=bounded(uniform.getCount(),1,16,"retained shader uniform count");
            type=bounded(uniform.getType(),0,10,"retained shader uniform type");location=uniform.getLocation();
            int width=type<=3?type+1:type<=7?type-3:(type-6)*(type-6);
            requireEqual(width,count,"retained scalar/vector/matrix uniform shape");
            if(type<=3) {
                ints=new int[count];floats=null;gpuFloats=null;gpuInts=location<0?null:new int[count];
                var buffer=uniform.getIntBuffer();position=buffer.position();limit=buffer.limit();
                var copy=buffer.duplicate().clear();for(int i=0;i<count;i++)ints[i]=copy.get(i);
                if(gpuInts!=null)glGetUniformiv(program,location,gpuInts);
            } else {
                floats=new float[count];ints=null;gpuInts=null;gpuFloats=location<0?null:new float[count];
                var buffer=uniform.getFloatBuffer();position=buffer.position();limit=buffer.limit();
                var copy=buffer.duplicate().clear();for(int i=0;i<count;i++)floats[i]=copy.get(i);
                if(gpuFloats!=null)glGetUniformfv(program,location,gpuFloats);
            }
        }
        void restore() {
            // Preserve CPU values AND actual pre-draw GPU values separately. A dirty Uniform
            // may contain a pending CPU update that had not reached the GPU when captured.
            Cleanup cleanup=new Cleanup();
            cleanup.run(()->{
                if(gpuInts!=null)switch(type){case 0->glUniform1iv(location,gpuInts);case 1->glUniform2iv(location,gpuInts);case 2->glUniform3iv(location,gpuInts);case 3->glUniform4iv(location,gpuInts);default->throw new AssertionError();}
                if(gpuFloats!=null)switch(type){case 4->glUniform1fv(location,gpuFloats);case 5->glUniform2fv(location,gpuFloats);case 6->glUniform3fv(location,gpuFloats);case 7->glUniform4fv(location,gpuFloats);case 8->glUniformMatrix2fv(location,false,gpuFloats);case 9->glUniformMatrix3fv(location,false,gpuFloats);case 10->glUniformMatrix4fv(location,false,gpuFloats);default->throw new AssertionError();}
            });
            cleanup.run(()->{
                if(floats!=null){var buffer=uniform.getFloatBuffer();buffer.clear();for(int i=0;i<floats.length;i++)buffer.put(i,floats[i]);buffer.limit(limit).position(position);}
                else {var buffer=uniform.getIntBuffer();buffer.clear();for(int i=0;i<ints.length;i++)buffer.put(i,ints[i]);buffer.limit(limit).position(position);}
            });
            cleanup.run(()->state.earthplatform$dirty(dirty));cleanup.finish();
        }
        void verify(int program) {
            if(state.earthplatform$dirty()!=dirty)throw new IllegalStateException("Native uniform dirty-state restoration mismatch");
            if(gpuInts!=null){int[] actual=new int[gpuInts.length];glGetUniformiv(program,location,actual);for(int i=0;i<actual.length;i++)requireEqual(gpuInts[i],actual[i],"integer uniform GPU value");}
            if(gpuFloats!=null){float[] actual=new float[gpuFloats.length];glGetUniformfv(program,location,actual);for(int i=0;i<actual.length;i++)
                requireEqual(Float.floatToRawIntBits(gpuFloats[i]),Float.floatToRawIntBits(actual[i]),"float uniform GPU value");}
            if(floats!=null){var buffer=uniform.getFloatBuffer();requireEqual(position,buffer.position(),"float uniform buffer position");requireEqual(limit,buffer.limit(),"float uniform buffer limit");
                var copy=buffer.duplicate().clear();for(int i=0;i<floats.length;i++)requireEqual(Float.floatToRawIntBits(floats[i]),Float.floatToRawIntBits(copy.get(i)),"float uniform CPU value");}
            else {var buffer=uniform.getIntBuffer();requireEqual(position,buffer.position(),"integer uniform buffer position");requireEqual(limit,buffer.limit(),"integer uniform buffer limit");
                var copy=buffer.duplicate().clear();for(int i=0;i<ints.length;i++)requireEqual(ints[i],copy.get(i),"integer uniform CPU value");}
        }
    }
    /** Error queues are observed, recorded, and boundedly drained, never silently discarded. */
    static final class GlErrors {
        static final int MAX_READS=16;
        record Report(int[] codes,int count,boolean drained) {
            void requireClear(String phase) {
                if(count==0&&drained)return;
                StringBuilder message=new StringBuilder(phase).append(":");
                for(int i=0;i<count;i++)message.append(" 0x").append(Integer.toHexString(codes[i]));
                if(!drained)message.append("; bounded error drain limit reached, remaining queue unknown");
                throw new IllegalStateException(message.toString());
            }
        }
        static Report read(IntSupplier source) {
            int[] codes=new int[MAX_READS];
            for(int i=0;i<MAX_READS;i++) {
                int value=source.getAsInt();
                if(value==GL_NO_ERROR)return new Report(codes,i,true);
                codes[i]=value;
            }
            return new Report(codes,MAX_READS,false);
        }
        static void check(String phase,IntSupplier source){read(source).requireClear(phase);}
    }
    static int bounded(int value,int min,int max,String field){if(value<min||value>max)throw new IllegalStateException("Unsupported native Iris "+field+": "+value);return value;}
    /** Pure cleanup reducer also exercised without a GL context. */
    static final class Cleanup {
        private Throwable failure;
        void run(Runnable action){try{action.run();}catch(RuntimeException|Error error){if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error);}}
        void finish(){if(failure instanceof Error error)throw error;if(failure instanceof RuntimeException error)throw error;}
    }
}
