package io.earthplatform.minecraft.fabric.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.cubic.client.CubicClient;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's fog events (ViewportEvent.ComputeFogColor and RenderFog), where NeoForge fires them. */
@Mixin(FogRenderer.class)
abstract class FogRendererHooksMixin {
    @Shadow private static float fogRed;
    @Shadow private static float fogGreen;
    @Shadow private static float fogBlue;

    /** The deep sea darkens: the final fog colour, just before vanilla clears the screen with it. */
    @Inject(method = "setupColor", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clearColor(FFFF)V", ordinal = 1))
    private static void earthplatform$fogColour(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darken, CallbackInfo info) {
        float light = CubicClient.waterFogLight(camera);
        if (light == 1.0F) return;
        fogRed *= light;
        fogGreen *= light;
        fogBlue *= light;
    }

    /** Terrain fog out at the far terrain's reach. */
    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void earthplatform$fog(Camera camera, FogRenderer.FogMode mode, float farPlaneDistance, boolean thick, float partialTick, CallbackInfo info) {
        float[] fog = CubicClient.fog(mode, camera.getFluidInCamera(), camera, farPlaneDistance);
        if (fog == null) return;
        RenderSystem.setShaderFogStart(fog[0]);
        RenderSystem.setShaderFogEnd(fog[1]);
    }
}
