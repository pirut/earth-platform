package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.gen.RegionalTrees;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.Test;

class EarthRegionalTreesTest {
    @Test void earthGeneratorSelectsExpectedRegionalSpeciesByClimateAndEcoregion() {
        var biomes = SurfacePacketFixtures.biomes();
        var jungle = biomes.getHolderOrThrow(Biomes.JUNGLE);
        var taiga = biomes.getHolderOrThrow(Biomes.TAIGA);
        var forest = biomes.getHolderOrThrow(Biomes.FOREST);
        var birchForest = biomes.getHolderOrThrow(Biomes.BIRCH_FOREST);
        var savanna = biomes.getHolderOrThrow(Biomes.SAVANNA);
        var beach = biomes.getHolderOrThrow(Biomes.BEACH);
        var swamp = biomes.getHolderOrThrow(Biomes.SWAMP);

        // Tropical rainforest -> RAINFOREST_CANOPY (plus lowland PALM and vanilla understory)
        Set<RegionalTrees.Species> rainforest = EnumSet.noneOf(RegionalTrees.Species.class);
        for (int c = 0; c < 100; c++) {
            var s = EarthGenerator.chooseRegionalTree(ClimateZone.RAINFOREST, 26, Ecoregions.TROPICAL_MOIST, Ecoregions.NEOTROPIC,
                    false, false, true, jungle, SurfaceGenerator.Tree.JUNGLE, c);
            if (s != null) rainforest.add(s);
        }
        assertTrue(rainforest.contains(RegionalTrees.Species.RAINFOREST_CANOPY));
        assertTrue(rainforest.contains(RegionalTrees.Species.PALM));

        // Tropical beach on sand & desert oasis -> PALM
        assertEquals(RegionalTrees.Species.PALM, EarthGenerator.chooseRegionalTree(
                ClimateZone.RAINFOREST, 27, Ecoregions.TROPICAL_MOIST, Ecoregions.NEOTROPIC,
                true, true, true, beach, SurfaceGenerator.Tree.JUNGLE, 50));
        assertEquals(RegionalTrees.Species.PALM, EarthGenerator.chooseRegionalTree(
                ClimateZone.HOT_DESERT, 29, Ecoregions.DESERT, Ecoregions.PALEARCTIC,
                false, false, true, savanna, SurfaceGenerator.Tree.ACACIA, 10));

        // Boreal taiga -> BOREAL_PINE
        assertEquals(RegionalTrees.Species.BOREAL_PINE, EarthGenerator.chooseRegionalTree(
                ClimateZone.SUBARCTIC, -2, Ecoregions.BOREAL, Ecoregions.PALEARCTIC,
                false, false, false, taiga, SurfaceGenerator.Tree.SPRUCE, 10));

        // Mediterranean woodland -> CYPRESS & STONE_PINE
        Set<RegionalTrees.Species> med = EnumSet.noneOf(RegionalTrees.Species.class);
        for (int c = 0; c < 100; c++) {
            var s = EarthGenerator.chooseRegionalTree(ClimateZone.MEDITERRANEAN, 16, Ecoregions.MEDITERRANEAN, Ecoregions.PALEARCTIC,
                    false, false, true, forest, SurfaceGenerator.Tree.OAK, c);
            if (s != null) med.add(s);
        }
        assertTrue(med.contains(RegionalTrees.Species.CYPRESS));
        assertTrue(med.contains(RegionalTrees.Species.STONE_PINE));

        // African savanna -> BAOBAB + FLAT_ACACIA; South American savanna -> FLAT_ACACIA without BAOBAB
        Set<RegionalTrees.Species> africa = EnumSet.noneOf(RegionalTrees.Species.class);
        Set<RegionalTrees.Species> southAmerica = EnumSet.noneOf(RegionalTrees.Species.class);
        for (int c = 0; c < 100; c++) {
            var af = EarthGenerator.chooseRegionalTree(ClimateZone.HOT_STEPPE, 25, Ecoregions.TROPICAL_GRASSLAND, Ecoregions.AFROTROPIC,
                    false, false, false, savanna, SurfaceGenerator.Tree.ACACIA, c);
            if (af != null) africa.add(af);
            var sa = EarthGenerator.chooseRegionalTree(ClimateZone.HOT_STEPPE, 25, Ecoregions.TROPICAL_GRASSLAND, Ecoregions.NEOTROPIC,
                    false, false, false, savanna, SurfaceGenerator.Tree.ACACIA, c);
            if (sa != null) southAmerica.add(sa);
        }
        assertTrue(africa.contains(RegionalTrees.Species.BAOBAB));
        assertTrue(africa.contains(RegionalTrees.Species.FLAT_ACACIA));
        assertFalse(southAmerica.contains(RegionalTrees.Species.BAOBAB));
        assertTrue(southAmerica.contains(RegionalTrees.Species.FLAT_ACACIA));

        // Temperate broadleaf & birch forests -> BROADLEAF_OAK & TALL_BIRCH
        assertEquals(RegionalTrees.Species.BROADLEAF_OAK, EarthGenerator.chooseRegionalTree(
                ClimateZone.CONTINENTAL, 10, Ecoregions.TEMPERATE_BROADLEAF, Ecoregions.NEARCTIC,
                false, false, false, forest, SurfaceGenerator.Tree.OAK, 10));
        assertEquals(RegionalTrees.Species.TALL_BIRCH, EarthGenerator.chooseRegionalTree(
                ClimateZone.CONTINENTAL, 6, Ecoregions.TEMPERATE_BROADLEAF, Ecoregions.PALEARCTIC,
                false, false, false, birchForest, SurfaceGenerator.Tree.BIRCH, 10));

        // Vanilla specialties stay vanilla (null)
        assertNull(EarthGenerator.chooseRegionalTree(ClimateZone.RAINFOREST, 26, Ecoregions.MANGROVE, Ecoregions.NEOTROPIC,
                true, false, true, biomes.getHolderOrThrow(Biomes.MANGROVE_SWAMP), SurfaceGenerator.Tree.MANGROVE, 0));
        assertNull(EarthGenerator.chooseRegionalTree(ClimateZone.CONTINENTAL, 11, Ecoregions.TEMPERATE_BROADLEAF, Ecoregions.PALEARCTIC,
                false, false, false, biomes.getHolderOrThrow(Biomes.CHERRY_GROVE), SurfaceGenerator.Tree.CHERRY, 0));
        assertNull(EarthGenerator.chooseRegionalTree(ClimateZone.SUBTROPICAL, 19, Ecoregions.FLOODED_GRASSLAND, Ecoregions.NEARCTIC,
                false, false, true, swamp, SurfaceGenerator.Tree.OAK, 0));
    }
}
