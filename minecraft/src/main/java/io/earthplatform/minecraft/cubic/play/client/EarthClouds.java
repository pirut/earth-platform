package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import java.util.Arrays;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Clouds for the Earth, where the ground is real height: vanilla's cloud deck at Y 192 would sit a
 * hundred metres over the lowlands and below most hills. Here the deck floats at a set height above
 * sea level (2500 m by default), and at least {@value #ABOVE_GROUND} m over the ground you stand on,
 * so high plateaus and mountains get a sky too; it follows the ground slowly, like weather, not like
 * a ceiling. Cells are scaled up to match and the edges fade into the sky, so it reads as weather
 * kilometres overhead. Two layers of the texture, turned and sized apart, are blurred together: one
 * layer of hard squares a hundred metres wide read as paper cut-outs, and its diagonal streaks as a
 * deck tilted toward the horizon. Wherever the far terrain reaches up near the deck (Aconcagua from its foot,
 * the Himalaya) the cloud thins away around the slopes, so peaks stand above the clouds rather than
 * being sliced by them. Other worlds keep vanilla clouds, and a shader pack keeps its own.
 */
public final class EarthClouds {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    /** Vanilla's cloud texture made white throughout, so blurring it doesn't darken the edges (its clear pixels are black). */
    private static final ResourceLocation SOFT = ResourceLocation.fromNamespaceAndPath("earthplatform", "earth_clouds");
    private static ResourceLocation softTexture;
    private static final boolean IRIS = ModList.get().isLoaded("iris");
    /** Sea level: 0 m is the water surface at Y 63. */
    private static final double SEA = 63;
    /** One cloud texture pixel, in metres (vanilla's is 12 blocks). */
    private static final double CELL = 96;
    /** Wind, in metres per tick. */
    private static final double WIND = 0.5;
    static final int GRID = 32;
    /** Least height of the deck over the player's ground, in metres. */
    private static final double ABOVE_GROUND = 1200;
    /** Seconds for the deck to settle after the ground under the player changes height. */
    private static final double SETTLE = 60;
    /** Cloud fades out where the ground below comes within this many metres of it... */
    private static final double CLEAR = 150;
    /** ...over this much more height, so mountains rise through the deck instead of cutting it. */
    private static final double CLEAR_FADE = 450;
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

    /**
     * The horizon height for the Earth's sky this frame: {@link #HORIZON_Y}, or just below the
     * camera when it is under water deeper than that, so the trench floors (Challenger Deep at
     * -10.9 km) see water-tinted sky past the loaded terrain rather than a black band.
     */
    public static double horizon() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera.isInitialized() && camera.getFluidInCamera() == FogType.WATER) return Math.min(HORIZON_Y, camera.getPosition().y - 1);
        return HORIZON_Y;
    }

    /** The white cloud texture, made from vanilla's on first use; vanilla's itself if that fails. */
    private static ResourceLocation soft() {
        if (softTexture != null) return softTexture;
        softTexture = TEXTURE;
        var minecraft = Minecraft.getInstance();
        try (var in = minecraft.getResourceManager().open(TEXTURE)) {
            NativeImage image = NativeImage.read(in);
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++)
                    image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) | 0x00FFFFFF);
            minecraft.getTextureManager().register(SOFT, new DynamicTexture(image));
            softTexture = SOFT;
        } catch (java.io.IOException | RuntimeException failure) {
            // Keep vanilla's texture.
        }
        return softTexture;
    }

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
        // This deck isn't fogged, so from under water, lava or powder snow it would show through
        // as a white speckled sheet far overhead; nothing that high can be seen from there anyway.
        if (Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) return true;

        double under = followGround(level);
        double deck = Double.isNaN(under) ? SEA + height : Math.max(SEA + height, under + ABOVE_GROUND);
        double y = deck - camY;
        // Far enough that the faded rim sits low on the horizon, near enough to keep cells visible.
        double radius = Math.max(8_000, 6 * Math.abs(y));
        double drift = (level.getGameTime() + partialTick) * WIND;
        Vec3 colour = level.getCloudColor(partialTick);
        float r = (float) colour.x, g = (float) colour.y, b = (float) colour.z;

        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        // The vertices carry the camera's turn (view); the game's model-view already holds it during
        // the level's render, and applying it twice tilted the deck by the camera's pitch.
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        ResourceLocation texture = soft();
        RenderSystem.setShaderTexture(0, texture);
        Minecraft.getInstance().getTextureManager().getTexture(texture).bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);

        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        double step = 2 * radius / GRID;
        float[] clear = clearance(deck, camX, camZ, radius, step);
        for (Layer layer : LAYERS) {
            double period = 256 * CELL * layer.size, cos = Math.cos(layer.turn), sin = Math.sin(layer.turn);
            double worldX = camX + drift, worldZ = camZ;
            double baseU = frac((cos * worldX - sin * worldZ) / period + layer.shift), baseV = frac((sin * worldX + cos * worldZ) / period);
            for (int i = 0; i < GRID; i++) {
                for (int j = 0; j < GRID; j++) {
                    double x0 = -radius + i * step, z0 = -radius + j * step;
                    vertex(buffer, view, layer, x0, y, z0, radius, period, baseU, baseV, r, g, b, clear[i * (GRID + 1) + j]);
                    vertex(buffer, view, layer, x0, y, z0 + step, radius, period, baseU, baseV, r, g, b, clear[i * (GRID + 1) + j + 1]);
                    vertex(buffer, view, layer, x0 + step, y, z0 + step, radius, period, baseU, baseV, r, g, b, clear[(i + 1) * (GRID + 1) + j + 1]);
                    vertex(buffer, view, layer, x0 + step, y, z0, radius, period, baseU, baseV, r, g, b, clear[(i + 1) * (GRID + 1) + j]);
                }
            }
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
        return true;
    }

    /**
     * The last clearance worked out and what it was worked out from: the far columns held, the deck,
     * and the camera's place. Standing still it stays as it is, and working it out again from tens
     * of thousands of far columns every frame took most of the render thread without a shader pack.
     */
    private static float[] lastClear;
    private static Object lastFar;
    private static int lastTops;
    private static double lastDeck, lastX, lastZ, lastRadius;

    /**
     * For each grid corner, how much cloud the ground lets through: 1 where the far terrain in the
     * cells around it stays well below the deck, down to 0 where it reaches the deck. Every held far
     * column counts toward each cell it overlaps, so no peak slips between samples.
     */
    private static float[] clearance(double deck, double camX, double camZ, double radius, double step) {
        ClientCubeWorld world = ClientCubeWorld.current();
        if (world != null && lastClear != null && lastFar == world.far() && lastTops == world.far().topsVersion()
                && lastDeck == deck && lastX == camX && lastZ == camZ && lastRadius == radius) return lastClear;
        float[] clear = new float[(GRID + 1) * (GRID + 1)];
        Arrays.fill(clear, 1);
        if (world == null) return clear;
        int[] high = new int[GRID * GRID];
        Arrays.fill(high, Integer.MIN_VALUE);
        double left = camX - radius, near = camZ - radius;
        world.far().forEachTop((x0, z0, width, top) -> Cells.raise(high, left, near, step, x0, z0, width, top));
        for (int i = 0; i <= GRID; i++) {
            for (int j = 0; j <= GRID; j++) {
                int top = Integer.MIN_VALUE;
                for (int ci = i - 1; ci <= i; ci++)
                    for (int cj = j - 1; cj <= j; cj++)
                        if (ci >= 0 && cj >= 0 && ci < GRID && cj < GRID) top = Math.max(top, high[ci * GRID + cj]);
                if (top != Integer.MIN_VALUE) clear[i * (GRID + 1) + j] = (float) smooth((deck - top - CLEAR) / CLEAR_FADE);
            }
        }
        lastClear = clear;
        lastFar = world.far();
        lastTops = world.far().topsVersion();
        lastDeck = deck;
        lastX = camX;
        lastZ = camZ;
        lastRadius = radius;
        return clear;
    }

    /** The deck's grid of cells as far columns raise them; apart, so it loads without the game. */
    static final class Cells {
        private Cells() {}

        /**
         * Raises the grid cells a far column overlaps (its square from x0, z0, the grid's corner at
         * left, near) to its top, where it stands higher.
         */
        static void raise(int[] high, double left, double near, double step, int x0, int z0, int width, int top) {
            // A column wholly off the grid overlaps no cell, as the floors below would find: most of the
            // coarser levels' columns are, and skipping them skips their divisions.
            double past = (GRID + 1) * step;
            if (x0 + width - left < 0 || x0 - left >= past || z0 + width - near < 0 || z0 - near >= past) return;
            int i0 = Math.max(0, (int) Math.floor((x0 - left) / step)), i1 = Math.min(GRID - 1, (int) Math.floor((x0 + width - left) / step));
            int j0 = Math.max(0, (int) Math.floor((z0 - near) / step)), j1 = Math.min(GRID - 1, (int) Math.floor((z0 + width - near) / step));
            for (int i = i0; i <= i1; i++)
                for (int j = j0; j <= j1; j++)
                    if (top > high[i * GRID + j]) high[i * GRID + j] = top;
        }
    }

    /**
     * The ground height the deck keeps clear of: where the player last stood, eased over time, or NaN
     * before they have stood anywhere (a spectator, or someone arriving in the air).
     */
    private static double followGround(ClientLevel level) {
        var player = Minecraft.getInstance().player;
        long now = System.nanoTime();
        double seconds = lastNanos == 0 ? 0 : Math.min(1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (player == null || player.level() != level) return ground;
        double feet = player.getY();
        // Jumps to the first spot and after travel; otherwise eases toward wherever the player stands.
        // Only standing counts: flying up to the jet stream must not lift the deck along.
        if (!player.onGround()) return ground;
        if (Double.isNaN(ground) || Math.abs(feet - ground) > 3000) ground = feet;
        else ground += (feet - ground) * (1 - Math.exp(-seconds / SETTLE));
        return ground;
    }

    /** One layer of the deck: its cells' size relative to {@link #CELL}, its turn in radians, where its texture starts, its opacity. */
    private record Layer(double size, double turn, double shift, double opacity) {}

    private static final Layer[] LAYERS = {new Layer(1.0, 0.0, 0.0, 0.55), new Layer(0.7, 1.9, 0.37, 0.5)};

    private static void vertex(BufferBuilder buffer, Matrix4f view, Layer layer, double x, double y, double z, double radius, double period,
                               double baseU, double baseV, float r, float g, float b, float clear) {
        double distance = Math.sqrt(x * x + z * z) / radius;
        // Solid overhead, fading out over the outer half so no edge shows.
        double fade = 1 - smooth((distance - 0.45) / 0.55);
        double cos = Math.cos(layer.turn), sin = Math.sin(layer.turn);
        buffer.addVertex(view, (float) x, (float) y, (float) z)
                .setUv((float) (baseU + (cos * x - sin * z) / period), (float) (baseV + (sin * x + cos * z) / period))
                .setColor(r, g, b, (float) (layer.opacity * fade * clear));
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private static double frac(double value) { return value - Math.floor(value); }
}
