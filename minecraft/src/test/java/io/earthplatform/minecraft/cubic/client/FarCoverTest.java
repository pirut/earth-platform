package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link FarMesher#evenCover} on a coarse forest: leaves lying on stone, with a grassy clearing; on level 1, the finest far level;
 * and what it leaves alone, the blocks players built.
 */
class FarCoverTest {
    private static Map<TagKey<Block>, List<Holder<Block>>> previous;

    @BeforeAll static void tags() {
        SurfacePacketFixtures.biomes();
        var blocks = BuiltInRegistries.BLOCK;
        previous = blocks.getTags().collect(Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var tags = new java.util.HashMap<>(previous);
        tags.put(BlockTags.LEAVES, blocks.stream().filter(b -> b instanceof LeavesBlock).map(blocks::wrapAsHolder).toList());
        tags.put(BlockTags.LOGS, List.of(blocks.wrapAsHolder(Blocks.OAK_LOG)));
        // The ground tags as vanilla 1.21.1 lists them (data/minecraft/tags/block), which the test world does not load.
        tags.put(BlockTags.DIRT, holders(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MYCELIUM,
                Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS));
        tags.put(BlockTags.SAND, holders(Blocks.SAND, Blocks.RED_SAND, Blocks.SUSPICIOUS_SAND));
        tags.put(BlockTags.SNOW, holders(Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW));
        tags.put(BlockTags.ICE, holders(Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.FROSTED_ICE));
        tags.put(BlockTags.BASE_STONE_OVERWORLD, holders(Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.DEEPSLATE));
        tags.put(BlockTags.TERRACOTTA, holders(Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
                Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA, Blocks.PINK_TERRACOTTA, Blocks.GRAY_TERRACOTTA,
                Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.BLUE_TERRACOTTA, Blocks.BROWN_TERRACOTTA,
                Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.BLACK_TERRACOTTA));
        blocks.bindTags(tags);
    }

    private static List<Holder<Block>> holders(Block... blocks) {
        return java.util.Arrays.stream(blocks).map(BuiltInRegistries.BLOCK::wrapAsHolder).toList();
    }

    @AfterAll static void restore() { BuiltInRegistries.BLOCK.bindTags(previous); }

    @Test void clearingKeepsItsGrassAmongLeavesOnStone() {
        LevelChunkSection section = new LevelChunkSection(SurfacePacketFixtures.biomes());
        BlockState stone = Blocks.STONE.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            boolean clearing = x == 7 && z == 7;
            // From 16-block voxels a forest's leaves are its top voxel, on the stone voxel under it.
            section.setBlockState(x, 0, z, clearing ? grass : stone, false);
            if (!clearing) section.setBlockState(x, 1, z, Blocks.JUNGLE_LEAVES.defaultBlockState(), false);
        }
        section.recalcBlockCounts();
        LevelChunkSection[] sections = {section};
        FarMesher.Job job = new FarMesher.Job(1, 1, 5, 0, 0, 0, 0, sections, new char[256], null, null,
                null, false, new FarLooks(), new FarFaces(), null, new float[6], new float[4]);
        int[] rows = new int[256];
        for (int cell = 0; cell < 256; cell++) rows[cell] = FarMesher.groundRow(job, cell & 15, cell >> 4);
        BlockState[] cover = FarMesher.evenCover(job, rows);
        BlockState shown = cover == null || cover[7 * 16 + 7] == null ? grass : cover[7 * 16 + 7];
        assertEquals(grass, shown, "the hidden stone under the leaves turned the clearing to stone");
    }

    @Test void levelOneIsEvenedByDefault() {
        // Level 1 meets the real blocks at the render distance, so a change of this default is meant, not drifted into.
        assertEquals(1, FarMesher.COVER_FROM);
    }

    @Test void aLoneSpeckOnLevelOneTakesItsNeighboursCoverAndTheGroundStaysAsItWas() {
        BlockState stone = Blocks.STONE.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState();
        FarColumnMesh plain = new FarColumnMesh(levelOne(null, stone));
        FarColumnMesh speck = new FarColumnMesh(levelOne(grass, stone));
        int cell = 7 * 16 + 7;
        assertNotNull(speck.cover, "level 1 should be evened out");
        assertEquals(stone, speck.cover[cell], "the grass speck should show the stone around it");
        assertEquals(stone, speck.cover[0]);
        assertEquals(0, speck.groundRows[cell]);
        assertArrayEquals(plain.groundRows, speck.groundRows);
        assertArrayEquals(plain.tops, speck.tops);
        assertArrayEquals(plain.surface, speck.surface);
        assertArrayEquals(plain.normals, speck.normals);
    }

    @Test void aBuiltVoxelOnLevelOneKeepsItsMaterialInsteadOfTakingTheGroundAround() {
        // One aligned voxel of red wool in flat stone: players' paths, floor patterns and markers are a voxel or two wide.
        BlockState stone = Blocks.STONE.defaultBlockState(), wool = Blocks.RED_WOOL.defaultBlockState();
        FarColumnMesh plain = new FarColumnMesh(levelOne(null, stone));
        FarColumnMesh marker = new FarColumnMesh(levelOne(wool, stone));
        int cell = 7 * 16 + 7;
        assertNotNull(marker.cover, "the stone around it is still evened");
        assertNull(marker.cover[cell], "a null cover shows the voxel's own block: the wool");
        assertEquals(stone, marker.cover[0]);
        assertEquals(0, marker.groundRows[cell]);
        assertArrayEquals(plain.groundRows, marker.groundRows);
        assertArrayEquals(plain.tops, marker.tops);
        assertArrayEquals(plain.surface, marker.surface);
        assertArrayEquals(plain.normals, marker.normals);
    }

    @Test void aLineTwoVoxelsWideOfBuiltBlocksKeepsItsMaterialAlongItsWholeLength() {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState[] ground = new BlockState[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) ground[z * 16 + x] = z == 7 || z == 8 ? planks : grass;
        FarColumnMesh path = new FarColumnMesh(levelOne(ground));
        assertNotNull(path.cover);
        for (int x = 0; x < 16; x++) {
            assertNull(path.cover[7 * 16 + x], "the path was evened to grass at x " + x);
            assertNull(path.cover[8 * 16 + x], "the path was evened to grass at x " + x);
            assertEquals(grass, path.cover[6 * 16 + x]);
            assertEquals(grass, path.cover[9 * 16 + x]);
        }
    }

    @Test void builtBlocksDoNotVoteOnTheirNeighbours() {
        // A 2x2 patch of wool with a stone voxel beside two of its sides, nothing else standing. Were the
        // wool to vote, the stone (no stone neighbours, two wool ones) would take the wool's cover.
        BlockState stone = Blocks.STONE.defaultBlockState(), wool = Blocks.WHITE_WOOL.defaultBlockState();
        BlockState[] ground = new BlockState[256];
        for (int z = 7; z <= 8; z++) for (int x = 7; x <= 8; x++) ground[z * 16 + x] = wool;
        ground[7 * 16 + 6] = stone;
        ground[8 * 16 + 9] = stone;
        FarMesher.Job job = levelOne(ground);
        int[] rows = new int[256];
        for (int cell = 0; cell < 256; cell++) rows[cell] = FarMesher.groundRow(job, cell & 15, cell >> 4);
        BlockState[] cover = FarMesher.evenCover(job, rows);
        assertNotNull(cover);
        assertEquals(stone, cover[7 * 16 + 6], "the stone took the wool's cover");
        assertEquals(stone, cover[8 * 16 + 9], "the stone took the wool's cover");
        for (int z = 7; z <= 8; z++) for (int x = 7; x <= 8; x++) assertNull(cover[z * 16 + x], "the wool is not evened");
    }

    /** Natural ground, a mix of what the generator lays, one letter a voxel; G grass, S stone, N snow, D sand, T terracotta, V gravel, M mud, I ice, C coarse dirt. */
    private static final String[] MIXED = {
            "GGGGGGGGGGGGGGGG",
            "GGGGGGGGGGGGGGGG",
            "GGGSGGGGGGGGGGNG",
            "GGGGGGGGGDDDGGGG",
            "GGGGGSSSSDDDDGGG",
            "GGGGGSSSSDDVDGGG",
            "GGGGGSSNSDDDDGGG",
            "GGGGGSSSSGGGGGGG",
            "GGTTTGGGGGGGGMMM",
            "GGTTSTGGGGGGGMMM",
            "GGTTTTGGGGGGGGMG",
            "GGGGGGGGCCCGGGGG",
            "GGGGGGGGCGCGGGGG",
            "GIIIGGGGCCCGGGGG",
            "GIIIGGGGGGGGGGGG",
            "GGGGGGGGGGGGGGGG"};
    /** What {@link #MIXED} came out as on bc8266a8, before built blocks were exempted: the evening of every ground block. */
    private static final String[] MIXED_EVENED = {
            "GGGGGGGGGGGGGGGG",
            "GGGGGGGGGGGGGGGG",
            "GGGGGGGGGGGGGGGG",
            "GGGGGGGGGDDDGGGG",
            "GGGGGGSSSDDDDGGG",
            "GGGGGSSSSDDDDGGG",
            "GGGGGSSSSDDDGGGG",
            "GGGGGSSSGGGGGGGG",
            "GGGTTGGGGGGGGGMM",
            "GGGTTGGGGGGGGGMM",
            "GGGTTGGGGGGGGGGG",
            "GGGGGGGGGGGGGGGG",
            "GGGGGGGGGCGGGGGG",
            "GGGGGGGGGGGGGGGG",
            "GGGGGGGGGGGGGGGG",
            "GGGGGGGGGGGGGGGG"};

    @Test void naturalGroundIsEvenedExactlyAsBeforeAtEveryLevel() {
        BlockState[] ground = parse(MIXED);
        for (int level : new int[] {1, 2, 4}) {
            FarMesher.Job job = new FarMesher.Job(1, 1, level, 0, 0, 0, 0, new LevelChunkSection[] {section(ground)}, new char[256], null, null,
                    null, false, new FarLooks(), new FarFaces(), null, new float[6], new float[4]);
            int[] rows = new int[256];
            for (int cell = 0; cell < 256; cell++) rows[cell] = FarMesher.groundRow(job, cell & 15, cell >> 4);
            BlockState[] cover = FarMesher.evenCover(job, rows);
            assertNotNull(cover);
            assertEquals(String.join("\n", MIXED_EVENED), String.join("\n", render(cover)), "level " + level);
        }
    }

    @Test void everyBlockTheGeneratorLaysAsGroundIsNatural() {
        for (var ground : io.earthplatform.geography.globe.GroundPalette.Ground.values()) {
            Block block = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace(ground.id()));
            assertTrue(FarMesher.natural(block.defaultBlockState()), ground.id());
        }
        for (Block block : List.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.PODZOL, Blocks.MUD, Blocks.MOSS_BLOCK, Blocks.CLAY, Blocks.SNOW_BLOCK,
                Blocks.POWDER_SNOW, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.BLACKSTONE, Blocks.GRANITE, Blocks.GREEN_TERRACOTTA,
                Blocks.CYAN_TERRACOTTA, Blocks.PINK_TERRACOTTA, Blocks.PURPLE_TERRACOTTA, Blocks.RED_MUSHROOM_BLOCK, Blocks.BROWN_MUSHROOM_BLOCK))
            assertTrue(FarMesher.natural(block.defaultBlockState()), block.getDescriptionId());
        for (Block block : List.of(Blocks.RED_WOOL, Blocks.OAK_PLANKS, Blocks.BRICKS, Blocks.WHITE_CONCRETE, Blocks.GLASS, Blocks.COBBLESTONE,
                Blocks.STONE_BRICKS, Blocks.GOLD_BLOCK, Blocks.WHITE_GLAZED_TERRACOTTA))
            assertFalse(FarMesher.natural(block.defaultBlockState()), block.getDescriptionId());
    }

    private static BlockState[] parse(String[] rows) {
        BlockState[] ground = new BlockState[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) ground[z * 16 + x] = switch (rows[z].charAt(x)) {
            case 'G' -> Blocks.GRASS_BLOCK.defaultBlockState();
            case 'S' -> Blocks.STONE.defaultBlockState();
            case 'N' -> Blocks.SNOW_BLOCK.defaultBlockState();
            case 'D' -> Blocks.SAND.defaultBlockState();
            case 'T' -> Blocks.TERRACOTTA.defaultBlockState();
            case 'V' -> Blocks.GRAVEL.defaultBlockState();
            case 'M' -> Blocks.MUD.defaultBlockState();
            case 'I' -> Blocks.PACKED_ICE.defaultBlockState();
            case 'C' -> Blocks.COARSE_DIRT.defaultBlockState();
            default -> throw new IllegalArgumentException(rows[z]);
        };
        return ground;
    }

    private static String[] render(BlockState[] cover) {
        String[] rows = new String[16];
        for (int z = 0; z < 16; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < 16; x++) {
                BlockState state = cover[z * 16 + x];
                row.append(state == null ? '.' : state.is(Blocks.GRASS_BLOCK) ? 'G' : state.is(Blocks.STONE) ? 'S' : state.is(Blocks.SNOW_BLOCK) ? 'N'
                        : state.is(Blocks.SAND) ? 'D' : state.is(Blocks.TERRACOTTA) ? 'T' : state.is(Blocks.GRAVEL) ? 'V' : state.is(Blocks.MUD) ? 'M'
                        : state.is(Blocks.PACKED_ICE) ? 'I' : state.is(Blocks.COARSE_DIRT) ? 'C' : '?');
            }
            rows[z] = row.toString();
        }
        return rows;
    }

    /** A 16 by 16 level 1 column of {@code ground} one voxel deep, with {@code speck} (if any) at voxel 7, 7. */
    private static FarMesher.Job levelOne(BlockState speck, BlockState ground) {
        BlockState[] cells = new BlockState[256];
        for (int cell = 0; cell < 256; cell++) cells[cell] = speck != null && cell == 7 * 16 + 7 ? speck : ground;
        return levelOne(cells);
    }

    /** A 16 by 16 level 1 column one voxel deep, a block a voxel, null for none. */
    private static FarMesher.Job levelOne(BlockState[] cells) {
        return new FarMesher.Job(1, 1, 1, 0, 0, 0, 0, new LevelChunkSection[] {section(cells)}, new char[256], null, null,
                null, false, new FarLooks(), new FarFaces(), null, new float[6], new float[4]);
    }

    private static LevelChunkSection section(BlockState[] cells) {
        LevelChunkSection section = new LevelChunkSection(SurfacePacketFixtures.biomes());
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            if (cells[z * 16 + x] != null) section.setBlockState(x, 0, z, cells[z * 16 + x], false);
        section.recalcBlockCounts();
        return section;
    }
}
