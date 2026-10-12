package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.interaction.host.NativeInventoryMenuPreparation;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Capture the native crafted amount; retain the admitted item's actual native no-op callbacks. */
@Mixin(ItemStack.class)
public abstract class SurvivalCraftedStackPreparationMixin {
    @Redirect(method = "onCraftedBy", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/player/Player;awardStat(Lnet/minecraft/stats/Stat;I)V"), require = 1)
    private void earthplatform$stat(Player player, Stat<?> stat, int amount) {
        NativeInventoryMenuPreparation.statistic((ItemStack) (Object) this, player, stat, amount);
    }
}
