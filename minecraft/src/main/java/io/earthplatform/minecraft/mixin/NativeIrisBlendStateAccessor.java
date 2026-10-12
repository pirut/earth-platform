package io.earthplatform.minecraft.mixin;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=BlendModeStorage.class, remap=false)
public interface NativeIrisBlendStateAccessor {
    @Accessor("originalBlendEnable") static boolean earthplatform$enabled(){throw new AssertionError("Mixin missing");}
    @Accessor("originalBlendEnable") static void earthplatform$enabled(boolean value){throw new AssertionError("Mixin missing");}
    @Accessor("originalBlend") static BlendMode earthplatform$blend(){throw new AssertionError("Mixin missing");}
    @Accessor("originalBlend") static void earthplatform$blend(BlendMode value){throw new AssertionError("Mixin missing");}
    @Accessor("blendLocked") static void earthplatform$locked(boolean value){throw new AssertionError("Mixin missing");}
}
