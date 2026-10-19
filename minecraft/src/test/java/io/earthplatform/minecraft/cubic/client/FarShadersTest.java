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

    @Test void facesDepthTakesALocationThePackLeavesFree() {
        String photon = "layout(location = 0) out vec4 gbuffer_data_0;\n" + PHOTON_NORMAL;
        assertEquals(1, FarShaders.faceDepthLocation(photon));
        assertEquals(1, FarShaders.faceDepthLocation(null));
        // Complementary Reimagined r5.9.3 and TAA-Voxy 1.3 write a second (and third) target.
        assertEquals(2, FarShaders.faceDepthLocation("layout(location = 0) out vec4 gbufferData0;\nlayout(location = 1) out vec4 gbufferData6;\n"));
        assertEquals(3, FarShaders.faceDepthLocation(
                "layout(location = 0) out vec4 voxy_colour0;\nlayout(location = 1) out vec4 auxDataOut;\nlayout(location = 2) out vec4 voxyMarkerOut; // colortex9\n"));
        assertEquals(1, FarShaders.faceDepthLocation("out vec4 colour;\n"), "an output without a location takes 0");
        assertEquals(3, FarShaders.faceDepthLocation("out vec4 a;\nout vec4 b;\nlayout(location = 1) out vec4 c;\n"));
        assertEquals(2, FarShaders.faceDepthLocation("layout(location = 0) out vec4 data[2];\n"));
        assertEquals(1, FarShaders.faceDepthLocation("layout(location = 0) out vec4 a;\n// layout(location = 1) out vec4 b;\n/* layout(location = 1) out vec4 c; */\n"));
        assertEquals(1, FarShaders.faceDepthLocation("layout(location = 0) out vec4 a;\nvoid f(out vec3 n,\n        out float d) {}\n"));
        assertEquals(1, FarShaders.faceDepthLocation("layout (location=0) flat out uvec4 a;\nlayout(location=3) out vec4 b;\n"));

        String version = "#version 330 core\n";
        String own = FarShaders.packFragment(version, photon, true, FarGpu.NO_FADE);
        assertTrue(own.contains("layout(location = 1) out float earth_faceDepth;"), own);
        String complementary = "layout(location = 0) out vec4 gbufferData0;\nlayout(location = 1) out vec4 gbufferData6;\n" + PHOTON_NORMAL;
        for (String source : new String[] {FarShaders.packFragment(version, complementary, true, FarGpu.NO_FADE),
                FarShaders.packPrepassFragment(version, complementary, FarGpu.NO_FADE), FarShaders.packShadeFragment(version, complementary, FarGpu.NO_FADE)}) {
            assertTrue(source.contains("layout(location = 2) out float earth_faceDepth;") && !source.contains("layout(location = 1) out float earth_faceDepth;"), source);
        }
    }

    @Test void readsTheUniformsAndSamplersAVoxyPackAsksFor() {
        assertEquals(java.util.List.of("taa_offset"), FarShaders.voxyUniforms(PHOTON));
        String taaVoxy = """
                {
                	"version": 1,
                	"opaqueDrawBuffers": [0,1,9],
                	"uniforms": [
                		"framemod8",
                		"viewWidth",
                		"vxViewProjInv",
                "fogColor"
                	],
                	"samplers": {
                		"lightmap": "sampler2D",
                		"shadowtex0": "sampler2DShadow",
                		"colortex4": null
                	},
                	"taaOffset": "{ return vec2(0.0); }"
                }
                """;
        assertEquals(java.util.List.of("framemod8", "viewWidth", "vxViewProjInv", "fogColor"), FarShaders.voxyUniforms(taaVoxy));
        assertEquals(java.util.Map.of("lightmap", "sampler2D", "shadowtex0", "sampler2DShadow", "colortex4", "sampler2D"), FarShaders.voxySamplers(taaVoxy));
        assertEquals(java.util.Map.of("noisetex", "sampler2D", "depthtex0", "sampler2D"),
                FarShaders.voxySamplers("{\"samplers\": [\"noisetex\", \"depthtex0\"]}"));
        assertTrue(FarShaders.voxySamplers(null).isEmpty() && FarShaders.voxyUniforms("{}").isEmpty());
    }

    @Test void declaresOnlyWhatThePacksCodeReadsAndLeavesUndeclared() {
        java.util.Map<String, String> types = java.util.Map.of("viewWidth", "float", "vxViewProjInv", "mat4", "framemod8", "int", "gbufferModelView", "mat4");
        java.util.List<String> uniforms = java.util.List.of("framemod8", "viewWidth", "vxViewProjInv", "gbufferModelView", "fogColor");
        java.util.Map<String, String> samplers = new java.util.LinkedHashMap<>();
        samplers.put("lightmap", "sampler2D");
        samplers.put("noisetex", "sampler2D");
        // Photon's code reads none of its voxy.json's names: nothing is added.
        assertEquals("", FarShaders.voxyDeclarations("layout(location = 0) out vec4 gbuffer_data_0;\n" + PHOTON_NORMAL, uniforms, types, samplers));
        String code = """
                uniform float viewWidth; // its own
                vec3 at(vec2 p) { vec4 v = vxViewProjInv * vec4(p, 0.0, 1.0); return v.xyz / v.w + fogColor * 0.0; }
                void voxy_emitFragment(VoxyFragmentParameters parameters) {
                    vec3 c = texture(lightmap, parameters.lightMap).rgb * float(framemod8) / viewWidth; // noisetex is not read
                }
                """;
        String declared = FarShaders.voxyDeclarations(code, uniforms, types, samplers);
        assertEquals("uniform int framemod8;\nuniform mat4 vxViewProjInv;\nuniform sampler2D lightmap;\n", declared, "fogColor has no known type");
        assertEquals(java.util.Set.of("lightmap"), FarShaders.declaredSamplers(code, samplers));
    }

    @Test void depthPrepassOnlyForPacksThatKeepEveryFragmentAndItsDepth() {
        assertTrue(FarShaders.prepassSafe(PHOTON_NORMAL));
        assertTrue(!FarShaders.prepassSafe(null));
        assertTrue(!FarShaders.prepassSafe("void voxy_emitFragment(VoxyFragmentParameters p) { if (p.sampledColour.a < 0.1) discard; }"));
        assertTrue(!FarShaders.prepassSafe("void voxy_emitFragment(VoxyFragmentParameters p) { gl_FragDepth = 0.5; }"));
        assertTrue(!FarShaders.prepassSafe("layout(depth_greater) out float gl_FragDepth;"));
        assertTrue(!FarShaders.prepassSafe("void voxy_emitFragment(VoxyFragmentParameters p) { gl_SampleMask[0] = 1; }"));
        assertTrue(FarShaders.prepassSafe("float discarded; void voxy_emitFragment(VoxyFragmentParameters p) {}"));
    }

    @Test void prepassDecidesDepthAndShadingPassOnlyShades() {
        String version = "#version 330 core\n";
        String pre = FarShaders.packPrepassFragment(version, PHOTON_NORMAL, FarGpu.NO_FADE);
        assertTrue(pre.contains("#define EARTH_OWN_DEPTH") && pre.contains("earth_slopeDepth();") && pre.contains("earth_faceDepth = gl_FragCoord.z;"), pre);
        assertTrue(pre.contains("texelFetch(earth_GameDepth") && !pre.contains("voxy_emitFragment("), pre);
        String shade = FarShaders.packShadeFragment(version, PHOTON_NORMAL, FarGpu.NO_FADE);
        assertTrue(!shade.contains("gl_FragDepth"), shade);
        assertTrue(!shade.contains("earth_faceDepth ="), shade);
        String main = shade.substring(shade.lastIndexOf("void main() {"));
        assertTrue(!main.contains("discard") && main.contains("earth_sunShadow()") && main.contains("voxy_emitFragment(VoxyFragmentParameters("), main);
        assertTrue(shade.contains("normal = earth_packNormal(normal);"), shade);
        // Everything up to the pack's code is the one-pass program's, so the colour is worked out alike.
        String own = FarShaders.packFragment(version, PHOTON_NORMAL, true, FarGpu.FADE_IN);
        String faded = FarShaders.packShadeFragment(version, PHOTON_NORMAL, FarGpu.FADE_IN);
        int patchEnd = own.indexOf("encode_unit_vector(normal));");
        assertEquals(own.substring(0, patchEnd), faded.substring(0, patchEnd));
        assertTrue(faded.substring(faded.lastIndexOf("void main() {")).contains("earth_fade();"));
        assertTrue(FarShaders.packPrepassFragment(version, PHOTON_NORMAL, FarGpu.FADE_OUT).contains("#define EARTH_FADE 1"));
    }

    @Test void flatGroundPointsStraightUp() {
        assertEquals(32 | 32 << 6, FarMesher.normalCode(0.0F, 0.0F));
        int steep = FarMesher.normalCode(1.0F, 0.0F);
        assertEquals(32 - 22, steep & 63, "a 45 degree slope rising east leans west");
        assertEquals(32, steep >> 6);
        assertTrue(FarMesher.normalCode(1000.0F, -1000.0F) != 0, "0 stays free for the face's own normal");
    }
}
