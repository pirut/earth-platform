package io.earthplatform.minecraft.lod;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Immutable, bounded CPU copy of vertex bytes produced by the official DH
 * LodQuadBuilder. This value owns bytes only; it does not build vertices, load
 * the official runtime, or allocate GPU resources.
 */
public final class DhPreparedVertices {
    private static final int BYTES_PER_QUAD = 4 * 16;
    private static final int MAX_BYTES = 4096 * BYTES_PER_QUAD;

    private final byte[] bytes;

    public DhPreparedVertices(byte[] officialVertexBytes) {
        Objects.requireNonNull(officialVertexBytes, "officialVertexBytes");
        int length = officialVertexBytes.length;
        if (length == 0 || length % BYTES_PER_QUAD != 0 || length > MAX_BYTES) {
            throw new IllegalArgumentException("DH vertex payload must contain 1..4096 complete 64-byte quads");
        }
        bytes = officialVertexBytes.clone();
    }

    public int byteCount() { return bytes.length; }

    public int quads() { return bytes.length / BYTES_PER_QUAD; }

    public byte[] copyBytes() { return bytes.clone(); }

    /**
     * Copies into the target's remaining region and advances its position by
     * {@link #byteCount()}. The limit and byte order are unchanged. Insufficient
     * remaining space throws {@link BufferOverflowException} before any write;
     * a read-only target is also rejected without changing either buffer state
     * or contents.
     */
    public void writeTo(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        if (target.remaining() < bytes.length) throw new BufferOverflowException();
        target.put(bytes);
    }
}
