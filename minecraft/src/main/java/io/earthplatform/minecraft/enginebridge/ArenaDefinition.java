package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.List;

/** Versioned fixed 32-cubed editable capability. This is not a general region catalogue. */
public final class ArenaDefinition {
    public static final int CUBE_COUNT = 8, DEPENDENCY_COUNT = 64;
    public static final int EDIT_SIZE = 32, OWNED_SIZE = 34, READ_SIZE = 36;
    public static final int MIN_CENTER_XZ = -16, MAX_CENTER_XZ = 15;
    public static final int MIN_CENTER_Y = 6, MAX_CENTER_Y = 15;
    public static final ArenaDefinition FIXED = new ArenaDefinition();
    private final CubePos origin = new CubePos(0, 8, 0);
    private final List<CubePos> positions, dependencies;
    private final String identity;
    private ArenaDefinition() {
        positions = box(0, 2); dependencies = box(-1, 3);
        identity = ReplacementWorkspaces.sha256("earth-fixed-playable-arena-v1\n0 8 0\n32 34 36\n-16 15 6 15\n");
    }
    private List<CubePos> box(int min, int max) {
        var values = new ArrayList<CubePos>();
        for (int y = min; y < max; y++) for (int z = min; z < max; z++) for (int x = min; x < max; x++)
            values.add(origin.offset(x, y, z));
        return values.stream().sorted().toList();
    }
    public CubePos origin() { return origin; }
    public List<CubePos> positions() { return positions; }
    public List<CubePos> dependencies() { return dependencies; }
    public String identity() { return identity; }
    public long minX() { return 0; } public long minY() { return 128; } public long minZ() { return 0; }
    public long maxXExclusive() { return 32; } public long maxYExclusive() { return 160; } public long maxZExclusive() { return 32; }
    public long ownedMinX() { return -1; } public long ownedMinY() { return 127; } public long ownedMinZ() { return -1; }
    public long ownedMaxXExclusive() { return 33; } public long ownedMaxYExclusive() { return 161; } public long ownedMaxZExclusive() { return 33; }
    public long readMinX() { return -2; } public long readMinY() { return 126; } public long readMinZ() { return -2; }
    public long readMaxXExclusive() { return 34; } public long readMaxYExclusive() { return 162; } public long readMaxZExclusive() { return 34; }
    public boolean contains(CubePos position) { return positions.contains(position); }
    public boolean editable(long x, long y, long z) { return x >= minX() && x < maxXExclusive() && y >= minY() && y < maxYExclusive() && z >= minZ() && z < maxZExclusive(); }
    public boolean containsReadCell(long x, long y, long z) { return x >= readMinX() && x < readMaxXExclusive() && y >= readMinY() && y < readMaxYExclusive() && z >= readMinZ() && z < readMaxZExclusive(); }
    public boolean permitsCenter(CubePos center) { return center != null && center.x() >= MIN_CENTER_XZ && center.x() <= MAX_CENTER_XZ && center.z() >= MIN_CENTER_XZ && center.z() <= MAX_CENTER_XZ && center.y() >= MIN_CENTER_Y && center.y() <= MAX_CENTER_Y; }
    public void validateCenter(CubePos center) { if (!permitsCenter(center)) throw new IllegalArgumentException("Outside the fixed 512m exploration boundary"); RollingCubePlan.validateWindowCenter(center); }
    /** The complete arena read footprint must be inside the body's existing 125 owners. */
    public boolean insideWindow(CubePos center) { return dependencies.stream().allMatch(p -> Math.abs((long) p.x()-center.x()) <= 2 && Math.abs((long) p.y()-center.y()) <= 2 && Math.abs((long) p.z()-center.z()) <= 2); }
}
