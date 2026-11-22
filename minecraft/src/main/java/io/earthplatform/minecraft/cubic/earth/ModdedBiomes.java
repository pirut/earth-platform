package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Optional realistic biomes and surface blocks from installed biome mods (Biomes O' Plenty, Regions Unexplored,
 * and Oh The Biomes We've Gone), looked up softly by registry id with zero hard mod dependencies.
 *
 * <p>Every ecological slot falls back to its exact vanilla biome or block when no matching mod biome is
 * registered or when modded biome integration is disabled, keeping vanilla worlds identical.
 */
public final class ModdedBiomes {
    // Ecological biome slots ---------------------------------------------------------------------

    public final Holder<Biome> redwoodForest, oldGrowthConifer, bayou, marsh, wetland, bog, hotSprings, moor,
            tundra, coldDesert, dryland, scrubland, shrubland, rockyShrubland, prairie, grassland, lushSavanna,
            mediterraneanForest, coniferousForest, firClearing, snowyConiferousForest, woodland, oldGrowthWoodland,
            mapleWoods, seasonalForest, aspenGlade, rainforest, tropics, volcano, volcanicPlains, highland,
            duneBeach, gravelBeach;

    // Optional modded surface and giant-tree blocks ----------------------------------------------

    public final BlockState whiteSand, whiteSandstone, blackSand, blackSandstone, orangeSand, orangeSandstone, driedSalt;
    private final boolean hasWhiteSand, hasBlackSand, hasOrangeSand, hasDriedSalt;
    @Nullable private final BlockState[] redwoodGiant, sequoiaGiant, firGiant;
    private final int biomeMatches, blockMatches;

    private ModdedBiomes(Registry<Biome> biomes, Function<ResourceLocation, Optional<Block>> blocks, boolean enabled) {
        Holder<Biome> oldTaiga = biomes.getHolderOrThrow(Biomes.OLD_GROWTH_SPRUCE_TAIGA);
        Holder<Biome> taiga = biomes.getHolderOrThrow(Biomes.TAIGA);
        Holder<Biome> snowyTaiga = biomes.getHolderOrThrow(Biomes.SNOWY_TAIGA);
        Holder<Biome> swamp = biomes.getHolderOrThrow(Biomes.SWAMP);
        Holder<Biome> meadow = biomes.getHolderOrThrow(Biomes.MEADOW);
        Holder<Biome> desert = biomes.getHolderOrThrow(Biomes.DESERT);
        Holder<Biome> savanna = biomes.getHolderOrThrow(Biomes.SAVANNA);
        Holder<Biome> plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        Holder<Biome> forest = biomes.getHolderOrThrow(Biomes.FOREST);
        Holder<Biome> darkForest = biomes.getHolderOrThrow(Biomes.DARK_FOREST);
        Holder<Biome> birchForest = biomes.getHolderOrThrow(Biomes.BIRCH_FOREST);
        Holder<Biome> jungle = biomes.getHolderOrThrow(Biomes.JUNGLE);
        Holder<Biome> sparseJungle = biomes.getHolderOrThrow(Biomes.SPARSE_JUNGLE);
        Holder<Biome> stonyPeaks = biomes.getHolderOrThrow(Biomes.STONY_PEAKS);
        Holder<Biome> windsweptHills = biomes.getHolderOrThrow(Biomes.WINDSWEPT_HILLS);
        Holder<Biome> beach = biomes.getHolderOrThrow(Biomes.BEACH);

        int[] foundBiomes = {0};
        int[] foundBlocks = {0};

        redwoodForest = pickBiome(biomes, enabled, oldTaiga, false, foundBiomes,
                "biomesoplenty:redwood_forest", "regions_unexplored:redwoods", "biomeswevegone:redwood_thicket");
        oldGrowthConifer = pickBiome(biomes, enabled, oldTaiga, false, foundBiomes,
                "biomesoplenty:coniferous_forest", "regions_unexplored:pine_taiga", "biomeswevegone:coniferous_forest");
        bayou = pickBiome(biomes, enabled, swamp, false, foundBiomes,
                "biomesoplenty:bayou", "regions_unexplored:bayou", "biomeswevegone:bayou");
        marsh = pickBiome(biomes, enabled, swamp, false, foundBiomes,
                "biomesoplenty:marsh", "regions_unexplored:marsh", "biomeswevegone:cypress_wetlands");
        wetland = pickBiome(biomes, enabled, swamp, false, foundBiomes,
                "biomesoplenty:wetland", "biomesoplenty:floodplain", "regions_unexplored:fen", "biomeswevegone:cypress_swamplands");
        bog = pickBiome(biomes, enabled, swamp, false, foundBiomes,
                "biomesoplenty:bog", "biomesoplenty:muskeg", "regions_unexplored:fen", "biomeswevegone:pale_bog");
        hotSprings = pickBiome(biomes, enabled, swamp, false, foundBiomes,
                "biomesoplenty:hot_springs");
        moor = pickBiome(biomes, enabled, taiga, false, foundBiomes,
                "biomesoplenty:moor", "regions_unexplored:highland_fields");
        tundra = pickBiome(biomes, enabled, meadow, false, foundBiomes,
                "biomesoplenty:tundra", "regions_unexplored:tundra", "regions_unexplored:frozen_tundra");
        coldDesert = pickBiome(biomes, enabled, desert, false, foundBiomes,
                "biomesoplenty:cold_desert", "regions_unexplored:steppe");
        dryland = pickBiome(biomes, enabled, savanna, false, foundBiomes,
                "biomesoplenty:dryland", "regions_unexplored:outback", "biomeswevegone:atacama_outback");
        scrubland = pickBiome(biomes, enabled, savanna, false, foundBiomes,
                "biomesoplenty:scrubland", "regions_unexplored:dry_bushland", "biomeswevegone:mojave_desert");
        shrubland = pickBiome(biomes, enabled, plains, false, foundBiomes,
                "biomesoplenty:shrubland", "regions_unexplored:shrubland");
        rockyShrubland = pickBiome(biomes, enabled, savanna, false, foundBiomes,
                "biomesoplenty:rocky_shrubland", "biomesoplenty:shrubland", "regions_unexplored:shrubland");
        prairie = pickBiome(biomes, enabled, plains, false, foundBiomes,
                "biomesoplenty:prairie", "regions_unexplored:prairie", "biomeswevegone:prairie");
        grassland = pickBiome(biomes, enabled, plains, false, foundBiomes,
                "biomesoplenty:grassland", "biomesoplenty:field", "regions_unexplored:grassland");
        lushSavanna = pickBiome(biomes, enabled, savanna, false, foundBiomes,
                "biomesoplenty:lush_savanna", "regions_unexplored:baobab_savanna", "biomeswevegone:baobab_savanna");
        mediterraneanForest = pickBiome(biomes, enabled, forest, false, foundBiomes,
                "biomesoplenty:mediterranean_forest");
        coniferousForest = pickBiome(biomes, enabled, taiga, false, foundBiomes,
                "biomesoplenty:coniferous_forest", "regions_unexplored:pine_taiga", "biomeswevegone:coniferous_forest");
        firClearing = pickBiome(biomes, enabled, taiga, false, foundBiomes,
                "biomesoplenty:fir_clearing", "biomesoplenty:coniferous_forest", "regions_unexplored:boreal_taiga", "biomeswevegone:coniferous_forest");
        snowyConiferousForest = pickBiome(biomes, enabled, snowyTaiga, true, foundBiomes,
                "biomesoplenty:snowy_coniferous_forest", "regions_unexplored:frozen_pine_taiga", "biomeswevegone:frosted_coniferous_forest");
        woodland = pickBiome(biomes, enabled, forest, false, foundBiomes,
                "biomesoplenty:woodland", "regions_unexplored:old_growth_forest", "regions_unexplored:deciduous_forest", "biomeswevegone:temperate_grove");
        oldGrowthWoodland = pickBiome(biomes, enabled, darkForest, false, foundBiomes,
                "biomesoplenty:old_growth_woodland", "regions_unexplored:old_growth_forest", "biomeswevegone:overgrowth_woodlands");
        mapleWoods = pickBiome(biomes, enabled, forest, false, foundBiomes,
                "biomesoplenty:maple_woods", "regions_unexplored:maple_forest", "biomeswevegone:maple_taiga");
        seasonalForest = pickBiome(biomes, enabled, forest, false, foundBiomes,
                "biomesoplenty:seasonal_forest", "regions_unexplored:autumnal_maple_forest");
        aspenGlade = pickBiome(biomes, enabled, birchForest, false, foundBiomes,
                "biomesoplenty:aspen_glade", "regions_unexplored:silver_birch_forest", "biomeswevegone:aspen_boreal");
        rainforest = pickBiome(biomes, enabled, jungle, false, foundBiomes,
                "biomesoplenty:rainforest", "regions_unexplored:rainforest", "biomeswevegone:tropical_rainforest");
        tropics = pickBiome(biomes, enabled, sparseJungle, false, foundBiomes,
                "biomesoplenty:tropics", "regions_unexplored:tropics", "biomeswevegone:fragment_jungle");
        volcano = pickBiome(biomes, enabled, stonyPeaks, false, foundBiomes,
                "biomesoplenty:volcano");
        volcanicPlains = pickBiome(biomes, enabled, stonyPeaks, false, foundBiomes,
                "biomesoplenty:volcanic_plains");
        highland = pickBiome(biomes, enabled, windsweptHills, false, foundBiomes,
                "biomesoplenty:highland", "regions_unexplored:highland_fields");
        duneBeach = pickBiome(biomes, enabled, beach, false, foundBiomes,
                "biomesoplenty:dune_beach");
        gravelBeach = pickBiome(biomes, enabled, beach, false, foundBiomes,
                "biomesoplenty:gravel_beach", "regions_unexplored:gravel_beach");

        BlockState sand = Blocks.SAND.defaultBlockState();
        BlockState sandstone = Blocks.SANDSTONE.defaultBlockState();
        BlockState redSand = Blocks.RED_SAND.defaultBlockState();
        BlockState redSandstone = Blocks.RED_SANDSTONE.defaultBlockState();
        BlockState calcite = Blocks.CALCITE.defaultBlockState();

        BlockState ws = pickBlock(blocks, enabled, sand, foundBlocks, "biomesoplenty:white_sand");
        hasWhiteSand = ws != sand;
        whiteSand = ws;
        whiteSandstone = pickBlock(blocks, enabled, sandstone, foundBlocks, "biomesoplenty:white_sandstone");

        BlockState bs = pickBlock(blocks, enabled, sand, foundBlocks, "biomesoplenty:black_sand");
        hasBlackSand = bs != sand;
        blackSand = bs;
        blackSandstone = pickBlock(blocks, enabled, sandstone, foundBlocks, "biomesoplenty:black_sandstone");

        BlockState os = pickBlock(blocks, enabled, redSand, foundBlocks, "biomesoplenty:orange_sand");
        hasOrangeSand = os != redSand;
        orangeSand = os;
        orangeSandstone = pickBlock(blocks, enabled, redSandstone, foundBlocks, "biomesoplenty:orange_sandstone");

        BlockState ds = pickBlock(blocks, enabled, calcite, foundBlocks, "biomesoplenty:dried_salt");
        hasDriedSalt = ds != calcite;
        driedSalt = ds;

        BlockState spruceWood = Blocks.SPRUCE_WOOD.defaultBlockState();
        BlockState darkOakWood = Blocks.DARK_OAK_WOOD.defaultBlockState();
        BlockState mangroveWood = Blocks.MANGROVE_WOOD.defaultBlockState();
        BlockState spruceLeaves = persistent(Blocks.SPRUCE_LEAVES.defaultBlockState());

        BlockState rwWood = pickBlock(blocks, enabled, mangroveWood, foundBlocks,
                "biomesoplenty:redwood_wood", "regions_unexplored:redwood_wood", "biomeswevegone:redwood_wood");
        BlockState rwLeaves = persistent(pickBlock(blocks, enabled, spruceLeaves, foundBlocks,
                "biomesoplenty:redwood_leaves", "regions_unexplored:redwood_leaves", "biomeswevegone:redwood_leaves"));
        if (rwWood != mangroveWood || rwLeaves != spruceLeaves) {
            BlockState[] pair = new BlockState[]{rwWood, spruceWood, rwLeaves};
            sequoiaGiant = pair;
            redwoodGiant = pair;
        } else {
            sequoiaGiant = null;
            redwoodGiant = null;
        }

        BlockState fWood = pickBlock(blocks, enabled, spruceWood, foundBlocks,
                "biomesoplenty:fir_wood", "regions_unexplored:pine_wood");
        BlockState fLeaves = persistent(pickBlock(blocks, enabled, spruceLeaves, foundBlocks,
                "biomesoplenty:fir_leaves", "regions_unexplored:pine_leaves"));
        firGiant = fWood != spruceWood || fLeaves != spruceLeaves ? new BlockState[]{fWood, darkOakWood, fLeaves} : null;

        biomeMatches = foundBiomes[0];
        blockMatches = foundBlocks[0];
    }

    /** Resolves optional modded biomes from {@code biomes} and blocks from {@link BuiltInRegistries#BLOCK}. */
    public static ModdedBiomes resolve(Registry<Biome> biomes, boolean enabled) {
        return resolve(biomes, BuiltInRegistries.BLOCK::getOptional, enabled);
    }

    /** Resolves optional modded biomes and blocks with a custom block lookup, for tests. */
    static ModdedBiomes resolve(Registry<Biome> biomes, Function<ResourceLocation, Optional<Block>> blocks, boolean enabled) {
        return new ModdedBiomes(biomes, blocks, enabled);
    }

    /** Whether at least one realistic modded biome or modded surface/tree block is active. */
    public boolean active() { return biomeMatches > 0 || blockMatches > 0; }

    public int biomeMatches() { return biomeMatches; }
    public int blockMatches() { return blockMatches; }
    public boolean hasWhiteSand() { return hasWhiteSand; }
    public boolean hasBlackSand() { return hasBlackSand; }
    public boolean hasOrangeSand() { return hasOrangeSand; }
    public boolean hasDriedSalt() { return hasDriedSalt; }

    /**
     * Optional {@code {bark, furrow, leaves}} override for giant conifers (0 sequoia, 1 coast redwood, 2 fir),
     * or null when vanilla blocks should be used.
     */
    @Nullable public BlockState[] giantBlocks(int form) {
        return switch (form) {
            case 0 -> sequoiaGiant;
            case 1 -> redwoodGiant;
            case 2 -> firGiant;
            default -> null;
        };
    }

    private static Holder<Biome> pickBiome(Registry<Biome> biomes, boolean enabled, Holder<Biome> fallback,
                                           boolean requireSnowy, int[] matches, String... ids) {
        if (!enabled) return fallback;
        for (String id : ids) {
            Optional<Holder.Reference<Biome>> found = biomes.getHolder(ResourceKey.create(Registries.BIOME, ResourceLocation.parse(id)));
            if (found.isEmpty()) continue;
            if (requireSnowy && found.get().value().getBaseTemperature() >= 0.15f) continue;
            matches[0]++;
            return found.get();
        }
        return fallback;
    }

    private static BlockState pickBlock(Function<ResourceLocation, Optional<Block>> blocks, boolean enabled,
                                        BlockState fallback, int[] matches, String... ids) {
        if (!enabled) return fallback;
        for (String id : ids) {
            Optional<Block> found = blocks.apply(ResourceLocation.parse(id));
            if (found.isEmpty()) continue;
            Block block = found.get();
            if (block == Blocks.AIR) continue;
            matches[0]++;
            return block.defaultBlockState();
        }
        return fallback;
    }

    private static BlockState persistent(BlockState state) {
        return state.hasProperty(LeavesBlock.PERSISTENT) ? state.setValue(LeavesBlock.PERSISTENT, true) : state;
    }

    // Ecological classification helpers shared with Vegetation, Trees and SurfaceGenerator --------

    private static final Set<String> WETLAND_PATHS = Set.of(
            "bayou", "marsh", "wetland", "floodplain", "bog", "muskeg", "fen", "hot_springs",
            "cypress_swamplands", "cypress_wetlands", "pale_bog");
    private static final Set<String> ARID_PATHS = Set.of(
            "cold_desert", "dryland", "scrubland", "rocky_shrubland", "outback", "steppe",
            "dry_bushland", "mojave_desert", "atacama_outback", "lush_savanna", "baobab_savanna");
    private static final Set<String> JUNGLE_PATHS = Set.of(
            "rainforest", "tropics", "tropical_rainforest", "fragment_jungle");
    private static final Set<String> TAIGA_PATHS = Set.of(
            "redwood_forest", "redwoods", "redwood_thicket", "coniferous_forest", "snowy_coniferous_forest",
            "fir_clearing", "moor", "pine_taiga", "frozen_pine_taiga", "boreal_taiga", "frosted_coniferous_forest");
    private static final Set<String> FOREST_PATHS = Set.of(
            "woodland", "old_growth_woodland", "maple_woods", "seasonal_forest", "mediterranean_forest",
            "aspen_glade", "maple_forest", "autumnal_maple_forest", "old_growth_forest", "deciduous_forest",
            "silver_birch_forest", "temperate_grove", "overgrowth_woodlands", "maple_taiga", "aspen_boreal");
    private static final Set<String> PLAINS_PATHS = Set.of(
            "prairie", "grassland", "field", "shrubland");
    private static final Set<String> MEADOW_PATHS = Set.of(
            "tundra", "frozen_tundra", "highland", "highland_fields");

    /** Whether a biome comes from a third-party mod rather than vanilla or Earth Platform. */
    public static boolean isModdedBiome(Holder<Biome> biome) {
        String namespace = biome.unwrapKey().map(k -> k.location().getNamespace()).orElse("minecraft");
        return !namespace.equals("minecraft") && !namespace.equals(EarthPlatformMod.MOD_ID);
    }

    /** Whether a block is vanilla sand or a modded sand (such as white, black or orange sand). */
    public static boolean isSand(BlockState state) {
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(BlockTags.SAND)) return true;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && !id.getNamespace().equals("minecraft") && id.getPath().endsWith("_sand");
    }

    /** Whether a block is vanilla or modded sandstone (country rock that stands in cliff faces). */
    public static boolean isSandstone(BlockState state) {
        if (state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)) return true;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && !id.getNamespace().equals("minecraft") && id.getPath().endsWith("sandstone");
    }

    /** Whether a biome is a freshwater wetland, swamp, bayou, marsh or peat bog. */
    public static boolean isWetland(Holder<Biome> biome) {
        return biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP) || hasModdedPath(biome, WETLAND_PATHS);
    }

    /** Whether a biome is arid scrub, savanna, cold desert, desert or badlands. */
    public static boolean isArid(Holder<Biome> biome) {
        return biome.is(Biomes.SAVANNA) || biome.is(Biomes.WINDSWEPT_SAVANNA) || biome.is(Biomes.DESERT)
                || biome.is(Biomes.BADLANDS) || biome.is(BiomeTags.IS_SAVANNA) || biome.is(BiomeTags.IS_BADLANDS)
                || hasModdedPath(biome, ARID_PATHS);
    }

    /** Whether a biome is tropical rainforest or jungle. */
    public static boolean isJungle(Holder<Biome> biome) {
        return biome.is(Biomes.JUNGLE) || biome.is(Biomes.SPARSE_JUNGLE) || biome.is(Biomes.BAMBOO_JUNGLE)
                || biome.is(BiomeTags.IS_JUNGLE) || hasModdedPath(biome, JUNGLE_PATHS);
    }

    /** Whether a biome is taiga, coniferous forest, redwood forest or moorland. */
    public static boolean isTaiga(Holder<Biome> biome) {
        return biome.is(Biomes.TAIGA) || biome.is(Biomes.SNOWY_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
                || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || biome.is(BiomeTags.IS_TAIGA)
                || hasModdedPath(biome, TAIGA_PATHS);
    }

    /** Whether a biome is temperate broadleaf or Mediterranean forest. */
    public static boolean isForest(Holder<Biome> biome) {
        return biome.is(Biomes.FOREST) || biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)
                || biome.is(Biomes.DARK_FOREST) || biome.is(BiomeTags.IS_FOREST)
                || hasModdedPath(biome, FOREST_PATHS);
    }

    /** Whether a biome is open prairie, grassland or temperate plains. */
    public static boolean isPlains(Holder<Biome> biome) {
        return biome.is(Biomes.PLAINS) || biome.is(Biomes.SUNFLOWER_PLAINS) || hasModdedPath(biome, PLAINS_PATHS);
    }

    /** Whether a biome is alpine meadow, highland or tundra. */
    public static boolean isMeadow(Holder<Biome> biome) {
        return biome.is(Biomes.MEADOW) || hasModdedPath(biome, MEADOW_PATHS);
    }

    private static boolean hasModdedPath(Holder<Biome> biome, Set<String> paths) {
        return biome.unwrapKey()
                .map(k -> !k.location().getNamespace().equals("minecraft") && paths.contains(k.location().getPath()))
                .orElse(false);
    }
}
