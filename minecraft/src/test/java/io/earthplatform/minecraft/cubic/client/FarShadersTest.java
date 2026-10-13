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

    /** Photon v1.3b's voxy_opaque.glsl normal, which comes from Voxy's own example. */
    private static final String PHOTON_NORMAL = """
            void voxy_emitFragment(VoxyFragmentParameters parameters) {
                vec3 normal = vec3(
                                  uint((parameters.face >> 1) == 2),
                                  uint((parameters.face >> 1) == 0),
                                  uint((parameters.face >> 1) == 1)
                              ) *
                    (float(int(parameters.face) & 1) * 2.0 - 1.0);
                gbuffer_data_0.z = pack_unorm_2x8(encode_unit_vector(normal));
            }
            """;

    @Test void theGroundSlopeTakesOverThePacksFaceNormal() {
        String source = FarShaders.packFragment("#version 330 core\n", PHOTON_NORMAL, false);
        int computed = source.indexOf("* 2.0 - 1.0);"), replaced = source.indexOf("normal = earth_packNormal(normal);");
        assertTrue(computed > 0 && replaced > computed, source);
        assertTrue(replaced < source.indexOf("encode_unit_vector(normal)"), source);
    }

    @Test void ownDepthKeepsFarTerrainBehindTheGameAndWritesItsFaceDepth() {
        String own = FarShaders.packFragment("#version 330 core\n", PHOTON_NORMAL, true);
        assertTrue(own.startsWith("#version 330 core\n") && own.contains("#define EARTH_OWN_DEPTH"), own);
        assertTrue(own.contains("earth_faceDepth = gl_FragCoord.z"), own);
        assertTrue(!FarShaders.packFragment("#version 330 core\n", PHOTON_NORMAL, false).contains("#define EARTH_OWN_DEPTH"));
    }

    @Test void packsWithAnotherNormalAreLeftAlone() {
        String patch = "void voxy_emitFragment(VoxyFragmentParameters parameters) { vec3 n = vec3(0.0, 1.0, 0.0); }\n";
        assertTrue(!FarShaders.packFragment("#version 330 core\n", patch, false).contains("= earth_packNormal("));
    }

    @Test void flatGroundPointsStraightUp() {
        assertEquals(32 | 32 << 6, FarMesher.normalCode(0.0F, 0.0F));
        int steep = FarMesher.normalCode(1.0F, 0.0F);
        assertEquals(32 - 22, steep & 63, "a 45 degree slope rising east leans west");
        assertEquals(32, steep >> 6);
        assertTrue(FarMesher.normalCode(1000.0F, -1000.0F) != 0, "0 stays free for the face's own normal");
    }
}
