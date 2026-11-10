package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthCinema;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * While a camera shot is recorded the game's clock is the video's, slower than the server's, and
 * each second the server's word on the time would jump the sun ahead of the video: the level's
 * time and time of day follow the frames taken instead, whoever sets them, until the recording is
 * done. See {@link EarthCinema#videoTime}.
 */
@Mixin(ClientLevel.class)
abstract class ShotDayTimeMixin {
    @ModifyVariable(method = "setDayTime", at = @At("HEAD"), argsOnly = true)
    private long earthplatform$videoDayTime(long time) {
        return EarthCinema.videoTime(time, true);
    }

    @ModifyVariable(method = "setGameTime", at = @At("HEAD"), argsOnly = true)
    private long earthplatform$videoGameTime(long time) {
        return EarthCinema.videoTime(time, false);
    }
}
