package io.earthplatform.minecraft.cubic.play.client;

/** Tileable weather coverage for the world-fixed cloud volumes. */
public final class CloudField {
    private CloudField() {}
    public record Band(double height, double thickness, double period, double opacity, double turn, double wind) {}

    public static Band[] bands(double configuredHeight) {
        return new Band[] {
                new Band(63 + configuredHeight * .65, 450, 24576, .82, .35, .4),
                new Band(63 + configuredHeight * 1.5, 850, 40960, .64, 1.2, .65),
                new Band(63 + configuredHeight * 3.0, 360, 65536, .22, -.6, .9)};
    }

    public static double density(int kind, double u, double v) {
        double value = 0, weight = .55, total = 0;
        for (int octave = 0; octave < 5; octave++) {
            int sx = 8 << octave, sz = (kind == 2 ? 6 : 8) << octave;
            value += noise(u * sx, v * sz, sx, sz, kind * 53 + octave) * weight;
            total += weight; weight *= .5;
        }
        value /= total;
        return smooth((value - (kind == 1 ? .43 : .46)) / (kind == 2 ? .28 : .23));
    }

    private static double noise(double x, double z, int sx, int sz, int salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double tx = smooth(x - ix), tz = smooth(z - iz);
        double a = hash(ix, iz, sx, sz, salt), b = hash(ix + 1, iz, sx, sz, salt);
        double c = hash(ix, iz + 1, sx, sz, salt), d = hash(ix + 1, iz + 1, sx, sz, salt);
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }

    private static double hash(int x, int z, int sx, int sz, int salt) {
        long h = Math.floorMod(x, sx) * 0x9e3779b97f4a7c15L ^ Math.floorMod(z, sz) * 0xc2b2ae3d27d4eb4fL ^ salt * 0x165667b19e3779f9L;
        h = (h ^ h >>> 30) * 0xbf58476d1ce4e5b9L; h = (h ^ h >>> 27) * 0x94d049bb133111ebL;
        return ((h ^ h >>> 31) >>> 11) * 0x1.0p-53;
    }

    public static double smooth(double t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }
}
