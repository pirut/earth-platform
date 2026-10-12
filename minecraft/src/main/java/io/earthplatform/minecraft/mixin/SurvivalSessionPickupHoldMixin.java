package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ItemEntity.class,priority=2200)
abstract class SurvivalSessionPickupHoldMixin {
 @Inject(method="playerTouch(Lnet/minecraft/world/entity/player/Player;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdPickup(Player player,CallbackInfo ci){io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.rejectEffect(player,"holdPickup");if(NativeSessionExecutionGate.blocksNative(player))ci.cancel();}
}
