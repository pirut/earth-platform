package io.earthplatform.minecraft.residency;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.EmptyLevelChunk;

/** Construction routing changes only accessors with the explicit capability, never a system flag alone. */
public final class SparseFacadeHooks {
    private SparseFacadeHooks() {}
    public static SparseCubeStorage storage(LevelHeightAccessor accessor) {
        SparseCubeStorage storage;
        if (accessor instanceof SparseFacadeAccess capability) storage = capability.earthplatform$sparseCubes();
        else if (accessor instanceof io.earthplatform.minecraft.fixture.NativeSparseLevelAccess nativeLevel
                && nativeLevel.earthplatform$nativeSparseBinding() != null) storage = nativeLevel.earthplatform$nativeSparseBinding().cubes();
        else return null;
        if (storage == null) throw new SparseFacadeException("marked level has no initialized cube owner");
        storage.checkOwner();
        if (accessor.getMinBuildHeight() != storage.minY() || accessor.getHeight() != storage.height()) {
            throw new SparseFacadeException("level height and cube-owner envelope disagree");
        }
        return storage;
    }
    public static int constructorSectionCount(LevelHeightAccessor accessor, LevelChunkSection[] supplied, ChunkAccess owner) {
        SparseCubeStorage storage = storage(accessor);
        if (storage == null) return accessor.getSectionsCount();
        if (owner.getClass() != LevelChunk.class && owner.getClass() != EmptyLevelChunk.class) {
            throw new SparseFacadeException("unsupported chunk owner type; Gate 0 permits only exact LevelChunk and EmptyLevelChunk facades");
        }
        if (supplied != null && supplied.length != 0) throw new SparseFacadeException("legacy section-array import is unsupported");
        return 0;
    }
    public static void rejectLegacy(LevelHeightAccessor accessor, String operation) {
        if (storage(accessor) != null) throw new SparseFacadeException("unsupported legacy column operation " + operation + "; no empty fallback is permitted");
    }
}
