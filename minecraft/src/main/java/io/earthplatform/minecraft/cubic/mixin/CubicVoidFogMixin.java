package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla fades fog to black as the camera nears the bottom of the world. The cubic world's
 * carrier bottom is Y 0, so every ocean deeper than about 30 m went black under water; the fade
 * now starts at the cubic world's true bottom instead, and the deep sea darkens with depth in
 * {@link io.earthplatform.minecraft.cubic.client.CubicClient#fogColour}. Applied after
 * {@link EarthFogColourMixin}, whose floor 1,000 m under the sea still blacked out the trenches.
 */
@Mixin(value = FogRenderer.class, priority = 500)
abstract class CubicVoidFogMixin {
    @ModifyExpressionValue(method = "setupColor", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinBuildHeight()I"))
    private static int earthplatform$cubicBottom(int floor, @Local(argsOnly = true) ClientLevel level) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : floor;
    }
}
