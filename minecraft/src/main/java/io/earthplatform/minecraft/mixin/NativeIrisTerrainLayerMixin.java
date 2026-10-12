package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.scene.NativeIrisShaderStages;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Pinned Sodium 0.8.12 overwrites this method. Its managed render is fully exited at this
 * exact call site; Iris's terrain phase remains active until its RETURN injection. Iris's
 * ShadowRenderer calls the SAME method with its true shadow matrices and camera origin.
 * Pinned Mixin requires an INVOKE injector's priority to be STRICTLY above Sodium's
 * merged-method priority1000. All MAIN merges complete before INJECT_PREPARE, so1001
 * sees the overwritten body; the exact anchor, not priority order, fixes draw timing. */
@Mixin(value=LevelRenderer.class, priority=1001)
public abstract class NativeIrisTerrainLayerMixin implements io.earthplatform.minecraft.scene.SurvivalRenderHookAccess {
    @org.spongepowered.asm.mixin.Unique private Object earthplatform$survivalFrame;
    @org.spongepowered.asm.mixin.Unique private Object earthplatform$survivalInvocation;
    @org.spongepowered.asm.mixin.Unique private Object earthplatform$survivalPreparation;
    @org.spongepowered.asm.mixin.Unique private Matrix4f earthplatform$survivalPreparationView,earthplatform$survivalPreparationProjection;
    @org.spongepowered.asm.mixin.Unique private double earthplatform$survivalPreparationX,earthplatform$survivalPreparationY,earthplatform$survivalPreparationZ;
    @org.spongepowered.asm.mixin.Unique private boolean earthplatform$survivalPrepared;
    @org.spongepowered.asm.mixin.Unique private io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin earthplatform$survivalOwner;
    @org.spongepowered.asm.mixin.Unique private Matrix4f earthplatform$survivalView,earthplatform$survivalProjection;
    @org.spongepowered.asm.mixin.Unique private RenderType earthplatform$survivalLayer;
    @org.spongepowered.asm.mixin.Unique private double earthplatform$survivalX,earthplatform$survivalY,earthplatform$survivalZ;
    @org.spongepowered.asm.mixin.Unique private int earthplatform$survivalMainLayers;
    @org.spongepowered.asm.mixin.Unique private boolean earthplatform$survivalPoison;

    /** The token is created and retired by the actual transformed renderer, including exceptional exit. */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method="renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V")
    private void earthplatform$survivalRenderFrame(net.minecraft.client.DeltaTracker delta,boolean outline,
            net.minecraft.client.Camera camera,net.minecraft.client.renderer.GameRenderer game,
            net.minecraft.client.renderer.LightTexture light,Matrix4f view,Matrix4f projection,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var owner=io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin.attachedTo((LevelRenderer)(Object)this);
        if(owner==null){original.call(delta,outline,camera,game,light,view,projection);return;}
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        if(earthplatform$survivalOwner!=owner){earthplatform$survivalOwner=owner;earthplatform$survivalPoison=false;}
        if(earthplatform$survivalFrame!=null||earthplatform$survivalPoison){
            earthplatform$survivalPoison=true;owner.refuse("Nested or poisoned real survival render frame");
            throw new IllegalStateException("Nested or poisoned real survival render frame");
        }
        owner.beginActualFrame(); // Precharge before either private token allocation.
        boolean returned=false;
        try{earthplatform$survivalFrame=new Object();earthplatform$survivalMainLayers=0;earthplatform$survivalPrepared=false;
            original.call(delta,outline,camera,game,light,view,projection);returned=true;}
        finally{
            earthplatform$survivalFrame=null;earthplatform$survivalInvocation=null;
            earthplatform$survivalPreparation=null;earthplatform$survivalPreparationView=null;earthplatform$survivalPreparationProjection=null;
            earthplatform$survivalView=null;earthplatform$survivalProjection=null;earthplatform$survivalLayer=null;
            if(!returned){earthplatform$survivalPoison=true;owner.refuse("Actual survival render frame exited exceptionally");}
            owner.endActualFrame();
        }
    }
    /** Exact main renderLevel call site; it surrounds callee HEAD hooks without priority assumptions.
     * Earlier shadow invocations are not this call site and receive no main content authority. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method="renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",ordinal=0),require=1,allow=1)
    private void earthplatform$survivalPrepareMain(LevelRenderer receiver,RenderType layer,double x,double y,double z,
            Matrix4f view,Matrix4f projection,com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original){
        var owner=io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin.attachedTo((LevelRenderer)(Object)this);
        boolean scoped=owner!=null;
        if(scoped){
            earthplatform$requireSurvivalScope(false);
            if(receiver!=(LevelRenderer)(Object)this||receiver!=net.minecraft.client.Minecraft.getInstance().levelRenderer
                    ||layer!=RenderType.solid()||net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered()
                    ||earthplatform$survivalPrepared||earthplatform$survivalPreparation!=null){
                earthplatform$survivalPoison=true;owner.refuse("Repeated, foreign or non-main solid preparation call site");
                throw new IllegalStateException("Invalid actual main preparation call site");
            }
            earthplatform$survivalPrepared=true;
        }
        try{
            if(scoped){
                earthplatform$survivalPreparation=new Object();
                earthplatform$survivalPreparationView=view;earthplatform$survivalPreparationProjection=projection;
                earthplatform$survivalPreparationX=x;earthplatform$survivalPreparationY=y;earthplatform$survivalPreparationZ=z;
                io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin.prepareActualMainInvocation();
            }
            original.call(receiver,layer,x,y,z,view,projection);
        }finally{if(scoped){earthplatform$survivalPreparation=null;earthplatform$survivalPreparationView=null;earthplatform$survivalPreparationProjection=null;}}
    }
    @org.spongepowered.asm.mixin.Unique private void earthplatform$requirePreparation(){
        earthplatform$requireSurvivalScope(false);
        if(earthplatform$survivalPreparation==null||net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered())
            throw new IllegalStateException("No actual main solid call-site preparation");
    }
    @Override public Object earthplatform$survivalPreparation(){earthplatform$requirePreparation();return earthplatform$survivalPreparation;}
    @Override public double earthplatform$survivalPreparationCamera(int axis){earthplatform$requirePreparation();return switch(axis){case 0->earthplatform$survivalPreparationX;case 1->earthplatform$survivalPreparationY;case 2->earthplatform$survivalPreparationZ;default->throw new IllegalArgumentException("Camera axis");};}
    @Override public float earthplatform$survivalPreparationMatrix(boolean projection,int column,int row){
        earthplatform$requirePreparation();if(column<0||column>3||row<0||row>3)throw new IllegalArgumentException("Matrix component");
        return (projection?earthplatform$survivalPreparationProjection:earthplatform$survivalPreparationView).get(column,row);
    }
    @org.spongepowered.asm.mixin.Unique private void earthplatform$requireSurvivalScope(boolean invocation) {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        if(earthplatform$survivalPoison||earthplatform$survivalFrame==null
                ||earthplatform$survivalOwner!=io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin.attachedTo((LevelRenderer)(Object)this)
                ||(invocation&&earthplatform$survivalInvocation==null))
            throw new IllegalStateException("Actual survival render scope is absent or retired");
    }
    @Override public Object earthplatform$survivalFrame(){earthplatform$requireSurvivalScope(false);return earthplatform$survivalFrame;}
    @Override public Object earthplatform$survivalInvocation(){earthplatform$requireSurvivalScope(true);return earthplatform$survivalInvocation;}
    @Override public ClientLevel earthplatform$survivalLevel(){earthplatform$requireSurvivalScope(false);return level;}
    @Override public RenderType earthplatform$survivalLayer(){earthplatform$requireSurvivalScope(true);return earthplatform$survivalLayer;}
    @Override public double earthplatform$survivalCamera(int axis){earthplatform$requireSurvivalScope(true);return switch(axis){case 0->earthplatform$survivalX;case 1->earthplatform$survivalY;case 2->earthplatform$survivalZ;default->throw new IllegalArgumentException("Camera axis");};}
    @Override public float earthplatform$survivalMatrix(boolean projection,int column,int row){
        earthplatform$requireSurvivalScope(true);if(column<0||column>3||row<0||row>3)throw new IllegalArgumentException("Matrix component");
        return (projection?earthplatform$survivalProjection:earthplatform$survivalView).get(column,row);
    }
    @Shadow private ClientLevel level;
    @Inject(method="setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V",at=@At("HEAD"),require=1,allow=1)
    private void earthplatform$survivalLevelChanged(ClientLevel next,CallbackInfo callback){
        io.earthplatform.minecraft.scene.SurvivalSceneResources.invalidateBeforeMutation();
    }
    @Inject(method="renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at=@At(value="INVOKE", target="Lnet/caffeinemc/mods/sodium/client/services/PlatformLevelRenderHooks;getInstance()Lnet/caffeinemc/mods/sodium/client/services/PlatformLevelRenderHooks;", remap=false), require=1)
    private void earthplatform$nativeTerrain(RenderType layer, double x, double y, double z,
            Matrix4f modelView, Matrix4f projection, CallbackInfo callback) {
        var owner=io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin.attachedTo((LevelRenderer)(Object)this);
        boolean scoped=owner!=null;
        if(scoped){
            earthplatform$requireSurvivalScope(false);
            if(earthplatform$survivalInvocation!=null){earthplatform$survivalPoison=true;owner.refuse("Nested native survival invocation");throw new IllegalStateException("Nested native survival invocation");}
            if(!net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered()){
                int bit=layer==RenderType.solid()?1:layer==RenderType.cutoutMipped()?2:layer==RenderType.cutout()?4:layer==RenderType.translucent()?8:0;
                if(bit!=0&&(earthplatform$survivalMainLayers&bit)!=0){earthplatform$survivalPoison=true;owner.refuse("Repeated main native survival layer");throw new IllegalStateException("Repeated main native survival layer");}
                earthplatform$survivalMainLayers|=bit;
            }
            earthplatform$survivalInvocation=new Object();earthplatform$survivalLayer=layer;
            earthplatform$survivalView=modelView;earthplatform$survivalProjection=projection;
            earthplatform$survivalX=x;earthplatform$survivalY=y;earthplatform$survivalZ=z;
        }
        try {
            if (Boolean.getBoolean(io.earthplatform.minecraft.scene.SurvivalNativePageParityClient.ENABLED))
                io.earthplatform.minecraft.scene.SurvivalNativePageParityClient.onIrisTerrain(level, layer);
            if(scoped)io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin.observeActualNativeAnchor();
            NativeIrisShaderStages.dispatch(level, layer, modelView, projection, x, y, z);
        } finally {
            if(scoped){earthplatform$survivalInvocation=null;earthplatform$survivalView=null;earthplatform$survivalProjection=null;earthplatform$survivalLayer=null;}
        }
    }
}
