package io.earthplatform.minecraft.mixin;
import com.mojang.authlib.GameProfile;
import io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionStoreIdentity;
import io.earthplatform.minecraft.survival.source.MarinPlayerEnvironmentV2;
import net.minecraft.client.server.IntegratedPlayerList;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Always present on installed clients, even if entry opt-in is later disabled. No existing
 * per-player installed authority may silently resume ordinary login after a crash/restart.
 * This initial barrier deliberately refuses RETURNED restart too; full sequence recovery is later.
 */
@Mixin(value=IntegratedPlayerList.class,priority=2300)
abstract class InstalledMarinStartupFenceMixin {
 @Shadow public abstract IntegratedServer getServer();
 @Inject(method="canPlayerLogin(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/network/chat/Component;",
   at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$recoverBeforeLogin(java.net.SocketAddress address,GameProfile profile,CallbackInfoReturnable<Component> cir){
  try {
   if(InstalledMarinSessionStoreIdentity.requiresStartupRecovery(getServer().getWorldPath(LevelResource.ROOT),MarinPlayerEnvironmentV2.PROFILE,profile.getId()))
    cir.setReturnValue(Component.literal("Earth survival save requires explicit recovery before login. Its CURRENT and ordinary escrow were left unchanged."));
  } catch(java.io.IOException|RuntimeException failure){
   cir.setReturnValue(Component.literal("Earth survival recovery check could not verify this save. Login remains closed: "+failure.getMessage()));
  }
 }
}
