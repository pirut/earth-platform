package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.client.FarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shader packs fade terrain out at the {@code far} uniform, which Iris takes from the render
 * distance. In the cubic world terrain carries on to the far terrain's fog, so packs get that
 * reach instead. Skipped when Iris is not installed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms", remap = false)
abstract class CubicShaderFarMixin {
    @Inject(method = "getRenderDistanceInBlocks", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void earthplatform$farTerrainReach(CallbackInfoReturnable<Integer> result) {
        if (ClientCubeWorld.current() != null) result.setReturnValue(Math.max(result.getReturnValueI(), (int) FarRenderer.FOG_END));
    }
}
