package io.earthplatform.geography.globe;

import io.earthplatform.geography.globe.BareEarth.Field;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static io.earthplatform.geography.globe.BareEarth.GRID;
import static io.earthplatform.geography.globe.BareEarth.NONE;

/**
 * The disk cache of {@link BareEarth}'s worked-out fields: each kept compressed, with the format
 * version and a digest of the tiles and land cover it was worked out from, so a field is never
 * read back over changed data or rules.
 */
final class BareEarthCache {
    private BareEarthCache() {}

    /** Version of the fields' bytes and of the rules they are worked out by; a field kept under another is worked out again. */
    static final int FORMAT = 2;

    /** A 64-bit digest of a window's metres and classes. */
    static long digest(float[] dem, byte[] code) {
        long h = 0x9E3779B97F4A7C15L ^ dem.length;
        for (float m : dem) h = (h ^ Float.floatToRawIntBits(m)) * 0x100000001B3L;
        for (int k = 0; k < code.length; k += 8) {
            long word = 0;
            for (int b = 0; b < 8 && k + b < code.length; b++) word |= (code[k + b] & 0xFFL) << 8 * b;
            h = (h ^ word) * 0x100000001B3L;
        }
        return h ^ h >>> 29;
    }

    /** A field's bytes, compressed. */
    static byte[] write(Field field, long digest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(bytes), 1 << 16))) {
            out.writeInt(FORMAT);
            out.writeLong(digest);
            out.writeBoolean(field.ground() != null);
            if (field.ground() != null) { floats(out, field.ground()); out.write(field.weight()); out.write(field.water()); }
            out.writeBoolean(field.delta() != null);
            if (field.delta() != null) { floats(out, field.delta()); floats(out, field.smooth()); out.write(field.band()); }
        }
        return bytes.toByteArray();
    }

    /** The field kept at a path for a window with this digest, or null where there's none or it's for other data. */
    static Field read(Path path, long digest) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
        DiskCache.used(path);
        try (InputStream file = Files.newInputStream(path); DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(file), 1 << 16))) {
            if (in.readInt() != FORMAT || in.readLong() != digest) return null;
            float[] ground = null, delta = null, smooth = null;
            byte[] weight = null, water = null, band = null;
            if (in.readBoolean()) { ground = floats(in); weight = bytes(in); water = bytes(in); }
            if (in.readBoolean()) { delta = floats(in); smooth = floats(in); band = bytes(in); }
            return ground == null && delta == null ? NONE : new Field(ground, weight, water, delta, smooth, band);
        } catch (IOException | UncheckedIOException corrupt) {
            return null;
        }
    }

    private static void floats(DataOutputStream out, float[] values) throws IOException {
        for (float v : values) out.writeInt(Float.floatToRawIntBits(v));
    }

    private static float[] floats(DataInputStream in) throws IOException {
        float[] values = new float[GRID * GRID];
        for (int k = 0; k < values.length; k++) values[k] = Float.intBitsToFloat(in.readInt());
        return values;
    }

    private static byte[] bytes(DataInputStream in) throws IOException {
        byte[] values = new byte[GRID * GRID];
        in.readFully(values);
        return values;
    }
}
