package io.earthplatform.minecraft.cubic.client;

/** Readiness of actual cubic terrain for vanilla's one-time world-icon capture. */
public final class WorldThumbnailReadiness {
    private WorldThumbnailReadiness() {}

    public static boolean ready(int visibleNearMeshes, int pendingNearMeshes, int farRanges, int farSettledInViewPercent) {
        if (pendingNearMeshes != 0) return false;
        // Match vanilla's minimum visible-section threshold. High-altitude views can contain only
        // distant terrain; wait for its visible selection to finish refining before capturing it.
        return visibleNearMeshes > 10 || farRanges > 10 && farSettledInViewPercent == 100;
    }
}
