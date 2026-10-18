package io.earthplatform.minecraft.cubic.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import javax.annotation.Nullable;

/**
 * Position-only water depth geometry, in the caller's exact draw order. This has no GL or shader
 * dependencies so copying, float operands, ordering and cache invalidation can be checked
 * independently of a running client. It must never be used for a pack's colour pass: that pass
 * may depend on the original column-local positions and Iris's extended vertex attributes.
 */
final class FarWaterBatchPlan {
    static final int POSITION_BYTES = 3 * Float.BYTES;
    static final int VERTEX_BYTES = 2 * POSITION_BYTES;
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private final int maxBytes;
    private final ArrayList<Draw> draws = new ArrayList<>();
    private int cursor, vertices;
    private boolean building, valid, changed;
    private long revision;

    FarWaterBatchPlan(int maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("Negative water batch limit");
        this.maxBytes = maxBytes;
    }

    void begin() {
        if (building) throw new IllegalStateException("Unfinished water batch");
        building = true;
        valid = true;
        changed = false;
        cursor = vertices = 0;
    }

    /**
     * Append one original draw. A missing/unsupported mesh or too much geometry rejects the
     * entire pass, so the caller can execute its complete original loop without double drawing.
     * Reusing the same sources and float offsets makes no per-draw allocation on a cache hit.
     */
    void add(@Nullable Snapshot source, float x, float y, float z) {
        if (!building) throw new IllegalStateException("Water batch not begun");
        if (!valid) return;
        if (source == null || !source.available() || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || ((long) vertices + source.vertices()) * VERTEX_BYTES > maxBytes) {
            valid = false;
            return;
        }
        vertices += source.vertices();
        if (cursor == draws.size()) {
            draws.add(new Draw(source, x, y, z));
            changed = true;
        } else {
            Draw draw = draws.get(cursor);
            if (draw.source != source || !same(draw.x, x) || !same(draw.y, y) || !same(draw.z, z)) {
                draw.source = source;
                draw.x = x; draw.y = y; draw.z = z;
                changed = true;
            }
        }
        cursor++;
    }

    boolean end() {
        if (!building) throw new IllegalStateException("Water batch not begun");
        building = false;
        if (!valid) {
            // Do not retain old snapshots after a source was closed/replaced or rejected.
            clear();
            return false;
        }
        if (cursor != draws.size()) {
            draws.subList(cursor, draws.size()).clear();
            changed = true;
        }
        if (changed) revision++;
        return true;
    }

    int vertices() { return vertices; }
    int bytes() { return vertices * VERTEX_BYTES; }
    long revision() { return revision; }

    /** Writes sequential quads; the GPU uses the same 0,1,2,2,3,0 indices as each original draw. */
    void write(ByteBuffer target) {
        if (building || !valid) throw new IllegalStateException("Incomplete water batch");
        if (target.remaining() < bytes()) throw new IllegalArgumentException("Water batch destination too small");
        target.order(ByteOrder.nativeOrder());
        for (Draw draw : draws) {
            float[] xyz = draw.source.xyz;
            if (xyz == null) throw new IllegalStateException("Released water source");
            for (int at = 0; at < xyz.length; at += 3) {
                // Leave the addition on the GPU, with the exact original float operands.
                // CPU translation (or a world-coordinate matrix) can round differently.
                target.putFloat(xyz[at]).putFloat(xyz[at + 1]).putFloat(xyz[at + 2]);
                target.putFloat(draw.x).putFloat(draw.y).putFloat(draw.z);
            }
        }
    }

    void clear() {
        draws.clear();
        cursor = vertices = 0;
        building = valid = false;
        revision++;
    }

    private static boolean same(float a, float b) {
        return Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
    }

    /** Owned copy of one unsorted quad mesh's Position element, irrespective of its stride. */
    static final class Snapshot {
        @Nullable private float[] xyz;
        private final int vertices;

        private Snapshot(float[] xyz) { this.xyz = xyz; vertices = xyz.length / 3; }
        int vertices() { return vertices; }
        int bytes() { return vertices * POSITION_BYTES; }
        boolean available() { return xyz != null; }

        /** Drop the large array even when an old draw signature still holds this source handle. */
        int release() {
            if (xyz == null) return 0;
            xyz = null;
            return bytes();
        }

        /**
         * Sorted indices need their own preserved sequence; reject them rather than silently
         * assuming sequential quads. Only the standard float3 Position element is supported.
         * Other vertex bytes, including all Iris attributes, are read neither here nor by the
         * dedicated water depth shader, and stay unchanged in the original colour buffer.
         */
        @Nullable static Snapshot copy(ByteBuffer source, int stride, int offset, int vertices, boolean sorted) {
            if (sorted || vertices < 0 || vertices % 4 != 0 || stride < POSITION_BYTES
                    || offset < 0 || offset > stride - POSITION_BYTES
                    || (long) vertices * stride > source.remaining()
                    || (long) vertices * POSITION_BYTES > MAX_BYTES) return null;
            float[] xyz = new float[vertices * 3];
            ByteBuffer input = source.duplicate().order(ByteOrder.nativeOrder());
            int start = source.position() + offset;
            for (int vertex = 0; vertex < vertices; vertex++) {
                int at = start + vertex * stride;
                for (int axis = 0; axis < 3; axis++) {
                    float value = input.getFloat(at + axis * Float.BYTES);
                    if (!Float.isFinite(value)) return null;
                    xyz[vertex * 3 + axis] = value;
                }
            }
            return new Snapshot(xyz);
        }
    }

    /** A bounded reservation for retained water positions, shared by all water layers. Render-thread only. */
    static final class Retention {
        private final int limit;
        private int bytes;

        Retention(int limit) {
            if (limit < 0) throw new IllegalArgumentException("Negative water retention limit");
            this.limit = limit;
        }

        boolean reserve(int count) {
            if (count < 0 || (long) bytes + count > limit) return false;
            bytes += count;
            return true;
        }

        void release(int count) {
            if (count < 0 || count > bytes) throw new IllegalArgumentException("Invalid water retention release");
            bytes -= count;
        }

        int bytes() { return bytes; }
    }

    private static final class Draw {
        Snapshot source;
        float x, y, z;
        Draw(Snapshot source, float x, float y, float z) {
            this.source = source;
            this.x = x; this.y = y; this.z = z;
        }
    }
}
