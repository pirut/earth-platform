package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class FarNearSkipTest {
    @Test void growMatchesTheShader() {
        assertTrue(FarShaders.VERTEX.contains("const float EARTH_GROW = 0.0006;") && FarNearSkip.GROW == 0.0006, "EARTH_GROW changed; update FarNearSkip.GROW");
        assertTrue(FarShaders.PACK_MAIN.contains("if (length(earth_position.xz) < earth_NearReach) discard;"));
    }

    @Test void onlyColumnsWhollyInsideAreDropped() {
        assertTrue(FarNearSkip.dropped(0, 60, 0, 32, 96, 32, 16, 70, 16, 192));
        assertFalse(FarNearSkip.dropped(160, 60, 0, 192, 96, 32, 0, 70, 0, 192), "reaches out past the edge");
        assertFalse(FarNearSkip.dropped(174, 60, 0, 190, 96, 16, 0, 70, 0, 192), "a corner within the margin");
        assertFalse(FarNearSkip.dropped(0, 60, 0, 32, 96, 32, 16, 70, 16, 0), "no reach without the game's blocks");
    }

    /**
     * Grown corners as the vertex shader places them, and points between them, never come within
     * the reach test's keep for a column called dropped.
     */
    @Test void everyGrownPointOfADroppedColumnIsDropped() {
        SplittableRandom random = new SplittableRandom(7);
        int dropped = 0;
        for (int trial = 0; trial < 20_000; trial++) {
            int level = 1 + random.nextInt(8);
            double width = 16 << level, reach = 32 + random.nextInt(32) * 16;
            double cx = random.nextDouble(-600, 600), cy = random.nextDouble(-200, 3000), cz = random.nextDouble(-600, 600);
            double x0 = Math.floor(random.nextDouble(-800, 800) / width) * width, z0 = Math.floor(random.nextDouble(-800, 800) / width) * width;
            double y0 = random.nextDouble(-100, 400), y1 = y0 + width * (1 + random.nextInt(4));
            if (!FarNearSkip.dropped(x0, y0, z0, x0 + width, y1, z0 + width, cx, cy, cz, reach)) continue;
            dropped++;
            for (int point = 0; point < 50; point++) {
                // A point on a face, grown along two directions by the shader's EARTH_GROW times its distance.
                double px = x0 + random.nextDouble() * width - cx, py = y0 + random.nextDouble() * (y1 - y0) - cy, pz = z0 + random.nextDouble() * width - cz;
                double grow = FarNearSkip.GROW * Math.sqrt(px * px + py * py + pz * pz);
                px += grow * (random.nextBoolean() ? 1 : -1);
                pz += grow * (random.nextBoolean() ? 1 : -1);
                assertTrue((float) Math.sqrt(px * px + pz * pz) < (float) reach, "a dropped column's point is kept");
            }
        }
        assertTrue(dropped > 100, "the test reaches dropped columns");
    }
}
