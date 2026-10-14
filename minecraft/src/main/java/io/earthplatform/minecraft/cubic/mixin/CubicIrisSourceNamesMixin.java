package io.earthplatform.minecraft.cubic.mixin;

import com.google.common.collect.ImmutableList;
import io.earthplatform.minecraft.cubic.client.FarPackPatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris only reads the shader pack files it knows; this adds the pack's Voxy files so the far
 * terrain can use the pack's Voxy fragment code, as Voxy does. Skipped when Iris is not installed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames", remap = false)
abstract class CubicIrisSourceNamesMixin {
    @Inject(method = "findPotentialStarts", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void earthplatform$voxyFiles(CallbackInfoReturnable<ImmutableList<String>> result) {
        ImmutableList<String> names = result.getReturnValue();
        ImmutableList.Builder<String> builder = ImmutableList.<String>builder().addAll(names);
        for (String file : FarPackPatch.FILES) if (!names.contains(file)) builder.add(file);
        result.setReturnValue(builder.build());
    }
}
