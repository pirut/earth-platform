package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FloraAndTreeAuditTest {
    private static Map<TagKey<Block>, List<Holder<Block>>> previousTags;
    private static Registry<Biome> biomes;

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        biomes = SurfacePacketFixtures.biomes();
        var blocks = BuiltInRegistries.BLOCK;
        previousTags = blocks.getTags().collect(Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var tags = new HashMap<>(previousTags);
        tags.put(BlockTags.DIRT, holders(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MYCELIUM,
                Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS));
        tags.put(BlockTags.SAND, holders(Blocks.SAND, Blocks.RED_SAND));
        tags.put(BlockTags.LOGS, holders(Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG, Blocks.JUNGLE_LOG,
                Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG));
        tags.put(BlockTags.LEAVES, blocks.stream().filter(b -> b instanceof LeavesBlock).map(blocks::wrapAsHolder).toList());
        tags.put(BlockTags.REPLACEABLE_BY_TREES, holders(Blocks.SHORT_GRASS, Blocks.FERN, Blocks.VINE, Blocks.GLOW_LICHEN));
        blocks.bindTags(tags);
    }

    @AfterAll static void restoreTags() {
        if (previousTags != null) BuiltInRegistries.BLOCK.bindTags(previousTags);
    }

    private static List<Holder<Block>> holders(Block... blocks) {
        return Arrays.stream(blocks).map(BuiltInRegistries.BLOCK::wrapAsHolder).toList();
    }

    @Test void vegetationFreezesPoolAndNeverPlacesSnowAtopLiquidWater() {
        var snowyPool = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.SNOWY_PLAINS),
                Blocks.WATER.defaultBlockState(), Blocks.MUD.defaultBlockState(), 0, 0, 0, 0, 0, false);
        var generator = new SurfaceGenerator(42L, biomes, RandomSource.create(42L)) {
            @Override protected void ground(int x, int z, int[] out) { out[0] = 4; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) { return snowyPool; }
            @Override public String id() { return "test_snowy_pool"; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };

        LevelChunkSection section = generator.generate(0, 0, 0, new ArrayList<>(), new ArrayList<>());
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            BlockState top = section.getBlockState(lx, 4, lz);
            BlockState above = section.getBlockState(lx, 5, lz);
            assertTrue(top.is(Blocks.ICE), "pool surface in freezing biome should freeze to ice, got " + top);
            assertFalse(above.is(Blocks.SNOW), "snow must never float over pool water");
            assertFalse(above.is(Blocks.LILY_PAD), "lily pads must not spawn on freezing pool");
        }
    }

    @Test void treesFitsRejectsWaterLevelAndIsolatedOceanSpecks() {
        // Isolated 1-block dry speck at (0, 0) surrounded by ocean (water == 63, ground == 60),
        // and inland dry forest around (100, 100).
        var generator = new SurfaceGenerator(7L, biomes, RandomSource.create(7L)) {
            @Override protected void ground(int x, int z, int[] out) {
                if (x == 0 && z == 0) { out[0] = 63; out[1] = DRY; }
                else if (Math.abs(x) <= 8 && Math.abs(z) <= 8) { out[0] = 60; out[1] = 63; }
                else { out[0] = 70; out[1] = DRY; }
            }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) {
                return new Surface(biomes.getHolderOrThrow(Biomes.FOREST), GRASS, DIRT, 80, 0, 0, 0, 0, false);
            }
            @Override public String id() { return "test_ocean_speck"; }
            @Override public int seaLevel() { return 63; }
            @Override protected int shoreLevel() { return 63; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
        Trees trees = new Trees(generator);
        var forest = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.FOREST),
                Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 80, 0, 0, 0, 0, false);
        var beach = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.BEACH),
                Blocks.SAND.defaultBlockState(), Blocks.SAND.defaultBlockState(), 2, 0, 0, 0, 0, false);
        var mangrove = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.MANGROVE_SWAMP),
                Blocks.MUD.defaultBlockState(), Blocks.MUD.defaultBlockState(), 45, 0, 0, 0, 0, false, SurfaceGenerator.Tree.MANGROVE);

        assertFalse(trees.fits(forest, SurfaceGenerator.Tree.OAK, null, 100, 70, 100, 70),
                "dry tree must reject water == h");
        assertFalse(trees.fits(beach, SurfaceGenerator.Tree.JUNGLE, RegionalTrees.Species.PALM, 100, 70, 100, 70),
                "palm must reject water == h");
        assertFalse(trees.fits(forest, SurfaceGenerator.Tree.OAK, null, 0, 63, 0, SurfaceGenerator.DRY),
                "dry tree must reject isolated 1-block speck in open ocean");
        assertFalse(trees.fits(beach, SurfaceGenerator.Tree.JUNGLE, RegionalTrees.Species.PALM, 0, 63, 0, SurfaceGenerator.DRY),
                "palm must reject isolated 1-block speck in open ocean");
        assertTrue(trees.fits(forest, SurfaceGenerator.Tree.OAK, null, 100, 70, 100, SurfaceGenerator.DRY),
                "inland dry soil column should accept dry tree");
        assertTrue(trees.fits(mangrove, SurfaceGenerator.Tree.MANGROVE, null, 1, 61, 0, 63),
                "mangrove should still grow in shallow water");
    }

    @Test void nonAquaticTrunksAndGroundDoNotOverwriteOceanWaterOrSubmergedBed() {
        // Straight coastline: x < 8 is dry forest at y=4, x >= 8 is warm_ocean with seabed at y=2 and water up to y=4.
        var forest = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.FOREST),
                Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 100, 0, 0, 0, 0, false);
        var ocean = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.WARM_OCEAN),
                Blocks.SAND.defaultBlockState(), Blocks.SAND.defaultBlockState(), 0, 0, 0, 0, 0, false);
        var generator = new SurfaceGenerator(99L, biomes, RandomSource.create(99L)) {
            @Override protected void ground(int x, int z, int[] out) {
                if (x < 8) { out[0] = 4; out[1] = DRY; }
                else { out[0] = 2; out[1] = 4; }
            }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) {
                return x < 8 ? forest : ocean;
            }
            @Override protected boolean customTrees() { return false; }
            @Override public String id() { return "test_no_water_overwrite"; }
            @Override public int seaLevel() { return 4; }
            @Override protected int shoreLevel() { return 4; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };

        for (int cz = 0; cz < 4; cz++) {
            LevelChunkSection section = generator.generate(0, 0, cz, new ArrayList<>(), new ArrayList<>());
            for (int lz = 0; lz < 16; lz++) for (int lx = 8; lx < 16; lx++) {
                for (int ly = 3; ly <= 4; ly++) {
                    BlockState s = section.getBlockState(lx, ly, lz);
                    assertFalse(s.is(BlockTags.LOGS), "non-aquatic tree trunk must not overwrite ocean water at (" + lx + "," + ly + "," + lz + ")");
                }
                assertFalse(section.getBlockState(lx, 2, lz).is(Blocks.PODZOL),
                        "TreeShape.GROUND must not overwrite submerged ocean seabed");
            }
        }
    }

    @Test void zeroCanopySuppressesStrayMoorlandTreesWhileUnknownAndPositiveGrow() {
        assertEquals(0, countTreeBlocksWithCanopy(0, 12),
                "explicit 0 m canopy must suppress scattered non-woodland trees");
        assertTrue(countTreeBlocksWithCanopy(-1, 12) > 0,
                "unknown (-1) canopy must keep growing trees normally");
        assertTrue(countTreeBlocksWithCanopy(12, 12) > 0,
                "positive canopy height must keep growing trees normally");
    }

    private int countTreeBlocksWithCanopy(int canopyMetres, int treeDensity) {
        var moor = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.TAIGA),
                Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), treeDensity, 10, 0, 0, 0, false);
        var generator = new SurfaceGenerator(123L, biomes, RandomSource.create(123L)) {
            @Override protected void ground(int x, int z, int[] out) { out[0] = 2; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) { return moor; }
            @Override protected int canopy(int x, int z) { return canopyMetres; }
            @Override public String id() { return "test_canopy_" + canopyMetres; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
        int logs = 0;
        for (int cz = 0; cz < 4; cz++) for (int cx = 0; cx < 4; cx++) {
            LevelChunkSection section = generator.generate(cx, 0, cz, new ArrayList<>(), new ArrayList<>());
            for (int ly = 3; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                if (section.getBlockState(lx, ly, lz).is(BlockTags.LOGS)) logs++;
            }
        }
        return logs;
    }

    @Test void stretchedTreeLiftsBeeNestAndPreventsFloatingSnowAtSplitMinusOne() {
        Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        BlockState uprightLog = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState sideBranch = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        for (int y = 0; y <= 4; y++) blocks.put(BlockPos.asLong(0, y, 0), uprightLog);
        // Side branch at y = 2 (= split - 1 when lowestLeaf = 3): NOT lifted when stretched!
        blocks.put(BlockPos.asLong(2, 2, 0), sideBranch);
        // Crown starts at y = 3 -> split = 3, nestY = 2 (= split - 1).
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx != 0 || dz != 0) blocks.put(BlockPos.asLong(dx, 3, dz), leaves);
            blocks.put(BlockPos.asLong(dx, 4, dz), leaves);
        }
        blocks.put(BlockPos.asLong(0, 5, 0), leaves);

        TreeShape shape = new TreeShape(blocks, 1.0f);
        assertTrue(shape.stretches(), "test tree should be stretchable");

        int oy = 10, lift = 6;
        Long2ObjectOpenHashMap<BlockState> placed = new Long2ObjectOpenHashMap<>();
        shape.place(0, oy, 0, 0, 0L, true, lift, -8, 0, -8, 8, 40, 8, (x, z) -> oy - 1,
                (x, y, z, state, kind) -> placed.put(BlockPos.asLong(x, y, z), state));

        // 1. Bee nest must move with lift to oy + 2 + lift = 18 (directly under lifted crown at y=19), NOT stay at oy + 2 = 12.
        List<Integer> nestYs = new ArrayList<>();
        for (var entry : placed.long2ObjectEntrySet()) {
            if (entry.getValue().is(Blocks.BEE_NEST)) nestYs.add(BlockPos.getY(entry.getLongKey()));
        }
        assertEquals(List.of(oy + 2 + lift), nestYs, "bee nest on stretched tree must lift with the crown");

        // 2. Snow for unlifted branch at (2, oy + 2, 0) must rest directly on it at (2, oy + 3, 0), NOT float at oy + 3 + lift.
        assertEquals(Blocks.SNOW.defaultBlockState(), placed.get(BlockPos.asLong(2, oy + 3, 0)),
                "snow on unlifted split-1 branch must sit directly atop the branch");
        assertFalse(placed.containsKey(BlockPos.asLong(2, oy + 3 + lift, 0)),
                "snow must not float in mid-air above an unlifted split-1 branch");

        // 3. Snow on lifted crown top at (0, oy + 5 + lift, 0) must lift to oy + 6 + lift.
        assertEquals(Blocks.SNOW.defaultBlockState(), placed.get(BlockPos.asLong(0, oy + 6 + lift, 0)),
                "snow on lifted crown must move with lift");
    }

    @Test void overhangingCliffTreesAreNotShearedAcrossChunkBoundaries() {
        // Chunk (0, 0) sits at y = 10; neighbouring chunk (1, 0) sits on a 70-block cliff at y = 80 with dense trees.
        var plain = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.PLAINS),
                Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 0, 0, 0, 0, 0, false);
        var cliffForest = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.FOREST),
                Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), 150, 0, 0, 0, 0, false);
        var generator = new SurfaceGenerator(555L, biomes, RandomSource.create(555L)) {
            @Override protected void ground(int x, int z, int[] out) {
                out[0] = x >= 16 ? 80 : 10;
                out[1] = DRY;
            }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) {
                return x >= 16 ? cliffForest : plain;
            }
            @Override protected boolean customTrees() { return false; }
            @Override public String id() { return "test_cliff_overhang"; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };

        int overhangingLeaves = 0;
        // Cube cy = 5 spans y = 80..95, well above chunk 0's own col.max (10) + TALLEST (34) = 44.
        for (int cz = 0; cz < 8; cz++) {
            LevelChunkSection skyCube = generator.generate(0, 5, cz, new ArrayList<>(), new ArrayList<>());
            for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 12; lx < 16; lx++) {
                if (skyCube.getBlockState(lx, ly, lz).is(BlockTags.LEAVES)) overhangingLeaves++;
            }
        }
        assertTrue(overhangingLeaves > 0, "overhanging cliff tree crowns in sky cube cy=5 must not be sheared away");

        // High sky cube cy = 12 (y = 192..207) well above even cliff trees stays empty air.
        LevelChunkSection highSky = generator.generate(0, 12, 0, new ArrayList<>(), new ArrayList<>());
        assertTrue(highSky.hasOnlyAir(), "sky cube far above all neighbouring trees should stay empty");
    }

    @Test void treesShapesCacheKeyDistinguishesSoilDeterministically() {
        var generator = new SurfaceGenerator(0, biomes, RandomSource.create(0)) {
            @Override protected void ground(int x, int z, int[] out) { out[0] = 0; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) {
                return new Surface(biomes.getHolderOrThrow(Biomes.FOREST), GRASS, DIRT, 80, 0, 0, 0, 0, false);
            }
            @Override public String id() { return "test_soil_key"; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
        Trees trees = new Trees(generator);
        Feature<TreeConfiguration> soilSensitive = new Feature<>(TreeConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<TreeConfiguration> ctx) {
                BlockState ground = ctx.level().getBlockState(ctx.origin().below());
                BlockState log = ground.is(Blocks.PODZOL) ? Blocks.SPRUCE_LOG.defaultBlockState() : Blocks.OAK_LOG.defaultBlockState();
                for (int y = 0; y < 5; y++) ctx.level().setBlock(ctx.origin().above(y), log, 2);
                ctx.level().setBlock(ctx.origin().above(5), Blocks.OAK_LEAVES.defaultBlockState(), 2);
                return true;
            }
        };
        TreeConfiguration cfg = new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.OAK_LOG), new StraightTrunkPlacer(4, 0, 0),
                BlockStateProvider.simple(Blocks.OAK_LEAVES), new BlobFoliagePlacer(ConstantInt.of(1), ConstantInt.of(0), 1),
                new TwoLayersFeatureSize(1, 0, 1)).build();
        ConfiguredFeature<?, ?> feature = new ConfiguredFeature<>(soilSensitive, cfg);

        TreeShape[] onGrass = trees.shapes(feature, Blocks.GRASS_BLOCK.defaultBlockState());
        TreeShape[] onPodzol = trees.shapes(feature, Blocks.PODZOL.defaultBlockState());
        assertNotSame(onGrass, onPodzol, "same feature grown on different soils must not share a cache entry");

        List<BlockState> grassBlocks = new ArrayList<>(), podzolBlocks = new ArrayList<>();
        onGrass[0].place(0, 0, 0, 0, 0L, false, 0, -4, 0, -4, 4, 10, 4, (x, z) -> -1, (x, y, z, s, k) -> grassBlocks.add(s));
        onPodzol[0].place(0, 0, 0, 0, 0L, false, 0, -4, 0, -4, 4, 10, 4, (x, z) -> -1, (x, y, z, s, k) -> podzolBlocks.add(s));
        assertTrue(grassBlocks.stream().anyMatch(s -> s.is(Blocks.OAK_LOG)));
        assertTrue(podzolBlocks.stream().anyMatch(s -> s.is(Blocks.SPRUCE_LOG)));
    }
}
