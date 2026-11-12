package io.earthplatform.minecraft.cubic.play.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * The layered clouds' shader on the real driver, in a hidden window, with {@code EARTH_GL_TESTS=1}:
 * that it builds, and that it stops at far terrain's own depth past the game's blocks.
 */
@EnabledIfEnvironmentVariable(named = "EARTH_GL_TESTS", matches = "1")
class CloudDepthGlTest {
    private static final int SIZE = 64;
    private static final Path CORE = Path.of(System.getProperty("earthplatform.test.projectDirectory"), "src/main/resources/assets/earthplatform/shaders/core");
    private static long window;
    private static int program, target, colour, vao, buffer, scene, far, noise;
    private static final int[] cover = new int[3];
    private static final Matrix4f GAME = new Matrix4f().perspective(1.2f, 1, 0.05f, 200_000);
    private static final Matrix4f OWN = new Matrix4f(GAME).m22((200_000f + 8) / (8 - 200_000f)).m32(2 * 200_000f * 8 / (8 - 200_000f));

    @BeforeAll static void open() throws Exception {
        assertTrue(GLFW.glfwInit());
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(16, 16, "Earth cloud check", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        program = link(Files.readString(CORE.resolve("earthplatform_weather.vsh")), Files.readString(CORE.resolve("earthplatform_weather.fsh")));
        target = GL30C.glGenFramebuffers();
        colour = texture(GL30C.GL_RGBA32F, GL11C.GL_RGBA, SIZE, null);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
        assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));
        // Banks fully covered everywhere, so a ray through one always meets cloud.
        float[] full = new float[16 * 16 * 4];
        java.util.Arrays.fill(full, 1);
        for (int i = 0; i < 3; i++) cover[i] = texture(GL11C.GL_RGBA8, GL11C.GL_RGBA, 16, full);
        scene = texture(GL30C.GL_R32F, GL11C.GL_RED, 1, new float[] {1});
        far = texture(GL30C.GL_R32F, GL11C.GL_RED, 1, new float[] {1});
        noise = GL11C.glGenTextures();
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, noise);
        ByteBuffer bytes = BufferUtils.createByteBuffer(64 * 64 * 64);
        CloudWeather.writeNoise(bytes);
        bytes.flip();
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL30C.GL_R8, 64, 64, 64, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, bytes);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        vao = GL30C.glGenVertexArrays();
        buffer = GL15C.glGenBuffers();
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
        // A full-screen pair of triangles: position, UV, colour.
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, new float[] {-1, -1, 0, 0, 0, 1, 1, 1, 1, 1, -1, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1,
                -1, -1, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 1, 1}, GL15C.GL_STATIC_DRAW);
        String[] inputs = {"Position", "UV0", "Color"};
        int[] sizes = {3, 2, 4}, offsets = {0, 12, 20};
        for (int i = 0; i < 3; i++) {
            int at = GL20C.glGetAttribLocation(program, inputs[i]);
            GL20C.glEnableVertexAttribArray(at);
            GL20C.glVertexAttribPointer(at, sizes[i], GL11C.GL_FLOAT, false, 36, offsets[i]);
        }
    }

    @AfterAll static void close() {
        GL20C.glDeleteProgram(program);
        GL15C.glDeleteBuffers(buffer);
        GL30C.glDeleteVertexArrays(vao);
        GL30C.glDeleteFramebuffers(target);
        for (int id : new int[] {colour, scene, far, noise, cover[0], cover[1], cover[2]}) GL11C.glDeleteTextures(id);
        GL.setCapabilities(null);
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    @Test void cloudsStopAtFarTerrainsOwnDepth() {
        assertTrue(drawn(1, 1, false, 0) > .1, "nothing in front: the bank overhead shows");
        assertEquals(0, drawn(1, depth(OWN, 100), true, 0), "far terrain 100 blocks off hides the bank behind it");
        assertTrue(drawn(1, depth(OWN, 100), false, 0) > .1, "without far terrain's own depth, it isn't read");
    }

    @Test void theGamesDepthStillClipsNearbyButNotFarOut() {
        assertEquals(0, drawn(depth(GAME, 100), 1, true, 0), "the game's blocks near by still hide the clouds");
        assertTrue(drawn(depth(GAME, 30_000), 1, true, 0) > .1, "past 2 km its coarse depth gives way to far terrain's own");
        assertEquals(0, drawn(depth(GAME, 300), 1, false, 0), "without far terrain's own depth, the game's clips as before");
    }

    @Test void curvedBanksStillDraw() {
        assertTrue(drawn(1, 1, false, 1 / (2 * 6_371_000f)) > .1);
    }

    /** The most opacity drawn, looking up into the lowest bank from under it. */
    private static float drawn(float sceneDepth, float farDepth, boolean useFar, float curvature) {
        set(scene, sceneDepth);
        set(far, farDepth);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
        GL11C.glViewport(0, 0, SIZE, SIZE);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, new float[] {0, 0, 0, 0});
        GL20C.glUseProgram(program);
        String[] samplers = {"Sampler0", "Weather1", "Weather2", "SceneDepth", "FarDepth"};
        int[] textures = {cover[0], cover[1], cover[2], scene, far};
        for (int i = 0; i < samplers.length; i++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
            GL20C.glUniform1i(location(samplers[i]), i);
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE5);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, noise);
        GL20C.glUniform1i(location("NoiseVolume"), 5);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        matrix("SkyInvProjection", new Matrix4f(GAME).invert());
        matrix("SkyInvView", new Matrix4f().rotateX((float) Math.toRadians(-60)).invert());
        matrix("FarInvProjection", new Matrix4f(OWN).invert());
        GL20C.glUniform1f(location("UseFarDepth"), useFar ? 1 : 0);
        GL20C.glUniform1f(location("Curvature"), curvature);
        GL20C.glUniform1f(location("CameraHeight"), 900);
        GL20C.glUniform3f(location("CloudLight"), 1, 1, 1);
        GL20C.glUniform3f(location("SunDirection"), 0, 1, 0);
        GL20C.glUniform1i(location("MarchSteps"), 24);
        GL20C.glUniform1f(location("MaxOpacity"), .72f);
        GL20C.glUniform4f(location("ColorModulator"), 1, 1, 1, 1);
        // A front fully in: weather adds the most cover.
        GL20C.glUniform4f(location("WeatherPhase"), 0, 0, 0, 1);
        var bands = CloudField.bands(2500);
        for (int i = 0; i < 3; i++)
            GL20C.glUniform4f(location("Band" + i), (float) bands[i].height(), (float) bands[i].thickness(), (float) bands[i].period(), (float) bands[i].opacity());
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 6);
        assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        GL11C.glReadPixels(0, 0, SIZE, SIZE, GL11C.GL_RGBA, GL11C.GL_FLOAT, pixels);
        float most = 0;
        for (int i = 3; i < pixels.capacity(); i += 4) most = Math.max(most, pixels.get(i));
        return most;
    }

    private static float depth(Matrix4f projection, float distance) {
        Vector4f clip = projection.transform(new Vector4f(0, 0, -distance, 1));
        return clip.z / clip.w * .5f + .5f;
    }

    private static int location(String name) {
        int at = GL20C.glGetUniformLocation(program, name);
        assertNotEquals(-1, at, name);
        return at;
    }

    private static void matrix(String name, Matrix4f value) { GL20C.glUniformMatrix4fv(location(name), false, value.get(new float[16])); }

    private static void set(int texture, float value) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11C.GL_RED, GL11C.GL_FLOAT, new float[] {value});
    }

    private static int texture(int format, int external, int size, float[] pixels) {
        int id = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        if (pixels == null) GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, format, size, size, 0, external, GL11C.GL_FLOAT, (FloatBuffer) null);
        else GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, format, size, size, 0, external, GL11C.GL_FLOAT, pixels);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
        return id;
    }

    private static int link(String vertex, String fragment) {
        int program = GL20C.glCreateProgram();
        for (int i = 0; i < 2; i++) {
            int shader = GL20C.glCreateShader(i == 0 ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER);
            GL20C.glShaderSource(shader, i == 0 ? vertex : fragment);
            GL20C.glCompileShader(shader);
            assertEquals(GL11C.GL_TRUE, GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS), GL20C.glGetShaderInfoLog(shader));
            GL20C.glAttachShader(program, shader);
            GL20C.glDeleteShader(shader);
        }
        GL20C.glLinkProgram(program);
        assertEquals(GL11C.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), GL20C.glGetProgramInfoLog(program));
        return program;
    }
}
