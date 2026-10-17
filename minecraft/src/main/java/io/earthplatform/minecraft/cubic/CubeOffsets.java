package io.earthplatform.minecraft.cubic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cube offsets inside a horizontal circle and vertical band, nearest first, or for a look
 * direction, nearest first with those out of view counted further off (see {@link ViewOrder}).
 */
public final class CubeOffsets {
    private static final ConcurrentHashMap<Long, CubeOffsets> CACHE = new ConcurrentHashMap<>();
    private final int[] dx, dy, dz;

    private CubeOffsets(int h, int v, int sector) {
        List<int[]> all = new ArrayList<>();
        for (int y = -v; y <= v; y++) for (int z = -h; z <= h; z++) for (int x = -h; x <= h; x++) {
            if (within(x, y, z, h, v)) all.add(new int[]{x, y, z});
        }
        // Vertical distance weighs more: the ground below and above matters before far horizon.
        double lookX = sector < 0 ? 0 : ViewOrder.lookX(ViewOrder.sectorYaw(sector)), lookZ = sector < 0 ? 0 : ViewOrder.lookZ(ViewOrder.sectorYaw(sector));
        all.sort(Comparator.comparingDouble(o -> {
            double distance = o[0] * o[0] + o[2] * o[2] + 2.25 * o[1] * o[1];
            if (sector < 0) return distance;
            // The cubes right around the player, under and over them as well, come first whichever way they face.
            double later = ViewOrder.later(o[0], o[2], lookX, lookZ, 1.5);
            return distance * later * later;
        }));
        dx = new int[all.size()]; dy = new int[all.size()]; dz = new int[all.size()];
        for (int i = 0; i < all.size(); i++) { dx[i] = all.get(i)[0]; dy[i] = all.get(i)[1]; dz[i] = all.get(i)[2]; }
    }

    public static CubeOffsets of(int h, int v) { return of(h, v, -1); }

    /** For a look direction's {@link ViewOrder#sector}, or by distance alone for -1. */
    public static CubeOffsets of(int h, int v, int sector) {
        return CACHE.computeIfAbsent(((long) h << 40) | ((long) v << 8) | (sector + 1), k -> new CubeOffsets(h, v, sector));
    }

    public static boolean within(int x, int y, int z, int h, int v) {
        return Math.abs(y) <= v && (long) x * x + (long) z * z <= (long) h * h + h;
    }

    public int size() { return dx.length; }
    public int dx(int i) { return dx[i]; }
    public int dy(int i) { return dy[i]; }
    public int dz(int i) { return dz[i]; }
}
