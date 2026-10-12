package io.earthplatform.minecraft.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import io.earthplatform.minecraft.scene.NativeShaderTextureAliases;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear only exact deleted-name aliases in the admitted carrier's real reload. */
@Mixin(GlStateManager.class)
public abstract class NativeShaderTextureDeletionMixin {
    @Inject(method = "_deleteTexture(I)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL11;glDeleteTextures(I)V", shift = At.Shift.BEFORE, remap = false),
            require = 1, allow = 1)
    private static void earthplatform$beforeTextureDelete(int texture, CallbackInfo callback) {
        if (NativeShaderTextureAliases.active())
            NativeShaderTextureAliases.beforeDelete(Minecraft.getInstance().level,
                    NativeShaderRenderSystemAccessor.earthplatform$rawShaderTextures(), texture);
    }

    @Inject(method = "_deleteTextures([I)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL11;glDeleteTextures([I)V", shift = At.Shift.BEFORE, remap = false),
            require = 1, allow = 1)
    private static void earthplatform$beforeTexturesDelete(int[] textures, CallbackInfo callback) {
        if (NativeShaderTextureAliases.active())
            NativeShaderTextureAliases.beforeDelete(Minecraft.getInstance().level,
                    NativeShaderRenderSystemAccessor.earthplatform$rawShaderTextures(), textures);
    }
}
