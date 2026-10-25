package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import io.earthplatform.minecraft.cubic.far.Far;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

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
    public static final ModConfigSpec.EnumValue<Sharpness> FAR_SHARPNESS;
    public static final ModConfigSpec.IntValue FAR_TEXTURED_LEVELS;
    public static final ModConfigSpec.BooleanValue SHOW_DOWNLOADS;
    public static final ModConfigSpec.BooleanValue PRESELECT_EARTH;
    public static final ModConfigSpec.IntValue CLOUD_HEIGHT;

    // Common --------------------------------------------------------------------------------------
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_HOME;
    public static final ModConfigSpec.BooleanValue OFFLINE;
    public static final ModConfigSpec.BooleanValue SATELLITE_IMAGERY;
    public static final ModConfigSpec.IntValue CACHE_LIMIT_GB;
    public static final ModConfigSpec.BooleanValue ANYONE_CAN_TRAVEL;
    public static final ModConfigSpec.BooleanValue ANYONE_CAN_USE_HOMES;
    public static final ModConfigSpec.BooleanValue PLAYERS_CHOOSE_START;
    public static final ModConfigSpec.BooleanValue TELEPORT_REQUESTS;
    public static final ModConfigSpec.BooleanValue SHOW_PLAYERS_ON_MAP;
    public static final ModConfigSpec.BooleanValue COUNTRY_NAMES;
    public static final ModConfigSpec.IntValue TRAVEL_LEVELS_PER_1000_KM;
    public static final ModConfigSpec.IntValue TRAVEL_COOLDOWN;
    public static final ModConfigSpec.BooleanValue DAILY_RACE;
    public static final ModConfigSpec.ConfigValue<String> RACE_REWARD;
    public static final ModConfigSpec.IntValue WEB_MAP_PORT;
    public static final ModConfigSpec.BooleanValue WARPS;
    public static final ModConfigSpec.BooleanValue RANDOM_TRIPS;
    public static final ModConfigSpec.BooleanValue GEOGUESSR;
    public static final ModConfigSpec.BooleanValue RACES;
    public static final ModConfigSpec.BooleanValue WEB_MAP;

    static {
        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        FAR_DETAIL = client.comment("How far the far terrain reaches (4 to 48): 4 is about 37 km, 11 about 94 km, 24 about 201 km.",
                        "25 to 48 (up to about 397 km) is the extreme range, for very powerful computers.",
                        "Farther costs memory and video memory and takes longer to fill in. Default 11. Applies after a restart.",
                        "The sharpness setting scales this while playing; this one sets how far the far terrain reaches.")
                .translation(key("farDetail")).gameRestart().defineInRange("detail", 11, 4, Far.MAX_DETAIL);
        FAR_SHARPNESS = client.comment("How sharp far terrain looks: how close coarser levels take over. Changes at once, without a restart.",
                        "Sharper keeps finer voxels further out but takes longer to fill in and uses more memory; Fast fills in quickest. Default Balanced.",
                        "Extreme and Ultra are for very powerful computers: several times the far columns, GPU memory and fill-in time of Balanced,",
                        "and single-digit frame rates even on fast ones, Ultra most of all.")
                .translation(key("farSharpness")).defineEnum("sharpness", Sharpness.BALANCED);
        // A new key, so configs saved with the old default of 2 take up the new one.
        FAR_TEXTURED_LEVELS = client.comment("How many far levels are drawn with block textures; further ones use flat colors. Default 8, all of them.")
                .translation(key("farTexturedLevels")).gameRestart().defineInRange("texturedFarLevels", 8, 0, 8);
        SHOW_DOWNLOADS = client.comment("Show a small note while Earth data downloads, and when it can't be reached.")
                .translation(key("showDownloads")).define("showDownloadStatus", true);
        PRESELECT_EARTH = client.comment("Pick the Earth world type when you open Create World.")
                .translation(key("preselectEarth")).define("preselectEarth", true);
        CLOUD_HEIGHT = client.comment("Height of the clouds in Earth worlds, in meters above sea level; they also stay at least 1200 m over",
                        "the ground you stand on. 0 keeps vanilla clouds at Y 192. Shader packs draw their own clouds. Default 2500.")
                .translation(key("cloudHeight")).defineInRange("cloudHeight", 2500, 0, 9000);
        CLIENT_SPEC = client.build();

        ModConfigSpec.Builder common = new ModConfigSpec.Builder();
        DEFAULT_HOME = common.comment("Where new Earth worlds start when nobody picked a place: a famous place id (see /earth places)",
                        "or \"latitude,longitude\". Singleplayer worlds can pick one on the Create World screen.")
                .translation(key("defaultHome")).define("defaultHome", EarthPlaces.YOSEMITE.id(), value -> value instanceof String text && home(text) != null);
        OFFLINE = common.comment("Never download Earth data; only places already in the cache generate.")
                .translation(key("offline")).gameRestart().define("offline", false);
        CACHE_LIMIT_GB = common.comment("Largest size of the downloaded Earth data cache, in gigabytes; the least recently used files go first,",
                        "when the game starts and as it grows, never ones this session has used. 0 keeps everything.")
                .translation(key("cacheLimit")).defineInRange("cacheLimitGb", 8, 0, 1024);
        SATELLITE_IMAGERY = common.comment("Color the ground of new Earth worlds from satellite photos (EOX Sentinel-2 cloudless imagery,",
                        "downloaded like the terrain). Off by default; existing worlds keep what they were made with.")
                .translation(key("satelliteImagery")).define("satelliteImagery", false);
        ANYONE_CAN_TRAVEL = common.comment("Let every player use /earth goto. When false, only operators can (singleplayer always can).")
                .translation(key("anyoneCanTravel")).define("anyoneCanTravel", false);
        ANYONE_CAN_USE_HOMES = common.comment("Let every player save a home with /earth sethome and go back with /earth home, and go to the server spawn",
                        "with /earth spawn. When false, only players who may travel can.")
                .translation(key("anyoneCanUseHomes")).define("anyoneCanUseHomes", true);
        PLAYERS_CHOOSE_START = common.comment("Let each new player choose, once, where they start on a globe; they respawn there after that",
                        "when they have no bed. Operators can reset a player's choice with /earth spawn player <name> reset.")
                .translation(key("playersChooseStart")).define("playersChooseStart", false);
        TELEPORT_REQUESTS = common.comment("Let players ask to teleport to each other with /earth tpa and /earth tpahere.")
                .translation(key("teleportRequests")).define("teleportRequests", true);
        SHOW_PLAYERS_ON_MAP = common.comment("Show other players as dots on the travel map and the spawn picker.")
                .translation(key("showPlayersOnMap")).define("showPlayersOnMap", true);
        COUNTRY_NAMES = common.comment("Show \"Now entering <country>\" on screen when a player crosses a border (Natural Earth borders).")
                .translation(key("countryNames")).define("countryNames", true);
        TRAVEL_LEVELS_PER_1000_KM = common.comment("What a trip by command costs players in survival, in experience levels per 1000 km (at least 1).",
                        "Operators and creative players travel free. 0 makes travel free for everyone.")
                .translation(key("travelLevels")).defineInRange("travelLevelsPer1000Km", 0, 0, 1000);
        TRAVEL_COOLDOWN = common.comment("Seconds a survival player waits between trips by command. 0 for none.")
                .translation(key("travelCooldown")).defineInRange("travelCooldownSeconds", 0, 0, 86_400);
        DAILY_RACE = common.comment("Start a race to a random famous place each day (UTC) once someone is online. Operators can always start one with /earth race start.")
                .translation(key("dailyRace")).define("dailyRace", false);
        RACE_REWARD = common.comment("The race winner's prize: an item and a count, such as \"minecraft:diamond 5\". Empty for none.")
                .translation(key("raceReward")).define("raceReward", "minecraft:diamond 5");
        WARPS = common.comment("Let players use the server's warps (/earth warp). Operators make them with /earth setwarp.")
                .translation(key("warps")).define("warps", true);
        RANDOM_TRIPS = common.comment("Let players take random trips to land with /earth random.")
                .translation(key("randomTrips")).define("randomTrips", true);
        GEOGUESSR = common.comment("Allow Where on Earth guessing rounds (/earth guess).")
                .translation(key("geoguessr")).define("geoguessr", true);
        RACES = common.comment("Allow races to famous places (/earth race).")
                .translation(key("races")).define("races", true);
        WEB_MAP = common.comment("Serve a live web map of the players at http://<server>:<webMapPort>/ for sharing outside the game.",
                        "It shows player names and where they are to anyone who can reach the port.")
                .translation(key("webMap")).define("webMap", false);
        WEB_MAP_PORT = common.comment("The web map's port.")
                .translation(key("webMapPort")).defineInRange("webMapPort", 8123, 1, 65_535);
        COMMON_SPEC = common.build();

        var container = ModList.get().getModContainerById(EarthPlatformMod.MOD_ID).orElseThrow();
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        container.registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }

    private EarthConfig() {}

    /**
     * Far terrain sharpness choices, as scales of the start-up detail (see {@link Far#sharpness}).
     * Even the default keeps each level a bit past where it would turn to blurry squares on screen,
     * since far terrain drawn as blocks shows its block size far more than smooth ground did.
     * Extreme and Ultra, past {@link Far#EXTREME_SCALE}, are for very powerful computers and big
     * screens: far voxels about 0.4 and 0.3 times Balanced's size on screen at the default
     * distance, at about 4.6 and 7.8 times its far columns.
     */
    public enum Sharpness implements TranslatableEnum {
        FAST(1.0), BALANCED(1.3), SHARP(1.6), SHARPEST(2.0), EXTREME(3.0), ULTRA(4.0);

        final double scale;

        Sharpness(double scale) { this.scale = scale; }

        @Override public Component getTranslatedName() { return Component.translatable(key("farSharpness." + name().toLowerCase(java.util.Locale.ROOT))); }
    }

    /** How far the far terrain reaches with a detail setting, in kilometers where a block is a meter. */
    public static int distanceKm(int detail) {
        return (int) Math.round((2 * detail + 1) * (double) Far.width(Far.MAX_LEVEL) / 1000.0);
    }

    /** Sets the far terrain's distance from a screen in game: saved to the file, in effect after a restart. */
    public static void setDetail(int detail) {
        if (FAR_DETAIL.get() == detail) return;
        FAR_DETAIL.set(detail);
        CLIENT_SPEC.save();
    }

    /** Sets the far terrain's sharpness from a screen in game: saved to the file and in effect at once. */
    public static void setSharpness(Sharpness sharpness) {
        FAR_SHARPNESS.set(sharpness);
        CLIENT_SPEC.save();
        Far.sharpness(sharpness.scale);
    }

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
            Far.sharpness(FAR_SHARPNESS.get().scale);
        } else if (config.getSpec() == COMMON_SPEC) {
            property("earthplatform.earth.offline", Boolean.toString(OFFLINE.get()));
            property("earthplatform.earth.imagery", Boolean.toString(SATELLITE_IMAGERY.get()));
            // An edited file takes effect on a running server at once, as /earth settings does.
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) server.execute(() -> EarthToggles.changed(server));
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
        io.earthplatform.geography.globe.DiskCache.log = EarthPlatformMod.LOGGER::info;
        io.earthplatform.geography.globe.DiskCache.limit(FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache"), (long) CACHE_LIMIT_GB.get() << 30);
    }
}
