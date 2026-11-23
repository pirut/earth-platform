package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
}
