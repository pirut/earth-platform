package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.ByteBuffer;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

/**
 * The layered clouds: three banks at fixed heights (see {@link CloudField}) with thickness and
 * their own winds, ray marched in one full-screen pass and stopped at the scene's depth (see
 * {@link CloudDepth}), so mountains rise through them. Weather fronts (see {@link CloudWeather})
 * thin and thicken them over time. Without a shader pack only. Render thread only.
 */
final class CloudVolume {
    private static final int TEXTURE_SIZE = 256;
    /** The texture unit the 3D noise is bound to while the clouds draw. */
    private static final int NOISE_UNIT = 5;
    private static final ResourceLocation[] textures = new ResourceLocation[3];
    private static ShaderInstance shader, baseShader;
    private static boolean shaderFailed;
    private static int noiseTexture;

    private CloudVolume() {}

    /** Ray samples per bank for {@code earthplatform.clouds.quality}. */
    static int steps(String quality) {
        return switch (quality.toLowerCase(Locale.ROOT)) {
            case "balanced" -> 16;
            case "cinematic" -> 32;
            default -> 24;
        };
    }

    /** The most the clouds may cover, from {@code earthplatform.clouds.opacity}, kept within 0.2 to 0.98. */
    static float opacity(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? (float) Math.clamp(parsed, .2, .98) : .72f;
        }
        catch (NumberFormatException | NullPointerException invalid) { return .72f; }
    }

    /** Draws the clouds for this frame; false when it can't, and the plain deck draws instead. */
    static boolean draw(ClientLevel level, Matrix4f view, Matrix4f projection, float partialTick, double camX, double camY, double camZ, int height) {
        ShaderInstance cloud = shader();
        if (cloud == null) return false;
        int depth = CloudDepth.SCENE.texture();
        if (depth == 0) return false;
        Vec3 colour = level.getCloudColor(partialTick);
        double ticks = level.getGameTime() + partialTick;
        double angle = level.getTimeOfDay(partialTick) * Math.PI * 2;
        configure(cloud, view, projection, camX, camY, camZ, ticks, height, (float) colour.x, (float) colour.y, (float) colour.z,
                (float) -Math.sin(angle), (float) Math.cos(angle), .2f,
                steps(System.getProperty("earthplatform.clouds.quality", "high")),
                opacity(System.getProperty("earthplatform.clouds.opacity", "0.72")), level.getRainLevel(partialTick));
        var textureManager = Minecraft.getInstance().getTextureManager();
        RenderSystem.setShaderTexture(0, texture(0));
        cloud.setSampler("Weather1", textureManager.getTexture(texture(1)).getId());
        cloud.setSampler("Weather2", textureManager.getTexture(texture(2)).getId());
        cloud.setSampler("SceneDepth", depth);

        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), blend = GL11.glIsEnabled(GL11.GL_BLEND), depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
        int previousVolume = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        int previousSampler = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, NOISE_UNIT);
        GL33.glBindSampler(NOISE_UNIT, 0);
        if (noiseTexture == 0) noiseTexture = createNoiseTexture();
        else GL11.glBindTexture(GL12.GL_TEXTURE_3D, noiseTexture);
        GlStateManager._activeTexture(active);
        ShaderInstance previousShader = RenderSystem.getShader();
        RenderSystem.setShader(() -> cloud);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // The march stops at the scene's depth itself, so the depth test is off.
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        try {
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            buffer.addVertex(-1, -1, 0).setUv(0, 0).setColor(255, 255, 255, 255);
            buffer.addVertex(1, -1, 0).setUv(1, 0).setColor(255, 255, 255, 255);
            buffer.addVertex(1, 1, 0).setUv(1, 1).setColor(255, 255, 255, 255);
            buffer.addVertex(-1, 1, 0).setUv(0, 1).setColor(255, 255, 255, 255);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        } finally {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousVolume);
            GL33.glBindSampler(NOISE_UNIT, previousSampler);
            GlStateManager._activeTexture(active);
            RenderSystem.depthMask(depthMask);
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            RenderSystem.setShader(() -> previousShader);
        }
        return true;
    }

    /** The cloud shader, loaded again after a resource reload replaces the game's shaders; null if it failed to load. */
    private static ShaderInstance shader() {
        ShaderInstance base = GameRenderer.getPositionTexColorShader();
        if (base != baseShader) {
            if (shader != null) shader.close();
            shader = null;
            baseShader = base;
            shaderFailed = false;
        }
        if (shader == null && !shaderFailed) {
            try {
                shader = load(Minecraft.getInstance().getResourceManager());
            } catch (java.io.IOException | RuntimeException failure) {
                shaderFailed = true;
                EarthPlatformMod.LOGGER.warn("The layered cloud shader could not load; using the plain cloud deck", failure);
            }
        }
        return shader;
    }

    /** Loads the shader from the mod's namespace; the String constructor works on both loaders. */
    private static ShaderInstance load(ResourceProvider resources) throws java.io.IOException {
        return new ShaderInstance(location -> resources.getResource(
                location.getPath().startsWith("shaders/core/earthplatform_weather")
                        ? ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, location.getPath()) : location),
                "earthplatform_weather", DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    /** Bank {@code kind}'s coverage texture, made on first use from {@link CloudField#density}. */
    private static ResourceLocation texture(int kind) {
        ResourceLocation id = textures[kind];
        if (id != null) return id;
        NativeImage image = new NativeImage(TEXTURE_SIZE, TEXTURE_SIZE, false);
        for (int y = 0; y < TEXTURE_SIZE; y++)
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int alpha = (int) Math.round(CloudField.density(kind, (x + .5) / TEXTURE_SIZE, (y + .5) / TEXTURE_SIZE) * 255);
                image.setPixelRGBA(x, y, alpha << 24 | 0xFFFFFF);
            }
        id = ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "weather_cloud_" + kind);
        var textureManager = Minecraft.getInstance().getTextureManager();
        textureManager.register(id, new DynamicTexture(image));
        textureManager.getTexture(id).bind();
        // The shader samples level 0 only, so no mipmaps.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        textures[kind] = id;
        return id;
    }

    /** A repeating 64-cubed noise lattice, about 256 KiB, filtered by the GPU; left bound to the active unit. */
    private static int createNoiseTexture() {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        ByteBuffer bytes = MemoryUtil.memAlloc(64 * 64 * 64);
        try {
            CloudWeather.writeNoise(bytes);
            bytes.flip();
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, 64, 64, 64, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, bytes);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
        } finally { MemoryUtil.memFree(bytes); }
        return texture;
    }

    private static void configure(ShaderInstance shader, Matrix4f view, Matrix4f projection, double x, double y, double z, double ticks, double height,
                                  float r, float g, float b, float sunX, float sunY, float sunZ, int steps, float opacity, float rain) {
        shader.safeGetUniform("SkyInvProjection").set(new Matrix4f(projection).invert());
        shader.safeGetUniform("SkyInvView").set(new Matrix4f(view).invert());
        shader.safeGetUniform("CameraHeight").set((float) y);
        shader.safeGetUniform("CloudLight").set(r, g, b);
        shader.safeGetUniform("SunDirection").set(sunX, sunY, sunZ);
        shader.safeGetUniform("MarchSteps").set(Math.clamp(steps, 8, 32));
        shader.safeGetUniform("MaxOpacity").set(opacity);
        shader.safeGetUniform("NoiseVolume").set(NOISE_UNIT);
        // The phases are fractions of each texture's period, so the patterns stay fixed to the world as the camera moves.
        shader.safeGetUniform("WeatherPhase").set((float) CloudWeather.frac(x / CloudWeather.REGION_PERIOD), (float) CloudWeather.frac(z / CloudWeather.REGION_PERIOD),
                (float) CloudWeather.frac(ticks / CloudWeather.TIME_PERIOD), Math.clamp(rain, 0, 1) * .2f);
        var bands = CloudField.bands(height);
        for (int i = 0; i < bands.length; i++) {
            var band = bands[i];
            shader.safeGetUniform("Band" + i).set((float) band.height(), (float) band.thickness(), (float) band.period(), (float) band.opacity());
            double c = Math.cos(band.turn()), s = Math.sin(band.turn());
            shader.safeGetUniform("Phase" + i).set((float) CloudWeather.frac((c * x - s * z + ticks * band.wind()) / band.period()),
                    (float) CloudWeather.frac((s * x + c * z + ticks * band.wind() * .23) / band.period()));
        }
        shader.safeGetUniform("NoisePhase0").set((float) CloudWeather.frac(x / 18000), (float) CloudWeather.frac(y / 18000), (float) CloudWeather.frac(z / 18000));
        shader.safeGetUniform("NoisePhase1").set((float) CloudWeather.frac(x / 4500), (float) CloudWeather.frac(y / 4500), (float) CloudWeather.frac(z / 4500));
    }
}
