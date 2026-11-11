package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.WorldDepth;
import java.util.Properties;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class WorldDepthTest {
    private static final class Flat extends SurfaceGenerator {
        private final int groundY;
        int samples;
        Flat(int bottom, int groundY) {
            super(9876, SurfacePacketFixtures.biomes(), RandomSource.create(9876), bottom);
            this.groundY = groundY;
        }
        @Override protected void ground(int x, int z, int[] out) { samples++; out[0] = groundY; out[1] = DRY; }
        @Override protected Surface surface(int x, int z, int h, int water, int slope) {
            return new Surface(SurfacePacketFixtures.biomes().getHolderOrThrow(Biomes.PLAINS), GRASS, DIRT, 0, 0, 0, 0, 0, false);
        }
        public String id() { return "depth-test"; }
        public int seaLevel() { return 63; }
        @Override protected int shoreLevel() { return 63; }
        @Override protected boolean emeralds(int maxGround) { return false; }
    }

    @Test void missingPinPreservesOldWorldsAndInvalidPinsFailClearly() {
        Properties pin = new Properties();
        assertEquals(WorldDepth.EARTH_DEPTH, WorldDepth.read(pin));
        for (WorldDepth depth : WorldDepth.values()) {
            pin.setProperty(WorldDepth.KEY, depth.id);
            assertEquals(depth, WorldDepth.read(pin));
        }
        pin.setProperty(WorldDepth.KEY, "unknown");
        assertThrows(IllegalStateException.class, () -> WorldDepth.read(pin));
        assertEquals(-11937, WorldDepth.STANDARD.minimumY);
        assertEquals(CubeWorld.MIN_Y, WorldDepth.EARTH_DEPTH.minimumY);
    }

    @Test void newWorldDefaultAndOptInAreIndependentOfExistingPins() {
        String original = System.getProperty("earthplatform.earth.depth");
        try {
            System.clearProperty("earthplatform.earth.depth");
            assertEquals(WorldDepth.STANDARD, WorldDepth.newWorld());
            System.setProperty("earthplatform.earth.depth", "true");
            assertEquals(WorldDepth.EARTH_DEPTH, WorldDepth.newWorld());
            Properties standard = new Properties(); standard.setProperty(WorldDepth.KEY, "standard");
            assertEquals(WorldDepth.STANDARD, WorldDepth.read(standard));
            System.setProperty("earthplatform.earth.depth", "false");
            assertEquals(WorldDepth.EARTH_DEPTH, WorldDepth.read(new Properties()));
        } finally {
            if (original == null) System.clearProperty("earthplatform.earth.depth");
            else System.setProperty("earthplatform.earth.depth", original);
        }
    }

    @Test void belowFloorReturnsEmptyWithoutSamplingTerrainOrBuildingCaves() {
        Flat terrain = new Flat(WorldDepth.STANDARD.minimumY, 63);
        assertTrue(terrain.generate(0, (terrain.minimumY() >> 4) - 1, 0).hasOnlyAir());
        assertEquals(0, terrain.samples);
    }

    @Test void unalignedFloorIsSealedAcrossCubeSeamsWithNoBlocksBelowIt() {
        Flat terrain = new Flat(WorldDepth.STANDARD.minimumY, -10872);
        int bottom = terrain.minimumY();
        for (int cy = (bottom >> 4) - 1; cy <= (bottom + 20) >> 4; cy++) {
            LevelChunkSection cube = terrain.generate(-1, cy, 2);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int by = (cy << 4) + y;
                var state = cube.getBlockState(x, y, z);
                if (by < bottom) assertTrue(state.isAir(), "void below floor");
                else if (by == bottom || terrain.underground().bedrock(-16 + x, by, 32 + z)) assertTrue(state.is(Blocks.BEDROCK));
                else assertFalse(state.is(Blocks.BEDROCK));
            }
        }
        assertFalse(terrain.generate(0, -10872 >> 4, 0).hasOnlyAir(), "deep trench ground remains present");
    }

    @Test void ordinaryCavesAndOresAboveTheFloorKeepTheirOriginalBlocks() {
        Flat standard = new Flat(WorldDepth.STANDARD.minimumY, 63), original = new Flat(CubeWorld.MIN_Y, 63);
        for (int cy : new int[] {2, -1, -5, -20}) {
            LevelChunkSection a = standard.generate(-2, cy, 1), b = original.generate(-2, cy, 1);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                assertSame(b.getBlockState(x, y, z), a.getBlockState(x, y, z));
        }
    }
}
