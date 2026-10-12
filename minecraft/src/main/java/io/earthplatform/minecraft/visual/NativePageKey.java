package io.earthplatform.minecraft.visual;

/** Integral signed 64-metre native page indices. Never absolute float coordinates. */
public record NativePageKey(long x, long y, long z) implements Comparable<NativePageKey> {
    public static final int BLOCKS = 64;
    public NativePageKey {
        checkedOrigin(x); checkedOrigin(y); checkedOrigin(z);
    }
    private static long checkedOrigin(long index) {
        long origin = Math.multiplyExact(index, (long) BLOCKS);
        Math.addExact(origin, BLOCKS);
        return origin;
    }
    public static NativePageKey containing(long blockX, long blockY, long blockZ) {
        return new NativePageKey(Math.floorDiv(blockX, BLOCKS), Math.floorDiv(blockY, BLOCKS), Math.floorDiv(blockZ, BLOCKS));
    }
    public long minX() { return checkedOrigin(x); }
    public long minY() { return checkedOrigin(y); }
    public long minZ() { return checkedOrigin(z); }
    public long maxXExclusive() { return Math.addExact(minX(), BLOCKS); }
    public long maxYExclusive() { return Math.addExact(minY(), BLOCKS); }
    public long maxZExclusive() { return Math.addExact(minZ(), BLOCKS); }
    public boolean contains(long bx, long by, long bz) {
        return bx >= minX() && bx < maxXExclusive() && by >= minY() && by < maxYExclusive() && bz >= minZ() && bz < maxZExclusive();
    }
    public int localX(long blockX) { return local(blockX, minX()); }
    public int localY(long blockY) { return local(blockY, minY()); }
    public int localZ(long blockZ) { return local(blockZ, minZ()); }
    private static int local(long value, long origin) {
        long local = Math.subtractExact(value, origin);
        if (local < 0 || local >= BLOCKS) throw new IllegalArgumentException("Block is outside the exact native page");
        return (int) local;
    }
    @Override public int compareTo(NativePageKey other) {
        int c = Long.compare(x, other.x);
        if (c == 0) c = Long.compare(y, other.y);
        return c == 0 ? Long.compare(z, other.z) : c;
    }
}
