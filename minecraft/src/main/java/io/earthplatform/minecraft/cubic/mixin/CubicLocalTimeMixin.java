package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthSolarClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelTimeAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Localizes client {@code getTimeOfDay(float)} by longitude, latitude and season in Earth worlds,
 * catching vanilla sky, lightmap, clocks, far terrain, layered clouds and Iris shader celestial uniforms.
 */
@Mixin(LevelTimeAccess.class)
interface CubicLocalTimeMixin {
    @Inject(method = "getTimeOfDay(F)F", at = @At("HEAD"), cancellable = true)
    private void earthplatform$localTimeOfDay(float partialTick, CallbackInfoReturnable<Float> result) {
        if ((Object) this instanceof ClientLevel level) {
            float local = EarthSolarClient.timeOfDay(level, partialTick);
            if (!Float.isNaN(local)) result.setReturnValue(local);
        }
    }
}
