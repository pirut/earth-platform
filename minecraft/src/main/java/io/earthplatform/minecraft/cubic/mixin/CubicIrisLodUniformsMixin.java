package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.client.FarLodIris;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives every pack program far terrain's projection under Voxy's uniform names. Skipped when Iris is not installed. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
abstract class CubicIrisLodUniformsMixin {
    @Inject(method = "addDynamicUniforms", at = @At("TAIL"), require = 0, remap = false)
    private static void earthplatform$farProjection(DynamicUniformHolder uniforms, FogMode fog, CallbackInfo info) {
        FarLodIris.addUniforms(uniforms);
    }
}
