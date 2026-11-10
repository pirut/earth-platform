package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthCinema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A shader pack's clock ({@code frameTimeCounter}, which moves its water and clouds) runs on the
 * wall clock; while a camera shot is recorded it runs on the video's instead. Skipped when Iris is
 * not installed. See {@link EarthCinema#shaderClock}.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
abstract class ShotIrisClockMixin {
    @ModifyVariable(method = "beginFrame", at = @At("HEAD"), argsOnly = true, require = 0, remap = false)
    private long earthplatform$videoClock(long frameStartTime) {
        return EarthCinema.shaderClock(frameStartTime);
    }
}
