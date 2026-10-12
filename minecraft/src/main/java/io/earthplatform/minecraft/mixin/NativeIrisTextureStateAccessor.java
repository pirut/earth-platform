package io.earthplatform.minecraft.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Pinned Iris DSA tracks even non-2D texture IDs in this vanilla cache slot. */
@Mixin(targets="com.mojang.blaze3d.platform.GlStateManager$TextureState")
public interface NativeIrisTextureStateAccessor {
    @Accessor("binding") int earthplatform$binding();
    @Accessor("binding") void earthplatform$binding(int value);
}
