package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native remaining-items semantics execute once; their fallback cannot touch the live inventory. */
@Mixin(ResultSlot.class)
public abstract class SurvivalResultSlotPreparationMixin {
    @Inject(method = "onTake", at = @At("HEAD"), require = 1)
    private void earthplatform$take(Player player, ItemStack stack, CallbackInfo ci) {
        NativeInventoryMenuPreparation.enterTake((ResultSlot) (Object) this, player, stack);
    }
    @Redirect(method = "onTake", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/item/crafting/RecipeManager;getRemainingItemsFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Lnet/minecraft/core/NonNullList;"), require = 1)
    private NonNullList<ItemStack> earthplatform$remainders(RecipeManager manager, RecipeType<CraftingRecipe> type,
            RecipeInput input, Level level) {
        return NativeInventoryMenuPreparation.remainders((ResultSlot) (Object) this, manager, type, (CraftingInput) input, level);
    }
    @Redirect(method = "onTake", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/player/Player;getInventory()Lnet/minecraft/world/entity/player/Inventory;"), require = 1)
    private Inventory earthplatform$inventory(Player player) {
        return NativeInventoryMenuPreparation.inventory((ResultSlot) (Object) this, player);
    }
    @Redirect(method = "onTake", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;"), require = 1)
    private ItemEntity earthplatform$drop(Player player, ItemStack stack, boolean random) {
        return NativeInventoryMenuPreparation.drop((ResultSlot) (Object) this, player, stack, random);
    }
}
