package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * CC: Tweaked turtles check each move against the level's build height. Against the cubic level's
 * 16-block carrier every move above Y 15 or below Y 0 failed with "Too high to move" or "Too low
 * to move", which in the Earth world is nearly everywhere; they get the cubic world's range here.
 */
@Pseudo
@Mixin(targets = "dan200.computercraft.shared.turtle.core.TurtleMoveCommand", remap = false)
abstract class ComputerCraftTurtleMoveMixin {
    @WrapOperation(method = "canEnter", require = 0, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/server/level/ServerLevel;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean earthplatform$outside(ServerLevel level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null ? !CubeWorld.inRange(pos.getY()) : original.call(level, pos);
    }
}
