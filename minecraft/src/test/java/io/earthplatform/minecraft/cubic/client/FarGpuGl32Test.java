package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

/**
 * Far terrain's shaders on the real driver, with {@code EARTH_GL_TESTS=1}. A 3.2 core context
 * (Mesa: {@code MESA_GL_VERSION_OVERRIDE=3.2}) must accept the header {@link FarGpu#glslVersion()}
 * actually emits, and that header must link the shaders. On a newer context the same 1.50 header
 * is what a Fabric 3.2 window would emit, so it is linked here too.
 */
@EnabledIfEnvironmentVariable(named = "EARTH_GL_TESTS", matches = "1")
class FarGpuGl32Test {
    private static long window;

    @BeforeAll static void open() {
        assertTrue(GLFW.glfwInit());
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(16, 16, "Earth far terrain GL 3.2", 0, 0);
        assertTrue(window != 0, "no OpenGL 3.2 window");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("FAR_GPU " + GL11C.glGetString(GL11C.GL_RENDERER) + " / " + GL11C.glGetString(GL11C.GL_VERSION)
                + " / GLSL " + GL11C.glGetString(GL20C.GL_SHADING_LANGUAGE_VERSION));
    }

    @AfterAll static void close() {
        GL.setCapabilities(null);
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    @Test void thisContextCanDrawFarTerrain() {
        assertTrue(FarGpu.supported(), GL11C.glGetString(GL11C.GL_VERSION));
        String header = FarGpu.glslVersion();
        String gl = GL11C.glGetString(GL11C.GL_VERSION);
        if (gl.startsWith("3.2")) {
            assertTrue(header.startsWith("#version 150 core\n"), header);
            assertTrue(header.contains("#extension GL_ARB_shader_bit_encoding : enable\n"), header);
            String full = header + "#define EARTH_INDEXED\n";
            GL20C.glDeleteProgram(GlShaders.link(full + FarShaders.VERTEX, full + FarShaders.FRAGMENT, "live 3.2 far terrain"));
            GL20C.glDeleteProgram(GlShaders.link(full + FarShaders.VERTEX, full + FarShaders.PACK_FALLBACK, "live 3.2 pack fallback"));
        } else {
            assertFalse(header.startsWith("#version 150"), header);
            assertFalse(header.contains("#extension"), header);
        }
    }

    /** The header a 3.2 context emits, linked against the real far-terrain shaders. */
    @Test void glsl150HeaderLinksTheFarTerrainShaders() {
        String header = FarGpu.glslVersion(false, false, false, false, false, false, false, false, true, true) + "#define EARTH_INDEXED\n";
        String[] flags = {"", "#define EARTH_CURVATURE\nconst float earth_Flat = 2000.0;\n", "#define EARTH_ATMOSPHERE\n",
                "#define EARTH_BLOCK_TEXTURES\n"};
        for (String flag : flags) {
            for (int fade = 0; fade < 3; fade++) {
                for (String mode : new String[] {"", FarShaders.GROUND, FarShaders.PRECISE_DEPTH}) {
                    String full = header + flag + mode + FarShaders.fade(fade);
                    GL20C.glDeleteProgram(GlShaders.link(full + FarShaders.VERTEX, full + FarShaders.FRAGMENT, "150 " + flag + mode + fade));
                }
            }
            String full = header + flag;
            GL20C.glDeleteProgram(GlShaders.link(full + FarShaders.VERTEX, full + FarShaders.PACK_FALLBACK, "150 pack fallback " + flag));
        }
        String patch = """
                layout(location = 0) out vec4 gbuffer_data_0;
                void voxy_emitFragment(VoxyFragmentParameters parameters) {
                    gbuffer_data_0 = parameters.sampledColour;
                }
                """;
        GL20C.glDeleteProgram(GlShaders.link(FarShaders.packVertex(header, null), FarShaders.packFragment(header, patch, true), "150 pack own depth"));
        String terrainVert = """
                #version 150 core
                uniform mat4 iris_ProjMat;
                uniform mat4 iris_ModelViewMat;
                uniform mat3 iris_NormalMat;
                uniform vec4 iris_ColorModulator;
                in vec3 iris_Position;
                in vec4 iris_Color;
                in vec3 iris_Normal;
                in vec2 iris_UV0;
                in ivec2 iris_UV2;
                in vec4 mc_Entity;
                in vec4 at_tangent;
                out vec4 vCol;
                out vec2 vUv;
                out vec2 vLm;
                out vec3 vNorm;
                void main() {
                    gl_Position = iris_ProjMat * iris_ModelViewMat * vec4(iris_Position, 1.0);
                    vCol = iris_Color * iris_ColorModulator;
                    vUv = iris_UV0;
                    vLm = vec2(iris_UV2) / 240.0;
                    vNorm = normalize(iris_NormalMat * iris_Normal) + at_tangent.xyz * 0.0 + mc_Entity.xyz * 0.0;
                }
                """;
        String terrainFrag = """
                #version 150 core
                #extension GL_ARB_explicit_attrib_location : enable
                uniform sampler2D gtexture;
                uniform sampler2D lightmap;
                in vec4 vCol;
                in vec2 vUv;
                in vec2 vLm;
                in vec3 vNorm;
                layout(location = 0) out vec4 iris_FragData0;
                layout(location = 1) out vec4 iris_FragData1;
                layout(location = 2) out vec4 iris_FragData2;
                layout(location = 3) out vec4 iris_FragData3;
                void main() {
                    iris_FragData0 = texture(gtexture, vUv) * vCol;
                    iris_FragData1 = vec4(vNorm * 0.5 + 0.5, 1.0);
                    iris_FragData2 = vec4(vLm, 0.0, 1.0);
                    iris_FragData3 = texture(lightmap, vLm);
                }
                """;
        FarShaders.PackPatch terrain = FarShaders.terrainPatch(terrainVert, terrainFrag, 4);
        for (int fade = 0; fade < 3; fade++) {
            for (boolean ground : new boolean[] {false, true}) {
                String h = header + FarShaders.fade(fade);
                GL20C.glDeleteProgram(GlShaders.link(FarShaders.terrainVertex(h, terrain),
                        FarShaders.terrainFragment(header, terrain, fade, ground), "150 terrain " + fade + " " + ground));
            }
        }
        assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
    }
}
