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
 * Create gathers a contraption's blocks only inside the level's build height. The cubic level's
 * carrier is 16 blocks tall, so every Earth block counted as outside and contraptions came out empty;
 * here the cubic world's own height range decides instead.
 */
@Pseudo
@Mixin(targets = {
        "com.simibubi.create.content.contraptions.Contraption",
        "com.simibubi.create.content.contraptions.piston.PistonContraption"}, remap = false)
abstract class CreateContraptionMixin {
    @WrapOperation(method = {"moveBlock", "addToInitialFrontier"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$cubicHeight(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.of(level) != null ? !CubeWorld.inRange(pos.getY()) : original.call(level, pos);
    }
}
