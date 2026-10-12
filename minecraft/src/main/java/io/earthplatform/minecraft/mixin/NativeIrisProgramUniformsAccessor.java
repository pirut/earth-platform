package io.earthplatform.minecraft.mixin;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=ProgramUniforms.class, remap=false)
public interface NativeIrisProgramUniformsAccessor {
    @Accessor("active") static ProgramUniforms earthplatform$active() { throw new AssertionError("Mixin missing"); }
}
