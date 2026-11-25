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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import io.earthplatform.minecraft.EarthPlatformMod;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
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
 * <p>Reading a region's file never holds the region against writing, and nothing the server thread does reads a file:
 * an edit to a region not read yet waits in memory and joins the region's cubes when the file has been read, which the
 * writer asks a separate, abandonable reader thread to do. A read that hangs (a failing disk, a network drive) therefore
 * stalls only what needs that region's older cubes. A save waits for such a read no longer than {@link #loadWaitNanos}
 * in all, leaving the region dirty for the next save, and healthy regions are written first.
 *
 * <p>{@link #close} has one deadline ({@link #closeWaitNanos}) for everything. A region whose file could not be read by
 * then has its edits written to {@code <name>.nbt.unsaved-<time>}, a file holding just those cubes, and its own file is
 * not touched. When the region is next read, such files are merged over it (the sidecar's cubes are newer than the file's),
 * the region is written whole, and the sidecars are deleted; the region file lists the sidecars it already took in, so
 * one that survives a crash between the write and the delete is deleted, not applied a second time over newer edits.
 * Either way, every edit is on disk in the region or beside it when {@link #close} returns, unless the disk is not
 * writing either, which is logged.
 */
public final class CubeStorage {
    /** Reads a region file's bytes; the real one reads the disk, a test's may hang. */
    @FunctionalInterface interface Reader { byte[] read(Path file) throws IOException; }

    /** Seams for tests, which force the timings real disks only occasionally produce. */
    static class Hooks {
        /** A write's temporary file is complete and about to take this target's place. */
        Consumer<Path> beforeCommit = temp -> {};
        /** {@link #close} has given up on a region's read and is about to set its edits aside. */
        Consumer<Path> beforeAside = file -> {};
    }

    private interface Action { void run() throws IOException; }
    private enum Need { CLEAN, READY, COLD }

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
    /** One thread per region read under way. Daemon threads, so a read that never returns pins nothing but itself. */
    private final ExecutorService loader = Executors.newCachedThreadPool(NamedThreads.daemon("Earth cube reader"));
    /** Sidecar files by region, from the directory listing. */
    private final Map<Long, List<Path>> sidecars = new ConcurrentHashMap<>();
    private static final AtomicLong TEMPS = new AtomicLong();
    /** Per folder, how many storages have been opened on it: a writer left behind by {@link #close} must not write over a newer one's work. */
    private static final Map<Path, AtomicInteger> OPENED = new ConcurrentHashMap<>();
    private final AtomicInteger folderGeneration;
    private final int generation;
    private final Hooks hooks;

    /** How long a save waits for a region still being read, before it leaves that region for the next save. */
    private static final long LOAD_WAIT_NANOS = 2_000_000_000L;
    /** How long {@link #close} waits for the last write: longer than a slow disk takes, shorter than a host's patience. */
    private static final long CLOSE_WAIT_NANOS = 30_000_000_000L;
    private final Reader reader;
    private final long loadWaitNanos, closeWaitNanos;
    /** Set when {@link #close} gave up on the writer: whatever it was still doing no longer commits over a newer storage's files. */
    private volatile boolean abandoned;

    public CubeStorage(Path directory) { this(directory, Files::readAllBytes, LOAD_WAIT_NANOS, CLOSE_WAIT_NANOS); }

    CubeStorage(Path directory, Reader reader, long loadWaitNanos, long closeWaitNanos) { this(directory, reader, loadWaitNanos, closeWaitNanos, new Hooks()); }

    CubeStorage(Path directory, Reader reader, long loadWaitNanos, long closeWaitNanos, Hooks hooks) {
        this.directory = directory;
        this.reader = reader;
        this.loadWaitNanos = loadWaitNanos;
        this.closeWaitNanos = closeWaitNanos;
        this.hooks = hooks;
        this.folderGeneration = OPENED.computeIfAbsent(directory.toAbsolutePath().normalize(), key -> new AtomicInteger());
        this.generation = folderGeneration.incrementAndGet();
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
            Map<Long, CompoundTag> cubes = open != null ? open.copy() : sidecars.containsKey(region) ? null : decode(fileOf(region));
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

    private static Map<Long, CompoundTag> parse(byte[] bytes) throws IOException { return parsed(bytes).cubes(); }

    /** A file's cubes, and the names of the sidecars already merged into it. */
    private record Parsed(Map<Long, CompoundTag> cubes, Set<String> applied) {}

    private static Parsed parsed(byte[] bytes) throws IOException {
        CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        CompoundTag all = root.getCompound("cubes");
        Map<Long, CompoundTag> cubes = new HashMap<>();
        for (String name : all.getAllKeys()) {
            try { cubes.put(Long.parseLong(name), all.getCompound(name)); }
            catch (NumberFormatException ignored) {}
        }
        Set<String> applied = new TreeSet<>();
        ListTag names = root.getList("applied", Tag.TAG_STRING);
        for (int i = 0; i < names.size(); i++) applied.add(names.getString(i));
        return new Parsed(cubes, applied);
    }

    private Path fileOf(long region) {
        return directory.resolve("r." + CubeKey.x(region) + "." + CubeKey.y(region) + "." + CubeKey.z(region) + ".nbt");
    }

    private static long regionKey(long cubeKey) {
        return CubeKey.pack(CubeKey.x(cubeKey) >> REGION_SHIFT, CubeKey.y(cubeKey) >> REGION_SHIFT, CubeKey.z(cubeKey) >> REGION_SHIFT);
    }

    private Region region(long cubeKey) {
        return regions.computeIfAbsent(regionKey(cubeKey), Region::new);
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
                long staleBefore = System.currentTimeMillis() - 3_600_000L;
                try (Stream<Path> files = Files.list(directory)) {
                    files.forEach(file -> {
                        String name = file.getFileName().toString();
                        String[] parts = name.split("\\.");
                        try {
                            if (parts.length == 5 && parts[0].equals("r") && parts[4].equals("nbt")) {
                                known.add(CubeKey.pack(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                            } else if (parts.length == 6 && parts[0].equals("r") && parts[4].equals("nbt") && parts[5].startsWith("unsaved-")) {
                                long region = CubeKey.pack(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                                known.add(region);
                                sidecars.computeIfAbsent(region, key -> new ArrayList<>()).add(file);
                            } else if (name.startsWith("r.") && name.endsWith(".tmp") && Files.getLastModifiedTime(file).toMillis() < staleBefore) {
                                // Left by a write that never finished, long enough ago that nothing is still writing it.
                                Files.deleteIfExists(file);
                            }
                        }
                        catch (NumberFormatException | IOException ignored) {}
                    });
                }
                for (List<Path> found : sidecars.values()) found.sort(null);
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
                try { writeAll(System.nanoTime() + loadWaitNanos, false); }
                catch (IOException | RuntimeException failure) { EarthPlatformMod.LOGGER.error("Could not write cube regions", failure); }
                finally { writing.set(false); }
            });
        }
        letGo();
    }

    /**
     * Owner thread: writes every changed region now, after any write already under way. Gives up after
     * {@code closeWaitNanos}, and leaves a region whose file is still being read for the next save.
     */
    public void flush() throws IOException {
        try {
            var write = writer.submit(() -> { writeAll(System.nanoTime() + loadWaitNanos, false); return null; });
            write.get(closeWaitNanos, TimeUnit.NANOSECONDS);
        }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        catch (ExecutionException failure) { throw failure.getCause() instanceof IOException io ? io : new IOException(failure.getCause()); }
        catch (TimeoutException stuck) {
            EarthPlatformMod.LOGGER.error("Cube regions were not written within {} s (a disk that is not answering?); the edits stay in memory for the next save",
                    TimeUnit.NANOSECONDS.toSeconds(closeWaitNanos));
            return;
        }
        letGo();
    }

    /**
     * Owner thread: the last write, then no more. One deadline, {@code closeWaitNanos} from now, covers the wait for an
     * earlier write, the reads regions still need, and the writes. A region whose file was not read in two thirds of it has
     * its edits set aside beside it (see the class comment), so the last third is for writing.
     */
    public void close() throws IOException {
        long start = System.nanoTime();
        long readDeadline = start + closeWaitNanos / 3 * 2;
        try {
            var write = writer.submit(() -> { writeAll(readDeadline, true); return null; });
            write.get(Math.max(0, start + closeWaitNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        catch (ExecutionException failure) { throw failure.getCause() instanceof IOException io ? io : new IOException(failure.getCause()); }
        catch (TimeoutException stuck) {
            abandoned = true;
            EarthPlatformMod.LOGGER.error("Cube regions in {} were not written within {} s of shutting down (a disk that is not answering?); "
                    + "edits not yet written may be lost", directory, TimeUnit.NANOSECONDS.toSeconds(closeWaitNanos));
            writer.shutdownNow();
            loader.shutdownNow();
            return;
        }
        finally { writer.shutdown(); }
        loader.shutdown();
    }

    /**
     * Writer thread. Writes the regions whose cubes are known first; then waits, all together until {@code deadline},
     * for the files of the rest to be read (on reader threads, in parallel, so a stalled one takes nothing from the others)
     * and writes each as it is. A region still unread at the deadline is left dirty, or with {@code aside} has its
     * edits set aside. Every region is tried; the first failure is rethrown after.
     */
    private void writeAll(long deadline, boolean aside) throws IOException {
        Files.createDirectories(directory);
        List<Region> ready = new ArrayList<>(), cold = new ArrayList<>();
        for (Region region : regions.values()) {
            switch (region.need()) {
                case READY -> ready.add(region);
                case COLD -> cold.add(region);
                case CLEAN -> {}
            }
        }
        IOException failure = null;
        // Reads first, so they go on while the regions already known are written.
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (Region region : cold) loads.add(region.startLoad());
        for (Region region : ready) failure = attempt(failure, region::flushLoaded);
        List<Path> unresolved = new ArrayList<>();
        for (int i = 0; i < cold.size(); i++) {
            Region region = cold.get(i);
            if (awaitLoad(loads.get(i), deadline - System.nanoTime())) failure = attempt(failure, region::flushLoaded);
            else if (aside) {
                unresolved.add(region.file);
                failure = attempt(failure, region::keepAside);
            }
        }
        if (!unresolved.isEmpty()) {
            EarthPlatformMod.LOGGER.error("{} cube region file(s) could not be read before the world closed and were not rewritten; their edits were saved beside them "
                    + "as .unsaved files and will be merged in the next time the regions are read: {}", unresolved.size(), unresolved);
        }
        if (failure != null) throw failure;
    }

    private static IOException attempt(@Nullable IOException earlier, Action action) {
        try { action.run(); return earlier; }
        catch (IOException failure) {
            if (earlier == null) return failure;
            earlier.addSuppressed(failure);
            return earlier;
        }
    }

    /** Whether the read finished in time, and well. */
    private static boolean awaitLoad(CompletableFuture<Void> load, long nanos) {
        try {
            if (load.isDone()) return !load.isCompletedExceptionally();
            if (nanos <= 0) return false;
            load.get(nanos, TimeUnit.NANOSECONDS);
            return true;
        }
        catch (TimeoutException late) { return false; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (ExecutionException failure) {
            EarthPlatformMod.LOGGER.error("Reading a cube region failed", failure.getCause());
            return false;
        }
    }

    private void letGo() {
        long now = System.nanoTime();
        regions.values().removeIf(region -> region.idle(now));
    }

    private final class Region {
        private final long key;
        private final Path file;
        /** Held while the files are read, and only then: nothing that writes the region waits on it. */
        private final ReentrantLock reading = new ReentrantLock();
        /** Null until the files have been read; after that, only touched holding this region's monitor. */
        private volatile Map<Long, CompoundTag> cubes;
        /** Edits made before the files were read, which join the cubes when they are. Under the monitor. */
        private final Map<Long, CompoundTag> early = new HashMap<>();
        /** Sidecars whose cubes are in {@link #cubes} and which are not deleted yet. Under the monitor. */
        private final Set<String> merged = new TreeSet<>();
        /** The read started for the writer, on a reader thread. Under the monitor. */
        @Nullable private CompletableFuture<Void> load;
        private boolean dirty, writing;
        /** The file could not be read and is left alone: edits here live only until the server stops. */
        private volatile boolean blocked;
        private volatile long used = System.nanoTime();

        Region(long key) {
            this.key = key;
            this.file = fileOf(key);
        }

        /** The region's cubes, reading the files first if nobody has, for as long as that takes. Only for callers that want the cubes and may wait. */
        private Map<Long, CompoundTag> loaded() {
            used = System.nanoTime();
            Map<Long, CompoundTag> have = cubes;
            return have != null ? have : loadHere();
        }

        private Map<Long, CompoundTag> loadHere() {
            reading.lock();
            try {
                if (cubes != null) return cubes;
                Read read = readAll();
                synchronized (this) {
                    read.cubes.putAll(early);
                    early.clear();
                    merged.addAll(read.sidecars);
                    // Cubes taken from sidecars are written into the region file, which is what lets the sidecars go.
                    if (!read.sidecars.isEmpty()) dirty = true;
                    cubes = read.cubes;
                }
                return read.cubes;
            } finally {
                reading.unlock();
            }
        }

        /** Writer thread: has the region's files read on a reader thread, if that is not under way or done. */
        synchronized CompletableFuture<Void> startLoad() {
            if (cubes != null) return CompletableFuture.completedFuture(null);
            if (load == null) load = CompletableFuture.runAsync(this::loadHere, loader);
            return load;
        }

        private record Read(Map<Long, CompoundTag> cubes, List<String> sidecars) {}

        /**
         * The file's cubes with its sidecars' laid over them, oldest first. A file that can't be used (see {@link #block},
         * {@link #setAside}) reads as empty; so does a sidecar, but one that can't be read at all blocks the region, since
         * writing the region without it, and reading it later, would lay its older cubes over newer ones.
         */
        private Read readAll() {
            Map<Long, CompoundTag> all = new HashMap<>();
            Set<String> applied = Set.of();
            byte[] bytes = null;
            try { bytes = reader.read(file); }
            catch (NoSuchFileException absent) {}
            catch (IOException failure) { block(failure); return new Read(all, List.of()); }
            if (bytes != null) {
                try {
                    Parsed parsed = parsed(bytes);
                    all = parsed.cubes();
                    applied = parsed.applied();
                } catch (IOException | RuntimeException failure) {
                    if (!setAside(file, failure)) return new Read(all, List.of());
                }
            }
            List<Path> found;
            try {
                listRegions();
                found = sidecars.getOrDefault(key, List.of());
            } catch (IOException failure) { block(failure); return new Read(all, List.of()); }
            List<String> taken = new ArrayList<>();
            for (Path sidecar : found) {
                String name = sidecar.getFileName().toString();
                if (applied.contains(name)) {
                    // Merged into this file by a write that ended before it could be deleted.
                    try { Files.deleteIfExists(sidecar); }
                    catch (IOException failure) { EarthPlatformMod.LOGGER.warn("Could not delete {}", sidecar, failure); }
                    continue;
                }
                byte[] side;
                try { side = reader.read(sidecar); }
                catch (NoSuchFileException gone) { continue; }
                catch (IOException failure) { block(failure); return new Read(all, List.of()); }
                try {
                    all.putAll(parse(side));
                    taken.add(name);
                } catch (IOException | RuntimeException failure) {
                    if (!setAside(sidecar, failure)) return new Read(all, List.of());
                }
            }
            return new Read(all, taken);
        }

        /** Keeps the damaged file under a new name, so its area generates again and saves normally. False when it could not be moved, and the region is blocked instead. */
        private boolean setAside(Path damaged, Exception failure) {
            String stamp = LocalDateTime.now().format(STAMP);
            Path kept = damaged.resolveSibling(damaged.getFileName() + ".corrupt-" + stamp);
            for (int n = 2; Files.exists(kept); n++) kept = damaged.resolveSibling(damaged.getFileName() + ".corrupt-" + stamp + "-" + n);
            try { Files.move(damaged, kept); }
            catch (IOException moveFailure) {
                failure.addSuppressed(moveFailure);
                block(failure);
                return false;
            }
            EarthPlatformMod.LOGGER.error("Region file {} is damaged; kept it as {} and generating its area again", damaged, kept, failure);
            problems.accept(new Problem(damaged, kept));
            return true;
        }

        private void block(Exception failure) {
            blocked = true;
            EarthPlatformMod.LOGGER.error("Could not read region file {}; leaving it as it is, and edits in its area are not saved until the game restarts",
                    file, failure);
            problems.accept(new Problem(file, null));
        }

        Map<Long, CompoundTag> copy() {
            Map<Long, CompoundTag> all = loaded();
            synchronized (this) { return new HashMap<>(all); }
        }

        CompoundTag get(long cubeKey) {
            Map<Long, CompoundTag> all = loaded();
            synchronized (this) { return all.get(cubeKey); }
        }

        long[] keys() {
            Map<Long, CompoundTag> all = loaded();
            synchronized (this) {
                long[] keys = new long[all.size()];
                int i = 0;
                for (long cubeKey : all.keySet()) keys[i++] = cubeKey;
                return keys;
            }
        }

        /**
         * An edit. Never touches the disk: with the files not read yet it waits in {@link #early} and joins the cubes
         * when they are, so the owner thread never waits on the disk, behind another thread's read or its own.
         */
        void put(long cubeKey, CompoundTag tag) {
            used = System.nanoTime();
            synchronized (this) {
                if (cubes != null) cubes.put(cubeKey, tag);
                else early.put(cubeKey, tag);
                dirty = true;
            }
        }

        /**
         * Unused for a while with everything written, so dropping it and reading the file again later loses nothing.
         * A blocked region is kept, so a file fixed while the server runs is still not written over; so is one being read.
         */
        synchronized boolean idle(long now) {
            return !blocked && !dirty && !writing && !reading.isLocked() && (load == null || load.isDone()) && now - used > KEEP_NANOS;
        }

        /** Writer thread. */
        synchronized Need need() {
            if (!dirty || blocked) return Need.CLEAN;
            return cubes != null ? Need.READY : Need.COLD;
        }

        /**
         * Writer thread: when the cubes are known, snapshots them under the monitor, then compresses and writes without
         * holding it, and deletes the sidecars it took in once the file is on disk.
         */
        void flushLoaded() throws IOException {
            CompoundTag all = new CompoundTag();
            List<String> taken;
            synchronized (this) {
                if (!dirty || blocked || cubes == null) return;
                // Stored cube tags are replaced, never changed, so sharing them with the snapshot is safe.
                for (var entry : cubes.entrySet()) all.put(Long.toString(entry.getKey()), entry.getValue());
                taken = List.copyOf(merged);
                dirty = false;
                writing = true;
            }
            boolean written = false;
            try {
                write(all, taken, file);
                written = true;
            } finally {
                synchronized (this) {
                    writing = false;
                    if (!written) dirty = true;
                }
            }
            for (String name : taken) {
                try {
                    Files.deleteIfExists(file.resolveSibling(name));
                    synchronized (this) { merged.remove(name); }
                } catch (IOException failure) {
                    // Still listed in the file as taken in, so the next read deletes it rather than applying it again.
                    EarthPlatformMod.LOGGER.warn("Could not delete {}, merged into {}", name, file, failure);
                }
            }
        }

        /**
         * Writer thread, the last write, for a region whose files were not read in time: its edits made so far, as a file of
         * their own, so shutdown loses none of them. Decided under the monitor, so a read that ends at this moment either
         * has its cubes here, and the region is written whole, or has not merged the edits yet, and they are all in {@link #early}.
         */
        void keepAside() throws IOException {
            hooks.beforeAside.accept(file);
            CompoundTag all = new CompoundTag();
            synchronized (this) {
                if (!dirty || blocked) return;
                if (cubes == null) for (var entry : early.entrySet()) all.put(Long.toString(entry.getKey()), entry.getValue());
            }
            if (all.isEmpty()) {
                flushLoaded();
                return;
            }
            String stamp = LocalDateTime.now().format(STAMP);
            Path aside = file.resolveSibling(file.getFileName() + ".unsaved-" + stamp);
            for (int n = 2; Files.exists(aside); n++) aside = file.resolveSibling(file.getFileName() + ".unsaved-" + stamp + "-" + n);
            write(all, List.of(), aside);
            EarthPlatformMod.LOGGER.error("Region file {} could not be read before the world closed, so its {} edited cube(s) were saved as {} instead; "
                    + "the region file is as it was, and they merge into it when it is next read", file, all.size(), aside);
        }

        private void write(CompoundTag all, Collection<String> taken, Path target) throws IOException {
            CompoundTag root = new CompoundTag();
            root.putInt("v", CubeCodec.VERSION);
            root.put("cubes", all);
            if (!taken.isEmpty()) {
                ListTag names = new ListTag();
                for (String name : taken) names.add(StringTag.valueOf(name));
                root.put("applied", names);
            }
            // Its own name each time: a writer that outlived close() must not share this file with one of a world opened since.
            Path temp = target.resolveSibling(target.getFileName() + "." + Long.toHexString(System.nanoTime()) + "-" + TEMPS.incrementAndGet() + ".tmp");
            try {
                NbtIo.writeCompressed(root, temp);
                // On disk before it takes the old file's place: renamed while still in the system's buffers, a power
                // cut could leave an empty file where the region was, and with it every edit in the region.
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                hooks.beforeCommit.accept(target);
                if (abandoned && folderGeneration.get() != generation) throw new IOException("the world was opened again after this storage gave up; not writing " + target + " over it");
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException failure) {
                try { Files.deleteIfExists(temp); }
                catch (IOException ignored) {}
                throw failure;
            }
        }
    }
}
