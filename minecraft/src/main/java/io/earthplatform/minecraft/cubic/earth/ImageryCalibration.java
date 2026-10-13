package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.GroundPalette;
import io.earthplatform.geography.globe.ImagerySource;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.CubicSettings;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * A developer tool for tuning generation against the real Earth: {@code /cubes calibrate [size]}
 * renders, top-down around the player, the satellite view, the ground generation alone makes,
 * that ground with imagery applied, and how far generation's colour is from the satellite's, and
 * reports for each biome and ground block how its colour compares with what the satellite shows and
 * which vanilla ground block its bare patches match best. Output goes to
 * {@code earthplatform/calibration/} in the game folder.
 *
 * <p>Imagery is read here whatever the world's imagery setting, as this is where generation is
 * measured against it. Generated colours are each block's mean texture colour, grass and leaves
 * tinted by their biome, with trees covering as much ground as the far canopy shows; water is left
 * out of the statistics, while shade and dense forest, too dark to match a block, still count in
 * the colour gap.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class ImageryCalibration {
    private static final int PIXELS = 384;
    /** Colour distance (CIELAB, lightness weighted as in matching) above which a group is reported as off. */
    private static final double OFF = 12;
    private static final Map<BlockState, Integer> GROUND_RGB = new HashMap<>();
    static {
        for (GroundPalette.Ground ground : GroundPalette.Ground.values())
            GROUND_RGB.put(BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(ground.id())).defaultBlockState(), ground.rgb);
    }

    private ImageryCalibration() {}

    @SubscribeEvent static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cubes").then(Commands.literal("calibrate").requires(source -> source.hasPermission(2))
                .executes(context -> calibrate(context.getSource(), 2048))
                .then(Commands.argument("size", IntegerArgumentType.integer(64, 65536))
                        .executes(context -> calibrate(context.getSource(), IntegerArgumentType.getInteger(context, "size"))))));
    }

    private static int calibrate(CommandSourceStack source, int size) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getServer().getLevel(Cubic.LEVEL);
        if (level == null || !(CubicLevel.of(level) instanceof ServerCubeWorld cubes) || !(cubes.generator() instanceof EarthGenerator earth)) {
            source.sendFailure(Component.literal("This save's cubic world is not the Earth."));
            return 0;
        }
        BlockPos at = player.blockPosition();
        String place = earth.place(at.getX(), at.getZ());
        source.sendSuccess(() -> Component.literal("Calibrating " + size + " blocks around " + place + "...").withStyle(ChatFormatting.GRAY), false);
        Thread thread = new Thread(() -> {
            String result;
            try { result = run(earth, at.getX(), at.getZ(), size); }
            catch (RuntimeException | IOException failure) {
                EarthPlatformMod.LOGGER.warn("Calibration failed", failure);
                result = "Calibration failed: " + failure.getMessage();
            }
            String message = result;
            source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(message), false));
        }, "Earth calibration");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    /** Renders and reports one square; returns a one-line summary. */
    static String run(EarthGenerator earth, int cx, int cz, int size) throws IOException {
        return run(earth, CubicSettings.imagery(), FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("calibration"), null, cx, cz, size);
    }

    /** Renders and reports one square into a folder, named after its centre unless a name is given; also used without a game. */
    static String run(EarthGenerator earth, ImagerySource imagery, Path folder, @javax.annotation.Nullable String named, int cx, int cz, int size) throws IOException {
        ImageryGround ground = earth.imageryGround(imagery);
        double step = (double) size / PIXELS;
        int voxel = Math.max(1, Integer.highestOneBit((int) Math.max(1, step)));
        int zoom = ground.zoom(voxel);
        earth.prepare(cx, cz);
        imagery.prefetch(earth.grid(), cx - size / 2.0, cz - size / 2.0, cx + size / 2.0, cz + size / 2.0, zoom);

        BufferedImage image = new BufferedImage(4 * PIXELS + 24, PIXELS, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < image.getWidth(); i++) for (int j = 0; j < PIXELS; j++) image.setRGB(i, j, 0xFFFFFF);
        Map<String, Group> groups = new HashMap<>();
        double[] linear = new double[3];
        double totalOff = 0, changed = 0;
        int land = 0;
        for (int j = 0; j < PIXELS; j++) for (int i = 0; i < PIXELS; i++) {
            int x = (int) Math.floor(cx - size / 2.0 + (i + 0.5) * step), z = (int) Math.floor(cz - size / 2.0 + (j + 0.5) * step);
            int y = earth.surfaceY(x, z), water = earth.waterY(x, z);
            int slope = Math.abs(earth.surfaceY(x + 1, z) - earth.surfaceY(x - 1, z)) + Math.abs(earth.surfaceY(x, z + 1) - earth.surfaceY(x, z - 1));
            Surface plain = earth.surface(x, z, y, water, slope, voxel, null);
            Surface with = earth.surface(x, z, y, water, slope, voxel, ground);
            boolean wet = water != SurfaceGenerator.DRY && water > y;
            int plainRgb = wet ? 0x2B4F8C : colour(plain, x, z), withRgb = wet ? 0x2B4F8C : colour(with, x, z);
            double light = shade(earth, x, z, Math.max(1, (int) step));
            image.setRGB(PIXELS + 8 + i, j, scale(plainRgb, light));
            image.setRGB(2 * PIXELS + 16 + i, j, scale(withRgb, light));
            double px = earth.grid().pixelX(x + 0.5, zoom), py = earth.grid().pixelY(z + 0.5, zoom);
            int span = Math.max(1, (int) Math.round(step / earth.grid().blocksPerPixel(zoom)));
            if (!imagery.mean(px, py, zoom, span, linear)) { image.setRGB(i, j, 0); continue; }
            image.setRGB(i, j, rgb(linear));
            if (wet) continue;
            GroundPalette.Reading satellite = GroundPalette.read(linear);
            double off = distance(satellite, plainRgb);
            image.setRGB(3 * PIXELS + 24 + i, j, heat(off));
            land++;
            totalOff += off;
            if (with.top() != plain.top() || with.trees() != plain.trees()) changed++;
            String key = BuiltInRegistries.BLOCK.getKey(plain.top().getBlock()).getPath() + (plain.trees() > 0 ? " + trees" : "") + " in "
                    + plain.biome().unwrapKey().map(k -> k.location().getPath()).orElse("?");
            Group group = groups.computeIfAbsent(key, Group::new);
            group.add(satellite, linear, plainRgb, off);
        }

        GeoPoint centre = earth.grid().geographic(cx, cz);
        Files.createDirectories(folder);
        String name = named != null ? named : String.format(Locale.ROOT, "%.4f_%.4f_%d", centre.latitude(), centre.longitude(), size);
        ImageIO.write(image, "png", folder.resolve(name + ".png").toFile());
        double meanOff = land == 0 ? 0 : totalOff / land;
        Files.writeString(folder.resolve(name + ".txt"), report(centre, size, land, meanOff, changed, groups));
        return String.format(Locale.ROOT, "Calibration of %s: generated ground is %.1f off the satellite on average; imagery would change %.0f%% of land. Saved earthplatform/calibration/%s.png and .txt",
                centre, meanOff, land == 0 ? 0 : 100 * changed / land, name);
    }

    private static String report(GeoPoint centre, int size, int land, double meanOff, double changed, Map<String, Group> groups) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Calibration at %s, %d blocks square. Panels: satellite | generation alone | with imagery | colour gap (blue none, red %.0f+)%n",
                centre, size, 2 * OFF));
        out.append(String.format(Locale.ROOT, "Land pixels read: %d; mean colour gap %.1f; imagery would change %.0f%% of them.%n%n",
                land, meanOff, land == 0 ? 0 : 100 * changed / land));
        out.append("share  gap    colour gen / sat     lightness gen/sat  green gen/sat   bare ground there looks like            group\n");
        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> Double.compare(b.count, a.count));
        for (Group g : sorted) {
            if (g.count * 100 < land) continue;
            out.append(String.format(Locale.ROOT, "%4.0f%%  %4.1f%s  %s / %s   %5.1f / %5.1f     %5.3f / %5.3f   %-38s  %s%n",
                    100.0 * g.count / land, g.off / g.count, g.off / g.count > OFF ? "*" : " ", hex(g.generated()), hex(g.satellite()),
                    g.genL / g.count, g.satL / g.count, g.genGreen / g.count, g.satGreen / g.count, g.matches(), g.key));
        }
        out.append("\n* colour gap above ").append((int) OFF).append(": the group looks unlike the place. Lightness is CIELAB L*; green is the excess green\n")
                .append("of sRGB chromaticity (about 0 for bare ground, 0.1 and up for lush plants). 'Bare ground there looks like' lists, for the\n")
                .append("group's pixels the satellite shows bare (not green, not shadow), the nearest vanilla ground blocks with shares and the bare share.\n");
        return out.toString();
    }

    /** Pixels of one generated block and biome, with what the satellite shows there. */
    private static final class Group {
        final String key;
        int count, bare;
        double off, genL, satL, genGreen, satGreen, r, g, b, sr, sg, sb;
        final int[] matches = new int[GroundPalette.Ground.values().length];

        Group(String key) { this.key = key; }

        void add(GroundPalette.Reading satellite, double[] linear, int generated, double off) {
            count++;
            this.off += off;
            GroundPalette.Reading made = GroundPalette.read(new double[]{linear(generated >> 16 & 255), linear(generated >> 8 & 255), linear(generated & 255)});
            genL += made.lightness(); satL += satellite.lightness();
            genGreen += made.greenness(); satGreen += satellite.greenness();
            r += generated >> 16 & 255; g += generated >> 8 & 255; b += generated & 255;
            int sat = rgb(linear);
            sr += sat >> 16 & 255; sg += sat >> 8 & 255; sb += sat & 255;
            if (!satellite.shadow() && !satellite.green()) {
                bare++;
                matches[GroundPalette.match(satellite, GroundPalette.Ground.values(), 0).ordinal()]++;
            }
        }

        int generated() { return (int) (r / count) << 16 | (int) (g / count) << 8 | (int) (b / count); }

        int satellite() { return (int) (sr / count) << 16 | (int) (sg / count) << 8 | (int) (sb / count); }

        String matches() {
            StringBuilder text = new StringBuilder();
            Integer[] order = new Integer[matches.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> matches[b] - matches[a]);
            if (bare * 10 < count) return "(mostly green or shadow)";
            for (int k = 0; k < 3 && matches[order[k]] * 10 >= bare; k++)
                text.append(k == 0 ? "" : ", ").append(GroundPalette.Ground.values()[order[k]].id()).append(' ').append(100 * matches[order[k]] / bare).append('%');
            return text.append(" (bare ").append(100 * bare / count).append("%)").toString();
        }
    }

    /** A column's colour from above: its top block, under as much canopy as the far view shows. */
    static int colour(Surface surface, int x, int z) {
        BlockState top = surface.top();
        int ground;
        Integer known = GROUND_RGB.get(top);
        if (known != null) ground = known;
        else if (top.is(Blocks.GRASS_BLOCK)) ground = tint(surface.biome().value().getGrassColor(x, z), 0.58);
        else ground = top.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        // Crowns cover a bit over half of each tree's cell, as the far canopy shows them.
        double canopy = Math.min(1, surface.trees() * 0.006);
        if (canopy <= 0) return ground;
        int leaves = tint(surface.biome().value().getFoliageColor(), 0.48);
        return blend(ground, leaves, canopy);
    }

    private static double distance(GroundPalette.Reading satellite, int rgb) {
        double[] lab = GroundPalette.lab(linear(rgb >> 16 & 255), linear(rgb >> 8 & 255), linear(rgb & 255));
        double dl = (satellite.lightness() - lab[0]) * 0.45, da = satellite.a() - lab[1], db = satellite.b() - lab[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    private static double shade(EarthGenerator earth, int x, int z, int d) {
        double dx = earth.surfaceY(x + d, z) - earth.surfaceY(x - d, z), dz = earth.surfaceY(x, z + d) - earth.surfaceY(x, z - d);
        return Math.clamp(1 + (dx + dz) / (2.0 * d) * 0.35, 0.55, 1.25);
    }

    private static int heat(double off) {
        double t = Math.clamp(off / (2 * OFF), 0, 1);
        return (int) (40 + 215 * t) << 16 | (int) (60 + 80 * (1 - Math.abs(2 * t - 1))) << 8 | (int) (200 * (1 - t));
    }

    private static int tint(int rgb, double factor) { return scale(rgb, factor); }

    private static int blend(int a, int b, double t) {
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t), g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t),
                bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }

    private static int scale(int rgb, double factor) {
        int r = (int) Math.min(255, (rgb >> 16 & 255) * factor), g = (int) Math.min(255, (rgb >> 8 & 255) * factor), b = (int) Math.min(255, (rgb & 255) * factor);
        return r << 16 | g << 8 | b;
    }

    private static double linear(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int rgb(double[] linear) {
        int r = (int) Math.round(255 * GroundPalette.srgb(linear[0])), g = (int) Math.round(255 * GroundPalette.srgb(linear[1])),
                b = (int) Math.round(255 * GroundPalette.srgb(linear[2]));
        return Math.clamp(r, 0, 255) << 16 | Math.clamp(g, 0, 255) << 8 | Math.clamp(b, 0, 255);
    }

    private static String hex(int rgb) { return String.format(Locale.ROOT, "#%06X", rgb); }
}
