package io.earthplatform.minecraft.enginebridge;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable complete 36-cubed read capture, Y/Z/X order, four two-bit cells per byte. */
public final class ArenaCapture {
    public static final int AIR = 0, STONE = 1, DIORITE = 2;
    public static final int CELL_COUNT = 36 * 36 * 36, PACKED_BYTES = CELL_COUNT / 4;
    private final ArenaDefinition definition;
    private final byte[] packed;
    private final ArenaCapture base;
    private final int overlayIndex, overlayMaterial;
    private final String identity;
    private ArenaCapture(ArenaDefinition definition, byte[] owned) {
        this.definition = Objects.requireNonNull(definition); packed = owned; base = null; overlayIndex = overlayMaterial = -1;
        if (owned.length != PACKED_BYTES) throw new IllegalArgumentException("Exact arena capture length required");
        for (int i = 0; i < CELL_COUNT; i++) if (value(i) == 3) throw new IllegalArgumentException("Incomplete or invalid arena material");
        identity = digest();
    }
    private ArenaCapture(ArenaCapture source, int index, int material) {
        if (source.base != null) throw new IllegalStateException("Only one prospective arena overlay is allowed");
        definition = source.definition; packed = null; base = source; overlayIndex = index; overlayMaterial = material; identity = digest();
    }
    public static Builder builder(ArenaDefinition definition) { return new Builder(definition); }
    /** Decode directly into the sole new owner, without staging plus defensive-copy arrays. */
    public static ArenaCapture read(DataInput input, ArenaDefinition definition) throws IOException {
        byte[] owned = new byte[PACKED_BYTES]; input.readFully(owned);
        try { return new ArenaCapture(definition, owned); } catch (IllegalArgumentException bad) { throw new IOException("Invalid arena capture", bad); }
    }
    /** Wire serialization does not allocate or expose a second palette array. */
    public void write(DataOutput output) throws IOException { for (int i = 0; i < PACKED_BYTES; i++) output.writeByte(packedByte(i)); }
    public ArenaDefinition definition() { return definition; }
    public int cellCount() { return CELL_COUNT; }
    public long declaredBytes() { return PACKED_BYTES; }
    public String contentIdentity() { return identity; }
    public String contentDigest() { return identity; }
    public boolean containsReadCell(long x, long y, long z) { return definition.containsReadCell(x,y,z); }
    public int materialAt(long x, long y, long z) { return value(index(definition,x,y,z)); }
    public int argbAt(long x, long y, long z) { return switch(materialAt(x,y,z)) { case AIR -> 0; case STONE -> 0xff808080; case DIORITE -> 0xffdedede; default -> throw new AssertionError(); }; }
    public ArenaCapture withMaterial(long x, long y, long z, int material) {
        checkMaterial(material); if (!definition.editable(x,y,z)) throw new IllegalArgumentException("Edit must be inside fixed arena");
        int index = index(definition,x,y,z); return value(index) == material ? this : new ArenaCapture(this,index,material);
    }
    /** Exactly old plus new arrays: 23,328 bytes. An overlay is metadata only. */
    public ArenaCapture compact() {
        if (base == null) return this;
        byte[] owned = new byte[PACKED_BYTES]; for (int i=0;i<owned.length;i++) owned[i]=(byte)packedByte(i);
        return new ArenaCapture(definition,owned);
    }
    private int value(int index) { return base == null ? (packed[index >>> 2] >>> ((index & 3)*2)) & 3 : index == overlayIndex ? overlayMaterial : base.value(index); }
    private int packedByte(int index) { if (base == null) return packed[index] & 255; int b=base.packed[index]&255; if ((overlayIndex>>>2)==index) { int shift=(overlayIndex&3)*2; b=(b&~(3<<shift))|(overlayMaterial<<shift); } return b; }
    private String digest() {
        try { MessageDigest d=MessageDigest.getInstance("SHA-256"); d.update("earth-arena-packed-capture-v1\0".getBytes(StandardCharsets.UTF_8)); d.update(definition.identity().getBytes(StandardCharsets.US_ASCII)); for(int i=0;i<PACKED_BYTES;i++)d.update((byte)packedByte(i)); return HexFormat.of().formatHex(d.digest()); }
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static int index(ArenaDefinition d,long x,long y,long z) { if(!d.containsReadCell(x,y,z))throw new IndexOutOfBoundsException("Outside complete arena read halo"); return Math.toIntExact(((y-d.readMinY())*36+z-d.readMinZ())*36+x-d.readMinX()); }
    private static void checkMaterial(int material) { if(material<AIR||material>DIORITE)throw new IllegalArgumentException("Arena supports only AIR/STONE/DIORITE"); }
    /** Initial sentinel 3 proves every cell was written; sealing permanently transfers ownership. */
    public static final class Builder {
        private final ArenaDefinition definition;
        private byte[] owned = new byte[PACKED_BYTES];
        private Builder(ArenaDefinition definition){this.definition=Objects.requireNonNull(definition);Arrays.fill(owned,(byte)0xff);}
        public Builder set(long x,long y,long z,int material){if(owned==null)throw new IllegalStateException("Builder already sealed");checkMaterial(material);int i=index(definition,x,y,z),shift=(i&3)*2;owned[i>>>2]=(byte)((owned[i>>>2]&~(3<<shift))|(material<<shift));return this;}
        public ArenaCapture seal(){if(owned==null)throw new IllegalStateException("Builder already sealed");byte[] transfer=owned;owned=null;return new ArenaCapture(definition,transfer);}
    }
}
