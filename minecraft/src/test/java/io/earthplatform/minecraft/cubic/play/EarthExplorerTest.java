package io.earthplatform.minecraft.cubic.play;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The Earth tab's files have to match how {@link EarthExplorer} awards them, by name. */
class EarthExplorerTest {
    private static final Map<String, Integer> COUNTED = Map.of("globetrotter", EarthExplorer.GLOBETROTTER,
            "world_traveler", EarthExplorer.WORLD_TRAVELER, "around_the_world", EarthExplorer.ROUND_STEPS);

    private static Map<String, JsonObject> tab() throws Exception {
        String project = System.getProperty("earthplatform.test.projectDirectory");
        assertNotNull(project, "Explicit test project directory is required");
        Map<String, JsonObject> tab = new TreeMap<>();
        try (var files = Files.list(Path.of(project, "src/main/resources/data/earthplatform/advancement/earth"))) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".json")) tab.put(name.substring(0, name.length() - 5), JsonParser.parseString(Files.readString(file)).getAsJsonObject());
            }
        }
        return tab;
    }

    @Test void everyAdvancementHangsOffTheRoot() throws Exception {
        Map<String, JsonObject> tab = tab();
        assertTrue(tab.containsKey("root"));
        assertFalse(tab.get("root").has("parent"));
        for (var entry : tab.entrySet()) {
            if (entry.getKey().equals("root")) continue;
            String parent = entry.getValue().get("parent").getAsString();
            assertTrue(parent.startsWith("earthplatform:earth/") && tab.containsKey(parent.substring("earthplatform:earth/".length())), entry.getKey() + " hangs off " + parent);
        }
    }

    @Test void criteriaAreNamedAsTheCodeAwardsThem() throws Exception {
        for (var entry : tab().entrySet()) {
            String name = entry.getKey();
            if (name.equals("root")) continue;
            JsonObject criteria = entry.getValue().getAsJsonObject("criteria");
            List<String> expected = new ArrayList<>();
            if (COUNTED.containsKey(name)) for (int i = 1; i <= COUNTED.get(name); i++) expected.add(Integer.toString(i));
            else expected.add(name);
            assertEquals(expected, List.copyOf(criteria.keySet()), name);
            for (String key : criteria.keySet()) assertEquals("minecraft:impossible", criteria.getAsJsonObject(key).get("trigger").getAsString(), name);
            assertEquals(expected.size(), entry.getValue().getAsJsonArray("requirements").size(), name + " needs every criterion");
        }
    }

    @Test void titlesHaveKeysAndEnglish() throws Exception {
        for (var entry : tab().entrySet()) {
            JsonObject display = entry.getValue().getAsJsonObject("display");
            for (String part : List.of("title", "description")) {
                JsonObject text = display.getAsJsonObject(part);
                assertEquals("advancements.earthplatform.earth." + entry.getKey() + "." + part, text.get("translate").getAsString());
                assertFalse(text.get("fallback").getAsString().isBlank());
            }
        }
    }

    @Test void stepsAcrossTheDateLineGoTheShortWay() {
        assertEquals(10, EarthExplorer.east(20, 30), 1e-9);
        assertEquals(-10, EarthExplorer.east(30, 20), 1e-9);
        assertEquals(0.2, EarthExplorer.east(179.9, -179.9), 1e-9);
        assertEquals(-0.2, EarthExplorer.east(-179.9, 179.9), 1e-9);
    }
}
