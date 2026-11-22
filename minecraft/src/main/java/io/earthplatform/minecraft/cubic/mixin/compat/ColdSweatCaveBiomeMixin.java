package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cold Sweat's CaveBiomeTempModifier skips sample positions outside the world's build bounds.
 * Ensure cubic worlds check the full vertical cubic range.
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.api.temperature.modifier.CaveBiomeTempModifier", remap = false)
abstract class ColdSweatCaveBiomeMixin {
    @WrapOperation(method = "calculate", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isInWorldBounds(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$inBounds(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.of(level) != null ? CubeWorld.inRange(pos.getY()) : original.call(level, pos);
    }
}
