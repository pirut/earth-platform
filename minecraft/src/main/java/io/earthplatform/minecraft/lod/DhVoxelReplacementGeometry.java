package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.enginebridge.EarthReplacementCapture;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Volume;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Exact, bounded, policy-neutral 3D replacement for one edited 16-cubed cube.
 * Owns its one-block collar too: excavation on a cube edge exposes faces whose
 * solid belongs to a neighboring cube. All visibility reads use a copied halo.
 * No model, world, palette, source, lease, publication or GL object is retained.
 * A digest identifies content only; it is NOT an authority certificate.
 */
public final class DhVoxelReplacementGeometry {
    public static final int KNOWN_AIR = 0;
    public static final int MAX_READ_CELLS = 20 * 20 * 20;
    public static final int MAX_FACES = 32768;
    public static final int BYTES_PER_FACE = 64;
    public static final long MAX_VERTEX_BYTES = 5L * 1024 * 1024;

    /** Explicit face/vertex allocation refusal; source and cancellation failures stay distinct. */
    public static final class BudgetExceeded extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public BudgetExceeded(String message) { super(message); }
    }

    /**
     * Called synchronously by capture, never by build or from a retained callback.
     * Return zero ONLY for explicitly known air; return 0xFFRRGGBB ONLY for a
     * certified opaque unit cube under the caller's pinned material policy.
     * Unknown/unsupported data must throw (or use a nonzero nonopaque sentinel).
     * The caller supplies an immutable coherent source and separately validates
     * its exact dependency vector and capture leases before publication.
     */
    @FunctionalInterface
    public interface VoxelSource {
        int argbAt(long x, long y, long z);
    }

    /** Half-open copied read extent. May include explicitly certified Y padding. */
    public record ReadBounds(long minX, long minY, long minZ,
                             long maxXExclusive, long maxYExclusive, long maxZExclusive) {
        public ReadBounds {
            requireReadSpan(minX, maxXExclusive);
            requireReadSpan(minY, maxYExclusive);
            requireReadSpan(minZ, maxZExclusive);
        }
        public int sizeX() { return Math.toIntExact(maxXExclusive - minX); }
        public int sizeY() { return Math.toIntExact(maxYExclusive - minY); }
        public int sizeZ() { return Math.toIntExact(maxZExclusive - minZ); }
        public boolean contains(long x, long y, long z) {
            return minX <= x && x < maxXExclusive && minY <= y && y < maxYExclusive
                    && minZ <= z && z < maxZExclusive;
        }
    }

    /** Private owned array, immutable scalar reads and content identity only. */
    public static final class Capture {
        private final CubePos editedCube;
        private final Volume ownedVolume;
        private final ReadBounds readBounds;
        private final int[] argb;
        private final NativeReplacementCapture compact;
        private final EarthReplacementCapture earth;
        private final String contentDigest;

        private Capture(CubePos editedCube, Volume ownedVolume, ReadBounds readBounds,
                        int[] ownedArgb, String contentDigest) {
            this.editedCube = editedCube;
            this.ownedVolume = ownedVolume;
            this.readBounds = readBounds;
            this.compact = null; this.earth = null;
            this.argb = ownedArgb; // Fresh private capture allocation, never supplied or exposed to the caller.
            this.contentDigest = contentDigest;
        }
        private Capture(NativeReplacementCapture compact, Volume ownedVolume, ReadBounds readBounds) {
            this.compact = Objects.requireNonNull(compact); this.earth = null;
            this.editedCube = compact.position(); this.ownedVolume = ownedVolume; this.readBounds = readBounds;
            this.argb = null;
            MessageDigest digest = sha256();
            digest.update("dh-voxel-compact-air-stone-diorite-v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(compact.contentIdentity().getBytes(StandardCharsets.US_ASCII));
            this.contentDigest = HexFormat.of().formatHex(digest.digest());
        }
        private Capture(EarthReplacementCapture earth, Volume ownedVolume, ReadBounds readBounds) {
            this.earth = Objects.requireNonNull(earth); this.compact = null; this.argb = null;
            this.editedCube = earth.position(); this.ownedVolume = ownedVolume; this.readBounds = readBounds;
            MessageDigest digest = sha256();
            digest.update("dh-voxel-earth-six-material-stone2-snow8-face-shade-v2\0".getBytes(StandardCharsets.UTF_8));
            digest.update(earth.contentIdentity().getBytes(StandardCharsets.US_ASCII));
            this.contentDigest = HexFormat.of().formatHex(digest.digest());
        }
        /** The exact immutable owner, without copying or decoding its byte payload. Null for legacy captures. */
        public EarthReplacementCapture earthCapture() { return earth; }
        private int materialAt(long x, long y, long z) {
            if (earth == null) return 0;
            return earth.materialAt(x,y,z) == EarthReplacementCapture.SNOW_BLOCK ? 8 : 2;
        }
        public CubePos editedCube() { return editedCube; }
        public Volume ownedVolume() { return ownedVolume; }
        public ReadBounds readBounds() { return readBounds; }
        public int cellCount() { return earth != null ? earth.cellCount() : compact == null ? argb.length : compact.cellCount(); }
        /** Exact retained voxel payload bytes (legacy ARGB ints or immutable compact palette), excluding object overhead. */
        public long declaredBytes() { return earth != null ? earth.declaredBytes() : compact == null ? (long) argb.length * Integer.BYTES : compact.declaredBytes(); }
        /** Stable SHA-256 binding encoding/material mapping, cube, exact extents and every immutable voxel value. */
        public String contentDigest() { return contentDigest; }
        public int argbAt(long x, long y, long z) {
            if (!readBounds.contains(x, y, z)) throw new IndexOutOfBoundsException("Voxel lies outside captured read halo");
            if (earth != null) return earth.argbAt(x, y, z);
            if (compact != null) return compact.argbAt(x, y, z);
            int localX = Math.toIntExact(x - readBounds.minX);
            int localY = Math.toIntExact(y - readBounds.minY);
            int localZ = Math.toIntExact(z - readBounds.minZ);
            return argb[(localY * readBounds.sizeZ() + localZ) * readBounds.sizeX() + localX];
        }
    }

    /** Complete immutable geometry, or no result. Partitioning into owners is a separate pooled step. */
    public static final class Mesh {
        private final Volume ownedVolume;
        private final List<Face> faces;
        private final long vertexBytes;
        private Mesh(Volume ownedVolume, List<Face> faces, long vertexBytes) {
            this.ownedVolume = ownedVolume;
            this.faces = List.copyOf(faces);
            this.vertexBytes = vertexBytes;
        }
        public Volume ownedVolume() { return ownedVolume; }
        public List<Face> faces() { return faces; }
        public int faceCount() { return faces.size(); }
        public long vertexBytes() { return vertexBytes; }
    }

    private DhVoxelReplacementGeometry() { }

    /**
     * Shares a deeply immutable common palette capture without decoding a second int array.
     * It retains only bounded scalar/voxel content, never actor, lease guard or callbacks.
     */
    public static Capture captureCompact(NativeReplacementCapture compact) {
        Objects.requireNonNull(compact, "compact");
        CubePos cube = compact.position(); long x = cube.minBlockX(), y = cube.minBlockY(), z = cube.minBlockZ();
        if (y < DhTerrainGeometry.MIN_Y || y + 16 > DhTerrainGeometry.MAX_Y_EXCLUSIVE)
            throw new IllegalArgumentException("Compact replacement outside native envelope");
        Volume owned = new Volume(x - 1, Math.max(y - 1, DhTerrainGeometry.MIN_Y), z - 1,
                x + 17, Math.min(y + 17, DhTerrainGeometry.MAX_Y_EXCLUSIVE), z + 17);
        ReadBounds read = new ReadBounds(owned.minX() - 1, owned.minY() - 1, owned.minZ() - 1,
                owned.maxXExclusive() + 1, owned.maxYExclusive() + 1, owned.maxZExclusive() + 1);
        if (read.minX() != compact.minX() || read.minY() != compact.minY() || read.minZ() != compact.minZ()
                || read.maxXExclusive() != compact.maxXExclusive() || read.maxYExclusive() != compact.maxYExclusive()
                || read.maxZExclusive() != compact.maxZExclusive() || compact.cellCount() > MAX_READ_CELLS)
            throw new IllegalArgumentException("Compact capture must have the exact clipped read halo");
        return new Capture(compact, owned, read);
    }

    /** Shares the complete six-material read20 owner, preserving exact material categories. */
    public static Capture captureEarth(EarthReplacementCapture earth) {
        Objects.requireNonNull(earth, "earth");
        CubePos cube = earth.position(); long x=cube.minBlockX(), y=cube.minBlockY(), z=cube.minBlockZ();
        if (y - 1 < DhTerrainGeometry.MIN_Y || y + 17 > DhTerrainGeometry.MAX_Y_EXCLUSIVE)
            throw new IllegalArgumentException("Earth replacement requires a complete interior owned18 collar");
        Volume owned = new Volume(x-1,y-1,z-1,x+17,y+17,z+17);
        ReadBounds read = new ReadBounds(x-2,y-2,z-2,x+18,y+18,z+18);
        if (earth.cellCount()!=MAX_READ_CELLS || earth.declaredBytes()!=MAX_READ_CELLS
                ||earth.minX()!=read.minX()||earth.minY()!=read.minY()||earth.minZ()!=read.minZ()
                ||earth.maxXExclusive()!=read.maxXExclusive()||earth.maxYExclusive()!=read.maxYExclusive()
                ||earth.maxZExclusive()!=read.maxZExclusive())
            throw new IllegalArgumentException("Exact immutable Earth read20 payload required");
        return new Capture(earth,owned,read);
    }

    /**
     * Copies exactly owned-volume + one read halo, at most 20 cubed cells, in
     * Y/Z/X order. At either native endpoint this is 20 x 19 x 20, not guessed
     * air: the source must provide even the outside-envelope padding explicitly.
     */
    public static Capture capture(CubePos editedCube, VoxelSource source, Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck, "cancellationCheck").run();
        Objects.requireNonNull(editedCube, "editedCube");
        Objects.requireNonNull(source, "source");
        // All reads must map to the existing representable 27-cube neighborhood.
        try {
            editedCube.offset(-1, -1, -1);
            editedCube.offset(1, 1, 1);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Replacement neighborhood exceeds signed cube coordinates", overflow);
        }
        long x = editedCube.minBlockX(), y = editedCube.minBlockY(), z = editedCube.minBlockZ();
        if (y < DhTerrainGeometry.MIN_Y || y + CubePos.SIZE > DhTerrainGeometry.MAX_Y_EXCLUSIVE) {
            throw new IllegalArgumentException("Edited cube is outside the native Y envelope");
        }
        Volume owned = new Volume(Math.subtractExact(x, 1), Math.max(y - 1, DhTerrainGeometry.MIN_Y),
                Math.subtractExact(z, 1), Math.addExact(x, CubePos.SIZE + 1),
                Math.min(y + CubePos.SIZE + 1, DhTerrainGeometry.MAX_Y_EXCLUSIVE), Math.addExact(z, CubePos.SIZE + 1));
        ReadBounds read = new ReadBounds(Math.subtractExact(owned.minX(), 1), owned.minY() - 1,
                Math.subtractExact(owned.minZ(), 1), Math.addExact(owned.maxXExclusive(), 1),
                owned.maxYExclusive() + 1, Math.addExact(owned.maxZExclusive(), 1));
        int cells = Math.multiplyExact(Math.multiplyExact(read.sizeX(), read.sizeY()), read.sizeZ());
        if (cells > MAX_READ_CELLS) throw new IllegalArgumentException("Replacement read-cell budget exceeded");
        cancellationCheck.run();
        int[] values = new int[cells];
        MessageDigest digest = sha256();
        digest.update("dh-voxel-replacement-v1\0".getBytes(StandardCharsets.UTF_8));
        updateLong(digest, editedCube.x()); updateLong(digest, editedCube.y()); updateLong(digest, editedCube.z());
        updateLong(digest, owned.minX()); updateLong(digest, owned.minY()); updateLong(digest, owned.minZ());
        updateLong(digest, owned.maxXExclusive()); updateLong(digest, owned.maxYExclusive()); updateLong(digest, owned.maxZExclusive());
        updateLong(digest, read.minX()); updateLong(digest, read.minY()); updateLong(digest, read.minZ());
        updateLong(digest, read.maxXExclusive()); updateLong(digest, read.maxYExclusive()); updateLong(digest, read.maxZExclusive());
        int index = 0;
        for (long ry = read.minY(); ry < read.maxYExclusive(); ry++) {
            for (long rz = read.minZ(); rz < read.maxZExclusive(); rz++) {
                for (long rx = read.minX(); rx < read.maxXExclusive(); rx++) {
                    cancellationCheck.run();
                    int color = source.argbAt(rx, ry, rz);
                    if (color != KNOWN_AIR && (color >>> 24) != 255) {
                        throw new IllegalArgumentException("Unknown or unsupported replacement voxel at " + rx + "," + ry + "," + rz);
                    }
                    values[index++] = color;
                    updateInt(digest, color);
                }
            }
        }
        String identity = HexFormat.of().formatHex(digest.digest());
        cancellationCheck.run();
        return new Capture(editedCube, owned, read, values, identity);
    }

    /**
     * Pre-counts every exact unit face before allocating the output list or any
     * face objects. The caller passes remaining pooled capacity, never an extra
     * budget. No truncated or partially filled mesh is returned on any failure.
     * Color values pass through unchanged; shading belongs to the pinned policy.
     */
    public static Mesh build(Capture capture, int maxFaces, long maxVertexBytes, Runnable cancellationCheck) {
        Objects.requireNonNull(cancellationCheck, "cancellationCheck").run();
        Objects.requireNonNull(capture, "capture");
        if (maxFaces < 0 || maxFaces > MAX_FACES || maxVertexBytes < 0 || maxVertexBytes > MAX_VERTEX_BYTES) {
            throw new BudgetExceeded("Replacement budget exceeds existing face/byte caps");
        }
        int count = visitFaces(capture, null, cancellationCheck);
        long bytes = Math.multiplyExact((long) count, BYTES_PER_FACE);
        if (count > maxFaces || bytes > maxVertexBytes) {
            throw new BudgetExceeded("Exact replacement exceeds remaining face/byte budget: " + count + " faces, " + bytes + " bytes");
        }
        cancellationCheck.run();
        var faces = new ArrayList<Face>(count);
        if (visitFaces(capture, faces, cancellationCheck) != count) throw new AssertionError("Immutable replacement capture changed");
        cancellationCheck.run();
        return new Mesh(capture.ownedVolume, faces, bytes);
    }

    private static int visitFaces(Capture capture, ArrayList<Face> output, Runnable cancellationCheck) {
        Volume owned = capture.ownedVolume;
        int count = 0;
        for (long y = owned.minY(); y < owned.maxYExclusive(); y++) {
            for (long z = owned.minZ(); z < owned.maxZExclusive(); z++) {
                for (long x = owned.minX(); x < owned.maxXExclusive(); x++) {
                    cancellationCheck.run();
                    int color = capture.argbAt(x, y, z);
                    if (color == KNOWN_AIR) continue;
                    int material = capture.materialAt(x,y,z);
                    if (capture.argbAt(x, y + 1, z) == KNOWN_AIR) { count++; emit(output, Direction.UP, x, y + 1, z, color, material); }
                    if (capture.argbAt(x, y - 1, z) == KNOWN_AIR) { count++; emit(output, Direction.DOWN, x, y, z, color, material); }
                    if (capture.argbAt(x, y, z - 1) == KNOWN_AIR) { count++; emit(output, Direction.NORTH, x, y, z, color, material); }
                    if (capture.argbAt(x, y, z + 1) == KNOWN_AIR) { count++; emit(output, Direction.SOUTH, x, y, z + 1, color, material); }
                    if (capture.argbAt(x - 1, y, z) == KNOWN_AIR) { count++; emit(output, Direction.WEST, x, y, z, color, material); }
                    if (capture.argbAt(x + 1, y, z) == KNOWN_AIR) { count++; emit(output, Direction.EAST, x + 1, y, z, color, material); }
                }
            }
        }
        return count;
    }

    private static void emit(ArrayList<Face> faces, Direction direction, long x, long y, long z, int color, int material) {
        if (faces != null) faces.add(new Face(direction, x, y, z, 1, 1, material==0?color:DhTerrainGeometry.stratifiedFaceArgb(direction,color), material));
    }
    private static void requireReadSpan(long min, long max) {
        final long span;
        try { span = Math.subtractExact(max, min); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Read extent overflows", overflow); }
        if (span < 1 || span > 20) throw new IllegalArgumentException("Read extent must contain 1..20 cells per axis");
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError("Required SHA-256 unavailable", unavailable); }
    }
    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }
    private static void updateInt(MessageDigest digest, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }
}
