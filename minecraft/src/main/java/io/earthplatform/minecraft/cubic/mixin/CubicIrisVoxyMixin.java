package io.earthplatform.minecraft.cubic.mixin;

import com.google.common.collect.ImmutableList;
import io.earthplatform.minecraft.cubic.client.FarLod;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Compiles shader packs with {@code VOXY} defined, so packs with Voxy support read far terrain's
 * own depth; see {@link FarLod}. Skipped when Iris is not installed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.shader.StandardMacros", remap = false)
abstract class CubicIrisVoxyMixin {
    @Inject(method = "createStandardEnvironmentDefines", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void earthplatform$defineVoxy(CallbackInfoReturnable<ImmutableList<StringPair>> result) {
        if (!FarLod.defineVoxy()) return;
        result.setReturnValue(ImmutableList.<StringPair>builder().addAll(result.getReturnValue()).add(new StringPair("VOXY", "")).build());
    }
}
