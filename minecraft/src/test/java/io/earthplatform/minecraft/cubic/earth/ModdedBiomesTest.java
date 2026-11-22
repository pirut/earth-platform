package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModdedBiomesTest {
    private static final List<String> BOP_REALISTIC = List.of(
            "biomesoplenty:redwood_forest",
            "biomesoplenty:bayou",
            "biomesoplenty:marsh",
            "biomesoplenty:wetland",
            "biomesoplenty:bog",
            "biomesoplenty:hot_springs",
            "biomesoplenty:moor",
            "biomesoplenty:tundra",
            "biomesoplenty:cold_desert",
            "biomesoplenty:dryland",
            "biomesoplenty:scrubland",
            "biomesoplenty:shrubland",
            "biomesoplenty:rocky_shrubland",
            "biomesoplenty:prairie",
            "biomesoplenty:grassland",
            "biomesoplenty:lush_savanna",
            "biomesoplenty:mediterranean_forest",
            "biomesoplenty:coniferous_forest",
            "biomesoplenty:fir_clearing",
            "biomesoplenty:snowy_coniferous_forest",
            "biomesoplenty:woodland",
            "biomesoplenty:old_growth_woodland",
            "biomesoplenty:maple_woods",
            "biomesoplenty:seasonal_forest",
            "biomesoplenty:aspen_glade",
            "biomesoplenty:rainforest",
            "biomesoplenty:tropics",
            "biomesoplenty:volcano",
            "biomesoplenty:volcanic_plains",
            "biomesoplenty:highland",
            "biomesoplenty:dune_beach",
            "biomesoplenty:gravel_beach");

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Biome syntheticBiome(float temperature) {
        return new Biome.BiomeBuilder()
                .hasPrecipitation(temperature < 1.5f)
                .temperature(temperature)
                .downfall(0.5f)
                .specialEffects(new BiomeSpecialEffects.Builder()
                        .fogColor(0xC0D8FF)
                        .waterColor(0x3F76E4)
                        .waterFogColor(0x050533)
                        .skyColor(0x78A7FF)
                        .build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY)
                .build();
    }

    private static Registry<Biome> registryWith(Map<String, Float> extras) {
        MappedRegistry<Biome> r = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> r.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        r.register(ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("earthplatform", "glacial_lake")),
                syntheticBiome(0.2f), RegistrationInfo.BUILT_IN);
        for (var entry : extras.entrySet()) {
            ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(entry.getKey()));
            r.register(key, syntheticBiome(entry.getValue()), RegistrationInfo.BUILT_IN);
        }
        return r.freeze();
    }

    private static Registry<Biome> bopRegistry() {
        var map = new java.util.LinkedHashMap<String, Float>();
        for (String id : BOP_REALISTIC) {
            float temp = id.endsWith("snowy_coniferous_forest") ? -0.5f
                    : id.endsWith("tundra") ? 0.2f
                    : id.endsWith("cold_desert") ? 0.25f
                    : id.endsWith("hot_springs") ? 0.17f
                    : id.endsWith("volcano") || id.endsWith("volcanic_plains") ? 2.0f
                    : 0.7f;
            map.put(id, temp);
        }
        return registryWith(map);
    }

    @Test void vanillaRegistryStaysInactiveAndMatchesVanillaFallbacks() {
        Registry<Biome> vanilla = registryWith(Map.of());
        ModdedBiomes resolved = ModdedBiomes.resolve(vanilla, id -> Optional.empty(), true);

        assertFalse(resolved.active());
        assertEquals(0, resolved.biomeMatches());
        assertEquals(0, resolved.blockMatches());
        assertEquals(vanilla.getHolderOrThrow(Biomes.OLD_GROWTH_SPRUCE_TAIGA), resolved.redwoodForest);
        assertEquals(vanilla.getHolderOrThrow(Biomes.OLD_GROWTH_SPRUCE_TAIGA), resolved.oldGrowthConifer);
        assertEquals(vanilla.getHolderOrThrow(Biomes.SWAMP), resolved.bayou);
        assertEquals(vanilla.getHolderOrThrow(Biomes.SWAMP), resolved.marsh);
        assertEquals(vanilla.getHolderOrThrow(Biomes.SWAMP), resolved.wetland);
        assertEquals(vanilla.getHolderOrThrow(Biomes.SWAMP), resolved.bog);
        assertEquals(vanilla.getHolderOrThrow(Biomes.SWAMP), resolved.hotSprings);
        assertEquals(vanilla.getHolderOrThrow(Biomes.TAIGA), resolved.moor);
        assertEquals(vanilla.getHolderOrThrow(Biomes.MEADOW), resolved.tundra);
        assertEquals(vanilla.getHolderOrThrow(Biomes.DESERT), resolved.coldDesert);
        assertEquals(vanilla.getHolderOrThrow(Biomes.PLAINS), resolved.prairie);
        assertEquals(vanilla.getHolderOrThrow(Biomes.FOREST), resolved.mediterraneanForest);
        assertEquals(vanilla.getHolderOrThrow(Biomes.JUNGLE), resolved.rainforest);
        assertEquals(vanilla.getHolderOrThrow(Biomes.SNOWY_TAIGA), resolved.snowyConiferousForest);
        assertEquals(vanilla.getHolderOrThrow(Biomes.STONY_PEAKS), resolved.volcano);
        assertEquals(vanilla.getHolderOrThrow(Biomes.BEACH), resolved.duneBeach);
        assertFalse(resolved.hasWhiteSand());
        assertFalse(resolved.hasBlackSand());
        assertFalse(resolved.hasOrangeSand());
        assertFalse(resolved.hasDriedSalt());
        assertNull(resolved.giantBlocks(0));
        assertNull(resolved.giantBlocks(1));
        assertNull(resolved.giantBlocks(2));
    }

    @Test void biomesOPlentyBiomesResolveIntoAllRealisticSlots() {
        Registry<Biome> bop = bopRegistry();
        ModdedBiomes resolved = ModdedBiomes.resolve(bop, id -> Optional.empty(), true);

        assertTrue(resolved.active());
        assertEquals(33, resolved.biomeMatches());
        assertEquals("biomesoplenty:redwood_forest", id(resolved.redwoodForest));
        assertEquals("biomesoplenty:coniferous_forest", id(resolved.oldGrowthConifer));
        assertEquals("biomesoplenty:bayou", id(resolved.bayou));
        assertEquals("biomesoplenty:marsh", id(resolved.marsh));
        assertEquals("biomesoplenty:wetland", id(resolved.wetland));
        assertEquals("biomesoplenty:bog", id(resolved.bog));
        assertEquals("biomesoplenty:hot_springs", id(resolved.hotSprings));
        assertEquals("biomesoplenty:moor", id(resolved.moor));
        assertEquals("biomesoplenty:tundra", id(resolved.tundra));
        assertEquals("biomesoplenty:cold_desert", id(resolved.coldDesert));
        assertEquals("biomesoplenty:dryland", id(resolved.dryland));
        assertEquals("biomesoplenty:scrubland", id(resolved.scrubland));
        assertEquals("biomesoplenty:shrubland", id(resolved.shrubland));
        assertEquals("biomesoplenty:rocky_shrubland", id(resolved.rockyShrubland));
        assertEquals("biomesoplenty:prairie", id(resolved.prairie));
        assertEquals("biomesoplenty:grassland", id(resolved.grassland));
        assertEquals("biomesoplenty:lush_savanna", id(resolved.lushSavanna));
        assertEquals("biomesoplenty:mediterranean_forest", id(resolved.mediterraneanForest));
        assertEquals("biomesoplenty:coniferous_forest", id(resolved.coniferousForest));
        assertEquals("biomesoplenty:fir_clearing", id(resolved.firClearing));
        assertEquals("biomesoplenty:snowy_coniferous_forest", id(resolved.snowyConiferousForest));
        assertEquals("biomesoplenty:woodland", id(resolved.woodland));
        assertEquals("biomesoplenty:old_growth_woodland", id(resolved.oldGrowthWoodland));
        assertEquals("biomesoplenty:maple_woods", id(resolved.mapleWoods));
        assertEquals("biomesoplenty:seasonal_forest", id(resolved.seasonalForest));
        assertEquals("biomesoplenty:aspen_glade", id(resolved.aspenGlade));
        assertEquals("biomesoplenty:rainforest", id(resolved.rainforest));
        assertEquals("biomesoplenty:tropics", id(resolved.tropics));
        assertEquals("biomesoplenty:volcano", id(resolved.volcano));
        assertEquals("biomesoplenty:volcanic_plains", id(resolved.volcanicPlains));
        assertEquals("biomesoplenty:highland", id(resolved.highland));
        assertEquals("biomesoplenty:dune_beach", id(resolved.duneBeach));
        assertEquals("biomesoplenty:gravel_beach", id(resolved.gravelBeach));
    }

    @Test void regionsUnexploredAndBiomesWeveGoneServeAsCleanFallbacks() {
        Registry<Biome> ru = registryWith(Map.of(
                "regions_unexplored:redwoods", 0.5f,
                "regions_unexplored:bayou", 0.8f,
                "regions_unexplored:marsh", 0.6f,
                "regions_unexplored:tundra", 0.1f,
                "regions_unexplored:steppe", 0.3f,
                "regions_unexplored:frozen_pine_taiga", -0.5f));
        ModdedBiomes ruResolved = ModdedBiomes.resolve(ru, id -> Optional.empty(), true);
        assertEquals("regions_unexplored:redwoods", id(ruResolved.redwoodForest));
        assertEquals("regions_unexplored:bayou", id(ruResolved.bayou));
        assertEquals("regions_unexplored:marsh", id(ruResolved.marsh));
        assertEquals("regions_unexplored:tundra", id(ruResolved.tundra));
        assertEquals("regions_unexplored:steppe", id(ruResolved.coldDesert));
        assertEquals("regions_unexplored:frozen_pine_taiga", id(ruResolved.snowyConiferousForest));

        Registry<Biome> bwg = registryWith(Map.of(
                "biomeswevegone:redwood_thicket", 0.5f,
                "biomeswevegone:bayou", 0.8f,
                "biomeswevegone:prairie", 0.7f,
                "biomeswevegone:tropical_rainforest", 0.95f));
        ModdedBiomes bwgResolved = ModdedBiomes.resolve(bwg, id -> Optional.empty(), true);
        assertEquals("biomeswevegone:redwood_thicket", id(bwgResolved.redwoodForest));
        assertEquals("biomeswevegone:bayou", id(bwgResolved.bayou));
        assertEquals("biomeswevegone:prairie", id(bwgResolved.prairie));
        assertEquals("biomeswevegone:tropical_rainforest", id(bwgResolved.rainforest));
    }

    @Test void snowyConiferousSlotRefusesNonSnowyBiomes() {
        Registry<Biome> warmSnowy = registryWith(Map.of("biomesoplenty:snowy_coniferous_forest", 0.5f));
        ModdedBiomes resolved = ModdedBiomes.resolve(warmSnowy, id -> Optional.empty(), true);
        assertEquals("minecraft:snowy_taiga", id(resolved.snowyConiferousForest));
    }

    @Test void disabledToggleRevertsAllSlotsAndBlocksToVanilla() {
        Registry<Biome> bop = bopRegistry();
        Map<ResourceLocation, Block> fakeBlocks = Map.of(
                ResourceLocation.parse("biomesoplenty:white_sand"), Blocks.CALCITE,
                ResourceLocation.parse("biomesoplenty:black_sand"), Blocks.BASALT,
                ResourceLocation.parse("biomesoplenty:dried_salt"), Blocks.QUARTZ_BLOCK,
                ResourceLocation.parse("biomesoplenty:redwood_wood"), Blocks.ACACIA_WOOD,
                ResourceLocation.parse("biomesoplenty:redwood_leaves"), Blocks.DARK_OAK_LEAVES);

        ModdedBiomes enabled = ModdedBiomes.resolve(bop, id -> Optional.ofNullable(fakeBlocks.get(id)), true);
        assertTrue(enabled.active());
        assertTrue(enabled.hasWhiteSand());
        assertTrue(enabled.hasBlackSand());
        assertTrue(enabled.hasDriedSalt());
        BlockState[] redwoodGiant = enabled.giantBlocks(1);
        assertNotNull(redwoodGiant);
        assertEquals(Blocks.ACACIA_WOOD.defaultBlockState(), redwoodGiant[0]);
        assertEquals(Blocks.DARK_OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), redwoodGiant[2]);

        ModdedBiomes disabled = ModdedBiomes.resolve(bop, id -> Optional.ofNullable(fakeBlocks.get(id)), false);
        assertFalse(disabled.active());
        assertEquals("minecraft:old_growth_spruce_taiga", id(disabled.redwoodForest));
        assertEquals("minecraft:swamp", id(disabled.bayou));
        assertEquals("minecraft:desert", id(disabled.coldDesert));
        assertEquals("minecraft:meadow", id(disabled.tundra));
        assertFalse(disabled.hasWhiteSand());
        assertFalse(disabled.hasBlackSand());
        assertFalse(disabled.hasDriedSalt());
        assertNull(disabled.giantBlocks(0));
        assertNull(disabled.giantBlocks(1));
        assertNull(disabled.giantBlocks(2));
    }

    @Test void earthGeneratorAssignsBopBiomesAndRevertsIdenticallyWhenDisabled(@TempDir Path dir) throws IOException {
        Registry<Biome> vanillaReg = registryWith(Map.of());
        Registry<Biome> bopReg = bopRegistry();
        EarthSettings settings = EarthSettings.defaults();
        seedFlatTiles(dir, 11, 1024, 3);
        seedFlatTiles(dir, 9, 256, 2);
        try (TerrariumTileSource tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, dir, false, 64, 1, Duration.ofSeconds(1))) {
            EarthGenerator vanillaGen = new EarthGenerator(42L, vanillaReg, settings, tiles, null, null, null);
            EarthGenerator bopGen = new EarthGenerator(42L, bopReg, settings, tiles, null, null, null);
            EarthGenerator disabledGen = new EarthGenerator(42L, bopReg, settings, tiles, null, null, null);
            disabledGen.setModdedBiomes(false);

            // Cold desert bare ground (steep >= 0.2 avoids saltPan DEM lookup on uncached test tiles)
            var coldDesert = new EarthGenerator.Local(ClimateZone.COLD_DESERT, 6, 18, 150, ClimateSource.BWK);
            Surface vCold = vanillaGen.covered(WorldCoverSource.BARE, 0, 0, 100, coldDesert, 0.25, 10, 0, 1);
            Surface bCold = bopGen.covered(WorldCoverSource.BARE, 0, 0, 100, coldDesert, 0.25, 10, 0, 1);
            Surface dCold = disabledGen.covered(WorldCoverSource.BARE, 0, 0, 100, coldDesert, 0.25, 10, 0, 1);
            assertEquals("minecraft:desert", id(vCold.biome()));
            assertEquals("biomesoplenty:cold_desert", id(bCold.biome()));
            assertEquals(vCold.biome().unwrapKey(), dCold.biome().unwrapKey());

            // Arctic/alpine summer tundra
            var tundra = new EarthGenerator.Local(ClimateZone.TUNDRA, 0, 7, 300, ClimateSource.ET);
            Surface vTundra = vanillaGen.covered(WorldCoverSource.MOSS_AND_LICHEN, 0, 0, 100, tundra, 0.1, 10, 0, 1);
            Surface bTundra = bopGen.covered(WorldCoverSource.MOSS_AND_LICHEN, 0, 0, 100, tundra, 0.1, 10, 0, 1);
            Surface dTundra = disabledGen.covered(WorldCoverSource.MOSS_AND_LICHEN, 0, 0, 100, tundra, 0.1, 10, 0, 1);
            assertEquals("minecraft:meadow", id(vTundra.biome()));
            assertEquals("biomesoplenty:tundra", id(bTundra.biome()));
            assertEquals(vTundra.biome().unwrapKey(), dTundra.biome().unwrapKey());

            // Temperate tidal marsh (trees == 0 near sea level)
            var temperate = new EarthGenerator.Local(ClimateZone.OCEANIC, 12, 19, 900, ClimateSource.CFB);
            Surface vMarsh = vanillaGen.covered(WorldCoverSource.WETLAND, 0, 0, 65, temperate, 0.05, 10, 0, 1);
            Surface bMarsh = bopGen.covered(WorldCoverSource.WETLAND, 0, 0, 65, temperate, 0.05, 10, 0, 1);
            assertEquals("minecraft:swamp", id(vMarsh.biome()));
            assertEquals("biomesoplenty:marsh", id(bMarsh.biome()));

            // Inland temperate wetland (trees > 0)
            Surface bWetland = bopGen.covered(WorldCoverSource.WETLAND, 0, 0, 90, temperate, 0.05, 10, 0, 1);
            assertEquals("biomesoplenty:wetland", id(bWetland.biome()));

            // Mediterranean forest
            var med = new EarthGenerator.Local(ClimateZone.MEDITERRANEAN, 16, 24, 650, ClimateSource.CSA);
            Surface vMed = vanillaGen.covered(WorldCoverSource.TREE_COVER, 0, 0, 100, med, 0.1, 10, 0, 1);
            Surface bMed = bopGen.covered(WorldCoverSource.TREE_COVER, 0, 0, 100, med, 0.1, 10, 0, 1);
            assertEquals("minecraft:forest", id(vMed.biome()));
            assertEquals("biomesoplenty:mediterranean_forest", id(bMed.biome()));

            // Tropical rainforest
            var rain = new EarthGenerator.Local(ClimateZone.RAINFOREST, 26, 27, 2500, ClimateSource.AF);
            Surface vRain = vanillaGen.covered(WorldCoverSource.TREE_COVER, 0, 0, 100, rain, 0.1, 10, 0, 1);
            Surface bRain = bopGen.covered(WorldCoverSource.TREE_COVER, 0, 0, 100, rain, 0.1, 10, 0, 1);
            assertEquals("minecraft:jungle", id(vRain.biome()));
            assertEquals("biomesoplenty:rainforest", id(bRain.biome()));

            // Heather moor (cool wet oceanic)
            var moor = new EarthGenerator.Local(ClimateZone.OCEANIC, 7, 12, 1400, ClimateSource.CFB);
            Surface vMoor = vanillaGen.covered(WorldCoverSource.SHRUBLAND, 0, 0, 100, moor, 0.1, 10, 0, 1);
            Surface bMoor = bopGen.covered(WorldCoverSource.SHRUBLAND, 0, 0, 100, moor, 0.1, 10, 0, 1);
            assertEquals("minecraft:taiga", id(vMoor.biome()));
            assertEquals("biomesoplenty:moor", id(bMoor.biome()));

            // Dune beach and cold steep gravel beach
            Surface vBeach = vanillaGen.beachSurface(0, 0, 18, 0.1, true);
            Surface bBeach = bopGen.beachSurface(0, 0, 18, 0.1, true);
            assertEquals("minecraft:beach", id(vBeach.biome()));
            assertEquals("biomesoplenty:dune_beach", id(bBeach.biome()));
            Surface vGravel = vanillaGen.beachSurface(0, 0, 3, 0.5, false);
            Surface bGravel = bopGen.beachSurface(0, 0, 3, 0.5, false);
            assertEquals("minecraft:beach", id(vGravel.biome()));
            assertEquals("biomesoplenty:gravel_beach", id(bGravel.biome()));
            assertEquals(Blocks.GRAVEL.defaultBlockState(), bGravel.top());
        }
    }

    private static void seedFlatTiles(Path dir, int zoom, int center, int radius) throws IOException {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        int rgb = (128 << 16) | (100 << 8); // 100 m in Terrarium encoding
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) img.setRGB(x, y, rgb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        byte[] png = out.toByteArray();
        for (int tx = center - radius; tx <= center + radius; tx++) {
            Path col = dir.resolve(zoom + "/" + tx);
            Files.createDirectories(col);
            for (int ty = center - radius; ty <= center + radius; ty++) Files.write(col.resolve(ty + ".png"), png);
        }
    }

    private static String id(net.minecraft.core.Holder<Biome> biome) {
        return biome.unwrapKey().orElseThrow().location().toString();
    }
}
