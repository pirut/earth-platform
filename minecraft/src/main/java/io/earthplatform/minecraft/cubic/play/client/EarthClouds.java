package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Clouds for the Earth, where the ground is real height: vanilla's cloud deck at Y 192 would sit a
 * hundred metres over the lowlands and below most hills. Here the deck floats at a set height above
 * sea level (2500 m by default), and at least {@value #ABOVE_GROUND} m over the ground you stand on,
 * so high plateaus and mountains get a sky too; it follows the ground slowly, like weather, not like
 * a ceiling. Cells are scaled up to match and the edges fade into the sky, so it reads as weather
 * kilometres overhead. Other worlds keep vanilla clouds, and a shader pack keeps its own.
 */
public final class EarthClouds {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    private static final boolean IRIS = ModList.get().isLoaded("iris");
    /** Sea level: 0 m is the water surface at Y 63. */
    private static final double SEA = 63;
    /** One cloud texture pixel, in metres (vanilla's is 12 blocks). */
    private static final double CELL = 96;
    /** Wind, in metres per tick. */
    private static final double WIND = 0.5;
    private static final int GRID = 32;
    /** Least height of the deck over the player's ground, in metres. */
    private static final double ABOVE_GROUND = 1200;
    /** Seconds for the deck to settle after the ground under the player changes height. */
    private static final double SETTLE = 60;
    private static double ground = Double.NaN;
    private static long lastNanos;

    /**
     * Where the Earth's sky starts to darken: vanilla turns the lower sky black below Y 63 and fogs
     * everything below Y 32, which in a world with real heights blacks out Death Valley, the Dead Sea
     * shore and the Caspian. Here that only happens a kilometre below sea level, deeper than any dry
     * land on Earth.
     */
    public static final double HORIZON_Y = SEA - 1000;

    private EarthClouds() {}

    /** True for the cubic dimension, whose sky follows real heights. */
    public static boolean earthSky(Object level) {
        return level instanceof ClientLevel client && Cubic.isCubic(client.dimension());
    }

    /** Draws the Earth's clouds instead of vanilla's; false leaves the frame to vanilla. */
    public static boolean render(ClientLevel level, Matrix4f view, Matrix4f projection, float partialTick, double camX, double camY, double camZ) {
        if (level == null || !Cubic.isCubic(level.dimension())) return false;
        int height = EarthConfig.CLOUD_HEIGHT.get();
        if (height <= 0) return false;
        if (IRIS && net.irisshaders.iris.Iris.isPackInUseQuick()) return false;

        double y = Math.max(SEA + height, followGround(level, camY) + ABOVE_GROUND) - camY;
        // Far enough that the faded rim sits low on the horizon, near enough to keep cells visible.
        double radius = Math.max(8_000, 6 * Math.abs(y));
        double period = 256 * CELL;
        double drift = (level.getGameTime() + partialTick) * WIND;
        double baseU = frac((camX + drift) / period), baseV = frac(camZ / period);
        Vec3 colour = level.getCloudColor(partialTick);
        float r = (float) colour.x, g = (float) colour.y, b = (float) colour.z;

        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TEXTURE);
        Minecraft.getInstance().getTextureManager().getTexture(TEXTURE).bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);

        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        double step = 2 * radius / GRID;
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                double x0 = -radius + i * step, z0 = -radius + j * step;
                vertex(buffer, view, x0, y, z0, radius, period, baseU, baseV, r, g, b);
                vertex(buffer, view, x0, y, z0 + step, radius, period, baseU, baseV, r, g, b);
                vertex(buffer, view, x0 + step, y, z0 + step, radius, period, baseU, baseV, r, g, b);
                vertex(buffer, view, x0 + step, y, z0, radius, period, baseU, baseV, r, g, b);
            }
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.restoreProjectionMatrix();
        return true;
    }

    /** The ground height the deck keeps clear of: where the player last stood, eased over time. */
    private static double followGround(ClientLevel level, double camY) {
        var player = Minecraft.getInstance().player;
        long now = System.nanoTime();
        double seconds = lastNanos == 0 ? 0 : Math.min(1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (player == null || player.level() != level) return Double.isNaN(ground) ? camY : ground;
        double feet = player.getY();
        // Jumps to the first spot and after travel; otherwise eases toward wherever the player stands.
        if (Double.isNaN(ground) || Math.abs(feet - ground) > 3000) ground = feet;
        else if (player.onGround()) ground += (feet - ground) * (1 - Math.exp(-seconds / SETTLE));
        return ground;
    }

    private static void vertex(BufferBuilder buffer, Matrix4f view, double x, double y, double z, double radius, double period,
                               double baseU, double baseV, float r, float g, float b) {
        double distance = Math.sqrt(x * x + z * z) / radius;
        // Solid overhead, fading out over the outer half so no edge shows.
        double fade = 1 - smooth((distance - 0.45) / 0.55);
        buffer.addVertex(view, (float) x, (float) y, (float) z)
                .setUv((float) (baseU + x / period), (float) (baseV + z / period))
                .setColor(r, g, b, (float) (0.8 * fade));
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private static double frac(double value) { return value - Math.floor(value); }
}
