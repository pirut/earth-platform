package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.jet.OceanCurrent;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A boat in the ocean current goes far past vanilla's vehicle speed check, which singleplayer already
 * skips, so a server skips it too for a move the current could make (see {@link OceanCurrent#currentMove});
 * and each move a rider sends tells the server how fast its boat goes (see {@link OceanCurrent#moved}).
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class CubicCurrentMoveMixin {
    @Shadow public ServerPlayer player;
    @Unique private double earthplatform$fromX, earthplatform$fromZ;

    @WrapOperation(method = "handleMoveVehicle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"))
    private boolean earthplatform$currentSpeed(ServerGamePacketListenerImpl self, Operation<Boolean> original,
                                               @Local(argsOnly = true) ServerboundMoveVehiclePacket packet) {
        return original.call(self) || OceanCurrent.currentMove(player.getRootVehicle(), packet.getX(), packet.getY(), packet.getZ());
    }

    @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void earthplatform$from(ServerboundMoveVehiclePacket packet, CallbackInfo info) {
        Entity vehicle = player.getRootVehicle();
        earthplatform$fromX = vehicle.getX();
        earthplatform$fromZ = vehicle.getZ();
    }

    @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;move(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void earthplatform$moved(ServerboundMoveVehiclePacket packet, CallbackInfo info) {
        OceanCurrent.moved(player.getRootVehicle(), earthplatform$fromX, earthplatform$fromZ);
    }
}
