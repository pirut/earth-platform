package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskCacheTest {
    @TempDir Path cache;

    private Path file(String name, int kib, long time) throws Exception {
        Path path = cache.resolve(name);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[kib << 10]);
        Files.setLastModifiedTime(path, FileTime.fromMillis(time));
        return path;
    }

    @Test
    void deletesTheLeastRecentlyUsedFilesUntilTheCacheFitsInNinetyPercent() throws Exception {
        Path oldest = file("a/1", 100, 1_000), older = file("b/2", 100, 2_000), newer = file("a/3", 100, 3_000), newest = file("c/4", 100, 4_000);
        DiskCache.trim(cache, 300 << 10, Long.MAX_VALUE);
        assertFalse(Files.exists(oldest));
        assertFalse(Files.exists(older));
        assertTrue(Files.exists(newer));
        assertTrue(Files.exists(newest));
    }

    @Test
    void leavesTheCacheAloneUnderItsLimit() throws Exception {
        Path a = file("a", 100, 1_000), b = file("b", 100, 2_000);
        DiskCache.trim(cache, 200 << 10, Long.MAX_VALUE);
        assertTrue(Files.exists(a));
        assertTrue(Files.exists(b));
    }

    @Test
    void neverDeletesFilesThisSessionUsed() throws Exception {
        Path before = file("before", 100, 1_000), during = file("during", 100, 5_000), later = file("later", 100, 6_000);
        DiskCache.trim(cache, 100 << 10, 5_000);
        assertFalse(Files.exists(before));
        assertTrue(Files.exists(during));
        assertTrue(Files.exists(later));
    }

    @Test
    void trimsOlderCurrentSessionFilesOutsideGraceWindowWhenOverLimit() throws Exception {
        Path oldSession = file("old-session", 100, 1_000);
        Path earlierThisSession = file("earlier", 100, 5_000);
        Path recentThisSession = file("recent", 100, 12_000);
        DiskCache.trim(cache, 150 << 10, 4_000, 10_000);
        assertFalse(Files.exists(oldSession));
        assertFalse(Files.exists(earlierThisSession));
        assertTrue(Files.exists(recentThisSession));
    }

    @Test
    void hardCapEvictsEvenRecentBurstFilesWhenExceedingTwiceTheLimit() throws Exception {
        Path a = file("a", 80, 10_000);
        Path b = file("b", 80, 11_000);
        Path c = file("c", 80, 12_000);
        DiskCache.trim(cache, 100 << 10, 5_000, 9_000);
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertTrue(Files.exists(c));
    }

    @Test
    void readingAFileMarksItUsed() throws Exception {
        Path read = file("read", 1, 1_000);
        DiskCache.read(read);
        assertTrue(Files.getLastModifiedTime(read).toMillis() > 1_000);
    }

    @Test
    void manyWritersOfOneFileAllFinishWithOneWholeFile() throws Exception {
        Path target = cache.resolve("rows/5538.z");
        byte[] bytes = new byte[64 << 10];
        java.util.Arrays.fill(bytes, (byte) 7);
        List<Thread> writers = new java.util.ArrayList<>();
        List<Throwable> failed = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < 8; i++) writers.add(Thread.ofPlatform().start(() -> {
            try {
                for (int j = 0; j < 20; j++) {
                    DiskCache.write(target, bytes);
                    assertArrayEquals(bytes, Files.readAllBytes(target));
                }
            } catch (Throwable e) { failed.add(e); }
        }));
        for (Thread writer : writers) writer.join();
        assertEquals(List.of(), failed);
        try (var left = Files.list(target.getParent())) { assertEquals(List.of(target), left.toList(), "no .part files left behind"); }
    }

    @Test
    void aLockedMoveIsRetriedThenReplacedPlainly() throws Exception {
        Path target = cache.resolve("rows/5538.z");
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "part", ".part");
        byte[] bytes = "climate".getBytes();
        Files.write(temp, bytes);
        int[] atomic = {0}, plain = {0};
        long saved = DiskCache.retryWaitMs;
        DiskCache.retryWaitMs = 0;
        try {
            DiskCache.moveIntoPlace(temp, target, (from, to, atom) -> {
                if (atom) {
                    atomic[0]++;
                    throw new AccessDeniedException(from.toString(), to.toString(), "Access is denied");
                }
                plain[0]++;
                Files.move(from, to);
            });
        } finally { DiskCache.retryWaitMs = saved; }
        assertEquals(5, atomic[0]);
        assertEquals(1, plain[0]);
        assertArrayEquals(bytes, Files.readAllBytes(target));
    }

    @Test
    void aSharingViolationKeepsAFileAnotherWriterAlreadyFinished() throws Exception {
        Path target = cache.resolve("kept.z");
        byte[] existing = new byte[] {1, 2, 3, 4};
        Files.write(target, existing);
        Path temp = Files.createTempFile(cache, "part", ".part");
        Files.write(temp, new byte[] {9, 9, 9});
        DiskCache.retryWaitMs = 0;
        try {
            DiskCache.moveIntoPlace(temp, target, (from, to, atom) -> {
                throw new FileSystemException(from.toString(), to.toString(),
                        "The process cannot access the file because it is being used by another process");
            });
        } finally { DiskCache.retryWaitMs = 5; }
        assertArrayEquals(existing, Files.readAllBytes(target));
        assertTrue(DiskCache.locked(new FileSystemException("a", "b", "The process cannot access the file because it is being used by another process")));
        assertFalse(DiskCache.locked(new FileSystemException("a", "b", "No space left on device")));
    }

    @Test
    void anEmptyFileDoesNotCountAsTheBytesBeingSaved() throws Exception {
        Path target = cache.resolve("empty.z");
        Files.write(target, new byte[0]);
        Path temp = Files.createTempFile(cache, "part", ".part");
        byte[] bytes = new byte[] {8, 8};
        Files.write(temp, bytes);
        DiskCache.retryWaitMs = 0;
        try {
            DiskCache.moveIntoPlace(temp, target, (from, to, atom) -> {
                throw new AccessDeniedException(from.toString(), to.toString(), null);
            });
        } finally { DiskCache.retryWaitMs = 5; }
        assertArrayEquals(bytes, Files.readAllBytes(target));
    }

    @Test
    void aLockWithNoFileYetIsCopiedIntoPlace() throws Exception {
        Path target = cache.resolve("copied.z");
        Path temp = Files.createTempFile(cache, "part", ".part");
        byte[] bytes = new byte[] {5, 6, 7};
        Files.write(temp, bytes);
        DiskCache.retryWaitMs = 0;
        try {
            DiskCache.moveIntoPlace(temp, target, (from, to, atom) -> {
                throw new AccessDeniedException(from.toString(), to.toString(), null);
            });
        } finally { DiskCache.retryWaitMs = 5; }
        assertArrayEquals(bytes, Files.readAllBytes(target));
    }

    @Test
    void aMissingFileIsNotRetriedAsALock() {
        Path temp = cache.resolve("missing-temp");
        Path target = cache.resolve("missing-target");
        assertThrows(NoSuchFileException.class, () -> DiskCache.moveIntoPlace(temp, target, (from, to, atom) -> {
            throw new NoSuchFileException(from.toString());
        }));
    }
}
