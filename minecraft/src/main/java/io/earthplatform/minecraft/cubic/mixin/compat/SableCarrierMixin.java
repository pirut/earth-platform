package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.compat.SableCarrier;
import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With Sable installed, the cubic level's carrier takes the Overworld's build range and leaves
 * Sable's ship plots to chunks. Decided once, as the level is built, before its chunk source and
 * light engine size themselves from the build range.
 */
@Mixin(Level.class)
abstract class SableCarrierMixin implements SableCarrier {
    @Unique private boolean earthplatform$tallCarrier;

    @Override public boolean earthplatform$tallCarrier() { return earthplatform$tallCarrier; }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void earthplatform$sizeCarrier(CallbackInfo info) {
        Level level = (Level) (Object) this;
        if (!SableCompat.tallCarrier(level)) return;
        earthplatform$tallCarrier = true;
        SableCompat.keepPlots(level);
    }
}
