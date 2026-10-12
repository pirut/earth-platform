package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Original native packet and ack-prefix code; writes are redirected only in a private plan scope. */
@Mixin(value=ServerGamePacketListenerImpl.class,priority=2450)
abstract class NativeMiningPacketPrefixMixin {
    @Redirect(method="handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V",
        at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayerGameMode;handleBlockBreakAction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;Lnet/minecraft/core/Direction;II)V"),require=1,allow=1)
    private void earthplatform$detachedMiningMode(ServerPlayerGameMode receiver,BlockPos pos,ServerboundPlayerActionPacket.Action action,
            Direction face,int maxBuildHeight,int sequence) {
        if(!NativeMiningControlPreparation.invokePacketMode(receiver,pos,action,face,maxBuildHeight,sequence))
            receiver.handleBlockBreakAction(pos,action,face,maxBuildHeight,sequence);
    }
    @WrapOperation(method={"handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V","tick()V"},
        at=@At(value="FIELD",target="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;ackBlockChangesUpTo:I",opcode=Opcodes.PUTFIELD),require=2,allow=2)
    private void earthplatform$captureMiningAcknowledgement(ServerGamePacketListenerImpl receiver,int value,Operation<Void> original) {
        if(!NativeMiningControlPreparation.writeAcknowledgement(receiver,value))original.call(receiver,value);
    }
    @WrapOperation(method="tick()V",at=@At(value="FIELD",target="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;ackBlockChangesUpTo:I",opcode=Opcodes.GETFIELD),require=2,allow=2)
    private int earthplatform$readMiningAcknowledgement(ServerGamePacketListenerImpl receiver,Operation<Integer> original) {
        return NativeMiningControlPreparation.readAcknowledgement(receiver,original.call(receiver));
    }
    @Inject(method="tick()V",at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;resetPosition()V"),cancellable=true,require=1,allow=1)
    private void earthplatform$stopAfterOriginalAcknowledgement(CallbackInfo ci) {
        if(NativeMiningControlPreparation.finishAckPrefix((ServerGamePacketListenerImpl)(Object)this))ci.cancel();
    }
}
