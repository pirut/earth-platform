package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.compat.EarthWeatherProfile;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Enables PM Weather chunk moisture storage/sync in Earth and initializes moisture from regional climate. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.seasons.MoistureHandler", remap = false)
abstract class PMWeatherMoistureMixin {
    @ModifyExpressionValue(method = "setMoistureOnChunk", require = 0,
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;OVERWORLD:Lnet/minecraft/resources/ResourceKey;"))
    private static ResourceKey<Level> earthplatform$earthSetMoisture(ResourceKey<Level> original,
                                                                     @Local(argsOnly = true) Level level) {
        return Cubic.isCubic(level.dimension()) ? Cubic.LEVEL : original;
    }

    @ModifyExpressionValue(method = "HandleChunkData", require = 0,
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;OVERWORLD:Lnet/minecraft/resources/ResourceKey;"))
    private static ResourceKey<Level> earthplatform$earthHandleChunkData(ResourceKey<Level> original,
                                                                         @Local Level level) {
        return level != null && Cubic.isCubic(level.dimension()) ? Cubic.LEVEL : original;
    }

    @ModifyReturnValue(method = "getStartMoisture", at = @At("RETURN"), require = 0)
    private static float earthplatform$regionalStartMoisture(float original,
                                                             @Local(argsOnly = true) Level level,
                                                             @Local(argsOnly = true) ChunkAccess chunk) {
        BlockPos center = chunk.getPos().getMiddleBlockPosition(EarthGenerator.SEA);
        int y = PMWeatherCompat.surfaceY(level, center.getX(), center.getZ());
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, center.getX() + 0.5, y, center.getZ() + 0.5);
        return profile != null ? (float) profile.moisture() : original;
    }
}
