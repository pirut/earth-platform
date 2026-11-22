package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.EarthSolar;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Evaluates daylight detectors from each block's own longitude, latitude and season in Earth worlds. */
@Mixin(DaylightDetectorBlock.class)
abstract class CubicDaylightDetectorMixin {
    @Inject(method = "updateSignalStrength", at = @At("HEAD"), cancellable = true)
    private static void earthplatform$localDaylightSignal(BlockState state, Level level, BlockPos pos, CallbackInfo info) {
        float localTime = EarthSolar.serverTimeOfDay(level, pos.getX() + 0.5, pos.getZ() + 0.5);
        if (Float.isNaN(localTime)) return;
        int darken = EarthSolar.skyDarken(localTime, level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
        int signal = level.getBrightness(LightLayer.SKY, pos) - darken;
        float sunAngle = localTime * (float) (Math.PI * 2.0);
        if (state.getValue(DaylightDetectorBlock.INVERTED)) {
            signal = 15 - signal;
        } else if (signal > 0) {
            float target = sunAngle < (float) Math.PI ? 0.0F : (float) (Math.PI * 2.0);
            sunAngle += (target - sunAngle) * 0.2F;
            signal = Math.round((float) signal * Mth.cos(sunAngle));
        }
        signal = Mth.clamp(signal, 0, 15);
        if (state.getValue(DaylightDetectorBlock.POWER) != signal) {
            level.setBlock(pos, state.setValue(DaylightDetectorBlock.POWER, signal), 3);
        }
        info.cancel();
    }
}
