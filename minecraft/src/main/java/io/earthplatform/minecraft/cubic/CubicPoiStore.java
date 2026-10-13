package io.earthplatform.minecraft.cubic;

/**
 * Point-of-interest storage (beds, job sites, bells, portals) at cubic heights. Vanilla's store
 * only spans the carrier world's height; in the cubic world sections outside it are kept in
 * memory, rebuilt from a cube's blocks whenever the cube loads, and forgotten when it unloads.
 */
public interface CubicPoiStore {
    /** The sections held outside the carrier's height in a chunk column. */
    java.util.stream.Stream<?> earthplatform$cubicSections(long chunk);
    /** Drops a section's points of interest once its cube unloads. */
    void earthplatform$forget(long section);
}
