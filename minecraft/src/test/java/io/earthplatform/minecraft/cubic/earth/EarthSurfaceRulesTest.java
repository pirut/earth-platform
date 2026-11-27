package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import javax.imageio.ImageIO;
import io.earthplatform.geography.globe.CanopySource;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.GroundPalette;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.gen.FarColumn;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Tree;
import java.time.Duration;
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
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EarthSurfaceRulesTest {
    private static Registry<Biome> biomes;
    private static HttpServer tileServer;
    private static TerrariumTileSource tiles;
    private static WorldCoverSource cover;
    private static EarthGenerator earth;

    @BeforeAll
    static void setup() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> r = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        var registries = VanillaRegistries.createLookup();
        var lookup = registries.lookupOrThrow(Registries.BIOME);
        lookup.listElements().forEach(h -> r.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        try (var in = EarthSurfaceRulesTest.class.getResourceAsStream("/data/earthplatform/worldgen/biome/glacial_lake.json")) {
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
            Biome glacial = Biome.DIRECT_CODEC.parse(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries), json).getOrThrow();
            r.register(ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("earthplatform", "glacial_lake")),
                    glacial, RegistrationInfo.BUILT_IN);
        }
        biomes = r.freeze();
        // Local loopback server returning a flat 256x256 Terrarium tile so cone/slope checks need no internet.
        BufferedImage flat = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        int rgb = 128 << 16; // 0 m in Terrarium encoding: (128*256 + 0 + 0/256) - 32768 = 0
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) flat.setRGB(x, y, rgb);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(flat, "png", png);
        byte[] bytes = png.toByteArray();
        tileServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tileServer.createContext("/tiles/", ex -> {
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        });
        tileServer.createContext("/cover/", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        tileServer.start();
        URI base = URI.create("http://127.0.0.1:" + tileServer.getAddress().getPort());
        tiles = new TerrariumTileSource(base.resolve("/tiles/"), null, true, 16, 2, Duration.ofSeconds(5));
        cover = new WorldCoverSource(base.resolve("/cover/"), null, true, 16, 2, Duration.ofSeconds(5));
        earth = new EarthGenerator(1234L, biomes, EarthSettings.at(0, 0), tiles, cover, null, null);
    }

    @AfterAll
    static void tearDown() {
        if (cover != null) cover.close();
        if (tiles != null) tiles.close();
        if (tileServer != null) tileServer.stop(0);
    }

    @Test
    void polarOceanSedimentAndPlantsFollowClimateWithoutAddingSouthernPolarBears() {
        int[] south = earth.block(-70, 0);
        for (int depth : new int[] {15, 60}) {
            Surface frozen = earth.oceanSurface(south[0], south[1], depth, -9);
            assertTrue(frozen.biome().is(depth == 15 ? Biomes.COLD_OCEAN : Biomes.DEEP_COLD_OCEAN));
            assertTrue(frozen.top().is(Blocks.GRAVEL));
            assertTrue(frozen.under().is(Blocks.GRAVEL));
            assertFalse(earth.underwaterPlants(frozen, south[0], south[1]),
                    "freezing southern water stays bare, including open channels between ice floes");
        }
        int[] north = earth.block(70, 0);
        Surface arctic = earth.oceanSurface(north[0], north[1], 15, -9);
        assertTrue(arctic.biome().is(Biomes.FROZEN_OCEAN));
        assertTrue(arctic.top().is(Blocks.GRAVEL));
        assertFalse(earth.underwaterPlants(arctic, north[0], north[1]));

        int[] temperate = earth.block(50, 0);
        Surface cold = earth.oceanSurface(temperate[0], temperate[1], 15, 3);
        assertTrue(cold.biome().is(Biomes.COLD_OCEAN));
        assertTrue(cold.top().is(Blocks.SAND));
        assertTrue(earth.underwaterPlants(cold, temperate[0], temperate[1]));
        assertFalse(earth.iced(cold, temperate[0], temperate[1]));
        assertTrue(earth.oceanSurface(south[0], south[1], 15, -2).top().is(Blocks.SAND),
                "the frozen-ocean threshold is strictly below -2 degrees");
        assertTrue(earth.oceanSurface(south[0], south[1], 15, -2.01).top().is(Blocks.GRAVEL));
    }

    @Test
    void montaneBareRockAbove3000mStaysSubnivalRockNotGreenGrass() {
        // Tian Shan above Almaty: MONTANE_GRASSLAND ecoregion (767).
        int[] at = earth.block(43.13, 77.08);
        int eco = Ecoregions.get().ecoregion(43.13, 77.08);
        assertEquals(Ecoregions.MONTANE_GRASSLAND, Ecoregions.get().biome(eco), "Almaty mountains are MONTANE_GRASSLAND");
        int y = earth.seaLevel() + 3400;
        var subnival = new EarthGenerator.Local(ClimateZone.TUNDRA, -4.0, 3.0, 500, ClimateSource.ET);

        Surface bare = earth.covered(WorldCoverSource.BARE, at[0], at[1], y, subnival, 0.3, 50, 1, 1);
        assertNotNull(bare);
        assertTrue(bare.biome().is(Biomes.STONY_PEAKS), "subnival BARE should be stony_peaks, got " + bare.biome());
        assertFalse(bare.top().is(Blocks.GRASS_BLOCK), "subnival BARE must not become green grass");

        Surface coldGrass = earth.covered(WorldCoverSource.GRASSLAND, at[0], at[1], y,
                new EarthGenerator.Local(ClimateZone.TUNDRA, -6.0, 1.0, 500, ClimateSource.ET), 0.2, 50, 1, 1);
        assertNotNull(coldGrass);
        assertTrue(coldGrass.biome().is(Biomes.SNOWY_PLAINS), "cold subnival grass should snow, not revert to windswept_hills");

        // Warm tropical/Andean puna above 3,000 m keeps its windswept alpine tussock grass.
        int[] altiplano = earth.block(-16.5, -68.1);
        var puna = new EarthGenerator.Local(ClimateZone.TUNDRA, 4.0, 8.0, 400, ClimateSource.ET);
        Surface tussock = earth.covered(WorldCoverSource.GRASSLAND, altiplano[0], altiplano[1], earth.seaLevel() + 3600, puna, 0.2, 50, 1, 1);
        assertNotNull(tussock);
        assertTrue(tussock.biome().is(Biomes.WINDSWEPT_HILLS), "warm puna grassland above 3000m keeps windswept_hills");
    }

    @Test
    void midLatitudeAlpineMeadowsWithWarmSummersAvoidInvertedSnowBand() {
        int[] almaty = earth.block(43.13, 77.08);
        int y = earth.seaLevel() + 2900;
        var warmSummerAlpine = new EarthGenerator.Local(ClimateZone.TUNDRA, -3.5, 6.0, 550, ClimateSource.ET);

        Surface meadow = earth.covered(WorldCoverSource.GRASSLAND, almaty[0], almaty[1], y, warmSummerAlpine, 0.2, 50, 1, 1);
        assertNotNull(meadow);
        assertTrue(meadow.biome().is(Biomes.MEADOW), "mid-latitude alpine meadow with warm summer should stay meadow, got " + meadow.biome());

        Surface shrub = earth.covered(WorldCoverSource.SHRUBLAND, almaty[0], almaty[1], y, warmSummerAlpine, 0.2, 50, 1, 1);
        assertNotNull(shrub);
        assertTrue(shrub.biome().is(Biomes.MEADOW), "mid-latitude alpine shrubland with warm summer should stay meadow, got " + shrub.biome());

        // High-latitude arctic tundra still lies under snow.
        int[] arctic = earth.block(68.0, 20.0);
        Surface arcticTundra = earth.covered(WorldCoverSource.GRASSLAND, arctic[0], arctic[1], earth.seaLevel() + 400, warmSummerAlpine, 0.2, 50, 1, 1);
        assertNotNull(arcticTundra);
        assertTrue(arcticTundra.biome().is(Biomes.SNOWY_PLAINS), "arctic tundra above 55 deg should stay snowy");
    }

    @Test
    void imageryGroundKeepsDryBushveldEarthyAndPreservesTrees() {
        ImageryGround imagery = new ImageryGround(null, new MercatorGrid(1), 42L);
        var savanna = biomes.getHolderOrThrow(Biomes.SAVANNA);
        var desert = biomes.getHolderOrThrow(Biomes.DESERT);
        // Dry-season warm tan/ochre satellite pixel (low greenness, high lightness, non-grey).
        GroundPalette.Reading dryTan = GroundPalette.read(new double[]{
                linear(210), linear(175), linear(125)});
        assertTrue(dryTan.vegetation() < 0.25 && dryTan.lightness() > 42 && !dryTan.grey());

        Surface grassBushveld = new Surface(savanna, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                16, 0, 0, 320, 330, false, Tree.ACACIA);
        Surface dirtBushveld = new Surface(savanna, Blocks.COARSE_DIRT.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                6, 0, 0, 380, 390, false, Tree.ACACIA);

        boolean changedToLoam = false;
        for (int i = 0; i < 64; i++) {
            Surface afterGrass = imagery.apply(grassBushveld, WorldCoverSource.SHRUBLAND, i * 17, i * 31,
                    ClimateZone.HOT_STEPPE, 0.1, 0.9, dryTan);
            assertFalse(afterGrass.top().is(Blocks.SAND) || afterGrass.top().is(Blocks.RED_SAND),
                    "bushveld grassland/shrubland must not turn to desert sand: " + afterGrass.top());
            assertEquals(16, afterGrass.trees(), "dry-season bushveld imagery must not zero out trees");
            if (!afterGrass.top().is(Blocks.GRASS_BLOCK)) changedToLoam = true;

            Surface afterDirt = imagery.apply(dirtBushveld, WorldCoverSource.GRASSLAND, i * 17, i * 31,
                    ClimateZone.TROPICAL_DRY, 0.1, 0.9, dryTan);
            assertFalse(afterDirt.top().is(Blocks.SAND) || afterDirt.top().is(Blocks.RED_SAND),
                    "bushveld coarse dirt must not turn to desert sand: " + afterDirt.top());
            assertEquals(6, afterDirt.trees(), "bushveld coarse dirt must keep its trees");
        }
        assertTrue(changedToLoam, "dry-season imagery should still show earthy loam patches");

        // True hot desert open ground still turns to sand and clears vegetation.
        Surface desertOpen = new Surface(desert, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                2, 0, 0, 200, 200, false);
        boolean desertTurnedSandy = false;
        for (int i = 0; i < 64; i++) {
            Surface after = imagery.apply(desertOpen, WorldCoverSource.SHRUBLAND, i * 17, i * 31,
                    ClimateZone.HOT_DESERT, 0.1, 0.9, dryTan);
            if (after.top().is(Blocks.SAND) || after.top().is(Blocks.RED_SAND)) {
                desertTurnedSandy = true;
                assertEquals(0, after.trees());
            }
        }
        assertTrue(desertTurnedSandy, "true hot desert should still match desert sand");
    }

    @Test
    void bushveldCarriesModestAcaciaDensityWhileDesertStaysTreeless() {
        // Limpopo bushveld, South Africa: TROPICAL_GRASSLAND ecoregion.
        int[] limpopo = earth.block(-23.9, 29.4);
        int eco = Ecoregions.get().ecoregion(-23.9, 29.4);
        assertEquals(Ecoregions.TROPICAL_GRASSLAND, Ecoregions.get().biome(eco), "Limpopo is TROPICAL_GRASSLAND");
        int y = earth.seaLevel() + 1000;
        var steppe = new EarthGenerator.Local(ClimateZone.HOT_STEPPE, 21.0, 26.0, 480, ClimateSource.BSH);

        int maxShrubTrees = 0;
        for (int dx = 0; dx < 32; dx++) {
            Surface shrub = earth.covered(WorldCoverSource.SHRUBLAND, limpopo[0] + dx * 5, limpopo[1], y, steppe, 0.1, 50, 1, 1);
            if (shrub != null) maxShrubTrees = Math.max(maxShrubTrees, shrub.trees());
        }
        assertTrue(maxShrubTrees >= 12, "bushveld shrubland should have modest acacia density, got " + maxShrubTrees);

        Surface grass = earth.covered(WorldCoverSource.GRASSLAND, limpopo[0], limpopo[1], y, steppe, 0.1, 50, 1, 1);
        assertNotNull(grass);
        assertTrue(grass.trees() >= 5, "bushveld grassland should carry scattered acacias, got " + grass.trees());
        assertEquals(Tree.ACACIA, grass.broadleaf());

        // True hot desert stays treeless.
        int[] sahara = earth.block(23.0, 10.0);
        var arid = new EarthGenerator.Local(ClimateZone.HOT_DESERT, 26.0, 35.0, 60, ClimateSource.BWH);
        Surface desertGrass = earth.covered(WorldCoverSource.GRASSLAND, sahara[0], sahara[1], earth.seaLevel() + 500, arid, 0.1, 50, 1, 1);
        assertNotNull(desertGrass);
        assertEquals(0, desertGrass.trees(), "hot desert must stay treeless");
        assertNotEquals(0, grass.trees());
    }

    @Test
    void arcticColdPeatlandDoesNotAssignUnfrozenBogOrSwamp() {
        // Canadian Low Arctic tundra (issue #268): flat terrain, TUNDRA ecoregion, freezing mean annual temp.
        int[] arcticBasin = earth.block(65.33279, -104.21770);
        int eco = Ecoregions.get().ecoregion(65.33279, -104.21770);
        assertEquals(Ecoregions.TUNDRA, Ecoregions.get().biome(eco), "65.33N, 104.22W is TUNDRA ecoregion");
        var lowArctic = new EarthGenerator.Local(ClimateZone.SUBARCTIC, -10.0, 9.5, 280, ClimateSource.ET);
        int y = earth.seaLevel() + 120;

        for (int code : new int[]{WorldCoverSource.MOSS_AND_LICHEN, WorldCoverSource.GRASSLAND,
                WorldCoverSource.SHRUBLAND, WorldCoverSource.WETLAND}) {
            Surface s = earth.covered(code, arcticBasin[0], arcticBasin[1], y, lowArctic, 0.02, 50, 0, 1);
            assertNotNull(s);
            assertFalse(s.biome().is(Biomes.SWAMP), "Arctic tundra basin must not become unfrozen swamp for cover " + code);
            assertTrue(s.biome().value().getBaseTemperature() < 0.15f,
                    "Arctic tundra basin should get a freezing snowy biome, got " + s.biome() + " for cover " + code);
        }

        // Non-tundra ecoregion below the 10 C treeline (warmest < 10) is also excluded from unfrozen peatland.
        int[] hudson = earth.block(53.5, -83.5);
        int hudsonEco = Ecoregions.get().ecoregion(53.5, -83.5);
        assertNotEquals(Ecoregions.TUNDRA, Ecoregions.get().biome(hudsonEco));
        var subTreeline = new EarthGenerator.Local(ClimateZone.SUBARCTIC, -4.5, 9.5, 500, ClimateSource.DFC);
        Surface belowTreeline = earth.covered(WorldCoverSource.WETLAND, hudson[0], hudson[1], earth.seaLevel() + 50, subTreeline, 0.02, 50, 0, 1);
        assertNotNull(belowTreeline);
        assertFalse(belowTreeline.biome().is(Biomes.SWAMP), "sub-treeline (warmest < 10 C) basin must not form peatland");

        // Cold boreal Hudson Bay Lowlands (SUBARCTIC, warmest >= 10 C, annual mean < -3 C) still forms peatland!
        var coldBoreal = new EarthGenerator.Local(ClimateZone.SUBARCTIC, -4.5, 12.0, 550, ClimateSource.DFC);
        Surface coldBorealBog = earth.covered(WorldCoverSource.WETLAND, hudson[0], hudson[1], earth.seaLevel() + 50, coldBoreal, 0.02, 50, 0, 1);
        assertNotNull(coldBorealBog);
        assertTrue(coldBorealBog.biome().is(Biomes.SWAMP), "flat cold boreal Hudson Bay wetland (warmest >= 10 C, mean < -3 C) must remain peatland");
    }

    @Test
    void subpolarOceanicCanopyNoDataZeroLowAndTallWithForestTypes() {
        // Southern Iceland (issues #280, #281) and non-volcanic subpolar moor (Shetland) + coastal Norway broadleaf.
        int[] iceland = earth.block(63.53, -19.51);
        int[] moorSpot = earth.block(60.20, -1.20);
        var subpolarMoor = new EarthGenerator.Local(ClimateZone.SUBPOLAR_OCEANIC, 4.5, 10.5, 1450, ClimateSource.CFC);
        var subpolarDryShrub = new EarthGenerator.Local(ClimateZone.SUBPOLAR_OCEANIC, 4.5, 10.5, 650, ClimateSource.CFC);
        int y = earth.seaLevel() + 30;

        // Pick a subpolar coordinate where ForestTypes has broadleaf data so typed() is exercised.
        EarthGenerator typedEarth = new EarthGenerator(1234L, biomes, EarthSettings.at(0, 0), tiles, cover, null, null);
        typedEarth.enableForestTypes();
        ForestTypes ft = ForestTypes.get();
        assertNotNull(ft, "bundled ForestTypes data must be present");
        int[] broadleafSpot = null;
        double[] mix = new double[2];
        typedEarth.setStandOverride(12.0);
        try {
            outer:
            for (double lat = 60.0; lat <= 70.0; lat += 0.25) {
                for (double lon = -24.0; lon <= 24.0; lon += 0.5) {
                    if (!ft.at(lat, lon, mix) || mix[0] >= 0.05) continue;
                    int[] cand = typedEarth.block(lat, lon);
                    Surface probe = typedEarth.covered(WorldCoverSource.TREE_COVER, cand[0], cand[1], y, subpolarMoor, 0.05, 50, 0, 1);
                    if (probe != null && probe.biome().is(Biomes.BIRCH_FOREST)) {
                        broadleafSpot = cand;
                        break outer;
                    }
                }
            }
        } finally {
            typedEarth.setStandOverride(null);
        }
        assertNotNull(broadleafSpot, "must find a subpolar broadleaf ForestTypes coordinate that resolves to BIRCH_FOREST");

        try {
            // 1. stand == -1 (no-data / missing canopy): preserve old fallback densities.
            earth.setStandOverride(-1.0);
            typedEarth.setStandOverride(-1.0);
            for (int code : new int[]{WorldCoverSource.MOSS_AND_LICHEN, WorldCoverSource.GRASSLAND, WorldCoverSource.SHRUBLAND}) {
                Surface s = earth.covered(code, moorSpot[0], moorSpot[1], y, subpolarMoor, 0.05, 50, 0, 1);
                assertNotNull(s);
                assertEquals(1, s.trees(), "unknown canopy (-1) in moor must preserve fallback density 1 for " + code);
            }
            // Find a GRASS-top dry SHRUBLAND column so slope/top don't zero trees.
            int shrubX = iceland[0];
            while (earth.covered(WorldCoverSource.SHRUBLAND, shrubX, iceland[1], y, subpolarDryShrub, 0.05, 50, 0, 1).top() != Blocks.GRASS_BLOCK.defaultBlockState()) shrubX++;
            assertTrue(earth.covered(WorldCoverSource.SHRUBLAND, shrubX, iceland[1], y, subpolarDryShrub, 0.05, 50, 0, 1).trees() > 0,
                    "unknown canopy (-1) in subpolar SHRUBLAND must preserve non-zero fallback density");
            assertEquals(70, earth.covered(WorldCoverSource.TREE_COVER, iceland[0], iceland[1], y, subpolarMoor, 0.05, 50, 0, 1).trees(),
                    "unknown canopy (-1) subpolar TREE_COVER must keep full density 70");
            assertEquals(70, typedEarth.covered(WorldCoverSource.TREE_COVER, broadleafSpot[0], broadleafSpot[1], y, subpolarMoor, 0.05, 50, 0, 1).trees(),
                    "unknown canopy (-1) typed subpolar TREE_COVER must keep density 70");

            // 2. stand == 0 (measured treeless): suppress moor, SHRUBLAND, and TREE_COVER (even with ForestTypes active!).
            earth.setStandOverride(0.0);
            typedEarth.setStandOverride(0.0);
            for (int code : new int[]{WorldCoverSource.MOSS_AND_LICHEN, WorldCoverSource.GRASSLAND, WorldCoverSource.SHRUBLAND}) {
                Surface s = earth.covered(code, moorSpot[0], moorSpot[1], y, subpolarMoor, 0.05, 50, 0, 1);
                assertEquals(0, s.trees(), "measured 0 m canopy must suppress trees in moor for " + code);
            }
            assertEquals(0, earth.covered(WorldCoverSource.SHRUBLAND, shrubX, iceland[1], y, subpolarDryShrub, 0.05, 50, 0, 1).trees(),
                    "measured 0 m canopy must suppress trees in subpolar SHRUBLAND");
            assertEquals(0, earth.covered(WorldCoverSource.TREE_COVER, iceland[0], iceland[1], y, subpolarMoor, 0.05, 50, 0, 1).trees(),
                    "measured 0 m canopy must suppress trees in subpolar TREE_COVER");
            assertEquals(0, typedEarth.covered(WorldCoverSource.TREE_COVER, broadleafSpot[0], broadleafSpot[1], y, subpolarMoor, 0.05, 50, 0, 1).trees(),
                    "measured 0 m canopy must suppress subpolar TREE_COVER even with ForestTypes active");

            // 3. stand == 4 (measured low canopy): moor & shrub allow 1, TREE_COVER thins to 18 (even with ForestTypes active!).
            earth.setStandOverride(4.0);
            typedEarth.setStandOverride(4.0);
            assertEquals(1, earth.covered(WorldCoverSource.MOSS_AND_LICHEN, moorSpot[0], moorSpot[1], y, subpolarMoor, 0.05, 50, 0, 1).trees());
            assertTrue(earth.covered(WorldCoverSource.SHRUBLAND, shrubX, iceland[1], y, subpolarDryShrub, 0.05, 50, 0, 1).trees() > 0);
            assertEquals(18, earth.covered(WorldCoverSource.TREE_COVER, iceland[0], iceland[1], y, subpolarMoor, 0.05, 50, 0, 1).trees());
            assertEquals(18, typedEarth.covered(WorldCoverSource.TREE_COVER, broadleafSpot[0], broadleafSpot[1], y, subpolarMoor, 0.05, 50, 0, 1).trees(),
                    "low 4 m subpolar TREE_COVER must stay sparse (18) even when ForestTypes marks broadleaf");

            // 4. stand == 12 (tall subpolar forest, e.g. Magellanic Nothofagus / coastal Norway): full density 70.
            earth.setStandOverride(12.0);
            typedEarth.setStandOverride(12.0);
            Surface tallUntyped = earth.covered(WorldCoverSource.TREE_COVER, iceland[0], iceland[1], y, subpolarMoor, 0.05, 50, 0, 1);
            assertEquals(70, tallUntyped.trees(), "tall 12 m subpolar TREE_COVER must keep full density 70");
            assertEquals(110, tallUntyped.grass(), "tall 12 m subpolar TREE_COVER must keep original grass density");
            Surface tallTyped = typedEarth.covered(WorldCoverSource.TREE_COVER, broadleafSpot[0], broadleafSpot[1], y, subpolarMoor, 0.05, 50, 0, 1);
            assertEquals(70, tallTyped.trees(), "tall 12 m typed subpolar TREE_COVER must keep full density 70");
            assertTrue(tallTyped.biome().is(Biomes.BIRCH_FOREST),
                    "tall 12 m typed subpolar TREE_COVER at broadleafSpot must return BIRCH_FOREST, got " + tallTyped.biome());
        } finally {
            earth.setStandOverride(null);
            typedEarth.setStandOverride(null);
        }

        // Cool oceanic sea-level grassland with lower rainfall must not falsely match subalpine(-1) firClearing.
        var coolDryLowland = new EarthGenerator.Local(ClimateZone.OCEANIC, 4.5, 11.0, 650, ClimateSource.CFB);
        Surface seaLevelGrass = earth.covered(WorldCoverSource.GRASSLAND, iceland[0], iceland[1], earth.seaLevel() + 20, coolDryLowland, 0.05, 50, 0, 1);
        assertNotNull(seaLevelGrass);
        assertTrue(seaLevelGrass.trees() <= 1, "sea-level cool oceanic grassland must not spawn subalpine firClearing, got trees=" + seaLevelGrass.trees());

        // High-altitude subalpine grassland (>1000 m above sea level) still gets subalpine conifer clearing.
        int[] alps = earth.block(46.6, 8.0);
        Surface highSubalpine = earth.covered(WorldCoverSource.GRASSLAND, alps[0], alps[1], earth.seaLevel() + 1400, coolDryLowland, 0.05, 50, 0, 1);
        assertNotNull(highSubalpine);
        assertEquals(12, highSubalpine.trees(), "high-altitude subalpine grassland should keep conifer clearing");
    }

    @Test
    void offshoreNoDataSpeckSurroundedBySeaStaysTreelessShore() throws Exception {
        // Ocean tiles (-5 m everywhere: (127*256 + 251) - 32768 = -5) and NO_DATA land cover (404).
        BufferedImage oceanImg = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        int oceanRgb = (127 << 16) | (251 << 8);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) oceanImg.setRGB(x, y, oceanRgb);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(oceanImg, "png", png);
        byte[] oceanBytes = png.toByteArray();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tiles/", ex -> {
            ex.sendResponseHeaders(200, oceanBytes.length);
            try (var out = ex.getResponseBody()) { out.write(oceanBytes); }
        });
        server.createContext("/cover/", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (var oceanTiles = new TerrariumTileSource(base.resolve("/tiles/"), null, true, 16, 2, Duration.ofSeconds(5));
             var noCover = new WorldCoverSource(base.resolve("/cover/"), null, true, 16, 2, Duration.ofSeconds(5))) {
            // Tropical coordinates (NE Guam, issue #246) where typical(RAINFOREST) would otherwise be TREE_COVER (trees = 90).
            EarthGenerator oceanEarth = new EarthGenerator(42L, biomes, EarthSettings.at(13.48916, 144.89254), oceanTiles, noCover, null, null);
            int[] pos = oceanEarth.block(13.48916, 144.89254);
            // Dry speck at SEA + 3 (above the SEA + 2 beach strip) with NO_DATA cover surrounded by sea.
            Surface speck = oceanEarth.surface(pos[0], pos[1], oceanEarth.seaLevel() + 3, EarthGenerator.DRY, 0, 1, null);
            assertNotNull(speck);
            assertEquals(0, speck.trees(), "isolated dry NO_DATA speck in tropical sea must not fall back to TREE_COVER");
            assertTrue(speck.biome().is(Biomes.BEACH) || speck.biome().is(Biomes.STONY_SHORE),
                    "isolated offshore speck should be treeless shore, got " + speck.biome());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void marshyMangroveDeltaDryWaterAndNoDataPixelsAndOffshoreSpecks() throws Exception {
        // 1. Sundarbans mangrove estuary (Ecoregions.MANGROVE): sea at dx <= -2 (-5 m), flat estuary land at dx > -2 (+1 m).
        // Both a genuine shoreline dry WATER pixel (where coverAt() warp overlaps the shore) and a genuine shoreline
        // NO_DATA pixel (with cover == NO_DATA, y <= SEA + 2, nearSea() == true, and !seaSurrounded()) must be exempted
        // from the early beach decision and coastal beach fallback, staying mangrove/marsh instead of beach sand!
        double sundarbansLat = 22.0, sundarbansLon = 89.2;
        try (var world = new EarthShorelineTest.SyntheticWorld(biomes, sundarbansLat, sundarbansLon,
                (dx, dz) -> dx <= -2 ? -5.0 : 1.0,
                (dx, dz) -> dx <= -2 ? WorldCoverSource.WATER : WorldCoverSource.MANGROVES)) {
            EarthGenerator gen = world.generator;
            int waterX = Integer.MIN_VALUE, waterZ = Integer.MIN_VALUE;
            for (int dz = 0; dz <= 128 && waterX == Integer.MIN_VALUE; dz++) {
                int x = world.homeX, z = world.homeZ + dz;
                if (gen.cover(x, z, 1) == WorldCoverSource.WATER && gen.nearSea(x, z, 1) && !gen.seaSurrounded(x, z)) {
                    waterX = x;
                    waterZ = z;
                }
            }
            assertNotEquals(Integer.MIN_VALUE, waterX,
                    "mangrove shoreline fixture must find a genuine dry WATER column with nearSea() && !seaSurrounded()");
            for (int y : new int[]{gen.seaLevel() + 1, gen.seaLevel() + 3}) {
                Surface s = gen.surface(waterX, waterZ, y, EarthGenerator.DRY, 0, 1, null);
                assertNotNull(s);
                assertFalse(s.biome().is(Biomes.BEACH), "mangrove delta dry WATER must not become BEACH at y=" + y);
                assertNotEquals(Blocks.SAND.defaultBlockState(), s.top(), "mangrove delta dry WATER must not get beach sand at y=" + y);
                assertTrue(s.biome().is(Biomes.MANGROVE_SWAMP) || s.biome().is(Biomes.SWAMP),
                        "mangrove delta dry WATER should stay mangrove/marsh, got " + s.biome());
            }
        }
        try (var world = new EarthShorelineTest.SyntheticWorld(biomes, sundarbansLat, sundarbansLon,
                (dx, dz) -> dx <= -2 ? -5.0 : 1.0,
                (dx, dz) -> (Math.abs(dx) <= 24 && Math.abs(dz) <= 24) ? WorldCoverSource.NO_DATA
                        : (dx <= -2 ? WorldCoverSource.WATER : WorldCoverSource.MANGROVES))) {
            EarthGenerator gen = world.generator;
            assertEquals(WorldCoverSource.NO_DATA, gen.cover(world.homeX, world.homeZ, 1),
                    "estuary NO_DATA fixture center must genuinely sample NO_DATA");
            assertTrue(gen.nearSea(world.homeX, world.homeZ, 1),
                    "estuary NO_DATA fixture center must have nearSea() == true to exercise the early beach decision");
            assertFalse(gen.seaSurrounded(world.homeX, world.homeZ),
                    "estuary NO_DATA fixture center on flat shoreline must not be seaSurrounded()");
            for (int y : new int[]{gen.seaLevel() + 1, gen.seaLevel() + 3}) {
                Surface s = gen.surface(world.homeX, world.homeZ, y, EarthGenerator.DRY, 0, 1, null);
                assertNotNull(s);
                assertFalse(s.biome().is(Biomes.BEACH), "mangrove delta dry NO_DATA must not become BEACH at y=" + y);
                assertNotEquals(Blocks.SAND.defaultBlockState(), s.top(), "mangrove delta dry NO_DATA must not get beach sand at y=" + y);
                assertTrue(s.biome().is(Biomes.MANGROVE_SWAMP) || s.biome().is(Biomes.SWAMP),
                        "mangrove delta dry NO_DATA should stay mangrove/marsh, got " + s.biome());
            }
        }

        // 2. Exposed sea-surrounded offshore speck with a MANGROVES/WETLAND sample 8 blocks away must stay beach sand (not marsh/mud).
        for (int centerCover : new int[]{WorldCoverSource.WATER, WorldCoverSource.NO_DATA}) {
            try (var world = new EarthShorelineTest.SyntheticWorld(biomes, sundarbansLat, sundarbansLon,
                    (dx, dz) -> (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) || (dx >= 7 && dx <= 12 && Math.abs(dz) <= 3) ? 1.0 : -5.0,
                    (dx, dz) -> (dx >= 7 && dx <= 12 && Math.abs(dz) <= 3) ? WorldCoverSource.MANGROVES
                            : (Math.abs(dx) <= 2 && Math.abs(dz) <= 2 ? centerCover : WorldCoverSource.WATER))) {
                EarthGenerator gen = world.generator;
                for (int y : new int[]{gen.seaLevel() + 1, gen.seaLevel() + 3}) {
                    Surface s = gen.surface(world.homeX, world.homeZ, y, EarthGenerator.DRY, 0, 1, null);
                    assertNotNull(s);
                    assertTrue(s.biome().is(Biomes.BEACH) || s.biome().is(Biomes.STONY_SHORE),
                            "sea-surrounded offshore speck with marsh 8 blocks away must stay beach, got " + s.biome() + " (cover=" + centerCover + ", y=" + y + ")");
                    assertEquals(0, s.trees(), "sea-surrounded offshore speck must stay treeless");
                }
            }
        }

        // 3. Coarse far LOD (voxel >= SHORE_VOXEL = 64) dry WATER pixel inside a marsh/mangrove estuary must NOT trigger
        // fine-resolution seaSurrounded() (zero Terrarium elevation tile requests).
        try (var world = new EarthShorelineTest.SyntheticWorld(biomes, sundarbansLat, sundarbansLon,
                (dx, dz) -> Math.abs(dx) <= 2 && Math.abs(dz) <= 2 ? 1.0 : -5.0,
                (dx, dz) -> WorldCoverSource.WATER)) {
            EarthGenerator gen = world.generator;
            Surface coarse = gen.surface(world.homeX, world.homeZ, gen.seaLevel() + 1, EarthGenerator.DRY, 0, 64, null);
            assertNotNull(coarse);
            assertEquals(0, world.terrariumRequests.get(),
                    "coarse far LOD (voxel=64) dry WATER pixel must not trigger fine-resolution seaSurrounded/waterY elevation fetches");
            assertTrue(coarse.biome().is(Biomes.MANGROVE_SWAMP) || coarse.biome().is(Biomes.SWAMP),
                    "coarse far LOD mangrove estuary pixel should keep marshyFallback without calling seaSurrounded, got " + coarse.biome());
        }
    }

    @Test
    void quickPreviewAndCoarseFarLodSkipCanopyFetchesWhileVoxel8StillSuppressesZeroCanopy() throws Exception {
        java.util.concurrent.atomic.AtomicInteger canopyRequests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer canopyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        canopyServer.createContext("/canopy/", ex -> {
            canopyRequests.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        canopyServer.start();
        URI canopyBase = URI.create("http://127.0.0.1:" + canopyServer.getAddress().getPort() + "/canopy/");
        try (var canopySource = new CanopySource(canopyBase, null, true, 16, 2, Duration.ofSeconds(5))) {
            EarthGenerator gen = new EarthGenerator(1234L, biomes, EarthSettings.at(0, 0), tiles, cover, null, null);
            gen.data(canopySource, false, false);
            int[] iceland = gen.block(64.5, -18.5);
            int y = gen.seaLevel() + 100;
            var subpolarMoor = new EarthGenerator.Local(ClimateZone.SUBPOLAR_OCEANIC, 3.0, 9.5, 1200, ClimateSource.CFC);

            // Coarse far LOD (voxel > 8) must return -1 and never query CanopySource.
            assertEquals(-1, gen.farCanopy(iceland[0], iceland[1], 16), "voxel=16 must skip canopy lookup");
            assertEquals(-1, gen.farCanopy(iceland[0], iceland[1], 64), "voxel=64 must skip canopy lookup");
            assertEquals(70, gen.covered(WorldCoverSource.TREE_COVER, iceland[0], iceland[1], y, subpolarMoor, 0.05, 50, 0, 16).trees(),
                    "voxel=16 subpolar TREE_COVER must use -1 fallback without querying canopy");
            assertEquals(1, gen.covered(WorldCoverSource.MOSS_AND_LICHEN, iceland[0], iceland[1], y, subpolarMoor, 0.05, 50, 0, 32).trees(),
                    "voxel=32 subpolar moor must use -1 fallback without querying canopy");
            assertEquals(0, canopyRequests.get(), "coarse far LOD (voxel > 8) must issue zero CanopySource requests");

            // quick() preview must return -1 even at voxel <= 8 and never query CanopySource.
            gen.previewing(5);
            try {
                assertEquals(-1, gen.canopy(iceland[0], iceland[1]), "quick() preview must skip fine canopy lookup");
                assertEquals(-1, gen.farCanopy(iceland[0], iceland[1], 8), "quick() preview must skip voxel=8 canopy lookup");
                assertEquals(70, gen.covered(WorldCoverSource.TREE_COVER, iceland[0], iceland[1], y, subpolarMoor, 0.05, 50, 0, 8).trees(),
                        "quick() preview subpolar TREE_COVER must use -1 fallback without querying canopy");
            } finally {
                gen.previewing(0);
            }
            assertEquals(0, canopyRequests.get(), "quick() preview must issue zero CanopySource requests");

            // voxel == 8 outside quick() still honours measured 0 m canopy (level-3 seam alignment), while voxel > 8 still returns -1.
            gen.setStandOverride(0.0);
            try {
                assertEquals(0, gen.farCanopy(iceland[0], iceland[1], 8), "voxel=8 must honour 0 m canopy outside quick()");
                assertEquals(0, gen.covered(WorldCoverSource.TREE_COVER, iceland[0], iceland[1], y, subpolarMoor, 0.05, 50, 0, 8).trees(),
                        "voxel=8 with 0 m canopy must suppress subpolar TREE_COVER");
                assertEquals(-1, gen.farCanopy(iceland[0], iceland[1], 16), "voxel=16 must still skip canopy even when standOverride is set");
            } finally {
                gen.setStandOverride(null);
            }
        } finally {
            canopyServer.stop(0);
        }
    }

    @Test
    void highElevationInlandNoDataInFloodedGrasslandKeepsRockAndClimateSurface() throws Exception {
        // Steep synthetic Pantanal upland (Ecoregions.FLOODED_GRASSLAND) at y = SEA + 200 with NO_DATA cover
        // must use the normal rock/climate fallback rather than marshyFallback() swamp mud.
        double pantanalLat = -17.5, pantanalLon = -57.0;
        assertEquals(Ecoregions.FLOODED_GRASSLAND, Ecoregions.get().biome(Ecoregions.get().ecoregion(pantanalLat, pantanalLon)),
                "fixture coordinate must lie in FLOODED_GRASSLAND (Pantanal)");
        try (var world = new EarthShorelineTest.SyntheticWorld(biomes, pantanalLat, pantanalLon,
                (dx, dz) -> 200.0 + dx * 2.0,
                (dx, dz) -> WorldCoverSource.NO_DATA)) {
            EarthGenerator gen = world.generator;
            assertEquals(WorldCoverSource.NO_DATA, gen.cover(world.homeX, world.homeZ, 1),
                    "Pantanal upland fixture must sample NO_DATA cover");
            Surface s = gen.surface(world.homeX, world.homeZ, gen.seaLevel() + 200, EarthGenerator.DRY, 6, 1, null);
            assertNotNull(s);
            assertFalse(s.biome().is(Biomes.SWAMP) || s.biome().is(Biomes.MANGROVE_SWAMP),
                    "high-elevation inland NO_DATA in Pantanal must not turn into swamp/mangrove, got " + s.biome());
            assertNotEquals(Blocks.MUD.defaultBlockState(), s.top(),
                    "steep high-elevation inland NO_DATA in Pantanal must not get swamp mud");
            assertTrue(s.top().is(Blocks.STONE) || s.top().is(Blocks.ANDESITE) || s.top().is(Blocks.DIORITE) || s.top().is(Blocks.GRAVEL),
                    "steep high-elevation inland NO_DATA in Pantanal should keep rock surface, got " + s.top());
        }
    }

    @Test
    void coniferQuickPreviewAndCoarseFarRenderSkipCanopyHttpRequests() throws Exception {
        // Cold-cache conifer country (Sierra Nevada, Ecoregions.TEMPERATE_CONIFER):
        // both a quick() preview and a v = 16 (level 4) farColumn render must issue ZERO CanopySource HTTP requests
        // across sequoias(), oldGrowth(), standHeight(), farCanopy(), and farGiants().
        double sierraLat = 36.56, sierraLon = -118.75;
        assertEquals(Ecoregions.TEMPERATE_CONIFER, Ecoregions.get().biome(Ecoregions.get().ecoregion(sierraLat, sierraLon)),
                "fixture coordinate must lie in TEMPERATE_CONIFER (Sierra Nevada)");
        java.util.concurrent.atomic.AtomicInteger canopyRequests = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer canopyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        canopyServer.createContext("/canopy/", ex -> {
            canopyRequests.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        canopyServer.start();
        URI canopyBase = URI.create("http://127.0.0.1:" + canopyServer.getAddress().getPort() + "/canopy/");
        try (var world = new EarthShorelineTest.SyntheticWorld(biomes, sierraLat, sierraLon,
                (dx, dz) -> 1500.0,
                (dx, dz) -> WorldCoverSource.TREE_COVER);
             var canopySource = new CanopySource(canopyBase, null, true, 16, 2, Duration.ofSeconds(5))) {
            EarthGenerator gen = world.generator;
            gen.data(canopySource, false, false);

            // 1. Cold-cache quick() preview at level 2 (v = 4) in conifer country.
            int previewColX = Math.floorDiv(world.homeX, 16 * 4);
            int previewColZ = Math.floorDiv(world.homeZ, 16 * 4);
            gen.farPreview(2, previewColX, previewColZ, new FarColumn());
            assertEquals(0, canopyRequests.get(),
                    "cold-cache conifer quick() preview must issue zero CanopySource HTTP requests");

            // 2. Direct giants() during quick() preview must also stay silent.
            gen.previewing(2);
            try {
                assertFalse(gen.giants(world.homeX, world.homeZ), "giants() must return false during quick() preview");
            } finally {
                gen.previewing(0);
            }
            assertEquals(0, canopyRequests.get(),
                    "giants() during quick() preview must issue zero CanopySource HTTP requests");

            // 3. Real v = 16 (level 4) far render path (farSurface + farCanopy + SurfaceGenerator farGiants).
            int farColX = Math.floorDiv(world.homeX, 16 * 16);
            int farColZ = Math.floorDiv(world.homeZ, 16 * 16);
            gen.farColumn(4, farColX, farColZ, new FarColumn());
            assertEquals(0, canopyRequests.get(),
                    "cold-cache conifer v = 16 (level 4) farColumn render must issue zero CanopySource HTTP requests");

            // 4. Positive control: outside quick() at voxel == 8, conifer country does query CanopySource.
            gen.farGiants(world.homeX, world.homeZ, 8);
            assertTrue(canopyRequests.get() > 0,
                    "positive control: fine/voxel=8 conifer lookup outside quick() must reach CanopySource");
        } finally {
            canopyServer.stop(0);
        }
    }

    @Test
    void coldLatitudeFallbackGrowsNoPalmsAndTropicsStill() {
        // No climate data here, so local() falls back to the latitude-derived temperature.
        var beach = biomes.getHolderOrThrow(Biomes.BEACH);
        var sand = Blocks.SAND.defaultBlockState();
        var shore = new Surface(beach, sand, sand, 40, 0, 0, 0, 0, false, Tree.JUNGLE);
        int[] iceland = earth.block(64.1, -21.9);
        int[] tropics = earth.block(5.0, 0.0);
        int y = earth.seaLevel() + 2;
        assertFalse(earth.palmsGrow(iceland[0], iceland[1], y), "Iceland's latitude is too cold for any palm");
        assertTrue(earth.palmsGrow(tropics[0], tropics[1], y), "the tropics grow palms");
        for (long roll = 0; roll < 400; roll++) {
            long r = roll << 36;
            assertNotEquals(io.earthplatform.minecraft.cubic.gen.RegionalTrees.Species.PALM,
                    earth.regionalTree(iceland[0], iceland[1], y, shore, Tree.JUNGLE, r), "Iceland beach, roll " + roll);
        }
    }

    private static double linear(int srgb) {
        double c = srgb / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
