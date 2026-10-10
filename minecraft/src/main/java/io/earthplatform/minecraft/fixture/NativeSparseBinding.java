package io.earthplatform.minecraft.fixture;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.residency.SparseCubeStorage;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Authoritative fixture binding used by native cache lookups. No player, renderer or tick integration. */
public final class NativeSparseBinding {
    public static final CubePos LOWER = new CubePos(0, -688, 0), UPPER = new CubePos(0, 553, 0);
    public static final Set<CubePos> ALLOWED_CUBES = Set.of(LOWER, UPPER);
    private final Level level;
    private final EnginePaletteContext palette;
    private final SparseCubeStorage cubes;
    private final Map<Long, LevelChunk> facades = new HashMap<>();
    private boolean closing;
    NativeSparseBinding(Level level) {
        this.level = level;
        palette = EnginePaletteContext.create(level.registryAccess().registryOrThrow(Registries.BIOME));
        cubes = new SparseCubeStorage(palette, SparseFixtureDimensions.MIN_Y, SparseFixtureDimensions.HEIGHT, 2, 2, Set.of());
    }
    public static NativeSparseBinding find(Level level) {
        return ((NativeSparseLevelAccess) level).earthplatform$nativeSparseBinding();
    }
    public SparseCubeStorage cubes() { checkOwner(); return cubes; }
    public EnginePaletteContext palette() { checkOwner(); return palette; }
    public Level level() { return level; }
    public int facadeCount() { checkOwner(); return facades.size(); }
    public LevelChunk facade(int x, int z, ChunkStatus status) {
        checkOpen();
        if (status != ChunkStatus.FULL) throw new SparseFacadeException("generation/status scheduling is unsupported in the native fixture");
        if (x != 0 || z != 0) throw new SparseFacadeException("unknown column outside the explicit two-cube fixture; no loading or air fallback");
        return facades.computeIfAbsent(ChunkPos.asLong(x, z), key -> new LevelChunk(level, new ChunkPos(x, z)));
    }
    public boolean isLoaded(BlockPos position) {
        checkOpen();
        if (level.isOutsideBuildHeight(position)) return false;
        CubePos cube = CubePos.fromBlock(position.getX(), position.getY(), position.getZ());
        return ALLOWED_CUBES.contains(cube) && cubes.presence(cube) == SparseCubeStorage.Presence.RESIDENT;
    }
    public boolean hasAnyResident(int x, int z) {
        checkOpen();
        if (x != 0 || z != 0) return false;
        return cubes.presence(LOWER) == SparseCubeStorage.Presence.RESIDENT || cubes.presence(UPPER) == SparseCubeStorage.Presence.RESIDENT;
    }
    public void reject(String operation) { checkOwner(); throw new SparseFacadeException("native fixture does not support " + operation); }
    public void checkOpen() { checkOwner(); if (closing) throw new SparseFacadeException("native fixture is closing"); }
    public void checkOwner() { cubes.checkOwner(); }
    /** Explicit disposable-fixture teardown only. It is not a save or a general world-unload implementation. */
    public void beginDisposableClose() { checkOwner(); closing = true; }
    public boolean isClosing() { checkOwner(); return closing; }
}
