package io.earthplatform.minecraft.fabric.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A sprite's pixels as loaded, which NeoForge exposes and vanilla keeps private. */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccess {
    @Accessor("originalImage") NativeImage earthplatform$originalImage();
}
