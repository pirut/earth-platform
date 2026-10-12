package io.earthplatform.minecraft.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Direct CPU alias storage; no setters that invoke Iris texture-tracker callbacks. */
@Mixin(RenderSystem.class)
public interface NativeShaderRenderSystemAccessor {
    @Accessor("shaderTextures")
    static int[] earthplatform$rawShaderTextures() { throw new AssertionError("Mixin missing"); }
}
