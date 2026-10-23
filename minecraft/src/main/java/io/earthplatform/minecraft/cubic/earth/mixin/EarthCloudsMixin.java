package io.earthplatform.minecraft.cubic.earth.mixin;

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
 * In the Earth, clouds float kilometres up instead of at Y 192. Applied ahead of other mods' cloud
 * renderers (Sodium's replaces this method the same way) so the Earth's deck wins only there.
 */
@Mixin(value = LevelRenderer.class, priority = 900)
abstract class EarthCloudsMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void earthplatform$earthClouds(PoseStack pose, Matrix4f view, Matrix4f projection, float partialTick,
                                           double camX, double camY, double camZ, CallbackInfo ci) {
        if (EarthClouds.render(Minecraft.getInstance().level, view, projection, partialTick, camX, camY, camZ)) ci.cancel();
    }
}
