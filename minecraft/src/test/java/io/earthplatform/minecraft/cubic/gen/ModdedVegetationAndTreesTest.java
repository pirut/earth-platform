package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ModdedVegetationAndTreesTest {
    private static Map<TagKey<Block>, List<Holder<Block>>> previousTags;

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var blocks = BuiltInRegistries.BLOCK;
        previousTags = blocks.getTags().collect(Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var tags = new HashMap<>(previousTags);
        tags.put(BlockTags.DIRT, holders(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MYCELIUM,
                Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS));
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

    private static Biome moddedBiome(float temperature, @javax.annotation.Nullable PlacedFeature treeFeature) {
        BiomeGenerationSettings.PlainBuilder gen = new BiomeGenerationSettings.PlainBuilder();
        if (treeFeature != null) gen.addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, Holder.direct(treeFeature));
        return new Biome.BiomeBuilder()
                .hasPrecipitation(true)
                .temperature(temperature)
                .downfall(0.8f)
                .specialEffects(new BiomeSpecialEffects.Builder()
                        .fogColor(0xC0D8FF)
                        .waterColor(0x3F76E4)
                        .waterFogColor(0x050533)
                        .skyColor(0x78A7FF)
                        .build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(gen.build())
                .build();
    }

    private static PlacedFeature acaciaTestTree() {
        TreeConfiguration config = new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.ACACIA_LOG),
                new StraightTrunkPlacer(4, 1, 0),
                BlockStateProvider.simple(Blocks.ACACIA_LEAVES),
                new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1)).build();
        ConfiguredFeature<?, ?> configured = new ConfiguredFeature<>(Feature.TREE, config);
        return new PlacedFeature(Holder.direct(configured), List.of(CountPlacement.of(8)));
    }

    private static Registry<Biome> registryWithModded(String id, Biome biome) {
        MappedRegistry<Biome> r = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> r.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        r.register(ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id)), biome, RegistrationInfo.BUILT_IN);
        return r.freeze();
    }

    private static SurfaceGenerator flatGenerator(Registry<Biome> biomes, SurfaceGenerator.Surface surface) {
        return new SurfaceGenerator(1234L, biomes, RandomSource.create(1234L)) {
            @Override protected void ground(int x, int z, int[] out) { out[0] = 0; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) { return surface; }
            @Override public String id() { return "test_modded_veg"; }
            @Override public int seaLevel() { return 0; }
            @Override protected int shoreLevel() { return 0; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
    }

    @Test void vegetationPlacesWetlandFlowersAndAridScrubInModdedBiomes() {
        Registry<Biome> biomes = registryWithModded("biomesoplenty:bayou", moddedBiome(0.95f, null));
        Holder<Biome> bayou = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.parse("biomesoplenty:bayou")));
        var surface = new SurfaceGenerator.Surface(bayou, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                0, 0, 0, 0, 1000, false);
        var gen = flatGenerator(biomes, surface);

        int[] height = new int[18 * 18], water = new int[18 * 18];
        Arrays.fill(water, SurfaceGenerator.DRY);
        var surfaces = new SurfaceGenerator.Surface[16 * 16];
        Arrays.fill(surfaces, surface);
        var section = new LevelChunkSection(biomes);

        new Vegetation(gen).decorate(section, 0, 0, 0, height, water, surfaces);
        assertTrue(section.getBlockState(4, 1, 4).is(Blocks.BLUE_ORCHID), "modded bayou grows swamp orchids");

        Registry<Biome> aridReg = registryWithModded("biomesoplenty:cold_desert", moddedBiome(0.25f, null));
        Holder<Biome> coldDesert = aridReg.getHolderOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.parse("biomesoplenty:cold_desert")));
        var aridSurface = new SurfaceGenerator.Surface(coldDesert, Blocks.COARSE_DIRT.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                0, 0, 0, 1000, 1000, false);
        var aridGen = flatGenerator(aridReg, aridSurface);
        Arrays.fill(surfaces, aridSurface);
        var aridSection = new LevelChunkSection(aridReg);
        new Vegetation(aridGen).decorate(aridSection, 0, 0, 0, height, water, surfaces);
        int bushes = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            if (aridSection.getBlockState(x, 1, z).is(Blocks.DEAD_BUSH)) bushes++;
        assertTrue(bushes > 0, "modded cold_desert grows dry scrub dead bushes on coarse dirt");
    }

    @Test void treesUseModdedBiomeNativeTreeFeaturesAtLandCoverDensity() {
        Registry<Biome> biomes = registryWithModded("biomesoplenty:redwood_forest", moddedBiome(0.6f, acaciaTestTree()));
        Holder<Biome> redwood = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.parse("biomesoplenty:redwood_forest")));

        // Dense tree cover: should place the modded biome's native acacia tree feature (NOT vanilla spruce)
        var wooded = new SurfaceGenerator.Surface(redwood, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                90, 10, 0, 0, 0, false);
        Trees woodedTrees = new Trees(flatGenerator(biomes, wooded));
        AtomicInteger acaciaLogs = new AtomicInteger(), otherLogs = new AtomicInteger();
        woodedTrees.place(-32, 0, -32, 32, 32, 32, false, (x, y, z, state, kind) -> {
            if (state.is(Blocks.ACACIA_LOG)) acaciaLogs.incrementAndGet();
            else if (state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.OAK_LOG)) otherLogs.incrementAndGet();
        });
        assertTrue(acaciaLogs.get() > 0, "modded biome grows its own native tree feature");
        assertEquals(0, otherLogs.get(), "modded biome with native trees does not double-plant vanilla trees");

        // Treeless clearing (trees == 0): must NOT plant any trees even though modded biome has tree features
        var clearing = new SurfaceGenerator.Surface(redwood, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                0, 0, 0, 0, 0, false);
        Trees clearingTrees = new Trees(flatGenerator(biomes, clearing));
        AtomicInteger clearingBlocks = new AtomicInteger();
        clearingTrees.place(-32, 0, -32, 32, 32, 32, false, (x, y, z, state, kind) -> clearingBlocks.incrementAndGet());
        assertEquals(0, clearingBlocks.get(), "treeless ground in a modded biome stays treeless");
    }

    @Test void moddedBiomeWithoutNativeTreeFeaturesFallsBackCleanlyToVanillaTrees() {
        Registry<Biome> biomes = registryWithModded("biomesoplenty:prairie", moddedBiome(0.7f, null));
        Holder<Biome> prairie = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.parse("biomesoplenty:prairie")));
        var wooded = new SurfaceGenerator.Surface(prairie, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                80, 0, 0, 0, 0, false);
        Trees trees = new Trees(flatGenerator(biomes, wooded));
        AtomicInteger blocks = new AtomicInteger();
        trees.place(-32, 0, -32, 32, 32, 32, false, (x, y, z, state, kind) -> blocks.incrementAndGet());
        assertTrue(blocks.get() > 0, "modded biome without native tree features falls back to vanilla species");
    }

    @Test void customPaletteGiantsAndSequoiasBuildWithModdedWoodAndLeaves() {
        BlockState customBark = Blocks.ACACIA_WOOD.defaultBlockState();
        BlockState customFurrow = Blocks.SPRUCE_WOOD.defaultBlockState();
        BlockState customLeaves = Blocks.DARK_OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);

        TreeShape[] redwoods = Giants.shapes(Giants.REDWOOD, customBark, customFurrow, customLeaves);
        assertEquals(12, redwoods.length);
        AtomicInteger barkCount = new AtomicInteger(), leafCount = new AtomicInteger(), vanillaMangrove = new AtomicInteger();
        redwoods[0].place(0, 0, 0, 0, 1, false, 0, -32, -4, -32, 32, 120, 32, (x, z) -> -1, (x, y, z, state, kind) -> {
            if (state.is(Blocks.ACACIA_WOOD)) barkCount.incrementAndGet();
            if (state.is(Blocks.DARK_OAK_LEAVES)) leafCount.incrementAndGet();
            if (state.is(Blocks.MANGROVE_WOOD)) vanillaMangrove.incrementAndGet();
        });
        assertTrue(barkCount.get() > 500, "modded redwood giant uses modded bark");
        assertTrue(leafCount.get() > 500, "modded redwood giant uses modded leaves");
        assertEquals(0, vanillaMangrove.get(), "modded redwood giant replaces vanilla mangrove bark");

        TreeShape[] sequoias = Sequoia.shapes(customBark, customLeaves);
        assertEquals(12, sequoias.length);
        AtomicInteger seqBark = new AtomicInteger(), seqLeaves = new AtomicInteger();
        sequoias[0].place(0, 0, 0, 0, 1, false, 0, -32, -4, -32, 32, 120, 32, (x, z) -> -1, (x, y, z, state, kind) -> {
            if (state.is(Blocks.ACACIA_WOOD)) seqBark.incrementAndGet();
            if (state.is(Blocks.DARK_OAK_LEAVES)) seqLeaves.incrementAndGet();
        });
        assertTrue(seqBark.get() > 500);
        assertTrue(seqLeaves.get() > 500);
    }
}
