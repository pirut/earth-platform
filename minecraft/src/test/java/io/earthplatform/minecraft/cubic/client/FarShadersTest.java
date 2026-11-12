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

    @Test void farTerrainUnderTheGamesBlocksDrawsOnlyWhereTheyAreNot() {
        String version = "#version 330 core\n";
        String near = FarShaders.packNearFragment(version, PHOTON_NORMAL, FarGpu.FADE_IN, true);
        assertTrue(near.contains("#define EARTH_NEAR") && near.contains(FarShaders.GROUND) && !near.contains("#define EARTH_OWN_DEPTH"), near);
        String main = near.substring(near.lastIndexOf("void main() {"));
        int ground = main.indexOf("earth_ground();");
        assertTrue(ground > main.indexOf("earth_fade();") && ground < main.indexOf("voxy_emitFragment("), main);
        assertTrue(FarShaders.packFragment(version, PHOTON_NORMAL, false, FarGpu.NO_FADE, true).contains(FarShaders.GROUND));
        // Without it the programs are as they were.
        assertEquals(FarShaders.packNearFragment(version, PHOTON_NORMAL, FarGpu.NO_FADE), FarShaders.packNearFragment(version, PHOTON_NORMAL, FarGpu.NO_FADE, false));
        assertTrue(!FarShaders.packFragment(version, PHOTON_NORMAL, true, FarGpu.NO_FADE).contains(FarShaders.GROUND));
        for (String plain : new String[] {FarShaders.FRAGMENT, FarShaders.PACK_FALLBACK}) {
            String body = plain.substring(plain.lastIndexOf("void main() {"));
            assertTrue(body.contains("#ifdef EARTH_GROUND\n    earth_ground();"), body);
        }
    }

    @Test void flatGroundPointsStraightUp() {
        assertEquals(32 | 32 << 6, FarMesher.normalCode(0.0F, 0.0F));
        int steep = FarMesher.normalCode(1.0F, 0.0F);
        assertEquals(32 - 22, steep & 63, "a 45 degree slope rising east leans west");
        assertEquals(32, steep >> 6);
        assertTrue(FarMesher.normalCode(1000.0F, -1000.0F) != 0, "0 stays free for the face's own normal");
    }

    /** A Distant Horizons terrain vertex shader in the form Iris's patcher leaves it. */
    private static final String DH_VERTEX = """
            #version 330 core
            #extension GL_ARB_explicit_attrib_location : enable
            uniform mat4 iris_ProjectionMatrix;
            uniform mat4 iris_ModelViewMatrix;
            in uvec4 vPosition;
            in vec4 iris_color;
            in uvec4 irisExtra;
            vec3 _vert_position;
            vec2 _vert_tex_light_coord;
            int dhMaterialId;
            vec4 _vert_color;
            vec3 _vert_normal;
            uniform vec3 modelOffset;
            void _vert_init() {
                uint meta = vPosition.a;
                if (meta > 0u) { _vert_position = vec3(vPosition.xyz); }
                _vert_color = iris_color;
            }
            vec4 getVertexPosition() { return vec4(modelOffset + _vert_position, 1.0); }
            out vec4 tint;
            void main() {
                _vert_init();
                tint = _vert_color;
                gl_Position = iris_ProjectionMatrix * iris_ModelViewMatrix * getVertexPosition();
            }
            """;

    private static final String DH_FRAGMENT = """
            #version 430 core
            in vec4 tint;
            layout (location = 0) out vec4 iris_FragData0;
            layout (location = 1) out vec4 iris_FragData1;
            layout (location = 2) out vec4 iris_FragData2;
            void main() {
                if (tint.a < 0.1) discard;
                iris_FragData0 = tint;
                iris_FragData1 = vec4(1.0);
                iris_FragData2 = vec4(0.0);
            }
            """;

    @Test void aDistantHorizonsProgramTakesFarFacesAsItsVertices() {
        FarShaders.PackPatch patch = FarShaders.dhPatch(DH_VERTEX, DH_FRAGMENT, 3);
        assertTrue(patch.dh());
        assertEquals(3, patch.outputs());
        // The faces' own depth goes after the pack's three outputs.
        assertEquals(3, patch.faceDepthLocation());
        String vertex = FarShaders.dhVertex("#version 330 core\n", patch);
        // Distant Horizons' own unpacking is gone, braces and all, and far terrain's stands in.
        assertTrue(!vertex.contains("vPosition.a") && !vertex.contains("meta > 0u"), vertex);
        assertTrue(vertex.contains("_vert_position = earth_dhPosition;") && vertex.contains("dhMaterialId = earth_dhMaterial;"), vertex);
        assertTrue(vertex.contains("vec4 getVertexPosition()"), vertex);
        // One main, which runs far terrain's then the pack's; extensions follow the version line.
        assertEquals(1, count(vertex, "void main()"));
        assertTrue(vertex.contains("void earth_main()") && vertex.contains("void earth_dhMain()"), vertex);
        assertTrue(vertex.startsWith("#version 330 core\n#extension GL_ARB_explicit_attrib_location : enable\n"), vertex);
        assertEquals(1, count(vertex, "#version"));
    }

    @Test void aDistantHorizonsFragmentShaderRunsAfterFarTerrainsOwnPart() {
        FarShaders.PackPatch patch = FarShaders.dhPatch(DH_VERTEX, DH_FRAGMENT, 3);
        String fragment = FarShaders.dhFragment("#version 330 core\n", patch, true, 0, false);
        // The pack asks for a newer GLSL than far terrain's own, and gets it.
        assertTrue(fragment.startsWith("#version 430 core\n#define EARTH_OWN_DEPTH\n"), fragment);
        assertEquals(1, count(fragment, "#version"));
        assertEquals(1, count(fragment, "void main()"));
        assertTrue(fragment.contains("layout(location = 3) out float earth_faceDepth;"), fragment);
        assertTrue(fragment.indexOf("void earth_dhBefore()") < fragment.indexOf("void earth_dhMain()"), fragment);
        assertTrue(fragment.indexOf("earth_dhBefore();\n    earth_dhMain();") > fragment.indexOf("void earth_dhMain()"), fragment);
    }

    @Test void aVertexShaderIrisDidNotPatchForDistantHorizonsIsLeftAlone() {
        assertNull(FarShaders.dhPatch("#version 330 core\nvoid main() { gl_Position = vec4(0.0); }\n", DH_FRAGMENT, 3));
    }

    @Test void nearFarTerrainUnderADistantHorizonsPackStopsAtTheBlocksReach() {
        String near = FarShaders.dhNearFragment("#version 330 core\n");
        assertEquals(1, count(near, "void main()"));
        assertTrue(near.contains("if (length(earth_position.xz) >= earth_NearReach) discard;"), near);
    }

    /**
     * Far terrain under a pack never curves or hazes: with {@code far.curvature} and
     * {@code far.atmosphere} both on, every pack shader is built exactly as with both off.
     */
    @Test void packShadersLeaveOutTheCurveAndTheHazeWithBothOn() throws Exception {
        ClassLoader fresh = new FreshPackage(FarShadersTest.class.getClassLoader());
        Class<?> shaders = withCurveAndHaze(() -> Class.forName(FarShaders.class.getName(), true, fresh));
        Class<?> patchType = Class.forName(FarShaders.PackPatch.class.getName(), true, fresh);
        // The flags took: far terrain without a pack curves and hazes.
        assertTrue(((String) field(shaders, "VERTEX")).contains("#define EARTH_CURVATURE\n"));
        assertTrue(((String) field(shaders, "FRAGMENT")).contains("#define EARTH_ATMOSPHERE\n"));
        String version = "#version 330 core\n", jitter = "vec2 earth_taaOffset() { return vec2(0.0); }\n";
        Object patch = call(shaders, "dhPatch", types(String.class, String.class, int.class), DH_VERTEX, DH_FRAGMENT, 3);
        FarShaders.PackPatch own = FarShaders.dhPatch(DH_VERTEX, DH_FRAGMENT, 3);
        java.util.List<String[]> built = new java.util.ArrayList<>();
        for (String taa : new String[] {null, jitter}) {
            built.add(new String[] {call(shaders, "packVertex", types(String.class, String.class), version, taa), FarShaders.packVertex(version, taa)});
        }
        built.add(new String[] {call(shaders, "dhVertex", types(String.class, patchType), version, patch), FarShaders.dhVertex(version, own)});
        built.add(new String[] {version + field(shaders, "PACK_FALLBACK"), version + FarShaders.PACK_FALLBACK});
        built.add(new String[] {call(shaders, "dhNearFragment", types(String.class), version), FarShaders.dhNearFragment(version)});
        for (int fade : new int[] {FarGpu.NO_FADE, FarGpu.FADE_IN}) {
            built.add(new String[] {call(shaders, "packPrepassFragment", types(String.class, String.class, int.class), version, PHOTON_NORMAL, fade),
                    FarShaders.packPrepassFragment(version, PHOTON_NORMAL, fade)});
            built.add(new String[] {call(shaders, "packShadeFragment", types(String.class, String.class, int.class), version, PHOTON_NORMAL, fade),
                    FarShaders.packShadeFragment(version, PHOTON_NORMAL, fade)});
            for (boolean ground : new boolean[] {false, true}) {
                built.add(new String[] {call(shaders, "packNearFragment", types(String.class, String.class, int.class, boolean.class), version, PHOTON_NORMAL, fade, ground),
                        FarShaders.packNearFragment(version, PHOTON_NORMAL, fade, ground)});
                for (boolean ownDepth : new boolean[] {false, true}) {
                    built.add(new String[] {call(shaders, "packFragment", types(String.class, String.class, boolean.class, int.class, boolean.class),
                            version, PHOTON_NORMAL, ownDepth, fade, ground), FarShaders.packFragment(version, PHOTON_NORMAL, ownDepth, fade, ground)});
                    built.add(new String[] {call(shaders, "dhFragment", types(String.class, patchType, boolean.class, int.class, boolean.class),
                            version, patch, ownDepth, fade, ground), FarShaders.dhFragment(version, own, ownDepth, fade, ground)});
                }
            }
        }
        for (String[] source : built) {
            assertTrue(!source[0].contains("#define EARTH_CURVATURE") && !source[0].contains("const float earth_Flat")
                    && !source[0].contains("#define EARTH_ATMOSPHERE"), source[0]);
            assertEquals(source[1], source[0]);
        }
    }

    private static Class<?>[] types(Class<?>... types) { return types; }

    private static Object field(Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T call(Class<?> type, String name, Class<?>[] types, Object... arguments) throws ReflectiveOperationException {
        java.lang.reflect.Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return (T) method.invoke(null, arguments);
    }

    /** Runs {@code load} with {@code far.curvature} and {@code far.atmosphere} on, as they were read when a fresh FarShaders initialises. */
    private static <T> T withCurveAndHaze(java.util.concurrent.Callable<T> load) throws Exception {
        String[] flags = {"earthplatform.far.curvature", "earthplatform.far.atmosphere"};
        String[] was = new String[flags.length];
        for (int i = 0; i < flags.length; i++) was[i] = System.setProperty(flags[i], "true");
        try { return load.call(); }
        finally {
            for (int i = 0; i < flags.length; i++) {
                if (was[i] == null) System.clearProperty(flags[i]);
                else System.setProperty(flags[i], was[i]);
            }
        }
    }

    /**
     * Defines this package's classes afresh, so their flags are read again; everything else comes
     * from the test's own loader. The whole package, as its classes reach each other's package-private parts.
     */
    private static final class FreshPackage extends ClassLoader {
        private static final String PACKAGE = FarShaders.class.getPackageName() + ".";

        FreshPackage(ClassLoader parent) { super(parent); }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PACKAGE) || name.indexOf('.', PACKAGE.length()) >= 0) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                try (java.io.InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) return super.loadClass(name, resolve);
                    byte[] bytes = in.readAllBytes();
                    Class<?> defined = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) resolveClass(defined);
                    return defined;
                } catch (java.io.IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
        }
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }
}
