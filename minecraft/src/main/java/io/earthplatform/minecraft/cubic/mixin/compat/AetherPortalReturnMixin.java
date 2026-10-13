package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.compat.PortalReturn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaving the Aether normally lands in the Overworld at a portal the Aether finds or builds. Someone who
 * entered from the Earth world goes back beside the Earth portal they used instead.
 */
@Pseudo
@Mixin(targets = "com.aetherteam.aether.block.portal.AetherPortalBlock", remap = false)
abstract class AetherPortalReturnMixin {
    private static final String EARTHPLATFORM$RETURN = PortalReturn.AETHER_RETURN;

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$earthReturn(ServerLevel level, Entity entity, BlockPos portal, CallbackInfoReturnable<DimensionTransition> result) {
        if (PortalReturn.rememberIfEarth(EARTHPLATFORM$RETURN, level, entity, portal)) return;
        DimensionTransition back = PortalReturn.back(EARTHPLATFORM$RETURN, level, entity);
        if (back != null) result.setReturnValue(back);
    }
}
