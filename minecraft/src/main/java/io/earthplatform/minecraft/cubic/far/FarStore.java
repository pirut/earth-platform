package io.earthplatform.minecraft.cubic.far;

import io.earthplatform.geography.globe.NamedThreads;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.annotation.Nullable;

/**
 * Far columns as they were made, kept on disk with the world, so a place seen before shows again
 * from a read rather than from the generator: without it every column of the far view (about
 * 17,000 at the default detail) was made again each time the world opened, some 40 s of every
 * core even with all the map data at hand.
 *
 * <p>Only a cache: anything in it can be made again, so a file that can't be read is dropped, never
 * repaired, and the whole store is emptied when the mod's build or the generator changes (the
 * {@code stamp}), since either may change what a column looks like. Columns over ground players
 * changed are taken out as their cubes are saved (see {@link #remove}).
 *
 * <p>Each level's columns lie in files of {@link #REGION} by {@link #REGION} columns, written as a
 * log: a column made is appended, one taken out is appended as an empty record, and the last
 * record of a key counts. A record cut short by a crash fails its length or checksum and ends the
 * file there. A file whose dead records outweigh its live ones is written out afresh. Every write
 * happens on one thread, in the order asked; reads happen on the caller's.
 */
public final class FarStore implements AutoCloseable {
    /** Columns along a side of one file: a level's square around a player lies in four to nine files. */
    static final int REGION = 32;
    private static final int MAGIC = 0x46415231, HEADER = 20, FORMAT = 1;
    /** Longest a file stays open after it was last used. */
    private static final long IDLE_NANOS = 120_000_000_000L;
    /** Dead bytes a file may hold before it is written out afresh, once they also outweigh the live ones. */
    private static final long COMPACT_BYTES = 1 << 20;

    private final Path directory;
    private final long limit;
    private final String stamp;
    private final Consumer<String> log;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(NamedThreads.daemon("Earth far store", Thread.MIN_PRIORITY));
    private final Map<Long, Region> regions = new ConcurrentHashMap<>();
    /** How often each key was taken out this session: a column made from the ground as it was before is not kept. */
    private final Map<Long, Integer> epochs = new ConcurrentHashMap<>();
    /** Keys taken out whose empty record is not written yet, which read as absent meanwhile. */
    private final Map<Long, Integer> removing = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), puts = new AtomicLong();
    /** False until the stamp has been checked, and after a failure to write: nothing is read or kept then. */
    private volatile boolean ready;
    private volatile boolean closed;
    private long lastSweep = System.nanoTime();

    /**
     * @param stamp what the columns depend on beyond the ground: the mod's build and the generator; a store with
     *              another stamp is emptied
     * @param limit most bytes kept, the files used longest ago going first when the world opens; 0 for no limit
     */
    public FarStore(Path directory, String stamp, long limit, Consumer<String> log) {
        this.directory = directory;
        this.limit = limit;
        this.stamp = stamp;
        this.log = log;
        writer.execute(this::open);
    }

    /**
     * Any thread. Empties the store, for when what it holds may no longer match the world and nobody can say where,
     * as after a damaged save file was set aside and its ground made again without what was built on it.
     */
    public void empty() {
        if (closed) return;
        ready = false;
        writer.execute(() -> {
            for (Region region : regions.values()) region.close();
            try { Files.deleteIfExists(directory.resolve("stamp")); }
            catch (IOException failure) { log.accept("Far terrain is not kept on disk this session: " + failure); return; }
            open();
        });
    }

    private void open() {
        try {
            Path file = directory.resolve("stamp");
            String wanted = FORMAT + "|" + stamp, found = null;
            try { found = Files.readString(file, StandardCharsets.UTF_8); }
            catch (NoSuchFileException absent) { /* a new store */ }
            if (!wanted.equals(found)) {
                if (found != null && !closed) log.accept("Far terrain kept on disk was made by another build; it is made again as it is seen");
                clear();
                Files.createDirectories(directory);
                Files.writeString(file, wanted, StandardCharsets.UTF_8);
            } else {
                trim();
            }
            ready = true;
        } catch (IOException | RuntimeException failure) {
            log.accept("Far terrain is not kept on disk this session: " + failure);
        }
    }

    private void clear() throws IOException {
        if (!Files.isDirectory(directory)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                if (!folder.equals(directory)) Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Deletes the files used longest ago until the store fits in nine tenths of its limit. */
    private void trim() throws IOException {
        if (limit <= 0 || !Files.isDirectory(directory)) return;
        record Kept(Path file, long size, FileTime used) {}
        List<Kept> files = new ArrayList<>();
        long[] total = new long[1];
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.getFileName().toString().endsWith(".far")) {
                    files.add(new Kept(file, attributes.size(), attributes.lastModifiedTime()));
                    total[0] += attributes.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (total[0] <= limit) return;
        files.sort(Comparator.comparing(Kept::used));
        long before = total[0];
        for (Kept kept : files) {
            if (total[0] <= limit / 10 * 9) break;
            Files.deleteIfExists(kept.file);
            total[0] -= kept.size;
        }
        log.accept("Far terrain kept on disk trimmed from " + (before >> 20) + " MB to " + (total[0] >> 20) + " MB");
    }

    private static long regionKey(long key) {
        return Far.key(Far.level(key), Math.floorDiv(Far.x(key), REGION), Math.floorDiv(Far.z(key), REGION));
    }

    private Region region(long key) {
        return regions.computeIfAbsent(regionKey(key), region -> new Region(directory.resolve(Integer.toString(Far.level(region)))
                .resolve(Far.x(region) + "." + Far.z(region) + ".far")));
    }

    /** How often a key has been taken out so far; given to {@link #put} with a column made from the ground as it is now. */
    public int epoch(long key) { return epochs.getOrDefault(key, 0); }

    /** Any thread. The column kept for a key, as it was given to {@link #put}, or null. */
    @Nullable public byte[] get(long key) {
        if (!ready || closed || removing.containsKey(key)) return null;
        byte[] found = null;
        try { found = region(key).read(key); }
        catch (IOException | RuntimeException failure) { drop(key, failure); }
        (found == null ? misses : hits).incrementAndGet();
        return found;
    }

    /**
     * Any thread. Keeps a column, unless its key was taken out since {@code epoch} was read: it was then made from
     * ground that has changed since, and the column made after the change is the one to keep.
     */
    public void put(long key, int epoch, byte[] column) {
        if (!ready || closed) return;
        writer.execute(() -> {
            if (!ready || epoch(key) != epoch) return;
            try {
                region(key).append(key, column);
                puts.incrementAndGet();
            } catch (IOException | RuntimeException failure) { drop(key, failure); }
            sweep();
        });
    }

    /** Any thread. Takes a column out, as when the ground under it changed; it reads as absent from now on. */
    public void remove(long key) {
        if (closed) return;
        epochs.merge(key, 1, Integer::sum);
        removing.merge(key, 1, Integer::sum);
        writer.execute(() -> {
            try {
                if (ready) region(key).append(key, null);
            } catch (IOException | RuntimeException failure) {
                drop(key, failure);
            } finally {
                removing.computeIfPresent(key, (k, count) -> count <= 1 ? null : count - 1);
            }
        });
    }

    /** A file that can't be read or written is deleted: all it held can be made again. */
    private void drop(long key, Exception failure) {
        Region region = regions.remove(regionKey(key));
        if (region == null) return;
        log.accept("Far terrain file " + region.file + " could not be used and is dropped: " + failure);
        region.delete();
    }

    /** Writer thread: closes files nobody has used for a while. */
    private void sweep() {
        long now = System.nanoTime();
        if (now - lastSweep < IDLE_NANOS / 4) return;
        lastSweep = now;
        // Closed, not forgotten: a reader may hold one, and two of a file would each keep their own idea of it.
        for (Region region : regions.values()) region.closeIfIdle(now);
    }

    /** Columns read from disk, asked for and not there, and written, since the world opened. */
    public String describe() { return hits + " far columns read from disk, " + misses + " not there, " + puts + " written"; }

    /** Waits for the writes asked for so far; for tests and for closing. */
    void flush() {
        try { writer.submit(() -> {}).get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (java.util.concurrent.ExecutionException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        writer.execute(() -> {
            for (Region region : regions.values()) region.close();
            regions.clear();
        });
        writer.shutdown();
        try { writer.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 4 + 64);
            out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally { deflater.end(); }
    }

    private static byte[] inflate(byte[] packed) throws IOException {
        int size = ByteBuffer.wrap(packed).getInt();
        if (size < 0 || size > 64 << 20) throw new IOException("Far column of " + size + " bytes");
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed, 4, packed.length - 4);
            byte[] bytes = new byte[size];
            int read = 0;
            while (read < size) {
                int n = inflater.inflate(bytes, read, size - read);
                if (n == 0 && (inflater.finished() || inflater.needsInput())) throw new IOException("Far column cut short");
                read += n;
            }
            return bytes;
        } catch (DataFormatException damaged) {
            throw new IOException(damaged);
        } finally { inflater.end(); }
    }

    private static final class Region {
        final Path file;
        @Nullable private FileChannel channel;
        /** Where each live key's record starts, and its packed length. */
        @Nullable private Map<Long, long[]> index;
        private long size, live;
        private long used = System.nanoTime();

        Region(Path file) { this.file = file; }

        /** Opens the file and reads its records' headers; the file ends at the first one that can't be right. */
        private void open(boolean create) throws IOException {
            used = System.nanoTime();
            if (index != null && (channel != null || !create)) return;
            Map<Long, long[]> found = new HashMap<>();
            if (!create && !Files.exists(file)) { index = found; return; }
            Files.createDirectories(file.getParent());
            FileChannel opened = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                long end = opened.size(), at = 0, kept = 0;
                ByteBuffer header = ByteBuffer.allocate(HEADER);
                while (at + HEADER <= end) {
                    header.clear();
                    if (opened.read(header, at) < HEADER) break;
                    header.flip();
                    if (header.getInt() != MAGIC) break;
                    long key = header.getLong();
                    int length = header.getInt();
                    if (length < 0 || at + HEADER + length > end) break;
                    long[] old = length == 0 ? found.remove(key) : found.put(key, new long[]{at, length});
                    if (old != null) kept -= old[1] + HEADER;
                    if (length > 0) kept += length + HEADER;
                    at += HEADER + length;
                }
                if (at < end) opened.truncate(at);
                size = at;
                live = kept;
                // Used now, so the trim at the next opening counts it among the latest.
                try { Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis())); }
                catch (IOException ignored) { /* only the trim's order */ }
            } catch (IOException | RuntimeException failure) {
                opened.close();
                throw failure;
            }
            channel = opened;
            index = found;
        }

        synchronized byte[] read(long key) throws IOException {
            open(false);
            long[] place = index.get(key);
            if (place == null || channel == null) return null;
            ByteBuffer record = ByteBuffer.allocate(HEADER + (int) place[1]);
            while (record.hasRemaining()) if (channel.read(record, place[0] + record.position()) < 0) throw new IOException("Far column past the file's end");
            record.flip();
            record.position(16);
            int crc = record.getInt();
            byte[] packed = new byte[(int) place[1]];
            record.get(packed);
            CRC32 check = new CRC32();
            check.update(packed);
            if ((int) check.getValue() != crc) throw new IOException("Far column fails its checksum");
            return inflate(packed);
        }

        /** Appends a column's record, or an empty one that takes the key out when {@code column} is null. */
        synchronized void append(long key, @Nullable byte[] column) throws IOException {
            open(column != null);
            if (column == null && !index.containsKey(key)) return;
            byte[] packed = column == null ? new byte[0] : deflate(column);
            CRC32 check = new CRC32();
            check.update(packed);
            ByteBuffer record = ByteBuffer.allocate(HEADER + packed.length);
            record.putInt(MAGIC).putLong(key).putInt(packed.length).putInt((int) check.getValue()).put(packed).flip();
            long at = size;
            while (record.hasRemaining()) channel.write(record, at + record.position());
            size += HEADER + packed.length;
            long[] old = column == null ? index.remove(key) : index.put(key, new long[]{at, packed.length});
            if (old != null) live -= old[1] + HEADER;
            if (column != null) live += packed.length + HEADER;
            if (size - live > Math.max(COMPACT_BYTES, live)) compact();
        }

        /** Writes the live records out to a new file that takes this one's place. */
        private void compact() throws IOException {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Map<Long, long[]> moved = new HashMap<>();
            long at = 0;
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (Map.Entry<Long, long[]> entry : index.entrySet()) {
                    int length = HEADER + (int) entry.getValue()[1];
                    ByteBuffer record = ByteBuffer.allocate(length);
                    while (record.hasRemaining()) if (channel.read(record, entry.getValue()[0] + record.position()) < 0) throw new IOException("Far column past the file's end");
                    record.flip();
                    while (record.hasRemaining()) out.write(record, at + record.position());
                    moved.put(entry.getKey(), new long[]{at, entry.getValue()[1]});
                    at += length;
                }
            }
            channel.close();
            channel = null;
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
            index = moved;
            size = live = at;
        }

        synchronized void closeIfIdle(long now) {
            if (now - used >= IDLE_NANOS) close();
        }

        synchronized void close() {
            try { if (channel != null) channel.close(); }
            catch (IOException ignored) { /* nothing unwritten is lost: every append went straight to the file */ }
            channel = null;
            index = null;
        }

        synchronized void delete() {
            close();
            try { Files.deleteIfExists(file); }
            catch (IOException ignored) { /* it is dropped again the next time it fails */ }
        }
    }
}
