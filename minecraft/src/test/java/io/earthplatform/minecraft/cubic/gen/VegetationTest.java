package io.earthplatform.minecraft.cubic.gen;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer;
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VegetationTest {
    @Test void freezingOceanColumnsStayBareWhileTemperateColdOceansKeepKelp() {
        var biomes = SurfacePacketFixtures.biomes();
        for (var biome : List.of(Biomes.COLD_OCEAN, Biomes.DEEP_COLD_OCEAN,
                Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN)) {
            for (int mode = 0; mode < 3; mode++) {
                boolean iced = mode == 1, freezing = mode == 2;
                var bed = Blocks.GRAVEL.defaultBlockState();
                var ocean = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(biome), bed, bed,
                        0, 0, 0, 0, 0, false);
                var generator = new SurfaceGenerator(42, biomes, RandomSource.create(42)) {
                    @Override protected void ground(int x, int z, int[] out) { out[0] = 0; out[1] = 15; }
                    @Override protected Surface surface(int x, int z, int ground, int water, int slope) { return ocean; }
                    @Override protected boolean iced(Surface s, int x, int z) { return iced; }
                    @Override protected boolean underwaterPlants(Surface s, int x, int z) {
                        return !freezing && super.underwaterPlants(s, x, z);
                    }
                    @Override public String id() { return "test_ocean_plants"; }
                    @Override public int seaLevel() { return 15; }
                    @Override protected int shoreLevel() { return 15; }
                    @Override protected boolean emeralds(int maxGround) { return false; }
                };
                int[] height = new int[18 * 18], water = new int[18 * 18];
                Arrays.fill(water, 15);
                var surfaces = new SurfaceGenerator.Surface[256];
                Arrays.fill(surfaces, ocean);
                var section = new LevelChunkSection(biomes);
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    section.setBlockState(x, 0, z, bed, false);
                    for (int y = 1; y <= 15; y++) section.setBlockState(x, y, z, Blocks.WATER.defaultBlockState(), false);
                }
                new Vegetation(generator).decorate(section, 0, 0, 0, height, water, surfaces);
                int kelp = 0, grass = 0;
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 1; y <= 15; y++) {
                    var block = section.getBlockState(x, y, z);
                    if (block.is(Blocks.KELP) || block.is(Blocks.KELP_PLANT)) kelp++;
                    if (block.is(Blocks.SEAGRASS) || block.is(Blocks.TALL_SEAGRASS)) grass++;
                    if (iced && y == 15) assertTrue(block.is(Blocks.ICE));
                }
                if (iced || freezing || biome == Biomes.FROZEN_OCEAN || biome == Biomes.DEEP_FROZEN_OCEAN) {
                    assertEquals(0, kelp, biome + " must have no kelp in mode " + mode);
                    assertEquals(0, grass, biome + " must have no seagrass in mode " + mode);
                } else {
                    assertTrue(kelp > 0, "temperate cold ocean must retain kelp");
                    assertTrue(grass > 0, "temperate cold ocean must retain seagrass");
                }
            }
        }
    }

    @Test void sandyColumnsObserveTheGeneratorCactusRange() {
        var biomes = SurfacePacketFixtures.biomes();
        var sand = Blocks.RED_SAND.defaultBlockState();
        var desert = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.DESERT), sand, sand,
                0, 0, 0, 0, 1000, false);
        var generator = new SurfaceGenerator(0, biomes, RandomSource.create(0)) {
            @Override protected boolean cacti(int x, int z) { return x >= 8; }
            @Override protected void ground(int x, int z, int[] out) { out[0] = 0; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) { return desert; }
            @Override public String id() { return "test_cactus_range"; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
        int[] height = new int[18 * 18], water = new int[18 * 18];
        Arrays.fill(water, SurfaceGenerator.DRY);
        var surface = new SurfaceGenerator.Surface[16 * 16];
        Arrays.fill(surface, desert);
        var section = new LevelChunkSection(biomes);

        new Vegetation(generator).decorate(section, 0, 0, 0, height, water, surface);

        assertFalse(section.getBlockState(6, 1, 0).is(Blocks.CACTUS));
        assertTrue(section.getBlockState(8, 1, 0).is(Blocks.CACTUS));
    }

    @Test void earthForestsExcludeHugeMushrooms() {
        SurfacePacketFixtures.biomes();
        var cap = BlockStateProvider.simple(Blocks.RED_MUSHROOM_BLOCK);
        var stem = BlockStateProvider.simple(Blocks.MUSHROOM_STEM);
        var redMushroom = new ConfiguredFeature<>(Feature.HUGE_RED_MUSHROOM, new HugeMushroomFeatureConfiguration(cap, stem, 2));
        var brownMushroom = new ConfiguredFeature<>(Feature.HUGE_BROWN_MUSHROOM, new HugeMushroomFeatureConfiguration(cap, stem, 2));
        var oak = new ConfiguredFeature<>(Feature.TREE, new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.OAK_LOG), new StraightTrunkPlacer(4, 2, 0),
                BlockStateProvider.simple(Blocks.OAK_LEAVES), new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1)).build());

        assertNull(Trees.tree(Holder.direct(redMushroom)), "huge red mushrooms must be excluded from Earth forests");
        assertNull(Trees.tree(Holder.direct(brownMushroom)), "huge brown mushrooms must be excluded from Earth forests");
        assertNotNull(Trees.tree(Holder.direct(oak)), "vanilla trees must still be accepted");
    }

    @Test void treeLikeExcludesHugeMushroomsButKeepsTrees() {
        SurfacePacketFixtures.biomes();
        var cap = BlockStateProvider.simple(Blocks.RED_MUSHROOM_BLOCK);
        var stem = BlockStateProvider.simple(Blocks.MUSHROOM_STEM);
        assertFalse(Trees.treeLike(new ConfiguredFeature<>(Feature.HUGE_RED_MUSHROOM, new HugeMushroomFeatureConfiguration(cap, stem, 2))));
        assertFalse(Trees.treeLike(new ConfiguredFeature<>(Feature.HUGE_BROWN_MUSHROOM, new HugeMushroomFeatureConfiguration(cap, stem, 2))));
        assertTrue(Trees.treeLike(new ConfiguredFeature<>(Feature.TREE, new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.OAK_LOG), new StraightTrunkPlacer(4, 2, 0),
                BlockStateProvider.simple(Blocks.OAK_LEAVES), new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1)).build())));
    }

    @Test void treesGrowFallsBackWhenThirdPartyModBreaksTreeFeaturePlace() {
        var biomes = SurfacePacketFixtures.biomes();
        var generator = new SurfaceGenerator(0, biomes, RandomSource.create(0)) {
            @Override protected void ground(int x, int z, int[] out) { out[0] = 0; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) {
                return new Surface(biomes.getHolderOrThrow(Biomes.FOREST), GRASS, DIRT, 80, 0, 0, 0, 0, false);
            }
            @Override public String id() { return "test_tree_fallback"; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
        Trees trees = new Trees(generator);

        Feature<TreeConfiguration> crashingFeature = new Feature<>(TreeConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<TreeConfiguration> ctx) {
                throw new NullPointerException("simulated third-party mod crash on null chunkGenerator");
            }
        };
        TreeConfiguration birchConfig = new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.BIRCH_LOG), new StraightTrunkPlacer(5, 2, 0),
                BlockStateProvider.simple(Blocks.BIRCH_LEAVES), new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1)).build();
        ConfiguredFeature<?, ?> crashingBirch = new ConfiguredFeature<>(crashingFeature, birchConfig);

        // Layer 1 fallback: direct TrunkPlacer + FoliagePlacer placement grows non-empty trees.
        TreeShape[] direct = trees.grow(crashingBirch, null);
        assertTrue(direct.length > 0, "crashing Earth tree feature must fall back to direct placement");
        assertTrue(direct[0].blocks() > 10 && direct[0].height() >= 4);

        // Layer 2 fallback: even if TrunkPlacer also throws, simple fallback still grows non-empty trees.
        TrunkPlacer crashingTrunk = new TrunkPlacer(4, 0, 0) {
            @Override protected TrunkPlacerType<?> type() { return TrunkPlacerType.STRAIGHT_TRUNK_PLACER; }
            @Override public List<FoliagePlacer.FoliageAttachment> placeTrunk(LevelSimulatedReader l, BiConsumer<BlockPos, BlockState> out,
                    RandomSource r, int h, BlockPos pos, TreeConfiguration cfg) {
                throw new IllegalStateException("simulated crashing trunk placer");
            }
        };
        TreeConfiguration doublyBroken = new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.SPRUCE_LOG), crashingTrunk,
                BlockStateProvider.simple(Blocks.SPRUCE_LEAVES), new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1)).build();
        TreeShape[] simple = trees.grow(new ConfiguredFeature<>(crashingFeature, doublyBroken), null);
        assertTrue(simple.length > 0, "doubly broken Earth tree feature must fall back to simple tree shapes");
        assertTrue(simple[0].blocks() > 10 && simple[0].height() >= 4);

        // Third-party mod additions (soil != null) still return empty when broken so non-tree features are skipped.
        TreeShape[] brokenModAddition = trees.grow(crashingBirch, Blocks.GRASS_BLOCK.defaultBlockState());
        assertEquals(0, brokenModAddition.length, "broken third-party mod additions should still be skipped");
    }

    @Test void featuresGrownAsTreesAreGivenAChunkGenerator() {
        var biomes = SurfacePacketFixtures.biomes();
        var generator = new SurfaceGenerator(0, biomes, RandomSource.create(0)) {
            @Override protected void ground(int x, int z, int[] out) { out[0] = 0; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) {
                return new Surface(biomes.getHolderOrThrow(Biomes.FOREST), GRASS, DIRT, 80, 0, 0, 0, 0, false);
            }
            @Override public String id() { return "test_feature_generator"; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
        Trees trees = new Trees(generator);
        // As Aquamirae's camps do: read the generator's sea level, then build up from the origin.
        Feature<TreeConfiguration> camp = new Feature<>(TreeConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<TreeConfiguration> ctx) {
                int sea = ctx.chunkGenerator().getSeaLevel();
                assertEquals(ctx.level().getSeaLevel(), sea, "the generator's sea level is the level's");
                for (int y = 0; y < 6; y++) ctx.level().setBlock(ctx.origin().above(y), Blocks.SPRUCE_LOG.defaultBlockState(), 2);
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                    ctx.level().setBlock(ctx.origin().offset(x, 6, z), Blocks.SPRUCE_LEAVES.defaultBlockState(), 2);
                return true;
            }
        };
        TreeConfiguration config = new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.SPRUCE_LOG), new StraightTrunkPlacer(5, 2, 0),
                BlockStateProvider.simple(Blocks.SPRUCE_LEAVES), new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1)).build();
        TreeShape[] grown = trees.grow(new ConfiguredFeature<>(camp, config), Blocks.GRASS_BLOCK.defaultBlockState());
        assertTrue(grown.length > 0, "a mod feature that reads the chunk generator grows instead of failing");
    }
}
