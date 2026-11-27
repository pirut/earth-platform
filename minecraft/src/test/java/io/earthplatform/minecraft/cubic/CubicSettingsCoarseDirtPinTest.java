package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.globe.ClimateSource;
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

/** {@code earth.coarseDirt} is pinned only when it is off, and a world that never pinned it keeps coarse dirt. */
class CubicSettingsCoarseDirtPinTest {
    private static Registry<Biome> biomes;

    @BeforeAll
    static void bootBiomes() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder ->
                registry.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        try (var in = CubicSettingsCoarseDirtPinTest.class.getResourceAsStream("/data/earthplatform/worldgen/biome/glacial_lake.json")) {
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            Biome biome = Biome.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, lookup), json).getOrThrow();
            registry.register(ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("earthplatform", "glacial_lake")),
                    biome, RegistrationInfo.BUILT_IN);
        }
        biomes = registry;
    }

    @Test
    void absentPropertyKeepsCoarseDirtAndOnlyFalseTurnsItOff() {
        assertTrue(CubicSettings.coarseDirt(new Properties()), "no pin means coarse dirt, as every world made before had");
        Properties on = new Properties();
        on.setProperty("earth.coarseDirt", "true");
        assertTrue(CubicSettings.coarseDirt(on));
        Properties off = new Properties();
        off.setProperty("earth.coarseDirt", "false");
        assertFalse(CubicSettings.coarseDirt(off));
    }

    @Test
    void newWorldsPinCoarseDirtOnlyWhenItIsOffAndReopenWithTheSameChoice(@TempDir Path tempDir) throws Exception {
        String prevOffline = System.getProperty("earthplatform.earth.offline"), prevFlag = System.getProperty("earthplatform.earth.coarseDirt");
        System.setProperty("earthplatform.earth.offline", "true");
        CubicSettings.cacheFolderOverride = tempDir.resolve("cache");
        CubicSettings.resetForTests();
        try {
            System.clearProperty("earthplatform.earth.coarseDirt");
            Path normal = tempDir.resolve("normal");
            CubicSettings.pinEarth(normal, EarthSettings.defaults(), "Home");
            assertNull(read(normal).getProperty("earth.coarseDirt"), "a default world writes nothing, so its file is as it was");
            assertTrue(assertInstanceOf(EarthGenerator.class, CubicSettings.open(normal, 42L, biomes)).coarseDirt());

            System.setProperty("earthplatform.earth.coarseDirt", "false");
            Path plain = tempDir.resolve("plain");
            CubicSettings.pinEarth(plain, EarthSettings.defaults(), "Home");
            assertEquals("false", read(plain).getProperty("earth.coarseDirt"));
            assertFalse(assertInstanceOf(EarthGenerator.class, CubicSettings.open(plain, 42L, biomes)).coarseDirt());

            // The flag only matters when a world is made: a saved world keeps its choice whatever the flag says later.
            System.clearProperty("earthplatform.earth.coarseDirt");
            assertFalse(assertInstanceOf(EarthGenerator.class, CubicSettings.open(plain, 42L, biomes)).coarseDirt());
            System.setProperty("earthplatform.earth.coarseDirt", "false");
            assertTrue(assertInstanceOf(EarthGenerator.class, CubicSettings.open(normal, 42L, biomes)).coarseDirt(),
                    "turning the flag off later leaves an existing world as it was");

            // A world saved before the setting existed has no such property.
            Path legacy = tempDir.resolve("legacy");
            Files.createDirectories(legacy);
            Properties p = new Properties();
            EarthSettings d = EarthSettings.defaults();
            p.setProperty("generator", EarthGenerator.ID);
            p.setProperty("earth.latitude", Double.toString(d.latitude()));
            p.setProperty("earth.longitude", Double.toString(d.longitude()));
            p.setProperty("earth.metresPerBlock", Double.toString(d.metresPerBlock()));
            p.setProperty("earth.projection", d.projection());
            p.setProperty("earth.elevation", CubicSettings.legacyBaseline());
            p.setProperty("earth.cover", WorldCoverSource.DATASET_ID);
            p.setProperty("earth.climate", ClimateSource.DATASET_ID);
            try (Writer writer = Files.newBufferedWriter(legacy.resolve("generator.properties"), StandardCharsets.UTF_8)) { p.store(writer, "test"); }
            assertTrue(assertInstanceOf(EarthGenerator.class, CubicSettings.open(legacy, 42L, biomes)).coarseDirt());
        } finally {
            CubicSettings.resetForTests();
            CubicSettings.cacheFolderOverride = null;
            restore("earthplatform.earth.offline", prevOffline);
            restore("earthplatform.earth.coarseDirt", prevFlag);
        }
    }

    private static Properties read(Path folder) throws Exception {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(folder.resolve("generator.properties"), StandardCharsets.UTF_8)) { props.load(reader); }
        return props;
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
