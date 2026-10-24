package io.earthplatform.minecraft.cubic;

import java.nio.file.Path;
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
}
