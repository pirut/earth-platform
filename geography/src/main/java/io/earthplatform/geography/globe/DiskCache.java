package io.earthplatform.geography.globe;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
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

    /** How often a cache file's modified time is refreshed during a long session so LRU order stays current. */
    private static final long TOUCH_INTERVAL_MS = 60_000L;
    /** Grace window keeping files touched in the last minute when trimming an active session back to its limit. */
    private static final long SESSION_GRACE_MS = 60_000L;

    /** Marks a cache file used, refreshing its timestamp periodically during long sessions. */
    static void used(Path cached) {
        try {
            long now = System.currentTimeMillis();
            long modified = Files.getLastModifiedTime(cached).toMillis();
            if (modified < SESSION || now - modified >= TOUCH_INTERVAL_MS)
                Files.setLastModifiedTime(cached, FileTime.fromMillis(now));
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
            moveIntoPlace(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Tries of an atomic move before a plain one, waiting {@link #retryWaitMs} doubled between them. */
    private static final int MOVE_TRIES = 5;
    /** First wait between refused moves, doubled each try. Tests set this to 0. */
    static long retryWaitMs = 5;

    /**
     * Moves a finished temporary file over its cache file. On Windows the move is refused while any
     * other handle has the cache file open: another thread reading it, a second download of the same
     * piece that got there first, or a virus scanner looking at the new file. The refusal is
     * {@link AccessDeniedException} or a sharing violation ({@link FileSystemException} whose reason
     * says the file is in use). Such a lock lasts moments, so the atomic move is tried again a few
     * times, then as a plain replace, then as a copy. If it still fails while the cache file is
     * there, that file is a whole copy of the same bytes (cache files are only ever written whole,
     * this way), so it is kept and this copy dropped.
     */
    static void moveIntoPlace(Path temp, Path path) throws IOException {
        moveIntoPlace(temp, path, DiskCache::placeFile);
    }

    /** @param atomic {@code true} for an atomic replace, {@code false} for a plain one */
    @FunctionalInterface
    interface Placer { void place(Path temp, Path path, boolean atomic) throws IOException; }

    private static void placeFile(Path temp, Path path, boolean atomic) throws IOException {
        if (atomic) Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        else Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    static void moveIntoPlace(Path temp, Path path, Placer placer) throws IOException {
        FileSystemException locked = null;
        for (int tried = 1; tried <= MOVE_TRIES; tried++) {
            try {
                placer.place(temp, path, true);
                return;
            } catch (FileSystemException refused) {
                if (refused instanceof AtomicMoveNotSupportedException) { locked = refused; break; }
                if (!locked(refused)) throw refused;
                locked = refused;
                if (tried == MOVE_TRIES) break;
                if (!waitToRetry(tried, temp, path, refused)) return;
            }
        }
        try {
            placer.place(temp, path, false);
            return;
        } catch (FileSystemException refused) {
            if (!locked(refused) && !(refused instanceof AtomicMoveNotSupportedException)) throw refused;
            locked = refused;
        }
        // An empty file is not a finished write: missing-marker files are empty on purpose and share
        // this path only when the bytes being saved are empty too. A real payload still tries to land.
        if (keptWhole(temp, path)) return;
        try {
            Files.copy(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileSystemException refused) {
            if (keptWhole(temp, path)) return;
            if (locked(refused) || refused instanceof AtomicMoveNotSupportedException) throw refused;
            throw locked != null ? locked : refused;
        }
    }

    /** Whether {@code path} already holds a whole write we can keep instead of {@code temp}. */
    private static boolean keptWhole(Path temp, Path path) throws IOException {
        if (!Files.isRegularFile(path)) return false;
        if (Files.size(path) > 0) return true;
        return Files.isRegularFile(temp) && Files.size(temp) == 0;
    }

    /** A Windows lock or access refusal, as opposed to a missing disk or a full one. */
    static boolean locked(FileSystemException failure) {
        if (failure instanceof AccessDeniedException) return true;
        String reason = failure.getReason();
        if (reason == null) reason = failure.getMessage();
        if (reason == null) return false;
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.contains("being used by another process")
                || lower.contains("access is denied")
                || lower.contains("sharing violation")
                || lower.contains("user-mapped section open");
    }

    /** @return false when the caller should keep the cache file already in place and stop */
    private static boolean waitToRetry(int tried, Path temp, Path path, FileSystemException failure) throws IOException {
        try {
            Thread.sleep(Math.max(0, retryWaitMs) << (tried - 1));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (keptWhole(temp, path)) return false;
            throw failure;
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
            try { trim(folder, most, SESSION, System.currentTimeMillis() - SESSION_GRACE_MS); }
            finally { trimming.set(false); }
        }, "Earth cache trim");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /**
     * Deletes the least recently used files under {@code folder} until it fits in 90% of
     * {@code limit} bytes, evicting entries before {@code since} first and enforcing a hard cap
     * of twice {@code limit} if current-session data alone grows far beyond {@code limit}.
     */
    static void trim(Path folder, long limit, long since) {
        trim(folder, limit, since, since);
    }

    /**
     * Deletes the least recently used files under {@code folder} until it fits in 90% of
     * {@code limit} bytes: pre-session files (before {@code since}) go first, then older
     * current-session files (before {@code keepAfter}) whenever the cache still exceeds
     * {@code limit}, and finally the oldest remaining files if even recent writes exceed twice
     * {@code limit}.
     */
    static void trim(Path folder, long limit, long since, long keepAfter) {
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
        int removed = 0, index = 0;
        // Pass 1: evict pre-session files oldest-first down to 90% of limit.
        while (index < entries.size() && total - freed > target && entries.get(index).time < since) {
            Entry entry = entries.get(index++);
            try { if (Files.deleteIfExists(entry.path)) { freed += entry.size; removed++; } }
            catch (IOException | RuntimeException ignored) { /* in use; try the next one */ }
        }
        // Pass 2: if still over limit, evict older current-session files outside the recent grace window.
        if (total - freed > limit) {
            while (index < entries.size() && total - freed > target && entries.get(index).time < keepAfter) {
                Entry entry = entries.get(index++);
                try { if (Files.deleteIfExists(entry.path)) { freed += entry.size; removed++; } }
                catch (IOException | RuntimeException ignored) { /* in use; try the next one */ }
            }
        }
        // Pass 3: hard ceiling so rapid bursts within the grace window still cannot grow unboundedly.
        long hardLimit = limit <= Long.MAX_VALUE / 2 ? limit * 2 : Long.MAX_VALUE;
        if (total - freed > hardLimit) {
            while (index < entries.size() && total - freed > target) {
                Entry entry = entries.get(index++);
                try { if (Files.deleteIfExists(entry.path)) { freed += entry.size; removed++; } }
                catch (IOException | RuntimeException ignored) { /* in use; try the next one */ }
            }
        }
        if (removed > 0) log.accept("Trimmed the Earth data cache: removed " + removed + " files, " + (freed >> 20) + " MB; " + ((total - freed) >> 20) + " MB kept");
        if (total - freed > limit && !warned) {
            warned = true;
            log.accept("This session's Earth data alone is " + ((total - freed) >> 20) + " MB, over the cache limit of " + (limit >> 20)
                    + " MB; it is trimmed when the game next starts");
        }
    }
}
