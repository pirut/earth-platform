package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's chunk weather in the Earth waits for a column's height to be known (see
 * {@link ServerCubeWorld#weatherRuns}): snow and ice, and lightning, which both ask for the height of the
 * column's top. NeoForge force-ticks a loader's chunk with nobody near, and before its cubes were in the
 * height came from reading the terrain on the server thread.
 */
@Mixin(ServerLevel.class)
abstract class CubicTicketWeatherMixin {
    @Inject(method = "tickPrecipitation", at = @At("HEAD"), cancellable = true)
    private void earthplatform$snowWaits(BlockPos pos, CallbackInfo info) {
        if (CubicLevel.of((ServerLevel) (Object) this) instanceof ServerCubeWorld cubes && !cubes.weatherRuns(pos.getX() >> 4, pos.getZ() >> 4)) info.cancel();
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isThundering()Z"))
    private boolean earthplatform$lightningWaits(ServerLevel level, Operation<Boolean> original, LevelChunk chunk, int randomTickSpeed) {
        if (!original.call(level)) return false;
        return !(CubicLevel.of(level) instanceof ServerCubeWorld cubes) || cubes.weatherRuns(chunk.getPos().x, chunk.getPos().z);
    }
}
