package io.earthplatform.compat.dh.shader.mixin;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.irisshaders.iris.Iris",remap=false)
public abstract class IrisShaderLifecycleMixin {
    @Inject(method={"reload","destroyEverything"},at=@At("HEAD"),require=1)
    private static void earth$revokeBeforeReload(CallbackInfo callback){InstalledIrisRuntime.invalidateLifecycle("Iris reload");ShaderCarrierHooks.invalidate("Iris reload");}
}
