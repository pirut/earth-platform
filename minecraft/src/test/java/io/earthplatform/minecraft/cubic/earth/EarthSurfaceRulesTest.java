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
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.GroundPalette;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
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
        int[] broadleafSpot = iceland;
        ForestTypes ft = ForestTypes.get();
        if (ft != null) {
            double[] mix = new double[2];
            outer:
            for (double lat = 63.0; lat <= 69.0; lat += 0.5) {
                for (double lon = -22.0; lon <= 18.0; lon += 1.0) {
                    if (ft.at(lat, lon, mix) && mix[0] < 0.05) {
                        broadleafSpot = typedEarth.block(lat, lon);
                        break outer;
                    }
                }
            }
        }

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
            assertEquals(70, typedEarth.covered(WorldCoverSource.TREE_COVER, broadleafSpot[0], broadleafSpot[1], y, subpolarMoor, 0.05, 50, 0, 1).trees(),
                    "tall 12 m typed subpolar TREE_COVER must keep full density 70");
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
    void marshyMangroveDeltaDryWaterPixelNearSeaDoesNotBecomeBeachSand() throws Exception {
        // Sundarbans mangrove estuary (Ecoregions.MANGROVE): sea at dx < -10 (-5 m), flat estuary land at dx >= -10 (+1 m),
        // with a dry-DEM WATER creek pixel at (0, 0) surrounded by MANGROVES cover.
        double sundarbansLat = 22.0, sundarbansLon = 89.2;
        try (var world = new EarthShorelineTest.SyntheticWorld(biomes, sundarbansLat, sundarbansLon,
                (dx, dz) -> dx < -10 ? -5.0 : 1.0,
                (dx, dz) -> dx < -10 ? WorldCoverSource.WATER
                        : (Math.abs(dx) <= 4 && Math.abs(dz) <= 4 ? WorldCoverSource.WATER : WorldCoverSource.MANGROVES))) {
            EarthGenerator gen = world.generator;
            // Dry-DEM WATER pixel 10 blocks from sea (inside seaWithin(32)) at SEA + 1 and SEA + 3 must stay mangrove/marsh, NOT beach sand!
            for (int y : new int[]{gen.seaLevel() + 1, gen.seaLevel() + 3}) {
                Surface s = gen.surface(world.homeX, world.homeZ, y, EarthGenerator.DRY, 0, 1, null);
                assertNotNull(s);
                assertFalse(s.biome().is(Biomes.BEACH), "mangrove delta dry WATER pixel must not become BEACH at y=" + y);
                assertNotEquals(Blocks.SAND.defaultBlockState(), s.top(), "mangrove delta dry WATER pixel must not get beach sand at y=" + y);
                assertTrue(s.biome().is(Biomes.MANGROVE_SWAMP) || s.biome().is(Biomes.SWAMP),
                        "mangrove delta dry WATER pixel should stay mangrove/marsh, got " + s.biome());
            }
        }
    }

    private static double linear(int srgb) {
        double c = srgb / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
