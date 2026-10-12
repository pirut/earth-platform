package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** 64 m native page addresses. This is a bounded work unit, never a render-distance selection. */
public record SurvivalVisualPage(CubePos minimum) {
    public static final int CUBES_PER_AXIS = 4, MAX_MEMBERS = 64, MAX_DEPENDENCIES = 216;
    public SurvivalVisualPage {
        if (minimum == null || Math.floorMod(minimum.x(), 4) != 0
                || Math.floorMod(minimum.y(), 4) != 0 || Math.floorMod(minimum.z(), 4) != 0)
            throw new IllegalArgumentException("A native page starts at an aligned signed 4-cube coordinate");
        // Include halo overflow in the plan check, before retaining payloads.
        minimum.offset(-1, -1, -1);
        minimum.offset(4, 4, 4);
    }
    public static SurvivalVisualPage containing(CubePos cube) {
        return new SurvivalVisualPage(new CubePos(Math.multiplyExact(Math.floorDiv(cube.x(), 4), 4),
                Math.multiplyExact(Math.floorDiv(cube.y(), 4), 4),
                Math.multiplyExact(Math.floorDiv(cube.z(), 4), 4)));
    }
    public Set<CubePos> members() { return box(0, 3); }
    /** Includes all 64 members and 152 surrounding halo owners: 6 cubed, not 64 + 216. */
    public Set<CubePos> dependencies() { return box(-1, 4); }
    private Set<CubePos> box(int start, int end) {
        var result = new TreeSet<CubePos>();
        for (int y = start; y <= end; y++) for (int z = start; z <= end; z++)
            for (int x = start; x <= end; x++) result.add(minimum.offset(x, y, z));
        return Collections.unmodifiableSet(result);
    }
}
