package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import net.minecraft.client.server.IntegratedPlayerList;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Fences singleplayer cached NBT before the original saveWithoutId call can observe Earth. */
@Mixin(value=IntegratedPlayerList.class,priority=2300)
abstract class InstalledMarinPlayerSaveHoldMixin {
 @Inject(method="save(Lnet/minecraft/server/level/ServerPlayer;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdCachedSave(ServerPlayer player,CallbackInfo ci){
  if(NativeSessionExecutionGate.blocksPlayerListSave(player))ci.cancel();
 }
}
