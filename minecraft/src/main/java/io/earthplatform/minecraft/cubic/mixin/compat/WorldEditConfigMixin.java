package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WorldEdit records undo history with positions packed into Y -2048 to 2047 unless its
 * extended-y-limit option is on, so any edit above or below that in the Earth world failed with
 * "Location exceeds long packing limits". It is turned on every time the config loads.
 */
@Pseudo
@Mixin(targets = "com.sk89q.worldedit.util.PropertiesConfiguration", remap = false)
abstract class WorldEditConfigMixin {
    @Inject(method = "load", at = @At("TAIL"), require = 0)
    private void earthplatform$tallHistory(CallbackInfo info) {
        try {
            getClass().getField("extendedYLimit").setBoolean(this, true);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Could not turn on WorldEdit's extended-y-limit; set it in worldedit.properties for edits above Y 2047", failure);
        }
    }
}
