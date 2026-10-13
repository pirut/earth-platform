package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.compat.CubicMaps;
import io.earthplatform.minecraft.cubic.compat.JourneyMapCompat;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** JourneyMap caches a snapshot of every chunk it drew; for the cubic world that is the cube snapshot, not the empty carrier. */
@Pseudo
@Mixin(targets = "journeymap.common.chunk.JMChunkSnapshot", remap = false)
abstract class JourneyMapSnapshotMixin {
    @Inject(method = "fromLevelChunk", at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$cubicSnapshot(LevelChunk chunk, DataLayer light, CallbackInfoReturnable<Object> result) {
        if (chunk != null && chunk.getLevel() != null && Cubic.isCubic(chunk.getLevel().dimension()))
            result.setReturnValue(JourneyMapCompat.snapshot(CubicMaps.column(chunk.getPos().x, chunk.getPos().z)));
    }
}
