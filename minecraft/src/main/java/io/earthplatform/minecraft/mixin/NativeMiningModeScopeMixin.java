package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Exact private scratch can run; held live GameMode remains closed to direct calls too. */
@Mixin(value=ServerPlayerGameMode.class,priority=2450)
abstract class NativeMiningModeScopeMixin {
    @Shadow @Final protected ServerPlayer player;
    @Inject(method="tick()V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
    private void earthplatform$exactNativeMiningTick(CallbackInfo ci) {
        if(!NativeMiningControlPreparation.enterMode((ServerPlayerGameMode)(Object)this,true)
                &&NativeSessionExecutionGate.blocksNative(player))ci.cancel();
    }
    @Inject(method="handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
    private void earthplatform$exactNativeMiningPacket(BlockPos pos,ServerboundPlayerActionPacket.Action action,Direction face,int height,int sequence,CallbackInfo ci) {
        if(!NativeMiningControlPreparation.enterMode((ServerPlayerGameMode)(Object)this,false)
                &&NativeSessionExecutionGate.blocksNative(player))ci.cancel();
    }
    @Inject(method="destroyBlock(Lnet/minecraft/core/BlockPos;)Z",at=@At("HEAD"),cancellable=true,require=1,allow=1)
    private void earthplatform$refuseUncomposedNativeDestroy(BlockPos pos,CallbackInfoReturnable<Boolean> ci) {
        if(NativeMiningControlPreparation.captureDestroy((ServerPlayerGameMode)(Object)this,pos)){ci.setReturnValue(true);return;}
        if(NativeSessionExecutionGate.blocksNative(player))ci.setReturnValue(false);
    }
}
