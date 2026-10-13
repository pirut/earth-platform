package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.Cubic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Sable looks for a ship overhead up to the build top; in Earth it looks the same 384 blocks up as in the Overworld. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.mixinhelpers.entity.sublevels_block_sky.SubLevelsBlockSkyMixinHelper", remap = false)
abstract class SableSkyReachMixin {
    @WrapOperation(method = "checkSkyWithSublevels", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private static int earthplatform$skyReach(Level level, Operation<Integer> original, Level self, BlockPos pos) {
        return Cubic.isCubic(level.dimension()) ? Math.min(CubeWorld.MAX_Y + 1, pos.getY() + 384) : original.call(level);
    }
}
