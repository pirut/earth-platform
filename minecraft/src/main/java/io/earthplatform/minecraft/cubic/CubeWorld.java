package io.earthplatform.minecraft.cubic;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * True-height block authority for one cubic Level, shared by the server and client sides.
 * The Level mixins route block reads, writes, block entities, light and height queries here,
 * so vanilla gameplay code runs unchanged at any Y. Only the owning thread may call it.
 */
public abstract class CubeWorld {
    public static final BlockState UNLOADED = Blocks.VOID_AIR.defaultBlockState();
    /** Earth's surface spans about -11 km to +9 km; leave room for digging and building. */
    public static final int MIN_Y = -16384, MAX_Y = 16383;

    protected final Level level;
    protected final Thread owner;
    protected final Long2ObjectOpenHashMap<Cube> cubes = new Long2ObjectOpenHashMap<>();
    /** Per cube column: 256 sky tops, the highest Y in each block column that stops skylight. */
    protected final Long2ObjectOpenHashMap<int[]> tops = new Long2ObjectOpenHashMap<>();
    private final Map<BlockPos, CubeTicker> tickers = new HashMap<>();
    private Cube last;

    protected CubeWorld(Level level) { this.level = level; this.owner = Thread.currentThread(); }

    public Level level() { return level; }
    public int loadedCubes() { return cubes.size(); }
    @Nullable public Cube cube(long key) { return cubes.get(key); }
    /** Owner-thread lookup with a one-entry cache; other threads see nothing loaded. */
    @Nullable public Cube cubeAtBlock(int x, int y, int z) {
        if (Thread.currentThread() != owner) return null;
        Cube cached = last;
        if (cached != null && cached.x == x >> 4 && cached.y == y >> 4 && cached.z == z >> 4) return cached;
        Cube found = cubes.get(CubeKey.ofBlock(x, y, z));
        if (found != null) last = found;
        return found;
    }
    public boolean isOwnerThread() { return Thread.currentThread() == owner; }
    public Iterable<Cube> cubes() { return cubes.values(); }

    public static boolean inRange(int y) { return y >= MIN_Y && y <= MAX_Y; }

    public BlockState getBlockState(BlockPos pos) {
        Cube cube = cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        return cube == null ? UNLOADED : cube.get(pos.getX(), pos.getY(), pos.getZ());
    }
    public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    public boolean isLoaded(BlockPos pos) { return cubeAtBlock(pos.getX(), pos.getY(), pos.getZ()) != null; }

    @Nullable public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
        Cube cube = cubeAtBlock(quartX << 2, quartY << 2, quartZ << 2);
        return cube == null ? null : cube.section.getNoiseBiome(quartX & 3, quartY & 3, quartZ & 3);
    }

    // Sky tops -----------------------------------------------------------------------------------

    /** Sky top used before anything is known about a column. */
    protected abstract int baselineTop(int x, int z);

    @Nullable public int[] columnTops(int cubeX, int cubeZ) { return tops.get(CubeKey.column(cubeX, cubeZ)); }

    protected int[] topsFor(int cubeX, int cubeZ) {
        long key = CubeKey.column(cubeX, cubeZ);
        int[] column = tops.get(key);
        if (column == null) {
            column = new int[256];
            int x0 = cubeX << 4, z0 = cubeZ << 4;
            for (int i = 0; i < 256; i++) column[i] = baselineTop(x0 + (i & 15), z0 + (i >> 4));
            tops.put(key, column);
        }
        return column;
    }

    /** Highest Y in this column that blocks skylight. */
    public int skyTop(int x, int z) {
        int[] column = tops.get(CubeKey.column(x >> 4, z >> 4));
        return column == null ? baselineTop(x, z) : column[(z & 15) << 4 | (x & 15)];
    }

    /**
     * Highest Y in this column that a vanilla heightmap of this type would hold: from the sky top
     * down through loaded cubes, so MOTION_BLOCKING_NO_LEAVES finds the ground under a canopy.
     * Stops at the sky top when the cubes below are unknown or the scan runs long (deep water).
     */
    public int heightTop(Heightmap.Types type, int x, int z) {
        int top = skyTop(x, z);
        Predicate<BlockState> counts = type.isOpaque();
        for (int y = top, limit = top - 256; y > limit; y--) {
            Cube cube = cubeAtBlock(x, y, z);
            if (cube == null) return top;
            if (counts.test(cube.get(x, y, z))) return y;
        }
        return top;
    }

    public boolean blocksSky(BlockState state, BlockPos pos) {
        return !state.isAir() && !state.propagatesSkylightDown(level, pos);
    }

    /** Called when a column's top changed. */
    protected void topChanged(int x, int z, int oldTop, int top) {}

    protected void setTop(int x, int z, int top) {
        int[] column = topsFor(x >> 4, z >> 4);
        int i = (z & 15) << 4 | (x & 15);
        int old = column[i];
        if (old != top) { column[i] = top; topChanged(x, z, old, top); }
    }

    /** First sky-blocking Y at or below {@code y}; an unloaded cube counts as solid. */
    protected int scanDown(int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int limit = y - 512; y > limit; y--) {
            Cube cube = cubeAtBlock(x, y, z);
            if (cube == null) return y;
            BlockState state = cube.get(x, y, z);
            if (blocksSky(state, pos.set(x, y, z))) return y;
        }
        return y;
    }

    /** Fold a newly present cube into its column's sky tops. */
    protected void refreshTops(Cube cube) {
        int[] column = topsFor(cube.x, cube.z);
        int x0 = cube.x << 4, y0 = cube.y << 4, z0 = cube.z << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean empty = cube.isEmpty();
        for (int i = 0; i < 256; i++) {
            int x = x0 + (i & 15), z = z0 + (i >> 4), top = column[i], highest = Integer.MIN_VALUE;
            if (!empty) for (int y = y0 + 15; y >= y0; y--) {
                if (blocksSky(cube.get(x, y, z), pos.set(x, y, z))) { highest = y; break; }
            }
            if (highest > top) setTop(x, z, highest);
            else if (top >= y0 && top <= y0 + 15) setTop(x, z, highest != Integer.MIN_VALUE ? highest : scanDown(x, y0 - 1, z));
        }
    }

    protected void updateTop(BlockPos pos, BlockState state) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ(), top = skyTop(x, z);
        boolean blocks = blocksSky(state, pos);
        if (blocks && y > top) setTop(x, z, y);
        else if (!blocks && y == top) setTop(x, z, scanDown(x, y - 1, z));
    }

    public int getBrightness(LightLayer layer, BlockPos pos) {
        return layer == LightLayer.SKY ? (pos.getY() > skyTop(pos.getX(), pos.getZ()) ? 15 : 0) : 0;
    }
    public int getRawBrightness(BlockPos pos, int skyDarken) {
        return Math.max(getBrightness(LightLayer.SKY, pos) - skyDarken, getBrightness(LightLayer.BLOCK, pos));
    }

    /** Mirrors LevelChunk.setBlockState. Returns the replaced state, or null when nothing changed. */
    @Nullable public BlockState setBlockState(BlockPos pos, BlockState state, boolean moving) {
        Cube cube = cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        if (cube == null) return null;
        int lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
        BlockState old = cube.section.setBlockState(lx, ly, lz, state);
        if (old == state) return null;
        Block block = state.getBlock();
        cube.markModified();
        updateTop(pos, state);
        onStateStored(cube, pos, old, state);
        boolean hadEntity = old.hasBlockEntity();
        if (!level.isClientSide) old.onRemove(level, pos, state, moving);
        else if (!old.is(block) && hadEntity) removeBlockEntity(pos);
        if (!cube.section.getBlockState(lx, ly, lz).is(block)) return null;
        if (!level.isClientSide && !level.captureBlockSnapshots) state.onPlace(level, pos, old, moving);
        if (state.hasBlockEntity()) {
            BlockEntity existing = getBlockEntity(pos, false);
            if (existing != null && !existing.isValidBlockState(state)) { removeBlockEntity(pos); existing = null; }
            if (existing == null) {
                BlockEntity created = ((EntityBlock) block).newBlockEntity(pos, state);
                if (created != null) addAndRegisterBlockEntity(created);
            } else {
                existing.setBlockState(state);
                updateTicker(existing);
            }
        }
        return old;
    }

    /** Hook for derived data (sky tops, meshes) after a state lands in storage. */
    protected void onStateStored(Cube cube, BlockPos pos, BlockState old, BlockState state) {}

    /**
     * Mirrors Level.markAndNotifyBlock without the chunk ticking-status gate. sendBlockUpdated still
     * runs vanilla path recomputation; its chunk broadcast is routed to {@link #blockChanged}.
     */
    public void markAndNotify(BlockPos pos, BlockState old, BlockState state, int flags, int depth) {
        BlockState now = getBlockState(pos);
        if (now != state) return;
        if (old != now) level.setBlocksDirty(pos, old, now);
        if ((flags & 2) != 0 && (!level.isClientSide || (flags & 4) == 0)) level.sendBlockUpdated(pos, old, state, flags);
        if ((flags & 1) != 0) {
            level.blockUpdated(pos, old.getBlock());
            if (!level.isClientSide && state.hasAnalogOutputSignal()) level.updateNeighbourForOutputSignal(pos, state.getBlock());
        }
        if ((flags & 16) == 0 && depth > 0) {
            int neighbourFlags = flags & -34;
            old.updateIndirectNeighbourShapes(level, pos, neighbourFlags, depth - 1);
            state.updateNeighbourShapes(level, pos, neighbourFlags, depth - 1);
            state.updateIndirectNeighbourShapes(level, pos, neighbourFlags, depth - 1);
        }
        level.onBlockStateChange(pos, old, now);
        state.onBlockStateChange(level, pos, old);
    }

    /** Replaces the chunk holder broadcast: tell watchers that this position changed. */
    public abstract void blockChanged(BlockPos pos);

    // Block entities -------------------------------------------------------------------------

    @Nullable public BlockEntity getBlockEntity(BlockPos pos, boolean create) {
        Cube cube = cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        if (cube == null) return null;
        BlockEntity entity = cube.blockEntities.get(pos);
        if (entity != null && entity.isRemoved()) { cube.blockEntities.remove(pos); entity = null; }
        if (entity == null && create) {
            BlockState state = cube.get(pos.getX(), pos.getY(), pos.getZ());
            if (state.hasBlockEntity()) {
                entity = ((EntityBlock) state.getBlock()).newBlockEntity(pos.immutable(), state);
                if (entity != null) addAndRegisterBlockEntity(entity);
            }
        }
        return entity;
    }

    public void addAndRegisterBlockEntity(BlockEntity entity) {
        if (!setBlockEntity(entity)) return;
        updateTicker(entity);
        level.addFreshBlockEntities(List.of(entity));
    }

    /** Mirrors LevelChunk.setBlockEntity. */
    public boolean setBlockEntity(BlockEntity entity) {
        BlockPos pos = entity.getBlockPos();
        Cube cube = cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        if (cube == null) return false;
        BlockState state = cube.get(pos.getX(), pos.getY(), pos.getZ());
        if (!state.hasBlockEntity()) return false;
        if (state != entity.getBlockState()) {
            if (!entity.getType().isValid(state)) return false;
            entity.setBlockState(state);
        }
        entity.setLevel(level);
        entity.clearRemoved();
        BlockEntity previous = cube.blockEntities.put(pos.immutable(), entity);
        if (previous != null && previous != entity) previous.setRemoved();
        cube.dirty = true; cube.modified = true;
        return true;
    }

    public void removeBlockEntity(BlockPos pos) {
        Cube cube = cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        if (cube != null) {
            BlockEntity removed = cube.blockEntities.remove(pos);
            if (removed != null) { removed.setRemoved(); cube.markModified(); }
        }
        CubeTicker ticker = tickers.remove(pos);
        if (ticker != null) ticker.retired = true;
    }

    /** Called from Level.blockEntityChanged so edited inventories reach the save. */
    public void blockEntityChanged(BlockPos pos) {
        Cube cube = cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        if (cube != null) cube.markModified();
    }

    @SuppressWarnings("unchecked")
    protected <T extends BlockEntity> void updateTicker(T entity) {
        BlockEntityTicker<T> ticker = entity.getBlockState().getTicker(level, (BlockEntityType<T>) entity.getType());
        BlockPos pos = entity.getBlockPos();
        CubeTicker previous = tickers.remove(pos);
        if (previous != null) previous.retired = true;
        if (ticker == null) return;
        CubeTicker bound = new CubeTicker(entity, (BlockEntityTicker<BlockEntity>) (Object) ticker);
        tickers.put(pos.immutable(), bound);
        level.addBlockEntityTicker(bound);
    }

    /** Drop a cube from memory; its block entities stop ticking. */
    protected void forget(Cube cube) {
        cubes.remove(cube.key);
        last = null;
        for (BlockEntity entity : new ArrayList<>(cube.blockEntities.values())) {
            CubeTicker ticker = tickers.remove(entity.getBlockPos());
            if (ticker != null) ticker.retired = true;
            entity.onChunkUnloaded();
            entity.setRemoved();
        }
    }

    /** Insert a ready cube and bring its block entities to life. */
    protected void adopt(Cube cube) {
        Cube previous = cubes.get(cube.key);
        if (previous != null && previous != cube) forget(previous);
        cubes.put(cube.key, cube);
        last = null;
        List<BlockEntity> fresh = new ArrayList<>(cube.blockEntities.values());
        for (BlockEntity entity : fresh) { entity.setLevel(level); entity.clearRemoved(); updateTicker(entity); }
        if (!fresh.isEmpty()) level.addFreshBlockEntities(fresh);
    }

    final class CubeTicker implements TickingBlockEntity {
        private final BlockEntity entity;
        private final BlockEntityTicker<BlockEntity> ticker;
        boolean retired;
        CubeTicker(BlockEntity entity, BlockEntityTicker<BlockEntity> ticker) { this.entity = entity; this.ticker = ticker; }
        @Override public void tick() {
            if (retired || entity.isRemoved() || !entity.hasLevel()) return;
            BlockPos pos = entity.getBlockPos();
            if (level instanceof ServerLevel server && !server.shouldTickBlocksAt(pos)) return;
            BlockState state = getBlockState(pos);
            if (entity.getType().isValid(state)) ticker.tick(level, pos, state, entity);
        }
        @Override public boolean isRemoved() { return retired || entity.isRemoved(); }
        @Override public BlockPos getPos() { return entity.getBlockPos(); }
        @Override public String getType() { return String.valueOf(BlockEntityType.getKey(entity.getType())); }
    }
}
