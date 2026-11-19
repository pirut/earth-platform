package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.compat.CreateSkyCompat;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Use the Earth storm ceiling when Create Sky searches for ships near lightning. */
@Pseudo
@Mixin(targets = "com.createskys.weather.ShipStrikes", remap = false)
abstract class CreateSkyShipStrikesMixin {
    @ModifyExpressionValue(method = "onStrike", at = @At(value = "INVOKE",
            target = "Lcom/createskys/config/StormConfig;cloudTop()D"), require = 0)
    private static double earthplatform$shipStrikeCeiling(double top, @Local(argsOnly = true) ServerLevel level) {
        return top + CreateSkyCompat.cloudOffset(level);
    }
}
