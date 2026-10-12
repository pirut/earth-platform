package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.compat.dh.shader.DhShaderPassGate;
import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import java.util.Objects;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.LightTextureAccessor;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;

/** Exact installed-Iris path only. No entry point is used by the isolated/default renderer. */
public final class NativeIrisShaderStages {
    private NativeIrisShaderStages() {}
    private static Context active;
    /** Must complete before the host releases borrowed pipeline resources after a failure. */
    public static void retryCleanup(){RenderSystem.assertOnRenderThread();NativeIrisState.retryCleanup();}
    public static boolean hasRetainedCleanup(){return NativeIrisState.hasRetainedCleanup();}
    public static void restorePendingState(){retryCleanup();}
    public static boolean restorationPending(){return hasRetainedCleanup();}

    /** An immutable borrowed invocation. Matrix accessors copy; retaining this object grants
     * no future drawing right. Its capability expires when the synchronous hook returns. */
    public static final class Context {
        private final Thread owner=Thread.currentThread();
        private final ClientLevel level;
        private final IrisRenderingPipeline pipeline;
        private final RenderType layer;
        private final DhShaderPassGate.Pass pass;
        private final Matrix4f modelView,projection;
        private final double x,y,z;
        private final boolean shadow,beforeTranslucent;
        private final WorldRenderingPhase phase;
        private Context(ClientLevel level,IrisRenderingPipeline pipeline,RenderType layer,
                        Matrix4f modelView,Matrix4f projection,double x,double y,double z,boolean shadow) {
            this.level=level;this.pipeline=pipeline;this.layer=layer;this.pass=NativeIrisShaderStages.pass(layer,shadow);
            this.modelView=new Matrix4f(modelView);this.projection=new Matrix4f(projection);
            this.x=x;this.y=y;this.z=z;this.shadow=shadow;
            beforeTranslucent=pipeline.isBeforeTranslucent;phase=pipeline.getPhase();
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                    ||!this.modelView.isFinite()||!this.projection.isFinite())
                throw new IllegalArgumentException("Finite native terrain invocation required");
            if(phase!=WorldRenderingPhase.fromTerrainRenderType(layer))
                throw new IllegalStateException("Native terrain hook missed Iris terrain phase");
        }
        public ClientLevel level(){return level;}
        public RenderType layer(){return layer;}
        public DhShaderPassGate.Pass pass(){return pass;}
        float scalar(boolean projection,int column,int row){return (projection?this.projection:modelView).get(column,row);}
        public Matrix4f modelView(){return new Matrix4f(modelView);}
        public Matrix4f projection(){return new Matrix4f(projection);}
        public double cameraX(){return x;} public double cameraY(){return y;} public double cameraZ(){return z;}
        public boolean shadow(){return shadow;}
        public boolean isCurrent(){
            return Thread.currentThread()==owner&&active==this&&Minecraft.getInstance().level==level
                    &&Iris.getPipelineManager().getPipelineNullable()==pipeline
                    &&Iris.isPackInUseQuick()&&ShadowRenderingState.areShadowsCurrentlyBeingRendered()==shadow
                    &&pipeline.isBeforeTranslucent==beforeTranslucent&&pipeline.getPhase()==phase;
        }
    }

    /** Internal survival readers never accept a caller's transient context. */
    static Context actualContext(){return context(active);}
    static void requireSurvivalBoundState(Context context,ShaderInstance shader,double[] range){
        if(context!=active||!context.isCurrent()||context.shadow)throw new IllegalStateException("No live native main scope");
        var access=(io.earthplatform.minecraft.mixin.NativeIrisExtendedShaderAccessor)shader;
        var target=context.beforeTranslucent?access.earthplatform$beforeFramebuffer():access.earthplatform$afterFramebuffer();
        if(target==null||!target.hasDepthAttachment()||!org.lwjgl.opengl.GL33C.glIsFramebuffer(target.getId())
                ||org.lwjgl.opengl.GL33C.glGetInteger(org.lwjgl.opengl.GL33C.GL_DRAW_FRAMEBUFFER_BINDING)!=target.getId()
                ||org.lwjgl.opengl.GL33C.glGetInteger(org.lwjgl.opengl.GL33C.GL_CURRENT_PROGRAM)!=shader.getId()
                ||!org.lwjgl.opengl.GL33C.glIsProgram(shader.getId())
                ||!org.lwjgl.opengl.GL33C.glIsEnabled(org.lwjgl.opengl.GL33C.GL_DEPTH_TEST)
                ||!org.lwjgl.opengl.GL33C.glGetBoolean(org.lwjgl.opengl.GL33C.GL_DEPTH_WRITEMASK)
                ||org.lwjgl.opengl.GL33C.glGetInteger(org.lwjgl.opengl.GL33C.GL_DEPTH_FUNC)!=org.lwjgl.opengl.GL33C.GL_LEQUAL)
            throw new IllegalStateException("Actual native Iris program/FBO/depth state changed");
        org.lwjgl.opengl.GL33C.glGetDoublev(org.lwjgl.opengl.GL33C.GL_DEPTH_RANGE,range);
        if(range[0]!=0.0||range[1]!=1.0)throw new IllegalStateException("Native depth range was remapped");
        io.earthplatform.compat.dh.shader.StrictGlErrors.check("survival-native.pass.state");
    }

    public static Context context(Object transientContext) {
        if(!(transientContext instanceof Context context)||!context.isCurrent())
            throw new IllegalStateException("Native shader invocation expired or belongs to another stage");
        return context;
    }

    /** Called only from the exact pinned transformed LevelRenderer method, including shadows. */
    public static void dispatch(ClientLevel level,RenderType layer,Matrix4f modelView,Matrix4f projection,
                                double x,double y,double z) {
        if(!ShaderCarrierHooks.owns(level))return;
        try {
            RenderSystem.assertOnRenderThread();
            if(active!=null)throw new IllegalStateException("Reentrant native shader invocation");
            if(hasRetainedCleanup())throw new IllegalStateException("Native shader cleanup remains owned");
            if(!supportedLayer(layer))return; // Tripwire is outside the opaque analytic policy.
            if(!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline))
                throw new IllegalStateException("Native shader terrain pipeline is absent");
            var context=new Context(level,pipeline,layer,modelView,projection,x,y,z,
                    ShadowRenderingState.areShadowsCurrentlyBeingRendered());
            active=context;
            try {ShaderCarrierHooks.nativeLayer(level,context.pass,context);}
            finally {active=null;}
        } catch(RuntimeException|Error failure) {ShaderCarrierHooks.failed(failure);}
    }

    /** Host obtains this only after the carrier's verified pipeline lease and frame fence. */
    public static SceneNativeShaderContract.ShaderPass shaderPass(Object transientContext,
                                                                  SceneNativeShaderContract.Capture capture) {
        Context context=context(transientContext);Objects.requireNonNull(capture);
        if(!capture.isCurrent()||capture.values().pipeline()!=context.pipeline)
            throw new IllegalStateException("Native mesh capture belongs to a different shader pipeline");
        // AIR/STONE/DIORITE has no translucent native geometry. Consume that ledger slot
        // without calling SceneMesh, whose compiled policy deliberately has only three layers.
        if(context.layer==RenderType.translucent())throw new IllegalArgumentException("Translucent native geometry is not admitted");
        ShaderInstance shader=context.pipeline.getShaderMap().getShader(shaderKey(context.layer,context.shadow));
        if(!(shader instanceof ExtendedShader)||shader.getVertexFormat()!=capture.values().format())
            throw new IllegalStateException("Pinned extended Iris terrain shader/format is unavailable");
        var minecraft=Minecraft.getInstance();
        var lightTexture=((LightTextureAccessor)minecraft.gameRenderer.lightTexture()).getLightTexture();
        int lightmap=lightTexture.getId();
        int atlas=minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
        if(lightmap<=0||atlas<=0)throw new IllegalStateException("Live block atlas and Minecraft lightmap are required");
        return new SceneNativeShaderContract.ShaderPass() {
            public SceneNativeShaderContract.Capture capture(){return capture;}
            public RenderType layer(){return context.layer;}
            public ShaderInstance shader(){return shader;}
            public boolean isCurrent(){return context.isCurrent()&&capture.isCurrent()
                    &&lightTexture.getId()==lightmap
                    &&context.pipeline.getShaderMap().getShader(shaderKey(context.layer,context.shadow))==shader;}
            public Runnable captureState(){
                if(!isCurrent())throw new IllegalStateException("Stale native shader pass");
                return NativeIrisState.capture(context.pipeline,shader);
            }
            public void setup(){
                if(!isCurrent())throw new IllegalStateException("Native shader pass changed before setup");
                RenderSystem.setShaderTexture(0,atlas);
                RenderSystem.setShaderTexture(2,lightmap);
                RenderSystem.setShaderColor(1,1,1,1);
                RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL_LEQUAL);RenderSystem.depthMask(true);
                RenderSystem.colorMask(true,true,true,true);RenderSystem.disableBlend();
                // Iris shadow terrain is deliberately two-sided; preserve that behavior.
                if(context.shadow)RenderSystem.disableCull();else RenderSystem.enableCull();
                // ExtendedShader.apply in VBO.drawWithShader owns the actual gbuffer/shadow
                // FBO, MRT blend rules, sampler and image bindings. Never bind a replacement FBO.
            }
        };
    }
    private static boolean supportedLayer(RenderType layer){return layer==RenderType.solid()||layer==RenderType.cutoutMipped()
            ||layer==RenderType.cutout()||layer==RenderType.translucent();}
    static DhShaderPassGate.Pass pass(RenderType layer,boolean shadow) {
        if(layer==RenderType.solid())return shadow?DhShaderPassGate.Pass.SHADOW_NATIVE_SOLID:DhShaderPassGate.Pass.MAIN_NATIVE_SOLID;
        if(layer==RenderType.cutoutMipped())return shadow?DhShaderPassGate.Pass.SHADOW_NATIVE_CUTOUT_MIPPED:DhShaderPassGate.Pass.MAIN_NATIVE_CUTOUT_MIPPED;
        if(layer==RenderType.cutout())return shadow?DhShaderPassGate.Pass.SHADOW_NATIVE_CUTOUT:DhShaderPassGate.Pass.MAIN_NATIVE_CUTOUT;
        if(layer==RenderType.translucent())return shadow?DhShaderPassGate.Pass.SHADOW_NATIVE_TRANSLUCENT:DhShaderPassGate.Pass.MAIN_NATIVE_TRANSLUCENT;
        throw new IllegalArgumentException("Not an admitted native terrain layer");
    }
    static ShaderKey shaderKey(RenderType layer,boolean shadow){
        if(!supportedLayer(layer))throw new IllegalArgumentException("Not a native terrain layer");
        if(shadow)return layer==RenderType.translucent()?ShaderKey.SHADOW_TRANSLUCENT:ShaderKey.SHADOW_TERRAIN_CUTOUT;
        if(layer==RenderType.solid())return ShaderKey.TERRAIN_SOLID;
        return layer==RenderType.translucent()?ShaderKey.TERRAIN_TRANSLUCENT:ShaderKey.TERRAIN_CUTOUT;
    }
}
