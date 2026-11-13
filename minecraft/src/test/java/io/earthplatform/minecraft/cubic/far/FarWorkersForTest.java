package io.earthplatform.minecraft.cubic.far;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FarWorkersForTest {
    /** Every core count a player might have gives thread counts without throwing. */
    @Test void previewWorkersHoldOnEveryCoreCount() {
        for (int cores = 1; cores <= 256; cores++) {
            int workers = FarServer.workersFor(cores);
            int previews = FarServer.previewWorkersFor(cores, workers);
            int expected = Math.max(workers, Math.min(cores - 2, 8));
            assertEquals(expected, previews, "cores " + cores);
        }
    }

    @Test void previewWorkersOnCommonMachines() {
        assertEquals(2, FarServer.previewWorkersFor(4, FarServer.workersFor(4)));
        assertEquals(8, FarServer.previewWorkersFor(12, FarServer.workersFor(12)));
        assertEquals(8, FarServer.previewWorkersFor(18, FarServer.workersFor(18)));
        // A Ryzen 9 3900X: 24 threads, 11 far workers. The old clamp threw "11 > 8" here.
        assertEquals(11, FarServer.previewWorkersFor(24, FarServer.workersFor(24)));
        assertEquals(12, FarServer.previewWorkersFor(64, FarServer.workersFor(64)));
    }
}
