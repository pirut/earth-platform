package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.runtime.InstalledCallbackStartupMarker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Actual Minecraft-owned initial-screen call, immediately before the sole original
 * ClientModLoader.completeModLoading invocation. No bus/library class is transformed.
 * This first-stage hook only records prerequisites; it never reads a backing map.
 */
@Mixin(value=Minecraft.class,remap=false)
abstract class InstalledCallbackStartupMarkerMixin {
    @Inject(method="buildInitialScreens",at=@At(value="INVOKE",
            target="Lnet/neoforged/neoforge/client/loading/ClientModLoader;completeModLoading(Ljava/lang/Runnable;)Ljava/lang/Runnable;",
            shift=At.Shift.BEFORE),require=1,allow=1)
    private void earthplatform$installedCallbackStartupCut(CallbackInfoReturnable<Runnable> callback) {
        InstalledCallbackStartupMarker.observe((Minecraft)(Object)this);
    }
}
