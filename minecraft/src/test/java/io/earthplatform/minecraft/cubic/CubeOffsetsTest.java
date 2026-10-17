package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CubeOffsetsTest {
    private static Set<Long> all(CubeOffsets offsets) {
        Set<Long> keys = new HashSet<>();
        for (int i = 0; i < offsets.size(); i++) keys.add(CubeKey.pack(offsets.dx(i), offsets.dy(i), offsets.dz(i)));
        return keys;
    }

    private static int index(CubeOffsets offsets, int x, int y, int z) {
        for (int i = 0; i < offsets.size(); i++) if (offsets.dx(i) == x && offsets.dy(i) == y && offsets.dz(i) == z) return i;
        throw new AssertionError("missing " + x + " " + y + " " + z);
    }

    @Test void viewOrderKeepsEveryCube() {
        CubeOffsets plain = CubeOffsets.of(12, 6);
        for (int sector = 0; sector < ViewOrder.SECTORS; sector++) assertEquals(all(plain), all(CubeOffsets.of(12, 6, sector)));
    }

    @Test void inViewComesBeforeBehind() {
        // Yaw 0 looks south, toward +z.
        int sector = ViewOrder.sector(0);
        assertEquals(0, sector);
        CubeOffsets offsets = CubeOffsets.of(12, 6, sector);
        assertTrue(index(offsets, 0, 0, 8) < index(offsets, 0, 0, -6));
        assertTrue(index(offsets, 0, 0, 10) < index(offsets, 6, 0, 0));
        // The cubes right around the player still come first, whichever way they lie.
        assertTrue(index(offsets, 0, -1, -1) < index(offsets, 0, 0, 4));
        // Facing north instead turns it round.
        CubeOffsets north = CubeOffsets.of(12, 6, ViewOrder.sector(180));
        assertTrue(index(north, 0, 0, -8) < index(north, 0, 0, 6));
    }

    @Test void laterGrowsOnlyOutOfView() {
        assertEquals(1.0, ViewOrder.later(0, 10, 0, 1, 1.5));
        assertEquals(1.0, ViewOrder.later(8, 10, 0, 1, 1.5));
        assertEquals(ViewOrder.BEHIND, ViewOrder.later(0, -10, 0, 1, 1.5), 1e-9);
        assertEquals(1.0, ViewOrder.later(0, -1, 0, 1, 1.5));
        assertTrue(ViewOrder.later(10, 0, 0, 1, 1.5) > 1.0);
    }
}
