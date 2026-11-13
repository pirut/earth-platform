package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.earthplatform.minecraft.cubic.play.EngineToggles.Group;
import io.earthplatform.minecraft.cubic.play.EngineToggles.Side;
import io.earthplatform.minecraft.cubic.play.EngineToggles.Toggle;
import io.earthplatform.minecraft.cubic.play.EngineToggles.When;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTogglesTest {
    private static final String ELEVATION_LAYER = "a command-line flag only: BareEarth, which every world's heights are read through, is one process-wide "
            + "object built from the current flags rather than the world's own earth.elevation pin, so a setting could not keep existing worlds' layers";
    private static final Toggle OFF = new Toggle(Side.CLIENT, Group.FAR_TERRAIN, "far.smooth", false, When.RESTART, List.of());
    private static final Toggle ON = new Toggle(Side.CLIENT, Group.FAR_TERRAIN, "far.fade", true, When.RESTART, List.of());
    private static final Toggle WORLDS = new Toggle(Side.COMMON, Group.WORLD_DATA, "earth.canopy", true, When.NEW_WORLDS, List.of());
    private static final List<Toggle> THREE = List.of(OFF, ON, WORLDS);

    // Precedence, through the code that ships: apply and preload ------------------------------------

    @Test void settingsBecomePropertiesAndDefaultsLeaveThemUnset() {
        Properties properties = new Properties();
        Map<Toggle, Boolean> settings = new HashMap<>(Map.of(OFF, true, ON, false, WORLDS, true));
        EngineToggles.apply(properties, Map.of(), THREE, settings::get, true, false);
        assertEquals("true", properties.getProperty("earthplatform.far.smooth"), "off by default, set on");
        assertEquals("false", properties.getProperty("earthplatform.far.fade"), "on by default, set off");
        assertNull(properties.getProperty("earthplatform.earth.canopy"), "a toggle at its default leaves its property unset");

        settings.put(OFF, false);
        EngineToggles.apply(properties, Map.of(), THREE, settings::get, true, false);
        assertNull(properties.getProperty("earthplatform.far.smooth"), "back at its default, the property goes again");
    }

    @Test void aToggleWithNoSettingStandsAtItsDefault() {
        Properties properties = new Properties();
        EngineToggles.apply(properties, Map.of(), THREE, toggle -> null, true, false);
        assertTrue(properties.isEmpty(), "nothing set means nothing differs from before");
    }

    @Test void aCommandLinePropertyIsNeverTouched() {
        Properties properties = new Properties();
        properties.setProperty("earthplatform.far.smooth", "true");
        properties.setProperty("earthplatform.earth.canopy", "false");
        Map<String, String> commandLine = EngineToggles.commandLine(THREE, properties);
        assertEquals(Map.of("earthplatform.far.smooth", "true", "earthplatform.earth.canopy", "false"), commandLine);

        Map<Toggle, Boolean> settings = Map.of(OFF, false, ON, false, WORLDS, true);
        EngineToggles.apply(properties, commandLine, THREE, settings::get, true, false);
        EngineToggles.apply(properties, commandLine, THREE, settings::get, false, false);
        assertEquals("true", properties.getProperty("earthplatform.far.smooth"), "the file says off; the command line still on");
        assertEquals("false", properties.getProperty("earthplatform.earth.canopy"), "the file says on; the command line still off");
        assertEquals("false", properties.getProperty("earthplatform.far.fade"), "the one with no command line follows the file");
    }

    @Test void onlyTheNewWorldTogglesChangeAfterStartup() {
        Properties properties = new Properties();
        Map<Toggle, Boolean> settings = new HashMap<>(Map.of(OFF, false, ON, true, WORLDS, true));
        EngineToggles.apply(properties, Map.of(), THREE, settings::get, true, false);

        settings.put(OFF, true);
        settings.put(WORLDS, false);
        EngineToggles.apply(properties, Map.of(), THREE, settings::get, false, false);
        assertNull(properties.getProperty("earthplatform.far.smooth"), "read once at class initialization: the next start");
        assertEquals("false", properties.getProperty("earthplatform.earth.canopy"), "read when a world is made: at once");
    }

    @Test void aRestartToggleThatChangesAtStartupAfterThePreloadIsReportedAndKeepsTheEarlyValue() {
        Properties properties = new Properties();
        Map<Toggle, Boolean> settings = Map.of(OFF, true, ON, true, WORLDS, false);
        List<Toggle> late = EngineToggles.apply(properties, Map.of(), THREE, settings::get, true, true);
        assertEquals(List.of(OFF), late, "only a read-once toggle can be too late; the new-world one is read afterwards, and ON is at its default");
        assertNull(properties.getProperty("earthplatform.far.smooth"), "after the early read its value stands, so no class sees two");
        assertEquals("false", properties.getProperty("earthplatform.earth.canopy"), "a new-world toggle still follows the file");
        List<String> warnings = new ArrayList<>();
        EngineToggles.warnLate(late, true, warnings::add);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("farSmooth") && warnings.get(0).contains("after a restart"), warnings.get(0));
    }

    @Test void withoutAnEarlyReadTheLoadersReadStillAppliesAndIsReportedLate() {
        Properties properties = new Properties();
        Map<Toggle, Boolean> settings = Map.of(OFF, true);
        List<Toggle> late = EngineToggles.apply(properties, Map.of(), THREE, settings::get, true, false);
        assertEquals(List.of(OFF), late);
        assertEquals("true", properties.getProperty("earthplatform.far.smooth"), "best effort when nothing read the file early");
        List<String> warnings = new ArrayList<>();
        EngineToggles.warnLate(late, false, warnings::add);
        assertTrue(warnings.get(0).contains("read late"), warnings.get(0));
        warnings.clear();
        EngineToggles.warnLate(List.of(), false, warnings::add);
        assertTrue(warnings.isEmpty());
    }

    // The early read of the settings files -------------------------------------------------------

    @Test void thePreloadReadsBothFilesAndTheCommandLineStillWins(@TempDir Path config) throws IOException {
        Files.writeString(config.resolve("earthplatform-client.toml"), String.join("\n",
                "# comment", "cloudHeight = 2500", "", "[farTerrain]", "\t#Same as -Dx", "\tfarSmooth = true # trailing", "\tfarFade = false", "\tfarZoom = true", "",
                "[nearbyBlocks]", "\tcubesCaveCull = false", ""));
        Files.writeString(config.resolve("earthplatform-common.toml"), "[worldData]\n\tearthCanopy = false\n");
        Properties properties = new Properties();
        properties.setProperty("earthplatform.far.fade", "true");   // on the command line
        Map<String, String> commandLine = EngineToggles.commandLine(EngineToggles.ALL, properties);

        List<String> log = new ArrayList<>();
        EngineToggles.preload(config, properties, commandLine, log::add);

        assertEquals("true", properties.getProperty("earthplatform.far.smooth"));
        assertEquals("true", properties.getProperty("earthplatform.far.fade"), "the file says false, the command line true");
        assertEquals("false", properties.getProperty("earthplatform.cubes.caveCull"));
        assertEquals("false", properties.getProperty("earthplatform.earth.canopy"));
        assertNull(properties.getProperty("earthplatform.far.zoom"), "in the file at its default: nothing is set");
        assertNull(properties.getProperty("earthplatform.far.cruiseSmooth"), "not in the file: its default");
        assertTrue(log.stream().anyMatch(line -> line.contains("3 of") && line.contains("farSmooth=true") && line.contains("cubesCaveCull=false")), log.toString());
        assertTrue(log.stream().anyMatch(line -> line.contains("farFade=true")), "the command line's win is said: " + log);
        assertTrue(EngineToggles.preloaded());
    }

    @Test void aMissingFileLeavesEverythingAtItsDefault(@TempDir Path config) {
        Properties properties = new Properties();
        List<String> log = new ArrayList<>();
        EngineToggles.preload(config, properties, Map.of(), log::add);
        assertTrue(properties.isEmpty());
        assertEquals(1, log.size(), "one line, saying nothing differs: " + log);
        assertTrue(log.get(0).contains("0 of " + EngineToggles.ALL.size() + " differ"), log.get(0));
    }

    @Test void theFileReaderTakesTablesAndComments(@TempDir Path config) throws IOException {
        Path file = config.resolve("settings.toml");
        Files.writeString(file, String.join("\n", "top = true", "name = \"a # b\"", "count = 5", "[[list]]", "x = true", "[a]", "b = false", "[c.d]", "e = true # yes", "not a line", ""));
        Map<String, Boolean> read = EngineToggles.readBooleans(file);
        assertEquals(Map.of("top", true, "a.b", false, "c.d.e", true), read);
        assertTrue(EngineToggles.readBooleans(config.resolve("absent.toml")).isEmpty());
    }

    // Every toggle ---------------------------------------------------------------------------------

    @Test void everyToggleHasASettingWithTheFlagsDefault() {
        Set<String> seen = new HashSet<>();
        for (Toggle toggle : EngineToggles.ALL) {
            assertTrue(seen.add(toggle.key()), "two toggles share the name " + toggle.key());
            var setting = EarthConfig.toggle(toggle);
            assertNotNull(setting, toggle.property() + " has no setting");
            assertEquals(List.of(toggle.group().key, toggle.key()), setting.getPath(), toggle.property());
            assertEquals(toggle.defaultValue(), setting.getDefault(), toggle.property() + ": the setting's default is the flag's");
            assertEquals(toggle.langKey(), setting.getSpec().getTranslationKey(), toggle.property());
            boolean restart = setting.getSpec().restartType() == net.neoforged.neoforge.common.ModConfigSpec.RestartType.GAME;
            assertEquals(toggle.when() == When.RESTART, restart, toggle.property() + ": a restart toggle is a game-restart setting");
        }
    }

    @Test void everyToggleIsNamedAndDescribedInTheLanguageFile() throws IOException {
        JsonObject lang = lang();
        for (Group group : Group.values()) {
            assertEquals(group.label, string(lang, "earthplatform.configuration.group." + group.key), "the heading of " + group);
            string(lang, "earthplatform.configuration.group." + group.key + ".tooltip");
        }
        for (Toggle toggle : EngineToggles.ALL) {
            String name = string(lang, toggle.langKey());
            String text = string(lang, toggle.langKey() + ".tooltip");
            assertTrue(text.length() > 20, toggle.property() + " needs a real description");
            assertEquals(toggle.experimental(), name.toLowerCase().contains("experimental"), toggle.property() + ": experimental ones say so in the name, and only those");
            if (toggle.when() == When.RESTART) assertTrue(text.endsWith("Restart the game to apply."), toggle.property() + " must say it needs a restart");
            else assertTrue(text.contains("new world") || text.contains("worlds you make"), toggle.property() + " must say it applies to new worlds");
        }
    }

    @Test void theSodiumPageCarriesFeaturesAndNoDiagnostics() throws IOException {
        JsonObject lang = lang();
        for (String key : List.of("flying", "nearbyBlocks", "compatibility")) string(lang, "earthplatform.sodium." + key);
        for (Toggle toggle : EngineToggles.ALL) {
            if (toggle.diagnostic()) {
                assertFalse(toggle.sodium(), toggle.property() + " is a diagnostic and stays off Sodium's page");
                assertEquals(Group.DIAGNOSTICS, toggle.group(), toggle.property() + " belongs in the Diagnostics group");
            }
            if (toggle.sodium()) assertEquals(Side.CLIENT, toggle.side(), toggle.property() + ": Sodium's page edits the client file");
            if (toggle.experimental()) assertFalse(toggle.defaultValue(), toggle.property() + " is experimental, so off by default");
        }
        assertEquals(Set.of("far.checkWalk", "far.checkMask", "cubes.checkMesh", "far.checkPackets", "far.gpuTimers", "earth.prepareLog",
                        "far.queryCull.debug", "cubic.trace", "cubic.perf", "perfLog"),
                EngineToggles.ALL.stream().filter(Toggle::diagnostic).map(Toggle::property).collect(java.util.stream.Collectors.toSet()));
    }

    // The defaults against the doc and the code that reads each flag -------------------------------

    private static final Pattern OFF_ONLY_FALSE = Pattern.compile("!\"false\"\\.equals\\(System\\.getProperty\\(\"earthplatform\\.([A-Za-z0-9.]+)\"\\)\\)");
    private static final Pattern ON_ONLY_TRUE = Pattern.compile("\"true\"\\.equals\\(System\\.getProperty\\(\"earthplatform\\.([A-Za-z0-9.]+)\"\\)\\)");
    private static final Pattern GET_BOOLEAN = Pattern.compile("Boolean\\.getBoolean\\(\"earthplatform\\.([A-Za-z0-9.]+)\"\\)");
    private static final Pattern PARSE = Pattern.compile("Boolean\\.parseBoolean\\(System\\.getProperty\\(\"earthplatform\\.([A-Za-z0-9.]+)\",\\s*\"(true|false)\"\\)\\)");

    /** The properties read as on/off flags in the sources, with the default each read gives. */
    private static Map<String, Set<Boolean>> readsInTheSources() throws IOException {
        Map<String, Set<Boolean>> reads = new HashMap<>();
        for (Path root : List.of(repo().resolve("minecraft/src/main/java"), repo().resolve("minecraft/src/neoforge/java"),
                repo().resolve("fabric/src/main/java"), repo().resolve("geography/src/main/java"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    collect(reads, OFF_ONLY_FALSE, source, 1, true);
                    collect(reads, ON_ONLY_TRUE, source, 1, false);
                    collect(reads, GET_BOOLEAN, source, 1, false);
                    Matcher matcher = PARSE.matcher(source);
                    while (matcher.find()) reads.computeIfAbsent(matcher.group(1), key -> new HashSet<>()).add(Boolean.parseBoolean(matcher.group(2)));
                }
            }
        }
        return reads;
    }

    private static void collect(Map<String, Set<Boolean>> reads, Pattern pattern, String source, int group, boolean defaultValue) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) reads.computeIfAbsent(matcher.group(group), key -> new HashSet<>()).add(defaultValue);
    }

    @Test void everyToggleDefaultIsWhatTheCodeThatReadsItDoes() throws IOException {
        Map<String, Set<Boolean>> reads = readsInTheSources();
        for (Toggle toggle : EngineToggles.ALL) {
            Set<Boolean> defaults = reads.get(toggle.property());
            assertNotNull(defaults, toggle.property() + " is read nowhere as an on/off flag");
            assertEquals(Set.of(toggle.defaultValue()), defaults, toggle.property() + ": the code's default and the toggle's differ");
        }
    }

    /** On/off flags that are deliberately not settings, each with the reason. */
    private static final Map<String, String> NOT_SETTINGS = Map.of(
            "clouds.layered", "already a setting of its own (layeredClouds), with its own screens: see EarthConfig",
            "earth.offline", "already a setting of its own (offline): see EarthConfig",
            "earth.imagery", "already a setting of its own (satelliteImagery): see EarthConfig",
            "earth.copernicus", ELEVATION_LAYER,
            "earth.3dep", ELEVATION_LAYER,
            "earth.hrdem", ELEVATION_LAYER,
            "earth.rema", ELEVATION_LAYER,
            "earth.ign", ELEVATION_LAYER,
            "earth.gsi", ELEVATION_LAYER);

    @Test void everyOnOffFlagInTheCodeHasAToggleOrAReasonItHasNone() throws IOException {
        Set<String> missing = new TreeSet<>(readsInTheSources().keySet());
        missing.removeAll(NOT_SETTINGS.keySet());
        EngineToggles.ALL.forEach(toggle -> missing.remove(toggle.property()));
        assertTrue(missing.isEmpty(), "on/off flags with no toggle (add them to EngineToggles.ALL, the language file and docs/FLAGS.md, or to NOT_SETTINGS with the reason): " + missing);
        for (String property : NOT_SETTINGS.keySet())
            assertTrue(EngineToggles.ALL.stream().noneMatch(toggle -> toggle.property().equals(property)), property + " is listed as not a setting, but has a toggle");
    }

    @Test void theElevationLayersStayCommandLineFlagsAndTheOtherNewWorldDataIsPerWorld() {
        assertEquals(Set.of("earth.canopy", "earth.maps", "earth.realms"),
                EngineToggles.of(Side.COMMON, Group.WORLD_DATA).stream().map(Toggle::property).collect(java.util.stream.Collectors.toSet()));
        EngineToggles.of(Side.COMMON, Group.WORLD_DATA).forEach(toggle -> assertEquals(When.NEW_WORLDS, toggle.when(), toggle.property()));
    }

    @Test void flagsThatCannotWorkFromSettingsAloneAreNotOnSodiumsPage() {
        EngineToggles.ALL.stream().filter(toggle -> toggle.property().equals("far.cruiseSmooth")).forEach(toggle -> assertFalse(toggle.sodium(),
                "far.cruiseSmooth needs -Dearthplatform.far.smoothFrom past 5, so it stays on the config screen"));
        assertEquals(Group.FAR_TERRAIN, EngineToggles.ALL.stream().filter(toggle -> toggle.property().equals("far.nearGround")).findAny().orElseThrow().group(),
                "far.nearGround changes what is drawn near the player, so it is not a speed-up");
    }

    @Test void flagsDocListsEveryToggleWithTheSameDefault() throws IOException {
        Map<String, String> defaults = new HashMap<>();
        Pattern row = Pattern.compile("^\\| `earthplatform\\.([A-Za-z0-9.]+)` \\| ([^|]*) \\|");
        for (String line : Files.readAllLines(repo().resolve("docs/FLAGS.md"), StandardCharsets.UTF_8)) {
            Matcher matcher = row.matcher(line);
            if (matcher.find()) defaults.put(matcher.group(1), matcher.group(2).strip().replace("`", ""));
        }
        for (Toggle toggle : EngineToggles.ALL) {
            String text = defaults.get(toggle.property());
            assertNotNull(text, toggle.property() + " is missing from docs/FLAGS.md");
            assertTrue(toggle.defaultValue() ? text.equals("on") || text.equals("true") : text.equals("off") || text.equals("false"),
                    toggle.property() + ": docs/FLAGS.md gives the default as '" + text + "'");
        }
    }

    // Helpers -------------------------------------------------------------------------------------

    private static Path repo() {
        Path here = Path.of("").toAbsolutePath();
        for (Path at = here; at != null; at = at.getParent()) {
            if (Files.isRegularFile(at.resolve("docs/FLAGS.md"))) return at;
        }
        throw new IllegalStateException("docs/FLAGS.md not found above " + here);
    }

    private static JsonObject lang() throws IOException {
        try (var in = EngineTogglesTest.class.getResourceAsStream("/assets/earthplatform/lang/en_us.json")) {
            assertNotNull(in, "the language file");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        }
    }

    private static String string(JsonObject lang, String key) {
        assertTrue(lang.has(key), "the language file has no " + key);
        String value = lang.get(key).getAsString();
        assertFalse(value.isBlank(), key + " is empty");
        return value;
    }
}
