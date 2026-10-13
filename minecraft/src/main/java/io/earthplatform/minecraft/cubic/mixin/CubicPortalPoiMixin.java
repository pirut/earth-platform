package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.portal.CubicPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cubic Nether portal blocks go to the cubic portal list instead of the point-of-interest store,
 * which only spans the carrier's height and rejects true-height positions.
 */
@Mixin(ServerLevel.class)
abstract class CubicPortalPoiMixin {
    @Inject(method = "onBlockStateChange", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubicPortal(BlockPos pos, BlockState old, BlockState now, CallbackInfo info) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (CubicLevel.of(self) == null || !(old.is(Blocks.NETHER_PORTAL) || now.is(Blocks.NETHER_PORTAL))) return;
        CubicPortals.blockChanged(self, pos, old, now);
        info.cancel();
    }
}
