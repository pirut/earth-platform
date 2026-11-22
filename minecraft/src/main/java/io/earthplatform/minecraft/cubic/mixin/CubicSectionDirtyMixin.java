package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dynamic lighting mods and other client hooks ask {@link LevelRenderer} to rebuild sections when
 * lighting changes without altering blocks. Vanilla's view area and Sodium's section manager drop
 * coordinates outside the 16-block carrier, so forward them to the cubic renderer too. Applied
 * after Sodium's overwrite of {@code setSectionDirty}.
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
abstract class CubicSectionDirtyMixin {
    @Shadow @Nullable private ClientLevel level;

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void earthplatform$sectionDirty(int cx, int cy, int cz, boolean important, CallbackInfo info) {
        if (level != null && CubicLevel.of(level) instanceof ClientCubeWorld cubes
                && !((CubicLevel) level).earthplatform$keptInChunks(cx, cz)) {
            cubes.renderer().sectionDirty(cx, cy, cz);
        }
    }
}
