package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess;
import io.earthplatform.minecraft.enginebridge.NativePlayerVoidSafety;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stock min-Y applies everywhere except a bounded, exact-player diagnostic native-height lease. */
@Mixin(Entity.class)
abstract class CorePlayerVoidMixin {
    @Inject(method="checkBelowWorld()V",at=@At("HEAD"),cancellable=true)
    private void earthplatform$nativePlayerVoid(CallbackInfo result){
        Entity entity=(Entity)(Object)this;
        if(entity instanceof Player p&&((NativeCollisionLevelAccess)p.level()).earthplatform$coreCollisionView() instanceof NativePlayerVoidSafety safety
                &&safety.preventsStockVoidCheck(p.getUUID(),p.getBoundingBox()))result.cancel();
    }
}
