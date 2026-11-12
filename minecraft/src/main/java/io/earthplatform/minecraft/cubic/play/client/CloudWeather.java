package io.earthplatform.minecraft.cubic.play.client;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Weather fronts for the layered clouds, fixed to the world: clear, scattered and cloudy spells
 * that come and go over game time. The shader reads the same repeating 64-cubed lattice from a
 * linearly filtered R8 texture; {@link #coverage} samples it the same way on the CPU.
 */
public final class CloudWeather {
    /** Blocks across the lattice: cells about 20 km wide. */
    public static final double REGION_PERIOD = 1_280_000;
    /** Ticks until the weather repeats: 12,000 ticks (about ten minutes) a lattice step. */
    public static final double TIME_PERIOD = 64 * 12_000.0;
    private static final byte[] NOISE = noise();

    private CloudWeather() {}

    private static byte[] noise() {
        byte[] data = new byte[64 * 64 * 64];
        Random random = new Random(0x57454154484552L);
        for (int i = 0; i < data.length; i++) data[i] = (byte) random.nextInt(256);
        return data;
    }

    public static void writeNoise(ByteBuffer buffer) { buffer.put(NOISE); }

    /** How cloudy the weather lets it be at a place and time, 0 to 1: the envelope, not one cloud's density. Rain adds cover. */
    public static double coverage(double x, double z, double ticks, double rain) {
        double px = frac(x / REGION_PERIOD) * 64 - .5, py = frac(z / REGION_PERIOD) * 64 - .5, pz = frac(ticks / TIME_PERIOD) * 64 - .5;
        int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
        double fx = px - bx, fy = py - by, fz = pz - bz, value = 0;
        for (int dz = 0; dz < 2; dz++)
            for (int dy = 0; dy < 2; dy++)
                for (int dx = 0; dx < 2; dx++) {
                    double weight = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
                    int at = ((bx + dx) & 63) + 64 * (((by + dy) & 63) + 64 * ((bz + dz) & 63));
                    value += (NOISE[at] & 255) / 255.0 * weight;
                }
        return CloudField.smooth((value + .2 * Math.clamp(rain, 0, 1) - .38) / .30);
    }

    static double frac(double value) { return value - Math.floor(value); }
}
