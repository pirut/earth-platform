package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.client.FarBlockDepth;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import io.earthplatform.minecraft.platform.Loader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/** Depth-clipped, lit cloud volumes at several world-fixed heights. Packs keep their own clouds. */
public final class EarthClouds {
    private static final boolean IRIS = Loader.INSTANCE.isModLoaded("iris");
    private static final int TEXTURE_SIZE = 256;
    public static final double HORIZON_Y = 63 - 1000;
    private static final ResourceLocation[] textures = new ResourceLocation[3];
    private static ShaderInstance cloudShader, baseShader;
    private static boolean shaderFailed;
    private static int noiseTexture;
    private EarthClouds() {}

    public static double horizon() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera.isInitialized() && camera.getFluidInCamera() == FogType.WATER) return Math.min(HORIZON_Y, camera.getPosition().y - 1);
        return HORIZON_Y;
    }

    public static boolean earthSky(Object level) { return level instanceof ClientLevel client && Cubic.isCubic(client.dimension()); }

    private static boolean active() {
        return Boolean.parseBoolean(System.getProperty("earthplatform.clouds.layered", "true"))
                && !(IRIS && net.irisshaders.iris.Iris.isPackInUseQuick());
    }

    private static ResourceLocation texture(int kind) {
        ResourceLocation id = textures[kind];
        if (id != null) return id;
        NativeImage image = new NativeImage(TEXTURE_SIZE, TEXTURE_SIZE, false);
        for (int y = 0; y < TEXTURE_SIZE; y++) for (int x = 0; x < TEXTURE_SIZE; x++) {
            int alpha = (int) Math.round(CloudField.density(kind, (x + .5) / TEXTURE_SIZE, (y + .5) / TEXTURE_SIZE) * 255);
            image.setPixelRGBA(x, y, alpha << 24 | 0xFFFFFF);
        }
        id = ResourceLocation.fromNamespaceAndPath("earthplatform", "weather_cloud_" + kind);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        Minecraft.getInstance().getTextureManager().getTexture(id).bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 8);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        textures[kind] = id;
        return id;
    }

    private static ShaderInstance shader() {
        ShaderInstance base = GameRenderer.getPositionTexColorShader();
        if (base != baseShader) {
            if (cloudShader != null) cloudShader.close();
            cloudShader = null; baseShader = base; shaderFailed = false;
        }
        if (cloudShader == null && !shaderFailed) {
            try {
                cloudShader = loadShader(Minecraft.getInstance().getResourceManager());
            } catch (java.io.IOException | RuntimeException failure) {
                shaderFailed = true;
                EarthPlatformMod.LOGGER.warn("Cloud volume shader could not load; using vanilla clouds", failure);
            }
        }
        return cloudShader;
    }

    /** The String constructor and provider are shared by NeoForge and Fabric. */
    public static ShaderInstance loadShader(ResourceProvider resources) throws java.io.IOException {
        return new ShaderInstance(location -> resources.getResource(
                location.getPath().startsWith("shaders/core/earthplatform_weather")
                        ? ResourceLocation.fromNamespaceAndPath("earthplatform", location.getPath()) : location),
                "earthplatform_weather", DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    public static boolean render(ClientLevel level, Matrix4f view, Matrix4f projection, float partialTick, double camX, double camY, double camZ) {
        if (level == null || !Cubic.isCubic(level.dimension())) return false;
        int configured = EarthConfig.CLOUD_HEIGHT.get();
        if (configured <= 0 || !active()) return false;
        if (Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) return true;
        ShaderInstance cloud = shader();
        if (cloud == null || !FarBlockDepth.copy()) return false;
        ClientCubeWorld world = ClientCubeWorld.current();
        float curvature = world == null ? 0 : world.far().curvature();
        Vec3 colour = level.getCloudColor(partialTick);
        double ticks = level.getGameTime() + partialTick;
        double angle = level.getTimeOfDay(partialTick) * Math.PI * 2;
        float sunX = (float) -Math.sin(angle), sunY = (float) Math.cos(angle);
        int steps = CloudVolume.steps(System.getProperty("earthplatform.clouds.quality", "high"));
        float opacity = (float) Math.clamp(Double.parseDouble(System.getProperty("earthplatform.clouds.opacity", "0.72")), 0, .98);
        CloudVolume.configure(cloud, view, projection, camX, camY, camZ, ticks, configured,
                (float) colour.x, (float) colour.y, (float) colour.z, sunX, sunY, .2f, curvature, steps, opacity);
        CloudVolume.configureWeather(cloud, camX, camZ, ticks, level.getRainLevel(partialTick));
        RenderSystem.setShaderTexture(0, texture(0));
        cloud.setSampler("Weather1", Minecraft.getInstance().getTextureManager().getTexture(texture(1)).getId());
        cloud.setSampler("Weather2", Minecraft.getInstance().getTextureManager().getTexture(texture(2)).getId());
        cloud.setSampler("SceneDepth", FarBlockDepth.texture());
        int farDepth = world == null ? 0 : world.far().cloudDepthTexture();
        cloud.setSampler("FarDepth", farDepth == 0 ? FarBlockDepth.texture() : farDepth);
        if (farDepth != 0) {
            cloud.getUniform("UseFarDepth").set(1f);
            cloud.getUniform("FarInvProjection").set(new Matrix4f(world.far().cloudDepthProjection()).invert());
        }
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE5);
        int previousVolume = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        int previousSampler = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 5);
        GL33.glBindSampler(5, 0);
        if (noiseTexture == 0) noiseTexture = CloudVolume.createNoiseTexture();
        else GL11.glBindTexture(GL12.GL_TEXTURE_3D, noiseTexture);
        GlStateManager._activeTexture(active);
        ShaderInstance previousShader = RenderSystem.getShader();
        RenderSystem.setShader(() -> cloud);
        RenderSystem.disableCull(); RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableDepthTest(); RenderSystem.depthMask(false);
        try {
            BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            buffer.addVertex(-1, -1, 0).setUv(0, 0).setColor(255, 255, 255, 255);
            buffer.addVertex(1, -1, 0).setUv(1, 0).setColor(255, 255, 255, 255);
            buffer.addVertex(1, 1, 0).setUv(1, 1).setColor(255, 255, 255, 255);
            buffer.addVertex(-1, 1, 0).setUv(0, 1).setColor(255, 255, 255, 255);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        } finally {
            GlStateManager._activeTexture(GL13.GL_TEXTURE5);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previousVolume);
            GL33.glBindSampler(5, previousSampler);
            GlStateManager._activeTexture(active);
            RenderSystem.depthMask(depthMask);
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            RenderSystem.setShader(() -> previousShader);
        }
        return true;
    }

}
