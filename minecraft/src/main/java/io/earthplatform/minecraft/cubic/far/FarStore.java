package io.earthplatform.minecraft.cubic.far;

import io.earthplatform.minecraft.EarthPlatformMod;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.annotation.Nullable;

/**
 * Far columns as the generator made them, before any saved cube or build was folded in, kept on
 * disk in the world's folder so a world opened again, or a place come back to, has its far
 * terrain at once instead of making every column again: tens of thousands of columns, some
 * hundred processor-seconds at Yosemite. The idea is FarPlaneTwo's tile storage (DaPorkchop_,
 * https://github.com/PorkStudios/FarPlaneTwo), written afresh here.
 *
 * <p>Columns go into two files, each kept to half of {@link #MOST_BYTES}: new ones are added to
 * the newer, and once it is full the older is deleted and a new one started, so what is kept is
 * what was made or read most lately. A column read from the older file is written again into the
 * newer. Each file begins with a token naming the build of the mod and the block and biome ids
 * the bytes hold; a file with another token is deleted unread, as a column made by another build
 * may differ. Each column carries a checksum, and a column cut short by a crash is dropped when
 * the file is opened. Saved cubes and builds are folded in by {@link FarServer} every time, so
 * edits never need to reach the files. {@code -Dearthplatform.far.store=false} keeps nothing on
 * disk; {@code -Dearthplatform.far.storeMegabytes} sets the size (2048 by default). Any thread.
 */
public final class FarStore {
    public static final boolean ENABLED = !"false".equals(System.getProperty("earthplatform.far.store"));
    static final long MOST_BYTES = Math.max(16, Long.getLong("earthplatform.far.storeMegabytes", 2048)) << 20;
    private static final int MAGIC = 0x45504653, FORMAT = 1;
    /** A column's bytes as stored: key, length of the compressed bytes, their checksum. */
    private static final int HEADER = 16;
    /** Larger than any column could be; a length past it means the file is damaged from there on. */
    private static final int MOST_RECORD = 64 << 20;

    private final Path directory;
    private final String token;
    private final long half;
    /** Where each column is: the file's generation in the top 16 bits, the record's offset below. */
    private final Long2LongOpenHashMap index = new Long2LongOpenHashMap();
    private File newer, older;
    private boolean closed;
    private final ThreadLocal<Deflater> deflaters = ThreadLocal.withInitial(() -> new Deflater(1));
    private final ThreadLocal<Inflater> inflaters = ThreadLocal.withInitial(Inflater::new);

    /** A far column's parts, as {@link io.earthplatform.minecraft.cubic.net.CubePayloads.FarColumnData} holds them; a section of no bytes is all air. */
    public record Stored(int bottom, List<byte[]> sections, short[] drops) {}

    private static final class File {
        final int generation;
        final Path path;
        final RandomAccessFile data;
        long end;

        File(int generation, Path path, RandomAccessFile data, long end) {
            this.generation = generation;
            this.path = path;
            this.data = data;
            this.end = end;
        }
    }

    private FarStore(Path directory, String token, long most) {
        this.directory = directory;
        this.token = token;
        this.half = Math.max(1 << 20, most / 2);
        index.defaultReturnValue(-1);
    }

    /** Opens the store in {@code directory}, or null when it is turned off or can't be opened (then far columns are only made). */
    @Nullable public static FarStore open(Path directory, String token) { return ENABLED ? open(directory, token, MOST_BYTES) : null; }

    @Nullable static FarStore open(Path directory, String token, long most) {
        FarStore store = new FarStore(directory, token, most);
        try {
            store.load();
            return store;
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Far terrain can't keep its columns on disk in {}; they are made every time", directory, failure);
            store.close();
            return null;
        }
    }

    private void load() throws IOException {
        Files.createDirectories(directory);
        List<Integer> generations = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                String name = path.getFileName().toString();
                if (!name.startsWith("far.") || !name.endsWith(".bin")) continue;
                try { generations.add(Integer.parseInt(name.substring(4, name.length() - 4))); }
                catch (NumberFormatException ignored) {}
            }
        }
        generations.sort(null);
        // Only the two newest are ever kept.
        for (int i = 0; i < generations.size() - 2; i++) Files.deleteIfExists(path(generations.get(i)));
        int count = generations.size();
        if (count >= 2) older = openFile(generations.get(count - 2));
        if (count >= 1) newer = openFile(generations.get(count - 1));
        if (newer == null) newer = createFile(older != null ? older.generation + 1 : 1);
    }

    private Path path(int generation) { return directory.resolve("far." + generation + ".bin"); }

    /** Opens a file and lists its columns, or deletes it and returns null when another build wrote it or its start is damaged. */
    @Nullable private File openFile(int generation) throws IOException {
        Path path = path(generation);
        RandomAccessFile data = new RandomAccessFile(path.toFile(), "rw");
        long start = readToken(data);
        if (start < 0) {
            data.close();
            Files.deleteIfExists(path);
            return null;
        }
        File file = new File(generation, path, data, start);
        long length = data.length(), at = start;
        byte[] header = new byte[HEADER];
        while (at + HEADER <= length) {
            data.seek(at);
            data.readFully(header);
            long key = longAt(header, 0);
            int size = intAt(header, 8);
            if (size <= 0 || size > MOST_RECORD || at + HEADER + size > length) break;
            index.put(key, (long) generation << 48 | at);
            at += HEADER + size;
        }
        // Whatever follows the last whole column was cut short: the next column goes there.
        if (at < length) data.setLength(at);
        file.end = at;
        return file;
    }

    /** Where the columns start, after a token matching this build's; -1 when the token differs. */
    private long readToken(RandomAccessFile data) throws IOException {
        if (data.length() < 12) return -1;
        data.seek(0);
        if (data.readInt() != MAGIC || data.readInt() != FORMAT) return -1;
        int length = data.readInt();
        if (length < 0 || length > 1 << 16 || 12 + length > data.length()) return -1;
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        return token.equals(new String(bytes, StandardCharsets.UTF_8)) ? 12 + length : -1;
    }

    private File createFile(int generation) throws IOException {
        Path path = path(generation);
        RandomAccessFile data = new RandomAccessFile(path.toFile(), "rw");
        data.setLength(0);
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        data.seek(0);
        data.writeInt(MAGIC);
        data.writeInt(FORMAT);
        data.writeInt(bytes.length);
        data.write(bytes);
        return new File(generation, path, data, 12 + bytes.length);
    }

    /** The column stored for a key, or null when none is (or it can't be read). */
    @Nullable public Stored get(long key) {
        byte[] record;
        boolean fromOlder;
        synchronized (this) {
            if (closed) return null;
            long at = index.get(key);
            if (at < 0) return null;
            File file = file((int) (at >>> 48));
            if (file == null) { index.remove(key); return null; }
            fromOlder = file == older;
            try {
                record = read(file, at & 0xFFFFFFFFFFFFL);
            } catch (IOException failure) {
                record = null;
            }
            if (record == null) { index.remove(key); return null; }
            // Kept: what is read is what is near players now.
            if (fromOlder) append(key, record);
        }
        try {
            return decode(record);
        } catch (IOException | DataFormatException | RuntimeException damaged) {
            synchronized (this) { index.remove(key); }
            return null;
        }
    }

    /** Keeps a column as the generator made it. */
    public void put(long key, int bottom, List<byte[]> sections, short[] drops) {
        byte[] record = encode(bottom, sections, drops);
        synchronized (this) {
            if (!closed) append(key, record);
        }
    }

    @Nullable private File file(int generation) {
        if (newer != null && newer.generation == generation) return newer;
        if (older != null && older.generation == generation) return older;
        return null;
    }

    /** The compressed bytes at an offset, whose header must check out; null when it doesn't. */
    @Nullable private static byte[] read(File file, long at) throws IOException {
        byte[] header = new byte[HEADER];
        file.data.seek(at);
        file.data.readFully(header);
        int size = intAt(header, 8);
        if (size <= 0 || size > MOST_RECORD || at + HEADER + size > file.end) return null;
        byte[] record = new byte[HEADER + size];
        System.arraycopy(header, 0, record, 0, HEADER);
        file.data.readFully(record, HEADER, size);
        CRC32 crc = new CRC32();
        crc.update(record, HEADER, size);
        return (int) crc.getValue() == intAt(header, 12) ? record : null;
    }

    /** Writes a record (header included) at the newer file's end, starting a new file first when it is full. */
    private void append(long key, byte[] record) {
        try {
            putLong(record, 0, key);
            if (newer.end + record.length > half) rotate();
            long at = newer.end;
            newer.data.seek(at);
            newer.data.write(record);
            newer.end = at + record.length;
            index.put(key, (long) newer.generation << 48 | at);
        } catch (IOException failure) {
            EarthPlatformMod.LOGGER.warn("Could not keep a far column on disk; far terrain stops keeping them this session", failure);
            close();
        }
    }

    private void rotate() throws IOException {
        if (older != null) {
            int gone = older.generation;
            older.data.close();
            Files.deleteIfExists(older.path);
            for (var it = index.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
                if ((int) (it.next().getLongValue() >>> 48) == gone) it.remove();
            }
        }
        older = newer;
        newer = createFile(older.generation + 1);
    }

    private byte[] encode(int bottom, List<byte[]> sections, short[] drops) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(4096);
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(bottom);
            out.writeInt(sections.size());
            for (byte[] section : sections) {
                out.writeInt(section.length);
                out.write(section);
            }
            for (short drop : drops) out.writeShort(drop);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] plain = raw.toByteArray();
        Deflater deflater = deflaters.get();
        deflater.reset();
        deflater.setInput(plain);
        deflater.finish();
        // The plain length first, so reading knows how much to inflate.
        ByteArrayOutputStream packed = new ByteArrayOutputStream(plain.length / 3 + 64);
        byte[] chunk = new byte[8192];
        for (int i = 0; i < HEADER; i++) packed.write(0);
        packed.write(plain.length >>> 24);
        packed.write(plain.length >>> 16);
        packed.write(plain.length >>> 8);
        packed.write(plain.length);
        while (!deflater.finished()) packed.write(chunk, 0, deflater.deflate(chunk));
        byte[] record = packed.toByteArray();
        int size = record.length - HEADER;
        CRC32 crc = new CRC32();
        crc.update(record, HEADER, size);
        putInt(record, 8, size);
        putInt(record, 12, (int) crc.getValue());
        return record;
    }

    private Stored decode(byte[] record) throws IOException, DataFormatException {
        int plainLength = intAt(record, HEADER);
        if (plainLength < 0 || plainLength > MOST_RECORD) throw new IOException("Damaged far column");
        byte[] plain = new byte[plainLength];
        Inflater inflater = inflaters.get();
        inflater.reset();
        inflater.setInput(record, HEADER + 4, record.length - HEADER - 4);
        int done = 0;
        while (done < plainLength) {
            int got = inflater.inflate(plain, done, plainLength - done);
            if (got == 0 && (inflater.finished() || inflater.needsInput())) break;
            done += got;
        }
        if (done != plainLength) throw new IOException("Damaged far column");
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(plain));
        int bottom = in.readInt(), count = in.readInt();
        if (count < 0 || count > Far.MAX_SECTIONS) throw new IOException("Damaged far column");
        List<byte[]> sections = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int length = in.readInt();
            if (length < 0 || length > plainLength) throw new IOException("Damaged far column");
            byte[] section = new byte[length];
            in.readFully(section);
            sections.add(section);
        }
        short[] drops = new short[256];
        for (int i = 0; i < drops.length; i++) drops[i] = in.readShort();
        return new Stored(bottom, sections, drops);
    }

    /** Columns kept, and bytes on disk. */
    public synchronized String stats() {
        long bytes = (newer == null ? 0 : newer.end) + (older == null ? 0 : older.end);
        return index.size() + " far columns kept on disk, " + (bytes >> 20) + " MB";
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (File file : new File[] {older, newer}) {
            if (file == null) continue;
            try { file.data.close(); } catch (IOException ignored) {}
        }
        index.clear();
    }

    private static long longAt(byte[] bytes, int at) {
        long value = 0;
        for (int i = 0; i < 8; i++) value = value << 8 | bytes[at + i] & 0xFF;
        return value;
    }

    private static int intAt(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8 | bytes[at + 3] & 0xFF;
    }

    private static void putLong(byte[] bytes, int at, long value) {
        for (int i = 7; i >= 0; i--) { bytes[at + i] = (byte) value; value >>>= 8; }
    }

    private static void putInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }
}
