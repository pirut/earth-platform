package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.client.FarRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Far terrain reaches kilometres past the render distance, so the cubic world's projection keeps
 * it inside the far clip plane. Applied to the perspective call itself so it also holds when a
 * shader loader replaces the depth-far lookup.
 */
@Mixin(GameRenderer.class)
abstract class CubicFarPlaneMixin {
    @ModifyArg(method = "getProjectionMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;perspective(FFFF)Lorg/joml/Matrix4f;"), index = 3)
    private float earthplatform$farTerrainDepth(float far) {
        return ClientCubeWorld.current() != null ? Math.max(far, FarRenderer.DEPTH_FAR) : far;
    }
}
