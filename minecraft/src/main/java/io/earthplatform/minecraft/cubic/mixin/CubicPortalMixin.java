package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.portal.CubicPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Nether portal in the Nether leads back to the cubic Earth for those who came from it. */
@Mixin(NetherPortalBlock.class)
abstract class CubicPortalMixin {
    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void earthplatform$toCubic(ServerLevel level, Entity entity, BlockPos pos, CallbackInfoReturnable<DimensionTransition> result) {
        if (level.dimension() != Level.NETHER) return;
        DimensionTransition transition = CubicPortals.fromNether(level, entity, pos);
        if (transition != null) result.setReturnValue(transition);
    }
}
