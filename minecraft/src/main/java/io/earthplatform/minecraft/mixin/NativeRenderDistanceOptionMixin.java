package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.coregate.RollingGateClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Narrow HEAD fence: no unrelated slider, initialization or same-value write invalidates terrain. */
@Mixin(OptionInstance.class)
public abstract class NativeRenderDistanceOptionMixin<T> {
    @Inject(method="set(Ljava/lang/Object;)V",at=@At("HEAD"),require=1,allow=1)
    private void earthplatform$beforeDistanceSelection(T value,CallbackInfo ci){
        Minecraft game=Minecraft.getInstance();
        if(game!=null&&game.options!=null&&game.options.renderDistance()==(Object)this
                &&!java.util.Objects.equals(game.options.renderDistance().get(),value))
            RollingGateClient.beforeNativeDistanceMutation();
    }
}
