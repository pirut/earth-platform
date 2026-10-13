package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.portal.CubicTravel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The End's exit portal sends a player to their respawn point, else the overworld spawn; a player
 * who lives on the cubic Earth goes to its arrival point instead. (Before the credits have been
 * seen, the exit goes through a respawn, which {@link CubicTravel} handles.)
 */
@Mixin(EndPortalBlock.class)
abstract class CubicEndExitMixin {
    @Inject(method = "getPortalDestination", at = @At("RETURN"), cancellable = true)
    private void earthplatform$homeToCubic(ServerLevel level, Entity entity, BlockPos pos, CallbackInfoReturnable<DimensionTransition> result) {
        if (level.dimension() != Level.END || !(entity instanceof ServerPlayer player) || result.getReturnValue() == null) return;
        result.setReturnValue(CubicTravel.home(player, result.getReturnValue()));
    }
}
