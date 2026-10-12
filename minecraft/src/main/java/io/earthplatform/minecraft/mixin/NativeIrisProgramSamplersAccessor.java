package io.earthplatform.minecraft.mixin;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.sampler.SamplerBinding;
import com.google.common.collect.ImmutableList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=ProgramSamplers.class, remap=false)
public interface NativeIrisProgramSamplersAccessor {
    @Accessor("samplerBindings") ImmutableList<SamplerBinding> earthplatform$bindings();
    @Accessor("active") static ProgramSamplers earthplatform$active() { throw new AssertionError("Mixin missing"); }
}
