package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.GroundPalette;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import javax.imageio.ImageIO;
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
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A world pinned without coarse dirt gets none from its surface rules or its imagery ground; the default is unchanged. */
class CoarseDirtToggleTest {
    private static final BlockState COARSE = Blocks.COARSE_DIRT.defaultBlockState(), GRASS = Blocks.GRASS_BLOCK.defaultBlockState(),
            DIRT = Blocks.DIRT.defaultBlockState();
    private static Registry<Biome> biomes;
    private static HttpServer server;
    private static TerrariumTileSource tiles;
    private static WorldCoverSource cover;
    private static EarthGenerator on, off;

    @BeforeAll static void setup() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> r = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        var registries = VanillaRegistries.createLookup();
        registries.lookupOrThrow(Registries.BIOME).listElements().forEach(h -> r.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        try (var in = CoarseDirtToggleTest.class.getResourceAsStream("/data/earthplatform/worldgen/biome/glacial_lake.json")) {
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
            Biome glacial = Biome.DIRECT_CODEC.parse(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries), json).getOrThrow();
            r.register(ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("earthplatform", "glacial_lake")), glacial, RegistrationInfo.BUILT_IN);
        }
        biomes = r.freeze();
        BufferedImage flat = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) flat.setRGB(x, y, 128 << 16);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(flat, "png", png);
        byte[] bytes = png.toByteArray();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tiles/", ex -> {
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/cover/", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        tiles = new TerrariumTileSource(base.resolve("/tiles/"), null, true, 16, 2, Duration.ofSeconds(5));
        cover = new WorldCoverSource(base.resolve("/cover/"), null, true, 16, 2, Duration.ofSeconds(5));
        on = new EarthGenerator(1234L, biomes, EarthSettings.at(0, 0), tiles, cover, null, null);
        off = new EarthGenerator(1234L, biomes, EarthSettings.at(0, 0), tiles, cover, null, null);
        off.disableCoarseDirt();
    }

    @AfterAll static void tearDown() {
        if (cover != null) cover.close();
        if (tiles != null) tiles.close();
        if (server != null) server.stop(0);
    }

    @Test void theSettingDefaultsToOnAndTurnsOffPerGenerator() {
        assertTrue(on.coarseDirt());
        assertFalse(off.coarseDirt());
        assertTrue(on.coarseDirt(), "turning one world's off leaves another's alone");
    }

    @Test void surfaceRulesMakeNoCoarseDirtWhenOffAndAreUnchangedWhenOn() {
        int[] codes = {WorldCoverSource.GRASSLAND, WorldCoverSource.SHRUBLAND, WorldCoverSource.CROPLAND, WorldCoverSource.TREE_COVER,
                WorldCoverSource.BARE, WorldCoverSource.WETLAND};
        int coarseWhenOn = 0, replaced = 0;
        for (ClimateZone zone : ClimateZone.values()) for (int code : codes) for (int i = 0; i < 40; i++) {
            int x = i * 977 - 15000, z = i * 1301 + 4000, y = on.seaLevel() + 40 + i * 3;
            var local = new EarthGenerator.Local(zone, 14.0, 22.0, 450, ClimateSource.ET);
            Surface raw = on.covered(code, x, z, y, local, 0.15 + (i % 5) * 0.2, i * 7 % 100, 1, 1);
            if (raw == null) continue;
            Surface kept = on.withoutCoarseDirt(raw);
            assertEquals(raw, kept, "with coarse dirt on, the surface is untouched");
            Surface plain = off.withoutCoarseDirt(raw);
            assertFalse(plain.top().is(Blocks.COARSE_DIRT), zone + " " + code + " still has coarse dirt");
            if (raw.top() == COARSE) {
                coarseWhenOn++;
                replaced++;
                assertTrue(plain.top().is(Blocks.GRASS_BLOCK) || plain.top().is(Blocks.DIRT) || plain.top().is(Blocks.SAND),
                        "coarse dirt becomes natural ground, got " + plain.top());
                assertEquals(raw.under(), plain.under());
                assertEquals(raw.trees(), plain.trees());
                assertEquals(raw.biome(), plain.biome());
            } else {
                assertEquals(raw, plain, "ground that was not coarse dirt is untouched");
            }
        }
        assertTrue(coarseWhenOn > 20, "the sweep must reach coarse dirt rules to prove anything: " + coarseWhenOn);
        assertEquals(coarseWhenOn, replaced);
    }

    @Test void coarseDirtOnDryLandBecomesDirtAndOnWetSoilBecomesGrass() {
        var savanna = biomes.getHolderOrThrow(Biomes.SAVANNA);
        var forest = biomes.getHolderOrThrow(Biomes.FOREST);
        var desert = biomes.getHolderOrThrow(Biomes.DESERT);
        Surface dry = new Surface(savanna, COARSE, DIRT, 6, 0, 0, 380, 390, false);
        Surface wet = new Surface(forest, COARSE, DIRT, 40, 0, 0, 100, 100, false);
        Surface sandy = new Surface(desert, COARSE, Blocks.SANDSTONE.defaultBlockState(), 0, 0, 0, 25, 25, false);
        assertEquals(DIRT, off.withoutCoarseDirt(dry).top());
        assertEquals(GRASS, off.withoutCoarseDirt(wet).top());
        assertEquals(Blocks.SAND.defaultBlockState(), off.withoutCoarseDirt(sandy).top());
    }

    @Test void imageryGroundPicksNoCoarseDirtWhenOff() {
        BlockState[] seen = new BlockState[1];
        ImageryGround normal = new ImageryGround(null, new MercatorGrid(1), 42L), plain = new ImageryGround(null, new MercatorGrid(1), 42L);
        plain.withoutCoarseDirt();
        var savanna = biomes.getHolderOrThrow(Biomes.SAVANNA);
        // A pixel the colour of coarse dirt itself, so the loam palette would pick it.
        GroundPalette.Reading coarseColoured = GroundPalette.read(new double[]{linear(125), linear(90), linear(63)});
        int coarseOn = 0, coarseOff = 0, dirtOff = 0;
        for (Surface start : new Surface[]{new Surface(savanna, COARSE, DIRT, 6, 0, 0, 380, 390, false),
                new Surface(savanna, Blocks.GRAVEL.defaultBlockState(), Blocks.STONE.defaultBlockState(), 0, 0, 0, 100, 100, false),
                new Surface(savanna, GRASS, DIRT, 6, 0, 0, 380, 390, false)})
            for (int i = 0; i < 200; i++) for (int code : new int[]{WorldCoverSource.SHRUBLAND, WorldCoverSource.GRASSLAND}) {
                Surface a = normal.apply(start, code, i * 53, i * 97, ClimateZone.HOT_STEPPE, 0.1, 0.5, coarseColoured);
                Surface b = plain.apply(start, code, i * 53, i * 97, ClimateZone.HOT_STEPPE, 0.1, 0.5, coarseColoured);
                if (a.top() == COARSE) coarseOn++;
                if (b.top() == COARSE) coarseOff++;
                if (b.top() == DIRT) dirtOff++;
                seen[0] = b.top();
            }
        assertTrue(coarseOn > 0, "by default imagery still picks coarse dirt for coarse-coloured ground");
        assertEquals(0, coarseOff, "off, imagery never picks it");
        assertTrue(dirtOff > 0, "off, plain dirt takes its place");
    }

    private static double linear(int srgb) {
        double c = srgb / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
