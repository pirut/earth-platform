package io.earthplatform.minecraft.cubic.client;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Bounded sharing of requests: establish nearby coverage, then sharpen the view while filling the world. */
final class FarRequestOrder {
    private FarRequestOrder() {}

    static void merge(LongArrayList normal, LongArrayList zoom, LongArrayList out) {
        out.clear();
        LongOpenHashSet added = new LongOpenHashSet();
        int base = 0, focus = 0;
        while (base < Math.min(32, normal.size())) add(normal.getLong(base++), added, out);
        while (base < normal.size() || focus < zoom.size()) {
            if (focus < zoom.size()) add(zoom.getLong(focus++), added, out);
            for (int i = 0; i < 2 && base < normal.size(); i++) add(normal.getLong(base++), added, out);
        }
    }

    private static void add(long key, LongOpenHashSet added, LongArrayList out) {
        if (added.add(key)) out.add(key);
    }
}
