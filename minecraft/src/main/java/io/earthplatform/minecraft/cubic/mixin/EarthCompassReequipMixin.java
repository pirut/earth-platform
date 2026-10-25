package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.EarthCompass;
import net.minecraft.world.item.CompassItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A compass that follows another player has its needle moved every few seconds; the hand holding
 * it would dip and come back up each time, as for a new item, so it keeps still instead.
 */
@Mixin(CompassItem.class)
abstract class EarthCompassReequipMixin {
    // Overrides IItemExtension's default, which Item leaves alone.
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        if (!slotChanged && EarthCompass.sameFollower(oldStack, newStack)) return false;
        return !oldStack.equals(newStack);
    }
}
