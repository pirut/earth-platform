package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * CC: Tweaked's wireless modems reach further the higher they are: from the normal range at Y 96
 * up to the high-altitude range at the top of the world. Measured against the cubic level's
 * 16-block carrier the slope turned negative, so above Y 96 a modem's range fell below zero and
 * no message, rednet or GPS, got through; most of the Earth's land is above Y 96. Here the range
 * climbs as in the Overworld, reaching the high-altitude range at Y 320 and keeping it above.
 */
@Pseudo
@Mixin(targets = "dan200.computercraft.shared.peripheral.modem.wireless.WirelessModemPeripheral", remap = false)
abstract class ComputerCraftModemRangeMixin {
    /** The Overworld's build top, where vanilla worlds give a modem its full high-altitude range. */
    @Unique private static final int OVERWORLD_TOP = 320;

    /**
     * The top the range is measured against: the Overworld's, or just above the modem when it is
     * higher, which makes the range come out at exactly the high-altitude range.
     */
    @WrapOperation(method = "getRange", require = 0,
            at = @At(value = "INVOKE", remap = true, target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$top(Level level, Operation<Integer> original, @Local Vec3 position) {
        if (CubicLevel.at(level, (int) Math.floor(position.x), (int) Math.floor(position.z)) == null) return original.call(level);
        return Math.max(OVERWORLD_TOP, (int) Math.ceil(position.y) + 1);
    }
}
