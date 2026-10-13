package io.earthplatform.minecraft.cubic.earth;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.ImagerySource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@code /cubes calibrate} without a game: the same satellite | generation | with imagery | gap
 * panels and report for a list of places, so ground tuning can be scored before a build reaches a
 * client.
 *
 * <p>Off unless asked for: {@code EARTH_CALIBRATE=<cache dir> EARTH_CALIBRATE_PLACES=<file>
 * EARTH_CALIBRATE_OUT=<dir> ./gradlew test --tests '*CalibrateBench'}, with optional
 * {@code EARTH_CALIBRATE_SIZE} (blocks square, default 2048). Each line of the places file is a
 * name, a latitude and a longitude. The world's home is the default one, as in a new world.
 */
class CalibrateBench {
    private static int[] colourMap(String name) throws java.io.IOException {
        // The client's resources sit beside the merged game jar the tests run on.
        String merged = System.getProperty("earthplatform.test.minecraftJar");
        try (var jar = new java.util.zip.ZipFile(merged.replace("-merged.jar", "-client-extra-aka-minecraft-resources.jar"))) {
            var entry = jar.getEntry("assets/minecraft/textures/colormap/" + name + ".png");
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(jar.getInputStream(entry));
            return image.getRGB(0, 0, 256, 256, null, 0, 256);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EARTH_CALIBRATE", matches = ".+")
    void calibrate() throws Exception {
        Path cache = Path.of(System.getenv("EARTH_CALIBRATE"));
        Path out = Path.of(System.getenv("EARTH_CALIBRATE_OUT"));
        int size = Integer.parseInt(System.getenv().getOrDefault("EARTH_CALIBRATE_SIZE", "2048"));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Grass and leaf tints come from the game's colour maps, which only a client loads.
        net.minecraft.world.level.GrassColor.init(colourMap("grass"));
        net.minecraft.world.level.FoliageColor.init(colourMap("foliage"));
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder ->
                biomes.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        // The mod's own biomes, from its data pack.
        for (String name : List.of("glacial_lake")) {
            try (var in = CalibrateBench.class.getResourceAsStream("/data/earthplatform/worldgen/biome/" + name + ".json")) {
                var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
                Biome biome = Biome.DIRECT_CODEC.parse(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lookup), json).getOrThrow();
                biomes.register(net.minecraft.resources.ResourceKey.create(Registries.BIOME, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("earthplatform", name)),
                        biome, RegistrationInfo.BUILT_IN);
            }
        }
        TerrariumTileSource tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache.resolve(TerrariumTileSource.DATASET_ID),
                true, 512, 6, Duration.ofSeconds(20));
        WorldCoverSource cover = new WorldCoverSource(WorldCoverSource.AWS_WORLDCOVER, cache.resolve(WorldCoverSource.DATASET_ID), true, 96, 4, Duration.ofSeconds(30));
        ClimateSource climate = new ClimateSource(ClimateSource.CHELSA, cache.resolve(ClimateSource.DATASET_ID), true, 320, 8, Duration.ofSeconds(30));
        ImagerySource imagery = new ImagerySource(ImagerySource.EOX_S2_CLOUDLESS_2016, cache.resolve(ImagerySource.DATASET_ID), true, 384, 6, Duration.ofSeconds(30));
        EarthSettings settings = EarthSettings.at(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE);
        EarthGenerator earth = new EarthGenerator(1234L, biomes, settings, tiles, cover, climate, null);
        ExecutorService pool = Executors.newFixedThreadPool(Integer.parseInt(System.getenv().getOrDefault("EARTH_CALIBRATE_THREADS", "4")));
        List<Future<String>> runs = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(System.getenv("EARTH_CALIBRATE_PLACES")))) {
            String[] part = line.trim().split("\\s+");
            if (part.length < 3 || part[0].startsWith("#")) continue;
            int[] at = earth.block(Double.parseDouble(part[1]), Double.parseDouble(part[2]));
            runs.add(pool.submit(() -> {
                String line1 = part[0] + ": " + ImageryCalibration.run(earth, imagery, out, part[0], at[0], at[1], size);
                // What the land cover and climate are across the square, for tuning.
                java.util.TreeMap<String, Integer> kinds = new java.util.TreeMap<>(), climates = new java.util.TreeMap<>();
                for (int j = 0; j < 32; j++) for (int i = 0; i < 32; i++) {
                    String d = earth.diagnose(at[0] - size / 2 + (i * size) / 32, at[1] - size / 2 + (j * size) / 32);
                    if (d == null) continue;
                    String[] two = d.split("\\|");
                    kinds.merge(two[0], 1, Integer::sum);
                    climates.merge(two[1], 1, Integer::sum);
                }
                StringBuilder text = new StringBuilder();
                kinds.forEach((k, n) -> text.append(String.format("%4d  %s%n", n, k)));
                text.append(System.lineSeparator());
                climates.forEach((k, n) -> text.append(String.format("%4d  %s%n", n, k)));
                Files.writeString(out.resolve(part[0] + "-cover.txt"), text);
                return line1;
            }));
        }
        for (Future<String> run : runs) System.out.println(run.get());
        pool.shutdown();
    }
}
