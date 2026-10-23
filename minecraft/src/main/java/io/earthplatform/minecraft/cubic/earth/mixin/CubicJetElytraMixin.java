package io.earthplatform.minecraft.cubic.earth.mixin;

import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The wind does the work in the jet stream, so elytra don't wear while riding it. */
@Mixin(ElytraItem.class)
abstract class CubicJetElytraMixin {
    @Inject(method = "elytraFlightTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void earthplatform$noWear(ItemStack stack, LivingEntity entity, int flightTicks, CallbackInfoReturnable<Boolean> result) {
        if (JetStream.riding(entity)) result.setReturnValue(true);
    }
}
