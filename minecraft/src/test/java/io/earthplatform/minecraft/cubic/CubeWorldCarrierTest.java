package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.junit.jupiter.api.Test;

/**
 * A block entity changing in the Earth marks the carrier chunk there unsaved, as vanilla marks its
 * chunk, so data mods keep in chunk attachments and change in place is saved (Create Ore
 * Excavation's veins). A chunk not loaded is never loaded for it.
 */
class CubeWorldCarrierTest {
    /** A carrier chunk as the chunk source holds it; no level behind it is needed to mark it unsaved. */
    private static LevelChunk carrier() throws ReflectiveOperationException {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (LevelChunk) ((sun.misc.Unsafe) field.get(null)).allocateInstance(LevelChunk.class);
    }

    private static final class Chunks extends ChunkSource {
        @Nullable final LevelChunk loaded;
        final List<Boolean> loads = new ArrayList<>();
        Chunks(@Nullable LevelChunk loaded) { this.loaded = loaded; }
        @Nullable @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load) {
            loads.add(load);
            return x == 3 && z == -2 ? loaded : null;
        }
        @Override public void tick(BooleanSupplier hasTime, boolean tickChunks) {}
        @Override public String gatherStats() { return ""; }
        @Override public int getLoadedChunksCount() { return loaded == null ? 0 : 1; }
        @Override public LevelLightEngine getLightEngine() { throw new UnsupportedOperationException(); }
        @Override public BlockGetter getLevel() { throw new UnsupportedOperationException(); }
    }

    @Test void aLoadedCarrierIsMarkedUnsaved() throws ReflectiveOperationException {
        LevelChunk chunk = carrier();
        Chunks chunks = new Chunks(chunk);
        assertFalse(chunk.isUnsaved());
        CubeWorld.carrierChanged(chunks, new BlockPos(3 * 16 + 5, 1250, -2 * 16 + 9));
        assertTrue(chunk.isUnsaved());
        assertFalse(chunks.loads.contains(true), "a chunk was asked to load");
    }

    @Test void aCarrierNotLoadedIsNotLoaded() {
        Chunks chunks = new Chunks(null);
        CubeWorld.carrierChanged(chunks, new BlockPos(1000, 1250, 1000));
        assertFalse(chunks.loads.contains(true), "a chunk was asked to load");
    }

    @Test void noChunkSourceIsSafe() {
        CubeWorld.carrierChanged(null, new BlockPos(0, 0, 0));
    }
}
