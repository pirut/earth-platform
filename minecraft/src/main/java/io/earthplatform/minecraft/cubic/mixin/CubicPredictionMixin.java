package io.earthplatform.minecraft.cubic.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Predictions are keyed by BlockPos.asLong, whose 12-bit Y wraps above Y 2047. Remember the real
 * position for each key so acknowledged states land where they were predicted.
 */
@Mixin(BlockStatePredictionHandler.class)
abstract class CubicPredictionMixin {
    @Unique private final Long2ObjectOpenHashMap<BlockPos> earthplatform$positions = new Long2ObjectOpenHashMap<>();

    @Inject(method = "retainKnownServerState", at = @At("HEAD"))
    private void earthplatform$remember(BlockPos pos, BlockState state, LocalPlayer player, CallbackInfo info) {
        earthplatform$positions.put(pos.asLong(), pos.immutable());
    }

    @Redirect(method = "endPredictionsUpTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;of(J)Lnet/minecraft/core/BlockPos;"))
    private BlockPos earthplatform$realPosition(long key, int sequence, ClientLevel level) {
        BlockPos pos = earthplatform$positions.remove(key);
        return pos != null ? pos : BlockPos.of(key);
    }
}
