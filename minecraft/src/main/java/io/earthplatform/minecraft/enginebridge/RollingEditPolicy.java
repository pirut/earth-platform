package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.util.Objects;

/**
 * Immutable admission for the original rolling workspace only. Selecting a wider policy does not
 * grant replacement authority, enlarge native coordinates, or change terrain/store identity.
 * The caller retains lifecycle, material, lease, revision, and durable-save checks.
 */
public enum RollingEditPolicy {
    INTERIOR_V1("interior-v1"),
    FULL_CUBE_V1("full-cube-v1");

    private final String id;

    RollingEditPolicy(String id) { this.id = id; }

    public String id() { return id; }

    /** Exact signed cube membership; unrelated long coordinates are never narrowed or wrapped. */
    public boolean permits(CubePos original, long x, long y, long z) {
        Objects.requireNonNull(original, "original");
        if (this == INTERIOR_V1) return RollingCubePlan.isEditableInterior(original, x, y, z);
        return y >= RollingCubePlan.GAMEPLAY_MIN_Y && y < RollingCubePlan.GAMEPLAY_MAX_Y_EXCLUSIVE
                && original.x() >= RollingCubePlan.NATIVE_XZ_MIN && original.x() <= RollingCubePlan.NATIVE_XZ_MAX
                && original.z() >= RollingCubePlan.NATIVE_XZ_MIN && original.z() <= RollingCubePlan.NATIVE_XZ_MAX
                && Math.floorDiv(x, CubePos.SIZE) == original.x()
                && Math.floorDiv(y, CubePos.SIZE) == original.y()
                && Math.floorDiv(z, CubePos.SIZE) == original.z();
    }
}
