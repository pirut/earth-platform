package io.earthplatform.minecraft.cubic.gen;

import com.mojang.serialization.MapCodec;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
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
 * Ores other mods add to overworld biomes, which vanilla would place while decorating a chunk.
 * The cubic world never runs that decoration: its own veins are the vanilla ores, and a biome
 * modifier's placed feature (Create's zinc, its layered ochrum, a Fabric BiomeModification)
 * would otherwise never be asked to place.
 *
 * <p>Each such feature is run once per chunk, in a frame whose vanilla Y 64 sits on the local
 * floor, the same datum the rock layers hang from. Whatever it writes is kept only where that
 * spot is still the stone it replaced, below the ground and not open to the sea. A feature is
 * kept only in biomes whose own ore list contains it, which is where the modifier put it.
 */
final class ModOres {
    /** Vanilla's overworld, the heights ore features were written against. */
    private static final int VANILLA_MIN = -64, VANILLA_HEIGHT = 384;
    /** Default overworld dimension type when no live {@link ServerLevel} has been attached yet (e.g. unit tests). */
    static final DimensionType DEFAULT_DIMENSION_TYPE = new DimensionType(
            OptionalLong.empty(), true, false, false, true, 1.0, true, false,
            VANILLA_MIN, VANILLA_HEIGHT, VANILLA_HEIGHT,
            BlockTags.INFINIBURN_OVERWORLD, BuiltinDimensionTypes.OVERWORLD_EFFECTS, 0.0f,
            new DimensionType.MonsterSettings(false, true, UniformInt.of(0, 7), 0));
    /**
     * How many chunks out a vein is allowed to reach. Layered stones (ochrum and its kin) are
     * about 32 blocks across, so a chunk two away can still drop a block in this one.
     */
    private static final int REACH = 2;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState();

    private final SurfaceGenerator terrain;
    private final Underground under;
    private final long seed;
    private final Registry<Biome> biomes;
    private final Holder<Biome> anyBiome;
    private final ChunkGenerator generator;
    private final int oreStep = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
    private volatile RegistryAccess registries;
    private volatile DimensionType dimensionType;
    /** Null until the first cube asks. Empty when no mod adds ores, which is the usual test world. */
    private volatile List<Added> added;
    private final VanillaStructures.Generations<ConcurrentHashMap<Integer, List<Drop>>> placed = new VanillaStructures.Generations<>(2048);

    ModOres(SurfaceGenerator terrain, Underground under, long seed, Registry<Biome> biomes) {
        this.terrain = terrain;
        this.under = under;
        this.seed = seed;
        this.biomes = biomes;
        this.registries = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        this.dimensionType = DEFAULT_DIMENSION_TYPE;
        Holder<Biome> plains = biomes.getHolder(Biomes.PLAINS).orElse(null);
        this.anyBiome = plains != null ? plains : biomes.holders().findFirst().orElseThrow();
        this.generator = new OreGenerator();
    }

    /** Wires the live world's registries and dimension type once attached to a {@link ServerLevel}. */
    void attach(@Nullable RegistryAccess registries, @Nullable DimensionType dimensionType) {
        if (registries != null) this.registries = registries;
        if (dimensionType != null) this.dimensionType = dimensionType;
    }

    /** Writes this cube's share of every mod ore vein that can reach it. */
    void place(LevelChunkSection section, int cx, int cy, int cz) {
        List<Added> ores = added();
        if (ores.isEmpty()) return;
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4, y1 = y0 + 15;
        // Exact set of biomes in this cube (biomeAt depends only on x, z and cube cy).
        Set<Biome> cubeBiomes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            cubeBiomes.add(terrain.biomeAt(x0 + lx, y0, z0 + lz).value());
        }
        List<Integer> active = new ArrayList<>(ores.size());
        for (int i = 0; i < ores.size(); i++) {
            Added ore = ores.get(i);
            if (!ore.failed.get() && anyBiomeMatches(ore.biomes, cubeBiomes)) active.add(i);
        }
        if (active.isEmpty()) return;
        for (int dz = -REACH; dz <= REACH; dz++) for (int dx = -REACH; dx <= REACH; dx++) {
            int nx = cx + dx, nz = cz + dz;
            int chunkCenterX = (nx << 4) + 8, chunkCenterZ = (nz << 4) + 8;
            int chunkFloor = under.floor(chunkCenterX, chunkCenterZ, terrain.surfaceY(chunkCenterX, chunkCenterZ));
            // Vanilla frame bottom is VANILLA_MIN (-64) + (chunkFloor - 64) = chunkFloor - 128.
            if (y1 < chunkFloor - 128) continue;
            for (int index : active) {
                Added ore = ores.get(index);
                for (Drop drop : drops(nx, nz, index, ore, chunkFloor)) {
                    if (drop.y < y0 || drop.y > y1 || (drop.x >> 4) != cx || (drop.z >> 4) != cz) continue;
                    if (!kept(ore, drop)) continue;
                    BlockState here = section.getBlockState(drop.x & 15, drop.y & 15, drop.z & 15);
                    // Veins already in this cube may have turned the stone into an ore of vanilla's. Leave that.
                    if (here.isAir() || !here.getFluidState().isEmpty() || !replaces(drop.was, here)) continue;
                    section.setBlockState(drop.x & 15, drop.y & 15, drop.z & 15, drop.state, false);
                }
            }
        }
    }

    private static boolean anyBiomeMatches(Set<Biome> allowed, Set<Biome> present) {
        for (Biome biome : present) if (allowed.contains(biome)) return true;
        return false;
    }

    /** Stone the feature saw, or the same kind of rock a small vein has since put there. */
    private static boolean replaces(BlockState seen, BlockState here) {
        return here.equals(seen) || Underground.rock(seen) && Underground.rock(here);
    }

    /**
     * A kept block is below its own ground (so not in the air, on the surface, or on a seabed)
     * and the biome there lists this feature. Cave biomes count: a modifier on the overworld tag
     * includes them, and a modifier on plains alone does not fill the deep dark.
     */
    private boolean kept(Added ore, Drop drop) {
        if (drop.y >= terrain.surfaceY(drop.x, drop.z)) return false;
        return ore.biomes.contains(terrain.biomeAt(drop.x, drop.y, drop.z).value());
    }

    private List<Drop> drops(int chunkX, int chunkZ, int index, Added ore, int chunkFloor) {
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        ConcurrentHashMap<Integer, List<Drop>> byOre = placed.get(chunkKey);
        if (byOre == null) byOre = placed.putIfAbsent(chunkKey, new ConcurrentHashMap<>());
        return byOre.computeIfAbsent(index, key -> generate(chunkX, chunkZ, index, ore, chunkFloor));
    }

    private List<Drop> generate(int chunkX, int chunkZ, int index, Added ore, int chunkFloor) {
        if (ore.failed.get()) return List.of();
        int shift = chunkFloor - 64;
        LinkedHashMap<Pos, Drop> writes = new LinkedHashMap<>();
        OreLevel level = new OreLevel(shift, writes);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decoration = random.setDecorationSeed(seed, chunkX << 4, chunkZ << 4);
        random.setFeatureSeed(decoration, index, oreStep);
        try {
            ore.feature.placeWithBiomeCheck(level, generator, random, new BlockPos(chunkX << 4, 0, chunkZ << 4));
        } catch (MissingTerrainException missing) {
            throw missing;
        } catch (RuntimeException | LinkageError failure) {
            if (isMissingTerrain(failure)) throw failure;
            if (ore.failed.compareAndSet(false, true))
                EarthPlatformMod.LOGGER.warn("Earth ores: {} failed to place and was left out", ore.name, failure);
            return List.of();
        }
        return List.copyOf(writes.values());
    }

    private static boolean isMissingTerrain(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cur = failure; cur != null && seen.add(cur); cur = cur.getCause()) {
            if (cur instanceof MissingTerrainException) return true;
        }
        return false;
    }

    private List<Added> added() {
        List<Added> known = added;
        if (known != null) return known;
        synchronized (this) {
            if (added == null) added = scan();
            return added;
        }
    }

    /** Placed features mods add at the ore step, once each, overworld biomes only. */
    private List<Added> scan() {
        boolean restrict = tagsBound();
        Map<PlacedFeature, Set<Biome>> biomesByFeature = new IdentityHashMap<>();
        List<Added> found = new ArrayList<>();
        for (Holder<Biome> biome : biomes.holders().toList()) {
            if (restrict && !biome.is(BiomeTags.IS_OVERWORLD)) continue;
            var steps = biome.value().getGenerationSettings().features();
            if (steps.size() <= oreStep) continue;
            for (Holder<PlacedFeature> placed : steps.get(oreStep)) {
                PlacedFeature feature = placed.value();
                String namespace = placed.unwrapKey().map(key -> key.location().getNamespace()).orElse("");
                if (namespace.equals("minecraft") || namespace.equals(EarthPlatformMod.MOD_ID)) continue;
                Set<Biome> allowed = biomesByFeature.get(feature);
                if (allowed == null) {
                    allowed = Collections.newSetFromMap(new IdentityHashMap<>());
                    biomesByFeature.put(feature, allowed);
                    String name = placed.unwrapKey().map(key -> key.location().toString()).orElse(featureName(feature));
                    found.add(new Added(feature, name, allowed, new AtomicBoolean()));
                }
                allowed.add(biome.value());
            }
        }
        if (!found.isEmpty()) EarthPlatformMod.LOGGER.info("Earth ores: other mods add {}", found.stream().map(ore -> ore.name).toList());
        return List.copyOf(found);
    }

    /**
     * A live world binds biome tags, so nether and end ores stay out. A unit test's registry
     * often has none bound, and then every biome is read; vanilla's own features are skipped by name.
     */
    private boolean tagsBound() {
        try {
            return biomes.holders().anyMatch(biome -> biome.is(BiomeTags.IS_OVERWORLD));
        } catch (IllegalStateException unbound) {
            return false;
        }
    }

    private static String featureName(PlacedFeature feature) {
        ConfiguredFeature<?, ?> configured = feature.feature().value();
        var id = BuiltInRegistries.FEATURE.getKey(configured.feature());
        return id == null ? "modded ore" : id.toString();
    }

    private record Added(PlacedFeature feature, String name, Set<Biome> biomes, AtomicBoolean failed) {}
    private record Pos(int x, int y, int z) {}
    /** {@code was} is the block the feature replaced, in earth coordinates. */
    private record Drop(int x, int y, int z, BlockState state, BlockState was) {}

    /**
     * The block a feature reads: the ground as generated, before small ore veins. Above the ground
     * and in caves it is air (or water, or lava), so an ore feature declines to write there.
     */
    private BlockState at(int x, int y, int z) {
        int ground = terrain.surfaceY(x, z);
        int water = terrain.waterY(x, z);
        if (y > ground) return y <= water ? WATER : AIR;
        int depth = ground - y;
        if (depth < 4) {
            SurfaceGenerator.Surface surface = terrain.coverAt(x, z, ground, water);
            return depth == 0 ? surface.top() : surface.under();
        }
        int floor = under.floor(x, z, ground);
        if (!under.solid(x, y, z)) return Underground.cavity(y, floor);
        return under.rock(x, y, z, floor);
    }

    /**
     * A generation level whose Y is vanilla's. {@code shift} is added to reach the earth, so
     * vanilla's sea level lies on this chunk's floor. Writes remember the earth position.
     */
    private final class OreLevel implements WorldGenLevel {
        final int shift;
        private final LinkedHashMap<Pos, Drop> writes;
        private final RandomSource random = RandomSource.create(seed);
        private final BiomeManager biomeManager = new BiomeManager(this, BiomeManager.obfuscateSeed(seed));
        private final ConcurrentHashMap<Long, Slice> chunks = new ConcurrentHashMap<>();
        private final LevelLightEngine light = new LevelLightEngine(new net.minecraft.world.level.chunk.LightChunkGetter() {
            @Override public net.minecraft.world.level.chunk.LightChunk getChunkForLighting(int x, int z) { return null; }
            @Override public net.minecraft.world.level.BlockGetter getLevel() { return OreLevel.this; }
        }, false, false);

        OreLevel(int shift, LinkedHashMap<Pos, Drop> writes) {
            this.shift = shift;
            this.writes = writes;
        }

        private boolean accept(int x, int vanillaY, int z, BlockState state) {
            if (state.isAir() || !state.getFluidState().isEmpty()) return false;
            int y = vanillaY + shift;
            if (y >= terrain.surfaceY(x, z)) return false;
            Pos pos = new Pos(x, y, z);
            Drop prev = writes.get(pos);
            BlockState was = prev != null ? prev.was : at(x, y, z);
            if (was.isAir() || !was.getFluidState().isEmpty()) return false;
            BlockState current = prev != null ? prev.state : was;
            if (current.equals(state)) return false;
            if (state.equals(was)) {
                writes.remove(pos);
                return true;
            }
            writes.put(pos, new Drop(x, y, z, state, was));
            return true;
        }

        private BlockState read(int x, int y, int z) {
            Drop written = writes.get(new Pos(x, y, z));
            return written != null ? written.state : at(x, y, z);
        }

        @Override public BlockState getBlockState(BlockPos pos) { return read(pos.getX(), pos.getY() + shift, pos.getZ()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
            return accept(pos.getX(), pos.getY(), pos.getZ(), state);
        }
        @Override public boolean removeBlock(BlockPos pos, boolean moving) { return false; }
        @Override public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int recursionLeft) { return false; }
        @Override public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) { return predicate.test(getBlockState(pos)); }
        @Override public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) { return predicate.test(getFluidState(pos)); }
        /** Ore features write through the chunk section, and only inside the area they were given. */
        @Override public boolean ensureCanWrite(BlockPos pos) { return true; }
        @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }

        @Override public int getHeight(Heightmap.Types type, int x, int z) {
            int ground = terrain.surfaceY(x, z), water = terrain.waterY(x, z);
            int top = switch (type) {
                case OCEAN_FLOOR, OCEAN_FLOOR_WG -> ground;
                default -> Math.max(ground, water);
            };
            return top + 1 - shift;
        }

        @Override public boolean addFreshEntity(Entity entity) { return false; }
        @Override public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) { return List.of(); }
        @Override public List<Entity> getEntities(@Nullable Entity except, AABB box, @Nullable Predicate<? super Entity> predicate) { return List.of(); }
        @Override public List<? extends Player> players() { return List.of(); }
        @Nullable @Override public Player getNearestPlayer(double x, double y, double z, double distance, @Nullable Predicate<Entity> predicate) { return null; }

        @Nullable @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            return chunks.computeIfAbsent(ChunkPos.asLong(x, z), key -> new Slice(x, z));
        }
        @Override public boolean hasChunk(int x, int z) { return true; }
        @Override public BiomeManager getBiomeManager() { return biomeManager; }
        @Override public Holder<Biome> getUncachedNoiseBiome(int qx, int qy, int qz) {
            return terrain.biomeAt(qx << 2, (qy << 2) + shift, qz << 2);
        }
        @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) { return getUncachedNoiseBiome(qx, qy, qz); }
        @Override public float getShade(Direction direction, boolean shade) { return 1; }
        @Override public LevelLightEngine getLightEngine() { return light; }
        @Override public int getSkyDarken() { return 0; }
        @Override public int getMinBuildHeight() { return VANILLA_MIN; }
        @Override public int getHeight() { return VANILLA_HEIGHT; }
        @Override public int getSeaLevel() { return 63; }

        @Nullable @Override public ServerLevel getLevel() { return null; }
        @Override public long getSeed() { return seed; }
        @Override public RandomSource getRandom() { return random; }
        @Override public RegistryAccess registryAccess() { return ModOres.this.registries; }
        @Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.VANILLA_SET; }
        @Nullable @Override public LevelData getLevelData() { return null; }
        @Override public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) { return new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0f); }
        @Nullable @Override public MinecraftServer getServer() { return null; }
        @Nullable @Override public ChunkSource getChunkSource() { return null; }
        @Override public WorldBorder getWorldBorder() { return new WorldBorder(); }
        @Override public DimensionType dimensionType() { return ModOres.this.dimensionType; }
        @Override public boolean isClientSide() { return false; }
        @Override public long nextSubTickCount() { return 0; }
        @Override public LevelTickAccess<Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
        @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
        @Override public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {}
        @Override public void scheduleTick(BlockPos pos, Block block, int delay) {}
        @Override public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {}
        @Override public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {}
        @Override public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {}
        @Override public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {}
        @Override public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {}
        @Override public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {}

        /** One vanilla chunk whose sections translate their rows onto the earth. */
        final class Slice extends ProtoChunk {
            private final LevelChunkSection[] rows = new LevelChunkSection[VANILLA_HEIGHT >> 4];
            Slice(int cx, int cz) {
                super(new ChunkPos(cx, cz), UpgradeData.EMPTY, LevelHeightAccessor.create(VANILLA_MIN, VANILLA_HEIGHT), biomes, null);
            }
            @Override public LevelChunkSection getSection(int index) {
                LevelChunkSection row = rows[index];
                if (row == null) rows[index] = row = new Mapped(getPos().x, getPos().z, VANILLA_MIN + index * 16);
                return row;
            }
        }

        /** Sixteen rows of one section. Reads the earth; writes remember a block the feature placed. */
        final class Mapped extends LevelChunkSection {
            private final int cx, cz, baseY;
            Mapped(int cx, int cz, int baseY) {
                super(biomes);
                this.cx = cx;
                this.cz = cz;
                this.baseY = baseY;
            }
            @Override public BlockState getBlockState(int lx, int ly, int lz) {
                return OreLevel.this.read((cx << 4) + lx, baseY + ly + shift, (cz << 4) + lz);
            }
            @Override public BlockState setBlockState(int lx, int ly, int lz, BlockState state, boolean lock) {
                BlockState old = getBlockState(lx, ly, lz);
                accept((cx << 4) + lx, baseY + ly, (cz << 4) + lz, state);
                return old;
            }
            @Override public boolean hasOnlyAir() { return false; }
        }
    }

    /** Heights ore placement asks for: vanilla's bottom and depth, so {@code above_bottom} means what it does there. */
    private final class OreGenerator extends ChunkGenerator {
        OreGenerator() { super(new OreBiomes()); }
        @Override public int getMinY() { return VANILLA_MIN; }
        @Override public int getGenDepth() { return VANILLA_HEIGHT; }
        @Override public int getSeaLevel() { return 63; }
        @Override protected MapCodec<? extends ChunkGenerator> codec() { throw new UnsupportedOperationException("mod ore frame"); }
        @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}
        @Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {}
        @Override public void spawnOriginalMobs(WorldGenRegion region) {}
        @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
            return CompletableFuture.completedFuture(chunk);
        }
        @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heights, RandomState random) { return 0; }
        @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heights, RandomState random) {
            return new NoiseColumn(VANILLA_MIN, new BlockState[0]);
        }
        @Override public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {}
    }

    private final class OreBiomes extends BiomeSource {
        @Override protected MapCodec<? extends BiomeSource> codec() { throw new UnsupportedOperationException("mod ore frame"); }
        @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return Stream.of(anyBiome); }
        @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) { return anyBiome; }
    }
}
