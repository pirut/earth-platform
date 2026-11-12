package io.earthplatform.minecraft.cubic.play.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CloudVolumeTest {
    @Test void qualityAndOpacityReadSafely() {
        assertEquals(16, CloudVolume.steps("Balanced"));
        assertEquals(24, CloudVolume.steps("high"));
        assertEquals(32, CloudVolume.steps("CINEMATIC"));
        assertEquals(24, CloudVolume.steps("nonsense"));
        assertEquals(.72f, CloudVolume.opacity("0.72"), 1e-6);
        assertEquals(.98f, CloudVolume.opacity("5"), 1e-6);
        assertEquals(.2f, CloudVolume.opacity("-1"), 1e-6);
        assertEquals(.72f, CloudVolume.opacity("lots"), 1e-6);
        assertEquals(.72f, CloudVolume.opacity("NaN"), 1e-6);
        assertEquals(.72f, CloudVolume.opacity("Infinity"), 1e-6);
    }

    /**
     * The game drops a listed uniform the program doesn't have, and never sets one the list leaves
     * out, so the shader's uniforms and samplers and the JSON's must be the same names.
     */
    @Test void shaderAndItsDefinitionNameTheSameUniforms() throws IOException {
        String json = read("earthplatform_weather.json"), fragment = read("earthplatform_weather.fsh"), vertex = read("earthplatform_weather.vsh");
        Set<String> listed = names(Pattern.compile("\"name\": \"(\\w+)\""), json);
        Set<String> declared = names(Pattern.compile("uniform \\w+ (\\w+);"), fragment + vertex);
        assertEquals(declared, listed);
        for (String name : declared)
            assertTrue(Pattern.compile("\\b" + name + "\\b").matcher(fragment + vertex).results().count() > 1, name + " is declared but not used");
    }

    private static Set<String> names(Pattern pattern, String text) {
        Set<String> names = new TreeSet<>();
        pattern.matcher(text).results().forEach(match -> names.add(match.group(1)));
        return names;
    }

    private static String read(String file) throws IOException {
        try (InputStream in = CloudVolumeTest.class.getResourceAsStream("/assets/earthplatform/shaders/core/" + file)) {
            assertNotNull(in, file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
