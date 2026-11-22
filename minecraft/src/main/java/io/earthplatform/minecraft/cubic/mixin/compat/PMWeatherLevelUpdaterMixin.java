package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Enables PM Weather's surface/seasonal updaters in cubic worlds across full cubic Y range. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.level.LevelUpdater", remap = false)
abstract class PMWeatherLevelUpdaterMixin {
    @Inject(method = "isValidDimension", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$allowEarthDimension(Level level, CallbackInfoReturnable<Boolean> cir) {
        if (Cubic.isCubic(level.dimension())) cir.setReturnValue(true);
    }

    @WrapOperation(method = "tick", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isLoaded(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$cubicSurfaceLoaded(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null
                ? PMWeatherCompat.isColumnSurfaceLoaded(level, pos.getX(), pos.getZ())
                : original.call(level, pos);
    }

    @Inject(method = "onSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;ZLjava/util/function/BiConsumer;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubicOnSurface(ChunkAccess chunk, boolean above,
                                              BiConsumer<BlockPos, BlockState> action, CallbackInfo ci) {
        Level level = PMWeatherCompat.chunkLevel(chunk);
        if (level == null) return;
        BlockPos origin = chunk.getPos().getWorldPosition();
        CubeWorld cubes = CubicLevel.at(level, origin.getX(), origin.getZ());
        if (cubes == null) return;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int top = cubes.heightTop(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (!CubeWorld.inRange(top)) continue;
                BlockPos surface = new BlockPos(x, top, z);
                if (!cubes.isLoaded(surface)) continue;
                action.accept(surface, level.getBlockState(surface));
                if (above && CubeWorld.inRange(top + 1)) {
                    BlockPos over = surface.above();
                    if (cubes.isLoaded(over)) action.accept(over, level.getBlockState(over));
                }
            }
        }
        ci.cancel();
    }
}
