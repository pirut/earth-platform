package io.earthplatform.core;

/** A signed, independent 16 x 16 x 16 cube. Block coordinates intentionally use longs. */
public record CubePos(int x, int y, int z) implements Comparable<CubePos> {
    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE;

    public static CubePos fromBlock(long x, long y, long z) {
        return new CubePos(section(x), section(y), section(z));
    }
    public static int section(long block) { return Math.toIntExact(Math.floorDiv(block, SIZE)); }
    public static int local(long block) { return Math.floorMod(block, SIZE); }
    /** X is fastest, then Z, then Y. */
    public static int index(int x, int y, int z) {
        checkLocal(x); checkLocal(y); checkLocal(z);
        return (y * SIZE + z) * SIZE + x;
    }
    public static int indexOfBlock(long x, long y, long z) { return index(local(x), local(y), local(z)); }
    public static void checkIndex(int index) {
        if (index < 0 || index >= VOLUME) throw new IndexOutOfBoundsException("Cube index: " + index);
    }
    private static void checkLocal(int value) {
        if (value < 0 || value >= SIZE) throw new IndexOutOfBoundsException("Local coordinate: " + value);
    }
    public long minBlockX() { return (long) x * SIZE; }
    public long minBlockY() { return (long) y * SIZE; }
    public long minBlockZ() { return (long) z * SIZE; }
    public CubePos offset(int dx, int dy, int dz) {
        return new CubePos(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }
    @Override public int compareTo(CubePos other) {
        int c = Integer.compare(x, other.x);
        if (c == 0) c = Integer.compare(y, other.y);
        return c == 0 ? Integer.compare(z, other.z) : c;
    }
}
