package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Create blocks that bound their reach by the level's build height: mechanical arms, rope pulleys,
 * metal scaffolding and elevator contacts. Against the cubic level's 16-block carrier they stopped
 * working above Y 16 or below Y 0, so they get the cubic world's range instead.
 */
@Pseudo
@Mixin(targets = {
        "com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity",
        "com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity",
        "com.simibubi.create.content.decoration.MetalScaffoldingBlockItem",
        "com.simibubi.create.content.contraptions.elevator.ElevatorColumn"}, remap = false)
abstract class CreateBuildHeightMixin {
    @WrapOperation(method = {"isAreaActuallyLoaded", "getExtensionRange", "getMinValue"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$cubicBottom(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = {"isAreaActuallyLoaded", "updatePlacementContext"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$cubicTop(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }

    @WrapOperation(method = "gatherAll", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getMinBuildHeight()I"))
    private int earthplatform$columnBottom(LevelAccessor level, Operation<Integer> original) {
        return level instanceof Level real && CubicLevel.of(real) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "gatherAll", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getMaxBuildHeight()I"))
    private int earthplatform$columnTop(LevelAccessor level, Operation<Integer> original) {
        return level instanceof Level real && CubicLevel.of(real) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }
}
