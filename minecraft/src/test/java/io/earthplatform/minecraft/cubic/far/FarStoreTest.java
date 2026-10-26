package io.earthplatform.minecraft.cubic.far;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FarStoreTest {
    private static List<byte[]> sections(long seed) {
        Random random = new Random(seed);
        byte[] a = new byte[300 + random.nextInt(4000)], b = new byte[random.nextInt(100)];
        // Compressible, as sections are.
        for (int i = 0; i < a.length; i++) a[i] = (byte) (random.nextInt(4) * 17);
        random.nextBytes(b);
        return List.of(a, new byte[0], b);
    }

    private static short[] drops(long seed) {
        short[] drops = new short[256];
        for (int i = 0; i < drops.length; i++) drops[i] = (short) (seed * 7 + i);
        return drops;
    }

    private static void assertStored(FarStore store, long key) {
        FarStore.Stored stored = store.get(key);
        assertNotNull(stored, "column " + key);
        assertEquals((int) key - 3, stored.bottom());
        List<byte[]> expected = sections(key);
        assertEquals(expected.size(), stored.sections().size());
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), stored.sections().get(i));
        assertArrayEquals(drops(key), stored.drops());
    }

    private static void put(FarStore store, long key) { store.put(key, (int) key - 3, sections(key), drops(key)); }

    @Test void keepsColumnsAcrossAReopen(@TempDir Path directory) {
        FarStore store = FarStore.open(directory, "build a", 1 << 30);
        for (long key = 1; key <= 50; key++) put(store, key);
        put(store, 7);
        assertNull(store.get(99));
        store.close();
        FarStore again = FarStore.open(directory, "build a", 1 << 30);
        for (long key = 1; key <= 50; key++) assertStored(again, key);
        again.close();
    }

    @Test void startsAfreshForAnotherBuild(@TempDir Path directory) {
        FarStore store = FarStore.open(directory, "build a", 1 << 30);
        put(store, 5);
        store.close();
        FarStore other = FarStore.open(directory, "build b", 1 << 30);
        assertNull(other.get(5));
        put(other, 6);
        assertStored(other, 6);
        other.close();
    }

    @Test void dropsAColumnCutShort(@TempDir Path directory) throws Exception {
        FarStore store = FarStore.open(directory, "build a", 1 << 30);
        put(store, 1);
        put(store, 2);
        store.close();
        Path file;
        try (var files = Files.list(directory)) { file = files.findFirst().orElseThrow(); }
        try (RandomAccessFile data = new RandomAccessFile(file.toFile(), "rw")) { data.setLength(data.length() - 10); }
        FarStore again = FarStore.open(directory, "build a", 1 << 30);
        assertStored(again, 1);
        assertNull(again.get(2));
        put(again, 3);
        again.close();
        FarStore third = FarStore.open(directory, "build a", 1 << 30);
        assertStored(third, 1);
        assertStored(third, 3);
        third.close();
    }

    @Test void keepsWhatWasUsedLatelyWithinItsSize(@TempDir Path directory) throws Exception {
        // Two files of 1 MB at most.
        FarStore store = FarStore.open(directory, "build a", 2 << 20);
        put(store, 0);
        for (long key = 1; key <= 3000; key++) {
            put(store, key);
            // Column 0 is read now and then, as a column near a player is.
            if (key % 200 == 0) assertStored(store, 0);
        }
        assertStored(store, 0);
        assertStored(store, 3000);
        assertNull(store.get(1));
        long bytes = 0;
        try (var files = Files.list(directory)) { for (Path path : (Iterable<Path>) files::iterator) bytes += Files.size(path); }
        assertTrue(bytes <= 2 << 20, "kept " + bytes);
        store.close();
        FarStore again = FarStore.open(directory, "build a", 2 << 20);
        assertStored(again, 0);
        assertStored(again, 3000);
        again.close();
    }
}
