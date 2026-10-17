package io.earthplatform.minecraft.cubic.gen;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Procedural engine test world, deliberately placed far above vanilla's build limit: hills around
 * Y 2400 rising to snowy ridges near Y 3300, with lakes, caves, ores and trees. Cubic worlds made
 * before the Earth generator keep it, so their unedited cubes come back the same.
 */
public final class HighlandsGenerator extends SurfaceGenerator {
    public static final String ID = "highlands-v1";
    public static final int SEA = 2380, BASE = 2400, TREELINE = 2950, SNOWLINE = 3020;

    private final SimplexNoise continent, ridge, mask, hills, detail, moisture, snow;
    private final Holder<Biome> plains, forest, birch, taiga, meadow, snowy, peaks, beach;

    /** The height noises, drawn first from the random the cave noise then continues. */
    private record Noises(RandomSource random, SimplexNoise[] noise) {
        static Noises of(long seed) {
            RandomSource random = new LegacyRandomSource(seed ^ 0x4541525448L);
            SimplexNoise[] noise = new SimplexNoise[7];
            for (int i = 0; i < noise.length; i++) noise[i] = new SimplexNoise(random);
            return new Noises(random, noise);
        }
    }

    public HighlandsGenerator(long seed, Registry<Biome> biomes) { this(seed, biomes, Noises.of(seed)); }

    private HighlandsGenerator(long seed, Registry<Biome> biomes, Noises noises) {
        super(seed, biomes, noises.random);
        SimplexNoise[] n = noises.noise;
        continent = n[0]; ridge = n[1]; mask = n[2]; hills = n[3]; detail = n[4]; moisture = n[5]; snow = n[6];
        plains = holder(biomes, Biomes.PLAINS); forest = holder(biomes, Biomes.FOREST); birch = holder(biomes, Biomes.BIRCH_FOREST);
        taiga = holder(biomes, Biomes.TAIGA); meadow = holder(biomes, Biomes.MEADOW); snowy = holder(biomes, Biomes.SNOWY_SLOPES);
        peaks = holder(biomes, Biomes.JAGGED_PEAKS); beach = holder(biomes, Biomes.BEACH);
    }

    @Override public String id() { return ID; }
    @Override public int seaLevel() { return SEA; }
    @Override public boolean barren(int x, int z, int y) { return y >= TREELINE; }
    @Override protected int shoreLevel() { return SEA; }
    @Override protected boolean emeralds(int maxGround) { return maxGround > SNOWLINE - 200; }

    @Override protected void ground(int x, int z, int[] out) {
        double c = fbm(continent, x / 3200.0, z / 3200.0, 3);
        double r = 1 - Math.abs(fbm(ridge, x / 760.0, z / 760.0, 4));
        double m = Mth.smoothstep(Mth.clamp((fbm(mask, x / 2600.0, z / 2600.0, 2) + 0.15) / 0.7, 0, 1));
        double h = BASE + c * 110 + m * r * r * r * 950 + fbm(hills, x / 190.0, z / 190.0, 3) * 34 + fbm(detail, x / 38.0, z / 38.0, 2) * 4;
        out[0] = Mth.floor(h);
        out[1] = out[0] < SEA ? SEA : DRY;
    }
    private int snowline(int x, int z) { return SNOWLINE + (int) (snow.getValue(x / 90.0, z / 90.0) * 50); }
    private double wetness(int x, int z) { return fbm(moisture, x / 900.0, z / 900.0, 2); }

    @Override protected Surface surface(int x, int z, int y, int water, int slope) {
        double wet = wetness(x, z);
        Holder<Biome> b; BlockState top;
        if (y <= SEA + 1) { b = y < SEA - 2 ? plains : beach; top = y < SEA - 3 ? GRAVEL : SAND; }
        else if (y >= snowline(x, z)) { b = slope > 9 ? peaks : snowy; top = slope > 12 ? STONE : SNOW_BLOCK; }
        else if (y > TREELINE) { b = wet > 0.1 ? taiga : meadow; top = slope > 7 ? STONE : GRASS; }
        else { b = wet > 0.25 ? birch : wet > -0.25 ? forest : plains; top = slope > 8 ? STONE : GRASS; }
        BlockState under = top == SAND || top == GRAVEL ? top : top == STONE ? STONE : DIRT;
        boolean high = y > TREELINE;
        int trees = y <= SEA + 1 || y > TREELINE + 60 || slope > 6 ? 0 : high ? 55 : wet > 0.25 ? 85 : wet > -0.25 ? 80 : 12;
        int spruce = high || (wet > 0.1 && y > TREELINE - 80) ? 10 : 0, birches = wet > 0.25 ? 7 : 2;
        int grass = b == plains || b == meadow ? 220 : 90, flowers = b == meadow ? 300 : 232;
        return new Surface(b, top, under, trees, spruce, birches, grass, flowers, b == taiga);
    }
}
