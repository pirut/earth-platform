package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.RiverMap;
import io.earthplatform.geography.globe.SurfaceWaterSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies saved-world version pinning for both global-coordinate {@link RiverMap} joins
 * ({@code earth.rivers}) and terrain-affecting hydrology v2 rules ({@code earth.hydrology})
 * end-to-end through {@link CubicSettings#open}.
 */
class CubicSettingsRiverPinTest {
    private static Registry<Biome> biomes;

    @BeforeAll
    static void bootBiomes() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder ->
                registry.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        try (var in = CubicSettingsRiverPinTest.class.getResourceAsStream("/data/earthplatform/worldgen/biome/glacial_lake.json")) {
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            Biome biome = Biome.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, lookup), json).getOrThrow();
            registry.register(ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("earthplatform", "glacial_lake")),
                    biome, RegistrationInfo.BUILT_IN);
        }
        biomes = registry;
    }

    @Test
    void pinParsingDistinguishesV2LegacyAndInvalidPins() {
        assertEquals(RiverMap.V2_ID, CubicSettings.riversPin());
        assertTrue(CubicSettings.isGlobalRiverJoin(RiverMap.V2_ID), "v2 pin enables global join");
        assertFalse(CubicSettings.isGlobalRiverJoin(SurfaceWaterSource.DATASET_ID), "legacy v1 pin preserves window-local join");
        assertFalse(CubicSettings.isGlobalRiverJoin(""), "pre-river pin stays non-v2");
        assertThrows(IllegalStateException.class, () -> CubicSettings.isGlobalRiverJoin("unknown-rivers-v99"));

        assertEquals(EarthGenerator.HYDROLOGY_V2, CubicSettings.hydrologyPin());
        assertTrue(CubicSettings.isHydrologyV2(EarthGenerator.HYDROLOGY_V2), "v2 pin enables hydrology v2");
        assertFalse(CubicSettings.isHydrologyV2(""), "absent hydrology pin preserves legacy rules");
        assertFalse(CubicSettings.isHydrologyV2("v1"), "explicit v1 hydrology pin preserves legacy rules");
        assertThrows(IllegalStateException.class, () -> CubicSettings.isHydrologyV2("v99"));
    }

    @Test
    void openRoundTripsNewAndLegacyHydrologyAndRiverPins(@TempDir Path tempDir) throws Exception {
        String prevOffline = System.getProperty("earthplatform.earth.offline");
        System.setProperty("earthplatform.earth.offline", "true");
        CubicSettings.cacheFolderOverride = tempDir.resolve("cache");
        CubicSettings.resetForTests();
        try {
            // 1. New world pinned via pinEarth persists both v2 pins and reopens with hydrologyV2 + RiverMap v2.
            Path newWorld = tempDir.resolve("new-world");
            CubicSettings.pinEarth(newWorld, EarthSettings.defaults(), "Test Home");
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(newWorld.resolve("generator.properties"), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            assertEquals(RiverMap.V2_ID, props.getProperty("earth.rivers"), "newly pinned worlds must use RiverMap.V2_ID");
            assertEquals(EarthGenerator.HYDROLOGY_V2, props.getProperty("earth.hydrology"), "newly pinned worlds must pin earth.hydrology=v2");

            EarthGenerator openedNew = assertInstanceOf(EarthGenerator.class, CubicSettings.open(newWorld, 42L, biomes));
            assertTrue(openedNew.hydrologyV2(), "new world reopened via CubicSettings.open must enable hydrologyV2");
            assertNotNull(openedNew.rivers(), "new world must load RiverMap");
            assertTrue(openedNew.rivers().isGlobalJoin(), "new world reopened via CubicSettings.open must use RiverMap v2");

            // 2. Legacy world with legacyBaseline elevation, v1 rivers, and absent earth.hydrology stays on legacy rules + v1 rivers.
            Path legacyAbsent = tempDir.resolve("legacy-absent");
            writeEarthProperties(legacyAbsent, CubicSettings.legacyBaseline(), SurfaceWaterSource.DATASET_ID, null);
            EarthGenerator openedLegacyAbsent = assertInstanceOf(EarthGenerator.class, CubicSettings.open(legacyAbsent, 42L, biomes));
            assertFalse(openedLegacyAbsent.hydrologyV2(), "legacy world without earth.hydrology must stay on legacy hydrology");
            assertNotNull(openedLegacyAbsent.rivers());
            assertFalse(openedLegacyAbsent.rivers().isGlobalJoin(), "legacy world with v1 earth.rivers must stay on v1 RiverMap");

            // 3. Legacy world with explicit earth.hydrology=v1 stays on legacy rules + v1 rivers.
            Path legacyV1 = tempDir.resolve("legacy-v1");
            writeEarthProperties(legacyV1, CubicSettings.legacyBaseline(), SurfaceWaterSource.DATASET_ID, "v1");
            EarthGenerator openedLegacyV1 = assertInstanceOf(EarthGenerator.class, CubicSettings.open(legacyV1, 42L, biomes));
            assertFalse(openedLegacyV1.hydrologyV2(), "explicit v1 hydrology pin must stay on legacy hydrology");
            assertNotNull(openedLegacyV1.rivers());
            assertFalse(openedLegacyV1.rivers().isGlobalJoin());

            // 4. Pre-river legacy world (no earth.rivers, no earth.hydrology) stays on legacy rules with rivers == null.
            Path preRiver = tempDir.resolve("legacy-preriver");
            writeEarthProperties(preRiver, CubicSettings.legacyBaseline(), null, null);
            EarthGenerator openedPreRiver = assertInstanceOf(EarthGenerator.class, CubicSettings.open(preRiver, 42L, biomes));
            assertFalse(openedPreRiver.hydrologyV2(), "pre-river legacy world must stay on legacy hydrology");
            assertNull(openedPreRiver.rivers(), "pre-river legacy world must keep rivers == null");

            // 5. Unknown earth.hydrology pin fails fast on open.
            Path invalid = tempDir.resolve("invalid-hydrology");
            writeEarthProperties(invalid, CubicSettings.legacyBaseline(), SurfaceWaterSource.DATASET_ID, "v99");
            assertThrows(IllegalStateException.class, () -> CubicSettings.open(invalid, 42L, biomes));
        } finally {
            CubicSettings.resetForTests();
            CubicSettings.cacheFolderOverride = null;
            if (prevOffline == null) System.clearProperty("earthplatform.earth.offline");
            else System.setProperty("earthplatform.earth.offline", prevOffline);
        }
    }

    private static void writeEarthProperties(Path folder, String elevationPin, String riversPin, String hydrologyPin) throws Exception {
        Files.createDirectories(folder);
        Properties p = new Properties();
        EarthSettings d = EarthSettings.defaults();
        p.setProperty("generator", EarthGenerator.ID);
        p.setProperty("earth.latitude", Double.toString(d.latitude()));
        p.setProperty("earth.longitude", Double.toString(d.longitude()));
        p.setProperty("earth.metresPerBlock", Double.toString(d.metresPerBlock()));
        p.setProperty("earth.projection", d.projection());
        p.setProperty("earth.elevation", elevationPin);
        p.setProperty("earth.cover", WorldCoverSource.DATASET_ID);
        p.setProperty("earth.climate", ClimateSource.DATASET_ID);
        if (riversPin != null) p.setProperty("earth.rivers", riversPin);
        if (hydrologyPin != null) p.setProperty("earth.hydrology", hydrologyPin);
        try (Writer writer = Files.newBufferedWriter(folder.resolve("generator.properties"), StandardCharsets.UTF_8)) {
            p.store(writer, "test world");
        }
    }
}
