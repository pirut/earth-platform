package io.earthplatform.minecraft.cubic;

import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import io.earthplatform.minecraft.cubic.gen.HighlandsGenerator;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Which generator a cubic world was made with, pinned in its folder the first time it loads so
 * saved cubes never meet a different generator. New worlds get the Earth; cubic worlds that
 * already saved cubes before this file existed keep the Highlands test world.
 */
public final class CubicSettings {
    private static final String FILE = "generator.properties";
    private static TerrariumTileSource tiles;

    private CubicSettings() {}

    public static CubeGenerator open(Path folder, long seed, Registry<Biome> biomes) {
        Path file = folder.resolve(FILE);
        Properties properties = new Properties();
        try {
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            } else {
                if (hasSavedCubes(folder.resolve("cubes"))) properties.setProperty("generator", HighlandsGenerator.ID);
                else {
                    EarthSettings earth = EarthSettings.defaults();
                    properties.setProperty("generator", EarthGenerator.ID);
                    properties.setProperty("earth.latitude", Double.toString(earth.latitude()));
                    properties.setProperty("earth.longitude", Double.toString(earth.longitude()));
                    properties.setProperty("earth.metresPerBlock", Double.toString(earth.metresPerBlock()));
                    properties.setProperty("earth.projection", earth.projection());
                    properties.setProperty("earth.elevation", TerrariumTileSource.DATASET_ID);
                }
                Files.createDirectories(folder);
                Path temp = folder.resolve(FILE + ".tmp");
                try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    properties.store(writer, "Cubic world generator, fixed when the world was made");
                }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read or pin the cubic world's generator in " + file, failure);
        }
        String id = properties.getProperty("generator", "");
        return switch (id) {
            case HighlandsGenerator.ID -> new HighlandsGenerator(seed, biomes);
            case EarthGenerator.ID -> {
                if (!MercatorGrid.PROJECTION_ID.equals(properties.getProperty("earth.projection")))
                    throw new IllegalStateException("Unsupported Earth projection in " + file);
                EarthSettings settings = new EarthSettings(number(properties, "earth.latitude"), number(properties, "earth.longitude"),
                        number(properties, "earth.metresPerBlock"));
                yield new EarthGenerator(seed, biomes, settings, tiles());
            }
            default -> throw new IllegalStateException("Unknown cubic generator '" + id + "' in " + file);
        };
    }

    private static double number(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalStateException("Missing " + key + " in the cubic world's " + FILE);
        return Double.parseDouble(value.trim());
    }

    private static boolean hasSavedCubes(Path cubes) throws IOException {
        if (!Files.isDirectory(cubes)) return false;
        try (Stream<Path> files = Files.list(cubes)) { return files.findAny().isPresent(); }
    }

    /**
     * Elevation tiles shared by every Earth world in this game, cached on disk under the game
     * folder. {@code -Dearthplatform.earth.offline=true} reads only that cache.
     */
    public static synchronized TerrariumTileSource tiles() {
        if (tiles == null) {
            Path cache = FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache").resolve(TerrariumTileSource.DATASET_ID.toLowerCase(Locale.ROOT));
            try {
                tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        256, 6, Duration.ofSeconds(20));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the elevation tile cache at " + cache, failure);
            }
        }
        return tiles;
    }
}
