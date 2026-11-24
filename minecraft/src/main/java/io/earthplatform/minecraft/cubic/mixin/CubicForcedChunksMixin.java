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
 * Forced chunks in the Earth are saved whatever came after them; a forced chunk gets its Earth heights
 * and place in the queue, and an unforced one forgets them, however it was forced or unforced
 * ({@code /forceload}, {@code remove all}, a mod). Vanilla's
 * {@code setChunkForced} sets the saved flag to whether the call changed anything, so forcing a chunk
 * already forced (for a second height) cleared the flag the first force set, and a restart lost it.
 */
@Mixin(ServerLevel.class)
abstract class CubicForcedChunksMixin {
    @WrapOperation(method = "setChunkForced", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ForcedChunksSavedData;setDirty(Z)V"))
    private void earthplatform$keepSaved(ForcedChunksSavedData data, boolean changed, Operation<Void> original) {
        original.call(data, ChunkTickets.forcedDirty(changed, data.isDirty(), CubicLevel.of((ServerLevel) (Object) this) != null));
    }

    /** A chunk forced gets its place in the queue and its ground band (a height replaces it after); one unforced forgets them. */
    @Inject(method = "setChunkForced", at = @At("RETURN"))
    private void earthplatform$heights(int chunkX, int chunkZ, boolean add, CallbackInfoReturnable<Boolean> result) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!result.getReturnValueZ() || CubicLevel.of(self) == null) return;
        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        if (add) ForcedHeights.of(self).ensure(chunk);
        else ForcedHeights.of(self).clear(chunk);
    }
}
