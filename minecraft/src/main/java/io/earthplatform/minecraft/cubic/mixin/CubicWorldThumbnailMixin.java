package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.client.WorldThumbnailReadiness;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps vanilla's capture/retry/crop/save path, using cubic terrain instead of empty carrier sections. */
@Mixin(GameRenderer.class)
abstract class CubicWorldThumbnailMixin {
    @WrapOperation(method = "takeAutoScreenshot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;countRenderedSections()I"))
    private int earthplatform$thumbnailSections(LevelRenderer renderer, Operation<Integer> original) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world == null) return original.call(renderer);
        return WorldThumbnailReadiness.ready(world.renderer().visibleMeshCount(), world.renderer().pendingCount(),
                world.far().drawnRanges(), world.far().settledInViewPercent()) ? 11 : 0;
    }
}
