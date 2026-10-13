package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthClouds;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Earth's lower sky stays sky in places below sea level; see {@link EarthClouds#HORIZON_Y}. */
@Mixin(ClientLevel.ClientLevelData.class)
abstract class EarthHorizonMixin {
    @Inject(method = "getHorizonHeight", at = @At("HEAD"), cancellable = true)
    private void earthplatform$lowHorizon(LevelHeightAccessor level, CallbackInfoReturnable<Double> result) {
        if (EarthClouds.earthSky(level)) result.setReturnValue(EarthClouds.HORIZON_Y);
    }
}
