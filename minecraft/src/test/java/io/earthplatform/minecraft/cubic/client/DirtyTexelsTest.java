package io.earthplatform.minecraft.cubic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirtyTexelsTest {
    @Test void coalescesOnlyAdjacentRecordsAndHandlesZeroDuplicatesAndReuse() {
        DirtyTexels dirty = new DirtyTexels();
        for (int slot : new int[] {9, 0, 4, 3, 4, 10, 262143}) dirty.mark(slot);
        List<String> ranges = new ArrayList<>();
        dirty.flush((first, end) -> ranges.add(first + ":" + end));
        assertEquals(List.of("0:1", "3:5", "9:11", "262143:262144"), ranges);
        dirty.flush((first, end) -> fail("Already published"));
        dirty.mark(4);
        dirty.flush((first, end) -> assertEquals("4:5", first + ":" + end));
    }

    @Test void failedRangeAndLaterWritesSurviveRetry() {
        DirtyTexels dirty = new DirtyTexels();
        for (int slot : new int[] {0, 3, 4, 9}) dirty.mark(slot);
        List<String> published = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> dirty.flush((first, end) -> {
            if (first == 3) throw new IllegalStateException("test upload failure");
            published.add(first + ":" + end);
        }));
        assertEquals(List.of("0:1"), published);
        dirty.mark(5);
        dirty.flush((first, end) -> published.add(first + ":" + end));
        assertEquals(List.of("0:1", "3:6", "9:10"), published);
    }

    @Test void finalGpuMirrorEqualsImmediateWritesAcrossRandomEditsAndSlotReuse() {
        Random random = new Random(6059);
        int[] mirror = new int[65536 * 4], immediate = mirror.clone(), uploaded = mirror.clone();
        DirtyTexels dirty = new DirtyTexels();
        for (int batch = 0; batch < 200; batch++) {
            for (int change = 0; change < 500; change++) {
                int slot = random.nextInt(mirror.length / 4);
                for (int component = 0; component < 4; component++) {
                    // Reassignment/reuse can replace the entire record before the next stamp.
                    int value = random.nextInt();
                    immediate[slot * 4 + component] = mirror[slot * 4 + component] = value;
                }
                dirty.mark(slot);
            }
            dirty.flush((first, end) -> System.arraycopy(mirror, first * 4, uploaded, first * 4, (end - first) * 4));
            assertArrayEquals(immediate, uploaded, "batch " + batch);
        }
    }
}
