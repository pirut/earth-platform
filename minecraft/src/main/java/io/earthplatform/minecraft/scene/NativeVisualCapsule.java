package io.earthplatform.minecraft.scene;

import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Pure immutable native-byte corpus. No renderer, model, state, atlas, GL, or owner callback is retained. */
public final class NativeVisualCapsule {
    public static final int STRIDE = 52, VERTICES_PER_QUAD = 4, QUAD_BYTES = 208;
    public static final int VARIANTS = 8, FACES = 6, MASKS = 256;
    public static final int TABLE_BYTES = VARIANTS * FACES * MASKS * QUAD_BYTES;
    public static final int AIR = 0, STONE = 1, DIORITE = 2, ANDESITE = 3, TUFF = 4, SNOW = 5, UNKNOWN = 255;
    /** Direction order matches the pinned renderer: down, up, north, south, west, east. */
    private static final int[][] NORMALS = {{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    public record Identity(long resourceGeneration, String resourceDigest, String modelDigest,
                           String appearanceDigest, String bytesDigest) {
        public Identity {
            if (resourceGeneration < 1) throw new IllegalArgumentException("Invalid resource generation");
            requireDigest(resourceDigest); requireDigest(modelDigest); requireDigest(appearanceDigest); requireDigest(bytesDigest);
        }
    }
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    private final Identity identity;
    private final byte[] faces;

    /** Ownership transfer only. The capture must discard its only reference before publishing this capsule. */
    NativeVisualCapsule(long resourceGeneration, String resourceDigest, String modelDigest,
                        String appearanceDigest, byte[] ownedSealedFaces) {
        if (ownedSealedFaces.length != TABLE_BYTES) throw new IllegalArgumentException("Incomplete native face corpus");
        faces = ownedSealedFaces;
        identity = new Identity(resourceGeneration, resourceDigest, modelDigest, appearanceDigest, sha256(faces, faces.length));
    }
    public Identity identity() { return identity; }
    public long retainedBytes() { return TABLE_BYTES + 1024L; }
    public int variantAt(int palette, int worldX, int worldY, int worldZ) {
        requirePalette(palette);
        if (palette == AIR) throw new IllegalArgumentException("Air has no model variant");
        return palette == STONE ? stoneVariant(worldX, worldY, worldZ) : palette + 2;
    }
    /** Exact legacy RandomSource first nextLong selection, including signed low-int addition. */
    public static int stoneVariant(int x, int y, int z) {
        long seed = blockSeed(x, y, z);
        long state = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
        state = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
        int high = (int) (state >>> 16);
        state = (state * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
        int low = (int) (state >>> 16);
        long value = ((long) high << 32) + low;
        // The admitted graph has exactly four unit weights. MIN_VALUE % 4 is exactly zero.
        return Math.abs((int) value) % 4;
    }
    public static long blockSeed(int x, int y, int z) {
        long value = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        value = value * value * 42317861L + value * 11L;
        return value >> 16;
    }
    static int normal(int face, int axis) { return NORMALS[face][axis]; }
    /** Ring order: two ascending tangent coordinates, skipping their shared zero. */
    static int ring(int face, int bit, int axis) {
        if (face < 0 || face >= FACES || bit < 0 || bit >= 8 || axis < 0 || axis > 2) throw new IllegalArgumentException();
        int normalAxis = face / 2 == 0 ? 1 : face / 2 == 1 ? 2 : 0;
        int tangentA = normalAxis == 0 ? 1 : 0;
        int tangentB = normalAxis == 2 ? 1 : 2;
        int cell = bit < 4 ? bit : bit + 1;
        return axis == normalAxis ? NORMALS[face][axis] : axis == tangentA ? cell % 3 - 1 : cell / 3 - 1;
    }
    void appendFace(byte[] target, int offset, int variant, int face, int mask, int x, int y, int z) {
        if (variant < 0 || variant >= VARIANTS || face < 0 || face >= FACES || (mask & ~255) != 0
                || offset < 0 || offset > target.length - QUAD_BYTES || (x | y | z) < 0 || x > 63 || y > 63 || z > 63)
            throw new IllegalArgumentException("Invalid native face copy");
        System.arraycopy(faces, ((variant * FACES + face) * MASKS + mask) * QUAD_BYTES, target, offset, QUAD_BYTES);
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = offset + vertex * STRIDE;
            translate(target, base, x); translate(target, base + 4, y); translate(target, base + 8, z);
        }
    }
    private static void translate(byte[] bytes, int offset, int translation) {
        int bits;
        if (LITTLE_ENDIAN) bits=(bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16)|(bytes[offset+3]<<24);
        else bits=(bytes[offset]<<24)|((bytes[offset+1]&255)<<16)|((bytes[offset+2]&255)<<8)|(bytes[offset+3]&255);
        bits=Float.floatToRawIntBits(Float.intBitsToFloat(bits)+translation);
        if (LITTLE_ENDIAN) { bytes[offset]=(byte)bits;bytes[offset+1]=(byte)(bits>>>8);bytes[offset+2]=(byte)(bits>>>16);bytes[offset+3]=(byte)(bits>>>24); }
        else { bytes[offset]=(byte)(bits>>>24);bytes[offset+1]=(byte)(bits>>>16);bytes[offset+2]=(byte)(bits>>>8);bytes[offset+3]=(byte)bits; }
    }
    static void requirePalette(int value) {
        if (value < AIR || value > SNOW) throw new IllegalArgumentException("Unsupported or unknown native palette code " + value);
    }
    static void requireDigest(String value) {
        Objects.requireNonNull(value);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected SHA-256 identity");
    }
    static String sha256(byte[] bytes, int count) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(bytes, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
