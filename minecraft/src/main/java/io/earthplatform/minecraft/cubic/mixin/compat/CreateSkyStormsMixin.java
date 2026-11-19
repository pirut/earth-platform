package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.compat.CreateSkyCompat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Compare Earth positions in the coordinates used by Create Sky's storm-height settings. */
@Pseudo
@Mixin(targets = "com.createskys.weather.Storms", remap = false)
abstract class CreateSkyStormsMixin {
    @ModifyVariable(method = "aboveStorms", at = @At("HEAD"), argsOnly = true, require = 0)
    private static double earthplatform$relativeStormHeight(double y, @Local(argsOnly = true) Level level) {
        return y - CreateSkyCompat.cloudOffset(level);
    }
}
