package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.client.LevelPasses;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks each time the level is drawn, which a VR mod does once per eye; see {@link LevelPasses}. */
@Mixin(GameRenderer.class)
abstract class CubicLevelPassMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void earthplatform$levelPass(DeltaTracker deltaTracker, CallbackInfo info) {
        LevelPasses.begin();
    }
}
