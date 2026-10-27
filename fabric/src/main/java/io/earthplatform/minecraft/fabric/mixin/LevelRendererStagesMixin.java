package io.earthplatform.minecraft.fabric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.earthplatform.minecraft.cubic.client.CubicClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge's level render stages, at the points NeoForge's own patch fires them: after each chunk
 * layer (also in a shader pack's shadow pass, which draws chunk layers through this method), after
 * entities, after block entities and after particles. Applied after Sodium's, which replaces the
 * chunk layer method's body.
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
abstract class LevelRendererStagesMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private Frustum cullingFrustum;
    @Shadow private Frustum capturedFrustum;

    private Frustum earthplatform$frustum() { return capturedFrustum != null ? capturedFrustum : cullingFrustum; }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void earthplatform$afterLayer(RenderType type, double x, double y, double z, Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        CubicClient.Stage stage = type == RenderType.cutout() ? CubicClient.Stage.AFTER_CUTOUT_BLOCKS
                : type == RenderType.translucent() ? CubicClient.Stage.AFTER_TRANSLUCENT_BLOCKS : null;
        if (stage != null) CubicClient.render(stage, minecraft.gameRenderer.getMainCamera(), earthplatform$frustum(), modelView, projection,
                new PoseStack(), minecraft.getTimer().getGameTimeDeltaPartialTick(false));
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=blockentities"))
    private void earthplatform$afterEntities(DeltaTracker delta, boolean outline, Camera camera, GameRenderer renderer, LightTexture light,
                                             Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        CubicClient.render(CubicClient.Stage.AFTER_ENTITIES, camera, earthplatform$frustum(), modelView, projection, new PoseStack(), delta.getGameTimeDeltaPartialTick(false));
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=destroyProgress"))
    private void earthplatform$afterBlockEntities(DeltaTracker delta, boolean outline, Camera camera, GameRenderer renderer, LightTexture light,
                                                  Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        CubicClient.render(CubicClient.Stage.AFTER_BLOCK_ENTITIES, camera, earthplatform$frustum(), modelView, projection, new PoseStack(), delta.getGameTimeDeltaPartialTick(false));
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V"))
    private void earthplatform$afterParticles(DeltaTracker delta, boolean outline, Camera camera, GameRenderer renderer, LightTexture light,
                                              Matrix4f modelView, Matrix4f projection, CallbackInfo info) {
        CubicClient.render(CubicClient.Stage.AFTER_PARTICLES, camera, earthplatform$frustum(), modelView, projection, new PoseStack(), delta.getGameTimeDeltaPartialTick(false));
    }
}
