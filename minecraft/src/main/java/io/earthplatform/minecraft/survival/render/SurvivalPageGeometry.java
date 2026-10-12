package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/** Pure checked page-packing arithmetic. Geometry content confers no completion/publication authority. */
public final class SurvivalPageGeometry {
    public static final int MAX_PIECE_BYTES = 1 << 20;
    public static final int MAX_CALLBACK_BYTES = 1 << 20;
    private SurvivalPageGeometry() {}

    /** A member can span pieces; its ranges within any one piece are contiguous and canonical. */
    public record MemberRange(int member, int firstVertex, int vertices) {
        public MemberRange {
            if (member < 0 || member >= 64 || firstVertex < 0 || vertices <= 0
                    || firstVertex % 4 != 0 || vertices % 4 != 0
                    || firstVertex > Integer.MAX_VALUE - vertices)
                throw new IllegalArgumentException("Invalid complete-quad native member range");
        }
        public int firstIndex() { return Math.multiplyExact(firstVertex / 4, 6); }
        public int indexCount() { return Math.multiplyExact(vertices / 4, 6); }
    }
    public static int capacityVertices(int stride) {
        if (stride < 12 || stride > MAX_PIECE_BYTES / 4)
            throw new IllegalArgumentException("Unsupported bounded native vertex stride");
        return MAX_PIECE_BYTES / Math.multiplyExact(stride, 4) * 4;
    }
    public static int memberIndex(SurvivalVisualPage page, CubePos member) {
        Objects.requireNonNull(page); Objects.requireNonNull(member);
        long x = (long) member.x() - page.minimum().x(), y = (long) member.y() - page.minimum().y(),
                z = (long) member.z() - page.minimum().z();
        if (x < 0 || x > 3 || y < 0 || y > 3 || z < 0 || z > 3)
            throw new IllegalArgumentException("Cube is not within its native page");
        return (int) ((y * 4 + z) * 4 + x);
    }
    public static void requireRanges(List<MemberRange> ranges, int vertices) {
        Objects.requireNonNull(ranges);
        if (vertices < 0 || vertices % 4 != 0 || ranges.size() > 64)
            throw new IllegalArgumentException("Invalid bounded native page ranges");
        int expected = 0, previous = -1;
        for (var range : ranges) {
            if (range.firstVertex != expected || range.member <= previous)
                throw new IllegalArgumentException("Native ranges must partition bytes in ascending member order");
            expected = Math.addExact(expected, range.vertices); previous = range.member;
        }
        if (expected != vertices) throw new IllegalArgumentException("Native ranges do not cover exact emitted vertices");
    }
    /**
     * Translate only native Position floats AFTER Iris has emitted cube-local material/face metadata.
     * The supplied slice is owned and writable; all non-position bytes are left bit-identical.
     */
    public static void relocatePositions(ByteBuffer ownedBytes, int stride, List<MemberRange> ranges) {
        Objects.requireNonNull(ownedBytes);
        capacityVertices(stride);
        if (ownedBytes.isReadOnly() || ownedBytes.remaining() > MAX_PIECE_BYTES || ownedBytes.remaining() % stride != 0)
            throw new IllegalArgumentException("Invalid owned native vertex bytes");
        int vertices = ownedBytes.remaining() / stride;
        requireRanges(ranges, vertices);
        var bytes = ownedBytes.slice().order(ByteOrder.nativeOrder());
        // Validate the whole payload before changing one byte. Float model offsets remain supported.
        for (int vertex = 0; vertex < vertices; vertex++) for (int axis = 0; axis < 3; axis++)
            if (!Float.isFinite(bytes.getFloat(vertex * stride + axis * Float.BYTES)))
                throw new IllegalArgumentException("Non-finite native model position");
        for (var range : ranges) {
            int x = (range.member & 3) * 16, y = (range.member >>> 4) * 16, z = (range.member >>> 2 & 3) * 16;
            for (int vertex = range.firstVertex; vertex < range.firstVertex + range.vertices; vertex++) {
                int offset = Math.multiplyExact(vertex, stride);
                bytes.putFloat(offset, bytes.getFloat(offset) + x);
                bytes.putFloat(offset + 4, bytes.getFloat(offset + 4) + y);
                bytes.putFloat(offset + 8, bytes.getFloat(offset + 8) + z);
            }
        }
    }
}
