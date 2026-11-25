package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.MapColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Issue 291: a filled map in the Earth was entirely grey. Vanilla's map walks a column from the
 * heightmap's top down to the first block with a map color; the carrier chunk it asked has none, so
 * every pixel hit the bedrock case, grey stone. {@code CubicMapItemMixin} sends those reads to
 * {@link CubicMaps}; this runs the same walk over cubes as vanilla's {@code MapItem.update} does.
 */
class CubicMapsTest {
    private static Registry<Biome> biomes;

    @BeforeAll static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes = registry.freeze();
    }

    private static CubeWorld world() {
        return new CubeWorld(null) {
            @Override protected int baselineTop(int x, int z) { return 0; }
            @Override public void blockChanged(BlockPos pos) {}
        };
    }

    /** Loads the cube holding {@code y} with {@code block} at the bottom of every column up to y, and sets the column's sky top. */
    private static void ground(CubeWorld cubes, int cubeX, int cubeZ, int y, BlockState top, BlockState below) {
        int cubeY = y >> 4;
        LevelChunkSection section = new LevelChunkSection(biomes);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int ly = 0; ly < 16; ly++) {
            int worldY = (cubeY << 4) + ly;
            if (worldY < y) section.setBlockState(x, ly, z, below);
            else if (worldY == y) section.setBlockState(x, ly, z, top);
        }
        cubes.adopt(new Cube(cubeX, cubeY, cubeZ, section));
        int[] tops = new int[256];
        Arrays.fill(tops, y);
        cubes.putTops(CubeKey.column(cubeX, cubeZ), tops);
    }

    /** What vanilla's map reads for one block: the first block going down from the surface with a map color. */
    private static MapColor mapColorAt(CubeWorld cubes, int x, int z) {
        int y = CubicMaps.surface(cubes, x, z) + 1;
        if (y <= CubeWorld.MIN_Y + 1) return Blocks.BEDROCK.defaultBlockState().getMapColor(null, BlockPos.ZERO);
        BlockState state;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        do {
            pos.set(x, --y, z);
            state = CubicMaps.state(cubes.getBlockState(pos));
        } while (state.getMapColor(null, pos) == MapColor.NONE && y > CubeWorld.MIN_Y);
        return state.getMapColor(null, pos);
    }

    @Test void groundShowsItsOwnColorNotBedrock() {
        CubeWorld cubes = world();
        ground(cubes, 2, -1, 1200, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        assertEquals(1200, CubicMaps.surface(cubes, 35, -3));
        assertEquals(MapColor.GRASS, mapColorAt(cubes, 35, -3));
        assertNotEquals(MapColor.STONE, mapColorAt(cubes, 35, -3));
    }

    @Test void seaFloorBelowTheCarrierFloorStillReadsTheSeaAndNotBedrock() {
        CubeWorld cubes = world();
        // Below the carrier's Y 0: vanilla's min-height test sent every such column to bedrock.
        ground(cubes, 0, 0, -3000, Blocks.SAND.defaultBlockState(), Blocks.STONE.defaultBlockState());
        assertEquals(-3000, CubicMaps.surface(cubes, 4, 4));
        assertEquals(MapColor.SAND, mapColorAt(cubes, 4, 4));
    }

    @Test void waterSurfaceReadsAsWater() {
        CubeWorld cubes = world();
        ground(cubes, 0, 0, 0, Blocks.WATER.defaultBlockState(), Blocks.SAND.defaultBlockState());
        assertEquals(MapColor.WATER, mapColorAt(cubes, 7, 7));
    }

    @Test void aColumnWithNothingLoadedIsLeftUnexplored() {
        CubeWorld cubes = world();
        assertFalse(CubicMaps.columnLoaded(cubes, 100, 100));
        ground(cubes, 0, 0, 40, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
        assertTrue(CubicMaps.columnLoaded(cubes, 8, 8));
        assertFalse(CubicMaps.columnLoaded(cubes, 24, 8), "the next column over has no cubes");
        assertEquals(CubeWorld.MIN_Y - 1, CubicMaps.surface(cubes, 24, 8));
    }

    @Test void aColumnWhoseSurfaceCubeIsNotLoadedIsNotRead() {
        CubeWorld cubes = world();
        ground(cubes, 0, 0, 40, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
        // The sky top says 900, whose cube is not loaded.
        int[] tops = new int[256];
        Arrays.fill(tops, 900);
        cubes.putTops(CubeKey.column(0, 0), tops);
        assertFalse(CubicMaps.columnLoaded(cubes, 8, 8));
    }

    @Test void groundInAnUnloadedCubeStopsTheWalkAsStone() {
        assertEquals(Blocks.STONE.defaultBlockState(), CubicMaps.state(CubeWorld.UNLOADED));
        assertEquals(Blocks.AIR.defaultBlockState(), CubicMaps.state(Blocks.AIR.defaultBlockState()));
    }
}
