package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.play.EarthWorlds;
import io.earthplatform.minecraft.cubic.play.client.EarthPlayClient;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Earth world type's Customize button opens the place picker, as NeoForge's RegisterPresetEditorsEvent does. */
@Mixin(WorldCreationUiState.class)
abstract class PresetEditorMixin {
    private static final PresetEditor EARTHPLATFORM$EDITOR = (parent, context) -> EarthPlayClient.presetEditor(parent);

    @Inject(method = "getPresetEditor", at = @At("RETURN"), cancellable = true)
    private void earthplatform$earthEditor(CallbackInfoReturnable<PresetEditor> result) {
        var preset = ((WorldCreationUiState) (Object) this).getWorldType().preset();
        if (result.getReturnValue() == null && preset != null && preset.is(EarthWorlds.PRESET)) result.setReturnValue(EARTHPLATFORM$EDITOR);
    }
}
