package io.earthplatform.minecraft.cubic.client;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import javax.annotation.Nullable;

/**
 * The texture names the lightmap rotates among: the original, which its texture keeps owning, and two
 * extras this owns. GL names are unsigned, so any int but 0 and -1 (the game's "not assigned") is one.
 */
final class LightmapRing {
    @Nullable private int[] ids;
    private int cursor;

    private static boolean assigned(int id) {
        return id != 0 && id != -1;
    }

    boolean active() {
        return ids != null;
    }

    int[] snapshot() {
        return ids == null ? new int[0] : ids.clone();
    }

    boolean owns(int id) {
        return ids != null && (ids[0] == id || ids[1] == id || ids[2] == id);
    }

    /** The original name when current is one of the ring's, else current. */
    int original(int current) {
        return owns(current) ? ids[0] : current;
    }

    /** The name to upload to next, making the two extras with create the first time. */
    int advance(int current, IntSupplier create, IntConsumer free) {
        if (ids == null) {
            if (!assigned(current)) throw new IllegalStateException("Missing original lightmap texture");
            int first = -1;
            int second = -1;
            try {
                first = create.getAsInt();
                if (!assigned(first) || first == current) throw new IllegalStateException("Invalid lightmap allocation");
                second = create.getAsInt();
                if (!assigned(second) || second == current || second == first) throw new IllegalStateException("Invalid lightmap allocation");
                ids = new int[] {current, first, second};
                cursor = 0;
            } catch (RuntimeException failure) {
                if (assigned(first) && first != current) rollback(first, free, failure);
                if (assigned(second) && second != current && second != first) rollback(second, free, failure);
                throw failure;
            }
        } else if (!owns(current)) {
            throw new IllegalStateException("Lightmap texture changed outside its owner");
        }
        cursor = (cursor + 1) % ids.length;
        return ids[cursor];
    }

    private static void rollback(int id, IntConsumer free, RuntimeException failure) {
        try {
            free.accept(id);
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    /** Frees the two extras, trying both even when one fails. The caller has gone back to the original first. */
    void release(IntConsumer free) {
        int[] owned = ids;
        ids = null;
        cursor = 0;
        if (owned == null) return;
        RuntimeException failure = null;
        for (int i = 1; i < owned.length; i++) {
            try {
                free.accept(owned[i]);
            } catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }
}
