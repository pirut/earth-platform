package io.earthplatform.minecraft.cubic;

import io.earthplatform.geography.globe.BareEarth;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.CopernicusFill;
import io.earthplatform.geography.globe.CopernicusSource;
import io.earthplatform.geography.globe.ImagerySource;
import io.earthplatform.geography.globe.ElevationLayer;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.RemaElevation;
import io.earthplatform.geography.globe.RemaSource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.UsgsElevation;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.geography.globe.RiverMap;
import io.earthplatform.geography.globe.SurfaceWaterSource;
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
    private static CopernicusSource copernicus;
    private static RemaElevation rema;
    /** 3DEP by each way of reading it a world may pin. */
    private static final UsgsElevation[] usgs = new UsgsElevation[UsgsElevation.Reading.values().length];
    private static WorldCoverSource cover;
    private static BareEarth bareEarth;
    private static ClimateSource climate;
    private static RiverMap rivers;
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
                // Worlds made before narrow rivers keep their land as it was.
                String riversId = properties.getProperty("earth.rivers", "");
                if (!riversId.isEmpty() && !SurfaceWaterSource.DATASET_ID.equals(riversId))
                    throw new IllegalStateException("Unsupported rivers '" + riversId + "' in " + file);
                // Worlds made before bare-earth ground keep their towns' roofs as ground.
                String groundId = properties.getProperty("earth.ground", "");
                if (!groundId.isEmpty() && !BareEarth.ID.equals(groundId))
                    throw new IllegalStateException("Unsupported ground '" + groundId + "' in " + file);
                // Worlds made before elevation layers read the composite alone.
                String elevationId = properties.getProperty("earth.elevation", TerrariumTileSource.DATASET_ID);
                yield new EarthGenerator(seed, biomes, settings, tiles(elevationId), coverId.isEmpty() ? null : cover(), coverId.isEmpty() ? null : climate(),
                        coverId.isEmpty() || imageryId.isEmpty() ? null : imagery(), coverId.isEmpty() || riversId.isEmpty() ? null : rivers(),
                        coverId.isEmpty() || groundId.isEmpty() ? null : bareEarth());
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
        properties.setProperty("earth.rivers", SurfaceWaterSource.DATASET_ID);
        properties.setProperty("earth.ground", BareEarth.ID);
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
     * by '+'. The Copernicus fill, the United States from 3DEP and Antarctica from REMA are on unless
     * the game runs with {@code -Dearthplatform.earth.copernicus=false},
     * {@code -Dearthplatform.earth.3dep=false} or {@code -Dearthplatform.earth.rema=false}.
     */
    public static String elevation() {
        String pin = TerrariumTileSource.DATASET_ID;
        if (!"false".equals(System.getProperty("earthplatform.earth.copernicus"))) pin += "+" + CopernicusFill.DATASET_ID;
        if (!"false".equals(System.getProperty("earthplatform.earth.3dep"))) pin += "+" + UsgsElevation.GRADED_ID;
        if (!"false".equals(System.getProperty("earthplatform.earth.rema"))) pin += "+" + RemaElevation.DATASET_ID;
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
        // The Copernicus fill checks the tiles as they were before any layer, so it reads them back
        // from the source made here.
        TerrariumTileSource[] made = new TerrariumTileSource[1];
        for (int i = 1; i < ids.length; i++) {
            if (CopernicusFill.DATASET_ID.equals(ids[i])) layers.add(new CopernicusFill(copernicus(), (zoom, x, y) -> made[0].cleaned(zoom, x, y), 512));
            else if (UsgsElevation.Reading.of(ids[i]) != null) layers.add(usgs(UsgsElevation.Reading.of(ids[i])));
            else if (RemaElevation.DATASET_ID.equals(ids[i])) layers.add(rema());
            else throw new IllegalStateException("Unsupported elevation layer '" + ids[i] + "' in '" + pin + "'");
        }
        Path cache = cacheFolder().resolve(TerrariumTileSource.DATASET_ID.toLowerCase(Locale.ROOT));
        try {
            // Far terrain over a new area reads several hundred tiles at several zooms; with fewer kept than
            // that, a first visit cleaned the same tiles five or six times over. Each is 256 KiB: up to a
            // sixteenth of the heap, from 256 to 1,024 of them. Fetching them is mostly a wait on the round
            // trip, so many go at once.
            int kept = (int) Math.clamp(Runtime.getRuntime().maxMemory() / 16 / (256 << 10), 256, 1024);
            known = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                    kept, 16, Duration.ofSeconds(20), layers);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not open the elevation tile cache at " + cache, failure);
        }
        made[0] = known;
        tiles.put(pin, known);
        return known;
    }

    /**
     * Copernicus GLO-30, for the fill of places where the elevation tiles are wrong over a wide area:
     * fjords filled in as land, and heights off the sea's datum north of SRTM. Cached on disk beside
     * the elevation tiles; its blocks are up to 4 MiB decoded.
     */
    public static synchronized CopernicusSource copernicus() {
        if (copernicus == null) {
            Path cache = cacheFolder().resolve(CopernicusSource.DATASET_ID);
            try {
                copernicus = new CopernicusSource(CopernicusSource.AWS_COPERNICUS, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        12, 8, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the Copernicus elevation cache at " + cache, failure);
            }
        }
        return copernicus;
    }

    /** The United States from 3DEP, cached on disk beside the elevation tiles; both ways of reading it share the cache. */
    public static synchronized UsgsElevation usgs(UsgsElevation.Reading reading) {
        int at = reading.ordinal();
        if (usgs[at] == null) {
            Path cache = cacheFolder().resolve(UsgsElevation.DATASET_ID);
            try {
                // Blocks are up to 1 MiB decoded; a zoom-15 tile needs one to four.
                usgs[at] = new UsgsElevation(UsgsElevation.AWS_3DEP, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        48, 6, Duration.ofSeconds(30), reading);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the 3DEP elevation cache at " + cache, failure);
            }
        }
        return usgs[at];
    }

    /** Antarctica from REMA, cached on disk beside the elevation tiles. */
    public static synchronized RemaElevation rema() {
        if (rema == null) {
            Path cache = cacheFolder().resolve(RemaSource.DATASET_ID);
            try {
                // Blocks are 1 MiB decoded; a zoom-15 tile needs one to four.
                rema = new RemaElevation(new RemaSource(RemaSource.AWS_REMA, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        48, 6, Duration.ofSeconds(30)));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the REMA elevation cache at " + cache, failure);
            }
        }
        return rema;
    }

    private static Path cacheFolder() { return FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache"); }

    /** Bare-earth ground shared by every Earth world in this game, its worked-out fields cached on disk beside the elevation tiles. */
    public static synchronized BareEarth bareEarth() {
        if (bareEarth == null)
            bareEarth = new BareEarth(tiles(), cover(), cacheFolder().resolve(BareEarth.ID));
        return bareEarth;
    }

    /** Land cover shared by every Earth world in this game, cached on disk beside the elevation tiles. */
    public static synchronized WorldCoverSource cover() {
        if (cover == null) {
            Path cache = FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache").resolve(WorldCoverSource.DATASET_ID);
            try {
                cover = new WorldCoverSource(WorldCoverSource.AWS_WORLDCOVER, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        48, 8, Duration.ofSeconds(30));
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the land cover cache at " + cache, failure);
            }
        }
        return cover;
    }

    /** Narrow rivers shared by every Earth world in this game, their surface water cached on disk beside the elevation tiles. */
    public static synchronized RiverMap rivers() {
        if (rivers == null) {
            Path cache = FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache").resolve(SurfaceWaterSource.DATASET_ID);
            try {
                // Blocks are 64 KiB decoded and cover 7 km; each traced square keeps 64 KiB.
                rivers = new RiverMap(new SurfaceWaterSource(SurfaceWaterSource.JRC_GSW, cache, !Boolean.getBoolean("earthplatform.earth.offline"),
                        96, 4, Duration.ofSeconds(30)), 256);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not open the surface water cache at " + cache, failure);
            }
        }
        return rivers;
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
