package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable, bounded common-side palette capture. Content identity is not publication authority. */
public final class NativeReplacementCapture {
    public static final int AIR = 0, STONE = 1, DIORITE = 2;
    public static final int MAX_CELLS = 20 * 20 * 20;
    private final CubePos position;
    private final long minX, minY, minZ, maxX, maxY, maxZ;
    private final byte[] paletteVoxels;
    private final NativeReplacementCapture source;
    private final int overlayIndex, overlayMaterial;
    private final String contentIdentity;

    /** Values are complete clipped owned18-cubed + read halo, in Y/Z/X order. */
    public NativeReplacementCapture(CubePos position, byte[] paletteVoxels) {
        this(position, paletteVoxels, false);
    }
    private NativeReplacementCapture(CubePos position, byte[] paletteVoxels, boolean freshOwnedArray) {
        this.position = Objects.requireNonNull(position, "position");
        RollingCubePlan.validateWindowCenter(position);
        minX = position.minBlockX() - 2; minZ = position.minBlockZ() - 2;
        maxX = position.minBlockX() + 18; maxZ = position.minBlockZ() + 18;
        minY = Math.max(position.minBlockY() - 1, RollingCubePlan.GAMEPLAY_MIN_Y) - 1;
        maxY = Math.min(position.minBlockY() + 17, RollingCubePlan.GAMEPLAY_MAX_Y_EXCLUSIVE) + 1;
        int cells = Math.toIntExact((maxX - minX) * (maxY - minY) * (maxZ - minZ));
        Objects.requireNonNull(paletteVoxels, "paletteVoxels");
        if (cells > MAX_CELLS || paletteVoxels.length != cells)
            throw new IllegalArgumentException("Complete exact bounded replacement capture required");
        this.paletteVoxels = freshOwnedArray ? paletteVoxels : paletteVoxels.clone();
        source = null; overlayIndex = -1; overlayMaterial = -1;
        for (byte value : this.paletteVoxels) if (value < AIR || value > DIORITE)
            throw new IllegalArgumentException("Unknown or unsupported replacement palette value");
        contentIdentity = calculateIdentity();
    }

    private NativeReplacementCapture(NativeReplacementCapture source, int index, int material) {
        if (source.source != null) throw new IllegalStateException("Only one prospective capture overlay may be retained");
        this.source = source; paletteVoxels = null; overlayIndex = index; overlayMaterial = material;
        position = source.position; minX = source.minX; minY = source.minY; minZ = source.minZ;
        maxX = source.maxX; maxY = source.maxY; maxZ = source.maxZ;
        contentIdentity = calculateIdentity();
    }
    private String calculateIdentity() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("earth-native-replacement-capture-v1\0".getBytes(StandardCharsets.UTF_8));
            ByteBuffer metadata = ByteBuffer.allocate(3 * Integer.BYTES + 6 * Long.BYTES);
            metadata.putInt(position.x()).putInt(position.y()).putInt(position.z());
            metadata.putLong(minX).putLong(minY).putLong(minZ).putLong(maxX).putLong(maxY).putLong(maxZ);
            digest.update(metadata.array());
            if (paletteVoxels != null) digest.update(paletteVoxels);
            else for (int index = 0; index < cellCount(); index++) digest.update((byte) value(index));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public CubePos position() { return position; }
    public CubePos editedCube() { return position; }
    public long minX() { return minX; } public long minY() { return minY; } public long minZ() { return minZ; }
    public long maxXExclusive() { return maxX; } public long maxYExclusive() { return maxY; } public long maxZExclusive() { return maxZ; }
    public int cellCount() { return paletteVoxels == null ? source.cellCount() : paletteVoxels.length; }
    public long declaredBytes() { return cellCount(); }
    public String contentIdentity() { return contentIdentity; }
    public String contentDigest() { return contentIdentity; }
    /** Use only for explicitly bounded wire serialization, never as a retained mutable source. */
    public byte[] clonedBytes() {
        if (paletteVoxels != null) return paletteVoxels.clone();
        byte[] result = source.paletteVoxels.clone(); result[overlayIndex] = (byte) overlayMaterial; return result;
    }
    /** Serial transaction compaction allocates exactly one owned array, with no defensive copy. */
    public NativeReplacementCapture compact() {
        return source == null ? this : new NativeReplacementCapture(position, clonedBytes(), true);
    }
    public boolean containsReadCell(long x, long y, long z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }
    private int index(long x, long y, long z) {
        if (!containsReadCell(x, y, z)) throw new IndexOutOfBoundsException("Voxel is outside the exact replacement read halo");
        return Math.toIntExact(((y - minY) * (maxZ - minZ) + (z - minZ)) * (maxX - minX) + x - minX);
    }
    private int value(int index) {
        return paletteVoxels != null ? paletteVoxels[index] : index == overlayIndex ? overlayMaterial : source.paletteVoxels[index];
    }
    /** Bounded zero-copy prospective edit of any read cell, including the adjacent workspace. */
    public NativeReplacementCapture withReadMaterial(long x, long y, long z, int material) {
        if (material < AIR || material > DIORITE) throw new IllegalArgumentException("Unsupported replacement material");
        int index = index(x, y, z);
        return value(index) == material ? this : new NativeReplacementCapture(this, index, material);
    }
    /** Every retained view of a shared voxel must agree before whole-set admission/publication. */
    public static void verifySharedValues(java.util.List<NativeReplacementCapture> captures) {
        if (captures == null || captures.size() != 2)
            throw new IllegalArgumentException("Exactly two complete replacement captures required");
        var a = Objects.requireNonNull(captures.get(0)); var b = Objects.requireNonNull(captures.get(1));
        for (long y = Math.max(a.minY, b.minY); y < Math.min(a.maxY, b.maxY); y++)
            for (long z = Math.max(a.minZ, b.minZ); z < Math.min(a.maxZ, b.maxZ); z++)
                for (long x = Math.max(a.minX, b.minX); x < Math.min(a.maxX, b.maxX); x++)
                    if (a.materialAt(x, y, z) != b.materialAt(x, y, z))
                        throw new IllegalArgumentException("Shared replacement capture voxel values disagree");
    }
    public int materialAt(long x, long y, long z) {
        return value(index(x, y, z));
    }
    public int argbAt(long x, long y, long z) {
        return switch (materialAt(x, y, z)) {
            case AIR -> 0;
            case STONE -> 0xff808080;
            case DIORITE -> 0xffdedede;
            default -> throw new AssertionError("Validated capture palette changed");
        };
    }
    /** Prospective one-cell edit only within the actual16-cubed workspace, before any durable write. */
    public NativeReplacementCapture withMaterial(long x, long y, long z, int material) {
        if (material < AIR || material > DIORITE) throw new IllegalArgumentException("Unsupported replacement material");
        if (Math.floorDiv(x, CubePos.SIZE) != position.x() || Math.floorDiv(y, CubePos.SIZE) != position.y()
                || Math.floorDiv(z, CubePos.SIZE) != position.z())
            throw new IllegalArgumentException("Prospective edit must be inside its workspace");
        byte[] changed = clonedBytes();
        changed[Math.toIntExact(((y - minY) * (maxZ - minZ) + (z - minZ)) * (maxX - minX) + x - minX)] = (byte) material;
        return new NativeReplacementCapture(position, changed, true);
    }
}
