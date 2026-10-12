package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost;
import io.earthplatform.minecraft.survival.interaction.host.NativeItemPickupPreparation;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ordinary worlds use native behavior unchanged; bound survival items require one exact action. */
@Mixin(ItemEntity.class)
abstract class SurvivalItemPickupMixin {
    @Inject(method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$pickup(Player player, CallbackInfo callback) {
        ItemEntity item = (ItemEntity) (Object) this;
        if (NativeItemPickupPreparation.isDetachedCall(item, player)) return;
        if (!(item.level() instanceof ServerLevel level) || SurvivalLevelBinding.find(level) == null) return;
        NativeEffectDeliveryHost host = NativeEffectDeliveryHost.findAttached(level);
        if (host == null) throw new SurvivalRefusal("Native item pickup has no attached effect owner");
        host.dispatchPickup(item, player); callback.cancel();
    }
    @Redirect(method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getInventory()Lnet/minecraft/world/entity/player/Inventory;"))
    private Inventory earthplatform$detachedInventory(Player player) {
        return NativeItemPickupPreparation.inventory((ItemEntity) (Object) this, player);
    }
    @Redirect(method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;awardStat(Lnet/minecraft/stats/Stat;I)V"))
    private void earthplatform$preparedStatistic(Player player, Stat<?> stat, int amount) {
        NativeItemPickupPreparation.awardStat((ItemEntity) (Object) this, player, stat, amount);
    }
    @Redirect(method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;take(Lnet/minecraft/world/entity/Entity;I)V"))
    private void earthplatform$preparedTake(Player player, Entity takenEntity, int amount) {
        NativeItemPickupPreparation.take((ItemEntity) (Object) this, player, takenEntity, amount);
    }
}
