package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers Earth's cubic dimension with PM Weather and tracks its configured month length. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.config.ServerConfig", remap = false)
abstract class PMWeatherServerConfigMixin {
    @Shadow public static List<ResourceKey<Level>> validDimensions;
    @Shadow public static int monthLength;

    @Inject(method = "onLoad", at = @At("TAIL"), require = 0)
    private static void earthplatform$enableEarthDimension(@Coerce Object event, CallbackInfo ci) {
        PMWeatherCompat.setMonthLength(monthLength);
        if (validDimensions != null && !validDimensions.contains(Cubic.LEVEL)) {
            List<ResourceKey<Level>> updated = new ArrayList<>(validDimensions);
            updated.add(Cubic.LEVEL);
            validDimensions = updated;
        }
    }
}
