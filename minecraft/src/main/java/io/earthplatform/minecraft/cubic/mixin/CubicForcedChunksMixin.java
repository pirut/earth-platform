package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.ChunkTickets;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ForcedHeights;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forced chunks in the Earth are saved whatever came after them, and an unforced chunk forgets its
 * Earth heights however it was unforced ({@code /forceload remove all}, a mod). Vanilla's
 * {@code setChunkForced} sets the saved flag to whether the call changed anything, so forcing a chunk
 * already forced (for a second height) cleared the flag the first force set, and a restart lost it.
 */
@Mixin(ServerLevel.class)
abstract class CubicForcedChunksMixin {
    @WrapOperation(method = "setChunkForced", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ForcedChunksSavedData;setDirty(Z)V"))
    private void earthplatform$keepSaved(ForcedChunksSavedData data, boolean changed, Operation<Void> original) {
        original.call(data, ChunkTickets.forcedDirty(changed, data.isDirty(), CubicLevel.of((ServerLevel) (Object) this) != null));
    }

    @Inject(method = "setChunkForced", at = @At("RETURN"))
    private void earthplatform$forgetHeights(int chunkX, int chunkZ, boolean add, CallbackInfoReturnable<Boolean> result) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!add && result.getReturnValueZ() && CubicLevel.of(self) != null) ForcedHeights.of(self).clear(ChunkPos.asLong(chunkX, chunkZ));
    }
}
