package io.earthplatform.compat.dh.shader.mixin;
import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Preserve the normal DH/Iris provider everywhere except the exact owned cubic carrier. */
@Mixin(targets="com.seibel.distanthorizons.core.util.RenderUtil",remap=false)
public abstract class DhCubicNearPlaneMixin {
    @Inject(method="getHeightBasedNearClipOverrideBlockDistance",at=@At("HEAD"),cancellable=true,require=1)
    private static void earth$noStockHeightOverride(CallbackInfoReturnable<Float> callback){if(ShaderCarrierHooks.owns(Minecraft.getInstance().level))callback.setReturnValue(-1.0f);}
}
