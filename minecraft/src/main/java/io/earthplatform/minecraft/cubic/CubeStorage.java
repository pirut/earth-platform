package io.earthplatform.minecraft.cubic;

import io.earthplatform.geography.globe.NamedThreads;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import io.earthplatform.minecraft.EarthPlatformMod;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Edited cubes only, grouped into 8x8x8-cube region files. Untouched cubes are regenerated from
 * the seed, so a world's save size follows what players changed, not what they visited. Asking for
 * a cube in a region with no file costs nothing, and regions read a while ago are let go once
 * saved, so memory stays flat however far players travel.
 *
 * <p>A region file that can't be decoded is set aside as {@code <name>.corrupt-<time>} and its area
 * generated again, so new edits there save normally. One that can't be read at all (permissions, a
 * failing disk) is left exactly as it is and never written for the rest of the session, so fixing it
 * and restarting brings its contents back.
 *
 * <p>Reading a region's file never holds the region against writing: a read that hangs (a failing disk, a
 * network drive) stalls only what needs that region's older cubes. An edit made to a region still being read
 * is kept aside until the read is done, a save does not wait for the read past {@link #loadWaitNanos}, and
 * {@link #close} gives up on the writer after {@link #closeWaitNanos}, leaving such edits beside the region as
 * {@code <name>.unsaved-<time>} instead of stalling shutdown for as long as the disk does.
 */
public final class CubeStorage {
    /** Reads a region file's bytes; the real one reads the disk, a test's may hang. */
    @FunctionalInterface interface Reader { byte[] read(Path file) throws IOException; }

    /** A region file that could not be read: set aside as {@code keptAs}, or left alone and not saved to when that is null. */
    public record Problem(Path file, @Nullable Path keptAs) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final int REGION_SHIFT = 3;
    /** How long a saved region stays in memory after it was last used. */
    private static final long KEEP_NANOS = 60_000_000_000L;
    private final Path directory;
    private final Map<Long, Region> regions = new ConcurrentHashMap<>();
    /** Region keys with saved cubes, from the directory listing plus regions written since. */
    private final Set<Long> known = ConcurrentHashMap.newKeySet();
    private volatile boolean listed;
    /** Compressing and writing a region takes tens of milliseconds, so it happens here, not on the server thread. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(NamedThreads.daemon("Earth cube writer"));
    private final AtomicBoolean writing = new AtomicBoolean();

    private volatile Consumer<Problem> problems = problem -> {};

    /** How long a save waits for a region still being read, before it leaves that region for the next save. */
    private static final long LOAD_WAIT_NANOS = 2_000_000_000L;
    /** How long {@link #close} waits for the last write: longer than a slow disk takes, shorter than a host's patience. */
    private static final long CLOSE_WAIT_NANOS = 30_000_000_000L;
    private final Reader reader;
    private final long loadWaitNanos, closeWaitNanos;
    /** Set by {@link #close}: a region whose read is still going then has its edits written aside. */
    private volatile boolean closing;

    public CubeStorage(Path directory) { this(directory, Files::readAllBytes, LOAD_WAIT_NANOS, CLOSE_WAIT_NANOS); }

    CubeStorage(Path directory, Reader reader, long loadWaitNanos, long closeWaitNanos) {
        this.directory = directory;
        this.reader = reader;
        this.loadWaitNanos = loadWaitNanos;
        this.closeWaitNanos = closeWaitNanos;
    }

    /** Called once per bad region file, on whichever thread first read it. */
    public void onProblem(Consumer<Problem> listener) { problems = listener; }

    /** The folder the region files are in. */
    public Path directory() { return directory; }

    /**
     * Any thread: every cube in the region files as last written, one region at a time, without
     * keeping them; for a one-off pass over a whole save in the background.
     */
    public void forEachSaved(java.util.function.BiConsumer<Long, CompoundTag> visit) throws IOException {
        listRegions();
        for (long region : known) {
            Region open = regions.get(region);
            Map<Long, CompoundTag> cubes = open != null ? open.copy() : decode(fileOf(region));
            // A file that won't decode goes through the region itself, which sets it aside or blocks it.
            if (cubes == null) cubes = region(CubeKey.pack(CubeKey.x(region) << REGION_SHIFT, CubeKey.y(region) << REGION_SHIFT,
                    CubeKey.z(region) << REGION_SHIFT)).copy();
            for (var entry : cubes.entrySet()) visit.accept(entry.getKey(), entry.getValue());
        }
    }

    /** The cubes in a region file, empty when there is none, or null when it can't be read or decoded. */
    @Nullable private static Map<Long, CompoundTag> decode(Path file) {
        try { return parse(Files.readAllBytes(file)); }
        catch (NoSuchFileException absent) { return new HashMap<>(); }
        catch (IOException | RuntimeException failure) { return null; }
    }

    private static Map<Long, CompoundTag> parse(byte[] bytes) throws IOException {
        CompoundTag all = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap()).getCompound("cubes");
        Map<Long, CompoundTag> cubes = new HashMap<>();
        for (String name : all.getAllKeys()) {
            try { cubes.put(Long.parseLong(name), all.getCompound(name)); }
            catch (NumberFormatException ignored) {}
        }
        return cubes;
    }

    private Path fileOf(long region) {
        return directory.resolve("r." + CubeKey.x(region) + "." + CubeKey.y(region) + "." + CubeKey.z(region) + ".nbt");
    }

    private static long regionKey(long cubeKey) {
        return CubeKey.pack(CubeKey.x(cubeKey) >> REGION_SHIFT, CubeKey.y(cubeKey) >> REGION_SHIFT, CubeKey.z(cubeKey) >> REGION_SHIFT);
    }

    private Region region(long cubeKey) {
        return regions.computeIfAbsent(regionKey(cubeKey), key -> new Region(fileOf(key)));
    }

    /** Any thread. */
    @Nullable public CompoundTag read(long cubeKey) throws IOException {
        listRegions();
        if (!known.contains(regionKey(cubeKey))) return null;
        return region(cubeKey).get(cubeKey);
    }

    /** Owner thread: replaces the stored form of one cube; written by the next {@link #flush}. */
    public void put(long cubeKey, CompoundTag tag) throws IOException {
        region(cubeKey).put(cubeKey, tag);
        known.add(regionKey(cubeKey));
    }

    /** Any thread: keys of saved cubes in the cube columns minX..maxX, minZ..maxZ, at any height. */
    public LongList savedIn(int minX, int maxX, int minZ, int maxZ) throws IOException {
        listRegions();
        LongArrayList found = new LongArrayList();
        for (long region : known) {
            int rx = CubeKey.x(region), rz = CubeKey.z(region);
            if (rx < minX >> REGION_SHIFT || rx > maxX >> REGION_SHIFT || rz < minZ >> REGION_SHIFT || rz > maxZ >> REGION_SHIFT) continue;
            int ry = CubeKey.y(region);
            for (long key : region(CubeKey.pack(rx << REGION_SHIFT, ry << REGION_SHIFT, rz << REGION_SHIFT)).keys()) {
                int x = CubeKey.x(key), z = CubeKey.z(key);
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) found.add(key);
            }
        }
        return found;
    }

    /** Any thread: keys of saved cubes at cube Y {@code minY} or above, anywhere; reads only the regions that reach that high. */
    public LongList savedFrom(int minY) throws IOException {
        listRegions();
        LongArrayList found = new LongArrayList();
        for (long region : known) {
            int rx = CubeKey.x(region), ry = CubeKey.y(region), rz = CubeKey.z(region);
            if ((ry << REGION_SHIFT) + (1 << REGION_SHIFT) - 1 < minY) continue;
            for (long key : region(CubeKey.pack(rx << REGION_SHIFT, ry << REGION_SHIFT, rz << REGION_SHIFT)).keys()) {
                if (CubeKey.y(key) >= minY) found.add(key);
            }
        }
        return found;
    }

    private void listRegions() throws IOException {
        if (listed) return;
        synchronized (this) {
            if (listed) return;
            if (Files.isDirectory(directory)) {
                try (Stream<Path> files = Files.list(directory)) {
                    files.forEach(file -> {
                        String[] parts = file.getFileName().toString().split("\\.");
                        if (parts.length != 5 || !parts[0].equals("r") || !parts[4].equals("nbt")) return;
                        try { known.add(CubeKey.pack(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]))); }
                        catch (NumberFormatException ignored) {}
                    });
                }
            }
            listed = true;
        }
    }

    /**
     * Owner thread: writes changed regions on the writer thread, unless a write is still going,
     * then lets go of regions nobody has used for a while that hold nothing unwritten.
     */
    public void flushLater() {
        if (writing.compareAndSet(false, true)) {
            writer.execute(() -> {
                try { writeAll(); }
                catch (IOException | RuntimeException failure) { EarthPlatformMod.LOGGER.error("Could not write cube regions", failure); }
                finally { writing.set(false); }
            });
        }
        letGo();
    }

    /** Owner thread: writes every changed region now, after any write already under way. */
    public void flush() throws IOException {
        flush(Long.MAX_VALUE);
    }

    private void flush(long waitNanos) throws IOException {
        try {
            var write = writer.submit(() -> { writeAll(); return null; });
            if (waitNanos == Long.MAX_VALUE) write.get();
            else write.get(waitNanos, TimeUnit.NANOSECONDS);
        }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        catch (ExecutionException failure) { throw failure.getCause() instanceof IOException io ? io : new IOException(failure.getCause()); }
        catch (TimeoutException stuck) {
            EarthPlatformMod.LOGGER.error("Cube regions were not written within {} s of shutting down (a disk that is not answering?); "
                    + "edits in regions it did not reach may be lost, and those it was still reading are beside them as .unsaved files", TimeUnit.NANOSECONDS.toSeconds(waitNanos));
            writer.shutdownNow();
            return;
        }
        letGo();
    }

    /** Owner thread: the last write, then no more. Waits for the disk no longer than {@code closeWaitNanos}. */
    public void close() throws IOException {
        closing = true;
        try { flush(closeWaitNanos); }
        finally { writer.shutdown(); }
    }

    private void writeAll() throws IOException {
        Files.createDirectories(directory);
        for (Region region : regions.values()) region.flush();
    }

    private void letGo() {
        long now = System.nanoTime();
        regions.values().removeIf(region -> region.idle(now));
    }

    private final class Region {
        private final Path file;
        /** Held while the file is read, and only then: nothing that writes the region waits on it for longer than {@link #loadWaitNanos}. */
        private final ReentrantLock reading = new ReentrantLock();
        /** Null until the file has been read; after that, only touched holding this region's monitor. */
        private volatile Map<Long, CompoundTag> cubes;
        /** Edits made before the file was read, which join the cubes when it is. Under the monitor. */
        private final Map<Long, CompoundTag> early = new HashMap<>();
        private boolean dirty, writing;
        /** The file could not be read and is left alone: edits here live only until the server stops. */
        private volatile boolean blocked;
        private volatile long used = System.nanoTime();

        Region(Path file) { this.file = file; }

        /**
         * The region's cubes, reading the file first if nobody has. The read happens outside the monitor, so a read that
         * hangs holds nothing a save needs. Waits at most {@code waitNanos} for a read another thread has under way
         * ({@link Long#MAX_VALUE}: for as long as it takes); null when that wait ran out.
         */
        @Nullable private Map<Long, CompoundTag> loaded(long waitNanos) {
            used = System.nanoTime();
            Map<Long, CompoundTag> have = cubes;
            if (have != null) return have;
            try {
                if (waitNanos == Long.MAX_VALUE) reading.lock();
                else if (!reading.tryLock(waitNanos, TimeUnit.NANOSECONDS)) return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
            try {
                if (cubes != null) return cubes;
                Map<Long, CompoundTag> read = read();
                synchronized (this) {
                    read.putAll(early);
                    early.clear();
                    cubes = read;
                }
                return read;
            } finally {
                reading.unlock();
            }
        }

        /** The file's cubes, or an empty map when there is no file or it can't be used (see {@link #block}, {@link #setAside}). */
        private Map<Long, CompoundTag> read() {
            byte[] bytes;
            try { bytes = reader.read(file); }
            catch (NoSuchFileException absent) { return new HashMap<>(); }
            catch (IOException failure) { block(failure); return new HashMap<>(); }
            try { return parse(bytes); }
            catch (IOException | RuntimeException failure) { setAside(failure); return new HashMap<>(); }
        }

        /** Keeps the damaged file under a new name and starts the region empty, so its area generates again and saves normally. */
        private void setAside(Exception failure) {
            String stamp = LocalDateTime.now().format(STAMP);
            Path kept = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp);
            for (int n = 2; Files.exists(kept); n++) kept = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp + "-" + n);
            try { Files.move(file, kept); }
            catch (IOException moveFailure) {
                failure.addSuppressed(moveFailure);
                block(failure);
                return;
            }
            EarthPlatformMod.LOGGER.error("Region file {} is damaged; kept it as {} and generating its area again", file, kept, failure);
            problems.accept(new Problem(file, kept));
        }

        private void block(Exception failure) {
            blocked = true;
            EarthPlatformMod.LOGGER.error("Could not read region file {}; leaving it as it is, and edits in its area are not saved until the game restarts",
                    file, failure);
            problems.accept(new Problem(file, null));
        }

        Map<Long, CompoundTag> copy() {
            Map<Long, CompoundTag> all = loaded(Long.MAX_VALUE);
            synchronized (this) { return new HashMap<>(all); }
        }

        CompoundTag get(long key) {
            Map<Long, CompoundTag> all = loaded(Long.MAX_VALUE);
            synchronized (this) { return all.get(key); }
        }

        long[] keys() {
            Map<Long, CompoundTag> all = loaded(Long.MAX_VALUE);
            synchronized (this) {
                long[] keys = new long[all.size()];
                int i = 0;
                for (long key : all.keySet()) keys[i++] = key;
                return keys;
            }
        }

        /**
         * An edit. Reads the file first when no read of it is going (as before); when one is, the edit waits with the
         * others in {@link #early} and joins the cubes when the read ends, so the owner thread never waits on the disk
         * behind another thread's read.
         */
        void put(long key, CompoundTag tag) {
            loaded(0);
            synchronized (this) {
                if (cubes != null) cubes.put(key, tag);
                else early.put(key, tag);
                dirty = true;
            }
        }

        /**
         * Unused for a while with everything written, so dropping it and reading the file again later loses nothing.
         * A blocked region is kept, so a file fixed while the server runs is still not written over; so is one being read.
         */
        synchronized boolean idle(long now) { return !blocked && !dirty && !writing && !reading.isLocked() && now - used > KEEP_NANOS; }

        /**
         * Writer thread: snapshots the cubes under the lock, then compresses and writes without holding it. A region
         * with edits whose file is still being read is left for the next write, or, on the last, has its edits written
         * beside it, since the file's own cubes can't be had to write the region whole.
         */
        void flush() throws IOException {
            synchronized (this) { if (!dirty || blocked) return; }
            if (loaded(loadWaitNanos) == null) {
                if (closing) keepUnmerged();
                return;
            }
            CompoundTag all = new CompoundTag();
            synchronized (this) {
                if (!dirty || blocked) return;
                // Stored cube tags are replaced, never changed, so sharing them with the snapshot is safe.
                for (var entry : cubes.entrySet()) all.put(Long.toString(entry.getKey()), entry.getValue());
                dirty = false;
                writing = true;
            }
            boolean written = false;
            try {
                write(all, file);
                written = true;
            } finally {
                synchronized (this) {
                    writing = false;
                    if (!written) dirty = true;
                }
            }
        }

        /** The edits made before the file was read, as a region file of their own, so shutdown loses none of them. */
        private void keepUnmerged() throws IOException {
            CompoundTag all = new CompoundTag();
            synchronized (this) {
                if (early.isEmpty()) return;
                for (var entry : early.entrySet()) all.put(Long.toString(entry.getKey()), entry.getValue());
            }
            Path aside = file.resolveSibling(file.getFileName() + ".unsaved-" + LocalDateTime.now().format(STAMP));
            write(all, aside);
            EarthPlatformMod.LOGGER.error("Region file {} was still being read when the world closed, so its edits were saved as {} instead; "
                    + "its cubes are in the region file as before", file, aside);
        }

        private void write(CompoundTag all, Path target) throws IOException {
            CompoundTag root = new CompoundTag();
            root.putInt("v", CubeCodec.VERSION);
            root.put("cubes", all);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp);
            // On disk before it takes the old file's place: renamed while still in the system's buffers, a power
            // cut could leave an empty file where the region was, and with it every edit in the region.
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
