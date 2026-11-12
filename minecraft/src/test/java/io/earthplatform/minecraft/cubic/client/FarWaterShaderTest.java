package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FarWaterShaderTest {
    /** The game drops a listed uniform the program doesn't have and never sets one left out, so both must name the same ones. */
    @Test void shaderAndItsDefinitionNameTheSameUniforms() throws IOException {
        String json = read("earthplatform_water.json"), shaders = read("earthplatform_water.vsh") + read("earthplatform_water.fsh");
        assertEquals(names(Pattern.compile("uniform \\w+ (\\w+);"), shaders), names(Pattern.compile("\"name\": \"(\\w+)\""), json));
    }

    /** Apart from the curve and the depth bias, it is the game's translucent shader, whose uniforms the game sets. */
    @Test void itTakesTheTranslucentShadersVertexFormat() throws IOException {
        String vertex = read("earthplatform_water.vsh");
        for (String input : new String[] {"in vec3 Position;", "in vec4 Color;", "in vec2 UV0;", "in ivec2 UV2;"}) assertTrue(vertex.contains(input), input);
    }

    private static Set<String> names(Pattern pattern, String text) {
        Set<String> names = new TreeSet<>();
        pattern.matcher(text).results().forEach(match -> names.add(match.group(1)));
        return names;
    }

    private static String read(String file) throws IOException {
        try (InputStream in = FarWaterShaderTest.class.getResourceAsStream("/assets/earthplatform/shaders/core/" + file)) {
            assertNotNull(in, file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
