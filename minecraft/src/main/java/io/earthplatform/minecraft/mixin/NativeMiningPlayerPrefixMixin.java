package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation;
import net.minecraft.server.level.ServerPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Preserve the original Util.getMillis call, but do not change live player data during preparation. */
@Mixin(ServerPlayer.class)
abstract class NativeMiningPlayerPrefixMixin {
    @Inject(method="resetLastActionTime()V",at=@At("HEAD"),require=1,allow=1)
    private void earthplatform$requireOriginalMiningClock(CallbackInfo ci) {
        NativeMiningControlPreparation.requireOriginalClock((ServerPlayer)(Object)this);
    }
    @WrapOperation(method="resetLastActionTime()V",at=@At(value="FIELD",target="Lnet/minecraft/server/level/ServerPlayer;lastActionTime:J",opcode=Opcodes.PUTFIELD),require=1,allow=1)
    private void earthplatform$captureNativeActionTime(ServerPlayer receiver,long value,Operation<Void> original) {
        if(!NativeMiningControlPreparation.captureLastActionTime(receiver,value))original.call(receiver,value);
    }
}
