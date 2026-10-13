package io.earthplatform.geography.globe;

/**
 * Picks vanilla ground blocks whose colour matches what satellite imagery shows of a place, so pale
 * granite domes come out pale, red dunes red and dark rock plains dark, and reads how green the
 * ground is, so sparse dry grass shows its soil and thick woods grow thick.
 *
 * <p>Colours are compared in CIELAB. Block colours are the mean of each block's top texture in
 * Minecraft 1.21.1, averaged in linear light. Imagery is first corrected for the warm cast the
 * Sentinel-2 cloudless mosaic has, and lightness counts for less than hue and chroma, as a
 * surface seen from orbit through air and in sunlight is not lit like a block in game. Pixels too
 * dark to read (cliff shadows, dense wet forest, open water) say nothing.
 */
public final class GroundPalette {
    private GroundPalette() {}

    /** Vanilla ground blocks by their block id, with their texture's mean colour in CIELAB. */
    public enum Ground {
        STONE(126, 126, 126), ANDESITE(137, 137, 138), DIORITE(193, 193, 193), GRANITE(152, 106, 89), TUFF(111, 111, 105),
        CALCITE(225, 226, 222), DEEPSLATE(83, 83, 85), SMOOTH_BASALT(75, 75, 79),
        SANDSTONE(224, 214, 171), RED_SANDSTONE(182, 98, 32), TERRACOTTA(152, 94, 68), WHITE_TERRACOTTA(210, 178, 161),
        LIGHT_GRAY_TERRACOTTA(135, 107, 98), ORANGE_TERRACOTTA(162, 84, 38), YELLOW_TERRACOTTA(186, 133, 35),
        RED_TERRACOTTA(143, 61, 47), BROWN_TERRACOTTA(77, 51, 36),
        SAND(219, 208, 164), RED_SAND(191, 103, 33), GRAVEL(133, 129, 128), COARSE_DIRT(125, 90, 63), DIRT(138, 99, 70),
        PACKED_MUD(143, 107, 80), SNOW_BLOCK(249, 254, 254);

        public final int rgb;
        final double l, a, b;

        Ground(int r, int g, int b) {
            this.rgb = r << 16 | g << 8 | b;
            double[] lab = Lab.of(ImagerySource.LINEAR[r], ImagerySource.LINEAR[g], ImagerySource.LINEAR[b]);
            this.l = lab[0]; this.a = lab[1]; this.b = lab[2];
        }

        /** The block id, in the minecraft namespace. */
        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** Bare rock anywhere. */
    public static final Ground[] ROCK = {Ground.STONE, Ground.ANDESITE, Ground.DIORITE, Ground.GRANITE, Ground.TUFF, Ground.CALCITE,
            Ground.DEEPSLATE, Ground.SMOOTH_BASALT};
    /** Bare rock in dry lands, hot or cold, where layered sandstone and badlands colours are found. */
    public static final Ground[] DESERT_ROCK = {Ground.STONE, Ground.ANDESITE, Ground.DIORITE, Ground.GRANITE, Ground.TUFF,
            Ground.DEEPSLATE, Ground.SMOOTH_BASALT, Ground.SANDSTONE, Ground.RED_SANDSTONE, Ground.TERRACOTTA,
            Ground.WHITE_TERRACOTTA, Ground.LIGHT_GRAY_TERRACOTTA, Ground.ORANGE_TERRACOTTA, Ground.YELLOW_TERRACOTTA,
            Ground.RED_TERRACOTTA, Ground.BROWN_TERRACOTTA};
    /** Bare gentle ground: rock pavement, gravel, sand and soils. */
    public static final Ground[] LOOSE = {Ground.STONE, Ground.ANDESITE, Ground.DIORITE, Ground.TUFF, Ground.GRAVEL, Ground.SAND,
            Ground.COARSE_DIRT, Ground.DIRT};
    /** Bare gentle ground in dry lands: dunes, sand sheets and stony plains. */
    public static final Ground[] DESERT_LOOSE = {Ground.SAND, Ground.RED_SAND, Ground.GRAVEL, Ground.COARSE_DIRT};
    /** Soil showing through thin, dry plant cover. */
    public static final Ground[] SOIL = {Ground.COARSE_DIRT, Ground.DIRT, Ground.PACKED_MUD, Ground.SAND, Ground.RED_SAND, Ground.GRAVEL};

    /** Weight of lightness against hue and chroma. */
    static final double LIGHTNESS_WEIGHT = 0.45;
    /** The mosaic's warm cast, taken off a and b before matching. */
    static final double CAST_A = 2, CAST_B = 10;
    /** How much further than the nearest the second nearest block may be and still be mixed in. */
    static final double MIX_RATIO = 1.3;
    /** Lightness under which a pixel is shadow or water and says nothing. */
    static final double SHADOW = 25;

    /**
     * What imagery shows of a patch: its corrected CIELAB colour, and its greenness, the excess
     * green of its sRGB chromaticity (about 0 for bare soil and dry grass, 0.1 and up for living
     * plants).
     */
    public record Reading(double lightness, double a, double b, double greenness) {
        /** Too dark to tell anything from. */
        public boolean shadow() { return lightness < SHADOW; }
        /** Snow or ice: bright with almost no colour. */
        public boolean snow() { return lightness > 82 && Math.hypot(a + CAST_A, b + CAST_B) < 10; }
        /** Living plants: green leads red, with a clear excess of green. */
        public boolean green() { return !shadow() && greenness > 0.04; }
        /** How thick the plant cover looks, 0 for bare or dry ground to 1 for lush green. */
        public double vegetation() { return shadow() ? 0.5 : Math.clamp((greenness - 0.01) / 0.14, 0, 1); }
    }

    /** Reads a patch's mean colour, given in linear RGB 0..1. */
    public static Reading read(double[] linear) {
        double[] lab = lab(linear[0], linear[1], linear[2]);
        double r = srgb(linear[0]), g = srgb(linear[1]), b = srgb(linear[2]);
        double sum = r + g + b;
        double greenness = sum <= 0 || g <= r ? Math.min(0, (2 * g - r - b) / Math.max(sum, 1e-6)) : (2 * g - r - b) / sum;
        return new Reading(lab[0], lab[1] - CAST_A, lab[2] - CAST_B, greenness);
    }

    /**
     * The candidate closest in colour to a reading. With {@code roll} in [0, 1) the nearest two
     * are mixed when the second is nearly as close, half and half when both are as near, so ground
     * between two block colours shows both, as real ground grades between them; a roll of 0
     * always gives the nearest.
     */
    public static Ground match(Reading reading, Ground[] candidates, double roll) {
        Ground best = null, second = null;
        double bestDistance = Double.MAX_VALUE, secondDistance = Double.MAX_VALUE;
        for (Ground ground : candidates) {
            double d = distance(reading, ground);
            if (d < bestDistance) { second = best; secondDistance = bestDistance; best = ground; bestDistance = d; }
            else if (d < secondDistance) { second = ground; secondDistance = d; }
        }
        if (second == null || bestDistance <= 0) return best;
        // Share of the second: a half when both are as near, none once it is a third as far again.
        double share = Math.clamp((MIX_RATIO - secondDistance / bestDistance) / (MIX_RATIO - 1) * 0.5, 0, 0.5);
        return roll < 1 - share ? best : second;
    }

    static double distance(Reading reading, Ground ground) {
        double dl = (reading.lightness() - ground.l) * LIGHTNESS_WEIGHT, da = reading.a() - ground.a, db = reading.b() - ground.b;
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    static double srgb(double linear) {
        return linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
    }

    /** CIELAB (D65) of a linear RGB colour. */
    static double[] lab(double r, double g, double b) { return Lab.of(r, g, b); }

    /** Kept apart so the block colours can be made before this class's candidate sets, which hold them. */
    private static final class Lab {
        static double[] of(double r, double g, double b) {
            double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047, y = 0.2126 * r + 0.7152 * g + 0.0722 * b,
                    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
            double fx = f(x), fy = f(y), fz = f(z);
            return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
        }

        private static double f(double t) { return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116; }
    }
}
