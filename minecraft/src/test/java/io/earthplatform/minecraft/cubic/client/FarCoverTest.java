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

/** {@link FarMesher#evenCover} on a coarse forest: leaves lying on stone, with a grassy clearing; and on level 1, the finest far level. */
class FarCoverTest {
    private static Map<TagKey<Block>, List<Holder<Block>>> previous;

    @BeforeAll static void tags() {
        SurfacePacketFixtures.biomes();
        var blocks = BuiltInRegistries.BLOCK;
        previous = blocks.getTags().collect(Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var tags = new java.util.HashMap<>(previous);
        tags.put(BlockTags.LEAVES, blocks.stream().filter(b -> b instanceof LeavesBlock).map(blocks::wrapAsHolder).toList());
        tags.put(BlockTags.LOGS, List.of(blocks.wrapAsHolder(Blocks.OAK_LOG)));
        blocks.bindTags(tags);
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

    /** A 16 by 16 level 1 column of {@code ground} one voxel deep, with {@code speck} (if any) at voxel 7, 7. */
    private static FarMesher.Job levelOne(BlockState speck, BlockState ground) {
        LevelChunkSection section = new LevelChunkSection(SurfacePacketFixtures.biomes());
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            section.setBlockState(x, 0, z, speck != null && x == 7 && z == 7 ? speck : ground, false);
        section.recalcBlockCounts();
        return new FarMesher.Job(1, 1, 1, 0, 0, 0, 0, new LevelChunkSection[] {section}, new char[256], null, null,
                null, false, new FarLooks(), new FarFaces(), null, new float[6], new float[4]);
    }
}
