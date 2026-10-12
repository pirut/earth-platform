package io.earthplatform.minecraft.lod;

import java.util.List;

/**
 * Immutable common-side far-layout identity. No client, renderer, source or GL
 * classes are referenced here, so a server can bind the same policy as a client.
 * Radius denotes a square about the canonical center, not verified source extent.
 */
public enum DhLodLayout {
    FIXED_512("fixed-512-v1", 64, 16, false, List.of(new Ring(512, 16, 256))),
    LONG_4096("long-4096-v1", 256, 128, true, List.of(
            new Ring(512, 16, 256), new Ring(1024, 64, 1024), new Ring(4096, 128, 4096))),
    EARTH_ALPINE_2048("earth-alpine-2048-v1-stratified", 128, 128, true, List.of(
            new Ring(128, 16, 256), new Ring(256, 16, 256), new Ring(512, 32, 1024), new Ring(1024, 64, 1024), new Ring(2048, 128, 4096))),
    ARENA_4096("arena-4096-v2-three-smooth-hills", 256, 128, true, List.of(
            new Ring(128, 8, 512), new Ring(256, 16, 512), new Ring(512, 32, 512), new Ring(1024, 64, 1024), new Ring(4096, 128, 4096)));

    /** Exclusive inner ring is supplied by the previous list entry, or zero. */
    public record Ring(int radiusBlocks, int cellSize, int tileSize) {
        public Ring {
            if (radiusBlocks <= 0 || cellSize <= 0 || tileSize <= 0
                    || (cellSize & (cellSize - 1)) != 0 || (tileSize & (tileSize - 1)) != 0
                    || tileSize > 4096 || cellSize > tileSize || radiusBlocks % cellSize != 0)
                throw new IllegalArgumentException("Invalid bounded dyadic far ring");
        }
    }

    private final String id, identity;
    private final int snapBlocks, centerAlignmentBlocks;
    private final boolean requiresFootprintCoverage;
    private final List<Ring> rings;

    DhLodLayout(String id, int snapBlocks, int centerAlignmentBlocks,
                boolean requiresFootprintCoverage, List<Ring> rings) {
        this.id = id;
        this.snapBlocks = snapBlocks;
        this.centerAlignmentBlocks = centerAlignmentBlocks;
        this.requiresFootprintCoverage = requiresFootprintCoverage;
        this.rings = List.copyOf(rings);
        int previousRadius = 0;
        for (Ring ring : this.rings) {
            if (ring.radiusBlocks() <= previousRadius || previousRadius % ring.cellSize() != 0
                    || centerAlignmentBlocks % ring.cellSize() != 0)
                throw new IllegalArgumentException("Far rings must align exactly without padding");
            previousRadius = ring.radiusBlocks();
        }
        identity = id + ":square-radius=" + radiusBlocks() + ":center-align=" + centerAlignmentBlocks
                + ":snap=" + snapBlocks + ":rings=" + this.rings + ":fine=1:collar=1:fine-tile=32"
                + ":native-y=[-11008,8864):full-xyz-clip=true:complete-footprint-required=" + requiresFootprintCoverage
                + (id.startsWith("arena-") ? ":fine-cell-below=" + this.rings.getFirst().cellSize() : "")
                + (id.startsWith("earth-alpine-") ? ":strata=top1-sub3-deep:dh-material=stone2-snow8:coalesce=equal-color-and-category-v1:replacement=single-owned18-read20:six-material-unit:revision0-required:bucket=owned-min:pieces=4096:max=8192:face-color=up-down1-ew0.72-ns0.85" : "");
    }

    public String id() { return id; }
    /** Includes every sampling, grouping, support and native-height policy. */
    public String identity() { return identity; }
    public int radiusBlocks() { return rings.getLast().radiusBlocks(); }
    public int snapBlocks() { return snapBlocks; }
    public int centerAlignmentBlocks() { return centerAlignmentBlocks; }
    public int outerCellSize() { return rings.getLast().cellSize(); }
    public int minNativeY() { return -11008; }
    public int maxNativeYExclusive() { return 8864; }
    public boolean requiresFootprintCoverage() { return requiresFootprintCoverage; }
    public List<Ring> rings() { return rings; }

    /** Floor alignment is intentional for negative coordinates; validate the full requested extent. */
    public long normalizeCenter(long coordinate) {
        long center = Math.multiplyExact(Math.floorDiv(coordinate, centerAlignmentBlocks), centerAlignmentBlocks);
        Math.subtractExact(center, radiusBlocks());
        Math.addExact(center, radiusBlocks());
        return center;
    }

    public int tileSizeForCell(int size) {
        if (size < 1 || (size & (size - 1)) != 0 || size > outerCellSize())
            throw new IllegalArgumentException("Cell is outside the immutable far layout");
        if (size < rings.getFirst().cellSize()) return 32;
        for (Ring ring : rings) if (size <= ring.cellSize()) return ring.tileSize();
        throw new AssertionError("No ring owns the cell");
    }
}
