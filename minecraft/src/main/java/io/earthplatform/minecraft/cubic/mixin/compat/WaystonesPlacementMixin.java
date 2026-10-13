package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A waystone is two blocks tall and checks for room against the level's height. The cubic level's
 * carrier is 16 blocks tall, so no waystone could be placed in the Earth world.
 */
@Pseudo
@Mixin(targets = "net.blay09.mods.waystones.block.WaystoneBlockBase", remap = false)
abstract class WaystonesPlacementMixin {
    @WrapOperation(method = "getStateForPlacement", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getHeight()I"))
    private int earthplatform$cubicHeight(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }
}
