package io.earthplatform.minecraft.cubic.client;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Three backing names: the DynamicTexture owns the original, this pool owns only two extras. */
final class LightmapRing {
    private int[] ids;
    private int cursor;

    boolean active() { return ids != null; }
    int[] snapshot() { return ids == null ? new int[0] : ids.clone(); }
    boolean owns(int id) {
        return ids != null && (ids[0] == id || ids[1] == id || ids[2] == id);
    }
    int original(int current) { return owns(current) ? ids[0] : current; }

    int advance(int current, IntSupplier create, IntConsumer free) {
        if (ids == null) {
            if (current <= 0) throw new IllegalStateException("Missing original lightmap texture");
            int first = -1, second = -1;
            try {
                first = create.getAsInt();
                if (first <= 0 || first == current) throw new IllegalStateException("Invalid lightmap allocation");
                second = create.getAsInt();
                if (second <= 0 || second == current || second == first)
                    throw new IllegalStateException("Invalid lightmap allocation");
                ids = new int[] {current, first, second};
                cursor = 0;
            } catch (RuntimeException failure) {
                if (first > 0 && first != current) rollback(first, free, failure);
                if (second > 0 && second != current && second != first) rollback(second, free, failure);
                throw failure;
            }
        } else if (!owns(current)) throw new IllegalStateException("Lightmap texture changed outside its owner");
        cursor = (cursor + 1) % ids.length;
        return ids[cursor];
    }

    private static void rollback(int id, IntConsumer free, RuntimeException failure) {
        try { free.accept(id); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    }

    /** Caller restores original(current) before this call, including when cleanup throws. */
    void release(IntConsumer free) {
        int[] owned = ids;
        ids = null;
        cursor = 0;
        if (owned == null) return;
        RuntimeException failure = null;
        for (int i = 1; i < owned.length; i++) {
            try { free.accept(owned[i]); }
            catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }
}
