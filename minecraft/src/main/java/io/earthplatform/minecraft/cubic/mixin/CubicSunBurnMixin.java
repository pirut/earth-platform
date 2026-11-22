package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.EarthSolar;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Burns undead mobs according to local daylight at their own position when localized day/night is on. */
@Mixin(Mob.class)
abstract class CubicSunBurnMixin {
    @Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
    private void earthplatform$localSunBurn(CallbackInfoReturnable<Boolean> result) {
        Mob self = (Mob) (Object) this;
        Level level = self.level();
        float localTime = EarthSolar.serverTimeOfDay(level, self.getX(), self.getZ());
        if (Float.isNaN(localTime)) return;
        int darken = EarthSolar.skyDarken(localTime, level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
        if (darken >= 4) {
            result.setReturnValue(false);
            return;
        }
        BlockPos eye = BlockPos.containing(self.getX(), self.getEyeY(), self.getZ());
        int raw = level.hasChunkAt(self.getBlockX(), self.getBlockZ()) ? level.getMaxLocalRawBrightness(eye, darken) : 0;
        float norm = (float) raw / 15.0F;
        float brightness = Mth.lerp(level.dimensionType().ambientLight(), norm / (4.0F - 3.0F * norm), 1.0F);
        boolean sheltered = self.isInWaterRainOrBubble() || self.isInPowderSnow || self.wasInPowderSnow;
        result.setReturnValue(brightness > 0.5F && self.getRandom().nextFloat() * 30.0F < (brightness - 0.4F) * 2.0F
                && !sheltered && level.canSeeSky(eye));
    }
}
