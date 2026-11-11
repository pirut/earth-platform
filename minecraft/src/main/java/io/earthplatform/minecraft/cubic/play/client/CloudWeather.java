package io.earthplatform.minecraft.cubic.play.client;

import java.nio.ByteBuffer;
import java.util.Random;

/** World-anchored weather fronts. CPU sampling matches the repeating R8/linear GPU lattice. */
public final class CloudWeather {
    public static final double REGION_PERIOD = 1_280_000;
    public static final double TIME_PERIOD = 64 * 12_000.0;
    private static final byte[] NOISE = noise();
    private CloudWeather() {}

    private static byte[] noise() {
        byte[] data = new byte[64*64*64];
        Random random = new Random(0x57454154484552L);
        for (int i=0;i<data.length;i++) data[i]=(byte)random.nextInt(256);
        return data;
    }

    public static void writeNoise(ByteBuffer buffer) { buffer.put(NOISE); }
    public static double phase(double n) { return n-Math.floor(n); }

    /** Coverage envelope, not the density of an individual cloud. */
    public static double coverage(double x, double z, double ticks, double rain) {
        double[] p={phase(x/REGION_PERIOD)*64-.5,phase(z/REGION_PERIOD)*64-.5,phase(ticks/TIME_PERIOD)*64-.5};
        int[] base={(int)Math.floor(p[0]),(int)Math.floor(p[1]),(int)Math.floor(p[2])};
        double value=0;
        for(int dz=0;dz<2;dz++)for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
            double weight=(dx==0?1-(p[0]-base[0]):p[0]-base[0])
                    *(dy==0?1-(p[1]-base[1]):p[1]-base[1])*(dz==0?1-(p[2]-base[2]):p[2]-base[2]);
            int at=((base[0]+dx)&63)+64*(((base[1]+dy)&63)+64*((base[2]+dz)&63));
            value+=(NOISE[at]&255)/255.0*weight;
        }
        return CloudField.smooth((value+.2*Math.clamp(rain,0,1)-.38)/.30);
    }
}
