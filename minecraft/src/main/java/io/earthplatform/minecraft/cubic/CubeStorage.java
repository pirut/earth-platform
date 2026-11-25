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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 * <p>Closing is bounded: the server thread never waits on a region's file longer than one overall
 * deadline. Each region that is not simply free to write is saved on a thread of its own, so a region
 * whose file read never returns takes nothing from the others. A region still not saved when the deadline
 * passes is logged, with how many cubes had unsaved edits, and its edits are lost; its file is not touched.
 * Once closed, a storage writes nothing more, whatever a thread left behind finishes later.
 */
public final class CubeStorage {
    /** A region file that could not be read: set aside as {@code keptAs}, or left alone and not saved to when that is null. */
    public record Problem(Path file, @Nullable Path keptAs) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final int REGION_SHIFT = 3;
    /** How long a saved region stays in memory after it was last used. */
    private static final long KEEP_NANOS = 60_000_000_000L;
    /** How long {@link #close} and {@link #flush} wait in all before going on without what has not finished. */
    private static final long CLOSE_WAIT_NANOS = 30_000_000_000L;
    private final Path directory;
    private final long closeWaitNanos;
    private final Consumer<Path> beforeCommit;
    private final Map<Long, Region> regions = new ConcurrentHashMap<>();
    /** Region keys with saved cubes, from the directory listing plus regions written since. */
    private final Set<Long> known = ConcurrentHashMap.newKeySet();
    private volatile boolean listed;
    /** Compressing and writing a region takes tens of milliseconds, so it happens here, not on the server thread. */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(NamedThreads.daemon("Earth cube writer"));
    private final AtomicBoolean writing = new AtomicBoolean();
    /** Closing has begun: nothing more is accepted. */
    private final AtomicBoolean closing = new AtomicBoolean();
    /** Closed for good: no write lands on a file after this. Set under the write lock, so none is half way. */
    private volatile boolean closed;
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
    /** A {@link #flush} that outlived its wait, so the next does not wait on a writer that is stuck. */
    private volatile Future<?> stuck;

    private volatile Consumer<Problem> problems = problem -> {};

    public CubeStorage(Path directory) { this(directory, CLOSE_WAIT_NANOS, file -> {}); }

    /** For tests: a short wait, and a call just before each region file is replaced. */
    CubeStorage(Path directory, long closeWaitNanos, Consumer<Path> beforeCommit) {
        this.directory = directory;
        this.closeWaitNanos = closeWaitNanos;
        this.beforeCommit = beforeCommit;
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
        if (closing.get()) throw new IOException("Cube storage is closed");
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
        if (closing.get()) return;
        if (writing.compareAndSet(false, true)) {
            writer.execute(() -> {
                try { writeAll(); }
                catch (IOException | RuntimeException failure) { EarthPlatformMod.LOGGER.error("Could not write cube regions", failure); }
                finally { writing.set(false); }
            });
        }
        letGo();
    }

    /**
     * Owner thread: writes every changed region now, after any write already under way. Waits at most the
     * close wait: past that the writer is left to finish, and a later flush does not wait on it again.
     */
    public void flush() throws IOException {
        if (closing.get()) return;
        Future<?> earlier = stuck;
        if (earlier != null && !earlier.isDone()) {
            EarthPlatformMod.LOGGER.error("An earlier write of cube regions in {} is still stuck; not waiting on it again", directory);
            letGo();
            return;
        }
        Future<?> job = writer.submit(() -> { writeAll(); return null; });
        try { job.get(closeWaitNanos, TimeUnit.NANOSECONDS); }
        catch (TimeoutException late) {
            stuck = job;
            EarthPlatformMod.LOGGER.error("Writing cube regions in {} did not finish in {} s; going on without it. Closing reports what could not be saved",
                    directory, TimeUnit.NANOSECONDS.toSeconds(closeWaitNanos));
        }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        catch (ExecutionException failure) { throw failure.getCause() instanceof IOException io ? io : new IOException(failure.getCause()); }
        letGo();
    }

    /**
     * Owner thread: the last write, then no more. Takes at most the close wait in all. Regions that could not
     * be saved by then are logged at error level, edits and all, and left; a stuck thread that finishes later
     * writes nothing.
     */
    public void close() throws IOException {
        if (!closing.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + closeWaitNanos;
        Thread saver = new Thread(() -> saveAll(deadline), "Earth cube final save");
        saver.setDaemon(true);
        saver.start();
        boolean interrupted = false;
        try { TimeUnit.NANOSECONDS.timedJoin(saver, Math.max(1, deadline - System.nanoTime())); }
        catch (InterruptedException failure) { interrupted = true; }
        // No write lands after this. One already renaming is waited for a moment; one stuck inside the rename cannot be helped.
        boolean fenced = false;
        try { fenced = gate.writeLock().tryLock(1, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { interrupted = true; }
        closed = true;
        if (fenced) gate.writeLock().unlock();
        writer.shutdown();
        report();
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** The final save thread: regions free to write are written here, one at a time; each other gets a thread of its own. */
    private void saveAll(long deadline) {
        try { Files.createDirectories(directory); }
        catch (IOException failure) { EarthPlatformMod.LOGGER.error("Could not create the cube region folder {}", directory, failure); }
        List<Thread> helpers = new ArrayList<>();
        for (Region region : regions.values()) {
            if (region.saveIfFree()) continue;
            Thread helper = new Thread(() -> region.saveWhenFree(deadline), "Earth cube region save " + region.file.getFileName());
            helper.setDaemon(true);
            helper.start();
            helpers.add(helper);
        }
        for (Thread helper : helpers) {
            try { TimeUnit.NANOSECONDS.timedJoin(helper, deadline - System.nanoTime()); }
            catch (InterruptedException failure) { return; }
        }
    }

    private void report() {
        List<String> lines = unsavedReport();
        if (lines.isEmpty()) return;
        for (String line : lines) EarthPlatformMod.LOGGER.error("{}", line);
        EarthPlatformMod.LOGGER.error("Shutting down without saving {} cube regions: the edits listed above are lost", lines.size());
    }

    /** One line per region whose edits are not on disk, each saying how many cubes and why. */
    List<String> unsavedReport() {
        List<String> lines = new ArrayList<>();
        for (Region region : regions.values()) {
            if (region.owed()) lines.add("Could not save cube region " + region.file + ": " + region.unsavedCubes() + " cubes with unsaved edits; " + region.why());
        }
        return lines;
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
        /** Held for the whole of a read, as a monitor was; nothing on the owner thread waits for it. */
        private final ReentrantLock lock = new ReentrantLock();
        private Map<Long, CompoundTag> cubes;
        /** Edits made while the region was unread or in use: the owner thread never waits to make one. Taken in by the next user of the lock. */
        private final Map<Long, CompoundTag> early = new ConcurrentHashMap<>();
        /** Cubes taken in since the last write started, and cubes in a write that has not ended: for the count in a shutdown report. */
        private final Set<Long> pending = ConcurrentHashMap.newKeySet(), inFlight = ConcurrentHashMap.newKeySet();
        private volatile boolean loaded, dirty, writing;
        /** The file could not be read and is left alone: edits here live only until the server stops. */
        private volatile boolean blocked;
        private volatile Exception failure;
        private long used = System.nanoTime();

        Region(Path file) { this.file = file; }

        /** Lock held. */
        private Map<Long, CompoundTag> load() {
            used = System.nanoTime();
            if (cubes != null) return cubes;
            Map<Long, CompoundTag> read = new HashMap<>();
            cubes = read;
            byte[] bytes;
            try { bytes = Files.readAllBytes(file); }
            catch (NoSuchFileException absent) { loaded = true; return cubes; }
            catch (IOException failure) { block(failure); loaded = true; return cubes; }
            try { cubes = parse(bytes); }
            catch (IOException | RuntimeException failure) { setAside(failure); }
            loaded = true;
            return cubes;
        }

        /** Lock held: the cubes, with the edits made meanwhile laid over them. */
        private Map<Long, CompoundTag> open() {
            Map<Long, CompoundTag> all = load();
            for (var entry : early.entrySet()) {
                long key = entry.getKey();
                CompoundTag tag = entry.getValue();
                all.put(key, tag);
                pending.add(key);
                dirty = true;
                // A newer edit of the same cube stays, and is taken in next time.
                early.remove(key, tag);
            }
            return all;
        }

        /** Keeps the damaged file under a new name and starts the region empty, so its area generates again and saves normally. */
        private void setAside(Exception failure) {
            String stamp = LocalDateTime.now().format(STAMP);
            Path kept = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp);
            gate.readLock().lock();
            try {
                // A reader left behind by a closed storage must not move a file in a world opened since.
                if (closed) return;
                for (int n = 2; Files.exists(kept); n++) kept = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp + "-" + n);
                try { Files.move(file, kept); }
                catch (IOException moveFailure) {
                    failure.addSuppressed(moveFailure);
                    block(failure);
                    return;
                }
            } finally {
                gate.readLock().unlock();
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
            lock.lock();
            try { return new HashMap<>(open()); }
            finally { lock.unlock(); }
        }

        CompoundTag get(long key) {
            lock.lock();
            try { return open().get(key); }
            finally { lock.unlock(); }
        }

        long[] keys() {
            lock.lock();
            try {
                Map<Long, CompoundTag> all = open();
                long[] keys = new long[all.size()];
                int i = 0;
                for (long key : all.keySet()) keys[i++] = key;
                return keys;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Owner thread. A region already read, and free, takes the edit now. One not read yet, or in use, keeps it
         * until its next user: the owner thread never reads a file or waits on one being read.
         */
        void put(long key, CompoundTag tag) {
            early.put(key, tag);
            if (loaded && lock.tryLock()) {
                try { open(); }
                finally { lock.unlock(); }
            }
        }

        /**
         * Unused for a while with everything written, so dropping it and reading the file again later loses nothing.
         * A blocked region is kept, so a file fixed while the server runs is still not written over. One in use is not idle.
         */
        boolean idle(long now) {
            if (!lock.tryLock()) return false;
            try { return !blocked && !dirty && !writing && early.isEmpty() && now - used > KEEP_NANOS; }
            finally { lock.unlock(); }
        }

        /** Edits not on disk, and nothing to say they never will be. */
        boolean owed() { return !blocked && (dirty || writing || !early.isEmpty()); }

        int unsavedCubes() {
            Set<Long> all = new HashSet<>(early.keySet());
            all.addAll(pending);
            all.addAll(inFlight);
            return all.size();
        }

        String why() {
            Exception failed = failure;
            if (failed != null) return "writing it failed: " + failed;
            if (writing) return "a write of it did not finish";
            if (!loaded && lock.isLocked()) return "reading its file did not return";
            if (lock.isLocked()) return "it stayed in use";
            return "it was not reached in time";
        }

        /** Final save, first try: writes the region if nothing stands in its way. False when it has to wait for something. */
        boolean saveIfFree() {
            if (!lock.tryLock()) return false;
            boolean ready;
            try { ready = !owed() || loaded && !writing; }
            finally { lock.unlock(); }
            if (!ready) return false;
            // A write that failed outright is not tried again: it is reported.
            return !attempt() || !owed();
        }

        /** On a thread of its own: waits for the region's lock, reads it if it must, writes it, until it is saved or the deadline. */
        void saveWhenFree(long deadline) {
            while (true) {
                if (!attempt() || !owed() || System.nanoTime() > deadline) return;
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { return; }
            }
        }

        private boolean attempt() {
            try { flush(); return true; }
            catch (IOException | RuntimeException problem) {
                failure = problem;
                return false;
            }
        }

        /** Writer thread: snapshots the cubes under the lock, then compresses and writes without holding it. */
        void flush() throws IOException {
            CompoundTag all = new CompoundTag();
            lock.lock();
            try {
                // One write of a region at a time, so an older snapshot never lands over a newer one.
                if (writing || !dirty && early.isEmpty()) return;
                Map<Long, CompoundTag> open = open();
                if (!dirty || blocked) return;
                // Stored cube tags are replaced, never changed, so sharing them with the snapshot is safe.
                for (var entry : open.entrySet()) all.put(Long.toString(entry.getKey()), entry.getValue());
                dirty = false;
                writing = true;
                inFlight.addAll(pending);
                pending.clear();
            } finally {
                lock.unlock();
            }
            boolean written = false;
            Path temp = file.resolveSibling(file.getFileName() + "." + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp");
            try {
                CompoundTag root = new CompoundTag();
                root.putInt("v", CubeCodec.VERSION);
                root.put("cubes", all);
                NbtIo.writeCompressed(root, temp);
                // On disk before it takes the old file's place: renamed while still in the system's buffers, a power
                // cut could leave an empty file where the region was, and with it every edit in the region.
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                beforeCommit.accept(file);
                written = commit(temp);
            } finally {
                if (!written) {
                    try { Files.deleteIfExists(temp); }
                    catch (IOException ignored) {}
                }
                lock.lock();
                try {
                    writing = false;
                    if (written) { inFlight.clear(); failure = null; }
                    else dirty = true;
                } finally {
                    lock.unlock();
                }
            }
        }

        /** The one place a region file is replaced, and only while the storage is open: a thread left behind by close finds it shut. */
        private boolean commit(Path temp) throws IOException {
            gate.readLock().lock();
            try {
                if (closed) {
                    EarthPlatformMod.LOGGER.error("Dropped a late write of cube region {}: the storage had already closed", file);
                    return false;
                }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } finally {
                gate.readLock().unlock();
            }
        }
    }
}
