package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.earthplatform.minecraft.cubic.compat.EarthTemperature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Feeds real CHELSA Earth air temperatures (with elevation lapse rate and day/night swing) into
 * Cold Sweat's BiomeTempModifier in Earth worlds so latitude and elevation climates differ
 * authentically across the globe rather than using flat vanilla biome base temperatures.
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.api.temperature.modifier.BiomeTempModifier", remap = false)
abstract class ColdSweatBiomeTempMixin {
    @WrapOperation(method = "calculate", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/BiomeManager;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> earthplatform$captureSamplePos(BiomeManager manager, BlockPos pos,
                                                         Operation<Holder<Biome>> original,
                                                         @Share("samplePos") LocalRef<BlockPos> samplePos) {
        samplePos.set(pos);
        return original.call(manager, pos);
    }

    @WrapOperation(method = "calculate", require = 0,
            at = @At(value = "INVOKE", target = "Lcom/momosoftworks/coldsweat/util/world/WorldHelper;getBiomeTemperature(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/Holder;)D"))
    private double earthplatform$earthBiomeTemp(LevelAccessor levelAccessor, Holder<Biome> biome,
                                                Operation<Double> original,
                                                @Share("samplePos") LocalRef<BlockPos> samplePos) {
        if (levelAccessor instanceof Level level) {
            BlockPos pos = samplePos.get();
            if (pos != null) {
                double celsius = EarthTemperature.celsius(level, pos.getX(), pos.getY(), pos.getZ());
                if (Double.isFinite(celsius)) {
                    return EarthTemperature.coldSweatDiurnal(celsius, level.dayTime(), biome.value().hasPrecipitation());
                }
            }
        }
        return original.call(levelAccessor, biome);
    }
}
