package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.runtime.InstalledConsumerOrigins;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reserve and release-publish the fixed ledger before original FML mod construction.
 * This hook/helper does not resolve either later registrar transform target. */
@Mixin(value=Minecraft.class,remap=false)
abstract class InstalledConsumerStartupSeedMixin {
    @Inject(method="<init>(Lnet/minecraft/client/main/GameConfig;)V",at=@At(value="INVOKE",
            target="Lnet/neoforged/neoforge/client/loading/ClientModLoader;begin(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/repository/PackRepository;Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V",
            shift=At.Shift.BEFORE),require=1,allow=1)
    private void earthplatform$installedConsumerSeed(CallbackInfo callback) {
        try { InstalledConsumerOrigins.seed(this,((Minecraft)(Object)this).isSameThread()); }
        catch(Throwable observerFailure) {
            try { InstalledConsumerOrigins.observerFailed(observerFailure.getClass()); } catch(Throwable ignored) {}
        }
    }
}
