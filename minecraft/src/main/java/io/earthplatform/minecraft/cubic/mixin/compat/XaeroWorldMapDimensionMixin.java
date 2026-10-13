package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.Cubic;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In singleplayer Xaero's World Map builds its map from the world save's region files. The cubic
 * dimension's save holds only empty carrier chunks, so there it keeps its own map data, written from
 * the live client cubes like a multiplayer world.
 */
@Pseudo
@Mixin(targets = "xaero.map.world.MapDimension", remap = false)
abstract class XaeroWorldMapDimensionMixin {
    @Shadow @Final private ResourceKey<Level> dimId;

    @Inject(method = {"isUsingWorldSave", "isFutureUsingWorldSaveUnsynced"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$ownMapData(CallbackInfoReturnable<Boolean> result) {
        if (Cubic.isCubic(dimId)) result.setReturnValue(false);
    }
}
