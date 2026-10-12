package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.host.NativeItemTickReadScope;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The native outer tick owns old pose, tickCount and events; a raw bound item.tick is forbidden. */
@Mixin(ItemEntity.class)
abstract class SurvivalItemTickGuardMixin {
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void earthplatform$requireAcceptedOuterTick(CallbackInfo callback) {
        ItemEntity source = (ItemEntity) (Object) this;
        if (SurvivalLevelBinding.find(source.level()) != null && !NativeItemTickReadScope.isExecuting(source))
            throw new SurvivalRefusal("Bound native item requires its accepted ServerLevel outer tick");
    }
}
