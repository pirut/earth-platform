package io.earthplatform.minecraft.cubic.client;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/** Exact view state keeps sub-block movement, frustum offsets and another eye from reusing a stale pick. */
final class FarPickState {
    private boolean valid, band;
    private double x, y, z, frustumX, frustumY, frustumZ;
    private final Quaternionf rotation = new Quaternionf();
    private final Matrix4f frustum = new Matrix4f();
    private int columns, meshes, finest, hole;

    boolean changed(double x, double y, double z, Quaternionfc rotation, Matrix4fc frustum,
                    double frustumX, double frustumY, double frustumZ,
                    int columns, int meshes, int finest, int hole, boolean band) {
        boolean changed = !valid || this.x != x || this.y != y || this.z != z
                || !this.rotation.equals(rotation) || !this.frustum.equals(frustum)
                || this.frustumX != frustumX || this.frustumY != frustumY || this.frustumZ != frustumZ
                || this.columns != columns || this.meshes != meshes || this.finest != finest || this.hole != hole || this.band != band;
        if (changed) {
            valid = true;
            this.x = x; this.y = y; this.z = z;
            this.rotation.set(rotation);
            this.frustum.set(frustum);
            this.frustumX = frustumX; this.frustumY = frustumY; this.frustumZ = frustumZ;
            this.columns = columns; this.meshes = meshes; this.finest = finest; this.hole = hole; this.band = band;
        }
        return changed;
    }

    void invalidate() { valid = false; }
}
