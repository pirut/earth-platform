package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthCinema;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game's clock while a camera shot is recorded: each frame taken is worth exactly one video
 * frame of game time, and a frame held for its terrain is worth none, however long either took on
 * the wall clock. Every other frame of the game keeps the clock it has. See {@link EarthCinema}.
 */
@Mixin(DeltaTracker.Timer.class)
abstract class ShotTimerMixin {
    @Shadow private float deltaTicks;
    @Shadow private float deltaTickResidual;
    @Shadow private long lastMs;

    @Inject(method = "advanceGameTime", at = @At("HEAD"), cancellable = true)
    private void earthplatform$shotClock(long time, CallbackInfoReturnable<Integer> info) {
        float ticks = EarthCinema.frameTicks();
        if (Float.isNaN(ticks)) return;
        deltaTicks = ticks;
        deltaTickResidual += ticks;
        int whole = (int) deltaTickResidual;
        deltaTickResidual -= whole;
        lastMs = time;
        info.setReturnValue(whole);
    }
}
