package io.earthplatform.compat.dh.shader.mixin;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="net.irisshaders.iris.targets.RenderTargets",remap=false)
public abstract class IrisTargetResizeMixin {
    @Shadow private int cachedDepthBufferVersion;
    @Shadow private int currentDepthTexture;
    @Shadow private int cachedWidth;
    @Shadow private int cachedHeight;
    @Shadow private DepthBufferFormat currentDepthFormat;
    @Inject(method="resizeIfNeeded",at=@At("HEAD"),require=1)
    private void earth$beforeTargetChange(int version,int depth,int width,int height,DepthBufferFormat format,PackDirectives directives,CallbackInfoReturnable<Boolean> callback){
        if(version!=cachedDepthBufferVersion||depth!=currentDepthTexture||width!=cachedWidth||height!=cachedHeight||format!=currentDepthFormat){
            InstalledIrisRuntime.invalidateLifecycle("Iris render target replacement");ShaderCarrierHooks.invalidateAdmitted("Iris render target replacement");
        }
    }
    @Inject(method="destroy",at=@At("HEAD"),require=1)
    private void earth$beforeTargetDestroy(CallbackInfo callback){InstalledIrisRuntime.invalidateLifecycle("Iris render targets destroyed");ShaderCarrierHooks.invalidate("Iris render targets destroyed");}
}
