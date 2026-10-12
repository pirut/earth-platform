package io.earthplatform.minecraft.scene;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Single-writer pure CPU worker. Inputs contain only privately owned bytes, integers and immutable identities. */
public final class NativeVisualCpuBuilder {
    public static final int HALO_SIDE = 66, HALO_BYTES = HALO_SIDE * HALO_SIDE * HALO_SIDE;
    /** Only Input.state can create this failure; an oracle must also match the exact observed instance. */
    static final class UnknownDependency extends IllegalStateException {
        private final Input input;
        private final int x, y, z;
        private UnknownDependency(Input input, int x, int y, int z) {
            super("Unknown native visual dependency; defer whole request", null);
            this.input = input; this.x = x; this.y = y; this.z = z;
        }
        boolean matches(Input input, int x, int y, int z) {
            return this.input == input && this.x == x && this.y == y && this.z == z;
        }
    }
    public static final class Input {
        private final int x, y, z;
        private final byte[] palette;
        private final int[] members;
        private final String stamp;
        public Input(int originX, int originY, int originZ, byte[] pageWithHalo, int[] requiredMembers, String requestStamp) {
            if ((originX & 63) != 0 || (originY & 63) != 0 || (originZ & 63) != 0
                    || pageWithHalo.length != HALO_BYTES || requiredMembers.length < 1 || requiredMembers.length > 64)
                throw new IllegalArgumentException("A native request is one aligned 64 m page with its complete halo and 1..64 members");
            Math.addExact(originX, 64); Math.addExact(originY, 64); Math.addExact(originZ, 64);
            Math.subtractExact(originX, 1); Math.subtractExact(originY, 1); Math.subtractExact(originZ, 1);
            NativeVisualCapsule.requireDigest(requestStamp);
            x = originX; y = originY; z = originZ; stamp = requestStamp;
            // Caller must charge this defensive-copy peak before construction.
            palette = pageWithHalo.clone(); members = requiredMembers.clone();
            int previous = -1;
            for (int member : members) { if (member <= previous || member >= 64) throw new IllegalArgumentException("Members must be sorted and unique"); previous = member; }
            for (byte code : palette) if ((code & 255) > NativeVisualCapsule.SNOW && (code & 255) != NativeVisualCapsule.UNKNOWN)
                throw new IllegalArgumentException("Unsupported native palette");
        }
        public long retainedBytes() { return HALO_BYTES + members.length * 4L + 256; }
        public int memberCount() { return members.length; }
        public int originX() { return x; }
        public int originY() { return y; }
        public int originZ() { return z; }
        public String requestStamp() { return stamp; }
        public int member(int index) { return members[index]; }
        int state(int x, int y, int z) {
            if (x < -1 || y < -1 || z < -1 || x > 64 || y > 64 || z > 64) throw new IllegalStateException("Native lookup exceeded one-cell halo");
            int value = palette[((y + 1) * HALO_SIDE + z + 1) * HALO_SIDE + x + 1] & 255;
            if (value == NativeVisualCapsule.UNKNOWN) throw new UnknownDependency(this, x, y, z);
            return value;
        }
        public static int haloIndex(int x, int y, int z) {
            if (x < -1 || y < -1 || z < -1 || x > 64 || y > 64 || z > 64) throw new IllegalArgumentException();
            return ((y + 1) * HALO_SIDE + z + 1) * HALO_SIDE + x + 1;
        }
    }
    /** Cancellation never frees a writer's storage. The worker observes it and relinquishes ownership. */
    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
        public boolean isCancelled() { return cancelled.get(); }
    }
    /** Pure observer; count is cumulative across pieces and contains only fully completed face scans. */
    @FunctionalInterface public interface CompletedWork { void completedFaceScans(long count); }
    private final Input input;
    private final NativeVisualCapsule capsule;
    private final Cancellation cancellation;
    private final CompletedWork progress;
    private final AtomicReference<Thread> writer = new AtomicReference<>();
    private int memberIndex, faceCursor, ordinal;
    private boolean finished, failed;
    private long completedFaceScans, reportedFaceScans;
    public NativeVisualCpuBuilder(Input input, NativeVisualCapsule capsule, Cancellation cancellation) {
        this(input,capsule,cancellation,null);
    }
    public NativeVisualCpuBuilder(Input input, NativeVisualCapsule capsule, Cancellation cancellation, CompletedWork progress) {
        this.input = Objects.requireNonNull(input); this.capsule = Objects.requireNonNull(capsule); this.cancellation = Objects.requireNonNull(cancellation);
        this.progress = progress;
    }
    /** Exactly one piece per call. The caller retains its physical worker/output reservation while enqueue is blocked. */
    public NativeVisualCpuPiece nextPiece() {
        Thread current = Thread.currentThread();
        writer.compareAndSet(null,current);
        if (writer.get() != current) throw new IllegalStateException("Native worker requires its original writer");
        if (finished || failed) throw new IllegalStateException("Native worker is already terminal");
        try {
            checkCancellation();
            byte[] owned = new byte[NativeVisualCpuPiece.CAPACITY_BYTES];
            int bytes = 0;
            var ranges = new ArrayList<NativeVisualCpuPiece.MemberRange>(64);
            while (memberIndex < input.members.length) {
                int member = input.members[memberIndex], firstFace = faceCursor, firstQuad = bytes / NativeVisualCapsule.QUAD_BYTES;
                int memberX = (member & 3) * 16, memberZ = ((member >>> 2) & 3) * 16, memberY = (member >>> 4) * 16;
                while (faceCursor < 4096 * 6) {
                    checkCancellation();
                    int cell = faceCursor / 6, face = faceCursor % 6;
                    int x = memberX + (cell & 15), z = memberZ + ((cell >>> 4) & 15), y = memberY + (cell >>> 8);
                    int state = input.state(x, y, z);
                    if (state != NativeVisualCapsule.AIR
                            && input.state(x + NativeVisualCapsule.normal(face, 0), y + NativeVisualCapsule.normal(face, 1), z + NativeVisualCapsule.normal(face, 2)) == NativeVisualCapsule.AIR) {
                        if (bytes > owned.length - NativeVisualCapsule.QUAD_BYTES) break;
                        int mask = 0;
                        for (int bit = 0; bit < 8; bit++) if (input.state(x + NativeVisualCapsule.ring(face, bit, 0), y + NativeVisualCapsule.ring(face, bit, 1), z + NativeVisualCapsule.ring(face, bit, 2)) != NativeVisualCapsule.AIR) mask |= 1 << bit;
                        int variant = capsule.variantAt(state, input.x + x, input.y + y, input.z + z);
                        capsule.appendFace(owned, bytes, variant, face, mask, x, y, z);
                        bytes += NativeVisualCapsule.QUAD_BYTES;
                    }
                    faceCursor++;completedFaceScans++;
                    if((completedFaceScans&4095)==0)reportCompletedFaces();
                }
                if (faceCursor > firstFace) ranges.add(new NativeVisualCpuPiece.MemberRange(member, firstFace, faceCursor, firstQuad, bytes / NativeVisualCapsule.QUAD_BYTES - firstQuad));
                if (faceCursor < 4096 * 6) break;
                memberIndex++; faceCursor = 0;
                // Continue scanning culled/air faces at a capacity boundary. A following visible
                // face starts the next piece; an all-empty tail completes this same full piece.
            }
            checkCancellation();
            boolean last = memberIndex == input.members.length;
            var piece = new NativeVisualCpuPiece(owned, bytes, ordinal++, input.x, input.y, input.z, input.stamp, capsule.identity(), ranges, last);
            checkCancellation();
            finished = last;
            reportCompletedFaces(); // Flush a partial batch only after a successful nextPiece result exists.
            return piece;
        } catch (RuntimeException | Error failure) { failed = true; throw failure; }
    }
    private void reportCompletedFaces() {
        if(progress!=null&&completedFaceScans!=reportedFaceScans){progress.completedFaceScans(completedFaceScans);reportedFaceScans=completedFaceScans;}
    }
    private void checkCancellation() { if (cancellation.isCancelled()) throw new CancellationException("Native visual request cancelled"); }
    public long retainedInputBytes() { return input.retainedBytes(); }
}
