package io.earthplatform.minecraft.cubic;

import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.ImagerySource;
import io.earthplatform.geography.globe.ElevationLayer;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /** Elevation sources by a world's {@code earth.elevation} pin, so worlds made with different sources never share tiles. */
    private static final Map<String, TerrariumTileSource> tiles = new HashMap<>();
    private static WorldCoverSource cover;
    private static ClimateSource climate;
    private static ImagerySource imagery;

    private CubicSettings() {}

    public static CubeGenerator open(Path folder, long seed, Registry<Biome> biomes) {
        Path file = folder.resolve(FILE);
        Properties properties = new Properties();
        try {
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            } else {
                if (hasSavedCubes(folder.resolve("cubes"))) properties.setProperty("generator", HighlandsGenerator.ID);
                else earth(properties, EarthSettings.defaults(), "");
                store(folder, properties);
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
                // Worlds made before land cover keep generating as they did.
                String coverId = properties.getProperty("earth.cover", "");
                if (!coverId.isEmpty() && !WorldCoverSource.DATASET_ID.equals(coverId))
                    throw new IllegalStateException("Unsupported land cover '" + coverId + "' in " + file);
                // Climate came with land cover (worlds made in between read the same dataset).
                String climateId = properties.getProperty("earth.climate", "");
                if (!climateId.isEmpty() && !ClimateSource.DATASET_ID.equals(climateId))
                    throw new IllegalStateException("Unsupported climate '" + climateId + "' in " + file);
                // Worlds made before imagery keep their ground as it was.
                String imageryId = properties.getProperty("earth.imagery", "");
                if (!imageryId.isEmpty() && !ImagerySource.DATASET_ID.equals(imageryId))
                    throw new IllegalStateException("Unsupported imagery '" + imageryId + "' in " + file);
                // Worlds made before elevation layers read the composite alone.
                String elevationId = properties.getProperty("earth.elevation", TerrariumTileSource.DATASET_ID);
                yield new EarthGenerator(seed, biomes, settings, tiles(elevationId), coverId.isEmpty() ? null : cover(), coverId.isEmpty() ? null : climate(),
                        coverId.isEmpty() || imageryId.isEmpty() ? null : imagery());
            }
            default -> throw new IllegalStateException("Unknown cubic generator '" + id + "' in " + file);
        };
    }

    /**
     * Pins a new Earth world's home before its cubic level first loads, naming the place for
     * players; does nothing when the world already has a generator.
     */
    public static void pinEarth(Path folder, EarthSettings earth, String name) {
        if (Files.isRegularFile(folder.resolve(FILE))) return;
        Properties properties = new Properties();
        earth(properties, earth, name);
        try { store(folder, properties); }
        catch (IOException failure) { throw new UncheckedIOException("Could not pin the Earth's home in " + folder, failure); }
    }

    /** The name a world's home was given when it was made, or empty. */
    public static String homeName(Path folder) {
        Path file = folder.resolve(FILE);
        if (!Files.isRegularFile(file)) return "";
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        catch (IOException failure) { return ""; }
        return properties.getProperty("earth.homeName", "");
    }

    private static void earth(Properties properties, EarthSettings earth, String name) {
        properties.setProperty("generator", EarthGenerator.ID);
        properties.setProperty("earth.latitude", Double.toString(earth.latitude()));
        properties.setProperty("earth.longitude", Double.toString(earth.longitude()));
        properties.setProperty("earth.metresPerBlock", Double.toString(earth.metresPerBlock()));
        properties.setProperty("earth.projection", earth.projection());
        properties.setProperty("earth.elevation", elevation());
        properties.setProperty("earth.cover", WorldCoverSource.DATASET_ID);
        properties.setProperty("earth.climate", ClimateSource.DATASET_ID);
        if (imageryEnabled()) properties.setProperty("earth.imagery", ImagerySource.DATASET_ID);
        if (!name.isEmpty()) properties.setProperty("earth.homeName", name);
    }

    private static void store(Path folder, Properties properties) throws IOException {
        Files.createDirectories(folder);
        Path temp = folder.resolve(FILE + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            properties.store(writer, "Cubic world generator, fixed when the world was made");
        }
        Files.move(temp, folder.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
     * The elevation pin for new worlds: the Terrarium composite, then the layers laid over it, joined
     * by '+'. With no layers it is the composite's id alone, as worlds made before layers pinned.
     */
    public static String elevation() {
        String pin = TerrariumTileSource.DATASET_ID;
        return pin;
    }

    /** Elevation as new worlds read it. */
    public static synchronized TerrariumTileSource tiles() { return tiles(elevation()); }

    /**
     * Elevation tiles for an {@code earth.elevation} pin, shared by every Earth world in this game made
     * with the same sources, cached on disk under the game folder.
     * {@code -Dearthplatform.earth.offline=true} reads only that cache.
     */
    public static synchronized TerrariumTileSource tiles(String pin) {
        TerrariumTileSource known = tiles.get(pin);
        if (known != null) return known;
        String[] ids = pin.split("\\+");
        if (!TerrariumTileSource.DATASET_ID.equals(ids[0])) throw new IllegalStateException("Unsupported elevation '" + pin + "'");
        List<ElevationLayer> layers = new ArrayList<>();
        for (int i = 1; i < ids.length; i++) {
            throw new IllegalStateException("Unsupported elevation layer '" + ids[i] + "' in '" + pin + "'");
        }
        Path cache = cacheFolder().resolve(TerrariumTileSource.DATASET_ID.toLowerCase(Locale.ROOT));
        try {
            known = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                    256, 6, Duration.ofSeconds(20), layers);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not open the elevation tile cache at " + cache, failure);
        }
        tiles.put(pin, known);
        return known;
    }

    private static Path cacheFolder() { return FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache"); }

    /** Land cover shared by every Earth world in this game, cached on disk beside the elevation tiles. */
    public static synchronized WorldCoverSource cover() {
        if (cover == null) {
            Path cache = FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache").resolve(WorldCoverSource.DATASET_ID);
            try {
                cover = new WorldCoverSource(WorldCoverSource.AWS_WORLDCOVER, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        48, 4, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the land cover cache at " + cache, failure);
            }
        }
        return cover;
    }

    /** Climate shared by every Earth world in this game, cached on disk beside the elevation tiles. */
    public static synchronized ClimateSource climate() {
        if (climate == null) {
            Path cache = FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache").resolve(ClimateSource.DATASET_ID);
            try {
                // Rows are about 86 KiB; a 48 km square needs about 210 of them. Its elevation comes in
                // about 50 small reads, so more of them run at once.
                climate = new ClimateSource(ClimateSource.CHELSA, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        320, 8, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the climate cache at " + cache, failure);
            }
        }
        return climate;
    }

    /**
     * Whether new Earth worlds are made with satellite imagery: off unless the game runs with
     * {@code -Dearthplatform.earth.imagery=true}, as EOX's tile service is not cleared for public
     * use. A world keeps what it was made with, so its ground never changes partway.
     */
    public static boolean imageryEnabled() {
        return Boolean.getBoolean("earthplatform.earth.imagery");
    }

    /** Satellite imagery shared by every Earth world in this game, cached on disk beside the elevation tiles. */
    public static synchronized ImagerySource imagery() {
        if (imagery == null) {
            Path cache = FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache").resolve(ImagerySource.DATASET_ID);
            try {
                // Tiles are 256 KiB decoded; a 3 km square at full detail needs about 25 of them.
                imagery = new ImagerySource(ImagerySource.EOX_S2_CLOUDLESS_2016, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        192, 6, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the imagery cache at " + cache, failure);
            }
        }
        return imagery;
    }
}
