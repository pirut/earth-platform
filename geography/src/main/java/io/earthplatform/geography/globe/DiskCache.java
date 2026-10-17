package io.earthplatform.geography.globe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.function.Consumer;

/**
 * Keeps the downloaded Earth data on disk within a size limit. Every source reads and writes
 * its cache files through here: a file read is marked used (its modification time moved to now,
 * once a session), and once a sixteenth of the limit has been written since the last look, the
 * least recently used files are deleted until the cache fits in 90% of the limit. Files read or
 * written since the game started are never deleted, so the session's own places stay.
 */
public final class DiskCache {
    /** Where the trims are told, such as the game's log. */
    public static volatile Consumer<String> log = System.out::println;
    /** When this game started: files used since are this session's and stay. */
    private static final long SESSION = System.currentTimeMillis();
    private static volatile Path root;
    private static volatile long limit;
    private static final AtomicLong written = new AtomicLong();
    private static final AtomicBoolean trimming = new AtomicBoolean();
    private static volatile boolean warned;

    private DiskCache() {}

    /** Sets the cache folder and its limit in bytes (0 for none), and trims it now, in the background. */
    public static void limit(Path folder, long bytes) {
        root = folder;
        limit = bytes;
        trimLater();
    }

    /** A cache file's bytes, marking it used. */
    static byte[] read(Path cached) throws IOException {
        byte[] bytes = Files.readAllBytes(cached);
        used(cached);
        return bytes;
    }

    /** Marks a cache file used this session, so a trim leaves it. */
    static void used(Path cached) {
        try {
            if (Files.getLastModifiedTime(cached).toMillis() < SESSION) Files.setLastModifiedTime(cached, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException | RuntimeException ignored) {
            // Read-only or gone: it is then only trimmed sooner.
        }
    }

    /** Writes a cache file atomically, as {@link #write(Path, byte[], String)} with temporary files named {@code part*.part}. */
    static void write(Path path, byte[] bytes) throws IOException {
        write(path, bytes, "part");
    }

    /**
     * Writes a cache file atomically: the bytes go to a temporary file beside it, named
     * {@code prefix*.part}, which is then moved over it, so a reader never sees half a file.
     * Counts the bytes toward the next trim and creates the file's folder if needed.
     */
    static void write(Path path, byte[] bytes, String prefix) throws IOException {
        wrote(bytes.length);
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), prefix, ".part");
        try {
            Files.write(temp, bytes);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Counts bytes written to the cache, trimming it once enough have been since the last time. */
    static void wrote(long bytes) {
        long most = limit;
        if (most <= 0 || written.addAndGet(bytes) < most / 16) return;
        written.set(0);
        trimLater();
    }

    private static void trimLater() {
        Path folder = root;
        long most = limit;
        if (folder == null || most <= 0 || !trimming.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try { trim(folder, most, SESSION); }
            finally { trimming.set(false); }
        }, "Earth cache trim");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /**
     * Deletes the least recently used files under {@code folder} until it fits in 90% of
     * {@code limit} bytes, leaving every file used at or after {@code since}.
     */
    static void trim(Path folder, long limit, long since) {
        if (!Files.isDirectory(folder)) return;
        record Entry(Path path, long size, long time) {}
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                BasicFileAttributes attributes;
                try { attributes = Files.readAttributes(path, BasicFileAttributes.class); }
                catch (IOException gone) { continue; }
                if (!attributes.isRegularFile()) continue;
                entries.add(new Entry(path, attributes.size(), attributes.lastModifiedTime().toMillis()));
                total += attributes.size();
            }
        } catch (IOException | RuntimeException failure) {
            log.accept("Could not measure the Earth data cache: " + failure);
            return;
        }
        if (total <= limit) return;
        entries.sort(Comparator.comparingLong(Entry::time));
        long target = limit / 10 * 9, freed = 0;
        int removed = 0;
        for (Entry entry : entries) {
            if (total - freed <= target || entry.time >= since) break;
            try { if (Files.deleteIfExists(entry.path)) { freed += entry.size; removed++; } }
            catch (IOException | RuntimeException ignored) { /* in use; try the next one */ }
        }
        if (removed > 0) log.accept("Trimmed the Earth data cache: removed " + removed + " files, " + (freed >> 20) + " MB; " + ((total - freed) >> 20) + " MB kept");
        if (total - freed > limit && !warned) {
            warned = true;
            log.accept("This session's Earth data alone is " + ((total - freed) >> 20) + " MB, over the cache limit of " + (limit >> 20)
                    + " MB; it is trimmed when the game next starts");
        }
    }
}
