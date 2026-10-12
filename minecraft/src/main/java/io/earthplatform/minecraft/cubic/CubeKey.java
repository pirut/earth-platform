package io.earthplatform.minecraft.cubic;

import net.minecraft.core.BlockPos;

/**
 * Packs a cube coordinate into one long. X and Z keep 22 signed bits (about ±33.5 million blocks),
 * Y keeps 20 signed bits (about ±8.4 million blocks), so every Earth position fits.
 */
public final class CubeKey {
    private static final int XZ_BITS = 22, Y_BITS = 20;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1, Y_MASK = (1L << Y_BITS) - 1;
    private CubeKey() {}

    public static long pack(int x, int y, int z) {
        return ((x & XZ_MASK) << (XZ_BITS + Y_BITS)) | ((z & XZ_MASK) << Y_BITS) | (y & Y_MASK);
    }
    public static long ofBlock(int x, int y, int z) { return pack(x >> 4, y >> 4, z >> 4); }
    public static long ofBlock(BlockPos pos) { return ofBlock(pos.getX(), pos.getY(), pos.getZ()); }
    public static int x(long key) { return (int) (key << (64 - XZ_BITS - XZ_BITS - Y_BITS) >> (64 - XZ_BITS)); }
    public static int z(long key) { return (int) (key << (64 - XZ_BITS - Y_BITS) >> (64 - XZ_BITS)); }
    public static int y(long key) { return (int) (key << (64 - Y_BITS) >> (64 - Y_BITS)); }
    /** Column key for a 16x16 cube column. */
    public static long column(int cubeX, int cubeZ) { return ((long) cubeX << 32) | (cubeZ & 0xFFFFFFFFL); }
    public static int columnX(long column) { return (int) (column >> 32); }
    public static int columnZ(long column) { return (int) column; }
    public static String toString(long key) { return "[" + x(key) + "," + y(key) + "," + z(key) + "]"; }
}
