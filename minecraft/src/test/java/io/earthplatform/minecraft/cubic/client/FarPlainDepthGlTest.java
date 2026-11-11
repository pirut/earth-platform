package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/** Opt-in real-driver smoke test, using an invisible window; no running game is controlled. */
@EnabledIfEnvironmentVariable(named = "EARTH_GL_TESTS", matches = "1")
class FarPlainDepthGlTest {
    private static long window;

    @BeforeAll static void open() {
        assertTrue(GLFW.glfwInit(), "GLFW must initialize for the opt-in GPU checks");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        window = GLFW.glfwCreateWindow(16, 16, "Earth renderer verification", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
        System.out.println("FAR_GPU_CHECK " + GL11C.glGetString(GL11C.GL_RENDERER));
    }

    @AfterAll static void close() {
        GL.setCapabilities(null);
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    @Test void allPlainShaderVariantsCompileOnTheDriver() {
        for (int fade = 0; fade < 3; fade++) for (String mode : new String[] {"", FarShaders.GROUND, "#define EARTH_PRECISE_DEPTH\n"}) {
            String header = "#version 330 core\n" + mode + FarShaders.fade(fade);
            int program = GlShaders.link(header + FarShaders.VERTEX, header + FarShaders.FRAGMENT, "plain variant");
            GL20C.glDeleteProgram(program);
        }
        int fallback = GlShaders.link("#version 330 core\n" + FarShaders.VERTEX, "#version 330 core\n" + FarShaders.PACK_FALLBACK, "pack fallback");
        GL20C.glDeleteProgram(fallback);
        assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
    }

    @Test void softWeatherShaderCompilesWithoutTheBasicShadersHardAlphaCutoff() throws Exception {
        var folder = java.nio.file.Path.of(System.getProperty("earthplatform.test.projectDirectory"),
                "src/main/resources/assets/earthplatform/shaders/core");
        String vertex = java.nio.file.Files.readString(folder.resolve("earthplatform_weather.vsh"));
        String fragment = java.nio.file.Files.readString(folder.resolve("earthplatform_weather.fsh"));
        int program = GlShaders.link(vertex, fragment, "soft layered weather");
        GL20C.glDeleteProgram(program);
        assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
    }

    @Test void weatherShaderLoadsItsActualJsonAndPrivateResourceNamespace() throws Exception {
        try (var shader = weatherShader()) {
            assertNotNull(shader.getUniform("SkyInvProjection")); assertNotNull(shader.getUniform("NoiseVolume"));
        }
        assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
    }

    private net.minecraft.client.renderer.ShaderInstance weatherShader() throws Exception {
        return resourceShader(false);
    }

    @Test void continuousWaterShaderLoadsActualResources() throws Exception {
        try (var shader = resourceShader(true)) {
            assertNotNull(shader.getUniform("EarthCurvature"));
            assertNotNull(shader.getUniform("EarthDepthBias"));
            assertNotNull(shader.CHUNK_OFFSET);
        }
        assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
    }

    private net.minecraft.client.renderer.ShaderInstance resourceShader(boolean water) throws Exception {
        var root = java.nio.file.Path.of(System.getProperty("earthplatform.test.projectDirectory"), "src/main/resources/assets/earthplatform");
        var pack = (net.minecraft.server.packs.PackResources) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {net.minecraft.server.packs.PackResources.class},
                (object, method, args) -> method.getName().equals("packId") ? "weather-test" : null);
        net.minecraft.server.packs.resources.ResourceProvider provider = location -> {
            var path = root.resolve(location.getPath());
            if (!location.getNamespace().equals("earthplatform") || !java.nio.file.Files.isRegularFile(path)) return java.util.Optional.empty();
            return java.util.Optional.of(new net.minecraft.server.packs.resources.Resource(pack, () -> java.nio.file.Files.newInputStream(path)));
        };
        return water ? FarWaterShader.load(provider) : io.earthplatform.minecraft.cubic.play.client.EarthClouds.loadShader(provider);
    }

    @Test void curvedOceanKeepsAdjacentColumnsJoinedUnderZoom() throws Exception {
        int width = 960, height = 540;
        int target = GL30C.glGenFramebuffers(), colour = GL11C.glGenTextures();
        int vao = GL30C.glGenVertexArrays(), buffer = GL15C.glGenBuffers();
        var root = java.nio.file.Path.of(System.getProperty("earthplatform.test.projectDirectory"));
        String vertex = java.nio.file.Files.readString(root.resolve("src/main/resources/assets/earthplatform/shaders/core/earthplatform_water.vsh"));
        int program = GlShaders.link(vertex, "#version 150\nout vec4 colour; void main(){colour=vec4(0.03,0.2,0.45,1);}", "curved ocean regression");
        try {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colour);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, (FloatBuffer) null);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0); GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));
            GL11C.glViewport(0, 0, width, height); GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE); GL11C.glDisable(GL11C.GL_BLEND); GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL30C.glBindVertexArray(vao); GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, buffer);
            GlStateManager._glUseProgram(program);
            int position = GL20C.glGetAttribLocation(program, "Position");
            GL20C.glEnableVertexAttribArray(position); GL20C.glVertexAttribPointer(position, 3, GL11C.GL_FLOAT, false, 12, 0L);
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(10), width / (float) height, .05f, 190000);
            Matrix4f view = new Matrix4f().rotateX((float) Math.atan2(8300, 73728));
            float curve = 1f / (2 * 6371000);
            int[] cracks = new int[2];
            var directory = root.resolve("build/reports/horizon"); java.nio.file.Files.createDirectories(directory);
            for (int pass = 0; pass < 2; pass++) {
                GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, new float[] {1,.4f,0,1});
                GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "EarthCurvature"), pass == 0 ? 0 : curve);
                GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "EarthDepthBias"), pass == 0 ? 0 : 2f / (1 << 24));
                GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "ProjMat"), false, projection.get(new float[16]));
                for (int z = -86016; z < -65536; z += 4096) for (int x = -8192; x < 8192; x += 4096) {
                    Matrix4f matrix = view;
                    if (pass == 0) {
                        double distance = Math.sqrt((double) (x+2048)*(x+2048) + (double) (z+2048)*(z+2048) + 8000.0*8000);
                        matrix = EarthView.water(view, x+2048, z+2048, curve).scale((float) Math.max(.8, 1-distance*(2.0/(1<<24))/.05));
                    }
                    GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "ModelViewMat"), false, matrix.get(new float[16]));
                    GL20C.glUniform3f(GL20C.glGetUniformLocation(program, "ChunkOffset"), x, -8000, z);
                    int step = pass == 0 ? 4096 : EarthView.WATER_GRID;
                    for (int dz = 0; dz < 4096; dz += step) for (int dx = 0; dx < 4096; dx += step) {
                        float[] quad = {dx,0,dz, dx+step,0,dz, dx+step,0,dz+step,
                                dx,0,dz, dx+step,0,dz+step, dx,0,dz+step};
                        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, quad, GL15C.GL_STREAM_DRAW);
                        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 6);
                    }
                }
                float[] pixels = new float[width*height*4]; GL11C.glReadPixels(0,0,width,height,GL11C.GL_RGBA,GL11C.GL_FLOAT,pixels);
                var image = new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
                int rows = 0;
                for (int y = 0; y < height; y++) {
                    int first = -1, last = -1;
                    for (int x = 0; x < width; x++) {
                        int at = (y*width+x)*4;
                        if (pixels[at] < .1) { if (first < 0) first=x; last=x; }
                        image.setRGB(x,height-y-1,Math.round(pixels[at]*255)<<16|Math.round(pixels[at+1]*255)<<8|Math.round(pixels[at+2]*255));
                    }
                    if (last-first > width/2) {
                        rows++;
                        for (int x = first; x <= last; x++) if (pixels[(y*width+x)*4] > .8) cracks[pass]++;
                    }
                }
                assertTrue(rows > 20, "test must see a substantial ocean patch");
                javax.imageio.ImageIO.write(image,"PNG",directory.resolve(pass==0?"ocean-before.png":"ocean-after.png").toFile());
            }
            System.out.println("HORIZON_JOIN_CHECK old gap pixels="+cracks[0]+" new="+cracks[1]);
            assertTrue(cracks[0] > 10, "reproduce the old column cracks");
            assertEquals(0, cracks[1], "shared warp must close all internal gaps");
            assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        } finally {
            GlStateManager._glUseProgram(0); GL30C.glBindVertexArray(0); GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,0);
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);
            GL15C.glDeleteBuffers(buffer); GL30C.glDeleteVertexArrays(vao); GL20C.glDeleteProgram(program);
            GL11C.glDeleteTextures(colour); GL30C.glDeleteFramebuffers(target);
        }
    }

    @Test void actualCloudVolumesStayBoundedAtWhiteoutAltitudesAndRespectSceneDepth() throws Exception {
        final int width = 1280, height = 720;
        int target = GL30C.glGenFramebuffers(), colour = GL11C.glGenTextures(), scene = GL11C.glGenTextures(), farScene = GL11C.glGenTextures();
        int[] weather = new int[3]; int vao = GL30C.glGenVertexArrays(), vertices = GL15C.glGenBuffers();
        int noise = 0;
        try (var shader = weatherShader()) {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colour);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, (FloatBuffer) null);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0); GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));
            for (int kind = 0; kind < 3; kind++) {
                weather[kind] = GL11C.glGenTextures(); GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, weather[kind]);
                float[] pixels = new float[128 * 128 * 4];
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                    int at = (y * 128 + x) * 4;
                    pixels[at] = pixels[at + 1] = pixels[at + 2] = 1;
                    pixels[at + 3] = (float) io.earthplatform.minecraft.cubic.play.client.CloudField.density(kind, (x + .5) / 128, (y + .5) / 128);
                }
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 128, 128, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, pixels);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE5);
            noise = io.earthplatform.minecraft.cubic.play.client.CloudVolume.createNoiseTexture();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            shader.setSampler("Sampler0", weather[0]); shader.setSampler("Weather1", weather[1]); shader.setSampler("Weather2", weather[2]);
            shader.setSampler("SceneDepth", scene);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, farScene);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R32F, 1, 1, 0, GL11C.GL_RED, GL11C.GL_FLOAT, new float[] {1});
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            shader.setSampler("FarDepth", farScene);
            GL30C.glBindVertexArray(vao); GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
            float[] quad = {-1,-1,0,0,0,1,1,1,1, 1,-1,0,1,0,1,1,1,1, 1,1,0,1,1,1,1,1,1,
                    -1,-1,0,0,0,1,1,1,1, 1,1,0,1,1,1,1,1,1, -1,1,0,0,1,1,1,1,1};
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, quad, GL15C.GL_STATIC_DRAW);
            String[] names = {"Position", "UV0", "Color"}; int[] sizes = {3,2,4}, offsets = {0,12,20};
            for (int i = 0; i < 3; i++) {
                int at = GL20C.glGetAttribLocation(shader.getId(), names[i]);
                GL20C.glEnableVertexAttribArray(at); GL20C.glVertexAttribPointer(at, sizes[i], GL11C.GL_FLOAT, false, 36, offsets[i]);
            }
            GL11C.glViewport(0, 0, width, height); GL11C.glDisable(GL11C.GL_DEPTH_TEST); GL11C.glDisable(GL11C.GL_CULL_FACE); GL11C.glDisable(GL11C.GL_BLEND);
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), width / (float) height, .05f, 190000);
            var directory = java.nio.file.Path.of(System.getProperty("earthplatform.test.projectDirectory"), "build/reports/cloud-volume");
            java.nio.file.Files.createDirectories(directory);
            double[] weatherTimes={-1,-1};
            for(double t=0;t<io.earthplatform.minecraft.cubic.play.client.CloudWeather.TIME_PERIOD;t+=1000) {
                double low=1,high=0;
                for(int dz=-1000;dz<=1000;dz+=1000)for(int dx=-1000;dx<=1000;dx+=1000) {
                    double coverage=io.earthplatform.minecraft.cubic.play.client.CloudWeather.coverage(12000+dx,9000+dz,t,0);
                    low=Math.min(low,coverage);high=Math.max(high,coverage);
                }
                if(high==0 && weatherTimes[0]<0)weatherTimes[0]=t;
                if(low>.99 && weatherTimes[1]<0)weatherTimes[1]=t;
                if(weatherTimes[0]>=0 && weatherTimes[1]>=0)break;
            }
            assertTrue(weatherTimes[0]>=0 && weatherTimes[1]>=0,"natural clear/cloudy fixtures must exist");
            int[] altitudes = {1000, 3400, 3800, 4200, 8000, 8000, 3800, 3800, 3800, 3800, 8000, 8000, 8000, 8000, 8000,8912,8912};
            String[] namesOfRenders = {"altitude-1000", "altitude-3400", "altitude-3800", "altitude-4200", "altitude-8000",
                    "curved-8000", "balanced-3800", "cinematic-3800", "night-3800", "dense-bank-3800", "depth-layout-30000", "depth-layout-40000", "biased-water-10000", "biased-water-20000", "spyglass-8000","mountain-clear","mountain-cloudy"};
            float[] stableClouds = null;
            for (int scenario = 0; scenario < altitudes.length; scenario++) {
                int altitude = altitudes[scenario];
                boolean stableLayout = scenario >= 10 && scenario <= 13;
                boolean night = scenario == 8;
                if (scenario == 9) {
                    // Worst case: put the camera inside an unbroken middle cloud bank.
                    GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, weather[1]);
                    float[] full = new float[128 * 128 * 4]; java.util.Arrays.fill(full, 1);
                    GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 128, 128, GL11C.GL_RGBA, GL11C.GL_FLOAT, full);
                }
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene);
                float sceneDepth = 1;
                if (stableLayout) {
                    Vector4f clip = projection.transform(new Vector4f(0,0,scenario == 10 ? -30000 : scenario == 11 ? -40000 : scenario == 12 ? -10000 : -20000,1));
                    sceneDepth = clip.z / clip.w * .5f + .5f;
                }
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R32F, 1, 1, 0, GL11C.GL_RED, GL11C.GL_FLOAT, new float[] {sceneDepth});
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                Matrix4f view = new Matrix4f().rotateX((float) Math.toRadians(scenario >= 15 ? 90 : scenario == 14 ? 4 : stableLayout ? 70 : altitude < 2000 ? -12 : altitude > 6000 ? 25 : 0));
                Matrix4f lens = scenario >= 14 ? new Matrix4f().perspective((float) Math.toRadians(7), width/(float)height, .05f, 190000) : projection;
                io.earthplatform.minecraft.cubic.play.client.CloudVolume.configure(shader, view, lens, 12000, altitude, 9000, scenario>=15?weatherTimes[scenario-15]:100, 2500,
                        night ? .12f : 1, night ? .15f : 1, night ? .23f : 1, .4f, night ? -.8f : .8f, .3f,
                        scenario == 5 || stableLayout ? 1f / (2 * 6371000) : 0, scenario == 6 ? 16 : scenario == 7 ? 32 : 24, .72f);
                // Hold weather fixed for optical regressions; exercise natural fronts separately.
                if(scenario<15)shader.getUniform("WeatherAmount").set(0f);
                if (scenario >= 12) shader.getUniform("UseFarDepth").set(1f);
                GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, new float[] {0,0,0,0});
                shader.apply(); long begin = System.nanoTime(); GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 6); GL11C.glFinish();
                System.out.println("CLOUD_VOLUME_GPU render=" + namesOfRenders[scenario] + " size=" + width + "x" + height + " ms=" + (System.nanoTime() - begin) / 1e6);
                shader.clear();
                float[] output = new float[width * height * 4]; GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_FLOAT, output);
                float maximum = 0;
                var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int at = (y * width + x) * 4; float a = output[at + 3]; maximum = Math.max(maximum, a);
                    assertTrue(a <= .725f, "opacity is bounded even inside the middle band");
                    int red = Math.round(255 * (output[at] * a + (night ? .015f : .35f) * (1 - a)));
                    int green = Math.round(255 * (output[at + 1] * a + (night ? .025f : .58f) * (1 - a)));
                    int blue = Math.round(255 * (output[at + 2] * a + (night ? .06f : .85f) * (1 - a)));
                    image.setRGB(x, height - y - 1, red << 16 | green << 8 | blue);
                }
                if(scenario==15) assertEquals(0,maximum,.004,"a naturally clear front removes local cloud banks");
                else assertTrue(maximum > .01f, "the test actually draws cloud volume");
                if (scenario == 10 || scenario == 12) stableClouds = output.clone();
                if (scenario == 11 || scenario == 13) {
                    float difference = 0;
                    for (int i = 0; i < output.length; i++) difference = Math.max(difference, Math.abs(output[i] - stableClouds[i]));
                    assertTrue(difference <= 2f/255, "moving geometry behind clouds must not re-space foreground samples: " + difference);
                }
                javax.imageio.ImageIO.write(image, "PNG", directory.resolve(namesOfRenders[scenario] + ".png").toFile());
            }
            // Opaque geometry immediately in front of the camera must hide all distant clouds.
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11C.GL_RED, GL11C.GL_FLOAT, new float[] {.3f});
            GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, new float[] {0,0,0,0}); shader.apply();
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 6); shader.clear();
            float[] hidden = new float[width * height * 4]; GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_FLOAT, hidden);
            for (int i = 3; i < hidden.length; i += 4) assertEquals(0, hidden[i]);
            // The accurate far-terrain source must still hide clouds behind opaque hills.
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11C.GL_RED, GL11C.GL_FLOAT, new float[] {1});
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, farScene);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11C.GL_RED, GL11C.GL_FLOAT, new float[] {.3f});
            GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, new float[] {0,0,0,0}); shader.apply();
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 6); shader.clear();
            GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_FLOAT, hidden);
            for (int i = 3; i < hidden.length; i += 4) assertEquals(0, hidden[i]);
            assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
        } finally {
            GL11C.glDepthMask(true); GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0); GL30C.glBindVertexArray(0);
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            for (int id : weather) GL11C.glDeleteTextures(id);
            if (noise != 0) GL11C.glDeleteTextures(noise);
            GL11C.glDeleteTextures(scene); GL11C.glDeleteTextures(colour); GL15C.glDeleteBuffers(vertices);
            GL11C.glDeleteTextures(farScene);
            GL30C.glDeleteVertexArrays(vao); GL30C.glDeleteFramebuffers(target);
        }
    }

    @Test void precisePassMergesDepthPreservesColourAndRestoresDrawStateAcrossResize() {
        int colour = GL11C.glGenTextures(), depth = GL11C.glGenTextures(), target = GL30C.glGenFramebuffers();
        FarPlainDepth precise = new FarPlainDepth();
        Matrix4f game = new Matrix4f().perspective(1, 1, 0.05f, FarRenderer.DEPTH_FAR);
        try {
            for (int size : new int[] {4, 8}) {
                GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target);
                texture(colour, GL11C.GL_RGBA8, GL11C.GL_RGBA, size);
                texture(depth, GL30C.GL_DEPTH_COMPONENT24, GL11C.GL_DEPTH_COMPONENT, size);
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
                GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0); GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
                assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));
                GL11C.glViewport(0, 0, size, size);
                GL30C.glClearBufferfv(GL11C.GL_COLOR, 0, new float[] {.2f, .4f, .6f, 1});
                GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {1});
                GL11C.glEnable(GL11C.GL_SCISSOR_TEST); GL11C.glScissor(0, 0, size / 2, size);
                GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {.25f});
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                assertTrue(precise.begin(game, size, size));
                // A resolved far surface at 10 km, before conversion to the game's depth range.
                Vector4f clip = precise.projection().transform(new Vector4f(0, 0, -10000, 1));
                GL11C.glDepthMask(true);
                GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, new float[] {clip.z / clip.w * .5f + .5f});
                GL11C.glDepthFunc(GL11C.GL_LESS); GL11C.glDepthMask(false); GL11C.glEnable(GL11C.GL_CULL_FACE);
                precise.end();
                assertEquals(target, GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING));
                assertEquals(GL11C.GL_LESS, GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC));
                assertFalse(GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK));
                assertTrue(GL11C.glIsEnabled(GL11C.GL_CULL_FACE));
                float[] pixel = new float[4]; GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, pixel);
                assertEquals(.2f, pixel[0], .005); assertEquals(.4f, pixel[1], .005); assertEquals(.6f, pixel[2], .005);
                float[] stored = new float[1]; GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, stored);
                assertEquals(.25f, stored[0], 2e-7, "nearby block depth survives the far pass");
                GL11C.glReadPixels(size - 1, 0, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, stored);
                Vector4f expected = game.transform(new Vector4f(0, 0, -10000, 1));
                assertEquals(expected.z / expected.w * .5f + .5f, stored[0], 2e-7);
                assertEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
                GL11C.glDepthMask(true);
            }
        } finally {
            precise.close(); GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL30C.glDeleteFramebuffers(target); GL11C.glDeleteTextures(colour); GL11C.glDeleteTextures(depth);
        }
    }

    private static void texture(int id, int format, int external, int size) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, format, size, size, 0, external, GL11C.GL_FLOAT, (FloatBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
    }
}
