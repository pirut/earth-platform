package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.function.IntBinaryOperator;
import java.util.function.Function;
import javax.annotation.Nullable;
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

    /** A cubic world that counts its block reads, which is what a map's walk costs the server thread. */
    private static final class CountingWorld extends CubeWorld {
        int reads;

        CountingWorld() { super(null); }

        @Override protected int baselineTop(int x, int z) { return 0; }
        @Override public void blockChanged(BlockPos pos) {}
        @Override public BlockState getBlockState(BlockPos pos) { reads++; return super.getBlockState(pos); }
    }

    /** The chunks a level leaves to real chunks. */
    private interface Kept { boolean test(int chunkX, int chunkZ); }

    /** A level for {@link CubicMaps#pixelLoaded}: these cubes, with the chunks {@code kept} left to real chunks. */
    private static CubicLevel levelOf(@Nullable CubeWorld cubes, Kept kept) {
        return new CubicLevel() {
            @Override public CubeWorld earthplatform$cubes() { return cubes; }
            @Override public void earthplatform$setCubes(CubeWorld c) {}
            @Override public void earthplatform$keepInChunks(int minX, int minZ, int maxX, int maxZ) {}
            @Override public boolean earthplatform$keptInChunks(int chunkX, int chunkZ) { return kept.test(chunkX, chunkZ); }
        };
    }

    private static final int VANILLA_FLOOR = 0;

    /** What one column of the map reads, as vanilla's walk takes it. */
    private record Sample(MapColor color, int top, int depth) {}

    /**
     * Vanilla's {@code MapItem.update} column walk, step for step, with the reads, the floors and the
     * water cap that {@code CubicMapItemMixin} gives it. {@code cubes} null is a column a real chunk
     * owns (a Sable plot), read through {@code chunkHeight} and {@code chunkBlock}.
     */
    private static Sample sample(@Nullable CubeWorld cubes, IntBinaryOperator chunkHeight, Function<BlockPos, BlockState> chunkBlock, int x, int z) {
        int k3 = (cubes == null ? chunkHeight.applyAsInt(x, z) : CubicMaps.surface(cubes, x, z)) + 1;
        int floor = CubicMaps.floor(cubes, VANILLA_FLOOR);
        if (k3 <= floor + 1) return new Sample(Blocks.BEDROCK.defaultBlockState().getMapColor(null, BlockPos.ZERO), k3, 0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), below = new BlockPos.MutableBlockPos();
        BlockState state;
        do {
            pos.set(x, --k3, z);
            state = cubes == null ? chunkBlock.apply(pos) : CubicMaps.state(cubes.getBlockState(pos));
        } while (state.getMapColor(null, pos) == MapColor.NONE && k3 > floor);
        int depth = 0;
        if (k3 > floor && !state.getFluidState().isEmpty()) {
            int l3 = k3 - 1, waterFloor = CubicMaps.waterFloor(cubes, VANILLA_FLOOR, pos.getY());
            BlockState water;
            do {
                below.set(x, l3--, z);
                water = cubes == null ? chunkBlock.apply(below) : CubicMaps.waterState(cubes.getBlockState(below));
                depth++;
            } while (l3 > waterFloor && !water.getFluidState().isEmpty());
        }
        return new Sample(state.getMapColor(null, pos), k3, depth);
    }

    private static Sample sample(CubeWorld cubes, int x, int z) {
        return sample(cubes, (a, b) -> { throw new AssertionError("a cubic column is not read from a chunk"); },
                p -> { throw new AssertionError("a cubic column is not read from a chunk"); }, x, z);
    }

    private static MapColor mapColorAt(CubeWorld cubes, int x, int z) { return sample(cubes, x, z).color(); }

    /** Loads the cube {@code cubeY} of a column, filled with {@code block} up to and including {@code topY} (a world Y; the rest air; {@code Integer.MIN_VALUE} for all air). */
    private static void fill(CubeWorld cubes, int cubeX, int cubeY, int cubeZ, int topY, BlockState block) {
        LevelChunkSection section = new LevelChunkSection(biomes);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int ly = 0; ly < 16; ly++) {
            if ((cubeY << 4) + ly <= topY) section.setBlockState(x, ly, z, block);
        }
        cubes.adopt(new Cube(cubeX, cubeY, cubeZ, section));
    }

    private static void tops(CubeWorld cubes, int cubeX, int cubeZ, int y) {
        int[] tops = new int[256];
        Arrays.fill(tops, y);
        cubes.putTops(CubeKey.column(cubeX, cubeZ), tops);
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
        tops(cubes, 0, 0, 900);
        assertFalse(CubicMaps.columnLoaded(cubes, 8, 8));
    }

    @Test void groundInAnUnloadedCubeStopsTheWalkAsStone() {
        assertEquals(Blocks.STONE.defaultBlockState(), CubicMaps.state(CubeWorld.UNLOADED));
        assertEquals(Blocks.AIR.defaultBlockState(), CubicMaps.state(Blocks.AIR.defaultBlockState()));
    }

    // Finding 3: the surface is the highest block that is not air, not the highest that stops skylight.

    @Test void stainedGlassRoofIsTheSurfaceAndGivesItsColor() {
        CubeWorld cubes = world();
        ground(cubes, 0, 0, 40, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        BlockPos roofPos = new BlockPos(8, 41, 8);
        BlockState roof = Blocks.RED_STAINED_GLASS.defaultBlockState();
        Cube cube = cubes.cubeAtBlock(8, 41, 8);
        cube.section.setBlockState(8, 41 & 15, 8, roof);
        cubes.rewritten(cube);
        assertTrue(roof.propagatesSkylightDown(null, roofPos), "skylight goes through it, so the sky top stays below");
        assertEquals(40, cubes.skyTop(8, 8));
        assertEquals(41, CubicMaps.surface(cubes, 8, 8));
        assertNotEquals(MapColor.NONE, roof.getMapColor(null, roofPos));
        assertEquals(roof.getMapColor(null, roofPos), mapColorAt(cubes, 8, 8));
        assertEquals(MapColor.GRASS, mapColorAt(cubes, 9, 9), "the next column has no roof");
    }

    @Test void aCanopyInTheCubeAboveTheSkyTopIsFoundToo() {
        CubeWorld cubes = world();
        ground(cubes, 0, 0, 40, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        // Glass at Y 70, two cubes up; the cube between is loaded and empty.
        fill(cubes, 0, 3, 0, Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
        fill(cubes, 0, 4, 0, Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
        BlockState roof = Blocks.BLUE_STAINED_GLASS.defaultBlockState();
        cubes.cubeAtBlock(8, 70, 8).section.setBlockState(8, 70 & 15, 8, roof);
        assertEquals(70, CubicMaps.surface(cubes, 8, 8));
        assertEquals(roof.getMapColor(null, new BlockPos(8, 70, 8)), mapColorAt(cubes, 8, 8));
    }

    @Test void theSearchAboveTheSkyTopStopsAtTheFirstCubeNotLoaded() {
        CubeWorld cubes = world();
        ground(cubes, 0, 0, 40, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        // Cube 3 (Y 48..63) is not loaded, so a glass block in cube 4 is not reached, and costs no read.
        fill(cubes, 0, 4, 0, Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
        cubes.cubeAtBlock(8, 70, 8).section.setBlockState(8, 70 & 15, 8, Blocks.RED_STAINED_GLASS.defaultBlockState());
        assertEquals(40, CubicMaps.surface(cubes, 8, 8));
    }

    // Finding 2: a pixel is drawn or skipped by its own columns, not by the middle of its chunk.

    /** Chunk (0,0): columns x 0..7 have their surface cube loaded, x 8..15 (the chunk's middle among them) do not. */
    private static CubeWorld halfLoadedChunk() {
        CubeWorld cubes = world();
        ground(cubes, 0, 0, 40, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState());
        int[] tops = new int[256];
        for (int i = 0; i < 256; i++) tops[i] = (i & 15) < 8 ? 40 : 900;
        cubes.putTops(CubeKey.column(0, 0), tops);
        return cubes;
    }

    @Test void aLoadedEdgePixelIsDrawnWhoseChunkCenterIsNotLoaded() {
        CubeWorld cubes = halfLoadedChunk();
        CubicLevel level = levelOf(cubes, (cx, cz) -> false);
        assertFalse(CubicMaps.columnLoaded(cubes, 8, 8), "the chunk center, which the old gate asked, is not loaded");
        assertTrue(CubicMaps.pixelLoaded(level, 0, 0, 1));
        assertTrue(CubicMaps.pixelLoaded(level, 4, 4, 4), "a 4-block pixel at the loaded edge");
        assertFalse(CubicMaps.pixelLoaded(level, 8, 4, 4), "the pixel at the middle of the chunk is not");
        assertFalse(CubicMaps.pixelLoaded(level, 12, 12, 4));
    }

    @Test void aPixelWithOneColumnNotLoadedIsSkippedWholeNotAveragedWithBedrock() {
        CubeWorld cubes = halfLoadedChunk();
        CubicLevel level = levelOf(cubes, (cx, cz) -> false);
        // Four columns across the loaded/unloaded border, scale 2: one of them is not loaded.
        assertFalse(CubicMaps.pixelLoaded(level, 4, 0, 8));
        assertTrue(CubicMaps.pixelLoaded(level, 4, 0, 4));
        // The skipped pixel would have averaged in the bedrock, and its neighbours' shading with it:
        double bedrockHeight = CubicMaps.surface(cubes, 8, 0) + 1;
        assertEquals(CubeWorld.MIN_Y, bedrockHeight, "what the old read gave an unloaded column");
        double drawn = 0;
        for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) drawn += (sample(cubes, 4 + dx, dz).top()) / 16.0;
        assertEquals(40, drawn, 1e-9, "a drawn pixel averages only real surfaces");
    }

    @Test void columnsKeptInRealChunksDoNotHoldAPixelBack() {
        CubeWorld cubes = world();
        ground(cubes, 0, 0, 40, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
        // Chunk (1,0) is a Sable plot: no cubes there, but its columns are read from their real chunk.
        CubicLevel level = levelOf(cubes, (cx, cz) -> cx == 1 && cz == 0);
        assertTrue(CubicMaps.pixelLoaded(level, 16, 0, 4));
        assertFalse(CubicMaps.pixelLoaded(level, 32, 0, 4), "a plain unloaded column still holds it back");
        assertTrue(CubicMaps.pixelLoaded(levelOf(null, (cx, cz) -> false), 32, 0, 4), "a level with no cubes runs vanilla");
    }

    /** What a skipped pixel costs the server thread: no allocation (the old gate built an EmptyLevelChunk each time). */
    @Test void skippingAPixelAllocatesNothing() {
        CubeWorld cubes = halfLoadedChunk();
        CubicLevel level = levelOf(cubes, (cx, cz) -> false);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        int hits = 0;
        for (int i = 0; i < 20_000; i++) if (CubicMaps.pixelLoaded(level, 8 + (i & 3), 0, 4)) hits++;   // warm up
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 100_000; i++) {
            if (CubicMaps.pixelLoaded(level, 8 + (i & 3), 0, 4)) hits++;
            if (CubicMaps.pixelLoaded(level, 200, 200, 1)) hits++;
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(0, hits);
        assertTrue(allocated < 64 * 1024, "200 000 skipped pixels allocated " + allocated + " bytes");
    }

    // Finding 4: water over a cube that is not loaded is not shallow.

    @Test void waterOverAnUnloadedCubeReadsDeepNotShallow() {
        CubeWorld cubes = world();
        // Water Y 48..50 fills the bottom of the loaded cube; the cube under it (Y 32..47) is not loaded.
        fill(cubes, 0, 3, 0, 50, Blocks.WATER.defaultBlockState());
        tops(cubes, 0, 0, 50);
        Sample sample = sample(cubes, 5, 5);
        assertEquals(MapColor.WATER, sample.color());
        assertTrue(sample.depth() > 9, "unknown depth reads as deep, as the darkest blue starts at nine: " + sample.depth());
        // What the old read gave: the unloaded cube counted as stone, a sea three blocks deep.
        assertEquals(Blocks.STONE.defaultBlockState(), CubicMaps.state(CubeWorld.UNLOADED));
    }

    @Test void waterOverLoadedGroundKeepsItsRealDepth() {
        CubeWorld cubes = world();
        fill(cubes, 0, 3, 0, 50, Blocks.WATER.defaultBlockState());
        LevelChunkSection sand = new LevelChunkSection(biomes);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int ly = 0; ly < 16; ly++) sand.setBlockState(x, ly, z, Blocks.SAND.defaultBlockState());
        cubes.adopt(new Cube(0, 2, 0, sand));
        tops(cubes, 0, 0, 50);
        assertEquals(3, sample(cubes, 5, 5).depth(), "two more blocks of water, then the sand that ends it");
    }

    // Finding 1: bounded scans, counted in reads.

    @Test void anEmptyPlotColumnIsReadWithVanillasFloorNotFromTheBottomOfTheWorld() {
        int[] reads = {0};
        // A Sable plot whose chunk's heightmap says 20, over air.
        Sample sample = sample(null, (x, z) -> 20, pos -> { reads[0]++; return Blocks.AIR.defaultBlockState(); }, 4, 4);
        assertEquals(VANILLA_FLOOR, CubicMaps.floor(null, VANILLA_FLOOR));
        assertEquals(21, reads[0], "Y 20 down to the level's floor and no further");
        assertEquals(MapColor.NONE, sample.color());
        assertEquals(CubeWorld.MIN_Y, CubicMaps.floor(world(), VANILLA_FLOOR), "a column of the cubes keeps the bottom of the world");
    }

    @Test void aPlotColumnWhoseHeightmapIsEmptyReadsNothing() {
        int[] reads = {0};
        sample(null, (x, z) -> 0, pos -> { reads[0]++; return Blocks.AIR.defaultBlockState(); }, 4, 4);
        assertEquals(0, reads[0], "vanilla goes straight to bedrock; the old floor walked 16 000 blocks of air");
    }

    @Test void deepWaterIsReadNoFurtherThanTheCap() {
        CountingWorld cubes = new CountingWorld();
        // An ocean surface at Y 0 over water as far down as the cubes go: a kilometre of it loaded.
        for (int cubeY = -64; cubeY <= 0; cubeY++) fill(cubes, 0, cubeY, 0, cubeY == 0 ? 0 : cubeY * 16 + 15, Blocks.WATER.defaultBlockState());
        tops(cubes, 0, 0, 0);
        Sample sample = sample(cubes, 3, 3);
        assertEquals(MapColor.WATER, sample.color());
        assertTrue(sample.depth() <= CubicMaps.WATER_SCAN_LIMIT && sample.depth() >= CubicMaps.WATER_SCAN_LIMIT - 2,
                "the water scan stops at the cap: " + sample.depth());
        assertTrue(cubes.reads <= CubicMaps.WATER_SCAN_LIMIT + 2, "reads " + cubes.reads);
    }

    @Test void aCubicColumnsWalkStopsAtTheFirstCubeNotLoaded() {
        CountingWorld cubes = new CountingWorld();
        // Clear glass has no map color, so the walk goes on below it, through the loaded air to the cube that is missing.
        fill(cubes, 0, 5, 0, Integer.MIN_VALUE, Blocks.AIR.defaultBlockState());
        cubes.cubeAtBlock(2, 85, 2).section.setBlockState(2, 85 & 15, 2, Blocks.GLASS.defaultBlockState());
        tops(cubes, 0, 0, 85);
        Sample sample = sample(cubes, 2, 2);
        assertEquals(MapColor.STONE, sample.color(), "the unloaded cube counts as stone");
        assertTrue(cubes.reads <= 16, "reads " + cubes.reads);
    }
}
