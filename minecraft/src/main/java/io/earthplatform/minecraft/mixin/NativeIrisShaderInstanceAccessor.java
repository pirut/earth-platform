package io.earthplatform.minecraft.mixin;
import java.util.Map;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ShaderInstance.class)
public interface NativeIrisShaderInstanceAccessor {
    @Accessor("samplerMap") Map<String,Object> earthplatform$samplerMap();
    @Accessor("dirty") boolean earthplatform$dirty();
    @Accessor("dirty") void earthplatform$dirty(boolean value);
}
