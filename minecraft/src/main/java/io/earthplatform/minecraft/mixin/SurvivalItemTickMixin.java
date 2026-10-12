package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost;
import io.earthplatform.minecraft.survival.interaction.host.NativeItemTickReadScope;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gate before old-pose/tickCount changes and Pre/Post events; ordinary levels remain unchanged. */
@Mixin(ServerLevel.class)
abstract class SurvivalItemTickMixin {
    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$ownedItemTick(Entity entity, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (NativeItemTickReadScope.isExecuting(level, entity)) return;
        if (!(entity instanceof ItemEntity item)) return;
        SurvivalLevelBinding receiverBinding = SurvivalLevelBinding.find(level);
        SurvivalLevelBinding sourceBinding = SurvivalLevelBinding.find(item.level());
        if (receiverBinding == null && sourceBinding == null) return;
        if (item.level() != level || sourceBinding != receiverBinding)
            throw new SurvivalRefusal("Bound native item cannot tick through a foreign ServerLevel receiver");
        NativeEffectDeliveryHost host = NativeEffectDeliveryHost.findAttached(level);
        if (host == null) throw new SurvivalRefusal("Native item tick has no attached effect owner");
        host.dispatchItemTick(item); callback.cancel();
    }
}
