package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeMenuClosePreparation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** Exact cursor and grid return lookups remain on the private detached inventory during planning. */
@Mixin(AbstractContainerMenu.class)
abstract class SurvivalSessionMenuCloseContainerMixin {
 @Redirect(method={"removed(Lnet/minecraft/world/entity/player/Player;)V","clearContainer(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/Container;)V"},at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Player;getInventory()Lnet/minecraft/world/entity/player/Inventory;"),require=2,allow=2)
 private Inventory earthplatform$prepareCloseInventory(Player player){return NativeMenuClosePreparation.inventory((AbstractContainerMenu)(Object)this,player);}
}
