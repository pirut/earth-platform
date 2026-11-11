package io.earthplatform.minecraft.cubic.play.client;

import java.nio.ByteBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/** Bounded volume inputs shared by the live cloud draw and real-driver regression renders. */
public final class CloudVolume {
    private CloudVolume() {}

    public static int steps(String quality) {
        return switch (quality.toLowerCase(java.util.Locale.ROOT)) {
            case "balanced" -> 16;
            case "cinematic" -> 32;
            default -> 24;
        };
    }

    /** A repeating 64-cubed scalar lattice, smoothly filtered by the GPU. About 256 KiB. */
    public static int createNoiseTexture() {
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
        ByteBuffer bytes = MemoryUtil.memAlloc(64 * 64 * 64);
        try {
            CloudWeather.writeNoise(bytes);
            bytes.flip();
            GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL30C.GL_R8, 64, 64, 64, 0, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, bytes);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, GL11C.GL_REPEAT);
        } finally { MemoryUtil.memFree(bytes); }
        return texture;
    }

    public static void configure(ShaderInstance shader, Matrix4f view, Matrix4f projection,
                                 double x, double y, double z, double ticks, double height,
                                 float r, float g, float b, float sunX, float sunY, float sunZ,
                                 float curvature, int steps, float opacity) {
        shader.getUniform("SkyInvProjection").set(new Matrix4f(projection).invert());
        shader.getUniform("SkyInvView").set(new Matrix4f(view).invert());
        shader.getUniform("CameraHeight").set((float) y);
        shader.getUniform("CloudLight").set(r, g, b);
        shader.getUniform("SunDirection").set(sunX, sunY, sunZ);
        shader.getUniform("Curvature").set(curvature);
        shader.getUniform("MarchSteps").set(Math.clamp(steps, 8, 32));
        shader.getUniform("MaxOpacity").set(Math.clamp(opacity, 0, .98f));
        shader.getUniform("NoiseVolume").set(5);
        shader.getUniform("UseFarDepth").set(0f);
        shader.getUniform("FarInvProjection").set(new Matrix4f(projection).invert());
        configureWeather(shader, x, z, ticks, 0);
        var bands = CloudField.bands(height);
        for (int i = 0; i < 3; i++) {
            var band = bands[i];
            shader.getUniform("Band" + i).set((float) band.height(), (float) band.thickness(), (float) band.period(), (float) band.opacity());
            double c = Math.cos(band.turn()), s = Math.sin(band.turn());
            shader.getUniform("Phase" + i).set((float) frac((c * x - s * z + ticks * band.wind()) / band.period()),
                    (float) frac((s * x + c * z + ticks * band.wind() * .23) / band.period()));
        }
        shader.getUniform("NoisePhase0").set((float) frac(x / 18000), (float) frac(y / 18000), (float) frac(z / 18000));
        shader.getUniform("NoisePhase1").set((float) frac(x / 4500), (float) frac(y / 4500), (float) frac(z / 4500));
    }

    private static double frac(double n) { return n - Math.floor(n); }

    public static void configureWeather(ShaderInstance shader, double x, double z, double ticks, float rain) {
        shader.getUniform("WeatherAmount").set(1f);
        shader.getUniform("WeatherPhase").set((float) frac(x/CloudWeather.REGION_PERIOD),
                (float) frac(z/CloudWeather.REGION_PERIOD), (float) frac(ticks/CloudWeather.TIME_PERIOD), Math.clamp(rain,0,1)*.2f);
    }
}
