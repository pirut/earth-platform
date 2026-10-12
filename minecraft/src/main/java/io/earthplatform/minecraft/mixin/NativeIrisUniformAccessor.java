package io.earthplatform.minecraft.mixin;
import com.mojang.blaze3d.shaders.Uniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Uniform.class)
public interface NativeIrisUniformAccessor {
    @Accessor("dirty") boolean earthplatform$dirty();
    @Accessor("dirty") void earthplatform$dirty(boolean value);
}
