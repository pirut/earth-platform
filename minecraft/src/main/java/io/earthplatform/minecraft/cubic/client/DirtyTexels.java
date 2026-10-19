package io.earthplatform.minecraft.cubic.client;

import java.util.BitSet;

/** Dirty texels in an existing CPU mirror; only adjacent writes are combined. Render thread only. */
final class DirtyTexels {
    @FunctionalInterface interface Upload { void range(int first, int end); }
    private final BitSet dirty = new BitSet();

    void mark(int texel) { dirty.set(texel); }

    /** Successful ranges are retired; a failed upload retains that range and every later one. */
    void flush(Upload upload) {
        for (int first = dirty.nextSetBit(0); first >= 0; first = dirty.nextSetBit(first)) {
            int end = dirty.nextClearBit(first);
            upload.range(first, end);
            dirty.clear(first, end);
            first = end;
        }
    }
}
