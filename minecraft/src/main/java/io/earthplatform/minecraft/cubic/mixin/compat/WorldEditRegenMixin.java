package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.WorldEditCompat;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WorldEdit's //regen builds a throwaway copy of the world from its chunk generator, which in the
 * cubic level is the carrier's flat one. In the Earth world the region is made again from the Earth
 * generator instead (see {@link WorldEditCompat#regenerate}), through WorldEdit's own edit, so //undo works.
 */
@Pseudo
@Mixin(targets = "com.sk89q.worldedit.neoforge.NeoForgeWorld", remap = false)
abstract class WorldEditRegenMixin {
    @Shadow public abstract ServerLevel getWorld();

    @Inject(method = "regenerate", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$regen(@Coerce Object region, @Coerce Object extent, @Coerce Object options, CallbackInfoReturnable<Boolean> result) {
        ServerLevel level = getWorld();
        if (CubicLevel.of(level) == null) return;
        WorldEditCompat.regenerate(level, region, extent, options);
        result.setReturnValue(true);
    }
}
