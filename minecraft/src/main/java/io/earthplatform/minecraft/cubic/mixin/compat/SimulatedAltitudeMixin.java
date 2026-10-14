package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The altitude sensor spreads its signal over the level's build range. In Earth that range is the
 * carrier's, so a ship at any real height read as above the top. There the sensor spans sea level to
 * 10 km up instead, which covers every mountain and the jet stream.
 */
@Pseudo
@Mixin(targets = {
        "dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorBlockEntity",
        "dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorMovementBehaviour"
}, remap = false)
abstract class SimulatedAltitudeMixin {
    @WrapOperation(method = {"toWorldHeight", "toNormalHeight", "renderInContraption"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$sensorBottom(Level level, Operation<Integer> original) {
        return Cubic.isCubic(level.dimension()) ? SableCompat.SENSOR_MIN_Y : original.call(level);
    }

    @WrapOperation(method = {"toWorldHeight", "toNormalHeight", "renderInContraption"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$sensorTop(Level level, Operation<Integer> original) {
        return Cubic.isCubic(level.dimension()) ? SableCompat.SENSOR_MAX_Y : original.call(level);
    }
}
