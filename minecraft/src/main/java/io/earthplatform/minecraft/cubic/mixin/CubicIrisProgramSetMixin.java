package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.client.FarPackPatch;
import java.util.function.Function;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps each program set's Voxy fragment code, read with the pack's settings applied, for the far
 * terrain's shader. Skipped when Iris is not installed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.programs.ProgramSet", remap = false)
abstract class CubicIrisProgramSetMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
    private void earthplatform$keepVoxyPatch(AbsolutePackPath directory, Function<AbsolutePackPath, String> sources,
                                             ShaderProperties properties, ShaderPack pack, CallbackInfo info) {
        FarPackPatch.capture((ProgramSet) (Object) this, directory, sources);
    }
}
