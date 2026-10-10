package io.earthplatform.minecraft.storage;

/**
 * Deliberately an Error: MC 1.21.1 ChunkMap.handleChunkLoadFailure regenerates a chunk for
 * ordinary exceptions. This integrity failure must instead retain the failed load and stop it.
 * SectionYReadGuard independently blocks any subsequent serializer write for the affected key.
 */
public final class SectionYIntegrityError extends Error {
    public SectionYIntegrityError(String reason) {
        super(reason + ". Earth Platform has refused this chunk to protect its saved data. "
                + "Use the matching format version and experimental setting, or restore a verified backup, then restart. "
                + "Do not delete the chunk or continue with regeneration.");
    }
}
