package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Cancel automatic/logout player.dat writes before the vanilla try/catch or save observer runs. */
@Mixin(value=PlayerDataStorage.class,priority=2200)
abstract class SurvivalSessionSaveHoldMixin {
 @Inject(method="save(Lnet/minecraft/world/entity/player/Player;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdSave(Player player,CallbackInfo ci){if(NativeSessionExecutionGate.blocksSave(player,(PlayerDataStorage)(Object)this))ci.cancel();}
}
