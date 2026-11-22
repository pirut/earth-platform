package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.play.EarthConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RegionalTreesTest {
    private static Map<TagKey<Block>, List<Holder<Block>>> previousTags;

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        SurfacePacketFixtures.biomes();
        var blocks = BuiltInRegistries.BLOCK;
        previousTags = blocks.getTags().collect(Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var tags = new HashMap<>(previousTags);
        tags.put(BlockTags.DIRT, holders(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MYCELIUM, Blocks.MUD));
        tags.put(BlockTags.SAND, holders(Blocks.SAND, Blocks.RED_SAND));
        blocks.bindTags(tags);
    }

    @AfterAll static void restoreTags() {
        if (previousTags != null) BuiltInRegistries.BLOCK.bindTags(previousTags);
    }

    private static List<Holder<Block>> holders(Block... blocks) {
        return Arrays.stream(blocks).map(BuiltInRegistries.BLOCK::wrapAsHolder).toList();
    }

    private static Long2ObjectOpenHashMap<BlockState> placed(TreeShape shape) {
        Long2ObjectOpenHashMap<BlockState> out = new Long2ObjectOpenHashMap<>();
        shape.place(0, 0, 0, 0, 1L, false, 0, -32, -8, -32, 32, 64, 32, (x, z) -> -1,
                (x, y, z, state, kind) -> out.put(BlockPos.asLong(x, y, z), state));
        return out;
    }

    @Test void everyRegionalSpeciesProducesValidConnectedBoundedShapes() {
        for (RegionalTrees.Species species : RegionalTrees.Species.values()) {
            TreeShape[] variants = RegionalTrees.shapes(species);
            assertEquals(RegionalTrees.VARIANTS, variants.length, species + " variant count");
            for (int i = 0; i < variants.length; i++) {
                TreeShape shape = variants[i];
                String label = species + " variant " + i;
                assertTrue(shape.blocks() > 0, label + " should be non-empty");
                assertTrue(shape.height() <= Trees.TALLEST, label + " height " + shape.height() + " exceeds Trees.TALLEST");

                Long2ObjectOpenHashMap<BlockState> blocks = placed(shape);
                int logs = 0, leaves = 0, baseWood = 0, maxY = -1, maxR = 0, vines = 0, cocoa = 0;
                LongOpenHashSet visited = new LongOpenHashSet();
                LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

                for (var entry : blocks.long2ObjectEntrySet()) {
                    long key = entry.getLongKey();
                    int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
                    BlockState state = entry.getValue();
                    assertFalse(state.isAir(), label + " must not contain air blocks");
                    assertTrue(Math.abs(x) <= Trees.REACH && Math.abs(z) <= Trees.REACH, label + " exceeds Trees.REACH at " + x + "," + z);
                    assertTrue(y >= 0 && y < Trees.TALLEST, label + " y out of bounds: " + y);
                    maxY = Math.max(maxY, y);
                    maxR = Math.max(maxR, Math.max(Math.abs(x), Math.abs(z)));
                    if (state.getBlock() instanceof RotatedPillarBlock) {
                        logs++;
                        if (y == 0) {
                            baseWood++;
                            visited.add(key);
                            queue.enqueue(key);
                        }
                    } else if (state.getBlock() instanceof LeavesBlock) {
                        leaves++;
                        assertFalse(state.getValue(LeavesBlock.PERSISTENT), label + " leaves should not be persistent");
                        int dist = state.getValue(LeavesBlock.DISTANCE);
                        assertTrue(dist >= 1 && dist <= 6, label + " invalid leaf distance " + dist);
                    } else if (state.getBlock() instanceof VineBlock) {
                        vines++;
                    } else if (state.getBlock() instanceof CocoaBlock) {
                        cocoa++;
                    }
                }
                assertTrue(logs > 0, label + " must have logs");
                assertTrue(leaves > 0, label + " must have leaves");
                assertTrue(baseWood > 0, label + " must be rooted at y=0");

                // Every block must be 6-connected to the trunk base so no disconnected floating junk exists.
                while (!queue.isEmpty()) {
                    long at = queue.dequeueLong();
                    for (Direction d : Direction.values()) {
                        long n = BlockPos.offset(at, d);
                        if (blocks.containsKey(n) && visited.add(n)) queue.enqueue(n);
                    }
                }
                assertEquals(blocks.size(), visited.size(), label + " has disconnected floating blocks");

                switch (species) {
                    case PALM -> {
                        assertTrue(maxY >= 8 && maxY <= 17, label + " palm height " + maxY);
                        assertTrue(cocoa >= 1, label + " palm should have coconuts under crown");
                    }
                    case RAINFOREST_CANOPY -> {
                        assertTrue(maxY >= 16 && maxY < Trees.TALLEST, label + " rainforest canopy height " + maxY);
                        assertTrue(maxR >= 5, label + " rainforest canopy spread " + maxR);
                        assertTrue(vines >= 1, label + " rainforest canopy should have hanging vines");
                    }
                    case BOREAL_PINE -> {
                        assertTrue(maxY >= 12 && maxY <= 25, label + " boreal pine height " + maxY);
                        assertTrue(blocks.get(BlockPos.asLong(0, maxY, 0)).getBlock() instanceof LeavesBlock, label + " boreal pine top should be needle tip");
                    }
                    case CYPRESS -> {
                        assertTrue(maxY >= 8 && maxY <= 16, label + " cypress height " + maxY);
                        assertTrue(maxR <= 3, label + " cypress should stay columnar, radius " + maxR);
                    }
                    case STONE_PINE -> {
                        assertTrue(maxY >= 11 && maxY <= 20, label + " stone pine height " + maxY);
                        assertTrue(maxR >= 4, label + " stone pine umbrella radius " + maxR);
                    }
                    case BAOBAB -> {
                        assertTrue(maxY >= 10 && maxY <= 18, label + " baobab height " + maxY);
                        assertTrue(baseWood >= 12, label + " baobab should have thick bottle trunk, base wood " + baseWood);
                    }
                    case FLAT_ACACIA -> {
                        assertTrue(maxY >= 8 && maxY <= 16, label + " flat acacia height " + maxY);
                        assertTrue(maxR >= 4, label + " flat acacia umbrella spread " + maxR);
                    }
                    case BROADLEAF_OAK -> assertTrue(maxY >= 10 && maxY <= 19, label + " broadleaf oak height " + maxY);
                    case TALL_BIRCH -> assertTrue(maxY >= 10 && maxY <= 19, label + " tall birch height " + maxY);
                }
            }
        }
    }

    @Test void regionalTreesAreDeterministic() {
        for (RegionalTrees.Species species : RegionalTrees.Species.values()) {
            assertEquals(placed(RegionalTrees.grow(12345L, species)), placed(RegionalTrees.grow(12345L, species)),
                    species + " should grow identically for the same seed");
        }
    }

    @Test void hugeMushroomsAreExcludedFromEarthForestTrees() {
        HugeMushroomFeatureConfiguration redCfg = new HugeMushroomFeatureConfiguration(
                BlockStateProvider.simple(Blocks.RED_MUSHROOM_BLOCK),
                BlockStateProvider.simple(Blocks.MUSHROOM_STEM), 2);
        HugeMushroomFeatureConfiguration brownCfg = new HugeMushroomFeatureConfiguration(
                BlockStateProvider.simple(Blocks.BROWN_MUSHROOM_BLOCK),
                BlockStateProvider.simple(Blocks.MUSHROOM_STEM), 3);
        TreeConfiguration oakCfg = new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.OAK_LOG), new StraightTrunkPlacer(4, 2, 0),
                BlockStateProvider.simple(Blocks.OAK_LEAVES), new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1)).build();

        assertNull(Trees.tree(Holder.direct(new ConfiguredFeature<>(Feature.HUGE_RED_MUSHROOM, redCfg))));
        assertNull(Trees.tree(Holder.direct(new ConfiguredFeature<>(Feature.HUGE_BROWN_MUSHROOM, brownCfg))));
        assertNotNull(Trees.tree(Holder.direct(new ConfiguredFeature<>(Feature.TREE, oakCfg))));
    }

    @Test void customTreesToggleControlsRegionalTreePlacementAndDefaultsOn() {
        assertTrue(EarthConfig.customTrees(), "customTrees config should default to true");

        var biomes = SurfacePacketFixtures.biomes();
        var sand = Blocks.SAND.defaultBlockState();
        var tropicalBeach = new SurfaceGenerator.Surface(biomes.getHolderOrThrow(Biomes.BEACH), sand, sand,
                40, 0, 0, 0, 0, false, SurfaceGenerator.Tree.JUNGLE);

        int customBlocks = countPlacedTreeBlocks(biomes, tropicalBeach, true, RegionalTrees.Species.PALM);
        assertTrue(customBlocks > 0, "customTrees=true should place PALM blocks on tropical beach sand");

        int vanillaBlocks = countPlacedTreeBlocks(biomes, tropicalBeach, false, RegionalTrees.Species.PALM);
        assertEquals(0, vanillaBlocks, "customTrees=false should skip regional palms on sand");
    }

    private static int countPlacedTreeBlocks(net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes,
                                             SurfaceGenerator.Surface surface, boolean enabled,
                                             RegionalTrees.Species species) {
        var gen = new SurfaceGenerator(0, biomes, RandomSource.create(0)) {
            @Override protected boolean customTrees() { return enabled; }
            @Override protected RegionalTrees.Species regionalTree(int x, int z, int y, Surface s, Tree kind, long roll) { return species; }
            @Override protected void ground(int x, int z, int[] out) { out[0] = 65; out[1] = DRY; }
            @Override protected Surface surface(int x, int z, int ground, int water, int slope) { return surface; }
            @Override public String id() { return "test_regional_trees"; }
            @Override public int seaLevel() { return 63; }
            @Override protected int shoreLevel() { return 63; }
            @Override protected boolean emeralds(int maxGround) { return false; }
        };
        int[] count = {0};
        new Trees(gen).place(0, 64, 0, 31, 95, 31, false, (x, y, z, state, kind) -> count[0]++);
        return count[0];
    }
}
