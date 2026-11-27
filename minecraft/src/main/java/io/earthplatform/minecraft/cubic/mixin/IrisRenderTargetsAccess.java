package io.earthplatform.minecraft.cubic.mixin;

import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives the Voxy draw the same color textures that Iris uses for terrain. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public interface IrisRenderTargetsAccess {
    @Accessor(value = "renderTargets", remap = false)
    RenderTargets earthplatform$renderTargets();
}
