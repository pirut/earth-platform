package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.client.CubicLightmapTexture;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LightTexture.class)
abstract class CubicLightmapTextureMixin {
    @WrapOperation(method = "<init>", at = @At(value = "NEW",
            target = "(IIZ)Lnet/minecraft/client/renderer/texture/DynamicTexture;"))
    private DynamicTexture earthplatform$lightmap(int width, int height, boolean clear, Operation<DynamicTexture> original) {
        return CubicLightmapTexture.ENABLED && width == 16 && height == 16
                ? new CubicLightmapTexture(width, height, clear) : original.call(width, height, clear);
    }
}
