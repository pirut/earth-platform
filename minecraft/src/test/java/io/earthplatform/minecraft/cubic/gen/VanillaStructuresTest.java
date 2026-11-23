package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntBinaryOperator;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for natural structure toggle ({@code generateStructures == false}) and surface slope/terrain
 * adaptation limits that keep surface structures off steep mountain walls.
 */
class VanillaStructuresTest {
    private static Holder<Biome> plains;
    private static RandomState randomState;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var lookup = VanillaRegistries.createLookup();
        plains = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        NoiseGeneratorSettings noise = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        randomState = RandomState.create(noise, lookup.lookupOrThrow(Registries.NOISE), 1L);
    }

    // Issue #239: "No structures" world option ---------------------------------------------------

    @Test void shouldGenerateHonorsStructureManagerFlag() {
        StructureManager off = new StructureManager(null, new WorldOptions(1L, false, false), null);
        StructureManager on = new StructureManager(null, new WorldOptions(1L, true, false), null);
        assertFalse(VanillaStructures.shouldGenerate(off));
        assertTrue(VanillaStructures.shouldGenerate(on));
        assertTrue(VanillaStructures.shouldGenerate(null));
    }

    @Test void disabledStructuresProduceNoStartsLookupsOrClearing() {
        TestStructure surface = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.BEARD_THIN, 12);
        RegistryAccess registries = singleStructureRegistries("test_village", surface);
        VanillaStructures.Host failIfQueried = hostOf((x, z) -> { throw new AssertionError("ground queried when structures disabled"); });
        VanillaStructures structures = new VanillaStructures(null, registries, randomState, false, failIfQueried, 1L);

        assertTrue(structures.startsAt(0, 0).isEmpty());
        assertNull(structures.startOf(surface, 0, 0));
        assertNull(structures.locate(s -> true, 8, 8, 4));
        assertFalse(structures.clears(8, 8));
        assertNull(structures.soleBiome(0, 6, 0));
        assertEquals("No structures here.", structures.describe(8, 100, 8));
        assertNotNull(structures.view(), "/place structure still needs a world view when natural generation is off");
        VanillaStructures manualOff = new VanillaStructures(null, registries, randomState, false, hostOf((x, z) -> 100), 1L);
        assertNotNull(manualOff.placed(surface, new ChunkPos(0, 0)), "/place structure still works when natural generation is off");

        IdMap<Holder<Biome>> biomeIds = new IdMap<>() {
            @Override public int getId(Holder<Biome> value) { return 0; }
            @Override public Holder<Biome> byId(int id) { return plains; }
            @Override public int size() { return 1; }
            @Override public java.util.Iterator<Holder<Biome>> iterator() { return List.of(plains).iterator(); }
        };
        LevelChunkSection section = new LevelChunkSection(
                new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomeIds, plains, PalettedContainer.Strategy.SECTION_BIOMES));
        List<CompoundTag> blockEntities = new ArrayList<>(), entities = new ArrayList<>();
        structures.place(section, 0, 6, 0, blockEntities, entities);
        assertTrue(section.hasOnlyAir());
        assertTrue(blockEntities.isEmpty());
        assertTrue(entities.isEmpty());
    }

    @Test void enabledStructuresGenerateOnFlatGroundAndRejectCliffWalls() {
        TestStructure surface = new TestStructure(plains, GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.BEARD_THIN, 12);
        RegistryAccess registries = singleStructureRegistries("test_village", surface);

        // Flat terrain: starts normally.
        VanillaStructures flat = new VanillaStructures(null, registries, randomState, true, hostOf((x, z) -> 100), 1L);
        assertFalse(flat.startsAt(0, 0).isEmpty());
        assertNotNull(flat.startOf(surface, 0, 0));
        assertNotNull(flat.locate(s -> s == surface, 8, 8, 4));

        // Gentle 20-degree hill (slope ~0.36): still starts normally.
        VanillaStructures gentle = new VanillaStructures(null, registries, randomState, true,
                hostOf((x, z) -> 100 + (int) Math.round(0.36 * x)), 1L);
        assertFalse(gentle.startsAt(0, 0).isEmpty());

        // Steep 60-degree Karakoram wall (slope ~1.73): rejected.
        VanillaStructures wall = new VanillaStructures(null, registries, randomState, true,
                hostOf((x, z) -> 3000 + (int) Math.round(1.73 * x)), 1L);
        assertTrue(wall.startsAt(0, 0).isEmpty());
        assertNull(wall.startOf(surface, 0, 0));
        assertNull(wall.locate(s -> s == surface, 8, 8, 4));

        // Underground structures under the same 60-degree wall are still allowed.
        TestStructure underground = new TestStructure(plains, GenerationStep.Decoration.UNDERGROUND_STRUCTURES, TerrainAdjustment.NONE, 12);
        RegistryAccess undergroundReg = singleStructureRegistries("test_ruins", underground);
        VanillaStructures underWall = new VanillaStructures(null, undergroundReg, randomState, true,
                hostOf((x, z) -> 3000 + (int) Math.round(1.73 * x)), 1L);
        assertFalse(underWall.startsAt(0, 0).isEmpty());
    }

    // Legs over a void (YUNG's strongholds and zombie dungeons) ------------------------------

    @Test void layoutsAtVanillaDepthsPlaceDownToTheirFramesBottom() {
        // A stronghold whose pieces reach down to real Y 1,040, in a frame with vanilla's Y 0 at real Y 1,000.
        int low = VanillaStructures.lowestWrite(1040, -64, 1000);
        assertEquals(936, low, "down to the frame's bottom, where a leg over a void stops");
        assertTrue(VanillaStructures.placesInto(low, 1100, 936 >> 4), "the cube a leg ends in is placed into");
        assertFalse(VanillaStructures.placesInto(1040, 1100, 936 >> 4), "as it was: legs cut off at the pieces' reach");
        // Surface structures keep their reach, already some way under the ground.
        assertEquals(1040, VanillaStructures.lowestWrite(1040, io.earthplatform.minecraft.cubic.CubeWorld.MIN_Y, 0));
        assertFalse(VanillaStructures.placesInto(1040, 1100, 1200 >> 4), "nor above the top");
    }

    // Issue #216: surface slope / relief checks --------------------------------------------------

    @Test void footprintFitsAcceptsFlatAndGentleSlopes() {
        BoundingBox igloo = new BoundingBox(0, 100, 0, 6, 106, 7);
        BoundingBox house = new BoundingBox(0, 100, 0, 10, 108, 10);
        BoundingBox pyramid = new BoundingBox(0, 100, 0, 20, 112, 20);

        IntBinaryOperator flat = (x, z) -> 100;
        IntBinaryOperator gentle15 = (x, z) -> 100 + (int) Math.round(0.27 * x);
        IntBinaryOperator hill25 = (x, z) -> 100 + (int) Math.round(0.47 * x);
        IntBinaryOperator diag25 = (x, z) -> 100 + (int) Math.round(0.33 * x + 0.33 * z);
        IntBinaryOperator smallBump = (x, z) -> (x >= 4 && x <= 6 && z >= 4 && z <= 6) ? 103 : 100;

        for (BoundingBox box : List.of(igloo, house, pyramid)) {
            assertTrue(VanillaStructures.footprintFits(box, true, flat));
            assertTrue(VanillaStructures.footprintFits(box, true, gentle15));
            assertTrue(VanillaStructures.footprintFits(box, true, hill25));
            assertTrue(VanillaStructures.footprintFits(box, true, diag25));
            assertTrue(VanillaStructures.footprintFits(box, true, smallBump));
        }
    }

    @Test void footprintFitsRejectsWallsCliffsAndKnifeRidges() {
        BoundingBox igloo = new BoundingBox(0, 100, 0, 6, 106, 7);
        BoundingBox house = new BoundingBox(0, 100, 0, 10, 108, 10);
        BoundingBox outpost = new BoundingBox(0, 100, 0, 14, 118, 14);

        IntBinaryOperator slope35 = (x, z) -> 100 + (int) Math.round(0.70 * x);
        IntBinaryOperator wall45 = (x, z) -> 100 + x;
        IntBinaryOperator diagWall45 = (x, z) -> 100 + (int) Math.round(0.71 * x + 0.71 * z);
        IntBinaryOperator karakoram60 = (x, z) -> 3000 + (int) Math.round(1.73 * x);
        IntBinaryOperator cliffEdge = (x, z) -> x > 6 ? 118 : 100;
        IntBinaryOperator knifeRidge = (x, z) -> 110 - Math.abs(x - 5) * 2;

        for (BoundingBox box : List.of(igloo, house, outpost)) {
            assertFalse(VanillaStructures.footprintFits(box, true, slope35));
            assertFalse(VanillaStructures.footprintFits(box, true, wall45));
            assertFalse(VanillaStructures.footprintFits(box, true, diagWall45));
            assertFalse(VanillaStructures.footprintFits(box, true, karakoram60));
        }
        assertFalse(VanillaStructures.footprintFits(house, true, cliffEdge));
        assertFalse(VanillaStructures.footprintFits(house, true, knifeRidge));
    }

    @Test void nonRigidRoadAllowsLongGentleSlopeButRejectsSteepWall() {
        BoundingBox longRoad = new BoundingBox(0, 100, 0, 48, 101, 4);
        IntBinaryOperator gentleHill = (x, z) -> 100 + (int) Math.round(0.45 * x); // 22 blocks total relief, 0.45 slope
        IntBinaryOperator steepWall = (x, z) -> 100 + (int) Math.round(1.0 * x);

        assertTrue(VanillaStructures.footprintFits(longRoad, false, gentleHill));
        assertFalse(VanillaStructures.footprintFits(longRoad, false, steepWall));
    }

    // Issue #216: tightened terrain adaptation bounds --------------------------------------------

    @Test void adaptBoundsKeepCutsAndPedestalsRealistic() {
        int floor = 100, boxMaxY = 108;

        // Downhill fill is capped at ADAPT_FILL (16) blocks below floor rather than 64.
        assertEquals(floor - VanillaStructures.ADAPT_FILL, VanillaStructures.adaptFillBottom(floor));
        assertTrue(floor - VanillaStructures.adaptFillBottom(floor) <= 20);

        // Uphill BEARD_THIN cut clears gentle ground cleanly (ground + 4 when below headroom cap).
        assertEquals(107, VanillaStructures.adaptCutTop(TerrainAdjustment.BEARD_THIN, boxMaxY, 0, 103, 62));

        // Uphill BEARD_THIN cut against a tall cliff is capped at boxMaxY + ADAPT_HEADROOM (114), not ground + 24 (184).
        assertEquals(boxMaxY + VanillaStructures.ADAPT_HEADROOM,
                VanillaStructures.adaptCutTop(TerrainAdjustment.BEARD_THIN, boxMaxY, 4, 160, 62));

        // BEARD_BOX tapers inward from boxMaxY.
        assertEquals(boxMaxY - 3, VanillaStructures.adaptCutTop(TerrainAdjustment.BEARD_BOX, boxMaxY, 3, 160, 62));
    }

    // Test fixtures ------------------------------------------------------------------------------

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

    private static final class TestStructure extends Structure {
        private final int size;

        TestStructure(Holder<Biome> biome, GenerationStep.Decoration step, TerrainAdjustment adaptation, int size) {
            super(new StructureSettings(HolderSet.direct(biome), Map.of(), step, adaptation));
            this.size = size;
        }

        @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
            int x = context.chunkPos().getMinBlockX(), z = context.chunkPos().getMinBlockZ();
            int y = context.chunkGenerator().getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                    context.heightAccessor(), context.randomState());
            BoundingBox box = new BoundingBox(x, y, z, x + size, y + 8, z + size);
            return Optional.of(new GenerationStub(new BlockPos(x, y, z), builder -> builder.addPiece(new BoxPiece(box))));
        }

        @Override public StructureType<?> type() {
            return () -> MapCodec.unit(this);
        }
    }

    private static final class BoxPiece extends StructurePiece {
        BoxPiece(BoundingBox box) {
            super(StructurePieceType.JIGSAW, 0, box);
        }

        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {}

        @Override public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                                          RandomSource random, BoundingBox box, ChunkPos chunk, BlockPos pos) {}
    }
}
