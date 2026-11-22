package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.EarthTemperature;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tough as Nails drops temperature above Y 160, raises it below Y -32, checks cave shelter against
 * absolute Y <= 50, and checks snow exposure via vanilla's height-cooled {@code Biome.coldEnoughToSnow}.
 * On Earth, cities like Denver (Y 1660) and Cusco (Y 3460) were chilled by the altitude penalty and
 * mountain caves never sheltered players from night air. Supply real CHELSA Earth temperature tiers
 * and measure cave depth relative to the real ground surface.
 */
@Pseudo
@Mixin(targets = "toughasnails.temperature.TemperatureHelperImpl", remap = false)
abstract class ToughAsNailsTemperatureMixin {
    /** Depth below surface matching vanilla's Y <= 50 shelter line below sea/ground Y 64. */
    @Unique private static final int CAVE_SHELTER_DEPTH = 14;
    /** Depth below surface matching vanilla's Y < -32 geothermal warming line below Y 64. */
    @Unique private static final int DEEP_WARM_DEPTH = 96;
    @Unique private static volatile Object[] earthplatform$levels;

    @Unique
    @Nullable
    private static Object[] earthplatform$temperatureLevels() {
        Object[] cached = earthplatform$levels;
        if (cached != null) return cached;
        try {
            Object[] constants = Class.forName("toughasnails.api.temperature.TemperatureLevel").getEnumConstants();
            if (constants != null && constants.length >= 5) {
                earthplatform$levels = constants;
                return constants;
            }
        } catch (ClassNotFoundException ignored) {
        }
        return null;
    }

    @Inject(method = "getBiomeTemperatureLevel(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Ltoughasnails/api/temperature/TemperatureLevel;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$biomeLevel(Level level, BlockPos pos, CallbackInfoReturnable<Object> result) {
        if (CubicLevel.of(level) == null || !level.dimensionType().natural()) return;
        Object[] levels = earthplatform$temperatureLevels();
        if (levels == null) return;
        if (EarthTemperature.depthBelowSurface(level, pos.getX(), pos.getY(), pos.getZ()) >= CAVE_SHELTER_DEPTH) {
            result.setReturnValue(levels[EarthTemperature.TAN_NEUTRAL]);
            return;
        }
        double celsius = EarthTemperature.celsius(level, pos.getX(), pos.getY(), pos.getZ());
        if (Double.isFinite(celsius)) {
            result.setReturnValue(levels[EarthTemperature.toughAsNailsOrdinal(celsius)]);
        }
    }

    @Inject(method = "altitudeModifier(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ltoughasnails/api/temperature/TemperatureLevel;)Ltoughasnails/api/temperature/TemperatureLevel;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$altitude(Level level, BlockPos pos, @Coerce Object current,
                                               CallbackInfoReturnable<Object> result) {
        if (CubicLevel.of(level) == null || !level.dimensionType().natural()) return;
        Object[] levels = earthplatform$temperatureLevels();
        if (levels != null && current instanceof Enum<?> levelEnum
                && EarthTemperature.depthBelowSurface(level, pos.getX(), pos.getY(), pos.getZ()) > DEEP_WARM_DEPTH) {
            result.setReturnValue(levels[Math.min(levelEnum.ordinal() + 1, levels.length - 1)]);
            return;
        }
        result.setReturnValue(current);
    }

    @Inject(method = "nightModifier(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ltoughasnails/api/temperature/TemperatureLevel;)Ltoughasnails/api/temperature/TemperatureLevel;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$nightCaveShelter(Level level, BlockPos pos, @Coerce Object current,
                                                       CallbackInfoReturnable<Object> result) {
        if (CubicLevel.of(level) != null && level.dimensionType().natural()
                && EarthTemperature.depthBelowSurface(level, pos.getX(), pos.getY(), pos.getZ()) >= CAVE_SHELTER_DEPTH) {
            result.setReturnValue(current);
        }
    }

    @Inject(method = "coldEnoughToSnow(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$coldEnoughToSnow(Level level, Holder<Biome> biome, BlockPos pos,
                                                       CallbackInfoReturnable<Boolean> result) {
        if (CubicLevel.of(level) == null) return;
        double celsius = EarthTemperature.celsius(level, pos.getX(), pos.getY(), pos.getZ());
        if (Double.isFinite(celsius)) {
            result.setReturnValue(celsius < 0.0);
        } else {
            result.setReturnValue(biome.value().getBaseTemperature() < 0.15F);
        }
    }
}
