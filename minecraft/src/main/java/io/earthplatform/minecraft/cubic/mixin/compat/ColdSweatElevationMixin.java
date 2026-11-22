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
 * Cold Sweat's ElevationTempModifier clamps underground depth against {@code level.getMinBuildHeight()},
 * which on the 16-block carrier returns 0 and treats sub-sea-level valleys and caves as below bedrock.
 * Supply the cubic world's real floor instead.
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.api.temperature.modifier.ElevationTempModifier", remap = false)
abstract class ColdSweatElevationMixin {
    @WrapOperation(method = "calculate", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$minY(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }
}
