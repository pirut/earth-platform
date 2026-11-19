package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.Map;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

class TerrainSlabsTest {
    @Test void stepUsesTheNeighbourAcrossTheCubeEdgeAtAnyHeight() {
        Fixture edge = new Fixture(4095);
        edge.height[Fixture.COLUMN + 1] = 4096;
        edge.slabs.decorate(edge.section, 4080, edge.height, edge.water, edge.cover);
        assertSame(Blocks.AIR.defaultBlockState(), edge.section.getBlockState(15, 15, 8));

        edge.slabs.decorate(edge.section, 4096, edge.height, edge.water, edge.cover);
        assertSame(edge.slab, edge.section.getBlockState(15, 0, 8));
        assertSame(Blocks.AIR.defaultBlockState(), edge.section.getBlockState(14, 0, 8));
    }

    @Test void leavesCliffsWetEdgesAndPlantsAlone() {
        Fixture edge = new Fixture(100);
        edge.height[Fixture.COLUMN + 1] = 102;
        edge.slabs.decorate(edge.section, 96, edge.height, edge.water, edge.cover);
        assertSame(Blocks.AIR.defaultBlockState(), edge.section.getBlockState(15, 5, 8));

        edge.height[Fixture.COLUMN + 1] = 101;
        edge.water[Fixture.COLUMN - 18] = 100;
        edge.slabs.decorate(edge.section, 96, edge.height, edge.water, edge.cover);
        assertSame(Blocks.AIR.defaultBlockState(), edge.section.getBlockState(15, 5, 8));

        edge.water[Fixture.COLUMN - 18] = SurfaceGenerator.DRY;
        BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
        edge.section.setBlockState(15, 5, 8, grass, false);
        edge.slabs.decorate(edge.section, 96, edge.height, edge.water, edge.cover);
        assertSame(grass, edge.section.getBlockState(15, 5, 8));
    }

    private static class Fixture {
        static final int COLUMN = 9 * 18 + 16;
        final LevelChunkSection section;
        final int[] height = new int[18 * 18], water = new int[18 * 18];
        final SurfaceGenerator.Surface[] cover = new SurfaceGenerator.Surface[256];
        final BlockState slab;
        final TerrainSlabs slabs;

        Fixture(int ground) {
            var biomes = SurfacePacketFixtures.biomes();
            section = new LevelChunkSection(biomes);
            slab = Blocks.STONE_SLAB.defaultBlockState();
            slabs = new TerrainSlabs(Map.of(Blocks.GRASS_BLOCK, slab));
            Arrays.fill(height, ground);
            Arrays.fill(water, SurfaceGenerator.DRY);
            Arrays.fill(cover, new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.PLAINS),
                    Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 0, 0, 0, 0, 0, false));
        }
    }
}
