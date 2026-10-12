package io.earthplatform.compat.dh.shader.mixin;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import net.irisshaders.iris.targets.DepthTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.irisshaders.iris.compat.dh.DHCompatInternal",remap=false)
public abstract class IrisDhTargetResizeMixin {
    @Shadow private DepthTexture depthTexNoTranslucent;
    @Shadow private int storedDepthTex;
    @Inject(method="createDepthTex",at=@At("HEAD"),require=1)
    private void earth$replaceOpaqueDepth(int width,int height,CallbackInfo callback){
        if(depthTexNoTranslucent!=null){InstalledIrisRuntime.invalidateLifecycle("Iris DH depth texture replacement");ShaderCarrierHooks.invalidateAdmitted("Iris DH depth texture replacement");}
    }
    @Inject(method="reconnectDHTextures",at=@At("HEAD"),require=1)
    private void earth$reconnectDepth(int depth,CallbackInfo callback){
        if(storedDepthTex!=0&&storedDepthTex!=depth){InstalledIrisRuntime.invalidateLifecycle("Iris DH attachment replacement");ShaderCarrierHooks.invalidateAdmitted("Iris DH attachment replacement");}
    }
    @Inject(method="clear",at=@At("HEAD"),require=1)
    private void earth$clearBorrowedPrograms(CallbackInfo callback){InstalledIrisRuntime.invalidateLifecycle("Iris DH resources cleared");ShaderCarrierHooks.invalidate("Iris DH resources cleared");}
}
