package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthCinema;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a camera shot is recorded the game's clock is the video's, slower than the server's, and
 * each second the server's time of day would jump the sun ahead of the video: the server's time
 * waits until the recording is done. See {@link EarthCinema}.
 */
@Mixin(ClientPacketListener.class)
abstract class ShotDayTimeMixin {
    @Inject(method = "handleSetTime", at = @At("HEAD"), cancellable = true)
    private void earthplatform$videoTime(ClientboundSetTimePacket packet, CallbackInfo info) {
        if (EarthCinema.recording()) info.cancel();
    }
}
