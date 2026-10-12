package io.earthplatform.minecraft.startupfixture.mixin;

import io.earthplatform.minecraft.startupfixture.ShaderTextureTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Original fixture reproducing the mechanically verified pinned annotation contract. */
@Mixin({ShaderTextureTargets.RenderAliases.class, ShaderTextureTargets.BindingAliases.class})
public abstract class ShaderTextureStorageMixin {
    @ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 12), require = 1)
    private static int fixture$increaseMaximumAllowedTextureUnits(int existingValue) {
        return 128;
    }
}
