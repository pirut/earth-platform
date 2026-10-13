package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fire lights Nether portals in the cubic world, as it does in the overworld. */
@Mixin(BaseFireBlock.class)
abstract class CubicPortalFireMixin {
    @Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
    private static void earthplatform$cubicPortals(Level level, CallbackInfoReturnable<Boolean> result) {
        if (CubicLevel.of(level) != null) result.setReturnValue(true);
    }
}
