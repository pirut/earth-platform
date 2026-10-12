package io.earthplatform.minecraft.scene;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/** Privately owned sealed heap bytes. This receipt is geometry evidence, never publication authority. */
public final class NativeVisualCpuPiece {
    public static final int MAX_BYTES = 1 << 20;
    public static final int MAX_QUADS = MAX_BYTES / NativeVisualCapsule.QUAD_BYTES;
    public static final int CAPACITY_BYTES = MAX_QUADS * NativeVisualCapsule.QUAD_BYTES;
    /** Scan ranges include culled/air cells, so zero geometry cannot be confused with missing work. */
    public record MemberRange(int member, int firstFace, int endFace, int firstQuad, int quads) {
        public MemberRange {
            if (member < 0 || member >= 64 || firstFace < 0 || endFace <= firstFace || endFace > 4096 * 6
                    || firstQuad < 0 || firstQuad > MAX_QUADS || quads < 0 || quads > MAX_QUADS || quads > endFace - firstFace) throw new IllegalArgumentException("Invalid completed member scan range");
        }
        public boolean completesMember() { return endFace == 4096 * 6; }
    }
    private final byte[] bytes;
    private final int length, ordinal, originX, originY, originZ;
    private final String requestStamp, bytesDigest;
    private final NativeVisualCapsule.Identity capsuleIdentity;
    private final List<MemberRange> ranges;
    private final boolean last;
    NativeVisualCpuPiece(byte[] ownedBytes, int length, int ordinal, int x, int y, int z, String stamp,
                         NativeVisualCapsule.Identity capsule, List<MemberRange> ranges, boolean last) {
        if (ownedBytes.length != CAPACITY_BYTES || length < 0 || length > ownedBytes.length
                || length % NativeVisualCapsule.QUAD_BYTES != 0 || ordinal < 0 || ranges.size() > 64)
            throw new IllegalArgumentException("Invalid bounded native piece");
        bytes = ownedBytes; this.length = length; this.ordinal = ordinal;
        originX = x; originY = y; originZ = z; requestStamp = Objects.requireNonNull(stamp);
        capsuleIdentity = Objects.requireNonNull(capsule); this.ranges = List.copyOf(ranges); this.last = last;
        int q = 0, previousMember = -1;
        for (var range : ranges) {
            if (range.firstQuad() != q || range.member() <= previousMember) throw new IllegalArgumentException("Noncanonical range quads or members");
            q = Math.addExact(q, range.quads()); previousMember = range.member();
        }
        if (q != quads()) throw new IllegalArgumentException("Range geometry does not match byte count");
        bytesDigest = NativeVisualCapsule.sha256(bytes, length);
    }
    public int byteLength() { return length; }
    public int vertices() { return length / NativeVisualCapsule.STRIDE; }
    public int quads() { return length / NativeVisualCapsule.QUAD_BYTES; }
    public int indexCount() { return quads() * 6; }
    public int ordinal() { return ordinal; }
    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }
    public String requestStamp() { return requestStamp; }
    public NativeVisualCapsule.Identity capsuleIdentity() { return capsuleIdentity; }
    public String bytesDigest() { return bytesDigest; }
    public List<MemberRange> memberRanges() { return ranges; }
    public boolean completesRequest() { return last; }
    /** Charges the physically retained array, including unused tail in a short/final piece. */
    public long retainedBytes() { return bytes.length + 512L + ranges.size() * 48L; }
    public void copyTo(ByteBuffer destination) {
        if (destination.remaining() < length) throw new IllegalArgumentException("Insufficient owner upload storage");
        destination.put(bytes, 0, length);
    }
}
