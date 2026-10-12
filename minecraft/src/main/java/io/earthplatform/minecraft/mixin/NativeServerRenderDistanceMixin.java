package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.coregate.RollingGateClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class NativeServerRenderDistanceMixin implements io.earthplatform.minecraft.coregate.NativeDistanceOptionsAccess {
    @Shadow private int serverRenderDistance;
    @Override public int serverRenderDistance(){return serverRenderDistance;}
    @Inject(method="setServerRenderDistance(I)V",at=@At("HEAD"),require=1,allow=1)
    private void earthplatform$beforeServerDistance(int value,CallbackInfo ci){
        Minecraft game=Minecraft.getInstance();
        if(game!=null&&game.options==(Object)this&&serverRenderDistance!=value)RollingGateClient.beforeNativeDistanceMutation();
    }
}
