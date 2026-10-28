package io.earthplatform.minecraft.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The wind does the work in the jet stream, so elytra don't wear while riding it. */
@Mixin(LivingEntity.class)
abstract class ElytraWearMixin {
    @WrapOperation(method = "updateFallFlying", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V"))
    private void earthplatform$noWear(ItemStack stack, int amount, LivingEntity entity, EquipmentSlot slot, Operation<Void> original) {
        if (!JetStream.riding(entity)) original.call(stack, amount, entity, slot);
    }
}
