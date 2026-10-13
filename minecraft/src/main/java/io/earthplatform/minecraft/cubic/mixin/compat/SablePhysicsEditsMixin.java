package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cube edits in the cubic level reach Sable's physics, which otherwise only watches chunk edits. */
@Mixin(ServerLevel.class)
abstract class SablePhysicsEditsMixin {
    @Inject(method = "onBlockStateChange", at = @At("TAIL"))
    private void earthplatform$tellSable(BlockPos pos, BlockState old, BlockState state, CallbackInfo info) {
        if (!SableCompat.LOADED || old == state) return;
        ServerLevel level = (ServerLevel) (Object) this;
        CubeWorld cubes = SableCompat.cubes(level, pos.getX(), pos.getZ());
        if (cubes != null) SableCompat.worldEdit(level, cubes, pos, old, state);
    }
}
