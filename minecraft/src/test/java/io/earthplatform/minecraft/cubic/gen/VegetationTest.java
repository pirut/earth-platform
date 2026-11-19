package io.earthplatform.minecraft.cubic.gen;

import java.util.Arrays;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VegetationTest {
    @Test void sandyColumnsObserveTheGeneratorCactusRange() {
        var biomes = SurfacePacketFixtures.biomes();
        var sand = Blocks.RED_SAND.defaultBlockState();
        var desert = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.DESERT), sand, sand,
                0, 0, 0, 0, 1000, false);
        var generator = new SurfaceGenerator(0, biomes, RandomSource.create(0)) {
            @Override protected boolean cacti(int x, int z) { return x >= 8; }
            @Override protected void ground(int x, int z, int[] out) { out[0] = 0; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) { return desert; }
            @Override public String id() { return "test_cactus_range"; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
        int[] height = new int[18 * 18], water = new int[18 * 18];
        Arrays.fill(water, SurfaceGenerator.DRY);
        var surface = new SurfaceGenerator.Surface[16 * 16];
        Arrays.fill(surface, desert);
        var section = new LevelChunkSection(biomes);

        new Vegetation(generator).decorate(section, 0, 0, 0, height, water, surface);

        assertFalse(section.getBlockState(6, 1, 0).is(Blocks.CACTUS));
        assertTrue(section.getBlockState(8, 1, 0).is(Blocks.CACTUS));
    }
}
