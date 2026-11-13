package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Every on/off engine switch that is also a {@code -Dearthplatform...} system property, as a setting.
 * <p>
 * Most of these properties are read once, into a {@code static final} field, when the class that uses
 * them initializes, so a setting can only take effect if it becomes a property before that. NeoForge
 * loads the settings files only after the mods are constructed and the registries are filled, which is
 * after classes such as the world generator have initialized; so each loader's entry point calls
 * {@link #preload} first thing, which reads the two settings files itself and sets the properties.
 * {@link EarthConfig#apply} runs the same step when the loader's own config load finishes (and warns
 * if that changes anything, so a toggle never does nothing without a word), and again when the file is
 * edited, for the toggles that are read when a world is made ({@link When#NEW_WORLDS}).
 * <p>
 * A property given on the command line always wins over the file. A toggle at its default leaves its
 * property unset, so nothing differs from before for anyone who touches no setting.
 */
public final class EngineToggles {
    /** The settings file a toggle lives in: the client file, or the common file (the server, or both sides). */
    public enum Side {
        CLIENT("earthplatform-client.toml"), COMMON("earthplatform-common.toml");

        final String file;

        Side(String file) { this.file = file; }
    }

    /** The heading a toggle is listed under: a section of its settings file, and of the config screen. */
    public enum Group {
        FAR_TERRAIN("farTerrain", "Far terrain"), FLYING("flying", "Flying and horizon"), SKY("sky", "Clouds and sky"),
        NEARBY_BLOCKS("nearbyBlocks", "Nearby blocks"), COMPATIBILITY("compatibility", "Compatibility"),
        SPEEDUPS("speedups", "Speed-ups and comparisons"), DIAGNOSTICS("diagnostics", "Diagnostics"), WORLD_DATA("worldData", "Data for new worlds");

        /** The section's name in the settings file, and the end of its language key. */
        public final String key;
        /** The English heading, as the language file has it. */
        public final String label;

        Group(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /** When a changed setting takes effect. */
    public enum When {
        /** The property is read once, when its class initializes: the next start of the game. */
        RESTART,
        /** The property is read when a world is made: right after the change, for the next new world. */
        NEW_WORLDS
    }

    public enum Trait {
        /** Marked experimental on the screens, and off by default. */
        EXPERIMENTAL,
        /** Also a toggle on Sodium's Earth page: the player-facing ones. */
        SODIUM,
        /** A self-check, log or timer for developers, listed only on the config screen. */
        DIAGNOSTIC
    }

    /**
     * One toggle.
     *
     * @param property the system property without its {@code earthplatform.} prefix
     * @param defaultValue what the engine does when the property is absent
     */
    public record Toggle(Side side, Group group, String property, boolean defaultValue, When when, List<Trait> traits) {
        public String systemProperty() { return "earthplatform." + property; }

        /** The name in the settings file and the end of the language keys: {@code far.queryCull.debug} is {@code farQueryCullDebug}. */
        public String key() {
            String[] parts = property.split("\\.");
            StringBuilder key = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++) key.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i], 1, parts[i].length());
            return key.toString();
        }

        /** The language key of the name; the description is the same with {@code .tooltip}. */
        public String langKey() { return EarthPlatformMod.MOD_ID + ".configuration." + key(); }

        public boolean experimental() { return traits.contains(Trait.EXPERIMENTAL); }
        public boolean sodium() { return traits.contains(Trait.SODIUM); }
        public boolean diagnostic() { return traits.contains(Trait.DIAGNOSTIC); }
    }

    private static Toggle on(Side side, Group group, String property, When when, Trait... traits) {
        return new Toggle(side, group, property, true, when, List.of(traits));
    }

    private static Toggle off(Side side, Group group, String property, When when, Trait... traits) {
        return new Toggle(side, group, property, false, when, List.of(traits));
    }

    private static final When RESTART = When.RESTART;
    private static final When NEW_WORLDS = When.NEW_WORLDS;
    private static final Side CLIENT = Side.CLIENT;
    private static final Side COMMON = Side.COMMON;
    private static final Trait EXPERIMENTAL = Trait.EXPERIMENTAL;
    private static final Trait SODIUM = Trait.SODIUM;
    private static final Trait DIAGNOSTIC = Trait.DIAGNOSTIC;

    /**
     * Every boolean engine property. The defaults are the engine's own (see {@code docs/FLAGS.md}, which
     * lists where each is read); {@code EngineTogglesTest} checks them against the sources and the doc.
     * Flags that are not on or off (counts, distances, {@code auto} modes) are not here.
     */
    public static final List<Toggle> ALL = List.of(
            // client file, far terrain
            off(CLIENT, Group.FAR_TERRAIN, "far.smooth", RESTART, EXPERIMENTAL, SODIUM),
            off(CLIENT, Group.FAR_TERRAIN, "far.blockTextures", RESTART, EXPERIMENTAL, SODIUM),
            on(CLIENT, Group.FAR_TERRAIN, "far.fade", RESTART, SODIUM),
            on(CLIENT, Group.FAR_TERRAIN, "far.zoom", RESTART, SODIUM),
            on(CLIENT, Group.FAR_TERRAIN, "far.nearGround", RESTART),
            // client file, flying
            off(CLIENT, Group.FLYING, "far.curvature", RESTART, EXPERIMENTAL, SODIUM),
            off(CLIENT, Group.FLYING, "far.atmosphere", RESTART, EXPERIMENTAL, SODIUM),
            on(CLIENT, Group.FLYING, "far.cruiseSmooth", RESTART),
            // client file, sky
            on(CLIENT, Group.SKY, "far.plainDepth", RESTART, SODIUM),
            // client file, nearby blocks
            on(CLIENT, Group.NEARBY_BLOCKS, "cubes.caveCull", RESTART, SODIUM),
            on(CLIENT, Group.NEARBY_BLOCKS, "cubes.caveLine", RESTART),
            off(CLIENT, Group.NEARBY_BLOCKS, "cubes.occlusion", RESTART, EXPERIMENTAL, SODIUM),
            on(CLIENT, Group.NEARBY_BLOCKS, "cubes.shadowCull", RESTART, SODIUM),
            on(CLIENT, Group.NEARBY_BLOCKS, "cubes.shadowCaves", RESTART, SODIUM),
            off(CLIENT, Group.NEARBY_BLOCKS, "cubes.skipInterior", RESTART, EXPERIMENTAL, SODIUM),
            off(CLIENT, Group.NEARBY_BLOCKS, "cubes.reuseSnapshots", RESTART, EXPERIMENTAL, SODIUM),
            // client file, compatibility
            on(CLIENT, Group.COMPATIBILITY, "far.lodDepth", RESTART, SODIUM),
            on(CLIENT, Group.COMPATIBILITY, "far.dhPacks", RESTART, SODIUM),
            on(CLIENT, Group.COMPATIBILITY, "far.hiddenCull", RESTART, SODIUM),
            off(CLIENT, Group.COMPATIBILITY, "far.twoPhase", RESTART, EXPERIMENTAL, SODIUM),
            off(CLIENT, Group.COMPATIBILITY, "far.queryCull", RESTART, EXPERIMENTAL, SODIUM),
            on(CLIENT, Group.COMPATIBILITY, "far.skipNear", RESTART),
            on(CLIENT, Group.COMPATIBILITY, "far.replayPrepass", RESTART),
            // client file, speedups
            on(CLIENT, Group.SPEEDUPS, "far.firstMeshOrder", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.meshQueue", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.fastWater", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.trackBytes", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.cachePick", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.sharedWater", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.batchWater", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.batchStamps", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.linkColumns", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.reuseDraws", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.viewMargin", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.reuseWalk", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.quietMeshes", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.coarseWalks", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.sentInPlace", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.partialWalks", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.movePatches", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.maskChanges", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.reuseOrder", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.indexed", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.indexedAllocator", RESTART),
            off(CLIENT, Group.SPEEDUPS, "far.splitRanges", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.cacheCoverage", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.runMesher", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.recentLooks", RESTART),
            on(CLIENT, Group.SPEEDUPS, "far.cullTree", RESTART),
            on(CLIENT, Group.SPEEDUPS, "cubes.skipBuried", RESTART),
            on(CLIENT, Group.SPEEDUPS, "cubes.reusePose", RESTART),
            on(CLIENT, Group.SPEEDUPS, "cubes.occlusionFront", RESTART),
            on(CLIENT, Group.SPEEDUPS, "cubes.regions", RESTART),
            on(CLIENT, Group.SPEEDUPS, "cubic.lightmapRing", RESTART),
            // client file, diagnostics
            off(CLIENT, Group.DIAGNOSTICS, "far.checkWalk", RESTART, DIAGNOSTIC),
            off(CLIENT, Group.DIAGNOSTICS, "far.checkMask", RESTART, DIAGNOSTIC),
            off(CLIENT, Group.DIAGNOSTICS, "cubes.checkMesh", RESTART, DIAGNOSTIC),
            off(CLIENT, Group.DIAGNOSTICS, "far.gpuTimers", RESTART, DIAGNOSTIC),
            off(CLIENT, Group.DIAGNOSTICS, "far.queryCull.debug", RESTART, DIAGNOSTIC),
            off(CLIENT, Group.DIAGNOSTICS, "cubic.perf", RESTART, DIAGNOSTIC),
            on(CLIENT, Group.DIAGNOSTICS, "perfLog", RESTART, DIAGNOSTIC),
            // common file, world data
            on(COMMON, Group.WORLD_DATA, "earth.canopy", NEW_WORLDS),
            on(COMMON, Group.WORLD_DATA, "earth.maps", NEW_WORLDS),
            on(COMMON, Group.WORLD_DATA, "earth.realms", NEW_WORLDS),
            // common file, far terrain
            on(COMMON, Group.FAR_TERRAIN, "far.previews", RESTART),
            // common file, flying
            off(COMMON, Group.FLYING, "cubic.flightSurface", RESTART, EXPERIMENTAL),
            // common file, nearby blocks
            on(COMMON, Group.NEARBY_BLOCKS, "view.order", RESTART),
            off(COMMON, Group.NEARBY_BLOCKS, "cubic.buried", RESTART),
            // common file, speedups
            on(COMMON, Group.SPEEDUPS, "rivers.fastSample", RESTART),
            on(COMMON, Group.SPEEDUPS, "cogs.fastRead", RESTART),
            on(COMMON, Group.SPEEDUPS, "climate.rowChunks", RESTART),
            on(COMMON, Group.SPEEDUPS, "copernicus.fastWeight", RESTART),
            off(COMMON, Group.SPEEDUPS, "earth.everyLayer", RESTART),
            on(COMMON, Group.SPEEDUPS, "cubes.fastBlobs", RESTART),
            on(COMMON, Group.SPEEDUPS, "cubes.plainRandom", RESTART),
            on(COMMON, Group.SPEEDUPS, "cubes.fastLight", RESTART),
            on(COMMON, Group.SPEEDUPS, "far.fastReach", RESTART),
            on(COMMON, Group.SPEEDUPS, "far.fastDrowned", RESTART),
            on(COMMON, Group.SPEEDUPS, "far.giveWay", RESTART),
            on(COMMON, Group.SPEEDUPS, "far.surfacePackets", RESTART),
            // common file, diagnostics
            off(COMMON, Group.DIAGNOSTICS, "earth.prepareLog", RESTART, DIAGNOSTIC),
            off(COMMON, Group.DIAGNOSTICS, "far.checkPackets", RESTART, DIAGNOSTIC),
            off(COMMON, Group.DIAGNOSTICS, "cubic.trace", RESTART, DIAGNOSTIC)
    );

    /** The command-line value of each toggle's property, taken before this class sets any: absent means the file decides. */
    private static final Map<String, String> COMMAND_LINE = commandLine(ALL, System.getProperties());

    private static boolean preloaded;

    private EngineToggles() {}

    /** The toggles' properties given on the command line. */
    static Map<String, String> commandLine() { return COMMAND_LINE; }

    static Map<String, String> commandLine(List<Toggle> toggles, Properties properties) {
        Map<String, String> given = new LinkedHashMap<>();
        for (Toggle toggle : toggles) {
            String value = properties.getProperty(toggle.systemProperty());
            if (value != null) given.put(toggle.systemProperty(), value);
        }
        return Map.copyOf(given);
    }

    /** The toggles of one settings file, in the order the screen lists them. */
    public static List<Toggle> of(Side side) {
        return ALL.stream().filter(toggle -> toggle.side() == side).toList();
    }

    /** The toggles of one group of one settings file. */
    public static List<Toggle> of(Side side, Group group) {
        return ALL.stream().filter(toggle -> toggle.side() == side && toggle.group() == group).toList();
    }

    /**
     * Sets the properties of one side's toggles from their settings: a toggle at its default clears its
     * property (so the engine runs as it always did), another value sets it, and a property given on the
     * command line is left alone. With {@code startup} false, toggles read once at class initialization
     * are left alone, since changing their property mid-session could only split the engine's classes
     * between two values. Returns the read-once toggles whose setting differs from the property at
     * startup, which means the settings file was read too late for them (or changed since
     * {@link #preload}); with {@code keepEarly} they are reported only and the property stays as
     * the early read left it, so no class can end up with a different value from another.
     */
    static List<Toggle> apply(Properties properties, Map<String, String> commandLine, List<Toggle> toggles,
                              Function<Toggle, Boolean> settings, boolean startup, boolean keepEarly) {
        List<Toggle> late = new ArrayList<>();
        for (Toggle toggle : toggles) {
            String property = toggle.systemProperty();
            if (commandLine.containsKey(property)) continue;
            if (!startup && toggle.when() == When.RESTART) continue;
            Boolean setting = settings.apply(toggle);
            boolean value = setting != null ? setting : toggle.defaultValue();
            String wanted = value == toggle.defaultValue() ? null : Boolean.toString(value);
            if (Objects.equals(properties.getProperty(property), wanted)) continue;
            if (startup && toggle.when() == When.RESTART) {
                late.add(toggle);
                if (keepEarly) continue;
            }
            if (wanted == null) properties.remove(property); else properties.setProperty(property, wanted);
        }
        return late;
    }

    /**
     * Reads the settings files in {@code configDir} and sets the properties, before any class that reads
     * them can initialize. Called first thing from each loader's entry point; a missing or unreadable
     * file leaves the defaults, and the loader's own config load reports a broken one.
     */
    public static void preload(Path configDir) {
        preload(configDir, System.getProperties(), COMMAND_LINE, EarthPlatformMod.LOGGER::info);
    }

    static synchronized void preload(Path configDir, Properties properties, Map<String, String> commandLine, Consumer<String> log) {
        for (Side side : Side.values()) {
            Map<String, Boolean> file = readBooleans(configDir.resolve(side.file));
            apply(properties, commandLine, of(side), toggle -> file.get(toggle.group().key + "." + toggle.key()), true, false);
        }
        preloaded = true;
        List<String> changed = new ArrayList<>();
        List<String> overridden = new ArrayList<>();
        for (Toggle toggle : ALL) {
            String value = properties.getProperty(toggle.systemProperty());
            if (commandLine.containsKey(toggle.systemProperty())) overridden.add(toggle.key() + "=" + commandLine.get(toggle.systemProperty()));
            else if (value != null) changed.add(toggle.key() + "=" + value);
        }
        log.accept("Earth Platform engine settings read before the game's classes load: " + changed.size() + " of " + ALL.size() + " differ from the defaults"
                + (changed.isEmpty() ? "" : " (" + String.join(", ", changed) + ")"));
        if (!overridden.isEmpty()) log.accept("Earth Platform: command-line values win over the settings files for " + String.join(", ", overridden));
    }

    /** Whether {@link #preload} has run, so the loader's own config load only has to confirm it. */
    static boolean preloaded() { return preloaded; }

    /**
     * Warns of read-once toggles whose setting differs from what the game started with: after
     * {@link #preload} the early value stands, and the new one applies at the next start.
     */
    static void warnLate(List<Toggle> late, boolean preloaded, Consumer<String> warn) {
        if (late.isEmpty()) return;
        List<String> names = late.stream().map(Toggle::key).toList();
        warn.accept(preloaded
                ? "Earth Platform settings differ from what the game read at start (" + String.join(", ", names) + "); they apply after a restart"
                : "Earth Platform settings were read late (" + String.join(", ", names) + "); restart the game if they seem to do nothing");
    }

    /**
     * The on/off values of a settings file, as {@code group.key} to value, for the tables and keys of
     * the form the game writes. Anything else in the file, and a file that is not there, reads as nothing.
     */
    static Map<String, Boolean> readBooleans(Path file) {
        Map<String, Boolean> found = new HashMap<>();
        if (!Files.isRegularFile(file)) return found;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String table = "";   // null inside an array of tables, whose keys are not toggles
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) continue;
                if (text.startsWith("[")) {
                    int end = text.indexOf(']');
                    table = text.startsWith("[[") || end < 0 ? null : text.substring(1, end).strip();
                    continue;
                }
                if (table == null) continue;
                int equals = text.indexOf('=');
                if (equals < 0) continue;
                String value = text.substring(equals + 1).strip();
                int comment = value.indexOf('#');
                if (comment >= 0 && !value.startsWith("\"")) value = value.substring(0, comment).strip();
                if (!value.equals("true") && !value.equals("false")) continue;
                String name = text.substring(0, equals).strip();
                found.put(table.isEmpty() ? name : table + "." + name, Boolean.parseBoolean(value));
            }
        } catch (IOException unreadable) {
            EarthPlatformMod.LOGGER.debug("Could not read {} early; the defaults stand until it loads", file, unreadable);
        }
        return found;
    }
}
