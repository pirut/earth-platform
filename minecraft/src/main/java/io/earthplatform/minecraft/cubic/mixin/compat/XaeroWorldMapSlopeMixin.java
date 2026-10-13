package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.compat.CubicMaps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Xaero's World Map keeps each pixel's height in 12 bits when it shades slopes against the previous
 * row, so above Y 2047 the stored neighbour wraps and every chunk edge gets a dark line. Real slopes
 * between neighbouring pixels are far below 2048 blocks, so the neighbour is unwrapped to the value
 * closest to this pixel's height.
 */
@Pseudo
@Mixin(targets = "xaero.map.region.MapBlock", remap = false)
abstract class XaeroWorldMapSlopeMixin {
    @ModifyExpressionValue(method = "fixHeightType", require = 0,
            at = @At(value = "INVOKE", target = "Lxaero/map/region/texture/LeafRegionTexture;getHeight(II)I", ordinal = 0))
    private int earthplatform$unwrapPrev(int stored, @Local(argsOnly = true, ordinal = 2) int height) {
        return CubicMaps.unwrapHeight(stored, height);
    }

    @ModifyExpressionValue(method = "fixHeightType", require = 0,
            at = @At(value = "INVOKE", target = "Lxaero/map/region/texture/LeafRegionTexture;getHeight(II)I", ordinal = 1))
    private int earthplatform$unwrapDiagonal(int stored, @Local(argsOnly = true, ordinal = 2) int height) {
        return CubicMaps.unwrapHeight(stored, height);
    }
}
