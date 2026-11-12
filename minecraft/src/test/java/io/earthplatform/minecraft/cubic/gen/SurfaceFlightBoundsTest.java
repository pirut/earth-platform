package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.SurfaceFlight;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class SurfaceFlightBoundsTest {
    @Test void deepOceanUsesAWaterSkinRatherThanTheSeabedToSeaLevelStack() {
        CubeGenerator ocean = new CubeGenerator() {
            public String id() { return "test-ocean"; }
            public LevelChunkSection generate(int x, int y, int z) { return null; }
            public void farColumn(int level, int x, int z, FarColumn out) {}
            public int surfaceY(int x, int z) { return -10_000; }
            public int waterY(int x, int z) { return 63; }
            public int seaLevel() { return 63; }
        };
        assertEquals(-10_000, ocean.lowestGround(0, 0), "burial still uses the bed");
        assertEquals(63, ocean.lowestSurface(0, 0));
        assertEquals(63, ocean.highestSurface(0, 0));
        LongArrayList cubes = new LongArrayList();
        SurfaceFlight.column(0, 0, ocean.lowestSurface(0, 0), ocean.highestSurface(0, 0), 8, 263, 8, cubes::add);
        assertEquals(8, cubes.size(), "bounded skin even over a ten-kilometre trench");
    }

    @Test void cachedGeneratorBoundsMatchEveryBlockAcrossShoresAndNegativeCoordinates() {
        SurfaceGenerator country = new SurfacePacketFixtures.Country(0);
        for (int cz = -2; cz <= 2; cz++) for (int cx = -2; cx <= 2; cx++) {
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE, bed = Integer.MAX_VALUE;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int bx = (cx << 4) + x, bz = (cz << 4) + z;
                int ground = country.surfaceY(bx, bz), surface = Math.max(ground, country.waterY(bx, bz));
                bed = Math.min(bed, ground); low = Math.min(low, surface); high = Math.max(high, surface);
            }
            assertEquals(bed, country.lowestGround(cx, cz));
            assertEquals(low, country.lowestSurface(cx, cz));
            assertEquals(high, country.highestSurface(cx, cz));
        }
    }
}
