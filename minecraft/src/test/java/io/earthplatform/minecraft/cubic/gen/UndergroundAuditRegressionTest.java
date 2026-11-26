package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapCodec;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.cubic.CubeCodec;
import io.earthplatform.minecraft.cubic.CubeWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression suite covering Wave 2 PR 5 underground & structure audit findings:
 * UNDER-01, UNDER-02, UNDER-04, UNDER-05, UNDER-06, UNDER-07, UNDER-08, UNDER-09, UNDER-10, UNDER-11.
 */
class UndergroundAuditRegressionTest {
    private static Registry<Biome> biomes;
    private static Holder<Biome> plains;
    private static RandomState randomState;

    @BeforeAll static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        biomes = SurfacePacketFixtures.biomes();
        plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        var lookup = VanillaRegistries.createLookup();
        NoiseGeneratorSettings noise = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        randomState = RandomState.create(noise, lookup.lookupOrThrow(Registries.NOISE), 1L);
    }

    private static LevelChunkSection airSection() {
        return new LevelChunkSection(
                new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomes.asHolderIdMap(), plains, PalettedContainer.Strategy.SECTION_BIOMES));
    }

    private static StructureLevel makeStructureLevel(int shift, int minY, int height) {
        StructureLevel.Terrain terrain = new StructureLevel.Terrain() {
            @Override public BlockState block(int x, int y, int z) { return y <= 1300 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(); }
            @Override public int ground(int x, int z) { return 1300; }
            @Override public int water(int x, int z) { return 0; }
            @Override public Holder<Biome> biome(int x, int y, int z) { return plains; }
        };
        return new StructureLevel(null, biomes, terrain, shift, minY, height, 63, new BlockPos(0, 1250, 0), CubeWorld.MIN_Y, 1L, RandomSource.create(1L));
    }

    // UNDER-01: StructureLevel tick capture, Y-shift, dedup, filtering, and cube bucketing ---------

    @Test void structureLevelCapturesDirectViewAndPostProcessTicksWithShiftDedupAndFilter() {
        StructureLevel level = makeStructureLevel(1000, -64, 384);

        // 1. Direct fluid tick (like Stronghold RoomCrossing fountain) in shifted frame.
        BlockPos fountainFrame = new BlockPos(5, -30, 9); // real Y = 970
        level.setBlock(fountainFrame, Blocks.WATER.defaultBlockState(), 2);
        level.scheduleTick(fountainFrame, Fluids.WATER, 5, TickPriority.LOW);
        // Duplicate at same pos/type with shorter delay and higher priority -> keeps shortest delay (0) & highest priority (HIGH).
        level.scheduleTick(fountainFrame, Fluids.WATER, 0, TickPriority.HIGH);

        // 2. Tick scheduled via View (ProtoChunk) tick access.
        BlockPos sandFrame = new BlockPos(20, -28, 3); // real Y = 972, cube (1, 60, 0)
        level.setBlock(sandFrame, Blocks.SAND.defaultBlockState(), 2);
        ChunkAccess view = level.getChunk(1, 0);
        view.getBlockTicks().schedule(new ScheduledTick<>(Blocks.SAND, sandFrame, 3L, TickPriority.NORMAL, 0L));

        // 3. Tick whose block is later overwritten -> filtered out when drained.
        BlockPos overwrittenFrame = new BlockPos(6, -30, 9);
        level.setBlock(overwrittenFrame, Blocks.LAVA.defaultBlockState(), 2);
        level.scheduleTick(overwrittenFrame, Fluids.LAVA, 0);
        level.setBlock(overwrittenFrame, Blocks.STONE.defaultBlockState(), 2);

        // 4. Waterlogged stair post-processed -> updateFromNeighbourShapes schedules WATER tick!
        BlockPos stairFrame = new BlockPos(7, -30, 9);
        level.setBlock(stairFrame, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.WATERLOGGED, true), 2);
        level.setBlock(stairFrame.east(), Blocks.AIR.defaultBlockState(), 2);
        level.getChunk(0, 0).markPosForPostprocessing(stairFrame);
        level.postProcessAll();

        List<CubeCodec.PendingTick<Block>> blocks = level.capturedBlockTicks();
        List<CubeCodec.PendingTick<Fluid>> fluids = level.capturedFluidTicks();

        assertEquals(1, blocks.size(), "sand block tick captured via View");
        assertEquals(new BlockPos(20, 972, 3), blocks.get(0).pos());
        assertEquals(Blocks.SAND, blocks.get(0).type());
        assertEquals(3, blocks.get(0).delay());

        assertTrue(fluids.stream().noneMatch(t -> t.type() == Fluids.LAVA), "overwritten lava tick must be filtered out");
        var fountainTick = fluids.stream().filter(t -> t.pos().equals(new BlockPos(5, 970, 9))).findFirst().orElseThrow();
        assertEquals(Fluids.WATER, fountainTick.type());
        assertEquals(0, fountainTick.delay(), "dedup keeps shortest delay");
        assertEquals(TickPriority.HIGH, fountainTick.priority(), "dedup keeps highest priority");
        assertTrue(fluids.stream().anyMatch(t -> t.pos().equals(new BlockPos(7, 970, 9)) && t.type() == Fluids.WATER),
                "postProcessAll waterlogged stair must capture water tick at real Y");

        // 5. VanillaStructures.patches buckets ticks into their respective cubes.
        var patches = VanillaStructures.patches(level);
        long cube0 = SectionPos.asLong(0, 970 >> 4, 0);
        long cube1 = SectionPos.asLong(1, 972 >> 4, 0);
        VanillaStructures.Patch p0 = patches.get(cube0);
        VanillaStructures.Patch p1 = patches.get(cube1);
        assertNotNull(p0);
        assertNotNull(p1);
        assertTrue(p0.fluidTicks().stream().anyMatch(t -> t.pos().equals(new BlockPos(5, 970, 9))));
        assertEquals(1, p1.blockTicks().size());
        assertEquals(new BlockPos(20, 972, 3), p1.blockTicks().get(0).pos());
    }

    // UNDER-01 + UNDER-02: Fountain structure places fluid ticks & overlay eviction rebuilds identically ---

    @Test void fountainStructureEmitsFluidTicksAndRebuildsIdenticallyAfterOverlayEviction() {
        StatefulFountainStructure structure = new StatefulFountainStructure(plains);
        RegistryAccess registries = singleStructureRegistries("test_fountain", structure);
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> 100), 1L);

        StructureStart cachedStart = structures.startOf(structure, 0, 0);
        assertNotNull(cachedStart);
        StatefulFountainPiece cachedPiece = (StatefulFountainPiece) cachedStart.getPieces().get(0);
        assertFalse(cachedPiece.postProcessed, "freshly cached start has not been post-processed");

        int cy = 102 >> 4;
        LevelChunkSection first = airSection();
        List<CompoundTag> be1 = new ArrayList<>(), e1 = new ArrayList<>();
        List<CubeCodec.PendingTick<Block>> bt1 = new ArrayList<>();
        List<CubeCodec.PendingTick<Fluid>> ft1 = new ArrayList<>();
        structures.place(first, 0, cy, 0, be1, e1, bt1, ft1);

        assertFalse(cachedPiece.postProcessed, "UNDER-02: cached StructureStart must never be mutated by overlay build");
        assertEquals(Blocks.WATER, first.getBlockState(4, 102 & 15, 4).getBlock(), "fountain water block placed");
        assertTrue(ft1.stream().anyMatch(t -> t.pos().equals(new BlockPos(4, 102, 4)) && t.type() == Fluids.WATER),
                "UNDER-01: fountain water fluid tick emitted from VanillaStructures.place");

        // Evict overlay cache and rebuild the same cube: must produce identical blocks and fluid ticks!
        structures.clearOverlaysForTest();
        LevelChunkSection second = airSection();
        List<CompoundTag> be2 = new ArrayList<>(), e2 = new ArrayList<>();
        List<CubeCodec.PendingTick<Block>> bt2 = new ArrayList<>();
        List<CubeCodec.PendingTick<Fluid>> ft2 = new ArrayList<>();
        structures.place(second, 0, cy, 0, be2, e2, bt2, ft2);

        assertFalse(cachedPiece.postProcessed, "UNDER-02: cached start remains unmutated after rebuild");
        assertEquals(Blocks.WATER, second.getBlockState(4, 102 & 15, 4).getBlock(),
                "UNDER-02: post-eviction rebuild must place identical blocks even for stateful pieces");
        assertEquals(ft1, ft2, "UNDER-02: post-eviction rebuild must emit identical fluid ticks");
    }

    // UNDER-04: StructureLevel vehicle + passenger shifted once and serialized once ----------------

    @Test void structureLevelShiftsVehicleAndPassengerOnceAndSerializesSingleRootTag() {
        StructureLevel level = makeStructureLevel(1000, -64, 384);
        StubEntity vehicle = new StubEntity(EntityType.MINECART);
        StubEntity rider = new StubEntity(EntityType.ZOMBIE);
        vehicle.setPos(5.5, -20.0, 9.5);
        rider.setPos(5.5, -19.5, 9.5);
        vehicle.mount(rider);

        level.addFreshEntityWithPassengers(vehicle);

        assertEquals(1, level.entities.size(), "vehicle+passenger must serialize as one root entity tag, never duplicated");
        CompoundTag root = level.entities.get(0);
        assertEquals("minecraft:minecart", root.getString("id"));
        ListTag vehiclePos = root.getList("Pos", Tag.TAG_DOUBLE);
        assertEquals(980.0, vehiclePos.getDouble(1), 1e-6, "vehicle Y shifted once by +1000");

        assertTrue(root.contains("Passengers", Tag.TAG_LIST), "passenger must remain nested under Passengers");
        ListTag passengers = root.getList("Passengers", Tag.TAG_COMPOUND);
        assertEquals(1, passengers.size());
        CompoundTag riderTag = passengers.getCompound(0);
        assertEquals("minecraft:zombie", riderTag.getString("id"));
        ListTag riderPos = riderTag.getList("Pos", Tag.TAG_DOUBLE);
        assertEquals(980.5, riderPos.getDouble(1), 1e-6, "nested passenger Y must also be shifted once by +1000");
    }

    // UNDER-05: Cold STARTS single-flight & MissingTerrainException bypass -------------------------

    @Test void coldStartsDeduplicateConcurrentCallersAndMissingTerrainDoesNotPoisonCache() throws Exception {
        // Part A: single-flight deduplication under concurrent cold requests.
        AtomicInteger generations = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Structure gated = new Structure(new Structure.StructureSettings(
                HolderSet.direct(plains), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE)) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                generations.incrementAndGet();
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
                BoundingBox box = new BoundingBox(x, 100, z, x + 8, 108, z + 8);
                return Optional.of(new GenerationStub(new BlockPos(x, 100, z),
                        b -> b.addPiece(new SimplePiece(box, (lvl, bx) -> {}))));
            }
            @Override public StructureType<?> type() { return () -> MapCodec.unit(this); }
        };
        VanillaStructures singleFlight = new VanillaStructures(null,
                singleStructureRegistries("single_flight", gated), randomState, true, hostOf((x, z) -> 100), 1L);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<StructureStart>> futures = new ArrayList<>();
            Callable<StructureStart> task = () -> singleFlight.startOf(gated, 0, 0);
            futures.add(pool.submit(task));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "first thread must enter makeStart");
            for (int i = 0; i < 3; i++) futures.add(pool.submit(task));
            Thread.sleep(50);
            release.countDown();

            StructureStart first = futures.get(0).get(10, TimeUnit.SECONDS);
            assertNotNull(first);
            for (Future<StructureStart> f : futures) {
                assertSame(first, f.get(10, TimeUnit.SECONDS), "all concurrent callers share the single computed start");
            }
            assertEquals(1, generations.get(), "UNDER-05: concurrent cold STARTS callers must invoke makeStart once");
        } finally {
            pool.shutdownNow();
        }

        // Part B: repeated MissingTerrainException propagates without counting toward failure limit or poisoning cache.
        AtomicBoolean offline = new AtomicBoolean(true);
        Structure simple = new Structure(new Structure.StructureSettings(
                HolderSet.direct(plains), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE)) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
                int y = context.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                        context.heightAccessor(), context.randomState());
                BoundingBox box = new BoundingBox(x, y, z, x + 8, y + 8, z + 8);
                return Optional.of(new GenerationStub(new BlockPos(x, y, z),
                        b -> b.addPiece(new SimplePiece(box, (lvl, bx) -> {}))));
            }
            @Override public StructureType<?> type() { return () -> MapCodec.unit(this); }
        };
        VanillaStructures resilient = new VanillaStructures(null,
                singleStructureRegistries("missing_terrain_retry", simple), randomState, true,
                hostOf((x, z) -> {
                    if (offline.get()) throw new MissingTerrainException("tile offline");
                    return 100;
                }), 1L);

        // Exceed MOST_START_FAILURES (3) cleanly: every call throws MissingTerrainException, never poisons cache.
        for (int i = 0; i < 6; i++) {
            assertThrows(MissingTerrainException.class, () -> resilient.startOf(simple, 0, 0),
                    "attempt " + i + " must propagate MissingTerrainException");
        }
        offline.set(false);
        assertNotNull(resilient.startOf(simple, 0, 0),
                "UNDER-05: once terrain returns, startOf must succeed immediately without backoff or poisoned EMPTY entry");
    }

    // UNDER-06: Underground deep-dark plants emit sculk block entities -----------------------------

    @Test void undergroundDeepDarkPlantsEmitBlockEntitiesForSensorsShriekersAndCatalysts() {
        HighlandsGenerator generator = new HighlandsGenerator(13, biomes);
        Underground under = generator.underground();
        int sensors = 0, shriekers = 0, catalysts = 0;

        for (int cz = 0; cz < 6; cz++) for (int cx = 0; cx < 6; cx++) {
            LevelChunkSection section = airSection();
            // Solid stone floor at ly=0..1 and air at ly=2..14 so every column has a cave floor.
            for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                section.setBlockState(lx, 0, lz, Blocks.DEEPSLATE.defaultBlockState(), false);
                section.setBlockState(lx, 1, lz, Blocks.DEEPSLATE.defaultBlockState(), false);
            }
            List<CompoundTag> blockEntities = new ArrayList<>();
            int cy = -4;
            under.plants(section, cx, cy, cz, Underground.Kind.DEEP_DARK, blockEntities);

            Map<BlockPos, String> emitted = new HashMap<>();
            for (CompoundTag tag : blockEntities) {
                BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                String id = tag.getString("id");
                assertNull(emitted.put(pos, id), "duplicate block entity at " + pos);
                BlockState state = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(ResourceLocation.parse(id));
                assertNotNull(type, "valid block entity type for " + id);
                assertTrue(type.isValid(state), "block entity " + id + " must match block " + state + " at " + pos);
            }

            int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
            for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                Block block = section.getBlockState(lx, ly, lz).getBlock();
                BlockPos pos = new BlockPos(x0 + lx, y0 + ly, z0 + lz);
                if (block == Blocks.SCULK_SENSOR) {
                    sensors++;
                    assertEquals("minecraft:sculk_sensor", emitted.get(pos), "sculk sensor missing block entity at " + pos);
                } else if (block == Blocks.SCULK_SHRIEKER) {
                    shriekers++;
                    assertEquals("minecraft:sculk_shrieker", emitted.get(pos), "sculk shrieker missing block entity at " + pos);
                } else if (block == Blocks.SCULK_CATALYST) {
                    catalysts++;
                    assertEquals("minecraft:sculk_catalyst", emitted.get(pos), "sculk catalyst missing block entity at " + pos);
                }
            }
        }

        assertTrue(sensors > 0, "expected sculk sensors generated");
        assertTrue(shriekers > 0, "expected sculk shriekers generated");
        assertTrue(catalysts > 0, "expected sculk catalysts generated");
    }

    // UNDER-07: ModOres MissingTerrainException propagation, RegistryAccess/DimensionType, read-back

    @Test void modOresPropagatesMissingTerrainWithoutDisablingAndSupportsRegistryAndReadBack() {
        AtomicBoolean directOffline = new AtomicBoolean(true);
        AtomicBoolean wrappedOffline = new AtomicBoolean(true);
        AtomicBoolean checkedContextAndReadBack = new AtomicBoolean(false);

        Feature<NoneFeatureConfiguration> directFlakyFeature = new Feature<>(Codec.unit(NoneFeatureConfiguration.INSTANCE)) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
                if (directOffline.get()) throw new MissingTerrainException("direct missing tile");
                WorldGenLevel level = ctx.level();
                assertNotNull(level.registryAccess(), "UNDER-07: registryAccess() must be non-null");
                assertNotNull(level.dimensionType(), "UNDER-07: dimensionType() must be non-null");
                BlockPos target = firstSolid(level, ctx.origin());
                assertNotNull(target, "expected solid stone block in target cube");
                // Write via setBlock, read back via getBlockState AND via chunk section (Mapped), then overwrite.
                assertTrue(level.setBlock(target, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2));
                assertEquals(Blocks.DIAMOND_BLOCK, level.getBlockState(target).getBlock(),
                        "UNDER-07: OreLevel.getBlockState must read back written state");
                ChunkAccess chunk = level.getChunk(target.getX() >> 4, target.getZ() >> 4);
                LevelChunkSection sec = chunk.getSection(chunk.getSectionIndex(target.getY()));
                assertEquals(Blocks.DIAMOND_BLOCK, sec.getBlockState(target.getX() & 15, target.getY() & 15, target.getZ() & 15).getBlock(),
                        "UNDER-07: Mapped.getBlockState must read back written state");
                // Overwrite same position: original rock must still be preserved as `was` so drop applies onto stone!
                assertTrue(level.setBlock(target, Blocks.GOLD_BLOCK.defaultBlockState(), 2));
                assertEquals(Blocks.GOLD_BLOCK, level.getBlockState(target).getBlock());
                checkedContextAndReadBack.set(true);
                return true;
            }
        };

        Feature<NoneFeatureConfiguration> wrappedFlakyFeature = new Feature<>(Codec.unit(NoneFeatureConfiguration.INSTANCE)) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
                if (wrappedOffline.get()) throw new RuntimeException("wrapped", new MissingTerrainException("wrapped missing tile"));
                BlockPos target = firstSolid(ctx.level(), ctx.origin().offset(0, 8, 0));
                if (target != null) ctx.level().setBlock(target, Blocks.IRON_BLOCK.defaultBlockState(), 2);
                return true;
            }
        };

        PlacedFeature directPlaced = new PlacedFeature(
                Holder.direct(new ConfiguredFeature<>(directFlakyFeature, NoneFeatureConfiguration.INSTANCE)), List.of());
        PlacedFeature wrappedPlaced = new PlacedFeature(
                Holder.direct(new ConfiguredFeature<>(wrappedFlakyFeature, NoneFeatureConfiguration.INSTANCE)), List.of());

        MappedRegistry<Biome> customBiomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        var vanilla = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        vanilla.listElements().forEach(holder -> {
            Biome biome = holder.value();
            if (holder.is(Biomes.PLAINS)) {
                BiomeGenerationSettings.PlainBuilder settings = new BiomeGenerationSettings.PlainBuilder();
                var steps = biome.getGenerationSettings().features();
                for (int i = 0; i < steps.size(); i++)
                    for (Holder<PlacedFeature> f : steps.get(i)) settings.addFeature(i, f);
                settings.addFeature(GenerationStep.Decoration.UNDERGROUND_ORES, Holder.direct(directPlaced));
                settings.addFeature(GenerationStep.Decoration.UNDERGROUND_ORES, Holder.direct(wrappedPlaced));
                Biome.ClimateSettings climate = biome.getModifiedClimateSettings();
                biome = new Biome.BiomeBuilder()
                        .hasPrecipitation(climate.hasPrecipitation())
                        .temperature(climate.temperature())
                        .downfall(climate.downfall())
                        .temperatureAdjustment(climate.temperatureModifier())
                        .specialEffects(biome.getSpecialEffects())
                        .mobSpawnSettings(biome.getMobSettings())
                        .generationSettings(settings.build())
                        .build();
            }
            customBiomes.register(holder.key(), biome, RegistrationInfo.BUILT_IN);
        });
        Registry<Biome> frozen = customBiomes.freeze();
        Holder<Biome> customPlains = frozen.getHolderOrThrow(Biomes.PLAINS);

        SurfaceGenerator world = new SurfaceGenerator(77L, frozen, RandomSource.create(77L)) {
            { legacyCaves(); }
            @Override public String id() { return "mod-ore-audit-test"; }
            @Override public int seaLevel() { return 64; }
            @Override protected int shoreLevel() { return 64; }
            @Override protected boolean emeralds(int maxGround) { return false; }
            @Override protected void ground(int x, int z, int[] out) { out[0] = 80; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int y, int water, int slope) {
                return new Surface(customPlains, GRASS, DIRT, 0, 0, 0, 0, 0, false);
            }
        };

        // Origin of feature is (0, 0, 0) in frame with shift = floor - 64; floor = 80 -> shift = 16 -> real Y = 16 -> cy = 1.
        int targetCy = (world.underground().floor(8, 8) - 64) >> 4;

        // 1. Direct MissingTerrainException propagates and does NOT disable directFlakyFeature.
        assertThrows(MissingTerrainException.class, () -> world.generate(0, targetCy, 0));
        directOffline.set(false);

        // 2. Wrapped MissingTerrainException also propagates and does NOT disable wrappedFlakyFeature.
        assertThrows(RuntimeException.class, () -> world.generate(0, targetCy, 0));
        wrappedOffline.set(false);

        // 3. Once terrain is back, BOTH features run cleanly (neither was permanently poisoned!) and write gold & iron.
        LevelChunkSection section = world.generate(0, targetCy, 0);
        assertTrue(checkedContextAndReadBack.get(), "directFlakyFeature must have executed with valid context & read-back");
        boolean foundGold = false, foundIron = false;
        for (int i = 0; i < 4096; i++) {
            Block b = section.getBlockState(i & 15, i >> 8, i >> 4 & 15).getBlock();
            if (b == Blocks.GOLD_BLOCK) foundGold = true;
            if (b == Blocks.IRON_BLOCK) foundIron = true;
        }
        assertTrue(foundGold, "UNDER-07: overwritten gold block must survive read-back & original `was` preservation");
        assertTrue(foundIron, "UNDER-07: feature that threw wrapped MissingTerrainException must still run after recovery");
    }

    @Nullable private static BlockPos firstSolid(WorldGenLevel level, BlockPos origin) {
        for (int dy = 0; dy < 8; dy++) for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            BlockPos p = origin.offset(dx, dy, dz);
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && s.getFluidState().isEmpty()) return p;
        }
        return null;
    }

    // UNDER-08 & UNDER-09: Cache key distinctness beyond 20-bit/8-bit limits -----------------------

    @Test void caveFieldKeyDistinctBeyondOldTwentyBitCoordinateLimit() {
        int[] xs = {-1_250_000, -1_048_576, 0, 1_048_576, 1_250_000};
        int[] ys = {-512, 0, 512};
        int[] zs = {-1_250_000, 0, 1_250_000};
        Set<Long> keys = new HashSet<>();
        int total = 0;
        for (int x : xs) for (int y : ys) for (int z : zs) {
            total++;
            assertTrue(keys.add(Underground.caveFieldKey(x, y, z)),
                    "UNDER-08: caveFieldKey collision at (" + x + ", " + y + ", " + z + ")");
        }
        assertEquals(total, keys.size());
    }

    @Test void startKeyDistinctForMoreThan256StructuresAndLargeChunkCoords() {
        int[] kinds = {0, 1, 255, 256, 257, 512, 1024};
        int[] coords = {-1_250_000, -100, 0, 100, 1_250_000};
        Set<Long> keys = new HashSet<>();
        int total = 0;
        for (int kind : kinds) for (int cx : coords) for (int cz : coords) {
            total++;
            assertTrue(keys.add(VanillaStructures.startKey(kind, cx, cz)),
                    "UNDER-09: startKey collision at kind=" + kind + ", chunk=(" + cx + ", " + cz + ")");
        }
        assertEquals(total, keys.size());
    }

    // UNDER-10: Amethyst geode reaching cx+2 / cy+2 / cz+2 is not clipped --------------------------

    @Test void amethystGeodeReachingPlusTwoCubeIsNotClipped() {
        // With ground=64, floor=64, vanillaY(y, 64) == y; cy=-1 covers y=-16..-1 (well within [-58, 30] and depth>=24).
        SurfaceGenerator flat = new SurfaceGenerator(42L, biomes, RandomSource.create(42L)) {
            { legacyCaves(); }
            @Override public String id() { return "geode-test"; }
            @Override public int seaLevel() { return 64; }
            @Override protected int shoreLevel() { return 64; }
            @Override protected boolean emeralds(int maxGround) { return false; }
            @Override protected void ground(int x, int z, int[] out) { out[0] = 64; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int y, int water, int slope) {
                return new Surface(plains, GRASS, DIRT, 0, 0, 0, 0, 0, false);
            }
        };
        Structures s = new Structures(flat.underground(), 42L);
        int cy = -1;
        int verifiedCrossings = 0;
        for (int cz = 0; cz < 32 && verifiedCrossings < 3; cz++) {
            for (int cx = 0; cx < 32 && verifiedCrossings < 3; cx++) {
                LevelChunkSection cur = deepslateSection();
                s.place(cur, cx, cy, cz, new ArrayList<>(), new ArrayList<>());
                // Whenever a geode's inner amethyst/calcite wall reaches the east face (lx=15) of cube (cx, cy, cz),
                // the west face (lx=0) of cube (cx+1, cy, cz) must continue the geode (never clipped to plain deepslate!).
                boolean checkedThisCube = false;
                for (int ly = 0; ly < 16 && !checkedThisCube; ly++) {
                    for (int lz = 0; lz < 16 && !checkedThisCube; lz++) {
                        Block eastFace = cur.getBlockState(15, ly, lz).getBlock();
                        if (eastFace == Blocks.AMETHYST_BLOCK || eastFace == Blocks.CALCITE) {
                            LevelChunkSection east = deepslateSection();
                            s.place(east, cx + 1, cy, cz, new ArrayList<>(), new ArrayList<>());
                            Block westFaceNext = east.getBlockState(0, ly, lz).getBlock();
                            assertTrue(westFaceNext == Blocks.SMOOTH_BASALT || westFaceNext == Blocks.CALCITE
                                            || westFaceNext == Blocks.AMETHYST_BLOCK || westFaceNext == Blocks.BUDDING_AMETHYST
                                            || westFaceNext == Blocks.AIR,
                                    "UNDER-10: geode inner wall at (" + cx + "," + cy + "," + cz + ") lx=15 must continue into cube cx+1 without clipping (saw " + westFaceNext + ")");
                            verifiedCrossings++;
                            checkedThisCube = true;
                        }
                    }
                }
            }
        }
        assertTrue(verifiedCrossings > 0, "UNDER-10: must verify geodes crossing cube boundaries without clipping");
    }

    private static LevelChunkSection deepslateSection() {
        return new LevelChunkSection(
                new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.DEEPSLATE.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomes.asHolderIdMap(), plains, PalettedContainer.Strategy.SECTION_BIOMES));
    }

    // UNDER-11: StructureLevel.getChunk(..., false) non-null & bounded getSections() length --------

    @Test void structureLevelGetChunkFalseIsNonNullAndBoundsSectionArray() {
        int[] bounds = StructureLevel.bounds(1000, 1060, CubeWorld.MIN_Y, CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1, 0);
        StructureLevel level = makeStructureLevel(0, bounds[0], bounds[1]);

        ChunkAccess nonRequired = level.getChunk(0, 0, ChunkStatus.FULL, false);
        assertNotNull(nonRequired, "UNDER-11: getChunk(x, z, status, false) must never return null");
        assertSame(nonRequired, level.getChunk(0, 0, ChunkStatus.EMPTY, false), "getChunk(x, z, EMPTY, false) returns the same View");

        int sectionCount = nonRequired.getSections().length;
        assertTrue(sectionCount <= 16,
                "UNDER-11: bounded surface structure frame must allocate a small section array, not 2048 (got " + sectionCount + ")");
        assertTrue(level.getMinBuildHeight() <= 1000 && level.getMaxBuildHeight() > 1060,
                "bounded frame must cover the structure's full Y reach");
    }

    // Helpers --------------------------------------------------------------------------------------

    private static RegistryAccess singleStructureRegistries(String name, Structure structure) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", name);
        MappedRegistry<Structure> structures = new MappedRegistry<>(Registries.STRUCTURE, Lifecycle.stable());
        Holder.Reference<Structure> holder = structures.register(ResourceKey.create(Registries.STRUCTURE, id), structure, RegistrationInfo.BUILT_IN);
        structures.freeze();

        MappedRegistry<StructureSet> sets = new MappedRegistry<>(Registries.STRUCTURE_SET, Lifecycle.stable());
        StructureSet set = new StructureSet(
                List.of(StructureSet.entry(holder)),
                new RandomSpreadStructurePlacement(1, 0, RandomSpreadType.LINEAR, 12345));
        sets.register(ResourceKey.create(Registries.STRUCTURE_SET, id), set, RegistrationInfo.BUILT_IN);
        sets.freeze();

        return new RegistryAccess.ImmutableRegistryAccess(List.of(structures, sets)).freeze();
    }

    private static VanillaStructures.Host hostOf(IntBinaryOperator ground) {
        return new VanillaStructures.Host() {
            @Override public int ground(int x, int z) { return ground.applyAsInt(x, z); }
            @Override public int water(int x, int z) { return 62; }
            @Override public Holder<Biome> biome(int x, int y, int z) { return plains; }
            @Override public int floor(int x, int z) { return ground.applyAsInt(x, z); }
            @Nullable @Override public LevelChunkSection cube(int cx, int cy, int cz) { return null; }
            @Override public BlockState fill(int x, int y, int z, int depth) { return Blocks.STONE.defaultBlockState(); }
            @Override public int seaLevel() { return 63; }
            @Override public Stream<Holder<Biome>> possibleBiomes() { return Stream.of(plains); }
        };
    }

    private static final class StatefulFountainStructure extends Structure {
        StatefulFountainStructure(Holder<Biome> biome) {
            super(new StructureSettings(HolderSet.direct(biome), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE));
        }

        @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
            int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
            int y = context.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                    context.heightAccessor(), context.randomState());
            BoundingBox box = new BoundingBox(x, y, z, x + 8, y + 8, z + 8);
            return Optional.of(new GenerationStub(new BlockPos(x, y, z), b -> b.addPiece(new StatefulFountainPiece(box))));
        }

        @Override public StructureType<?> type() { return () -> MapCodec.unit(this); }
    }

    /** Mimics stateful vanilla pieces (Mineshaft, Mansion, RuinedPortal) that mutate fields during postProcess. */
    private static final class StatefulFountainPiece extends StructurePiece {
        boolean postProcessed;

        StatefulFountainPiece(BoundingBox box) {
            super(StructurePieceType.JIGSAW, 0, box);
        }

        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {}

        @Override public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                                          RandomSource random, BoundingBox box, ChunkPos chunk, BlockPos pos) {
            if (postProcessed) return;
            postProcessed = true;
            BlockPos fountain = new BlockPos(boundingBox.minX() + 4, boundingBox.minY() + 1, boundingBox.minZ() + 4);
            level.setBlock(fountain, Blocks.WATER.defaultBlockState(), 2);
            level.scheduleTick(fountain, Fluids.WATER, 0);
        }
    }

    private static final class SimplePiece extends StructurePiece {
        private final java.util.function.BiConsumer<WorldGenLevel, BoundingBox> action;

        SimplePiece(BoundingBox box, java.util.function.BiConsumer<WorldGenLevel, BoundingBox> action) {
            super(StructurePieceType.JIGSAW, 0, box);
            this.action = action;
        }

        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {}

        @Override public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                                          RandomSource random, BoundingBox box, ChunkPos chunk, BlockPos pos) {
            action.accept(level, boundingBox);
        }
    }

    /** Lightweight Entity subclass for exercising Entity.setPos, getSelfAndPassengers, and saveAsPassenger without a ServerLevel. */
    private static final class StubEntity extends Entity {
        private Entity stubVehicle;

        StubEntity(EntityType<?> type) {
            super(type, (Level) null);
        }

        void mount(StubEntity passenger) {
            passenger.stubVehicle = this;
            addPassenger(passenger);
        }

        @Override public RegistryAccess registryAccess() {
            return RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        }
        @Override public void gameEvent(Holder<net.minecraft.world.level.gameevent.GameEvent> event, @Nullable Entity entity) {}
        @Override @Nullable public Entity getVehicle() { return stubVehicle; }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    }
}
