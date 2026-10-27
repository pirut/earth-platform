package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.client.CubicClient;
import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The debug screen's left column, as NeoForge's CustomizeGuiOverlayEvent.DebugText hands it out. */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenMixin {
    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void earthplatform$cubicLines(CallbackInfoReturnable<List<String>> result) { CubicClient.debugText(result.getReturnValue()); }
}
