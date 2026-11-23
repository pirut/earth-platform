package io.earthplatform.minecraft.cubic.gen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * A world-generation level that vanilla structure and feature code writes into, as it writes
 * into a chunk region during normal generation. It reads the cubic world's terrain as generated
 * (before anything is placed on it) plus its own writes, and records every block, block entity
 * and entity written, for the cubes they fall in to copy.
 *
 * <p>Vanilla code sees the world in a frame: positions it uses are {@code shift} blocks below
 * the real ones, so structures whose heights vanilla fixes (an ancient city at Y -27) land at
 * the same depth under this world's ground.
 */
final class StructureLevel implements WorldGenLevel {
    /** What the generator gives about the world as generated. Real coordinates. */
    interface Terrain {
        BlockState block(int x, int y, int z);
        int ground(int x, int z);
        int water(int x, int z);
        Holder<Biome> biome(int x, int y, int z);
    }

    private final ServerLevel level;
    private final Terrain terrain;
    final int shift, minY, height, seaLevel;
    private final long seed;
    private final RandomSource random;
    private final BiomeManager biomes;
    private final net.minecraft.core.Registry<Biome> biomeRegistry;
    /** The chunks code asks this level for (see {@link View}), by chunk position, and the one placeholder section they are made with. */
    private final Long2ObjectOpenHashMap<View> views = new Long2ObjectOpenHashMap<>();
    private final net.minecraft.world.level.chunk.LevelChunkSection placeholder;
    private final LevelLightEngine light;
    /** Written blocks by packed real position, block entities likewise, and entity tags at real positions. */
    final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
    /** Block entities as code made them, at the positions it sees (frame ones), by packed real position. */
    private final Long2ObjectOpenHashMap<BlockEntity> blockEntities = new Long2ObjectOpenHashMap<>();
    /** Where a block entity was removed, so none is made again there for the block. */
    private final LongOpenHashSet removedBlockEntities = new LongOpenHashSet();
    final List<CompoundTag> entities = new ArrayList<>();
    final LongOpenHashSet postProcess = new LongOpenHashSet();
    /**
     * Keys pack positions relative to this point: x and z in 16 bits each, y in 32, since a packed
     * BlockPos only holds y -2048..2047 and structures sit far higher (or reach kilometres down).
     */
    private final int anchorX, anchorY, anchorZ;
    /** Writes below this real height are dropped: an ocean monument's legs would otherwise reach a seabed kilometres down. */
    private final int lowest;

    StructureLevel(ServerLevel level, Terrain terrain, int shift, int minY, int height, int seaLevel, BlockPos anchor, int lowest, long seed, RandomSource random) {
        this(level, level.registryAccess().registryOrThrow(Registries.BIOME), terrain, shift, minY, height, seaLevel, anchor, lowest, seed, random);
    }

    StructureLevel(@Nullable ServerLevel level, net.minecraft.core.Registry<Biome> biomeRegistry, Terrain terrain, int shift, int minY, int height, int seaLevel,
                   BlockPos anchor, int lowest, long seed, RandomSource random) {
        this.level = level;
        this.biomeRegistry = biomeRegistry;
        this.placeholder = new net.minecraft.world.level.chunk.LevelChunkSection(biomeRegistry);
        this.anchorX = anchor.getX(); this.anchorY = anchor.getY(); this.anchorZ = anchor.getZ();
        this.lowest = lowest;
        this.terrain = terrain;
        this.shift = shift; this.minY = minY; this.height = height; this.seaLevel = seaLevel;
        this.seed = seed;
        this.random = random;
        this.biomes = new BiomeManager(this, BiomeManager.obfuscateSeed(seed));
        this.light = new LevelLightEngine(new net.minecraft.world.level.chunk.LightChunkGetter() {
            @Override public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) { return null; }
            @Override public net.minecraft.world.level.BlockGetter getLevel() { return StructureLevel.this; }
        }, false, false);
    }

    long key(int x, int y, int z) { return (long) (y - anchorY) << 32 | (long) ((x - anchorX) & 0xFFFF) << 16 | (z - anchorZ) & 0xFFFF; }
    long key(BlockPos pos) { return key(pos.getX(), pos.getY(), pos.getZ()); }
    /** The real position a key stands for. */
    BlockPos at(long key) { return new BlockPos((short) (key >>> 16) + anchorX, (int) (key >> 32) + anchorY, (short) key + anchorZ); }

    BlockPos real(BlockPos pos) { return shift == 0 ? pos : pos.above(shift); }

    /** Tags of the block entities written, at their real positions. */
    List<CompoundTag> blockEntityTags() {
        List<CompoundTag> tags = new ArrayList<>();
        for (var entry : blockEntities.long2ObjectEntrySet()) {
            BlockEntity entity = entry.getValue();
            BlockState state = blocks.get(entry.getLongKey());
            if (state == null || !state.hasBlockEntity()) continue;
            CompoundTag tag = entity.saveWithFullMetadata(registryAccess());
            BlockPos real = at(entry.getLongKey()); // the block entity itself sits where the frame put it
            tag.putInt("x", real.getX()); tag.putInt("y", real.getY()); tag.putInt("z", real.getZ());
            tags.add(tag);
        }
        return tags;
    }

    // Blocks -------------------------------------------------------------------------------------

    /** The block at a real position, written or as generated. */
    BlockState realState(int x, int y, int z) {
        BlockState written = blocks.get(key(x, y, z));
        return written != null ? written : terrain.block(x, y, z);
    }

    /** Writes a plain block (no block entity) at a real position. */
    void putReal(int x, int y, int z, BlockState state) { blocks.put(key(x, y, z), state); }

    /** Settles the shapes of blocks marked for it (fences, stairs, panes) against their neighbours, as a chunk does on loading. */
    void postProcessAll() {
        for (long at : postProcess.toLongArray()) {
            BlockPos pos = at(at).below(shift);
            BlockState state = getBlockState(pos), settled = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, this, pos);
            if (settled != state) blocks.put(at, settled);
        }
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        BlockPos r = real(pos);
        BlockState written = blocks.get(key(r));
        return written != null ? written : terrain.block(r.getX(), r.getY(), r.getZ());
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        BlockPos r = real(pos);
        if (r.getY() < lowest) return false;
        long key = key(r);
        BlockState old = blocks.get(key);
        if (old == null) old = terrain.block(r.getX(), r.getY(), r.getZ());
        blocks.put(key, state);
        if (old.hasBlockEntity() && !state.is(old.getBlock())) blockEntities.remove(key);
        if (state.hasBlockEntity()) {
            BlockEntity existing = blockEntities.get(key);
            if (existing == null || !existing.isValidBlockState(state)) {
                removedBlockEntities.remove(key);
                BlockEntity created = ((EntityBlock) state.getBlock()).newBlockEntity(pos.immutable(), state);
                if (created != null) blockEntities.put(key, created); else blockEntities.remove(key);
            } else existing.setBlockState(state);
        }
        return true;
    }
    @Override public boolean removeBlock(BlockPos pos, boolean moving) { return setBlock(pos, Blocks.AIR.defaultBlockState(), 3); }
    @Override public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int recursionLeft) {
        return !getBlockState(pos).isAir() && setBlock(pos, Blocks.AIR.defaultBlockState(), 3, recursionLeft);
    }
    @Override public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) { return predicate.test(getBlockState(pos)); }
    @Override public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) { return predicate.test(getFluidState(pos)); }

    /**
     * Keeps a block entity some code made itself (through a chunk's own setBlockEntity), at the real
     * position of the block it stands at, as one this level makes for a block written.
     */
    void putBlockEntity(BlockEntity entity) {
        long key = key(real(entity.getBlockPos()));
        removedBlockEntities.remove(key);
        blockEntities.put(key, entity); // the live one, so later changes to it are kept; moved to real heights when saved
    }

    void removeBlockEntityAt(BlockPos pos) {
        long key = key(real(pos));
        blockEntities.remove(key);
        removedBlockEntities.add(key);
    }

    @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) {
        BlockPos r = real(pos);
        long key = key(r);
        BlockEntity entity = blockEntities.get(key);
        if (entity != null) return entity;
        BlockState state = blocks.get(key);
        if (state == null || !state.hasBlockEntity()) return null;
        if (removedBlockEntities.contains(key)) return null;
        entity = ((EntityBlock) state.getBlock()).newBlockEntity(pos.immutable(), state);
        if (entity != null) blockEntities.put(key, entity);
        return entity;
    }

    @Override public int getHeight(Heightmap.Types type, int x, int z) {
        int ground = terrain.ground(x, z), water = terrain.water(x, z);
        int top = switch (type) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> ground;
            default -> Math.max(ground, water);
        };
        return top + 1 - shift;
    }

    // Entities -----------------------------------------------------------------------------------

    @Override public boolean addFreshEntity(Entity entity) {
        if (shift != 0) entity.setPos(entity.getX(), entity.getY() + shift, entity.getZ());
        CompoundTag tag = new CompoundTag();
        if (entity.saveAsPassenger(tag)) entities.add(tag);
        return true;
    }
    @Override public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) { return Collections.emptyList(); }
    @Override public List<Entity> getEntities(@Nullable Entity except, AABB box, @Nullable Predicate<? super Entity> predicate) { return Collections.emptyList(); }
    @Override public List<? extends Player> players() { return Collections.emptyList(); }
    @Nullable @Override public Player getNearestPlayer(double x, double y, double z, double distance, @Nullable Predicate<Entity> predicate) { return null; }

    // Chunks, biomes, light ----------------------------------------------------------------------

    @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
        if (!required) return null;
        View view = views.get(ChunkPos.asLong(x, z));
        if (view == null) views.put(ChunkPos.asLong(x, z), view = new View(x, z));
        return view;
    }

    /**
     * A chunk of this level, at this level's heights, for code that reads and writes a chunk's
     * sections itself rather than through the level: YUNG's API processors (desert temples' pillars,
     * strongholds' legs, dungeons' stairs) index the chunk's sections by height. Each section reads
     * and writes this level's blocks, so those writes are kept as any other.
     */
    private final class View extends ProtoChunk {
        private final net.minecraft.world.level.chunk.LevelChunkSection[] rows;

        View(int cx, int cz) {
            super(new ChunkPos(cx, cz), UpgradeData.EMPTY, placeholders(net.minecraft.world.level.LevelHeightAccessor.create(minY, height).getSectionsCount()), new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                    new net.minecraft.world.ticks.ProtoChunkTicks<>(), net.minecraft.world.level.LevelHeightAccessor.create(minY, height), biomeRegistry, null);
            this.rows = new net.minecraft.world.level.chunk.LevelChunkSection[getSectionsCount()];
        }

        @Override public net.minecraft.world.level.chunk.LevelChunkSection getSection(int index) {
            net.minecraft.world.level.chunk.LevelChunkSection row = rows[index];
            if (row == null) rows[index] = row = new Row(getPos().getMinBlockX(), getMinBuildHeight() + index * 16, getPos().getMinBlockZ());
            return row;
        }

        @Override public net.minecraft.world.level.chunk.LevelChunkSection[] getSections() {
            for (int i = 0; i < rows.length; i++) getSection(i);
            return rows;
        }

        @Override public void markPosForPostprocessing(BlockPos pos) { postProcess.add(key(real(pos))); }
        @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) { return StructureLevel.this.getBlockEntity(pos); }
        @Override public void setBlockEntity(BlockEntity entity) { StructureLevel.this.putBlockEntity(entity); }
        @Override public void removeBlockEntity(BlockPos pos) { removeBlockEntityAt(pos); }
        // As vanilla's chunks: a block is read in this chunk at its position within it.
        @Override public BlockState getBlockState(BlockPos pos) { return StructureLevel.this.getBlockState(here(pos)); }
        @Override public FluidState getFluidState(BlockPos pos) { return StructureLevel.this.getFluidState(here(pos)); }
        private BlockPos here(BlockPos pos) {
            return new BlockPos(getPos().getMinBlockX() + (pos.getX() & 15), pos.getY(), getPos().getMinBlockZ() + (pos.getZ() & 15));
        }
        @Override public int getHeight(Heightmap.Types type, int x, int z) {
            return StructureLevel.this.getHeight(type, getPos().getMinBlockX() + (x & 15), getPos().getMinBlockZ() + (z & 15)) - 1;
        }
        @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
            return StructureLevel.this.getNoiseBiome((getPos().getMinBlockX() >> 2) + (qx & 3), qy, (getPos().getMinBlockZ() >> 2) + (qz & 3));
        }
    }

    private net.minecraft.world.level.chunk.LevelChunkSection[] placeholders(int count) {
        var sections = new net.minecraft.world.level.chunk.LevelChunkSection[count];
        java.util.Arrays.fill(sections, placeholder);
        return sections;
    }

    /** Sixteen rows of a {@link View}, in this level's (frame) heights. */
    private final class Row extends net.minecraft.world.level.chunk.LevelChunkSection {
        private final int x0, y0, z0;

        Row(int x0, int y0, int z0) {
            super(biomeRegistry);
            this.x0 = x0; this.y0 = y0; this.z0 = z0;
        }

        @Override public BlockState getBlockState(int x, int y, int z) { return StructureLevel.this.getBlockState(new BlockPos(x0 + x, y0 + y, z0 + z)); }
        @Override public FluidState getFluidState(int x, int y, int z) { return getBlockState(x, y, z).getFluidState(); }
        @Override public BlockState setBlockState(int x, int y, int z, BlockState state) { return setBlockState(x, y, z, state, true); }
        @Override public BlockState setBlockState(int x, int y, int z, BlockState state, boolean lock) {
            BlockPos pos = new BlockPos(x0 + x, y0 + y, z0 + z);
            BlockState old = StructureLevel.this.getBlockState(pos);
            setBlock(pos, state, 2);
            return old;
        }
        @Override public boolean hasOnlyAir() { return false; }
        @Override public boolean maybeHas(Predicate<BlockState> predicate) { return true; }
        @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
            return StructureLevel.this.getNoiseBiome((x0 >> 2) + qx, (y0 >> 2) + qy, (z0 >> 2) + qz);
        }
    }
    @Override public boolean hasChunk(int x, int z) { return true; }
    @Override public BiomeManager getBiomeManager() { return biomes; }
    @Override public Holder<Biome> getUncachedNoiseBiome(int qx, int qy, int qz) { return terrain.biome(qx << 2, (qy << 2) + shift, qz << 2); }
    @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) { return getUncachedNoiseBiome(qx, qy, qz); }
    @Override public float getShade(Direction direction, boolean shade) { return 1; }
    @Override public LevelLightEngine getLightEngine() { return light; }
    @Override public int getSkyDarken() { return 0; }
    @Override public int getMinBuildHeight() { return minY; }
    @Override public int getHeight() { return height; }
    @Override public int getSeaLevel() { return seaLevel; }

    // The rest of the level, from the real one --------------------------------------------------

    @Override public ServerLevel getLevel() { return level; }
    @Override public long getSeed() { return seed; }
    @Override public RandomSource getRandom() { return random; }
    @Override public RegistryAccess registryAccess() { return level == null ? RegistryAccess.EMPTY : level.registryAccess(); }
    @Override public FeatureFlagSet enabledFeatures() { return level.enabledFeatures(); }
    @Override public LevelData getLevelData() { return level.getLevelData(); }
    @Override public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return new DifficultyInstance(level.getDifficulty(), level.getDayTime(), 0L, level.getMoonBrightness());
    }
    @Override public MinecraftServer getServer() { return level.getServer(); }
    @Override public ChunkSource getChunkSource() { return level.getChunkSource(); }
    @Override public WorldBorder getWorldBorder() { return level.getWorldBorder(); }
    @Override public DimensionType dimensionType() { return level.dimensionType(); }
    @Override public boolean isClientSide() { return false; }
    @Override public long nextSubTickCount() { return 0; }
    @Override public LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {}
    @Override public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {}
    @Override public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {}
    @Override public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {}
}
