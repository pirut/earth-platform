package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Springs, laser pointers and swivel bearings ask whether the blocks around a spot are loaded by
 * looking for client chunks within the build range. The cubic client keeps cubes, not chunks, so in
 * world columns the answer comes from the cubes.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.util.SimLevelUtil", remap = false)
abstract class SimulatedLoadedMixin {
    @Inject(method = "isAreaActuallyLoaded", at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$cubesLoaded(Level level, BlockPos center, int range, CallbackInfoReturnable<Boolean> result) {
        if (SableCompat.cubes(level, center.getX(), center.getZ()) == null) return;
        result.setReturnValue(level.hasChunksAt(center.getX() - range, center.getY() - range, center.getZ() - range,
                center.getX() + range, center.getY() + range, center.getZ() + range));
    }
}
