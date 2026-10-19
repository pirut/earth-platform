package io.earthplatform.minecraft.cubic.client;

import java.util.BitSet;

/** Texels of a buffer's CPU mirror written since they were last uploaded; only adjacent ones are uploaded together. Render thread only. */
final class DirtyTexels {
    /** Uploads the texels from first up to end from the mirror. */
    @FunctionalInterface
    interface Upload {
        void range(int first, int end);
    }

    private final BitSet dirty = new BitSet();

    void mark(int texel) {
        dirty.set(texel);
    }

    /** Uploads each run of adjacent texels once. A run whose upload throws stays dirty, as do the runs after it. */
    void flush(Upload upload) {
        int first = dirty.nextSetBit(0);
        while (first >= 0) {
            int end = dirty.nextClearBit(first);
            upload.range(first, end);
            dirty.clear(first, end);
            first = dirty.nextSetBit(end);
        }
    }
}
