package io.earthplatform.minecraft.cubic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.earthplatform.minecraft.cubic.play.client.EarthClouds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Earth's raised deck when no other renderer owns the clouds. Create Sky's higher priority
 * lets it draw its deck first and fall back here when disabled or unavailable.
 */
@Mixin(value = LevelRenderer.class, priority = 899)
abstract class EarthCloudsMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void earthplatform$earthClouds(PoseStack pose, Matrix4f view, Matrix4f projection, float partialTick,
                                           double camX, double camY, double camZ, CallbackInfo ci) {
        if (EarthClouds.render(Minecraft.getInstance().level, view, projection, partialTick, camX, camY, camZ)) ci.cancel();
    }
}
