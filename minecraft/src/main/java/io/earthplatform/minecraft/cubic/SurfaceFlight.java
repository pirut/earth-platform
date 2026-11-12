package io.earthplatform.minecraft.cubic;

import java.util.Arrays;
import java.util.function.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * A player flying low over the ground still gets the cubes holding it, not only the band of cubes around them that
 * left the ground behind on the way up. {@code -Dearthplatform.cubic.flightSurface=true} turns it on; off, cubes are
 * asked for and sent as before.
 */
public final class SurfaceFlight {
    public static final boolean ENABLED = Boolean.getBoolean("earthplatform.cubic.flightSurface");
    /** Blocks above the ground where flight starts. */
    public static final int START_HEIGHT = 48;
    /** How far off surface cubes are sent; the far terrain shows the ground beyond. */
    public static final int REACH = 384;
    /** Cubes kept under a column's lowest surface, for lighting and meshing, and over its highest, for trees. */
    public static final int BELOW = 1, ABOVE = 6;
    private static final int MAX_COLUMN_CUBES = 16;
    /** Cubes of the band around the player that come before the surface. */
    static final int FIRST = 64;

    private SurfaceFlight() {}

    /** Whether a player at {@code y} over ground or water at {@code surfaceY} gets surface cubes. */
    public static boolean active(double y, int surfaceY, boolean underground) { return ENABLED && flying(y, surfaceY, underground); }

    static boolean flying(double y, int surfaceY, boolean underground) {
        return !underground && surfaceY != Integer.MIN_VALUE && y - surfaceY >= START_HEIGHT;
    }

    /**
     * The surface cubes of one cube column with its surface between {@code low} and {@code high}, when the nearest
     * point of it is within {@link #REACH}. A column with unknown bounds, or a cliff taller than
     * {@link #MAX_COLUMN_CUBES} cubes, is left to the far terrain.
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

    /**
     * The order a player's cubes are asked for and sent in: the first {@link #FIRST} cubes of the band around them,
     * then the surface nearest first, then the rest of the band. Without surface cubes it is the band's own order,
     * worked out as it is read, so a player on foot costs nothing more.
     */
    static final class Plan {
        private CubeOffsets offsets;
        private int cx, cy, cz;
        /** Surface keys nearest first, and its count; sort holds distance bits over each key's index. */
        private long[] surface = new long[0], sort = new long[0];
        private int count;

        /** Moves the band, keeping the surface cubes. */
        void around(CubeOffsets offsets, int cx, int cy, int cz) {
            this.offsets = offsets;
            this.cx = cx; this.cy = cy; this.cz = cz;
        }

        /** No surface cubes: the band alone. */
        void clear() { count = 0; }

        /** These surface cubes, nearest first as {@link ViewOrder} counts it from a player there. */
        void surface(LongSet cubes, double eyeX, double eyeY, double eyeZ, double yaw) {
            int n = cubes.size();
            if (surface.length < n) { surface = new long[n + n / 4]; sort = new long[surface.length]; }
            double lookX = ViewOrder.lookX(yaw), lookZ = ViewOrder.lookZ(yaw);
            int i = 0;
            for (var it = cubes.iterator(); it.hasNext(); i++) {
                long key = it.nextLong();
                double dx = (CubeKey.x(key) << 4) + 8 - eyeX, dy = (CubeKey.y(key) << 4) + 8 - eyeY, dz = (CubeKey.z(key) << 4) + 8 - eyeZ;
                double later = ViewOrder.ON ? ViewOrder.later(dx / 16, dz / 16, lookX, lookZ, 1.5) : 1;
                float far = (float) ((dx * dx + dy * dy + dz * dz) * later * later);
                surface[i] = key;
                // Positive floats sort as their bits do.
                sort[i] = (long) Float.floatToRawIntBits(far) << 32 | i;
            }
            Arrays.sort(sort, 0, n);
            for (i = 0; i < n; i++) sort[i] = surface[(int) sort[i]];
            long[] swap = surface; surface = sort; sort = swap;
            count = n;
        }

        int size() { return offsets.size() + count; }

        /** The cube at a place in the order; a surface cube inside the band comes twice, which asking and sending skip. */
        long key(int i) {
            int first = Math.min(FIRST, offsets.size());
            if (i >= first && i < first + count) return surface[i - first];
            if (i >= first) i -= count;
            return CubeKey.pack(cx + offsets.dx(i), cy + offsets.dy(i), cz + offsets.dz(i));
        }
    }
}
