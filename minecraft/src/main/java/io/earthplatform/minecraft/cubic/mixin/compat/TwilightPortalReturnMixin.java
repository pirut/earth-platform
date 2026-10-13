package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.compat.PortalReturn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaving the Twilight Forest normally lands in its origin dimension, the Overworld. Someone who
 * entered from the Earth world goes back to the Earth portal they used instead: the Twilight Forest's
 * own portal search can't place a portal in the cubic world, whose carrier is only 16 blocks tall.
 * Going in, Earth coordinates keep their scale: the Twilight Forest normally multiplies them by 8, which
 * would put almost all of Earth past its world border.
 */
@Pseudo
@Mixin(targets = "twilightforest.block.TFPortalBlock", remap = false)
abstract class TwilightPortalReturnMixin {
    private static final String EARTHPLATFORM$RETURN = "earthplatform:twilight_return";

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$earthReturn(ServerLevel level, Entity entity, BlockPos portal, CallbackInfoReturnable<DimensionTransition> result) {
        if (PortalReturn.rememberIfEarth(EARTHPLATFORM$RETURN, level, entity, portal)) return;
        DimensionTransition back = PortalReturn.back(EARTHPLATFORM$RETURN, level, entity);
        if (back != null) result.setReturnValue(back);
    }

    @WrapOperation(method = "getPortalDestination", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/dimension/DimensionType;getTeleportationScale(Lnet/minecraft/world/level/dimension/DimensionType;Lnet/minecraft/world/level/dimension/DimensionType;)D"))
    private double earthplatform$earthScale(DimensionType from, DimensionType to, Operation<Double> original,
                                            ServerLevel level, Entity entity, BlockPos portal) {
        return Cubic.isCubic(level.dimension()) ? 1.0 : original.call(from, to);
    }
}
