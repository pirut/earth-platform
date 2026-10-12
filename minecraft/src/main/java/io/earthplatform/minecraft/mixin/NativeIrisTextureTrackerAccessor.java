package io.earthplatform.minecraft.mixin;
import net.irisshaders.iris.pbr.TextureTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=TextureTracker.class, remap=false)
public interface NativeIrisTextureTrackerAccessor {
    @Accessor("lockBindCallback") boolean earthplatform$locked();
    @Accessor("lockBindCallback") void earthplatform$locked(boolean value);
}
