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
 * Quark and Handcrafted checks against the 16-block carrier's build height. In the Earth world
 * Quark's glass and dyed item frames couldn't be hung and its ladders wouldn't extend downwards above
 * Y 16, a Totem of Holding dropped below Y 3 was lifted to Y 3, and Handcrafted statues couldn't be
 * placed above Y 14. They get the cubic world's range here.
 */
@Pseudo
@Mixin(targets = {
        "org.violetmoon.quark.content.building.item.QuarkItemFrameItem",
        "org.violetmoon.quark.content.tweaks.module.EnhancedLaddersModule",
        "org.violetmoon.quark.addons.oddities.module.TotemOfHoldingModule",
        "earth.terrarium.handcrafted.common.blocks.trophies.StatueTrophyBlock"}, remap = false)
abstract class QuarkBuildHeightMixin {
    @WrapOperation(method = {"canPlace", "onInteract"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$outside(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.of(level) != null ? !CubeWorld.inRange(pos.getY()) : original.call(level, pos);
    }

    @WrapOperation(method = "onPlayerDrops", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$bottom(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "getStateForPlacement", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$top(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }
}
