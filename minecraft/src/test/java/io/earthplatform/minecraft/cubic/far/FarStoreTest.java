package io.earthplatform.minecraft.cubic.far;

import io.earthplatform.minecraft.cubic.net.CubePayloads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FarStoreTest {
    private static FarStore open(Path directory, String stamp, long limit) {
        FarStore store = new FarStore(directory, stamp, limit, message -> {});
        store.flush();
        return store;
    }

    private static byte[] column(int seed, int size) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static List<Path> files(Path directory) throws Exception {
        try (Stream<Path> all = Files.walk(directory)) { return all.filter(file -> file.toString().endsWith(".far")).sorted().toList(); }
    }

    @Test void columnsComeBackAfterAReopenAtEveryLevelAndSign(@TempDir Path directory) throws Exception {
        long[] keys = {Far.key(1, 0, 0), Far.key(1, -1, -1), Far.key(4, 31, 32), Far.key(8, -4000, 77), Far.key(8, -4001, 77)};
        FarStore store = open(directory, "a", 0);
        for (int i = 0; i < keys.length; i++) store.put(keys[i], 0, column(i, 100 + 3000 * i));
        store.flush();
        for (int i = 0; i < keys.length; i++) assertArrayEquals(column(i, 100 + 3000 * i), store.get(keys[i]));
        assertNull(store.get(Far.key(2, 0, 0)));
        store.close();

        FarStore again = open(directory, "a", 0);
        for (int i = 0; i < keys.length; i++) assertArrayEquals(column(i, 100 + 3000 * i), again.get(keys[i]), "key " + i);
        again.close();
    }

    @Test void theLastColumnPutCountsAndARemovedOneIsGoneAtOnce(@TempDir Path directory) {
        long key = Far.key(3, 5, -9);
        FarStore store = open(directory, "a", 0);
        store.put(key, 0, column(1, 500));
        store.put(key, 0, column(2, 700));
        store.flush();
        assertArrayEquals(column(2, 700), store.get(key));
        store.remove(key);
        assertNull(store.get(key), "absent before its record is written");
        store.flush();
        assertNull(store.get(key));
        store.close();
        FarStore again = open(directory, "a", 0);
        assertNull(again.get(key));
        again.close();
    }

    @Test void aColumnMadeFromGroundThatChangedMeanwhileIsNotKept(@TempDir Path directory) {
        long key = Far.key(2, 1, 1);
        FarStore store = open(directory, "a", 0);
        int before = store.epoch(key);
        store.remove(key);
        store.put(key, before, column(1, 300));
        store.flush();
        assertNull(store.get(key), "made before the change");
        store.put(key, store.epoch(key), column(2, 300));
        store.flush();
        assertArrayEquals(column(2, 300), store.get(key));
        store.close();
    }

    @Test void anotherStampEmptiesTheStore(@TempDir Path directory) throws Exception {
        long key = Far.key(2, 1, 1);
        FarStore store = open(directory, "build 70", 0);
        store.put(key, 0, column(1, 300));
        store.close();
        FarStore next = open(directory, "build 71", 0);
        assertNull(next.get(key));
        assertTrue(files(directory).isEmpty());
        next.put(key, 0, column(2, 300));
        next.close();
        FarStore same = open(directory, "build 71", 0);
        assertArrayEquals(column(2, 300), same.get(key));
        same.close();
    }

    @Test void aRecordCutShortEndsTheFileThereAndTheRestStays(@TempDir Path directory) throws Exception {
        long first = Far.key(2, 1, 1), second = Far.key(2, 2, 1);
        FarStore store = open(directory, "a", 0);
        store.put(first, 0, column(1, 4000));
        store.put(second, 0, column(2, 4000));
        store.close();
        Path file = files(directory).get(0);
        byte[] whole = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(whole, whole.length - 100));

        FarStore again = open(directory, "a", 0);
        assertArrayEquals(column(1, 4000), again.get(first));
        assertNull(again.get(second));
        again.put(second, 0, column(3, 4000));
        again.flush();
        assertArrayEquals(column(3, 4000), again.get(second), "written after the cut");
        again.close();
    }

    @Test void damagedBytesFailTheirChecksumAndDropOnlyThatFile(@TempDir Path directory) throws Exception {
        long bad = Far.key(2, 1, 1), good = Far.key(3, 1, 1);
        FarStore store = open(directory, "a", 0);
        store.put(bad, 0, column(1, 4000));
        store.put(good, 0, column(2, 4000));
        store.close();
        Path file = directory.resolve("2").resolve("0.0.far");
        byte[] whole = Files.readAllBytes(file);
        whole[whole.length - 50] ^= 0x55;
        Files.write(file, whole);

        List<String> told = new ArrayList<>();
        FarStore again = new FarStore(directory, "a", 0, told::add);
        again.flush();
        assertNull(again.get(bad));
        assertEquals(1, told.size(), "told " + told);
        assertFalse(Files.exists(file));
        assertArrayEquals(column(2, 4000), again.get(good));
        again.close();
    }

    @Test void aFileOfMostlyDeadRecordsIsWrittenOutAfresh(@TempDir Path directory) throws Exception {
        long key = Far.key(2, 1, 1), other = Far.key(2, 3, 3);
        FarStore store = open(directory, "a", 0);
        store.put(other, 0, column(9, 2000));
        for (int i = 0; i < 40; i++) store.put(key, 0, column(i, 100_000));
        store.flush();
        long size = Files.size(files(directory).get(0));
        assertTrue(size < 1_500_000, "size " + size);
        assertArrayEquals(column(39, 100_000), store.get(key));
        assertArrayEquals(column(9, 2000), store.get(other));
        store.close();
        FarStore again = open(directory, "a", 0);
        assertArrayEquals(column(39, 100_000), again.get(key));
        assertArrayEquals(column(9, 2000), again.get(other));
        again.close();
    }

    @Test void theFilesUsedLongestAgoGoFirstWhenTheStoreIsOverItsLimit(@TempDir Path directory) throws Exception {
        long old = Far.key(2, 1, 1), recent = Far.key(3, 1, 1);
        FarStore store = open(directory, "a", 0);
        store.put(old, 0, column(1, 300_000));
        store.put(recent, 0, column(2, 300_000));
        store.close();
        Files.setLastModifiedTime(directory.resolve("2").resolve("0.0.far"), java.nio.file.attribute.FileTime.fromMillis(1_000_000));

        FarStore limited = open(directory, "a", 400_000);
        assertNull(limited.get(old));
        assertArrayEquals(column(2, 300_000), limited.get(recent));
        limited.close();
    }

    @Test void emptyingTakesEverythingOutAndTheStoreGoesOn(@TempDir Path directory) throws Exception {
        long key = Far.key(2, 1, 1);
        FarStore store = open(directory, "a", 0);
        store.put(key, 0, column(1, 300));
        store.flush();
        store.empty();
        store.flush();
        assertNull(store.get(key));
        store.put(key, 0, column(2, 300));
        store.flush();
        assertArrayEquals(column(2, 300), store.get(key));
        store.close();
    }

    @Test void aColumnRoundTripsThroughItsStoredForm() {
        short[] drops = new short[256];
        for (int i = 0; i < 256; i++) drops[i] = (short) (i * 251 - 30000);
        var column = new CubePayloads.FarColumnData(Far.key(4, -3, 9), -12, List.of(column(1, 900), new byte[0], column(2, 17)), drops);
        var back = FarServer.stored(column.key(), FarServer.stored(column));
        assertEquals(column.key(), back.key());
        assertEquals(column.bottom(), back.bottom());
        assertEquals(3, back.sections().size());
        for (int i = 0; i < 3; i++) assertArrayEquals(column.sections().get(i), back.sections().get(i));
        assertArrayEquals(drops, back.drops());
        byte[] bytes = FarServer.stored(column);
        assertThrows(RuntimeException.class, () -> FarServer.stored(column.key(), java.util.Arrays.copyOf(bytes, bytes.length - 3)));
    }

    @Test void aPlayerMayQueueOnlySoManyColumns() {
        assertTrue(FarServer.mayQueue(0));
        assertTrue(FarServer.mayQueue(FarServer.QUEUED - 1));
        assertFalse(FarServer.mayQueue(FarServer.QUEUED));
    }
}
