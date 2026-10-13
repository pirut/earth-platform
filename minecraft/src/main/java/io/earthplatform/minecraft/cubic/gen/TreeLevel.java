package io.earthplatform.minecraft.cubic.gen;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
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
import net.minecraft.world.ticks.TickPriority;

/**
 * A flat, open patch of ground that one vanilla tree feature grows on, as it grows during normal
 * world generation: {@code top} at Y -1 over {@code under}, stone below, air above. Every block the
 * feature writes is recorded, by packed position, for {@link TreeShape} to keep.
 */
final class TreeLevel implements WorldGenLevel {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState();

    private final BlockState top, under;
    private final Holder<Biome> biome;
    private final RandomSource random;
    private final BiomeManager biomes;
    private final LevelLightEngine light;
    final Long2ObjectLinkedOpenHashMap<BlockState> blocks = new Long2ObjectLinkedOpenHashMap<>();

    TreeLevel(BlockState top, BlockState under, Holder<Biome> biome, RandomSource random) {
        this.top = top;
        this.under = under;
        this.biome = biome;
        this.random = random;
        this.biomes = new BiomeManager(this, 0L);
        this.light = new LevelLightEngine(new net.minecraft.world.level.chunk.LightChunkGetter() {
            @Override public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) { return null; }
            @Override public net.minecraft.world.level.BlockGetter getLevel() { return TreeLevel.this; }
        }, false, false);
    }

    /** The ground as it was before the feature grew. */
    BlockState ground(int y) { return y >= 0 ? AIR : y == -1 ? top : y >= -4 ? under : STONE; }

    // Blocks -------------------------------------------------------------------------------------

    @Override public BlockState getBlockState(BlockPos pos) {
        BlockState written = blocks.get(pos.asLong());
        return written != null ? written : ground(pos.getY());
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        blocks.put(pos.asLong(), state);
        return true;
    }
    @Override public boolean removeBlock(BlockPos pos, boolean moving) { return setBlock(pos, AIR, 3); }
    @Override public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int recursionLeft) {
        return !getBlockState(pos).isAir() && setBlock(pos, AIR, 3, recursionLeft);
    }
    @Override public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) { return predicate.test(getBlockState(pos)); }
    @Override public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) { return predicate.test(getFluidState(pos)); }
    /** No block entities: bee nests are added when the shape is placed. */
    @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight(Heightmap.Types type, int x, int z) { return 0; }

    // Entities -----------------------------------------------------------------------------------

    @Override public boolean addFreshEntity(Entity entity) { return false; }
    @Override public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) { return Collections.emptyList(); }
    @Override public List<Entity> getEntities(@Nullable Entity except, AABB box, @Nullable Predicate<? super Entity> predicate) { return Collections.emptyList(); }
    @Override public List<? extends Player> players() { return Collections.emptyList(); }
    @Nullable @Override public Player getNearestPlayer(double x, double y, double z, double distance, @Nullable Predicate<Entity> predicate) { return null; }

    // Chunks, biomes, light ----------------------------------------------------------------------

    @Nullable @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) { return null; }
    @Override public boolean hasChunk(int x, int z) { return true; }
    @Override public BiomeManager getBiomeManager() { return biomes; }
    @Override public Holder<Biome> getUncachedNoiseBiome(int qx, int qy, int qz) { return biome; }
    @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) { return biome; }
    @Override public float getShade(Direction direction, boolean shade) { return 1; }
    @Override public LevelLightEngine getLightEngine() { return light; }
    @Override public int getSkyDarken() { return 0; }
    @Override public int getMinBuildHeight() { return -64; }
    @Override public int getHeight() { return 512; }
    @Override public int getSeaLevel() { return -64; }

    // The rest of a level, which trees never use -------------------------------------------------

    @Nullable @Override public ServerLevel getLevel() { return null; }
    @Override public long getSeed() { return 0; }
    @Override public RandomSource getRandom() { return random; }
    @Override public RegistryAccess registryAccess() { return RegistryAccess.EMPTY; }
    @Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.VANILLA_SET; }
    @Nullable @Override public LevelData getLevelData() { return null; }
    @Override public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) { return new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0f); }
    @Nullable @Override public MinecraftServer getServer() { return null; }
    @Nullable @Override public ChunkSource getChunkSource() { return null; }
    @Override public WorldBorder getWorldBorder() { return new WorldBorder(); }
    @Nullable @Override public DimensionType dimensionType() { return null; }
    @Override public boolean isClientSide() { return false; }
    @Override public long nextSubTickCount() { return 0; }
    @Override public LevelTickAccess<Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
    // Ticks leaves ask for as their shapes settle go nowhere (making one would read the game time).
    @Override public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {}
    @Override public void scheduleTick(BlockPos pos, Block block, int delay) {}
    @Override public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {}
    @Override public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {}
    @Override public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {}
    @Override public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {}
    @Override public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {}
    @Override public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {}
}
