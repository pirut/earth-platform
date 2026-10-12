package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation;
import java.util.Collection;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.RecipeCraftingHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve native award order while recording only this exact detached result's metadata. */
@Mixin(RecipeCraftingHolder.class)
public interface SurvivalRecipeAwardPreparationMixin {
    @Redirect(method = "awardUsedRecipes", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/player/Player;triggerRecipeCrafted(Lnet/minecraft/world/item/crafting/RecipeHolder;Ljava/util/List;)V"), require = 1)
    private void earthplatform$crafted(Player player, RecipeHolder<?> recipe, List<ItemStack> input) {
        NativeInventoryMenuPreparation.crafted((RecipeCraftingHolder) this, player, recipe, input);
    }
    @Redirect(method = "awardUsedRecipes", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/player/Player;awardRecipes(Ljava/util/Collection;)I"), require = 1)
    private int earthplatform$unlock(Player player, Collection<RecipeHolder<?>> recipes) {
        return NativeInventoryMenuPreparation.unlocked((RecipeCraftingHolder) this, player, recipes);
    }
}
