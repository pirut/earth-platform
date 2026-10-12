package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeInstalledOrdinarySaveHooks;
import net.minecraft.client.server.IntegratedPlayerList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(IntegratedPlayerList.class)
abstract class InstalledIntegratedSaveEvidenceMixin {
 @Redirect(method="save(Lnet/minecraft/server/level/ServerPlayer;)V",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;saveWithoutId(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"),require=1,allow=1)
 private CompoundTag earthplatform$cache(ServerPlayer receiver,CompoundTag input,ServerPlayer invocation){
  return NativeInstalledOrdinarySaveHooks.cache(receiver,input,(IntegratedPlayerList)(Object)this,invocation);
 }
}
