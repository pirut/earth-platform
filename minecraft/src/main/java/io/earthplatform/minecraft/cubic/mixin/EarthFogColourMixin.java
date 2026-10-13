package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.play.client.EarthClouds;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla fades fog to black within 32 blocks of the world's floor. The cubic dimension's nominal
 * floor is Y 0, real lowlands lie below it, so the fade starts at {@link EarthClouds#HORIZON_Y} there.
 */
@Mixin(FogRenderer.class)
abstract class EarthFogColourMixin {
    @ModifyExpressionValue(method = "setupColor", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinBuildHeight()I"))
    private static int earthplatform$deepFloor(int floor, @Local(argsOnly = true) ClientLevel level) {
        return EarthClouds.earthSky(level) ? (int) EarthClouds.HORIZON_Y : floor;
    }
}
