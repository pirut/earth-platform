package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Tracks real cubic terrain elevation for early-stage supercells instead of clamping them to Y=69. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.weather.storms.Supercell", remap = false)
abstract class PMWeatherSupercellMixin {
    @Shadow public Level level;

    @ModifyConstant(method = "changeY", constant = @Constant(doubleValue = 69.0d), require = 0)
    private double earthplatform$cubicTerrainHeight(double original, @Local(argsOnly = true) BlockPos blockPos) {
        return level != null && CubicLevel.of(level) != null ? blockPos.getY() : original;
    }
}
