package io.earthplatform.minecraft.cubic.client;

import java.nio.ByteBuffer;
import javax.annotation.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Experimental batching of FarLod's position-only water depth passes. Disabled until the real
 * no-shader/Photon scene comparisons pass: {@code -Dearthplatform.far.batchWaterDepth=true}.
 * Colour draws deliberately keep their original per-column buffers, offsets and Iris attributes.
 * One instance belongs to each pass, preserving its exact source order and selection separately.
 */
final class FarWaterBatch implements AutoCloseable {
    static final boolean ENABLED = Boolean.getBoolean("earthplatform.far.batchWaterDepth");
    static final int MAX_BATCH_BYTES = FarWaterBatchPlan.MAX_BYTES;
    /** Source position copies <= 32 MiB; the two optional GPU batches add <= 32 MiB in total. */
    private static final FarWaterBatchPlan.Retention RETAINED = new FarWaterBatchPlan.Retention(32 * 1024 * 1024);
    private final FarWaterBatchPlan plan = new FarWaterBatchPlan(MAX_BATCH_BYTES);
    @Nullable private CubeBuffer buffer;
    private long uploadedRevision = -1;

    void begin() { plan.begin(); }

    /** Same order and float offsets as the original per-column depth loop. */
    void add(CubeBuffer source, float x, float y, float z) {
        plan.add(source.waterPositions(), x, y, z);
    }

    /**
     * True only when the entire pass is ready. False means run its original full loop. Unchanged
     * signatures reuse the GPU batch; no vertex copying, native allocation or upload occurs.
     */
    boolean end() {
        if (!plan.end()) return false;
        if (plan.vertices() == 0 || uploadedRevision == plan.revision()) return true;
        ByteBuffer positions = MemoryUtil.memAlloc(plan.bytes());
        try {
            plan.write(positions);
            positions.flip();
            if (buffer == null) buffer = new CubeBuffer();
            buffer.uploadWaterDepth(positions, plan.vertices());
            uploadedRevision = plan.revision();
            return true;
        } finally {
            MemoryUtil.memFree(positions);
        }
    }

    /** Caller must select FarLod's batch variant; no uniforms or GL pass state are changed here. */
    void draw() {
        if (plan.vertices() != 0 && buffer != null) buffer.draw();
    }

    @Nullable static FarWaterBatchPlan.Snapshot retain(ByteBuffer vertices, int stride, int offset, int count, boolean sorted) {
        if (!ENABLED || count < 0 || (long) count * FarWaterBatchPlan.POSITION_BYTES > MAX_BATCH_BYTES) return null;
        int bytes = count * FarWaterBatchPlan.POSITION_BYTES;
        if (!RETAINED.reserve(bytes)) return null;
        FarWaterBatchPlan.Snapshot snapshot = FarWaterBatchPlan.Snapshot.copy(vertices, stride, offset, count, sorted);
        if (snapshot == null) RETAINED.release(bytes);
        return snapshot;
    }

    static void release(@Nullable FarWaterBatchPlan.Snapshot snapshot) {
        if (snapshot != null) RETAINED.release(snapshot.release());
    }

    @Override public void close() {
        if (buffer != null) { buffer.close(); buffer = null; }
        plan.clear();
        uploadedRevision = -1;
    }
}
