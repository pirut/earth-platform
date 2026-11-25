package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

/**
 * Shutdown with a region file that never reads (a named pipe stands in for a hung disk), and a writer that
 * outlives close. Nothing here is a sidecar or a format change: a region that cannot be saved in time is
 * reported and its edits are lost; every other region is saved.
 */
class CubeStorageShutdownTest {
    private static final long EDITED = CubeKey.pack(0, 0, 0);
    private static final long UNCHANGED = CubeKey.pack(1, 0, 0);
    private static final String REGION = "r.0.0.0.nbt";
    private static final long WAIT = TimeUnit.MILLISECONDS.toNanos(1500);

    private static CompoundTag tag(int revision) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("revision", revision);
        return tag;
    }

    private static String regionOf(long cube) {
        return "r." + (CubeKey.x(cube) >> 3) + "." + (CubeKey.y(cube) >> 3) + "." + (CubeKey.z(cube) >> 3) + ".nbt";
    }

    private static void seed(Path directory, long... cubes) throws IOException {
        CubeStorage initial = new CubeStorage(directory);
        for (long cube : cubes) initial.put(cube, tag(cube == EDITED ? 1 : 99));
        initial.close();
    }

    private static int revisionIn(Path file, long cube) throws IOException {
        var cubes = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("cubes");
        assertTrue(cubes.contains(Long.toString(cube)), "no cube " + cube + " in " + file.getFileName());
        return cubes.getCompound(Long.toString(cube)).getInt("revision");
    }

    private static List<String> files(Path directory, String part) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.contains(part)).sorted().toList();
        }
    }

    /** A region file nobody has read, as a named pipe: opening it for reading blocks until something opens it for writing. */
    private static Path pipe(Path directory, String region) throws Exception {
        Path path = directory.resolve(region);
        Process made = new ProcessBuilder("mkfifo", path.toString()).redirectErrorStream(true).start();
        assumeTrue(made.waitFor() == 0, "no mkfifo here");
        return path;
    }

    /** Lets whatever is blocked opening the pipe through, with {@code content} (nothing when null) to read. */
    private static void release(Path pipe, byte[] content) throws Exception {
        try (var out = Files.newOutputStream(pipe)) { if (content != null) out.write(content); }
    }

    private static void waitUntilGone(String threadName) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            boolean alive = Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().equals(threadName));
            if (!alive) return;
            Thread.sleep(25);
        }
    }

    // ---- A cold read that never returns, at the final save ----

    @Test void aColdRegionThatNeverReadsDoesNotHangTheFinalSave(@TempDir Path directory) throws Exception {
        Path pipe = pipe(directory, REGION);
        long healthy = CubeKey.pack(40, 0, 0);
        CubeStorage storage = new CubeStorage(directory, WAIT, file -> {});
        try {
            // Nothing has read the pipe's region before the last save, so the last save would be the first reader.
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                storage.put(EDITED, tag(2));
                storage.put(healthy, tag(5));
                storage.close();
            });
            assertEquals(5, revisionIn(directory.resolve(regionOf(healthy)), healthy));
            assertFalse(Files.isRegularFile(pipe), "the pipe itself is untouched");
            assertEquals(List.of(), files(directory, ".tmp"));
            assertEquals(List.of(), files(directory, ".unsaved"), "no sidecar files");
            List<String> report = storage.unsavedReport();
            assertEquals(1, report.size(), "report " + report);
            assertTrue(report.get(0).contains(REGION) && report.get(0).contains("1 cubes with unsaved edits"), report.get(0));
            assertTrue(report.get(0).contains("reading its file did not return"), report.get(0));
        } finally {
            // Let the stuck reader out: it finds an empty file, and a storage that is closed neither moves it nor writes.
            release(pipe, null);
            waitUntilGone("Earth cube region save " + REGION);
        }
        assertTrue(Files.exists(pipe, LinkOption.NOFOLLOW_LINKS), "a late reader did not set the file aside");
        assertFalse(Files.isRegularFile(pipe));
        assertEquals(List.of(), files(directory, ".corrupt"));
        assertEquals(List.of(), files(directory, ".tmp"));
    }

    @Test void wedgedRegionsDoNotSpendTheHealthyRegionsShutdownBudget(@TempDir Path directory) throws Exception {
        long healthy = CubeKey.pack(0, 120, 0);
        long[] wedged = {CubeKey.pack(0, 0, 0), CubeKey.pack(0, 8, 0), CubeKey.pack(0, 16, 0)};
        CubeStorage initial = new CubeStorage(directory);
        initial.put(healthy, tag(1));
        initial.put(CubeKey.pack(1, 120, 0), tag(99));
        initial.close();
        List<Path> pipes = new ArrayList<>();
        for (long key : wedged) pipes.add(pipe(directory, regionOf(key)));

        CubeStorage storage = new CubeStorage(directory, WAIT, file -> {});
        try {
            assertEquals(1, storage.read(healthy).getInt("revision"));
            // The stalled regions come first in any order close could take them in.
            for (long key : wedged) storage.put(key, tag(2));
            storage.put(healthy, tag(2));
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> storage.close());
            Path file = directory.resolve(regionOf(healthy));
            assertEquals(2, revisionIn(file, healthy));
            assertEquals(99, revisionIn(file, CubeKey.pack(1, 120, 0)), "the region's other cubes are kept");
            assertEquals(3, storage.unsavedReport().size(), "report " + storage.unsavedReport());
        } finally {
            for (Path pipe : pipes) release(pipe, null);
            for (long key : wedged) waitUntilGone("Earth cube region save " + regionOf(key));
        }
        assertEquals(List.of(), files(directory, ".tmp"));
    }

    // ---- Nothing on the owner thread waits for a read ----

    @Test void putDoesNotWaitOnAFileBeingReadAndTheEditJoinsTheFilesCubesWhenTheReadEnds(@TempDir Path directory) throws Exception {
        seed(directory, EDITED, UNCHANGED);
        Path region = directory.resolve(REGION);
        byte[] original = Files.readAllBytes(region);
        Files.delete(region);
        Path pipe = pipe(directory, REGION);
        CubeStorage storage = new CubeStorage(directory, WAIT, file -> {});
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> reading = pool.submit(() -> storage.read(UNCHANGED).getInt("revision"));
            Thread.sleep(300); // the read is inside the pipe's open
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> storage.put(EDITED, tag(3)));
            release(pipe, original);
            assertEquals(99, reading.get(10, TimeUnit.SECONDS));
        }
        assertEquals(3, storage.read(EDITED).getInt("revision"), "the latest edit wins over the file");
        storage.close();
        CubeStorage again = new CubeStorage(directory);
        assertEquals(3, again.read(EDITED).getInt("revision"));
        assertEquals(99, again.read(UNCHANGED).getInt("revision"));
    }

    @Test void flushWaitsAtMostTheDeadlineOnAStuckRegionAndThenDoesNotWaitAgain(@TempDir Path directory) throws Exception {
        Path pipe = pipe(directory, REGION);
        long healthy = CubeKey.pack(40, 0, 0);
        CubeStorage storage = new CubeStorage(directory, TimeUnit.MILLISECONDS.toNanos(600), file -> {});
        try {
            storage.put(EDITED, tag(2));
            storage.put(healthy, tag(5));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> storage.flush());
            long start = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> storage.flush());
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(400), "the second flush does not wait on the stuck writer");
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> storage.close());
            assertEquals(5, revisionIn(directory.resolve(regionOf(healthy)), healthy));
            assertEquals(1, storage.unsavedReport().size());
        } finally {
            release(pipe, null);
            waitUntilGone("Earth cube region save " + REGION);
        }
    }

    @Test void theLatestEditOfEveryCubeSurvivesWritesRacingTheEdits(@TempDir Path directory) throws Exception {
        for (int round = 0; round < 10; round++) {
            Path world = Files.createDirectory(directory.resolve("world-" + round));
            CubeStorage storage = new CubeStorage(world);
            for (int revision = 1; revision <= 300; revision++) {
                // Eight regions, two cubes each; every edit replaces the last of its cube.
                for (int cube = 0; cube < 16; cube++) storage.put(CubeKey.pack((cube / 2) * 8 + cube % 2, 0, 0), tag(revision));
                if (revision % 7 == 0) storage.flushLater();
                if (revision % 50 == 0) storage.flush();
            }
            storage.close();
            CubeStorage reopened = new CubeStorage(world);
            for (int cube = 0; cube < 16; cube++) assertEquals(300, reopened.read(CubeKey.pack((cube / 2) * 8 + cube % 2, 0, 0)).getInt("revision"));
        }
    }

    // ---- A writer that outlives close ----

    @Test void aWriterLeftBehindByCloseNeverCommitsOverAReopenedWorld(@TempDir Path directory) throws Exception {
        Path world = Files.createDirectory(directory.resolve("world"));
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), world);
        seed(world, EDITED, UNCHANGED);
        CountDownLatch inCommit = new CountDownLatch(1), letGo = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CubeStorage old = new CubeStorage(world, TimeUnit.MILLISECONDS.toNanos(500), file -> {
            if (calls.getAndIncrement() != 0) return;
            inCommit.countDown();
            while (true) { try { letGo.await(); break; } catch (InterruptedException ignored) {} }
        });
        assertEquals(1, old.read(EDITED).getInt("revision"));
        old.put(EDITED, tag(2));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> old.close()); // gives up on the writer, inside its last write
            assertTrue(inCommit.await(5, TimeUnit.SECONDS));
            assertEquals(1, files(world, ".tmp").size(), "the stuck write's temp file");

            // The world is opened again, by another path to the same folder, and saved while the old writer is stuck.
            CubeStorage reopened = new CubeStorage(alias);
            assertEquals(1, reopened.read(EDITED).getInt("revision"));
            reopened.put(EDITED, tag(5));
            reopened.close();
            assertEquals(5, revisionIn(world.resolve(REGION), EDITED));
        } finally {
            letGo.countDown();
        }
        // The old writer wakes: its stale snapshot is dropped, and its temp file with it.
        for (int i = 0; i < 200 && !files(world, ".tmp").isEmpty(); i++) Thread.sleep(25);
        assertEquals(List.of(), files(world, ".tmp"));
        assertEquals(5, revisionIn(world.resolve(REGION), EDITED));
        assertEquals(99, revisionIn(world.resolve(REGION), UNCHANGED));
        assertThrows(IOException.class, () -> old.put(EDITED, tag(7)), "a closed storage takes no more edits");
    }

    @Test void twoWritesToOneRegionUseDifferentTempFiles(@TempDir Path directory) throws Exception {
        CountDownLatch bothIn = new CountDownLatch(2);
        List<String> names = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.function.Consumer<Path> hook = file -> {
            try { names.addAll(files(directory, ".tmp")); }
            catch (IOException failure) { throw new AssertionError(failure); }
            bothIn.countDown();
            try { bothIn.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        };
        // Two storages (an old one still finishing, and a new one) writing the same region at once.
        CubeStorage a = new CubeStorage(directory, 5_000_000_000L, hook);
        CubeStorage b = new CubeStorage(directory, 5_000_000_000L, hook);
        a.put(EDITED, tag(1));
        b.put(UNCHANGED, tag(2));
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> fa = pool.submit(() -> { a.close(); return null; });
            Future<?> fb = pool.submit(() -> { b.close(); return null; });
            fa.get(10, TimeUnit.SECONDS);
            fb.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2, names.stream().distinct().count(), "temp files seen: " + names);
        assertEquals(List.of(), files(directory, ".tmp"));
    }
}
