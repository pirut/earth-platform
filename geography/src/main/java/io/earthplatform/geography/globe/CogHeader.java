package io.earthplatform.geography.globe;

import io.earthplatform.geography.globe.FloatCogs.Header;
import io.earthplatform.geography.globe.FloatCogs.HeaderTooShort;
import io.earthplatform.geography.globe.FloatCogs.Level;
import io.earthplatform.geography.globe.FloatCogs.UnsupportedFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads the header of a tiled single-band float GeoTIFF for {@link FloatCogs}: its directories (the
 * image and its overviews, classic TIFF or BigTIFF) and its georeferencing, from the parts of the
 * file read so far ({@link Pieces}). A part still missing throws {@link FloatCogs.HeaderTooShort}
 * naming it, so the caller can fetch it and parse again; a file laid out in a way this cannot
 * read throws {@link FloatCogs.UnsupportedFile}.
 */
final class CogHeader {
    private CogHeader() {}

    /**
     * The parts of a file read so far, by where they start. Kept on disk as the bytes from the
     * file's start alone when that is all there is, as a cloud-optimised file needs; otherwise
     * marked and listed piece by piece.
     */
    static final class Pieces {
        private static final long MARK = 0x5049454345530A00L; // "PIECES\n\0"
        private final java.util.TreeMap<Long, byte[]> read = new java.util.TreeMap<>();
        private ByteOrder order = ByteOrder.LITTLE_ENDIAN;

        static Pieces of(byte[] bytes) throws IOException {
            Pieces pieces = new Pieces();
            ByteBuffer in = ByteBuffer.wrap(bytes);
            if (bytes.length < 8 || in.getLong(0) != MARK) {
                pieces.add(0, bytes);
                return pieces;
            }
            in.position(8);
            while (in.hasRemaining()) {
                if (in.remaining() < 12) throw new IOException("Truncated header pieces");
                long offset = in.getLong();
                int length = in.getInt();
                if (length < 0 || length > in.remaining()) throw new IOException("Truncated header pieces");
                byte[] piece = new byte[length];
                in.get(piece);
                pieces.add(offset, piece);
            }
            return pieces;
        }

        void add(long offset, byte[] bytes) {
            byte[] was = read.get(offset);
            if (was == null || was.length < bytes.length) read.put(offset, bytes);
        }

        long size() { return read.values().stream().mapToLong(piece -> piece.length).sum(); }

        byte[] bytes() {
            if (read.size() == 1 && read.firstKey() == 0) return read.firstEntry().getValue();
            ByteBuffer out = ByteBuffer.allocate((int) (8 + 12L * read.size() + size()));
            out.putLong(MARK);
            read.forEach((offset, piece) -> out.putLong(offset).putInt(piece.length).put(piece));
            return out.array();
        }

        /** {@code length} bytes from {@code offset}, in the file's byte order, from its first byte. */
        ByteBuffer at(long offset, long length) throws HeaderTooShort {
            var piece = offset < 0 ? null : read.floorEntry(offset);
            if (piece == null || length > Integer.MAX_VALUE || offset + length > piece.getKey() + piece.getValue().length)
                throw new HeaderTooShort(offset, length);
            return ByteBuffer.wrap(piece.getValue(), (int) (offset - piece.getKey()), (int) length).slice().order(order);
        }

        int u16(long offset) throws HeaderTooShort { return Short.toUnsignedInt(at(offset, 2).getShort(0)); }

        long u32(long offset) throws HeaderTooShort { return Integer.toUnsignedLong(at(offset, 4).getInt(0)); }

        long u64(long offset) throws HeaderTooShort { return at(offset, 8).getLong(0); }
    }

    /**
     * Reads a tiled single-band float TIFF's directories (the image and its overviews) and its
     * georeferencing, classic or BigTIFF.
     */
    static Header parse(byte[] bytes) throws IOException { return parse(Pieces.of(bytes), 0); }

    static Header parse(Pieces in) throws IOException { return parse(in, 0); }

    /** @param skip the finest levels whose block tables are left unread, their offsets and counts null */
    static Header parse(Pieces in, int skip) throws IOException {
        ByteBuffer start = in.at(0, 16);
        boolean bigEndian = start.get(0) == 'M';
        in.order = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        int version = in.u16(2);
        if (version != 42 && version != 43) throw new UnsupportedFile("Not a TIFF");
        boolean big = version == 43;
        if (big && in.u16(4) != 8) throw new UnsupportedFile("Unsupported BigTIFF offset size");
        // Sizes of a directory's entry count and an entry.
        int countSize = big ? 8 : 2, entrySize = big ? 20 : 12;
        long next = big ? in.u64(8) : in.u32(4);
        java.util.List<Level> levels = new java.util.ArrayList<>();
        double x0 = Double.NaN, y0 = Double.NaN, pixelX = Double.NaN, pixelY = Double.NaN;
        int firstPredictor = 0, firstCompression = 0;
        while (next != 0 && levels.size() <= 8) {
            long entries = big ? in.u64(next) : in.u16(next);
            if (entries > 4096) throw new UnsupportedFile("Oversized TIFF directory");
            // The whole directory, and the link to the next, at once.
            in.at(next, countSize + entries * entrySize + (big ? 8 : 4));
            int width = 0, height = 0, tileWidth = 0, tileHeight = 0, compression = 1, bits = 0, samples = 1, predictor = 1, format = 1, subfile = 0;
            long[] offsets = null, counts = null;
            // Masks don't count among the levels, but which directory is a mask is known only once read.
            boolean skipped = levels.size() < skip;
            long tableEntries = -1;
            for (int e = 0; e < entries; e++) {
                long at = next + countSize + (long) e * entrySize;
                int tag = in.u16(at);
                Entry entry = new Entry(in, at, big);
                switch (tag) {
                    case 254 -> subfile = (int) entry.integers()[0];
                    case 256 -> width = (int) entry.integers()[0];
                    case 257 -> height = (int) entry.integers()[0];
                    case 258 -> bits = (int) entry.integers()[0];
                    case 259 -> compression = (int) entry.integers()[0];
                    case 277 -> samples = (int) entry.integers()[0];
                    case 317 -> predictor = (int) entry.integers()[0];
                    case 322 -> tileWidth = (int) entry.integers()[0];
                    case 323 -> tileHeight = (int) entry.integers()[0];
                    case 324 -> { if (skipped) tableEntries = entry.count(); else offsets = entry.integers(); }
                    case 325 -> { if (skipped) tableEntries = tableEntries == entry.count() ? tableEntries : -2; else counts = entry.integers(); }
                    case 339 -> format = (int) entry.integers()[0];
                    case 33550 -> {
                        if (levels.isEmpty()) {
                            double[] scale = entry.doubles();
                            if (scale.length < 2) throw new UnsupportedFile("Unsupported pixel scale");
                            pixelX = scale[0];
                            pixelY = scale[1];
                        }
                    }
                    case 33922 -> {
                        if (levels.isEmpty()) {
                            double[] tie = entry.doubles();
                            if (tie.length < 6 || tie[0] != 0 || tie[1] != 0) throw new UnsupportedFile("Unsupported tie point");
                            x0 = tie[3];
                            y0 = tie[4];
                        }
                    }
                    default -> { }
                }
            }
            long link = next + countSize + entries * entrySize;
            next = big ? in.u64(link) : in.u32(link);
            // Masks are not overviews.
            if ((subfile & 4) != 0) continue;
            if (bits != 32 || samples != 1 || format != 3 || (compression != 1 && compression != 5) || (predictor != 1 && predictor != 3)
                    || (firstPredictor != 0 && predictor != firstPredictor) || (firstCompression != 0 && compression != firstCompression))
                throw new UnsupportedFile("Unsupported COG encoding");
            firstPredictor = predictor;
            firstCompression = compression;
            // Tiles are square and a multiple of 16 pixels, as TIFF requires; overviews may tile smaller than the full image.
            long blocks = (long) Math.ceilDiv(width, Math.max(1, tileWidth)) * Math.ceilDiv(height, Math.max(1, tileWidth));
            if (width <= 0 || height <= 0 || tileWidth != tileHeight || tileWidth < 16 || tileWidth > 1024 || tileWidth % 16 != 0
                    || (skipped ? tableEntries != blocks : offsets == null || counts == null || offsets.length != counts.length || offsets.length != blocks))
                throw new UnsupportedFile("Unsupported COG layout");
            levels.add(new Level(width, height, tileWidth, offsets, counts));
        }
        if (levels.isEmpty() || !(pixelX > 0) || !(pixelY > 0) || !Double.isFinite(x0) || !Double.isFinite(y0)) throw new UnsupportedFile("COG lacks georeferencing");
        return new Header(levels.toArray(Level[]::new), x0, y0, pixelX, pixelY, bigEndian, firstPredictor, firstCompression == 5);
    }

    private record Entry(Pieces in, int type, long count, long start) {
        Entry(Pieces in, long at, boolean big) throws IOException {
            this(in, in.u16(at + 2), big ? in.u64(at + 4) : in.u32(at + 4), at + (big ? 12 : 8), big);
        }

        private Entry(Pieces in, int type, long count, long valueAt, boolean big) throws IOException {
            this(in, type, count, place(in, type, count, valueAt, big));
        }


        private static int size(int type) { return switch (type) { case 3 -> 2; case 4 -> 4; case 12, 16 -> 8; default -> 1; }; }

        private static long place(Pieces in, int type, long count, long valueAt, boolean big) throws IOException {
            if (count > 1 << 20) throw new UnsupportedFile("Oversized TIFF array");
            // Classic entries hold four bytes inline, BigTIFF eight.
            int inline = big ? 8 : 4;
            return count * size(type) <= inline ? valueAt : big ? in.u64(valueAt) : in.u32(valueAt);
        }

        long[] integers() throws IOException {
            ByteBuffer values = in.at(start, count * size(type));
            long[] out = new long[(int) count];
            for (int i = 0; i < count; i++) {
                out[i] = switch (type) {
                    case 3 -> Short.toUnsignedInt(values.getShort(i * 2));
                    case 4 -> Integer.toUnsignedLong(values.getInt(i * 4));
                    case 16 -> values.getLong(i * 8);
                    default -> throw new UnsupportedFile("Unsupported TIFF integer type " + type);
                };
            }
            return out;
        }

        double[] doubles() throws IOException {
            if (type != 12) throw new UnsupportedFile("Unsupported TIFF double type " + type);
            ByteBuffer values = in.at(start, count * 8);
            double[] out = new double[(int) count];
            for (int i = 0; i < count; i++) out[i] = values.getDouble(i * 8);
            return out;
        }
    }
}
