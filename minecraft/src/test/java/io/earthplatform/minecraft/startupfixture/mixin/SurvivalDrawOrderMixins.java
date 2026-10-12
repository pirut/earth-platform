package io.earthplatform.minecraft.startupfixture.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.startupfixture.SurvivalDrawOrderHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Synthetic structural correspondence only: actual MixinExtras execution, mocked DH/Sodium work. */
public final class SurvivalDrawOrderMixins {
    @Mixin(targets="io.earthplatform.minecraft.startupfixture.SurvivalDrawOrderTarget",priority=1000,remap=false)
    public abstract static class SodiumOverwrite {
        /** @author Earth Platform fixture @reason Reproduce the managed terrain overwrite. */
        @Overwrite public void renderSectionLayer(Object layer,double x,double y,double z,Object view,Object projection){
            SurvivalDrawOrderHooks.event("managed-enter");
            try{SurvivalDrawOrderHooks.draw();}finally{SurvivalDrawOrderHooks.event("managed-exit");}
            SurvivalDrawOrderHooks.anchor();
        }
    }
    @Mixin(targets="io.earthplatform.minecraft.startupfixture.SurvivalDrawOrderTarget",priority=1000,remap=false)
    public abstract static class DhHead {
        @Inject(method="renderSectionLayer(Ljava/lang/Object;DDDLjava/lang/Object;Ljava/lang/Object;)V",at=@At("HEAD"),require=1,allow=1)
        private void fixture$dhHead(Object layer,double x,double y,double z,Object view,Object projection,CallbackInfo ci){
            SurvivalDrawOrderHooks.observe(this,"dh-head",layer,x,y,z,view,projection);
        }
    }
    @Mixin(targets="io.earthplatform.minecraft.startupfixture.SurvivalDrawOrderTarget",priority=1001,remap=false)
    public abstract static class MainOriginAndNative implements SurvivalDrawOrderHooks.Scope {
        @Unique private Object fixture$token,fixture$frameToken;
        @Override public Object fixture$frame(){return fixture$frameToken;}
        @WrapMethod(method="renderLevel(Ljava/lang/Object;DDDLjava/lang/Object;Ljava/lang/Object;)V")
        private void fixture$wholeFrame(Object layer,double x,double y,double z,Object view,Object projection,Operation<Void> original){
            if(fixture$frameToken!=null)throw new IllegalStateException("Nested synthetic frame");
            try{fixture$frameToken=new Object();SurvivalDrawOrderHooks.frameBegun(this);
                original.call(layer,x,y,z,view,projection);
            }finally{fixture$frameToken=null;SurvivalDrawOrderHooks.frameRetired(this);}
        }
        @Override public Object fixture$preparation(){return fixture$token;}
        @WrapOperation(method="renderLevel(Ljava/lang/Object;DDDLjava/lang/Object;Ljava/lang/Object;)V",
                at=@At(value="INVOKE",target="Lio/earthplatform/minecraft/startupfixture/SurvivalDrawOrderTarget;renderSectionLayer(Ljava/lang/Object;DDDLjava/lang/Object;Ljava/lang/Object;)V",ordinal=0),require=1,allow=1)
        private void fixture$mainOrigin(@Coerce Object receiver,Object layer,double x,double y,double z,Object view,Object projection,Operation<Void> original){
            if(receiver!=this||fixture$frameToken==null||fixture$token!=null)throw new IllegalStateException("Nested or foreign fixture preparation");
            try{fixture$token=new Object();SurvivalDrawOrderHooks.prepare(receiver,layer,x,y,z,view,projection);
                original.call(receiver,layer,x,y,z,view,projection);
            }finally{fixture$token=null;SurvivalDrawOrderHooks.retired(receiver);}
        }
        @Inject(method="renderSectionLayer(Ljava/lang/Object;DDDLjava/lang/Object;Ljava/lang/Object;)V",
                at=@At(value="INVOKE",target="Lio/earthplatform/minecraft/startupfixture/SurvivalDrawOrderHooks;anchor()V"),require=1,allow=1)
        private void fixture$nativeAnchor(Object layer,double x,double y,double z,Object view,Object projection,CallbackInfo ci){
            SurvivalDrawOrderHooks.observe(this,"native-anchor",layer,x,y,z,view,projection);
        }
    }
    private SurvivalDrawOrderMixins(){}
}
