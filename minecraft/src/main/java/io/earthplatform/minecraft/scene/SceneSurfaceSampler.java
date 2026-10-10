package io.earthplatform.minecraft.scene;

/** Shared near/far source. Unknown coverage throws MissingTerrainException; it is never ocean or air. */
public interface SceneSurfaceSampler {
    long surfaceY(long x,long z);
    int argb(long x,long z,long surfaceY);
    String identity();
}
