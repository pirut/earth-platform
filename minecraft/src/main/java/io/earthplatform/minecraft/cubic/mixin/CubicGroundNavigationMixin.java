package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Walking mobs move a target in the air down to the ground under it, or a target inside a block
 * up out of it, before pathing. Vanilla reads that column from the carrier chunk, which is empty
 * in a cubic level; here it reads the cubes, and stops at unloaded cubes instead of the carrier's
 * floor and ceiling.
 */
@Mixin(GroundPathNavigation.class)
abstract class CubicGroundNavigationMixin extends PathNavigation {
    /** As far as one adjustment searches; a vanilla world column is 384 blocks. */
    @Unique private static final int REACH = 384;

    CubicGroundNavigationMixin(Mob mob, Level level) { super(mob, level); }

    @Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubicTarget(BlockPos pos, int accuracy, CallbackInfoReturnable<Path> result) {
        CubeWorld cubes = CubicLevel.of(level);
        if (cubes == null) return;
        if (!cubes.isLoaded(pos)) { result.setReturnValue(null); return; }
        BlockPos target = pos;
        if (cubes.getBlockState(target).isAir()) {
            BlockPos below = target.below();
            for (int i = 0; i < REACH && cubes.isLoaded(below) && cubes.getBlockState(below).isAir(); i++) below = below.below();
            if (cubes.isLoaded(below) && !cubes.getBlockState(below).isAir()) target = below.above();
        } else if (cubes.getBlockState(target).isSolid()) {
            BlockPos above = target.above();
            for (int i = 0; i < REACH && cubes.isLoaded(above) && cubes.getBlockState(above).isSolid(); i++) above = above.above();
            target = above;
        }
        result.setReturnValue(super.createPath(target, accuracy));
    }
}
