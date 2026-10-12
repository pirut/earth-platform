package io.earthplatform.minecraft.survival.host;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Internal read-only identity proof. It does not complete, replace, or schedule futures. */
public interface SurvivalCarrierGenerationLineageAccess {
    ChunkStatus earthplatform$carrierStartedStatus();
    CompletableFuture<ChunkResult<ChunkAccess>> earthplatform$carrierFuture(ChunkStatus status);
}
