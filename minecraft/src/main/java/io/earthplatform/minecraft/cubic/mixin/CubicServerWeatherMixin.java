package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cauldrons and other precipitation handlers fill with rain where the biome rains (see CubicWeather). */
@Mixin(ServerLevel.class)
abstract class CubicServerWeatherMixin {
    @WrapOperation(method = "tickPrecipitation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation earthplatform$rain(Biome biome, BlockPos pos, Operation<Biome.Precipitation> original) {
        return CubicWeather.precipitation((ServerLevel) (Object) this, biome, original.call(biome, pos));
    }
}
