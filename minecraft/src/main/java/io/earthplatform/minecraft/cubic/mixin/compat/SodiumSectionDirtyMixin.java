package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium overwrites {@code LevelRenderer}'s block-area and neighbour-dirty methods to call into
 * {@code SodiumWorldRenderer} directly, bypassing {@code LevelRenderer.setSectionDirty}, and mods
 * can also schedule chunk rebuilds through Sodium directly. Forward every chunk rebuild request
 * to the cubic renderer so dynamic lights remesh cubes at any height.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
abstract class SodiumSectionDirtyMixin {
    @Inject(method = "scheduleRebuildForChunk(IIIZ)V", at = @At("HEAD"), require = 0, remap = false)
    private void earthplatform$scheduleRebuildForChunk(int cx, int cy, int cz, boolean important, CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && CubicLevel.of(level) instanceof ClientCubeWorld cubes
                && !((CubicLevel) level).earthplatform$keptInChunks(cx, cz)) {
            cubes.renderer().sectionDirty(cx, cy, cz);
        }
    }
}
