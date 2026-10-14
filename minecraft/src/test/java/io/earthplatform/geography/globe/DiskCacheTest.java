package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    void readingAFileMarksItUsed() throws Exception {
        Path read = file("read", 1, 1_000);
        DiskCache.read(read);
        assertTrue(Files.getLastModifiedTime(read).toMillis() > 1_000);
    }
}
