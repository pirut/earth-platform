package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * /spreadplayers scans each spot down from its height limit through the level's blocks. In a cubic
 * level the default limit is the 16-block carrier's top, so every spot read as open air and the
 * command gave up. Here the ground comes from the cube store's column tops (generated where the
 * cubes are not loaded), and the default limit means no limit.
 */
@Mixin(targets = "net.minecraft.server.commands.SpreadPlayersCommand$Position")
abstract class CubicSpreadPlayersMixin {
    @Shadow double x;
    @Shadow double z;

    @Unique @Nullable private static CubeWorld earthplatform$cubes(BlockGetter level) {
        return level instanceof Level real ? CubicLevel.of(real) : null;
    }

    /** The first open Y over the column's ground, or -1 when the limit is below that ground. */
    @Unique private int earthplatform$spawnY(CubeWorld cubes, Level level, int limit) {
        int top = cubes.skyTop(Mth.floor(x), Mth.floor(z)) + 1;
        return limit == level.getMaxBuildHeight() || top <= limit ? top : -1;
    }

    @Inject(method = "getSpawnY", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubicSpawnY(BlockGetter level, int limit, CallbackInfoReturnable<Integer> result) {
        CubeWorld cubes = earthplatform$cubes(level);
        if (cubes == null) return;
        int y = earthplatform$spawnY(cubes, (Level) level, limit);
        if (y >= 0) result.setReturnValue(y);
    }

    @Inject(method = "isSafe", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubicSafe(BlockGetter level, int limit, CallbackInfoReturnable<Boolean> result) {
        CubeWorld cubes = earthplatform$cubes(level);
        if (cubes == null) return;
        int y = earthplatform$spawnY(cubes, (Level) level, limit);
        if (y < 0) return;
        BlockState ground = level.getBlockState(BlockPos.containing(x, y - 1, z));
        result.setReturnValue(!ground.liquid() && !ground.is(BlockTags.FIRE));
    }
}
