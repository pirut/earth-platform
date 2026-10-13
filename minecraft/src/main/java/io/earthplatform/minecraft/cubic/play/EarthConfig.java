package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The player-facing settings, in config/earthplatform-client.toml and earthplatform-common.toml
 * and on the mod's config screen. Engine settings still read their {@code -D} system properties;
 * a property given on the command line wins over the file.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class EarthConfig {
    // Client --------------------------------------------------------------------------------------
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.IntValue FAR_DETAIL;
    public static final ModConfigSpec.IntValue FAR_TEXTURED_LEVELS;
    public static final ModConfigSpec.BooleanValue SHOW_DOWNLOADS;
    public static final ModConfigSpec.BooleanValue PRESELECT_EARTH;

    // Common --------------------------------------------------------------------------------------
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_HOME;
    public static final ModConfigSpec.BooleanValue OFFLINE;
    public static final ModConfigSpec.BooleanValue SATELLITE_IMAGERY;
    public static final ModConfigSpec.IntValue CACHE_LIMIT_GB;
    public static final ModConfigSpec.BooleanValue ANYONE_CAN_TRAVEL;

    static {
        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        FAR_DETAIL = client.comment("How much detail far terrain keeps before coarser levels take over (4 to 24).",
                        "Higher looks sharper and reaches further, but costs memory and video memory. Default 11.")
                .translation(key("farDetail")).gameRestart().defineInRange("detail", 11, 4, 24);
        FAR_TEXTURED_LEVELS = client.comment("How many far levels are drawn with block textures; further ones use flat colours. Default 2.")
                .translation(key("farTexturedLevels")).gameRestart().defineInRange("texturedLevels", 2, 0, 6);
        SHOW_DOWNLOADS = client.comment("Show a small note while Earth data downloads, and when it can't be reached.")
                .translation(key("showDownloads")).define("showDownloadStatus", true);
        PRESELECT_EARTH = client.comment("Pick the Earth world type when you open Create World.")
                .translation(key("preselectEarth")).define("preselectEarth", true);
        CLIENT_SPEC = client.build();

        ModConfigSpec.Builder common = new ModConfigSpec.Builder();
        DEFAULT_HOME = common.comment("Where new Earth worlds start when nobody picked a place: a famous place id (see /earth places)",
                        "or \"latitude,longitude\". Singleplayer worlds can pick one on the Create World screen.")
                .translation(key("defaultHome")).define("defaultHome", EarthPlaces.YOSEMITE.id(), value -> value instanceof String text && home(text) != null);
        OFFLINE = common.comment("Never download Earth data; only places already in the cache generate.")
                .translation(key("offline")).gameRestart().define("offline", false);
        CACHE_LIMIT_GB = common.comment("Largest size of the downloaded Earth data cache, in gigabytes; the least recently used files go first",
                        "when the game starts. 0 keeps everything.")
                .translation(key("cacheLimit")).defineInRange("cacheLimitGb", 8, 0, 1024);
        SATELLITE_IMAGERY = common.comment("Colour the ground of new Earth worlds from satellite photos (EOX Sentinel-2 cloudless imagery,",
                        "downloaded like the terrain). Off by default; existing worlds keep what they were made with.")
                .translation(key("satelliteImagery")).define("satelliteImagery", false);
        ANYONE_CAN_TRAVEL = common.comment("Let every player use /earth goto. When false, only operators can (singleplayer always can).")
                .translation(key("anyoneCanTravel")).define("anyoneCanTravel", false);
        COMMON_SPEC = common.build();

        var container = ModList.get().getModContainerById(EarthPlatformMod.MOD_ID).orElseThrow();
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        container.registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    private EarthConfig() {}

    private static String key(String name) { return EarthPlatformMod.MOD_ID + ".configuration." + name; }

    /** Settings for a home written as a place id or "latitude,longitude", or null when it is neither. */
    public static EarthSettings home(String text) {
        var place = EarthPlaces.byId(text.trim());
        if (place.isPresent()) return EarthSettings.at(place.get().latitude(), place.get().longitude());
        String[] parts = text.split(",");
        if (parts.length != 2) return null;
        try { return EarthSettings.at(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())); }
        catch (RuntimeException invalid) { return null; }
    }

    @SubscribeEvent static void loaded(ModConfigEvent.Loading event) { apply(event.getConfig()); }
    @SubscribeEvent static void reloaded(ModConfigEvent.Reloading event) { apply(event.getConfig()); }

    /** Hands the file's values to the engine through its system properties, unless the command line set them. */
    private static void apply(ModConfig config) {
        if (config.getSpec() == CLIENT_SPEC) {
            property("earthplatform.far.detail", Integer.toString(FAR_DETAIL.get()));
            property("earthplatform.far.textured", Integer.toString(FAR_TEXTURED_LEVELS.get()));
        } else if (config.getSpec() == COMMON_SPEC) {
            property("earthplatform.earth.offline", Boolean.toString(OFFLINE.get()));
            property("earthplatform.earth.imagery", Boolean.toString(SATELLITE_IMAGERY.get()));
        }
    }

    /** Engine properties given on the command line, which the file never overrides. */
    private static final java.util.Set<String> FROM_COMMAND_LINE = java.util.stream.Stream.of(
            "earthplatform.far.detail", "earthplatform.far.textured", "earthplatform.earth.offline", "earthplatform.earth.imagery")
            .filter(key -> System.getProperty(key) != null).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Sets satellite imagery from the Create World screen: saved to the file and in effect for the next new world. */
    public static void setImagery(boolean on) {
        SATELLITE_IMAGERY.set(on);
        COMMON_SPEC.save();
        property("earthplatform.earth.imagery", Boolean.toString(on));
    }

    /** Whether new worlds get satellite imagery, counting a command-line setting. */
    public static boolean imagery() { return Boolean.getBoolean("earthplatform.earth.imagery"); }

    private static void property(String key, String value) {
        if (!FROM_COMMAND_LINE.contains(key)) System.setProperty(key, value);
    }

    @SubscribeEvent static void setup(FMLCommonSetupEvent event) {
        long limit = (long) CACHE_LIMIT_GB.get() << 30;
        if (limit <= 0) return;
        Thread thread = new Thread(() -> trimCache(FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache"), limit), "Earth cache trim");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** Deletes the least recently changed cache files until the cache fits in 90% of its limit. */
    static void trimCache(Path cache, long limit) {
        if (!Files.isDirectory(cache)) return;
        record Entry(Path path, long size, long time) {}
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        try (Stream<Path> files = Files.walk(cache)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (!attributes.isRegularFile()) continue;
                entries.add(new Entry(path, attributes.size(), attributes.lastModifiedTime().toMillis()));
                total += attributes.size();
            }
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Could not measure the Earth data cache: {}", failure.toString());
            return;
        }
        if (total <= limit) return;
        entries.sort(Comparator.comparingLong(Entry::time));
        long target = limit / 10 * 9, freed = 0;
        int removed = 0;
        for (Entry entry : entries) {
            if (total - freed <= target) break;
            try { if (Files.deleteIfExists(entry.path)) { freed += entry.size; removed++; } }
            catch (IOException | RuntimeException ignored) { /* in use; try the next one */ }
        }
        EarthPlatformMod.LOGGER.info("Trimmed the Earth data cache: removed {} files, {} MB", removed, freed >> 20);
    }
}
