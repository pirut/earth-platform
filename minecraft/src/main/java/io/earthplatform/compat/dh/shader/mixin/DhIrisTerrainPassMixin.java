package io.earthplatform.compat.dh.shader.mixin;

import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.compat.dh.InstalledDhFrame;
import io.earthplatform.compat.dh.shader.SurvivalDhHookAccess;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact DH3.3.3 selector. Keep upstream setup, aggregate-event result and tail cleanup. */
@Mixin(targets="com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge",remap=false)
public abstract class DhIrisTerrainPassMixin implements SurvivalDhHookAccess {
    @Unique private Object earthplatform$survivalDhToken,earthplatform$survivalDhLevel;
    @Unique private InstalledDhFrame.Inputs earthplatform$survivalDhInputs;
    @Unique private double earthplatform$survivalDhX,earthplatform$survivalDhY,earthplatform$survivalDhZ;
    @Unique private float earthplatform$survivalDhNear,earthplatform$survivalDhFar;
    @Unique private int earthplatform$survivalDhPass;
    @Unique private boolean earthplatform$survivalDhOpaque,earthplatform$survivalDhPoisoned;

    @ModifyVariable(method="render",at=@At("HEAD"),argsOnly=true,ordinal=0,require=1)
    private SortedArraySet<LodBufferContainer> earth$emptyOwnedStockBuffers(SortedArraySet<LodBufferContainer> buffers,RenderParams parameters,boolean opaque,SortedArraySet<LodBufferContainer> original,IProfilerWrapper profiler){
        return ShaderCarrierHooks.owns(parameters.apiCopy.clientLevelWrapper.getWrappedMcObject())?null:buffers;
    }
    // The second cull call is after the complete BeforeRenderPass dispatcher and is
    // reached only when DH's Iris accessor exists. Null buffers then skip only the loop.
    @Inject(method="render",at=@At(value="INVOKE",target="Lcom/seibel/distanthorizons/common/wrappers/minecraft/MinecraftGLWrapper;enableFaceCulling()V",ordinal=1,shift=At.Shift.AFTER),require=1)
    private void earth$submitOwnedPass(RenderParams parameters,boolean opaque,SortedArraySet<LodBufferContainer> buffers,IProfilerWrapper profiler,CallbackInfo callback){
        var p=parameters.exactCameraPosition;
        Object level=parameters.apiCopy.clientLevelWrapper.getWrappedMcObject();
        if(!ShaderCarrierHooks.survivalOwns(level)||ShadowRenderingState.areShadowsCurrentlyBeingRendered()){
            ShaderCarrierHooks.terrain(level,parameters.apiCopy,opaque,ShadowRenderingState.areShadowsCurrentlyBeingRendered(),p.x,p.y,p.z);
            return;
        }
        RenderSystem.assertOnRenderThread();
        if(earthplatform$survivalDhPoisoned||earthplatform$survivalDhToken!=null){
            earthplatform$survivalDhPoisoned=true;throw new IllegalStateException("Nested or poisoned actual survival DH scope");
        }
        ShaderCarrierHooks.requireSurvivalHookReservation();
        try {
            earthplatform$survivalDhToken=new Object();
            earthplatform$survivalDhLevel=level;earthplatform$survivalDhOpaque=opaque;
            earthplatform$survivalDhPass=parameters.apiCopy.renderPass.ordinal();
            earthplatform$survivalDhNear=parameters.apiCopy.nearClipPlane;earthplatform$survivalDhFar=parameters.apiCopy.farClipPlane;
            earthplatform$survivalDhX=p.x;earthplatform$survivalDhY=p.y;earthplatform$survivalDhZ=p.z;
            earthplatform$survivalDhInputs=InstalledDhFrame.capture(parameters.apiCopy);
            ShaderCarrierHooks.terrain(level,parameters.apiCopy,opaque,false,p.x,p.y,p.z);
        } finally {
            earthplatform$survivalDhToken=null;earthplatform$survivalDhInputs=null;earthplatform$survivalDhLevel=null;
        }
    }

    @Unique private void earthplatform$requireSurvivalDhScope(){
        RenderSystem.assertOnRenderThread();
        if(earthplatform$survivalDhPoisoned||earthplatform$survivalDhToken==null||earthplatform$survivalDhInputs==null)
            throw new IllegalStateException("No live actual survival DH invocation");
    }
    @Override public Object earthplatform$survivalDhInvocation(){earthplatform$requireSurvivalDhScope();return earthplatform$survivalDhToken;}
    @Override public Object earthplatform$survivalDhLevel(){earthplatform$requireSurvivalDhScope();return earthplatform$survivalDhLevel;}
    @Override public boolean earthplatform$survivalDhOpaque(){earthplatform$requireSurvivalDhScope();return earthplatform$survivalDhOpaque;}
    @Override public int earthplatform$survivalDhPass(){earthplatform$requireSurvivalDhScope();return earthplatform$survivalDhPass;}
    @Override public float earthplatform$survivalDhClip(boolean far){earthplatform$requireSurvivalDhScope();return far?earthplatform$survivalDhFar:earthplatform$survivalDhNear;}
    @Override public double earthplatform$survivalDhCamera(int axis){earthplatform$requireSurvivalDhScope();return switch(axis){case 0->earthplatform$survivalDhX;case 1->earthplatform$survivalDhY;case 2->earthplatform$survivalDhZ;default->throw new IndexOutOfBoundsException();};}
    @Override public float earthplatform$survivalDhMatrix(boolean projection,int column,int row){earthplatform$requireSurvivalDhScope();if(column<0||column>3||row<0||row>3)throw new IndexOutOfBoundsException();return (projection?earthplatform$survivalDhInputs.projection():earthplatform$survivalDhInputs.modelView()).get(column,row);}
}
