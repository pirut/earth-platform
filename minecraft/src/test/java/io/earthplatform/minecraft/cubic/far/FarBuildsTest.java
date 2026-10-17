package io.earthplatform.minecraft.cubic.far;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FarBuildsTest {
    @BeforeAll static void registries() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    private static PalettedContainer<BlockState> states() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    @Test void countsOnlySolidBlocksAboveTheGround() {
        PalettedContainer<BlockState> cube = states();
        // Ground at y 5 everywhere: stone up to it, then grass, leaves, water and a 4x4 brick pillar to the top.
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            for (int y = 0; y <= 5; y++) cube.set(x, y, z, Blocks.STONE.defaultBlockState());
            cube.set(x, 6, z, Blocks.SHORT_GRASS.defaultBlockState());
        }
        cube.set(10, 7, 10, Blocks.OAK_LEAVES.defaultBlockState());
        cube.set(11, 7, 11, Blocks.WATER.defaultBlockState());
        for (int y = 6; y < 16; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) cube.set(x, y, z, Blocks.BRICKS.defaultBlockState());
        FarBuilds.Summary summary = FarBuilds.summarize(cube, 0, 0, 0, (x, z) -> 5);
        assertNotNull(summary);
        assertEquals(16, summary.area());
        assertEquals(6, summary.low());
        assertEquals(15, summary.tops()[0]);
        assertEquals(-1, summary.tops()[10 + 16 * 10], "leaves are not a build");
        assertEquals(Blocks.BRICKS.defaultBlockState(), summary.block());
        assertNull(FarBuilds.summarize(cube, 0, 0, 0, (x, z) -> 15), "nothing stands above ground at 15");
    }

    @Test void aHollowBuildingCountsAsItsWholeShape() {
        int level = 5;
        FarColumn column = flatColumn(0);
        // A 16x16 room in one cube column from y 129 to 159: walls, and a roof at 159 in the cube above.
        Long2ObjectOpenHashMap<FarBuilds.Summary> builds = new Long2ObjectOpenHashMap<>();
        PalettedContainer<BlockState> walls = states(), roof = states();
        for (int y = 1; y < 16; y++) for (int i = 0; i < 16; i++) {
            walls.set(i, y, 0, Blocks.STONE_BRICKS.defaultBlockState()); walls.set(i, y, 15, Blocks.STONE_BRICKS.defaultBlockState());
            walls.set(0, y, i, Blocks.STONE_BRICKS.defaultBlockState()); walls.set(15, y, i, Blocks.STONE_BRICKS.defaultBlockState());
        }
        for (int y = 0; y < 16; y++) for (int i = 0; i < 16; i++) {
            roof.set(i, y, 0, Blocks.STONE_BRICKS.defaultBlockState()); roof.set(i, y, 15, Blocks.STONE_BRICKS.defaultBlockState());
            roof.set(0, y, i, Blocks.STONE_BRICKS.defaultBlockState()); roof.set(15, y, i, Blocks.STONE_BRICKS.defaultBlockState());
        }
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) roof.set(x, 15, z, Blocks.STONE_BRICKS.defaultBlockState());
        builds.put(CubeKey.pack(0, 8, 0), FarBuilds.summarize(walls, 0, 8, 0, (x, z) -> 128));
        builds.put(CubeKey.pack(0, 9, 0), FarBuilds.summarize(roof, 0, 9, 0, (x, z) -> 128));
        // Copied 2x2 so the room fills a 32-block voxel's footprint.
        for (long key : new java.util.ArrayList<>(builds.keySet())) {
            for (int c = 1; c < 4; c++) builds.put(CubeKey.pack(c & 1, CubeKey.y(key), c >> 1), builds.get(key));
        }
        assertEquals(1, FarBuilds.fold(level, 0, 0, builds, column, FarBuildsTest::section), "voxel 4 (128-159)");
        LevelChunkSection section = column.sections.get(0);
        assertEquals(Blocks.STONE_BRICKS.defaultBlockState(), section.getBlockState(0, 4, 0), "the room reads solid, not as its walls");
        assertTrue(section.getBlockState(1, 4, 0).isAir());
    }

    @Test void keepsSummariesAcrossReopening(@TempDir Path folder) throws Exception {
        FarBuilds builds = new FarBuilds(folder.resolve("builds"));
        byte[] tops = new byte[256];
        java.util.Arrays.fill(tops, (byte) -1);
        tops[17] = 9;
        long key = CubeKey.pack(-300, 40, 5000);
        assertTrue(builds.put(key, new FarBuilds.Summary(tops, 3, Blocks.RED_WOOL.defaultBlockState())));
        assertFalse(builds.put(key, new FarBuilds.Summary(tops.clone(), 3, Blocks.RED_WOOL.defaultBlockState())), "the same again changes nothing");
        builds.flush();
        assertTrue(Files.list(folder.resolve("builds")).findAny().isPresent());

        FarBuilds reopened = new FarBuilds(folder.resolve("builds"));
        var found = reopened.in(-301, -299, 4999, 5001);
        assertEquals(1, found.size());
        assertEquals(9, found.get(key).tops()[17]);
        assertEquals(3, found.get(key).low());
        assertEquals(Blocks.RED_WOOL.defaultBlockState(), found.get(key).block());
        assertTrue(reopened.in(0, 10, 0, 10).isEmpty());

        assertTrue(reopened.put(key, null));
        reopened.flush();
        assertTrue(new FarBuilds(folder.resolve("builds")).in(-301, -299, 4999, 5001).isEmpty(), "a build taken down is gone");
    }

    /** A level-6 column (64-block voxels) of flat ground up to y 100 and a 64x64 tower from 100 to 420 standing on it. */
    @Test void standsATowerOnTheCoarseGround() {
        int level = 6;
        // Voxels 0 and 1 (blocks 0-127) are ground, lowered by 28 blocks so the top meets y 100.
        FarColumn column = flatColumn(28);
        LevelChunkSection ground = column.sections.get(0);
        // The tower covers cube columns 0..3 (blocks 0-63), as walls from y 100 to 419 with a roof.
        Long2ObjectOpenHashMap<FarBuilds.Summary> builds = new Long2ObjectOpenHashMap<>();
        for (int cy = 6; cy < 27; cy++) {
            byte[] tops = new byte[256];
            java.util.Arrays.fill(tops, (byte) (cy == 26 ? 3 : 15));
            int low = cy == 6 ? 4 : 0;
            for (int cz = 0; cz < 4; cz++) for (int cx = 0; cx < 4; cx++) builds.put(CubeKey.pack(cx, cy, cz), new FarBuilds.Summary(tops, low, Blocks.RED_WOOL.defaultBlockState()));
        }
        assertEquals(1, FarBuilds.groundTop(column, 0, 0));
        FarBuilds.fold(level, 0, 0, builds, column, FarBuildsTest::section);
        // Shown voxels k span [64k - 28, 64k + 36): the tower (100-419) fills voxels 2 to 6.
        for (int k = 2; k <= 6; k++) assertEquals(Blocks.RED_WOOL.defaultBlockState(), ground.getBlockState(0, k, 0), "voxel " + k);
        assertTrue(ground.getBlockState(0, 7, 0).isAir(), "nothing above the tower");
        assertEquals(Blocks.DIRT.defaultBlockState(), ground.getBlockState(0, 1, 0), "the ground stays");
        assertTrue(ground.getBlockState(1, 3, 0).isAir(), "only the tower's voxel column");
    }

    /** At the coarsest level a drop can be more than half a voxel, past what a short holds signed. */
    @Test void readsCoarsestDropsUnsigned() {
        FarColumn column = flatColumn(0);
        // Ground voxels 0-1 of 256 blocks, lowered by 200: the top is at y 312.
        java.util.Arrays.fill(column.drops, (short) (200 * Far.DROP_UNIT));
        assertEquals(-1, Integer.signum(column.drops[0]), "stored past a signed short");
        Long2ObjectOpenHashMap<FarBuilds.Summary> builds = new Long2ObjectOpenHashMap<>();
        byte[] tops = new byte[256];
        java.util.Arrays.fill(tops, (byte) 15);
        // 64x64 cube columns of build (a 1024-block square) from y 312 to 575: one voxel above the ground.
        for (int cy = 19; cy < 36; cy++) for (int cz = 0; cz < 64; cz++) for (int cx = 0; cx < 64; cx++) {
            builds.put(CubeKey.pack(cx, cy, cz), new FarBuilds.Summary(tops, cy == 19 ? 8 : 0, Blocks.RED_WOOL.defaultBlockState()));
        }
        assertTrue(FarBuilds.fold(8, 0, 0, builds, column, FarBuildsTest::section) > 0);
        assertEquals(Blocks.RED_WOOL.defaultBlockState(), column.sections.get(0).getBlockState(0, 2, 0), "the voxel right above the ground");
    }

    @Test void aSmallBuildStaysOutOfCoarseVoxels() {
        FarColumn column = flatColumn(0);
        byte[] tops = new byte[256];
        java.util.Arrays.fill(tops, (byte) -1);
        for (int i = 0; i < 64; i++) tops[i] = 7;
        Long2ObjectOpenHashMap<FarBuilds.Summary> builds = new Long2ObjectOpenHashMap<>();
        builds.put(CubeKey.pack(0, 8, 0), new FarBuilds.Summary(tops, 0, Blocks.OAK_PLANKS.defaultBlockState()));
        assertEquals(0, FarBuilds.fold(5, 0, 0, builds, column, FarBuildsTest::section), "a cottage is far smaller than a 32-block voxel");
    }

    private static Holder<Biome> plains;

    private static LevelChunkSection section(FarColumn column, int index) {
        while (column.sections.size() <= index) column.sections.add(null);
        if (column.sections.get(index) == null) column.sections.set(index, emptySection());
        return column.sections.get(index);
    }

    private static LevelChunkSection emptySection() {
        if (plains == null) {
            var biomes = net.minecraft.data.registries.VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
            plains = biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        }
        Holder<Biome> biome = plains;
        return new LevelChunkSection(states(), new PalettedContainer<>(new net.minecraft.core.IdMap<>() {
            @Override public int getId(Holder<Biome> value) { return 0; }
            @Override public Holder<Biome> byId(int id) { return biome; }
            @Override public int size() { return 1; }
            @Override public java.util.Iterator<Holder<Biome>> iterator() { return java.util.List.of(biome).iterator(); }
        }, biome, PalettedContainer.Strategy.SECTION_BIOMES));
    }

    /** One section of ground two voxels deep, lowered by {@code drop} blocks. */
    private static FarColumn flatColumn(int drop) {
        FarColumn column = new FarColumn();
        column.bottom = 0;
        LevelChunkSection ground = emptySection();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 0; y < 2; y++) ground.setBlockState(x, y, z, Blocks.DIRT.defaultBlockState(), false);
        column.sections.add(ground);
        java.util.Arrays.fill(column.drops, (short) (drop * Far.DROP_UNIT));
        return column;
    }
}
