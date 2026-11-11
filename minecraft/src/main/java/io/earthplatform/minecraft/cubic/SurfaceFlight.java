package io.earthplatform.minecraft.cubic;

import java.util.function.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Extra surface cubes for low flight, independent of the player's collision/ticking band. */
public final class SurfaceFlight {
    public static final boolean ENABLED = !"false".equals(System.getProperty("earthplatform.cubic.flightSurface"));
    public static final int START_HEIGHT = 48;
    /** First-pass limit for extra surface cubes; beyond it the far voxel pyramid takes over. */
    public static final int REACH = 384;
    private static final int BELOW = 1, ABOVE = 6, MAX_COLUMN_CUBES = 16;

    private SurfaceFlight() {}

    public static boolean active(double eyeY, int surfaceY, boolean underground) {
        return ENABLED && !underground && surfaceY != Integer.MIN_VALUE && eyeY - surfaceY >= START_HEIGHT;
    }

    /**
     * A thin ground/water band, with room for tall trees and one lighting/meshing neighbour below.
     * Bounds must be known. Extreme cliffs fall back to far terrain rather than expanding without
     * limit. Distance is to the nearest point of the surface column, not to sea level.
     */
    public static void column(int x, int z, int low, int high, double eyeX, double eyeY, double eyeZ, LongConsumer out) {
        if (low == Integer.MIN_VALUE || low == Integer.MAX_VALUE || high < low) return;
        double dx = Math.max(0, Math.abs(eyeX - ((double) x * 16 + 8)) - 8);
        double dz = Math.max(0, Math.abs(eyeZ - ((double) z * 16 + 8)) - 8);
        double dy = Math.max(0, Math.max(low - eyeY, eyeY - (high + 1.0)));
        if (dx * dx + dy * dy + dz * dz > (double) REACH * REACH) return;
        int bottom = Math.max(CubeWorld.MIN_Y >> 4, (low >> 4) - BELOW);
        int top = Math.min(CubeWorld.MAX_Y >> 4, (high >> 4) + ABOVE);
        if (top - bottom + 1 > MAX_COLUMN_CUBES) return;
        for (int y = bottom; y <= top; y++) out.accept(CubeKey.pack(x, y, z));
    }

    /** Player's immediate surroundings first, surface next, then the rest of the original band. */
    static void merge(int cx, int cy, int cz, CubeOffsets offsets, LongArrayList surface, LongArrayList out) {
        out.clear();
        if (surface.isEmpty()) {
            for (int i = 0; i < offsets.size(); i++) out.add(CubeKey.pack(cx + offsets.dx(i), cy + offsets.dy(i), cz + offsets.dz(i)));
            return;
        }
        LongOpenHashSet added = new LongOpenHashSet();
        int first = Math.min(64, offsets.size());
        for (int i = 0; i < first; i++) addOffset(out, added, offsets, i, cx, cy, cz);
        for (long key : surface) if (added.add(key)) out.add(key);
        for (int i = first; i < offsets.size(); i++) addOffset(out, added, offsets, i, cx, cy, cz);
    }

    private static void addOffset(LongArrayList out, LongOpenHashSet added, CubeOffsets offsets, int i, int cx, int cy, int cz) {
        long key = CubeKey.pack(cx + offsets.dx(i), cy + offsets.dy(i), cz + offsets.dz(i));
        if (added.add(key)) out.add(key);
    }
}
