package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Native keepalive remains live. Movement/menu/action packets are discarded before scheduling.
 * Teleport acknowledgement is intentionally NOT blocked; the prepared transfer binds its exact ID.
 * Commands are gated because even an otherwise benign command can mutate this exact player.
 */
@Mixin(value=ServerGamePacketListenerImpl.class,priority=2100)
abstract class SurvivalSessionConnectionHoldMixin {
 @Shadow public ServerPlayer player;
 @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method="handleAcceptTeleportPacket(Lnet/minecraft/network/protocol/game/ServerboundAcceptTeleportationPacket;)V")
 private void earthplatform$scopedNativeAck(net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket packet,
     com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original){
  NativeSessionExecutionGate.runTeleportAck((ServerGamePacketListenerImpl)(Object)this,player,packet.getId(),()->original.call(packet));
 }
 @Inject(method="tick()V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdListenerTick(CallbackInfo ci){io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.rejectEffect(player,"connection tick");if(NativeSessionExecutionGate.blocksNative(player)){
  if(io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation.permitsAckPrefix((ServerGamePacketListenerImpl)(Object)this))return;
  io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost.dispatchNativeMiningPulse((ServerGamePacketListenerImpl)(Object)this,player);
  ((SurvivalSessionCommonAccess)(Object)this).earthplatform$keepSessionConnectionAlive();ci.cancel();}}
 @Inject(method="handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$heldNativeMining(net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet,CallbackInfo ci) {
  if(io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation.enterPacket((ServerGamePacketListenerImpl)(Object)this,player,packet))return;
  if(NativeSessionExecutionGate.blocksNative(player)) {
   io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost.dispatchNativeMiningPacket((ServerGamePacketListenerImpl)(Object)this,player,packet);ci.cancel();
  }
 }
 @Inject(method="handleChatCommand",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$heldTypedCommand(net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet,CallbackInfo ci){
  io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation.rejectHeldIngress("held typed command");
  if(io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionRuntime.offerControl((ServerGamePacketListenerImpl)(Object)this,player,packet.command())
    || NativeSessionExecutionGate.blocksNative(player))ci.cancel();
 }
 @Inject(method="handleSignedChatCommand",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$heldTypedSignedCommand(net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet,CallbackInfo ci){
  io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation.rejectHeldIngress("held typed signed command");
  if(io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionRuntime.offerControl((ServerGamePacketListenerImpl)(Object)this,player,packet.command())
    || NativeSessionExecutionGate.blocksNative(player))ci.cancel();
 }
 @Inject(method={"handlePlayerInput","handleMoveVehicle","handleRecipeBookSeenRecipePacket","handleRecipeBookChangeSettingsPacket","handleSeenAdvancements","handleCustomCommandSuggestions","handleSetCommandBlock","handleSetCommandMinecart","handlePickItem","handleRenameItem","handleSetBeaconPacket","handleSetStructureBlock","handleSetJigsawBlock","handleJigsawGenerate","handleSelectTrade","handleEditBook","handleEntityTagQuery","handleContainerSlotStateChanged","handleBlockEntityTagQuery","handleUseItemOn","handleUseItem","handleTeleportToEntityPacket","handlePaddleBoat","handleSetCarriedItem","handleChat","handleAnimate","handlePlayerCommand","handleInteract","handleClientCommand","handleContainerClose","handleContainerClick","handlePlaceRecipe","handleContainerButtonClick","handleSetCreativeModeSlot","handleSignUpdate","handlePlayerAbilities","handleClientInformation","handleChangeDifficulty","handleLockDifficulty","handleConfigurationAcknowledged","handleDebugSampleSubscription","handleCustomPayload"},at=@At("HEAD"),cancellable=true,require=42,allow=42)
 private void earthplatform$holdMutatingPacket(CallbackInfo ci){io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation.rejectHeldIngress("foreign held packet");if(NativeSessionExecutionGate.blocksNative(player))ci.cancel();}
}
