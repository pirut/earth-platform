package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Capture native stage and exact XYZ before recipient iteration; no invented actor crack recipient. */
@Mixin(ServerLevel.class)
abstract class NativeMiningCrackScopeMixin {
    @Inject(method="destroyBlockProgress(ILnet/minecraft/core/BlockPos;I)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
    private void earthplatform$captureNativeMiningCrack(int breakerId,BlockPos pos,int stage,CallbackInfo ci) {
        if(NativeMiningControlPreparation.captureCrack((ServerLevel)(Object)this,breakerId,pos,stage))ci.cancel();
    }
}
