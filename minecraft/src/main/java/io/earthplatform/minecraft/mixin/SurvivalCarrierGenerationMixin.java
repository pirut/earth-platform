package io.earthplatform.minecraft.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Read exact future identities without allocating debug future-list snapshots. */
@Mixin(GenerationChunkHolder.class)
abstract class SurvivalCarrierGenerationMixin implements io.earthplatform.minecraft.survival.host.SurvivalCarrierGenerationLineageAccess {
    @Shadow @Final private AtomicReference<ChunkStatus> startedWork;
    @Shadow @Final private AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> futures;
    public ChunkStatus earthplatform$carrierStartedStatus() { return startedWork.get(); }
    public CompletableFuture<ChunkResult<ChunkAccess>> earthplatform$carrierFuture(ChunkStatus status) {
        return futures.get(status.getIndex());
    }
}
