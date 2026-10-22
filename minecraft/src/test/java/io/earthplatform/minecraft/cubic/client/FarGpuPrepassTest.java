package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarGpuPrepassTest {
    @Test void onByDefaultOffOnApple() {
        assertTrue(FarGpu.prepassByDefault("auto", "NVIDIA Corporation", "NVIDIA GeForce RTX 2080 Ti/PCIe/SSE2"));
        assertTrue(FarGpu.prepassByDefault("auto", "ATI Technologies Inc.", "AMD Radeon RX 7900 XTX"));
        assertTrue(FarGpu.prepassByDefault("auto", null, null));
        assertFalse(FarGpu.prepassByDefault("auto", "Apple", "Apple M4 Pro"));
        assertFalse(FarGpu.prepassByDefault("auto", "Apple Inc.", "Apple M1"));
    }

    @Test void theFlagWins() {
        assertTrue(FarGpu.prepassByDefault("true", "Apple", "Apple M4 Pro"));
        assertFalse(FarGpu.prepassByDefault("false", "NVIDIA Corporation", "NVIDIA GeForce RTX 2080 Ti/PCIe/SSE2"));
    }
}
