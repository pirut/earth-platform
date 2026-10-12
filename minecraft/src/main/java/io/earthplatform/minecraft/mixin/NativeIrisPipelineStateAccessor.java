package io.earthplatform.minecraft.mixin;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=IrisRenderingPipeline.class, remap=false)
public interface NativeIrisPipelineStateAccessor {
    @Accessor("isMainBound") boolean earthplatform$isMainBound();
    @Accessor("isMainBound") void earthplatform$isMainBound(boolean value);
    @Accessor("currentNormalTexture") void earthplatform$normalTexture(int value);
    @Accessor("currentSpecularTexture") void earthplatform$specularTexture(int value);
}
