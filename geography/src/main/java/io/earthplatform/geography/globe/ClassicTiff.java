package io.earthplatform.geography.globe;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Reads the values of entries in a classic (32-bit offset) TIFF image file directory, from the
 * file's first bytes held in a buffer in the file's byte order. The land cover, Copernicus and
 * climate sources each walk their own directories, as the tags and layouts they accept differ,
 * and read each entry's value through here.
 *
 * <p>An entry is 12 bytes: tag, type, count, then the value inline when it fits in four bytes or
 * else its offset. A value past the bytes read throws {@link HeaderTooShort}, so the caller can
 * read more of the file and try again.
 */
final class ClassicTiff {
    private ClassicTiff() {}

    /** A part of the header lies past the bytes read so far. */
    static final class HeaderTooShort extends IOException {
        private static final long serialVersionUID = 1L;

        HeaderTooShort() { super("Header extends past the bytes read"); }
    }

    /** The single SHORT (type 3) or LONG value of the entry at {@code at}. */
    static long scalar(ByteBuffer in, int at, int type) {
        return type == 3 ? Short.toUnsignedInt(in.getShort(at + 8)) : Integer.toUnsignedLong(in.getInt(at + 8));
    }

    /** Where the {@code count} values of {@code size} bytes of the entry at {@code at} start, checked against the {@code length} bytes read. */
    private static long start(ByteBuffer in, int length, int at, long count, int size) throws IOException {
        if (count > 1 << 20) throw new IOException("Oversized TIFF array");
        long start = count * size <= 4 ? at + 8 : Integer.toUnsignedLong(in.getInt(at + 8));
        if (start + count * size > length) throw new HeaderTooShort();
        return start;
    }

    /** The SHORT (type 3) or LONG (type 4) values of the entry at {@code at}, such as a block table. */
    static long[] array(ByteBuffer in, int length, int at, int type, long count) throws IOException {
        if (type != 3 && type != 4) throw new IOException("Unsupported TIFF array type " + type);
        int size = type == 3 ? 2 : 4;
        long start = start(in, length, at, count, size);
        long[] values = new long[(int) count];
        for (int i = 0; i < count; i++) {
            int p = (int) (start + (long) i * size);
            values[i] = size == 2 ? Short.toUnsignedInt(in.getShort(p)) : Integer.toUnsignedLong(in.getInt(p));
        }
        return values;
    }

    /** The DOUBLE (type 12) values of the entry at {@code at}, such as a GeoTIFF pixel scale or tie point. */
    static double[] doubles(ByteBuffer in, int length, int at, int type, long count) throws IOException {
        if (type != 12) throw new IOException("Unsupported TIFF double type " + type);
        long start = start(in, length, at, count, 8);
        double[] values = new double[(int) count];
        for (int i = 0; i < count; i++) values[i] = in.getDouble((int) (start + i * 8L));
        return values;
    }
}
