package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.CubeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;

/**
 * Code that reads and writes a chunk's sections itself, as YUNG's API processors do
 * ({@code ISafeWorldModifier}: desert temples' pillars, strongholds' legs, dungeons' stairs), sees
 * the structure's own blocks at any height and keeps what it writes.
 */
class StructureLevelTest {
    private static final BlockState STONE = Blocks.STONE.defaultBlockState(), QUARTZ = Blocks.QUARTZ_PILLAR.defaultBlockState();

    private static StructureLevel level(int shift, int minY, int height) {
        var biomes = SurfacePacketFixtures.biomes();
        Holder<Biome> plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        StructureLevel.Terrain terrain = new StructureLevel.Terrain() {
            @Override public BlockState block(int x, int y, int z) { return y <= 1300 ? STONE : Blocks.AIR.defaultBlockState(); }
            @Override public int ground(int x, int z) { return 1300; }
            @Override public int water(int x, int z) { return 0; }
            @Override public Holder<Biome> biome(int x, int y, int z) { return plains; }
        };
        return new StructureLevel(null, biomes, terrain, shift, minY, height, 63, new BlockPos(0, 1250 + 0, 0), CubeWorld.MIN_Y, 1L, RandomSource.create(1L));
    }

    /** As {@code ISafeWorldModifier.getBlockStateSafe}: the level's section index into the chunk it gives. */
    private static BlockState readSafe(StructureLevel level, BlockPos pos) {
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk.getSection(level.getSectionIndex(pos.getY())).getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    /** As {@code ISafeWorldModifier.setBlockStateSafe}: the chunk's own section index. */
    private static void writeSafe(StructureLevel level, BlockPos pos, BlockState state) {
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
        section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
    }

    @Test void surfaceStructuresHighUpReadAndKeepSectionWrites() {
        // A desert temple at Y 1,250 in a surface frame spanning the whole cubic world.
        StructureLevel level = level(0, CubeWorld.MIN_Y, CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1);
        BlockPos pillar = new BlockPos(37, 1255, -21);
        assertSame(STONE, readSafe(level, pillar));
        writeSafe(level, pillar, QUARTZ);
        assertEquals(QUARTZ, level.realState(37, 1255, -21), "a section write is kept as a block of the structure");
        assertEquals(QUARTZ, readSafe(level, pillar));
        assertEquals(QUARTZ, level.getBlockState(pillar));
    }

    @Test void deepStructuresBelowTheFrameOriginReadAndKeepSectionWrites() {
        // A stronghold's leg in a frame with vanilla's Y 64 a kilometre down: frame Y -40 is real Y 960.
        StructureLevel level = level(1000, -64, 384);
        BlockPos leg = new BlockPos(5, -40, 9);
        assertSame(STONE, readSafe(level, leg));
        writeSafe(level, leg, Blocks.COBBLESTONE.defaultBlockState());
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), level.realState(5, 960, 9));
        // Chunks keep their own place, so a write in one is not read back in another.
        assertSame(STONE, readSafe(level, leg.offset(16, 0, 0)));
    }

    @Test void theChunksSectionsArrayAndBlockEntitiesAreTheLevels() {
        StructureLevel level = level(0, CubeWorld.MIN_Y, CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1);
        ChunkAccess chunk = level.getChunk(2, -2);
        // Through the sections array, as code that walks a chunk's sections does.
        BlockPos at = new BlockPos(37, 1255, -21);
        LevelChunkSection[] sections = chunk.getSections();
        sections[chunk.getSectionIndex(at.getY())].setBlockState(at.getX() & 15, at.getY() & 15, at.getZ() & 15, QUARTZ, false);
        assertEquals(QUARTZ, level.realState(37, 1255, -21), "a write through getSections() is kept");
        assertEquals(QUARTZ, chunk.getBlockState(at));

        // A block entity set through the chunk is the level's.
        BlockPos chest = new BlockPos(38, 1256, -22);
        BlockState chestState = Blocks.CHEST.defaultBlockState();
        writeSafe(level, chest, chestState);
        var made = new net.minecraft.world.level.block.entity.ChestBlockEntity(chest, chestState);
        chunk.setBlockEntity(made);
        assertSame(made, level.getBlockEntity(chest), "a block entity set through the chunk is kept");
        assertSame(made, chunk.getBlockEntity(chest));
        chunk.removeBlockEntity(chest);
        org.junit.jupiter.api.Assertions.assertNull(chunk.getBlockEntity(chest), "removed through the chunk, and not made again");
        org.junit.jupiter.api.Assertions.assertNull(level.getBlockEntity(chest));

        // Heights and biomes are the level's.
        var type = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG;
        assertEquals(level.getHeight(type, 37, -21) - 1, chunk.getHeight(type, 37, -21));
        assertSame(level.getNoiseBiome(9, 313, -6), chunk.getNoiseBiome(9, 313, -6));
        assertTrue(chunk.getSection(chunk.getSectionIndex(1255)).maybeHas(state -> state == QUARTZ));
    }

    @Test void blockEntitiesInAShiftedFrameKeepTheirChangesAndSaveAtRealHeights() {
        // A stronghold's chest in a frame with vanilla's Y 0 at real Y 1,000.
        StructureLevel level = level(1000, -64, 384);
        BlockPos chest = new BlockPos(5, -30, 9);
        BlockState chestState = Blocks.CHEST.defaultBlockState();
        level.setBlock(chest, chestState, 2);
        ChunkAccess chunk = level.getChunk(0, 0);
        var made = new net.minecraft.world.level.block.entity.ChestBlockEntity(chest, chestState);
        chunk.setBlockEntity(made);
        // Filled in afterwards, as structure code sets loot on the chest it placed.
        var loot = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("chests/stronghold_corridor"));
        made.setLootTable(loot, 42L);
        var readBack = chunk.getBlockEntity(chest);
        assertSame(made, readBack, "the live block entity, not a copy");
        chunk.setBlockEntity(readBack); // read back and set again: no second shift

        java.util.List<net.minecraft.nbt.CompoundTag> tags = level.blockEntityTags();
        assertEquals(1, tags.size());
        net.minecraft.nbt.CompoundTag tag = tags.get(0);
        assertEquals(970, tag.getInt("y"), "saved at its real height");
        assertEquals(5, tag.getInt("x"));
        assertEquals("minecraft:chests/stronghold_corridor", tag.getString("LootTable"), "with the change made after it was set");
    }

    @Test void aChunkReadsBlocksAtPositionsWithinItself() {
        StructureLevel level = level(0, CubeWorld.MIN_Y, CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1);
        level.setBlock(new BlockPos(2 * 16 + 5, 1255, -2 * 16 + 7), QUARTZ, 2);
        ChunkAccess chunk = level.getChunk(2, -2);
        // Vanilla's chunks read a position within themselves, as heightmap priming passes them.
        assertEquals(QUARTZ, chunk.getBlockState(new BlockPos(5, 1255, 7)), "a chunk-local position, read in the chunk");
        assertEquals(QUARTZ, chunk.getBlockState(new BlockPos(2 * 16 + 5, 1255, -2 * 16 + 7)), "and a world one in it");
        assertSame(level.getNoiseBiome((2 * 16 >> 2) + 1, 313, (-2 * 16 >> 2) + 1), chunk.getNoiseBiome(1, 313, 1));
    }
}
