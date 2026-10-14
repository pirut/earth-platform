package io.earthplatform.minecraft.cubic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Cube offsets inside a horizontal circle and vertical band, nearest first. */
public final class CubeOffsets {
    private static final ConcurrentHashMap<Long, CubeOffsets> CACHE = new ConcurrentHashMap<>();
    private final int[] dx, dy, dz;

    private CubeOffsets(int h, int v) {
        List<int[]> all = new ArrayList<>();
        for (int y = -v; y <= v; y++) for (int z = -h; z <= h; z++) for (int x = -h; x <= h; x++) {
            if (within(x, y, z, h, v)) all.add(new int[]{x, y, z});
        }
        // Vertical distance weighs more: the ground below and above matters before far horizon.
        all.sort(Comparator.comparingDouble(o -> o[0] * o[0] + o[2] * o[2] + 2.25 * o[1] * o[1]));
        dx = new int[all.size()]; dy = new int[all.size()]; dz = new int[all.size()];
        for (int i = 0; i < all.size(); i++) { dx[i] = all.get(i)[0]; dy[i] = all.get(i)[1]; dz[i] = all.get(i)[2]; }
    }

    public static CubeOffsets of(int h, int v) { return CACHE.computeIfAbsent(((long) h << 32) | v, k -> new CubeOffsets(h, v)); }

    public static boolean within(int x, int y, int z, int h, int v) {
        return Math.abs(y) <= v && (long) x * x + (long) z * z <= (long) h * h + h;
    }

    public int size() { return dx.length; }
    public int dx(int i) { return dx[i]; }
    public int dy(int i) { return dy[i]; }
    public int dz(int i) { return dz[i]; }
}
