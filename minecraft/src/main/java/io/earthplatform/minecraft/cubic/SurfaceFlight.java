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
    /**
     * How far off surface cubes are sent, in a straight line. Across, they go no further than the band of cubes
     * around the player (the server's view distance, at most {@link Cubic#MAX_HORIZONTAL_RADIUS} cubes, 192 blocks):
     * the client draws far terrain past that reach in their place (see {@code FarLod.nearReach}), so surface cubes
     * there would be drawn twice, and would mean four times the columns to survey, make and hold. This limit binds a
     * player more than about 330 blocks over the ground.
     */
    public static final int REACH = 384;
    /** Cubes kept under a column's lowest surface, for lighting and meshing, and over its highest, for trees. */
    public static final int BELOW = 1, ABOVE = 6;
    private static final int MAX_COLUMN_CUBES = 16;
    /** Most rows {@link #topRows} gives. */
    static final int TOP_ROWS = 2 * MAX_COLUMN_CUBES;
    /** Cubes of the band around the player that come before the surface. */
    static final int FIRST = 64;
    /**
     * Column surveys a flying player may have in flight on the cube workers. They share the workers with the cubes
     * players stand in, so a flyer entering new ground never queues hundreds of them ahead of those cubes.
     */
    static final int SURVEYS = 16;
    /** A column's bounds not worked out yet; see {@link #bounds}. */
    public static final long UNKNOWN = Long.MIN_VALUE;

    private SurfaceFlight() {}

    /** Whether a player at {@code y} over ground or water at {@code surfaceY} gets surface cubes. */
    public static boolean active(double y, int surfaceY, boolean underground) { return ENABLED && flying(y, surfaceY, underground); }

    static boolean flying(double y, int surfaceY, boolean underground) {
        return !underground && surfaceY != Integer.MIN_VALUE && y - surfaceY >= START_HEIGHT;
    }

    /**
     * How many column surveys may be in flight on the workers, from every player, before a call for a player asks for
     * none ({@link ColumnSurveys#lowest}); no limit on foot, as before. None for a flyer while its cubes are being
     * asked for ({@code asking}): those go to the workers first, and a cube near it over a column not surveyed yet is
     * asked for as on foot. A call with no player of its own, such as adopting or unloading a cube, passes whether
     * anyone flies.
     */
    static int surveyCap(boolean flying, boolean asking) {
        if (!flying) return ColumnSurveys.ANY;
        return asking ? 0 : SURVEYS;
    }

    /** A column's lowest and highest ground or water, packed. */
    public static long bounds(int low, int high) { return (long) low << 32 | high & 0xFFFFFFFFL; }
    public static int low(long bounds) { return (int) (bounds >> 32); }
    public static int high(long bounds) { return (int) bounds; }

    /**
     * The ground or water a player flies over, from the bounds of the cube column they are in (its highest, so a
     * cliff in it counts). While that column is not worked out yet, as when flying into new ground, the last
     * column's stands, so the player does not land and take off again on the way.
     */
    static int ground(long bounds, int last) { return bounds == UNKNOWN ? last : high(bounds); }

    /** Known column bounds, or {@link #UNKNOWN}; never asks for them. */
    @FunctionalInterface interface Bounds { long of(int x, int z); }

    /**
     * The surface cubes of the cube columns within {@code h} cubes across of the one at {@code cx, cz} whose bounds
     * are known, as {@link #column} picks them; see {@link #REACH}.
     */
    static void surface(int cx, int cz, int h, Bounds known, double eyeX, double eyeY, double eyeZ, LongConsumer out) {
        for (int dz = -h; dz <= h; dz++) for (int dx = -h; dx <= h; dx++) {
            if (!CubeOffsets.within(dx, 0, dz, h, 0)) continue;
            long bounds = known.of(cx + dx, cz + dz);
            if (bounds != UNKNOWN) column(cx + dx, cz + dz, low(bounds), high(bounds), eyeX, eyeY, eyeZ, out);
        }
    }

    /**
     * Client: the cube rows a column's tops shade, from the one under its lowest top up to its highest, for remeshing
     * the cubes beside it a flying player holds; packed as {@link #bounds}, or {@link #UNKNOWN} with no top in the
     * world. At most {@link #TOP_ROWS} rows: surface cubes come only in columns {@link #MAX_COLUMN_CUBES} rows tall,
     * so a build high over the ground does not make every row between them be looked at.
     */
    public static long topRows(int[] tops) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int top : tops) {
            if (top < CubeWorld.MIN_Y || top > CubeWorld.MAX_Y) continue;
            low = Math.min(low, top); high = Math.max(high, top);
        }
        if (low > high) return UNKNOWN;
        int from = Math.max(CubeWorld.MIN_Y >> 4, (low >> 4) - BELOW);
        return bounds(from, Math.min(high >> 4, from + TOP_ROWS - 1));
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
