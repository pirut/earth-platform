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
import net.minecraft.server.packs.resources.ResourceManager;
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
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
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

        // 2. Tick scheduled via View (ProtoChunk) tick access (including huge triggerTick clamping).
        BlockPos sandFrame = new BlockPos(20, -28, 3); // real Y = 972, cube (1, 60, 0)
        level.setBlock(sandFrame, Blocks.SAND.defaultBlockState(), 2);
        ChunkAccess view = level.getChunk(1, 0);
        view.getBlockTicks().schedule(new ScheduledTick<>(Blocks.SAND, sandFrame, 3L, TickPriority.NORMAL, 0L));
        BlockPos farFutureFrame = new BlockPos(21, -28, 3);
        level.setBlock(farFutureFrame, Blocks.GRAVEL.defaultBlockState(), 2);
        view.getBlockTicks().schedule(new ScheduledTick<>(Blocks.GRAVEL, farFutureFrame, Long.MAX_VALUE, TickPriority.NORMAL, 0L));

        // 3. Tick whose block is later overwritten -> filtered out when drained.
        BlockPos overwrittenFrame = new BlockPos(6, -30, 9);
        level.setBlock(overwrittenFrame, Blocks.LAVA.defaultBlockState(), 2);
        level.scheduleTick(overwrittenFrame, Fluids.LAVA, 0);
        level.setBlock(overwrittenFrame, Blocks.STONE.defaultBlockState(), 2);

        // 4a. Waterlogged stair post-processed -> updateFromNeighbourShapes + fluid check captures WATER tick.
        BlockPos stairFrame = new BlockPos(7, -30, 9);
        level.setBlock(stairFrame, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.WATERLOGGED, true), 2);
        level.setBlock(stairFrame.east(), Blocks.AIR.defaultBlockState(), 2);
        level.getChunk(0, 0).markPosForPostprocessing(stairFrame);

        // 4b. Plain water source marked for postprocessing (no explicit scheduleTick) -> postProcessAll captures delay-0 WATER tick!
        BlockPos plainWaterFrame = new BlockPos(9, -30, 9);
        level.setBlock(plainWaterFrame, Blocks.WATER.defaultBlockState(), 2);
        level.getChunk(0, 0).markPosForPostprocessing(plainWaterFrame);

        level.postProcessAll();

        List<CubeCodec.PendingTick<Block>> blocks = level.capturedBlockTicks();
        List<CubeCodec.PendingTick<Fluid>> fluids = level.capturedFluidTicks();

        assertEquals(2, blocks.size(), "sand and clamped gravel block ticks captured via View");
        assertEquals(new BlockPos(20, 972, 3), blocks.get(0).pos());
        assertEquals(Blocks.SAND, blocks.get(0).type());
        assertEquals(3, blocks.get(0).delay());
        assertEquals(Integer.MAX_VALUE, blocks.get(1).delay(), "long-to-int tick delay must clamp at Integer.MAX_VALUE");

        assertTrue(fluids.stream().noneMatch(t -> t.type() == Fluids.LAVA), "overwritten lava tick must be filtered out");
        var fountainTick = fluids.stream().filter(t -> t.pos().equals(new BlockPos(5, 970, 9))).findFirst().orElseThrow();
        assertEquals(Fluids.WATER, fountainTick.type());
        assertEquals(0, fountainTick.delay(), "dedup keeps shortest delay");
        assertEquals(TickPriority.HIGH, fountainTick.priority(), "dedup keeps highest priority");
        assertTrue(fluids.stream().anyMatch(t -> t.pos().equals(new BlockPos(7, 970, 9)) && t.type() == Fluids.WATER),
                "postProcessAll waterlogged stair must capture water tick at real Y");
        var plainWaterTick = fluids.stream().filter(t -> t.pos().equals(new BlockPos(9, 970, 9))).findFirst().orElseThrow();
        assertEquals(Fluids.WATER, plainWaterTick.type());
        assertEquals(0, plainWaterTick.delay(), "plain fluid marked for postprocessing must capture delay-0 fluid tick");

        // 5. VanillaStructures.patches buckets ticks into their respective cubes.
        var patches = VanillaStructures.patches(level);
        long cube0 = SectionPos.asLong(0, 970 >> 4, 0);
        long cube1 = SectionPos.asLong(1, 972 >> 4, 0);
        VanillaStructures.Patch p0 = patches.get(cube0);
        VanillaStructures.Patch p1 = patches.get(cube1);
        assertNotNull(p0);
        assertNotNull(p1);
        assertTrue(p0.fluidTicks().stream().anyMatch(t -> t.pos().equals(new BlockPos(5, 970, 9))));
        assertTrue(p0.fluidTicks().stream().anyMatch(t -> t.pos().equals(new BlockPos(9, 970, 9))));
        assertEquals(2, p1.blockTicks().size());
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

    @Test void settledShiftZeroSurfaceStructureUpdatesRealBoundsWhileKeepingCachedStartPristine() {
        int earthGround = 180;
        Structure hut = new Structure(new Structure.StructureSettings(
                HolderSet.direct(plains), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE)) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                if (context.chunkPos().x != 0 || context.chunkPos().z != 0) return Optional.empty();
                int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
                // Single-piece surface structure starts at vanilla Y 64, then settles to Earth ground in postProcess.
                BoundingBox initial = new BoundingBox(x, 64, z, x + 7, 71, z + 7);
                return Optional.of(new GenerationStub(new BlockPos(x, 64, z),
                        b -> b.addPiece(new SettlingSurfacePiece(initial))));
            }
            @Override public StructureType<?> type() { return () -> MapCodec.unit(this); }
        };
        RegistryAccess registries = singleStructureRegistries("test_settling_hut", hut);
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> earthGround), 1L);

        // Before build(), cached start sits at vanilla Y 64..71 and startsAt populates `known`.
        StructureStart cachedBefore = structures.startOf(hut, 0, 0);
        assertNotNull(cachedBefore);
        assertEquals(64, cachedBefore.getBoundingBox().minY());
        assertEquals(64, structures.startsAt(0, 0).get(0).getBoundingBox().minY());

        // Building the overlay settles the working copy to earthGround (180..187).
        LevelChunkSection section = airSection();
        structures.place(section, 0, earthGround >> 4, 0, new ArrayList<>(), new ArrayList<>());
        assertEquals(Blocks.SPRUCE_PLANKS, section.getBlockState(2, earthGround & 15, 2).getBlock(),
                "settled hut must place at Earth ground height");

        // Cached start.start must stay pristine at Y 64..71 for future rebuilds!
        assertEquals(64, cachedBefore.getPieces().get(0).getBoundingBox().minY(),
                "UNDER-02: cached start.start piece must stay pristine at original Y");

        // real() / startsAt() / startOf() / locate() must all reflect settled Earth ground bounds (180..187).
        StructureStart settledFromStartOf = structures.startOf(hut, 0, 0);
        assertNotNull(settledFromStartOf);
        assertEquals(earthGround, settledFromStartOf.getBoundingBox().minY(), "startOf must reflect settled Y");
        assertEquals(earthGround + 7, settledFromStartOf.getBoundingBox().maxY());

        List<StructureStart> settledStartsAt = structures.startsAt(0, 0);
        assertEquals(1, settledStartsAt.size());
        assertEquals(earthGround, settledStartsAt.get(0).getBoundingBox().minY(),
                "startsAt must refresh known and reflect settled Y bounds");

        BlockPos located = structures.locate(s -> s == hut, 0, 0, 4);
        assertNotNull(located);
        assertTrue(located.getY() >= earthGround && located.getY() <= earthGround + 7,
                "locate must return settled center Y (got " + located.getY() + ")");

        // Evicting overlay cache and rebuilding must still produce identical settled blocks.
        structures.clearOverlaysForTest();
        LevelChunkSection rebuilt = airSection();
        structures.place(rebuilt, 0, earthGround >> 4, 0, new ArrayList<>(), new ArrayList<>());
        assertEquals(Blocks.SPRUCE_PLANKS, rebuilt.getBlockState(2, earthGround & 15, 2).getBlock());
        assertEquals(64, cachedBefore.getPieces().get(0).getBoundingBox().minY());
    }

    @Test void freshStartExercisesRealNbtCloneRejectsPartialCloneAndNeverMutatesCachedStart() {
        // Case A: Real NBT clone succeeds on a serializable vanilla piece without regenerating via makeStart.
        AtomicInteger fullCloneGens = new AtomicInteger();
        Structure serializable = new Structure(new Structure.StructureSettings(
                HolderSet.direct(plains), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE)) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                if (context.chunkPos().x != 0 || context.chunkPos().z != 0) return Optional.empty();
                fullCloneGens.incrementAndGet();
                int x = context.chunkPos().getMinBlockX() + 4, z = context.chunkPos().getMinBlockZ() + 4;
                return Optional.of(new GenerationStub(new BlockPos(x, 100, z),
                        b -> b.addPiece(new BuriedTreasurePieces.BuriedTreasurePiece(new BlockPos(x, 100, z)))));
            }
            @Override public StructureType<?> type() { return () -> MapCodec.unit(this); }
        };
        RegistryAccess fullRegs = singleStructureRegistries("test_nbt_full_clone", serializable);
        VanillaStructures fullStructures = new VanillaStructures(null, fullRegs, randomState, true, hostOf((x, z) -> 100), 1L);
        fullStructures.serializationContextForTest(new StructurePieceSerializationContext(ResourceManager.Empty.INSTANCE, fullRegs, null));

        assertNotNull(fullStructures.startOf(serializable, 0, 0));
        assertEquals(1, fullCloneGens.get());
        fullStructures.place(airSection(), 0, 100 >> 4, 0, new ArrayList<>(), new ArrayList<>());
        assertEquals(1, fullCloneGens.get(),
                "UNDER-02: complete NBT clone must be used directly without regenerating via makeStart");

        // Case B: Partial NBT clone (piece 0 serializable, piece 1 drops on loadStaticStart) must be rejected and regenerated!
        AtomicInteger partialGens = new AtomicInteger();
        AtomicBoolean secondPieceRan = new AtomicBoolean(false);
        Structure partial = new Structure(new Structure.StructureSettings(
                HolderSet.direct(plains), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE)) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                if (context.chunkPos().x != 0 || context.chunkPos().z != 0) return Optional.empty();
                partialGens.incrementAndGet();
                int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
                BoundingBox box = new BoundingBox(x, 100, z, x + 8, 108, z + 8);
                return Optional.of(new GenerationStub(new BlockPos(x, 100, z), b -> {
                    b.addPiece(new BuriedTreasurePieces.BuriedTreasurePiece(new BlockPos(x + 2, 100, z + 2)));
                    // SimplePiece uses StructurePieceType.JIGSAW without pool_element NBT, so loadStaticStart drops it (1 < 2 pieces).
                    b.addPiece(new SimplePiece(box, (lvl, bx) -> {
                        secondPieceRan.set(true);
                        lvl.setBlock(new BlockPos(x + 5, 101, z + 5), Blocks.GOLD_BLOCK.defaultBlockState(), 2);
                    }));
                }));
            }
            @Override public StructureType<?> type() { return () -> MapCodec.unit(this); }
        };
        RegistryAccess partialRegs = singleStructureRegistries("test_nbt_partial_clone", partial);
        VanillaStructures partialStructures = new VanillaStructures(null, partialRegs, randomState, true, hostOf((x, z) -> 100), 1L);
        partialStructures.serializationContextForTest(new StructurePieceSerializationContext(ResourceManager.Empty.INSTANCE, partialRegs, null));

        assertNotNull(partialStructures.startOf(partial, 0, 0));
        assertEquals(1, partialGens.get());
        LevelChunkSection partialSec = airSection();
        partialStructures.place(partialSec, 0, 100 >> 4, 0, new ArrayList<>(), new ArrayList<>());
        assertEquals(2, partialGens.get(), "UNDER-02: partial NBT clone (1/2 pieces) must be rejected and regenerated");
        assertTrue(secondPieceRan.get(), "regenerated start must run all pieces");
        assertEquals(Blocks.GOLD_BLOCK, partialSec.getBlockState(5, 101 & 15, 5).getBlock());

        // Case C: If clone fails AND regeneration returns empty/null, build() returns empty and NEVER mutates cached start.start!
        AtomicBoolean allowGenerate = new AtomicBoolean(true);
        Structure oneShot = new Structure(new Structure.StructureSettings(
                HolderSet.direct(plains), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE)) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                if (context.chunkPos().x != 0 || context.chunkPos().z != 0) return Optional.empty();
                if (!allowGenerate.get()) return Optional.empty();
                int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
                BoundingBox box = new BoundingBox(x, 100, z, x + 8, 108, z + 8);
                return Optional.of(new GenerationStub(new BlockPos(x, 100, z), b -> b.addPiece(new StatefulFountainPiece(box))));
            }
            @Override public StructureType<?> type() { return () -> MapCodec.unit(this); }
        };
        RegistryAccess failRegs = singleStructureRegistries("test_nbt_failed_fallback", oneShot);
        VanillaStructures failStructures = new VanillaStructures(null, failRegs, randomState, true, hostOf((x, z) -> 100), 1L);
        failStructures.serializationContextForTest(new StructurePieceSerializationContext(ResourceManager.Empty.INSTANCE, failRegs, null));

        StructureStart cachedOneShot = failStructures.startOf(oneShot, 0, 0);
        assertNotNull(cachedOneShot);
        StatefulFountainPiece cachedPiece = (StatefulFountainPiece) cachedOneShot.getPieces().get(0);
        allowGenerate.set(false);

        LevelChunkSection failSec = airSection();
        failStructures.place(failSec, 0, 102 >> 4, 0, new ArrayList<>(), new ArrayList<>());
        assertFalse(cachedPiece.postProcessed,
                "UNDER-02: failed clone+regeneration must return empty overlay and NEVER mutate cached start.start");
        assertEquals(Blocks.AIR, failSec.getBlockState(4, 102 & 15, 4).getBlock());
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

    // UNDER-05: Cold STARTS single-flight, MissingTerrainException boundaries & reentrant deadlock safety

    @Test void coldStartsDeduplicateConcurrentCallersAndMissingTerrainRespectsGenerationVsLookupBoundaries() throws Exception {
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

        // Part B: Generation paths propagate MissingTerrainException without poisoning cache/backoff;
        // lookup boundaries (locate, startOf, startsAt) return null / List.of() cleanly without throwing or poisoning.
        AtomicInteger offlineMode = new AtomicInteger(1); // 1 = direct, 2 = wrapped, 0 = online
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
                    int mode = offlineMode.get();
                    if (mode == 1) throw new MissingTerrainException("tile offline");
                    if (mode == 2) throw new RuntimeException("wrapped", new MissingTerrainException("wrapped tile offline"));
                    return 100;
                }), 1L);

        // Generation paths exceed MOST_START_FAILURES (3): always propagate MissingTerrainException, never poison cache.
        for (int i = 0; i < 6; i++) {
            assertThrows(MissingTerrainException.class, () -> resilient.clears(8, 8),
                    "generation clears() attempt " + i + " must propagate MissingTerrainException");
            assertThrows(MissingTerrainException.class,
                    () -> resilient.place(airSection(), 0, 100 >> 4, 0, new ArrayList<>(), new ArrayList<>()),
                    "generation place() attempt " + i + " must propagate MissingTerrainException");
        }

        // Lookup boundaries must NEVER throw MissingTerrainException (direct or wrapped) or poison cache.
        for (int mode : new int[]{1, 2}) {
            offlineMode.set(mode);
            assertNull(resilient.startOf(simple, 0, 0), "startOf must return null on missing terrain (mode " + mode + ")");
            assertTrue(resilient.startsAt(0, 0).isEmpty(), "startsAt must return empty on missing terrain (mode " + mode + ")");
            assertNull(resilient.locate(s -> s == simple, 0, 0, 2), "locate must return null on missing terrain (mode " + mode + ")");
        }

        // Once terrain returns, both lookups and generation succeed immediately without backoff or poisoned cache.
        offlineMode.set(0);
        assertNotNull(resilient.startOf(simple, 0, 0), "startOf must succeed immediately once terrain returns");
        assertFalse(resilient.startsAt(0, 0).isEmpty(), "startsAt must succeed immediately once terrain returns");
        assertNotNull(resilient.locate(s -> s == simple, 0, 0, 2), "locate must succeed immediately once terrain returns");
    }

    @Test void reentrantStartSelfReentryAndLockInversionNeverDeadlock() throws Exception {
        // Part A: Same-key self-reentry inside findGenerationPoint (holding STARTS) returns without deadlock or empty-column poisoning.
        VanillaStructures[] selfRef = new VanillaStructures[1];
        AtomicBoolean reenteredSelf = new AtomicBoolean(false);
        Structure selfReentrant = new Structure(new Structure.StructureSettings(
                HolderSet.direct(plains), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE)) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                if (context.chunkPos().equals(new ChunkPos(0, 0)) && reenteredSelf.compareAndSet(false, true)) {
                    assertNull(selfRef[0].startOf(this, 0, 0), "same-key reentrant startOf must be cache-only (null)");
                    assertTrue(selfRef[0].startsAt(0, 0).isEmpty(), "same-key reentrant startsAt must not deadlock");
                }
                int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
                BoundingBox box = new BoundingBox(x, 100, z, x + 8, 108, z + 8);
                return Optional.of(new GenerationStub(new BlockPos(x, 100, z),
                        b -> b.addPiece(new SimplePiece(box, (lvl, bx) -> {}))));
            }
            @Override public StructureType<?> type() { return () -> MapCodec.unit(this); }
        };
        VanillaStructures selfWorld = new VanillaStructures(null,
                singleStructureRegistries("test_self_reentry", selfReentrant), randomState, true, hostOf((x, z) -> 100), 1L);
        selfRef[0] = selfWorld;

        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            StructureStart result = single.submit(() -> selfWorld.startOf(selfReentrant, 0, 0)).get(5, TimeUnit.SECONDS);
            assertNotNull(result, "same-key reentrant start must complete cleanly");
            assertTrue(reenteredSelf.get());
            assertFalse(selfWorld.startsAt(0, 0).isEmpty(), "reentrant startsAt must not have cached an empty column");
        } finally {
            single.shutdownNow();
        }

        // Part B: Two-worker AB-BA lock inversion:
        // Worker A holds STARTS for chunk (0, 0); Worker B claims computingStarts for (1, 0) and blocks on STARTS;
        // Worker A then calls startOf(1, 0) while holding STARTS -> must stay cache-only and NEVER deadlock!
        VanillaStructures[] pairRef = new VanillaStructures[1];
        CountDownLatch workerAInStarts = new CountDownLatch(1);
        CountDownLatch workerBBlockedOnStarts = new CountDownLatch(1);
        Structure crossReentrant = new Structure(new Structure.StructureSettings(
                HolderSet.direct(plains), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE)) {
            @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
                if (context.chunkPos().equals(new ChunkPos(0, 0))) {
                    workerAInStarts.countDown();
                    try { assertTrue(workerBBlockedOnStarts.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    // Worker B currently owns computingStarts(1, 0) and is BLOCKED on STARTS: must return null without joining!
                    assertNull(pairRef[0].startOf(this, 1, 0),
                            "UNDER-05: reentrant startOf for another worker's in-flight key must return null without deadlock");
                }
                int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
                BoundingBox box = new BoundingBox(x, 100, z, x + 8, 108, z + 8);
                return Optional.of(new GenerationStub(new BlockPos(x, 100, z),
                        b -> b.addPiece(new SimplePiece(box, (lvl, bx) -> {}))));
            }
            @Override public StructureType<?> type() { return () -> MapCodec.unit(this); }
        };
        VanillaStructures pairWorld = new VanillaStructures(null,
                singleStructureRegistries("test_lock_inversion", crossReentrant), randomState, true, hostOf((x, z) -> 100), 1L);
        pairRef[0] = pairWorld;

        StructureStart[] results = new StructureStart[2];
        Throwable[] errors = new Throwable[2];
        Thread workerA = new Thread(() -> {
            try { results[0] = pairWorld.startOf(crossReentrant, 0, 0); }
            catch (Throwable t) { errors[0] = t; }
        }, "test-worker-A");
        Thread workerB = new Thread(() -> {
            try { results[1] = pairWorld.startOf(crossReentrant, 1, 0); }
            catch (Throwable t) { errors[1] = t; }
        }, "test-worker-B");

        workerA.start();
        assertTrue(workerAInStarts.await(5, TimeUnit.SECONDS), "worker A must acquire STARTS");
        workerB.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (workerB.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(Thread.State.BLOCKED, workerB.getState(),
                "worker B must have claimed computingStarts(1, 0) and blocked on STARTS");
        workerBBlockedOnStarts.countDown();

        workerA.join(5_000);
        workerB.join(5_000);
        assertFalse(workerA.isAlive(), "UNDER-05: worker A deadlocked");
        assertFalse(workerB.isAlive(), "UNDER-05: worker B deadlocked");
        assertNull(errors[0], () -> "worker A threw: " + errors[0]);
        assertNull(errors[1], () -> "worker B threw: " + errors[1]);
        assertNotNull(results[0], "worker A start (0, 0) must succeed");
        assertNotNull(results[1], "worker B start (1, 0) must succeed");
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

    @Test void modOresEnforcesLowerWriteBoundConsistentlyWithDeepCubeCutoff() {
        AtomicBoolean checkedBounds = new AtomicBoolean(false);
        Feature<NoneFeatureConfiguration> boundaryFeature = new Feature<>(Codec.unit(NoneFeatureConfiguration.INSTANCE)) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
                WorldGenLevel level = ctx.level();
                int x = ctx.origin().getX() + 4, z = ctx.origin().getZ() + 4;
                BlockPos atMin = new BlockPos(x, -64, z);
                BlockPos belowMin = new BlockPos(x, -65, z);
                BlockPos aboveMax = new BlockPos(x, 320, z);
                assertTrue(level.ensureCanWrite(atMin), "UNDER-07: vanillaY == -64 must be writable");
                assertFalse(level.ensureCanWrite(belowMin), "UNDER-07: vanillaY == -65 must be outside build height");
                assertFalse(level.ensureCanWrite(aboveMax), "UNDER-07: vanillaY == 320 must be outside build height");
                assertFalse(level.setBlock(belowMin, Blocks.EMERALD_BLOCK.defaultBlockState(), 2),
                        "UNDER-07: write below VANILLA_MIN must be rejected consistently with deep-cube cutoff");
                // Write at vanillaY == -64 on a solid column inside chunk (0, 0).
                for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                    BlockPos candidate = new BlockPos(ctx.origin().getX() + dx, -64, ctx.origin().getZ() + dz);
                    BlockState s = level.getBlockState(candidate);
                    if (!s.isAir() && s.getFluidState().isEmpty()) {
                        assertTrue(level.setBlock(candidate, Blocks.EMERALD_BLOCK.defaultBlockState(), 2),
                                "UNDER-07: write at vanillaY == -64 must succeed on solid rock");
                        checkedBounds.set(true);
                        return true;
                    }
                }
                return false;
            }
        };

        PlacedFeature placed = new PlacedFeature(
                Holder.direct(new ConfiguredFeature<>(boundaryFeature, NoneFeatureConfiguration.INSTANCE)), List.of());
        MappedRegistry<Biome> customBiomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        var vanilla = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        vanilla.listElements().forEach(holder -> {
            Biome biome = holder.value();
            if (holder.is(Biomes.PLAINS) || holder.is(Biomes.LUSH_CAVES) || holder.is(Biomes.DRIPSTONE_CAVES) || holder.is(Biomes.DEEP_DARK)) {
                BiomeGenerationSettings.PlainBuilder settings = new BiomeGenerationSettings.PlainBuilder();
                var steps = biome.getGenerationSettings().features();
                for (int i = 0; i < steps.size(); i++)
                    for (Holder<PlacedFeature> f : steps.get(i)) settings.addFeature(i, f);
                settings.addFeature(GenerationStep.Decoration.UNDERGROUND_ORES, Holder.direct(placed));
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

        SurfaceGenerator world = new SurfaceGenerator(88L, frozen, RandomSource.create(88L)) {
            { legacyCaves(); }
            @Override public String id() { return "mod-ore-lower-bound-test"; }
            @Override public int seaLevel() { return 64; }
            @Override protected int shoreLevel() { return 64; }
            @Override protected boolean emeralds(int maxGround) { return false; }
            @Override protected void ground(int x, int z, int[] out) { out[0] = 80; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int y, int water, int slope) {
                return new Surface(customPlains, GRASS, DIRT, 0, 0, 0, 0, 0, false);
            }
        };

        int floor = world.underground().floor(8, 8);
        int minRealY = ModOres.minRealY(floor);
        int minCy = minRealY >> 4;
        LevelChunkSection atMinCube = world.generate(0, minCy, 0);
        assertTrue(checkedBounds.get(), "boundary feature must execute and write at VANILLA_MIN");
        boolean foundEmerald = false;
        for (int i = 0; i < 4096; i++) {
            if (atMinCube.getBlockState(i & 15, i >> 8, i >> 4 & 15).getBlock() == Blocks.EMERALD_BLOCK) {
                foundEmerald = true;
                break;
            }
        }
        assertTrue(foundEmerald, "UNDER-07: write at vanillaY == -64 must reach destination cube at minRealY");

        // Cube strictly below minRealY must receive no emerald blocks.
        LevelChunkSection belowCube = world.generate(0, minCy - 1, 0);
        for (int i = 0; i < 4096; i++) {
            assertNotEquals(Blocks.EMERALD_BLOCK, belowCube.getBlockState(i & 15, i >> 8, i >> 4 & 15).getBlock(),
                    "UNDER-07: cube below minRealY must never receive out-of-frame writes");
        }
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
        // With ground=64, floor=64, vanillaY(y, 64) == y; cy in [-3..0] covers y in [-48..15] (well within [-58, 30] and depth>=24).
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
        boolean verifiedPlusTwoCube = false;
        for (int oy = -3; oy <= 0 && !verifiedPlusTwoCube; oy++) {
            for (int oz = 0; oz < 128 && !verifiedPlusTwoCube; oz++) {
                for (int ox = 0; ox < 128 && !verifiedPlusTwoCube; ox++) {
                    int[] first = s.geodeOriginForTest(ox, oy, oz);
                    if (first == null) continue;
                    // Check any axis where first + 12 reaches cube origin + 2 (requiring the -2 neighborhood lookup).
                    int[][] candidates = {
                            first[0] + 12 >= ((ox + 2) << 4) ? new int[]{ox + 2, first[1] >> 4, first[2] >> 4} : null,
                            first[1] + 12 >= ((oy + 2) << 4) ? new int[]{first[0] >> 4, oy + 2, first[2] >> 4} : null,
                            first[2] + 12 >= ((oz + 2) << 4) ? new int[]{first[0] >> 4, first[1] >> 4, oz + 2} : null
                    };
                    for (int[] c : candidates) {
                        if (c == null) continue;
                        if (hasAnyGeodeInMinusOneToPlusOne(s, c[0], c[1], c[2])) continue;
                        LevelChunkSection plusTwo = deepslateSection();
                        s.place(plusTwo, c[0], c[1], c[2], new ArrayList<>(), new ArrayList<>());
                        for (int i = 0; i < 4096; i++) {
                            Block b = plusTwo.getBlockState(i & 15, i >> 8, i >> 4 & 15).getBlock();
                            if (b == Blocks.SMOOTH_BASALT || b == Blocks.CALCITE || b == Blocks.AMETHYST_BLOCK) {
                                verifiedPlusTwoCube = true;
                                break;
                            }
                        }
                        if (verifiedPlusTwoCube) break;
                    }
                }
            }
        }
        assertTrue(verifiedPlusTwoCube,
                "UNDER-10: geode originating in cube o must place shell blocks into cube o+2 via -2 neighborhood lookup");
    }

    private static boolean hasAnyGeodeInMinusOneToPlusOne(Structures s, int cx, int cy, int cz) {
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (s.geodeOriginForTest(cx + dx, cy + dy, cz + dz) != null) return true;
        }
        return false;
    }

    private static LevelChunkSection deepslateSection() {
        return new LevelChunkSection(
                new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.DEEPSLATE.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomes.asHolderIdMap(), plains, PalettedContainer.Strategy.SECTION_BIOMES));
    }

    // UNDER-11: StructureLevel.getChunk(..., false) non-null & bounded getSections() length --------

    @Test void structureLevelGetChunkFalseIsNonNullAndBoundsSectionArray() {
        int[] bounds = StructureLevel.bounds(1000, 1060, CubeWorld.MIN_Y, CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1, 0);
        assertNotNull(bounds);
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

    @Test void structureLevelBoundsAndWritesClampAtWorldEdgesWithNonAlignedShifts() {
        int fullHeight = CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1;

        // 1. Reaches wholly outside cubic world bounds return null.
        assertNull(StructureLevel.bounds(CubeWorld.MAX_Y + 1, CubeWorld.MAX_Y + 32, CubeWorld.MIN_Y, fullHeight, 0),
                "reach wholly above MAX_Y must return null");
        assertNull(StructureLevel.bounds(CubeWorld.MIN_Y - 32, CubeWorld.MIN_Y - 1, CubeWorld.MIN_Y, fullHeight, 0),
                "reach wholly below MIN_Y must return null");
        assertNull(StructureLevel.bounds(100, 120, -64, 384, CubeWorld.MAX_Y + 100),
                "frame shifted wholly above MAX_Y must return null");

        // 2. Reach above frame top clamps lo <= clampMax - 16 so section top never exceeds clampMax.
        int[] aboveFrameTop = StructureLevel.bounds(500, 520, -64, 384, 0);
        assertNotNull(aboveFrameTop);
        assertTrue(aboveFrameTop[0] >= -64 && aboveFrameTop[0] + aboveFrameTop[1] <= 320,
                "section bounds must never exceed [clampMin, clampMax]");

        // 3. Top world edge (CubeWorld.MAX_Y) with non-section-aligned shift = +1:
        // Outward section allocation can include frame Y = MAX_Y (real Y = MAX_Y + 1), which MUST be rejected on write!
        int[] topPlus1 = StructureLevel.bounds(CubeWorld.MAX_Y - 8, CubeWorld.MAX_Y, CubeWorld.MIN_Y, fullHeight, 1);
        assertNotNull(topPlus1);
        StructureLevel topLevel = makeStructureLevel(1, topPlus1[0], topPlus1[1]);
        BlockPos frameAtRealMax = new BlockPos(4, CubeWorld.MAX_Y - 1, 4); // real Y = MAX_Y
        BlockPos frameAboveRealMax = new BlockPos(4, CubeWorld.MAX_Y, 4);  // real Y = MAX_Y + 1
        assertTrue(topLevel.setBlock(frameAtRealMax, Blocks.STONE.defaultBlockState(), 2),
                "write at exact real Y == MAX_Y must succeed");
        assertFalse(topLevel.setBlock(frameAboveRealMax, Blocks.STONE.defaultBlockState(), 2),
                "UNDER-11: write at real Y == MAX_Y + 1 must be rejected even inside section allocation");
        topLevel.scheduleTick(frameAboveRealMax, Blocks.SAND, 0);
        assertTrue(topLevel.capturedBlockTicks().isEmpty(), "out-of-world tick above MAX_Y must be rejected");

        // 4. Bottom world edge (CubeWorld.MIN_Y) with non-section-aligned shift = -1:
        // Outward section allocation can include frame Y = MIN_Y (real Y = MIN_Y - 1), which MUST be rejected on write!
        int[] botMinus1 = StructureLevel.bounds(CubeWorld.MIN_Y, CubeWorld.MIN_Y + 8, CubeWorld.MIN_Y, fullHeight, -1);
        assertNotNull(botMinus1);
        StructureLevel botLevel = makeStructureLevel(-1, botMinus1[0], botMinus1[1]);
        BlockPos frameAtRealMin = new BlockPos(4, CubeWorld.MIN_Y + 1, 4); // real Y = MIN_Y
        BlockPos frameBelowRealMin = new BlockPos(4, CubeWorld.MIN_Y, 4);  // real Y = MIN_Y - 1
        assertTrue(botLevel.setBlock(frameAtRealMin, Blocks.STONE.defaultBlockState(), 2),
                "write at exact real Y == MIN_Y must succeed");
        assertFalse(botLevel.setBlock(frameBelowRealMin, Blocks.STONE.defaultBlockState(), 2),
                "UNDER-11: write at real Y == MIN_Y - 1 must be rejected even inside section allocation");
        botLevel.scheduleTick(frameBelowRealMin, Blocks.SAND, 0);
        assertTrue(botLevel.capturedBlockTicks().isEmpty(), "out-of-world tick below MIN_Y must be rejected");
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

    /** Mimics vanilla surface pieces (SwampHut, JungleTemple, Igloo) that settle onto ground height during postProcess. */
    private static final class SettlingSurfacePiece extends StructurePiece {
        private boolean settled;

        SettlingSurfacePiece(BoundingBox box) {
            super(StructurePieceType.JIGSAW, 0, box);
        }

        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {}

        @Override public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                                          RandomSource random, BoundingBox box, ChunkPos chunk, BlockPos pos) {
            if (!settled) {
                settled = true;
                int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, boundingBox.minX() + 2, boundingBox.minZ() + 2) - 1;
                move(0, ground - boundingBox.minY(), 0);
            }
            level.setBlock(new BlockPos(boundingBox.minX() + 2, boundingBox.minY(), boundingBox.minZ() + 2),
                    Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
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
