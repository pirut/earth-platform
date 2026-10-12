package io.earthplatform.minecraft.lod;

/** Transient publication ownership; deliberately excluded from canonical CPU cache identity. */
public record LodJobStamp(long epoch, long lease, long resourceGeneration) {
    public LodJobStamp {
        if (epoch < 0 || lease < 0 || resourceGeneration < 0)
            throw new IllegalArgumentException("Negative LOD job stamp");
    }
}
