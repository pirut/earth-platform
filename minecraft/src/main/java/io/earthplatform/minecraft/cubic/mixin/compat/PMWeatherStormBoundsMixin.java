package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps PM Weather storms within cubic world bounds and checks surface cubes instead of Y=60. */
@Pseudo
@Mixin(targets = {
        "dev.protomanly.pmweather.weather.Storm",
        "dev.protomanly.pmweather.weather.storms.Cyclone",
        "dev.protomanly.pmweather.weather.storms.FireWhirl",
        "dev.protomanly.pmweather.weather.storms.Supercell"
}, remap = false)
abstract class PMWeatherStormBoundsMixin {
    @WrapOperation(method = "move", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$cubicSteeringMaxY(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }

    @WrapOperation(method = "tickServer", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isInWorldBounds(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$cubicTickInBounds(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null
                ? CubeWorld.inRange(pos.getY())
                : original.call(level, pos);
    }

    @WrapOperation(method = "shouldLoadChunk(Lnet/minecraft/world/level/ChunkPos;D)Z", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isInWorldBounds(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$cubicLoadChunkInBounds(ServerLevel level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null
                ? CubeWorld.inRange(pos.getY())
                : original.call(level, pos);
    }

    @WrapOperation(method = {"tickServer", "getBlockPos"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isLoaded(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$cubicSurfaceLoaded(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null
                ? PMWeatherCompat.isColumnSurfaceLoaded(level, pos.getX(), pos.getZ())
                : original.call(level, pos);
    }
}
