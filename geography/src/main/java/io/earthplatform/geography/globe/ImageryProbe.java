package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.ImageIO;

/**
 * Renders a top-down comparison of how imagery changes the ground of a real place: the satellite
 * view, the ground from land cover alone, and the ground with imagery matched blocks and plant
 * density, side by side and lit from the north-west.
 *
 * <pre>java -cp ... io.earthplatform.geography.globe.ImageryProbe LAT LON SIZE_BLOCKS PIXELS OUT.png [CACHE_DIR]</pre>
 *
 * Block colours are each block's mean texture colour; grass and leaves are drawn in plain greens.
 * It is a diagnostic image, not a Minecraft screenshot.
 */
public final class ImageryProbe {
    private static final int WATER = 0x3F76E4, GRASS = 0x6E9A44, DRY_GRASS = 0xA4A55A, LEAVES = 0x2F5E22, NONE = 0x000000;

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: ImageryProbe LAT LON SIZE_BLOCKS PIXELS OUT.png [CACHE_DIR]");
            System.exit(2);
        }
        GeoPoint centre = new GeoPoint(Double.parseDouble(args[0]), Double.parseDouble(args[1]));
        double size = Double.parseDouble(args[2]);
        int pixels = Integer.parseInt(args[3]);
        Path cache = args.length > 5 ? Path.of(args[5]) : null;
        var grid = new MercatorGrid(1 / Math.cos(Math.toRadians(centre.latitude())));
        Duration timeout = Duration.ofSeconds(30);
        try (var tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache == null ? null : cache.resolve("terrarium-v1"), true, 512, 8, timeout);
             var cover = new WorldCoverSource(WorldCoverSource.AWS_WORLDCOVER, cache == null ? null : cache.resolve("esa-worldcover-v200"), true, 64, 8, timeout);
             var climate = new ClimateSource(ClimateSource.CHELSA, cache == null ? null : cache.resolve("chelsa"), true, 256, 4, timeout);
             var imagery = new ImagerySource(ImagerySource.EOX_S2_CLOUDLESS_2016, cache == null ? null : cache.resolve(ImagerySource.DATASET_ID), true, 256, 8, timeout)) {
            var elevation = new GlobalElevation(grid, tiles, TerrariumTileSource.MAX_ZOOM);
            double cx = grid.blockX(centre), cz = grid.blockZ(centre), step = size / pixels;
            imagery.prefetch(grid, cx - size / 2, cz - size / 2, cx + size / 2, cz + size / 2, ImagerySource.MAX_ZOOM);
            ClimateSource.Climate c = climate.at(centre.latitude(), centre.longitude(), 0);
            boolean hotDry = c != null && (c.koppen() == ClimateSource.BWH || c.koppen() == ClimateSource.BSH);
            var image = new BufferedImage(3 * pixels + 16, pixels, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < image.getWidth(); i++) for (int j = 0; j < pixels; j++) image.setRGB(i, j, 0xFFFFFF);
            double[] slope = new double[3], linear = new double[3];
            int[] counts = new int[GroundPalette.Ground.values().length];
            for (int j = 0; j < pixels; j++) for (int i = 0; i < pixels; i++) {
                double x = cx - size / 2 + (i + 0.5) * step, z = cz - size / 2 + (j + 0.5) * step;
                elevation.sample(x, z, slope);
                double steep = Math.hypot(slope[0], slope[1]);
                double light = Math.clamp(1 + (slope[0] + slope[1]) * 0.35, 0.55, 1.25);
                GeoPoint p = grid.geographic(x, z);
                int code = cover.code(p.latitude(), p.longitude(), 0);
                double px = grid.pixelX(x, ImagerySource.MAX_ZOOM), py = grid.pixelY(z, ImagerySource.MAX_ZOOM);
                int sat = imagery.pixel((long) Math.floor(px), (long) Math.floor(py), ImagerySource.MAX_ZOOM);
                image.setRGB(i, j, sat == ImagerySource.NO_DATA ? NONE : sat);
                double roll = (mix(Math.floorDiv((long) x, 1), Math.floorDiv((long) z, 1)) >>> 11) * 0x1.0p-53;
                int before = landCover(code, steep, hotDry, roll);
                int after = before;
                GroundPalette.Ground ground = null;
                if (imagery.mean(px, py, ImagerySource.MAX_ZOOM, 2, linear)) {
                    GroundPalette.Reading reading = GroundPalette.read(linear);
                    after = matched(code, steep, hotDry, roll, reading, before);
                    for (GroundPalette.Ground g : GroundPalette.Ground.values()) if (g.rgb == after) ground = g;
                }
                if (ground != null) counts[ground.ordinal()]++;
                image.setRGB(pixels + 8 + i, j, shade(before, light));
                image.setRGB(2 * pixels + 16 + i, j, shade(after, light));
            }
            ImageIO.write(image, "png", Path.of(args[4]).toFile());
            System.out.printf("centre %s, climate %s, hot and dry %s%n", centre, c, hotDry);
            StringBuilder used = new StringBuilder("ground from imagery:");
            for (GroundPalette.Ground g : GroundPalette.Ground.values())
                if (counts[g.ordinal()] * 100 >= pixels * pixels) used.append(' ').append(g.id()).append(' ').append(counts[g.ordinal()] * 100 / (pixels * pixels)).append('%');
            System.out.println(used);
            System.out.println("imagery " + imagery.stats());
        }
    }

    /** Roughly what land cover alone puts on top today. */
    static int landCover(int code, double steep, boolean hotDry, double roll) {
        if (steep > 1.25 && code != WorldCoverSource.WATER) return GroundPalette.Ground.STONE.rgb;
        return switch (code) {
            case WorldCoverSource.WATER -> WATER;
            case WorldCoverSource.TREE_COVER, WorldCoverSource.MANGROVES -> roll < 0.6 ? LEAVES : GRASS;
            case WorldCoverSource.SHRUBLAND -> roll < 0.2 ? GroundPalette.Ground.COARSE_DIRT.rgb : GRASS;
            case WorldCoverSource.BARE -> hotDry ? GroundPalette.Ground.SAND.rgb : GroundPalette.Ground.STONE.rgb;
            case WorldCoverSource.SNOW_AND_ICE -> GroundPalette.Ground.SNOW_BLOCK.rgb;
            default -> GRASS;
        };
    }

    /** The same with imagery: matched rock and sand, soil through thin dry cover, and canopy as thick as it looks. */
    static int matched(int code, double steep, boolean hotDry, double roll, GroundPalette.Reading reading, int before) {
        if (code == WorldCoverSource.WATER || reading.shadow()) return before;
        if (reading.snow()) return GroundPalette.Ground.SNOW_BLOCK.rgb;
        boolean bare = code == WorldCoverSource.BARE || code == WorldCoverSource.SNOW_AND_ICE || steep > 1.25;
        if (bare && !reading.green()) {
            GroundPalette.Ground[] set = steep > 0.7 ? (hotDry ? GroundPalette.DESERT_ROCK : GroundPalette.ROCK) : hotDry ? GroundPalette.DESERT_LOOSE : GroundPalette.LOOSE;
            return GroundPalette.match(reading, set, roll).rgb;
        }
        double v = reading.vegetation();
        return switch (code) {
            case WorldCoverSource.TREE_COVER, WorldCoverSource.MANGROVES -> roll < 0.25 + 0.6 * v ? LEAVES : open(reading) ? soil(reading, roll) : GRASS;
            case WorldCoverSource.GRASSLAND, WorldCoverSource.SHRUBLAND, WorldCoverSource.CROPLAND, WorldCoverSource.MOSS_AND_LICHEN ->
                    open(reading) && roll > v * 3 ? soil(reading, roll) : v < 0.45 ? DRY_GRASS : GRASS;
            default -> before;
        };
    }

    /** Bare soil shows: neither green nor dark like shade under trees. */
    private static boolean open(GroundPalette.Reading reading) { return reading.vegetation() < 0.25 && reading.lightness() > 42; }

    private static int soil(GroundPalette.Reading reading, double roll) { return GroundPalette.match(reading, GroundPalette.SOIL, roll).rgb; }

    private static long mix(long x, long z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return h;
    }

    private static int shade(int rgb, double light) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * light), g = (int) Math.min(255, ((rgb >> 8) & 255) * light), b = (int) Math.min(255, (rgb & 255) * light);
        return r << 16 | g << 8 | b;
    }
}
