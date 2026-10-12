package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation;
import java.util.Optional;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact native crafting-grid boundary; outside the private detached scope all calls are native. */
@Mixin(CraftingMenu.class)
public abstract class SurvivalCraftingGridPreparationMixin {
    @Inject(method = "slotChangedCraftingGrid", at = @At("HEAD"), require = 1)
    private static void earthplatform$enter(AbstractContainerMenu menu, Level level, Player player,
            CraftingContainer grid, ResultContainer result, RecipeHolder<CraftingRecipe> previous, CallbackInfo ci) {
        NativeInventoryMenuPreparation.enterGrid(menu, level, player, grid, result);
    }
    @Inject(method = "slotChangedCraftingGrid", at = @At("TAIL"), require = 1)
    private static void earthplatform$exit(AbstractContainerMenu menu, Level level, Player player,
            CraftingContainer grid, ResultContainer result, RecipeHolder<CraftingRecipe> previous, CallbackInfo ci) {
        NativeInventoryMenuPreparation.exitGrid(menu);
    }
    @Redirect(method = "slotChangedCraftingGrid", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/item/crafting/RecipeManager;getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;"), require = 1)
    private static Optional<RecipeHolder<CraftingRecipe>> earthplatform$recipe(RecipeManager manager,
            RecipeType<CraftingRecipe> type, RecipeInput input, Level level, RecipeHolder<CraftingRecipe> previous) {
        return NativeInventoryMenuPreparation.findRecipe(manager, type, (CraftingInput) input, level, previous);
    }
    @Redirect(method = "slotChangedCraftingGrid", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"), require = 1)
    private static void earthplatform$packet(ServerGamePacketListenerImpl connection, Packet<?> packet) {
        NativeInventoryMenuPreparation.packet(connection, packet);
    }
}
