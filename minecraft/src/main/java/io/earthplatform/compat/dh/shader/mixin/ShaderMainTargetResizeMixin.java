package io.earthplatform.compat.dh.shader.mixin;
import com.mojang.blaze3d.pipeline.RenderTarget;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Earliest main attachment replacement, before Iris later observes its changed version. */
@Mixin(RenderTarget.class)
public abstract class ShaderMainTargetResizeMixin {
    @Inject(method="resize",at=@At("HEAD"),require=1)
    private void earth$beforeMainResize(int width,int height,boolean clearError,CallbackInfo callback){
        var mc=Minecraft.getInstance();if((Object)this==mc.getMainRenderTarget()&&ShaderCarrierHooks.owns(mc.level)){
            InstalledIrisRuntime.invalidateLifecycle("Main shader target resized");ShaderCarrierHooks.invalidateAdmitted("Main shader target resized");
        }
    }
}
