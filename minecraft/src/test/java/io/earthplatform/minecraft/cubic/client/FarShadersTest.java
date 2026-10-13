package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarShadersTest {
    /** Photon v1.3b's voxy.json as Iris's preprocessor leaves it with TAA on: settings pasted in, quotes in comments. */
    private static final String PHOTON = """
            {
                "unusedString":
                    "
                        const float shadowDistance = 128.0; // [64.0 "far" 128.0]
                    ",
                "version": 1,
                "blending": {
                    -1 : "off",
                    0 : "ONE ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA"
                },
                "taaOffset": "{
                        return taa_offset * 0.66;
                }",
                "useViewportDims": true,
                "uniforms": ["taa_offset"]
            }
            """;

    @Test void readsThePacksTaaOffset() {
        String body = FarShaders.taaOffset(PHOTON);
        assertTrue(body.startsWith("{") && body.endsWith("}"), body);
        assertTrue(body.contains("return taa_offset * 0.66;"), body);
    }

    @Test void noJitterWithoutTaa() {
        assertNull(FarShaders.taaOffset("{\"version\": 1, \"uniforms\": []}"));
        assertNull(FarShaders.taaOffset(null));
    }

    @Test void jitterGoesBeforeTheVertexShader() {
        String source = FarShaders.packVertex("#version 330 core\n", "vec2 earth_taaOffset() { return vec2(0.0); }\n");
        assertTrue(source.startsWith("#version 330 core\n#define EARTH_JITTER\n"), source);
        assertTrue(source.indexOf("earth_taaOffset() {") < source.indexOf("void main()"));
        assertEquals("#version 330 core\n" + FarShaders.VERTEX, FarShaders.packVertex("#version 330 core\n", null));
    }
}
