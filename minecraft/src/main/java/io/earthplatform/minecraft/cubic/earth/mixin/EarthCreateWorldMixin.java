package io.earthplatform.minecraft.cubic.earth.mixin;

import io.earthplatform.minecraft.cubic.play.EarthWorlds;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The Earth world type's own dimensions come from this mod's data pack, which Minecraft counts as
 * experimental; creating an Earth world skips that warning, which would only alarm new players.
 */
@Mixin(CreateWorldScreen.class)
abstract class EarthCreateWorldMixin {
    @ModifyArg(method = "onCreate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;confirmWorldCreation(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;Lcom/mojang/serialization/Lifecycle;Ljava/lang/Runnable;Z)V"),
            index = 4)
    private boolean earthplatform$trustEarth(boolean skipWarnings) {
        var preset = ((CreateWorldScreen) (Object) this).getUiState().getWorldType().preset();
        return skipWarnings || (preset != null && preset.is(EarthWorlds.PRESET));
    }
}
