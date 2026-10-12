package io.earthplatform.minecraft.mixin;
import net.irisshaders.iris.gl.sampler.SamplerBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=SamplerBinding.class, remap=false)
public interface NativeIrisSamplerBindingAccessor {
    @Accessor("textureUnit") int earthplatform$unit();
}
