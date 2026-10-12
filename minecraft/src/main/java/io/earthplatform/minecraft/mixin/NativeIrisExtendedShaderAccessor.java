package io.earthplatform.minecraft.mixin;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=ExtendedShader.class, remap=false)
public interface NativeIrisExtendedShaderAccessor {
    @Accessor("writingToBeforeTranslucent") net.irisshaders.iris.gl.framebuffer.GlFramebuffer earthplatform$beforeFramebuffer();
    @Accessor("writingToAfterTranslucent") net.irisshaders.iris.gl.framebuffer.GlFramebuffer earthplatform$afterFramebuffer();
    @Accessor("images") ProgramImages earthplatform$images();
    @Accessor("samplers") ProgramSamplers earthplatform$samplers();
}
