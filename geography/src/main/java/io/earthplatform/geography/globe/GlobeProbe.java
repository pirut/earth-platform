package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.ImageIO;

/**
 * Renders a shaded top-down preview of the block-space surface around a real place, using live Terrarium tiles.
 *
 * <pre>java -cp ... io.earthplatform.geography.globe.GlobeProbe LAT LON SIZE_BLOCKS PIXELS OUT.png [ZOOM] [CACHE_DIR]</pre>
 *
 * Heights are true metres, as the cubic world places them. Water below sea level is drawn blue by depth; land is
 * coloured by height relative to the highest point in the image and lit from the north-west. It is a diagnostic image,
 * not a Minecraft screenshot.
 */
public final class GlobeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: GlobeProbe LAT LON SIZE_BLOCKS PIXELS OUT.png [ZOOM] [CACHE_DIR]");
            System.exit(2);
        }
        GeoPoint centre = new GeoPoint(Double.parseDouble(args[0]), Double.parseDouble(args[1]));
        double size = Double.parseDouble(args[2]);
        int pixels = Integer.parseInt(args[3]);
        int zoom = args.length > 5 ? Integer.parseInt(args[5]) : 14;
        Path cache = args.length > 6 ? Path.of(args[6]) : null;
        var grid = new MercatorGrid(1.0);
        try (var tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache, true, 512, 8, Duration.ofSeconds(30))) {
            var elevation = new GlobalElevation(grid, tiles, zoom);
            double cx = grid.blockX(centre), cz = grid.blockZ(centre), step = size / pixels;
            double[] heights = new double[pixels * pixels];
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < pixels; j++) for (int i = 0; i < pixels; i++) {
                double x = cx - size / 2 + (i + 0.5) * step, z = cz - size / 2 + (j + 0.5) * step;
                double y = elevation.metres(x, z);
                heights[j * pixels + i] = y;
                min = Math.min(min, y); max = Math.max(max, y);
            }
            double sea = 0;
            var image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_RGB);
            for (int j = 0; j < pixels; j++) for (int i = 0; i < pixels; i++) {
                double y = heights[j * pixels + i];
                double west = heights[j * pixels + Math.max(0, i - 1)], north = heights[Math.max(0, j - 1) * pixels + i];
                double light = Math.max(0.35, Math.min(1.3, 0.85 + ((y - west) + (y - north)) / step * 0.35));
                int rgb = y < sea ? water(sea - y) : land((y - sea) / Math.max(1, max - sea) * 1300);
                image.setRGB(i, j, shade(rgb, y < sea ? 1 : light));
            }
            ImageIO.write(image, "png", Path.of(args[4]).toFile());
            System.out.printf("centre %s block X=%.0f Z=%.0f groundMetresPerBlock=%.3f%n", centre, cx, cz, grid.groundMetresPerBlock(cz));
            System.out.printf("elevation %.1f..%.1f m%n", min, max);
            System.out.println("tiles " + tiles.stats());
        }
    }

    private static int water(double depth) {
        double t = Math.min(1, depth / 70);
        return rgb(40 - 25 * t, 110 - 70 * t, 200 - 90 * t);
    }

    private static int land(double above) {
        double[][] stops = {{0, 200, 190, 140}, {4, 90, 150, 70}, {150, 60, 120, 50}, {450, 140, 130, 90}, {900, 120, 110, 105}, {1300, 235, 235, 240}};
        for (int s = 1; s < stops.length; s++) if (above <= stops[s][0]) {
            double t = (above - stops[s - 1][0]) / (stops[s][0] - stops[s - 1][0]);
            return rgb(lerp(stops[s - 1][1], stops[s][1], t), lerp(stops[s - 1][2], stops[s][2], t), lerp(stops[s - 1][3], stops[s][3], t));
        }
        return rgb(235, 235, 240);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static int shade(int rgb, double light) {
        return rgb(((rgb >> 16) & 255) * light, ((rgb >> 8) & 255) * light, (rgb & 255) * light);
    }

    private static int rgb(double r, double g, double b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }
}
