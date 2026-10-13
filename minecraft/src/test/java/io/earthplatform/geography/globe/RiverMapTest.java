package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class RiverMapTest {
    private static final int SPAN = 256;

    @Test
    void namesTilesByTheirNorthWestCorner() {
        assertEquals("occurrence_60W_0Nv1_4_2021.tif", SurfaceWaterSource.name(8, 12));
        assertEquals("occurrence_180W_80Nv1_4_2021.tif", SurfaceWaterSource.name(0, 0));
        assertEquals("occurrence_170E_50Sv1_4_2021.tif", SurfaceWaterSource.name(13, 35));
    }

    @Test
    void decodesLzwLiterals() throws Exception {
        byte[] data = new byte[120];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 37);
        Bits bits = new Bits();
        bits.put(256, 9);
        for (byte b : data) bits.put(b & 0xFF, 9);
        bits.put(257, 9);
        assertArrayEquals(data, SurfaceWaterSource.lzw(bits.bytes(), data.length));
    }

    @Test
    void bridgesAGapInANarrowRiver() {
        int[] occurrence = new int[SPAN * SPAN];
        for (int x = 20; x < 236; x++) if (x < 100 || x >= 120) occurrence[128 * SPAN + x] = 80;
        float[] f = RiverMap.trace(occurrence, SPAN);
        assertTrue(f[128 * SPAN + 60] >= RiverMap.EDGE, "the river itself");
        float best = 0;
        for (int y = 118; y <= 138; y++) best = Math.max(best, f[y * SPAN + 110]);
        assertTrue(best >= RiverMap.EDGE, "the gap is bridged, got " + best);
        assertEquals(0, f[40 * SPAN + 110], "dry land stays dry");
    }

    @Test
    void leavesOutWideWater() {
        int[] occurrence = new int[SPAN * SPAN];
        for (int y = 100; y < 160; y++) for (int x = 100; x < 160; x++) occurrence[y * SPAN + x] = 100;
        float[] f = RiverMap.trace(occurrence, SPAN);
        assertEquals(0, f[130 * SPAN + 130], "a lake is left to the land cover");
    }

    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int buffer, count;

        void put(int code, int width) {
            for (int i = width - 1; i >= 0; i--) {
                buffer = buffer << 1 | (code >> i & 1);
                if (++count == 8) { out.write(buffer); buffer = 0; count = 0; }
            }
        }

        byte[] bytes() {
            if (count > 0) out.write(buffer << (8 - count));
            return out.toByteArray();
        }
    }
}
