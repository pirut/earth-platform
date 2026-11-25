package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What shutdown does when a region's file can't be read, or read in time: every edit ends in the region or in a sidecar the next read merges. */
class CubeStorageShutdownTest {
    private static final long EDITED = CubeKey.pack(0, 0, 0);
    private static final long UNCHANGED = CubeKey.pack(1, 0, 0);
    private static final String REGION = "r.0.0.0.nbt";

    private static CompoundTag tag(int revision) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("revision", revision);
        return tag;
    }

    /** Blocks (not interruptibly, as a read stuck in the kernel is not) until released, then reads normally. */
    private static byte[] readAfter(Path file, CountDownLatch entered, CountDownLatch release) throws IOException {
        entered.countDown();
        while (true) {
            try {
                if (release.await(20, TimeUnit.SECONDS)) break;
                throw new IOException("test did not release reader");
            } catch (InterruptedException ignored) {}
        }
        return Files.readAllBytes(file);
    }

    private static void seed(Path directory) throws IOException {
        CubeStorage initial = new CubeStorage(directory);
        initial.put(EDITED, tag(1));
        initial.put(UNCHANGED, tag(99));
        initial.close();
    }

    private static List<Path> sidecars(Path directory, String region) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().startsWith(region + ".unsaved-")).sorted().toList();
        }
    }

    private static int revisionIn(Path file, long cube) throws IOException {
        var cubes = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("cubes");
        assertTrue(cubes.contains(Long.toString(cube)), "no cube " + cube + " in " + file.getFileName());
        return cubes.getCompound(Long.toString(cube)).getInt("revision");
    }

    private static boolean has(Path file, long cube) throws IOException {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("cubes").contains(Long.toString(cube));
    }

    private static List<String> leftovers(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) { return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".tmp")).toList(); }
    }

    // ---- Blocker 1: a cold region read at the final save ----

    /** A region file nobody has read, as a named pipe: opening it for reading blocks until something opens it for writing. */
    @Test void aColdRegionThatNeverReadsDoesNotHangTheFinalSave(@TempDir Path directory) throws Exception {
        Path region = directory.resolve(REGION);
        Process made = new ProcessBuilder("mkfifo", region.toString()).redirectErrorStream(true).start();
        assumeTrue(made.waitFor() == 0, "no mkfifo here");
        // A healthy neighbour, in another region.
        long healthy = CubeKey.pack(40, 0, 0);
        CubeStorage storage = new CubeStorage(directory, Files::readAllBytes, TimeUnit.MILLISECONDS.toNanos(100), TimeUnit.SECONDS.toNanos(3));
        try {
            // Nothing has read the pipe's region before the last save, so the last save would be the first reader.
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                storage.put(EDITED, tag(2));
                storage.put(healthy, tag(5));
                storage.flush();
                storage.close();
            });
            assertEquals(5, revisionIn(directory.resolve("r.5.0.0.nbt"), healthy));
            List<Path> aside = sidecars(directory, REGION);
            assertEquals(1, aside.size(), "files " + Files.list(directory).toList());
            assertEquals(2, revisionIn(aside.get(0), EDITED));
            assertTrue(Files.isRegularFile(region) == false, "the pipe itself is untouched");
            assertEquals(List.of(), leftovers(directory));
        } finally {
            // Let the reader thread out of its open(): it finds an empty file, sets it aside, and is done.
            try (var ignored = Files.newOutputStream(region)) {}
            for (int i = 0; i < 200 && Files.exists(region, java.nio.file.LinkOption.NOFOLLOW_LINKS); i++) Thread.sleep(25);
            Thread.sleep(200);
        }
    }

    @Test void putNeverReadsAFileOnTheCallingThread(@TempDir Path directory) throws Exception {
        seed(directory);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CubeStorage storage = new CubeStorage(directory, file -> readAfter(file, entered, release),
                TimeUnit.MILLISECONDS.toNanos(50), TimeUnit.SECONDS.toNanos(3));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                storage.put(EDITED, tag(2));
                storage.flushLater();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the writer asks a reader thread for the file");
            // Not the calling thread: the edit does not wait for the disk.
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> storage.put(EDITED, tag(3)));
        } finally {
            release.countDown();
        }
        storage.close();
        assertEquals(3, new CubeStorage(directory).read(EDITED).getInt("revision"));
        assertEquals(99, new CubeStorage(directory).read(UNCHANGED).getInt("revision"));
    }

    @Test void aSaveWaitsForAColdRegionOnlyAMomentAndLeavesItDirty(@TempDir Path directory) throws Exception {
        seed(directory);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CubeStorage storage = new CubeStorage(directory, file -> readAfter(file, entered, release),
                TimeUnit.MILLISECONDS.toNanos(100), TimeUnit.SECONDS.toNanos(5));
        try {
            storage.put(EDITED, tag(2));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> storage.flush());
            assertEquals(List.of(), sidecars(directory, REGION), "an ordinary save sets nothing aside");
            assertEquals(1, revisionIn(directory.resolve(REGION), EDITED));
        } finally {
            release.countDown();
        }
        storage.close();
        assertEquals(2, new CubeStorage(directory).read(EDITED).getInt("revision"));
        assertEquals(99, new CubeStorage(directory).read(UNCHANGED).getInt("revision"));
        assertEquals(List.of(), sidecars(directory, REGION));
    }

    // ---- Blocker 2: the read ends between the deadline and setting the edits aside ----

    @Test void aReadThatEndsAfterTheDeadlineButBeforeTheSidecarLosesNothing(@TempDir Path directory) throws Exception {
        seed(directory);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CubeStorage.Hooks hooks = new CubeStorage.Hooks();
        Future<?>[] reading = new Future<?>[1];
        var pool = Executors.newSingleThreadExecutor();
        // The deadline passed; before close looks at the region again, the read finishes and merges everything.
        hooks.beforeAside = file -> {
            release.countDown();
            try { reading[0].get(5, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        CubeStorage storage = new CubeStorage(directory, file -> readAfter(file, entered, release),
                TimeUnit.MILLISECONDS.toNanos(25), TimeUnit.MILLISECONDS.toNanos(900), hooks);
        try {
            reading[0] = pool.submit(() -> storage.read(EDITED));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            storage.put(EDITED, tag(2));
            storage.close();
        } finally {
            release.countDown();
            pool.shutdown();
        }
        // The cubes were known by then, so the region was written whole: the latest edit and the other cube, and nothing beside it.
        assertEquals(List.of(), sidecars(directory, REGION));
        assertEquals(2, revisionIn(directory.resolve(REGION), EDITED));
        assertEquals(99, revisionIn(directory.resolve(REGION), UNCHANGED));
        assertEquals(List.of(), leftovers(directory));
    }

    // ---- Blocker 3: sidecars on reopening ----

    @Test void reopeningMergesTheSidecarOverTheRegionAndKeepsItsOtherCubes(@TempDir Path directory) throws Exception {
        seed(directory);
        byte[] before = Files.readAllBytes(directory.resolve(REGION));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CubeStorage storage = new CubeStorage(directory, file -> readAfter(file, entered, release),
                TimeUnit.MILLISECONDS.toNanos(25), TimeUnit.SECONDS.toNanos(1));
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<CompoundTag> reader = pool.submit(() -> storage.read(UNCHANGED));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                storage.put(EDITED, tag(2));
                storage.close();
            } finally {
                release.countDown();
            }
            reader.get(5, TimeUnit.SECONDS);
        }
        List<Path> aside = sidecars(directory, REGION);
        assertEquals(1, aside.size());
        assertEquals(2, revisionIn(aside.get(0), EDITED));
        assertFalse(has(aside.get(0), UNCHANGED), "only the edits are in the sidecar");
        assertArrayEquals(before, Files.readAllBytes(directory.resolve(REGION)), "the region is as it was");

        CubeStorage reopened = new CubeStorage(directory);
        assertEquals(2, reopened.read(EDITED).getInt("revision"), "the sidecar's newer cube wins");
        assertEquals(99, reopened.read(UNCHANGED).getInt("revision"), "the region's other cubes stay");
        assertEquals(2, reopened.savedIn(0, 7, 0, 7).size());
        reopened.close();
        assertEquals(List.of(), sidecars(directory, REGION), "merged and written, the sidecar goes");
        assertEquals(2, revisionIn(directory.resolve(REGION), EDITED));
        assertEquals(99, revisionIn(directory.resolve(REGION), UNCHANGED));
        CubeStorage again = new CubeStorage(directory);
        assertEquals(2, again.read(EDITED).getInt("revision"));
        assertEquals(99, again.read(UNCHANGED).getInt("revision"));
    }

    @Test void aSidecarOnlyRegionIsFoundWithoutItsFile(@TempDir Path directory) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CubeStorage storage = new CubeStorage(directory, file -> readAfter(file, entered, release),
                TimeUnit.MILLISECONDS.toNanos(25), TimeUnit.MILLISECONDS.toNanos(600));
        try (var pool = Executors.newSingleThreadExecutor()) {
            // A file that does not exist reads at once; make the read stall by naming one that does, then removing it.
            Files.write(directory.resolve(REGION), new byte[]{1});
            pool.submit(() -> storage.read(UNCHANGED));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                storage.put(EDITED, tag(7));
                storage.close();
            } finally {
                Files.delete(directory.resolve(REGION));
                release.countDown();
            }
        }
        assertEquals(1, sidecars(directory, REGION).size());
        assertFalse(Files.exists(directory.resolve(REGION)));
        CubeStorage reopened = new CubeStorage(directory);
        assertEquals(7, reopened.read(EDITED).getInt("revision"));
        assertEquals(List.of(EDITED), reopened.savedFrom(0).stream().toList());
    }

    @Test void aSidecarAlreadyMergedIsNotLaidOverNewerEditsAfterACrash(@TempDir Path directory) throws Exception {
        seed(directory);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CubeStorage storage = new CubeStorage(directory, file -> readAfter(file, entered, release),
                TimeUnit.MILLISECONDS.toNanos(25), TimeUnit.MILLISECONDS.toNanos(600));
        try (var pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> storage.read(UNCHANGED));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                storage.put(EDITED, tag(2));
                storage.close();
            } finally {
                release.countDown();
            }
        }
        Path sidecar = sidecars(directory, REGION).get(0);
        byte[] kept = Files.readAllBytes(sidecar);

        // The next session takes the sidecar in, and edits the same cube again, all in one write.
        CubeStorage second = new CubeStorage(directory);
        assertEquals(2, second.read(EDITED).getInt("revision"));
        second.put(EDITED, tag(3));
        second.close();
        assertEquals(List.of(), sidecars(directory, REGION));
        // A crash between that write and the delete would have left the sidecar behind.
        Files.write(sidecar, kept);

        CubeStorage third = new CubeStorage(directory);
        assertEquals(3, third.read(EDITED).getInt("revision"), "the sidecar's revision 2 is not newer than the region's 3");
        assertEquals(99, third.read(UNCHANGED).getInt("revision"));
        third.close();
        assertEquals(List.of(), sidecars(directory, REGION), "the one already taken in is deleted");
        assertEquals(3, new CubeStorage(directory).read(EDITED).getInt("revision"));
    }

    @Test void aDamagedSidecarIsKeptUnderAnotherNameAndTheRegionStillLoads(@TempDir Path directory) throws Exception {
        seed(directory);
        Files.write(directory.resolve(REGION + ".unsaved-20260101-000000"), new byte[]{1, 2, 3});
        CubeStorage storage = new CubeStorage(directory);
        List<CubeStorage.Problem> problems = new ArrayList<>();
        storage.onProblem(problems::add);
        assertEquals(1, storage.read(EDITED).getInt("revision"));
        assertEquals(1, problems.size());
        assertNotNull(problems.get(0).keptAs());
        assertTrue(problems.get(0).keptAs().getFileName().toString().contains(".corrupt-"));
    }

    // ---- Should 4: one budget, healthy regions first ----

    @Test void wedgedRegionsDoNotSpendTheHealthyRegionsShutdownBudget(@TempDir Path directory) throws Exception {
        long healthy = CubeKey.pack(0, 120, 0);
        long[] wedged = {CubeKey.pack(0, 0, 0), CubeKey.pack(0, 8, 0), CubeKey.pack(0, 16, 0)};
        CubeStorage initial = new CubeStorage(directory);
        for (long key : wedged) initial.put(key, tag(1));
        initial.put(healthy, tag(1));
        initial.close();

        CountDownLatch entered = new CountDownLatch(wedged.length);
        CountDownLatch release = new CountDownLatch(1);
        CubeStorage storage = new CubeStorage(directory, file -> file.getFileName().toString().equals("r.0.15.0.nbt")
                ? Files.readAllBytes(file) : readAfter(file, entered, release),
                TimeUnit.MILLISECONDS.toNanos(200), TimeUnit.MILLISECONDS.toNanos(900));
        try {
            // The stalled regions come first in any order the writer could take them in: put theirs first, and flush the healthy one last.
            for (long key : wedged) storage.put(key, tag(2));
            storage.put(healthy, tag(2));
            storage.flushLater();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "all three reads began");
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> storage.close());
            assertEquals(2, new CubeStorage(directory).read(healthy).getInt("revision"),
                    "close should flush the healthy region without waiting behind stalled reads");
            for (long key : wedged) {
                String region = "r." + (CubeKey.x(key) >> 3) + "." + (CubeKey.y(key) >> 3) + "." + (CubeKey.z(key) >> 3) + ".nbt";
                List<Path> aside = sidecars(directory, region);
                assertEquals(1, aside.size(), "edit of " + key + " is in a sidecar: " + Files.list(directory).toList());
                assertEquals(2, revisionIn(aside.get(0), key));
            }
        } finally {
            release.countDown();
        }
        CubeStorage reopened = new CubeStorage(directory);
        for (long key : wedged) assertEquals(2, reopened.read(key).getInt("revision"));
    }

    // ---- Should 5: a writer that outlives close ----

    @Test void aWriterLeftBehindByCloseNeitherSharesATempFileNorWritesOverAReopenedWorld(@TempDir Path directory) throws Exception {
        seed(directory);
        CountDownLatch inCommit = new CountDownLatch(1), letGo = new CountDownLatch(1);
        List<Path> temps = new ArrayList<>();
        CubeStorage.Hooks stuck = new CubeStorage.Hooks();
        AtomicInteger first = new AtomicInteger();
        stuck.beforeCommit = target -> {
            if (first.getAndIncrement() != 0) return;
            inCommit.countDown();
            while (true) { try { letGo.await(); break; } catch (InterruptedException ignored) {} }
        };
        CubeStorage old = new CubeStorage(directory, Files::readAllBytes, TimeUnit.MILLISECONDS.toNanos(50), TimeUnit.MILLISECONDS.toNanos(400), stuck);
        assertEquals(1, old.read(EDITED).getInt("revision"));
        old.put(EDITED, tag(2));
        old.close();          // gives up on the writer, which is inside its last write
        assertTrue(inCommit.await(5, TimeUnit.SECONDS));
        try (Stream<Path> files = Files.list(directory)) { files.filter(f -> f.toString().endsWith(".tmp")).forEach(temps::add); }
        assertEquals(1, temps.size(), "the stuck write's temp file");

        // The world is opened again and saved while the old writer is still stuck.
        CubeStorage reopened = new CubeStorage(directory);
        assertEquals(1, reopened.read(EDITED).getInt("revision"));
        reopened.put(EDITED, tag(5));
        reopened.close();
        assertEquals(5, revisionIn(directory.resolve(REGION), EDITED));

        // Now the old writer wakes up. Its stale snapshot must not replace the newer file.
        letGo.countDown();
        for (int i = 0; i < 100 && Files.exists(temps.get(0)); i++) Thread.sleep(50);
        assertFalse(Files.exists(temps.get(0)), "the abandoned write cleans up its temp file");
        assertEquals(5, revisionIn(directory.resolve(REGION), EDITED));
        assertEquals(99, revisionIn(directory.resolve(REGION), UNCHANGED));
        assertEquals(5, new CubeStorage(directory).read(EDITED).getInt("revision"));
    }

    @Test void twoWritesToOneRegionUseDifferentTempFiles(@TempDir Path directory) throws Exception {
        CountDownLatch bothIn = new CountDownLatch(2);
        List<String> names = java.util.Collections.synchronizedList(new ArrayList<>());
        CubeStorage.Hooks hooks = new CubeStorage.Hooks();
        hooks.beforeCommit = target -> {
            try (Stream<Path> files = Files.list(directory)) { files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".tmp")).forEach(names::add); }
            catch (IOException failure) { throw new AssertionError(failure); }
            bothIn.countDown();
            try { bothIn.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        };
        // Two storages (an old one still finishing, and a new one) writing the same region at once.
        CubeStorage a = new CubeStorage(directory, Files::readAllBytes, 50_000_000L, 5_000_000_000L, hooks);
        CubeStorage b = new CubeStorage(directory, Files::readAllBytes, 50_000_000L, 5_000_000_000L, hooks);
        a.put(EDITED, tag(1));
        b.put(UNCHANGED, tag(2));
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> fa = pool.submit(() -> { a.close(); return null; });
            Future<?> fb = pool.submit(() -> { b.close(); return null; });
            fa.get(10, TimeUnit.SECONDS);
            fb.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2, names.stream().distinct().count() , "temp files seen: " + names);
        assertEquals(List.of(), leftovers(directory));
    }

    // ---- Sol's stress and review tests ----

    @Test void oneLoadAndLatestEditWinsAfterSaveTimeout(@TempDir Path directory) throws Exception {
        for (int round = 0; round < 20; round++) {
            Path world = directory.resolve("world-" + round);
            seed(world);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger reads = new AtomicInteger();
            CubeStorage storage = new CubeStorage(world, file -> {
                reads.incrementAndGet();
                return readAfter(file, entered, release);
            }, TimeUnit.MILLISECONDS.toNanos(10), TimeUnit.SECONDS.toNanos(3));
            try (var pool = Executors.newFixedThreadPool(8)) {
                var waiting = new ArrayList<Future<CompoundTag>>();
                try {
                    for (int i = 0; i < 8; i++) waiting.add(pool.submit(() -> storage.read(EDITED)));
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    for (int revision = 2; revision <= 50; revision++) storage.put(EDITED, tag(revision));
                    storage.flush();
                    assertEquals(1, reads.get());
                    assertEquals(1, new CubeStorage(world).read(EDITED).getInt("revision"));
                } finally {
                    release.countDown();
                }
                for (var reader : waiting) assertEquals(50, reader.get(5, TimeUnit.SECONDS).getInt("revision"));
                assertEquals(1, reads.get());
                storage.close();
            }
            CubeStorage reopened = new CubeStorage(world);
            assertEquals(50, reopened.read(EDITED).getInt("revision"));
            assertEquals(99, reopened.read(UNCHANGED).getInt("revision"));
        }
    }
}
