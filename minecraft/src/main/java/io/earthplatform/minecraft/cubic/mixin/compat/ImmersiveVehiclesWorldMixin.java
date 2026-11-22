package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.ImmersiveVehiclesCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Immersive Vehicles (MTS) derives atmospheric density from {@code Level.getHeight()} and scans
 * downward for ground height until {@code Level.getMinBuildHeight()}. Against the cubic carrier's
 * 0..16 range, air density collapsed to near-zero above sea level and ground scans stopped at Y 0.
 */
@Pseudo
@Mixin(targets = "mcinterface1211.WrapperWorld", remap = false)
abstract class ImmersiveVehiclesWorldMixin {
    @WrapOperation(method = "getMaxHeight()J", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getHeight()I"))
    private int earthplatform$atmosphereHeight(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? ImmersiveVehiclesCompat.ATMOSPHERE_HEIGHT : original.call(level);
    }

    @WrapOperation(method = "getHeight(Lminecrafttransportsimulator/baseclasses/Point3D;)D", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$groundScanBottom(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "getHeight(Lminecrafttransportsimulator/baseclasses/Point3D;)D", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isEmptyBlock(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$groundScanEmpty(Level level, BlockPos pos, Operation<Boolean> original) {
        CubeWorld cubes = CubicLevel.of(level);
        if (cubes == null) return original.call(level, pos);
        return ImmersiveVehiclesCompat.isEmptyForGroundScan(cubes, pos, original.call(level, pos));
    }
}
