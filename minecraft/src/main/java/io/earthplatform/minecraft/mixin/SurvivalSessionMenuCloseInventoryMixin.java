package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeMenuCloseCapacity;
import io.earthplatform.minecraft.survival.interaction.host.NativeMenuClosePreparation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** Two exact native return-to-inventory sinks; inert outside private detached close/capacity scope. */
@Mixin(Inventory.class)
abstract class SurvivalSessionMenuCloseInventoryMixin {
 @Redirect(method="placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;"),require=1,allow=1)
 private ItemEntity earthplatform$prepareCloseDrop(Player player,ItemStack stack,boolean includeThrower){return NativeMenuCloseCapacity.drop((Inventory)(Object)this,player,stack,includeThrower);}
 @Redirect(method="placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V",at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"),require=1,allow=1)
 private void earthplatform$prepareClosePacket(ServerGamePacketListenerImpl connection,Packet<?> packet){NativeMenuClosePreparation.packet((Inventory)(Object)this,connection,packet);}
}
