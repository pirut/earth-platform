package io.earthplatform.minecraft.cubic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CubeStorageTest {
    @Test void findsOnlyCubesHighEnoughAfterAReopen(@TempDir Path directory) throws Exception {
        CubeStorage storage = new CubeStorage(directory);
        long ground = CubeKey.pack(3, 10, -4), justBelow = CubeKey.pack(-70, 561, 12), sky = CubeKey.pack(-70, 562, 12), high = CubeKey.pack(9000, 700, -9000);
        for (long key : new long[]{ground, justBelow, sky, high}) storage.put(key, new CompoundTag());
        storage.close();

        var found = new CubeStorage(directory).savedFrom(562);
        assertEquals(2, found.size(), "found " + found);
        assertTrue(found.contains(sky) && found.contains(high));
    }

    private static final long CUBE = CubeKey.pack(3, 10, -4), OTHER = CubeKey.pack(40, 10, -4);

    private static CompoundTag marked(int value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("mark", value);
        return tag;
    }

    private static List<String> names(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) { return files.map(file -> file.getFileName().toString()).sorted().toList(); }
    }

    @Test void setsADamagedRegionAsideAndSavesNewEditsThere(@TempDir Path directory) throws Exception {
        CubeStorage first = new CubeStorage(directory);
        first.put(CUBE, marked(1));
        first.put(OTHER, marked(2));
        first.close();
        List<String> regions = names(directory);
        assertEquals(2, regions.size(), "regions " + regions);
        for (String name : regions) Files.write(directory.resolve(name), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        byte[] damaged = Files.readAllBytes(directory.resolve(regions.get(0)));

        CubeStorage reopened = new CubeStorage(directory);
        List<CubeStorage.Problem> problems = new ArrayList<>();
        reopened.onProblem(problems::add);
        assertNull(reopened.read(CUBE), "a damaged region reads as empty, so its area generates again");
        assertNull(reopened.read(OTHER));
        assertNull(reopened.read(CUBE), "reported once");
        assertEquals(2, problems.size(), "problems " + problems);
        for (CubeStorage.Problem problem : problems) {
            assertNotNull(problem.keptAs());
            assertTrue(problem.keptAs().getFileName().toString().startsWith(problem.file().getFileName() + ".corrupt-"));
        }
        // Both damaged files survive, untouched, under their new names.
        assertEquals(4 - 2, names(directory).stream().filter(name -> name.contains(".corrupt-")).count());
        assertArrayEquals(damaged, Files.readAllBytes(problems.stream().filter(p -> p.file().getFileName().toString().equals(regions.get(0)))
                .findFirst().orElseThrow().keptAs()));

        reopened.put(CUBE, marked(3));
        reopened.close();
        CubeStorage again = new CubeStorage(directory);
        again.onProblem(problem -> fail("no problem expected after the new edit saved: " + problem));
        assertEquals(3, again.read(CUBE).getInt("mark"), "an edit after setting aside saves normally");
        assertEquals(4 - 2, names(directory).stream().filter(name -> name.contains(".corrupt-")).count());
    }

    @Test void keepsTwoCopiesOfOneFileDamagedTwiceInAMinute(@TempDir Path directory) throws Exception {
        for (int round = 0; round < 2; round++) {
            CubeStorage storage = new CubeStorage(directory);
            storage.onProblem(problem -> {});
            storage.read(CUBE);
            storage.put(CUBE, marked(round));
            storage.close();
            for (String name : names(directory)) if (!name.contains(".corrupt-")) Files.write(directory.resolve(name), new byte[]{9, 9, 9});
        }
        new CubeStorage(directory).read(CUBE);
        assertEquals(2, names(directory).stream().filter(name -> name.contains(".corrupt-")).count(), "files " + names(directory));
    }

    @Test void leavesAnUnreadableRegionAloneUntilRestart(@TempDir Path directory) throws Exception {
        CubeStorage first = new CubeStorage(directory);
        first.put(CUBE, marked(1));
        first.close();
        Path region = directory.resolve(names(directory).get(0));
        byte[] original = Files.readAllBytes(region);
        // Tests run as any user, so stand in for a file without read permission with one that can't be read as a file.
        Path aside = directory.resolveSibling(directory.getFileName() + "-held");
        Files.move(region, aside);
        Files.createDirectory(region);

        CubeStorage blocked = new CubeStorage(directory);
        List<CubeStorage.Problem> problems = new ArrayList<>();
        blocked.onProblem(problems::add);
        assertNull(blocked.read(CUBE));
        assertEquals(1, problems.size());
        assertNull(problems.get(0).keptAs(), "an unreadable file is not renamed");
        blocked.put(CUBE, marked(2));
        // Fixed while the game runs: still never written over.
        Files.delete(region);
        Files.move(aside, region);
        blocked.flush();
        blocked.close();
        assertArrayEquals(original, Files.readAllBytes(region));
        assertEquals(1, names(directory).size(), "files " + names(directory));

        assertEquals(1, new CubeStorage(directory).read(CUBE).getInt("mark"), "after a restart the original contents load");
    }

    /** A reader that hangs on one region file until released, as a stalled disk or network drive does, and reads the rest normally. */
    private static final class Wedge implements CubeStorage.Reader {
        final String stuckFile;
        final CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        Wedge(String stuckFile) { this.stuckFile = stuckFile; }
        @Override public byte[] read(Path file) throws java.io.IOException {
            if (file.getFileName().toString().equals(stuckFile)) {
                inside.countDown();
                // Not interruptible, as a read stuck in the kernel is not.
                while (true) {
                    try { release.await(); break; } catch (InterruptedException ignored) {}
                }
            }
            return Files.readAllBytes(file);
        }
    }

    /** CUBE's region (0, 1, -1) also holds this; OTHER is in another. */
    private static final long SAME_REGION = CubeKey.pack(4, 10, -4);

    private static String regionOf(long cube) {
        return "r." + (CubeKey.x(cube) >> 3) + "." + (CubeKey.y(cube) >> 3) + "." + (CubeKey.z(cube) >> 3) + ".nbt";
    }

    @Test void shutdownDoesNotWaitForARegionReadThatNeverReturns(@TempDir Path directory) throws Exception {
        CubeStorage first = new CubeStorage(directory);
        first.put(CUBE, marked(1));
        first.put(OTHER, marked(2));
        first.close();
        byte[] original = Files.readAllBytes(directory.resolve(regionOf(CUBE)));

        Wedge wedge = new Wedge(regionOf(CUBE));
        CubeStorage storage = new CubeStorage(directory, wedge, 100_000_000L, 3_000_000_000L);
        Thread reader = new Thread(() -> { try { storage.read(CUBE); } catch (Exception ignored) {} }, "wedged read");
        reader.setDaemon(true);
        reader.start();
        assertTrue(wedge.inside.await(10, TimeUnit.SECONDS), "the read never began");
        try {
            // The owner thread's edits and the shutdown neither wait on the read.
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                storage.put(SAME_REGION, marked(3));
                storage.put(OTHER, marked(4));
                storage.flushLater();
                storage.close();
            });
            // The region that could be written was; the wedged one's file is as it was, and its edit is beside it.
            assertEquals(4, new CubeStorage(directory).read(OTHER).getInt("mark"));
            assertArrayEquals(original, Files.readAllBytes(directory.resolve(regionOf(CUBE))));
            List<String> aside = names(directory).stream().filter(name -> name.contains(".unsaved-")).toList();
            assertEquals(1, aside.size(), "files " + names(directory));
            assertTrue(aside.get(0).startsWith(regionOf(CUBE) + ".unsaved-"));
        } finally {
            wedge.release.countDown();
            reader.join(10_000);
        }
    }

    @Test void anEditMadeWhileTheRegionIsBeingReadJoinsItsCubesWhenTheReadEnds(@TempDir Path directory) throws Exception {
        CubeStorage first = new CubeStorage(directory);
        first.put(CUBE, marked(1));
        first.close();

        Wedge wedge = new Wedge(regionOf(CUBE));
        CubeStorage storage = new CubeStorage(directory, wedge, 100_000_000L, 3_000_000_000L);
        Thread reader = new Thread(() -> { try { storage.read(CUBE); } catch (Exception ignored) {} }, "slow read");
        reader.setDaemon(true);
        reader.start();
        assertTrue(wedge.inside.await(10, TimeUnit.SECONDS));
        storage.put(SAME_REGION, marked(3));
        storage.flush();
        assertEquals(0, names(directory).stream().filter(name -> name.contains(".unsaved-")).count(), "nothing is set aside before the world closes");
        wedge.release.countDown();
        reader.join(10_000);
        // The read is over: the edit is with the region's own cubes, and the next write holds both.
        assertEquals(1, storage.read(CUBE).getInt("mark"));
        assertEquals(3, storage.read(SAME_REGION).getInt("mark"));
        storage.close();
        CubeStorage again = new CubeStorage(directory);
        assertEquals(1, again.read(CUBE).getInt("mark"));
        assertEquals(3, again.read(SAME_REGION).getInt("mark"));
    }
}
