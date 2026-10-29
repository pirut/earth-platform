package io.earthplatform.minecraft.cubic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
}
