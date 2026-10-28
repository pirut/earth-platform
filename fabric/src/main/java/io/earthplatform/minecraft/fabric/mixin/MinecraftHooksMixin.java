package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.client.CubicClient;
import io.earthplatform.minecraft.cubic.client.PerformanceDiagnostics;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NeoForge's frame events around the game renderer, and its client level unload event. */
@Mixin(Minecraft.class)
abstract class MinecraftHooksMixin {
    @Shadow @Nullable public ClientLevel level;

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void earthplatform$frameStart(boolean tick, CallbackInfo info) { CubicClient.frame(); }

    @Inject(method = "runTick", at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void earthplatform$frameEnd(boolean tick, CallbackInfo info) { PerformanceDiagnostics.frame(); }

    /** The level being left: to another dimension, or by disconnecting. */
    @Inject(method = {"setLevel", "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", "clearClientLevel"}, at = @At("HEAD"))
    private void earthplatform$unload(CallbackInfo info) {
        if (level != null) CubicClient.unload(level);
    }
}
