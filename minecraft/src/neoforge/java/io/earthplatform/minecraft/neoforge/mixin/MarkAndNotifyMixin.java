package io.earthplatform.minecraft.neoforge.mixin;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge's markAndNotifyBlock, which its block snapshots call once a placement event lets a
 * placement stand, notifies through the cube store in cubic columns, as setBlock does.
 */
@Mixin(Level.class)
abstract class MarkAndNotifyMixin {
    @Inject(method = "markAndNotifyBlock", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeNotify(BlockPos pos, @Nullable LevelChunk chunk, BlockState old, BlockState state, int flags, int depth, CallbackInfo info) {
        CubicLevel level = (CubicLevel) this;
        CubeWorld cubes = level.earthplatform$cubes();
        if (cubes == null || level.earthplatform$keptInChunks(pos.getX() >> 4, pos.getZ() >> 4)) return;
        cubes.markAndNotify(pos, old, state, flags, depth);
        info.cancel();
    }
}
