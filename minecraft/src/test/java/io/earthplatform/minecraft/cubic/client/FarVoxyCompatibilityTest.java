package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11C.GL_NONE;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT1;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class FarVoxyCompatibilityTest {
    private static final String HEADER = "#version 330 core\n#define EARTH_INDEXED\n";
    // The one-output Photon shape, the two-output Complementary shape shared by both variants,
    // and MakeUp's two-output shape with the legacy built-in and implicit lightmap.
    private static final String PHOTON = """
            layout(location = 0) out vec4 color;
            void voxy_emitFragment(VoxyFragmentParameters p) {
                color = p.sampledColour;
            }
            """;
    private static final String COMPLEMENTARY = """
            layout(location = 0) out vec4 color;
            layout(location = 1) out vec4 material;
            void voxy_emitFragment(VoxyFragmentParameters p) {
                color = p.sampledColour;
                material = vec4(p.lightMap, 0.0, 1.0);
            }
            """;
    private static final String MAKEUP = """
            layout(location = 0) out vec4 color;
            layout(location = 1) out vec4 material;
            void voxy_emitFragment(VoxyFragmentParameters p) {
                color = texture2D(lightmap, p.lightMap);
                material = p.sampledColour;
            }
            """;

    @Test void suppliesTheGameLightmapAndUsesCoreTextureCalls() {
        for (String source : List.of(FarShaders.packFragment(HEADER, MAKEUP, true),
                FarShaders.packNearFragment(HEADER, MAKEUP, FarGpu.NO_FADE),
                FarShaders.packShadeFragment(HEADER, MAKEUP, FarGpu.NO_FADE))) {
            assertTrue(source.contains("uniform sampler2D earth_Lightmap;"));
            assertTrue(source.contains("#define lightmap earth_Lightmap"));
            assertTrue(source.contains("color = texture(lightmap, p.lightMap);"));
            assertFalse(source.contains("texture2D("));
        }
        String withPackSampler = FarShaders.packFragment(HEADER, "uniform sampler2D lightmap;\n" + MAKEUP, true);
        assertTrue(withPackSampler.contains("uniform sampler2D lightmap;"));
        assertFalse(withPackSampler.contains("#define lightmap"));
        assertFalse(FarShaders.packFragment(HEADER, PHOTON, true).contains("#define lightmap"));
    }

    @Test void preservesThePacksLightmapAndTextureFunction() {
        String varying = "in vec2 lightmap;\n" + MAKEUP.replace("texture2D(lightmap, p.lightMap)", "vec4(lightmap, 0.0, 1.0)");
        assertFalse(FarShaders.packFragment(HEADER, varying, true).contains("uniform sampler2D earth_Lightmap;"));

        String alias = "#define lightmap earth_PackSampler\nuniform sampler2D earth_PackSampler;\n" + MAKEUP;
        assertFalse(FarShaders.packFragment(HEADER, alias, true).contains("uniform sampler2D earth_Lightmap;"));

        String custom = "vec4 texture2D(vec2 uv) { return vec4(uv, 0.0, 1.0); }\n"
                + MAKEUP.replace("texture2D(lightmap, p.lightMap)", "texture2D(p.lightMap)");
        String generated = FarShaders.packFragment(HEADER, custom, true);
        assertTrue(generated.contains("vec4 texture2D(vec2 uv)"));
        assertTrue(generated.contains("color = texture2D(p.lightMap);"));
        assertFalse(generated.contains("uniform sampler2D earth_Lightmap;"));
    }

    @Test void routesOpaqueOutputsAroundFaceDepthInEachPass() {
        var photon = FarShaders.voxyDrawBuffers("{\"opaqueDrawBuffers\": [1], \"translucentDrawBuffers\": [13,16]}");
        var complementary = FarShaders.voxyDrawBuffers("{\"opaqueDrawBuffers\": [0, /* material */ 6]}");
        var makeup = FarShaders.voxyDrawBuffers("{\"opaqueDrawBuffers\": [1, 4]}");
        assertEquals(List.of(1), photon);
        assertEquals(List.of(0, 6), complementary);
        assertEquals(List.of(1, 4), makeup);
        assertEquals(List.of(), FarShaders.voxyDrawBuffers("{\"opaqueDrawBuffers\": [0, 0]}"));
        assertEquals(List.of(), FarShaders.voxyDrawBuffers("{\"opaqueDrawBuffers\": [0, -1]}"));
        assertEquals(List.of(), FarShaders.voxyDrawBuffers(null));

        int photonFace = FarShaders.faceDepthLocation(PHOTON);
        assertArrayEquals(new int[] { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 },
                FarLod.drawBuffers(photon.size(), photonFace, 8, true, true));
        int outputs = complementary.size();
        int face = FarShaders.faceDepthLocation(COMPLEMENTARY);
        assertArrayEquals(new int[] { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT1 },
                FarLod.drawBuffers(outputs, face, 8, true, true));
        assertArrayEquals(new int[] { GL_NONE, GL_NONE, GL_COLOR_ATTACHMENT1 },
                FarLod.drawBuffers(outputs, face, 8, false, true));
        assertArrayEquals(new int[] { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT2, GL_NONE },
                FarLod.drawBuffers(outputs, face, 8, true, false));
        assertArrayEquals(new int[] { GL_COLOR_ATTACHMENT0, GL_NONE, GL_COLOR_ATTACHMENT1 },
                FarLod.drawBuffers(1, face, 8, true, true));
    }

    /** Set EARTH_GLSLANG to the standalone validator's path to compile without an OpenGL context. */
    @Test
    @EnabledIfEnvironmentVariable(named = "EARTH_GLSLANG", matches = ".+")
    void linksAllVoxyFixtureLayouts(@TempDir Path dir) throws Exception {
        String glslang = System.getenv("EARTH_GLSLANG");
        Path vertex = Files.writeString(dir.resolve("far.vert"), FarShaders.packVertex(HEADER, null));
        Path fragment = dir.resolve("far.frag");
        Path output = dir.resolve("glslang.log");
        for (String fixture : List.of(PHOTON, COMPLEMENTARY, MAKEUP)) {
            for (String source : List.of(FarShaders.packFragment(HEADER, fixture, true),
                    FarShaders.packNearFragment(HEADER, fixture, FarGpu.NO_FADE),
                    FarShaders.packPrepassFragment(HEADER, fixture, FarGpu.NO_FADE),
                    FarShaders.packShadeFragment(HEADER, fixture, FarGpu.NO_FADE))) {
                Files.writeString(fragment, source);
                Process validator = new ProcessBuilder(glslang, "-l", vertex.toString(), fragment.toString())
                        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if (!validator.waitFor(15, TimeUnit.SECONDS)) {
                    validator.destroyForcibly();
                    throw new AssertionError("glslang did not finish");
                }
                assertEquals(0, validator.exitValue(), "glslang failed for fixture:\n" + fixture + "\n" + Files.readString(output));
            }
        }
    }
}
