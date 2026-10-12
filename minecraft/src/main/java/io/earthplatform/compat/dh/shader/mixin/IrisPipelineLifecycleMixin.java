package io.earthplatform.compat.dh.shader.mixin;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="net.irisshaders.iris.pipeline.PipelineManager",remap=false)
public abstract class IrisPipelineLifecycleMixin {
    @Unique private Object earth$previousDimension;
    @Inject(method="destroyPipeline",at=@At("HEAD"),require=1)
    private void earth$destroy(CallbackInfo callback){InstalledIrisRuntime.invalidateLifecycle("Iris pipeline destroy");ShaderCarrierHooks.invalidate("Iris pipeline destroy");}
    @Inject(method="preparePipeline",at=@At("HEAD"),require=1)
    private void earth$dimension(@Coerce Object dimension,CallbackInfoReturnable<Object> callback){
        if(earth$previousDimension!=null&&!earth$previousDimension.equals(dimension)){InstalledIrisRuntime.invalidateLifecycle("Iris dimension transition");ShaderCarrierHooks.invalidateAdmitted("Iris dimension transition");}
        earth$previousDimension=dimension;
    }
}
