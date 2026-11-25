package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarGpuPrepassTest {
    @Test void onByDefaultOffOnAppleAndAmd() {
        assertTrue(FarGpu.prepassByDefault("auto", "NVIDIA Corporation", "NVIDIA GeForce RTX 2080 Ti/PCIe/SSE2"));
        assertTrue(FarGpu.prepassByDefault("auto", "Intel", "Intel(R) Arc(TM) A770 Graphics"));
        assertTrue(FarGpu.prepassByDefault("auto", null, null));
        assertFalse(FarGpu.prepassByDefault("auto", "Apple", "Apple M4 Pro"));
        assertFalse(FarGpu.prepassByDefault("auto", "Apple Inc.", "Apple M1"));
        // Issue 290: an AMD driver faulted natively (atio6axx.dll) drawing far terrain's pack programs.
        assertFalse(FarGpu.prepassByDefault("auto", "ATI Technologies Inc.", "AMD Radeon RX 6800 XT"));
        assertFalse(FarGpu.prepassByDefault("auto", "ATI Technologies Inc.", null));
        assertFalse(FarGpu.prepassByDefault("auto", null, "Radeon RX 580 Series"));
        assertFalse(FarGpu.prepassByDefault("auto", "AMD", "AMD Radeon(TM) Graphics (radeonsi, raphael_mendocino)"));
    }

    @Test void theFlagWins() {
        assertTrue(FarGpu.prepassByDefault("true", "Apple", "Apple M4 Pro"));
        assertFalse(FarGpu.prepassByDefault("false", "NVIDIA Corporation", "NVIDIA GeForce RTX 2080 Ti/PCIe/SSE2"));
        assertTrue(FarGpu.prepassByDefault("true", "ATI Technologies Inc.", "AMD Radeon RX 6800 XT"));
    }
}
