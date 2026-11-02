package io.earthplatform.minecraft.cubic.gen;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Vanilla's caves (1.18 and later), run from Minecraft's own code: its registered cave density
 * functions (entrances and 3D spaghetti, 2D spaghetti, their roughness, pillars) and its cave
 * noises (cheese, cave layers, noodles), wired to noises seeded exactly as a vanilla world of the
 * same seed seeds them, and joined as vanilla's {@code NoiseRouterData.underground} joins them.
 *
 * <p>Vanilla's caves sit under a world whose plains are near Y 64. Here the ground stands anywhere,
 * so vanilla's code is asked about each block at its vanilla-equivalent height over its column's
 * floor, as the rock layers are: caves keep vanilla's layout by depth (2D spaghetti near vanilla's
 * Y 0, noodles from -60 to 320, entrances opening more readily above -10). Vanilla's surface rule,
 * which keeps the top ten or so blocks to entrances only and thins cheese caverns over the next
 * few, reads the depth under the column's ground.
 *
 * <p>Like vanilla, the noise is worked out on cells 4 blocks wide and 8 high and blended between
 * them; the noodle's parts are blended apart and joined per block, as vanilla does.
 */
final class VanillaCaves {
    /** Values per cell corner: the cave density, then the noodle's toggle, thickness and two ridges. */
    static final int VALUES = 5;
    /** Corners per cube: 5 across, 3 up (every 8 blocks), 5 deep. */
    static final int NX = 5, NY = 3, NZ = 5, NODES = NX * NY * NZ;

    /** Vanilla's terrain density gains about this much a block going down from the surface, in plain ground. */
    private static final double DENSITY_PER_BLOCK = 0.156;
    /** Under this terrain density vanilla carves entrances only. */
    private static final double SURFACE_DENSITY = 1.5625;

    /** Vanilla's built-in worldgen registries, read once. */
    private static volatile HolderLookup.Provider lookup;

    private final DensityFunction entrances, spaghetti2d, roughness, pillars;
    private final NormalNoise layer, cheese, noodle, noodleThickness, noodleRidgeA, noodleRidgeB;

    VanillaCaves(long seed) {
        HolderLookup.Provider registries = registries();
        HolderGetter<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
        PositionalRandomFactory random = new XoroshiroRandomSource(seed).forkPositional();
        // As a vanilla world's RandomState wires its density functions to its noises.
        DensityFunction.Visitor wire = new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction function) { return function; }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
                return new DensityFunction.NoiseHolder(holder.noiseData(), Noises.instantiate(noises, random, holder.noiseData().unwrapKey().orElseThrow()));
            }
        };
        HolderGetter<DensityFunction> functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);
        entrances = function(functions, "overworld/caves/entrances").mapAll(wire);
        spaghetti2d = function(functions, "overworld/caves/spaghetti_2d").mapAll(wire);
        roughness = function(functions, "overworld/caves/spaghetti_roughness_function").mapAll(wire);
        pillars = function(functions, "overworld/caves/pillars").mapAll(wire);
        layer = Noises.instantiate(noises, random, Noises.CAVE_LAYER);
        cheese = Noises.instantiate(noises, random, Noises.CAVE_CHEESE);
        noodle = Noises.instantiate(noises, random, Noises.NOODLE);
        noodleThickness = Noises.instantiate(noises, random, Noises.NOODLE_THICKNESS);
        noodleRidgeA = Noises.instantiate(noises, random, Noises.NOODLE_RIDGE_A);
        noodleRidgeB = Noises.instantiate(noises, random, Noises.NOODLE_RIDGE_B);
    }

    private static HolderLookup.Provider registries() {
        HolderLookup.Provider provider = lookup;
        if (provider == null) {
            synchronized (VanillaCaves.class) {
                if (lookup == null) lookup = VanillaRegistries.createLookup();
                provider = lookup;
            }
        }
        return provider;
    }

    private static DensityFunction function(HolderGetter<DensityFunction> functions, String name) {
        return functions.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.withDefaultNamespace(name))).value();
    }

    /**
     * Fills one cell corner's values at {@code out[at..at+5)}: block column {@code (x, z)} at vanilla
     * height {@code vy}, {@code depth} blocks under its column's ground.
     */
    void corner(double[] out, int at, int x, int vy, int z, int depth) {
        if (depth < 0) {
            // Over the ground: vanilla's terrain density alone, already open air; no noodle.
            out[at] = DENSITY_PER_BLOCK * depth; out[at + 1] = -1; out[at + 2] = 0; out[at + 3] = 0; out[at + 4] = 0;
            return;
        }
        out[at] = density(x, vy, z, depth);
        if (vy >= -60 && vy < 321) {
            out[at + 1] = noodle.getValue(x, vy, z);
            out[at + 2] = -0.075 + -0.025 * noodleThickness.getValue(x, vy, z);
            out[at + 3] = noodleRidgeA.getValue(x * 2.6666666666666665, vy * 2.6666666666666665, z * 2.6666666666666665);
            out[at + 4] = noodleRidgeB.getValue(x * 2.6666666666666665, vy * 2.6666666666666665, z * 2.6666666666666665);
        } else {
            out[at + 1] = -1; out[at + 2] = 0; out[at + 3] = 0; out[at + 4] = 0;
        }
    }

    /** Vanilla's cave density under its surface rule: negative is cave. Noodles come apart, in {@link #carved}. */
    private double density(int x, int vy, int z, int depth) {
        DensityFunction.SinglePointContext at = new DensityFunction.SinglePointContext(x, vy, z);
        double terrain = DENSITY_PER_BLOCK * depth;
        double entrance = entrances.compute(at);
        if (terrain < SURFACE_DENSITY) return Math.min(terrain, 5 * entrance);
        // Caverns: cheese with its layered floors, thinned just under the surface.
        double layered = layer.getValue(x, vy * 8.0, z);
        double caverns = 4 * layered * layered + Mth.clamp(0.27 + cheese.getValue(x, vy * 0.6666666666666666, z), -1, 1)
                + Mth.clamp(1.5 - 0.64 * terrain, 0, 0.5);
        double carved = Math.min(Math.min(caverns, entrance), spaghetti2d.compute(at) + roughness.compute(at));
        double pillar = pillars.compute(at);
        return pillar < 0.03 ? carved : Math.max(carved, pillar);
    }

    /** Vanilla's noodle at one block, unblended: negative is cave. */
    double noodle(int x, int vy, int z) {
        double[] f = new double[VALUES];
        corner(f, 0, x, vy, z, 0);
        return f[1] < 0 ? 64 : f[2] + 1.5 * Math.max(Math.abs(f[3]), Math.abs(f[4]));
    }

    /** Whether a block is cave, from its cell's blended values: the density, or else a noodle. */
    static boolean carved(double[] f, int lx, int ly, int lz) {
        int ix = lx >> 2, iy = ly >> 3, iz = lz >> 2;
        double tx = (lx & 3) / 4.0, ty = (ly & 7) / 8.0, tz = (lz & 3) / 4.0;
        int c000 = ((iy * NZ + iz) * NX + ix) * VALUES, c100 = c000 + VALUES;
        int c010 = c000 + NX * NZ * VALUES, c110 = c010 + VALUES;
        int c001 = c000 + NX * VALUES, c101 = c001 + VALUES;
        int c011 = c010 + NX * VALUES, c111 = c011 + VALUES;
        if (lerp(f, 0, tx, ty, tz, c000, c100, c010, c110, c001, c101, c011, c111) < 0) return true;
        if (lerp(f, 1, tx, ty, tz, c000, c100, c010, c110, c001, c101, c011, c111) < 0) return false;
        double a = lerp(f, 3, tx, ty, tz, c000, c100, c010, c110, c001, c101, c011, c111);
        double b = lerp(f, 4, tx, ty, tz, c000, c100, c010, c110, c001, c101, c011, c111);
        return lerp(f, 2, tx, ty, tz, c000, c100, c010, c110, c001, c101, c011, c111) + 1.5 * Math.max(Math.abs(a), Math.abs(b)) < 0;
    }

    private static double lerp(double[] f, int v, double tx, double ty, double tz, int c000, int c100, int c010, int c110,
                               int c001, int c101, int c011, int c111) {
        return Mth.lerp3(tx, ty, tz, f[c000 + v], f[c100 + v], f[c010 + v], f[c110 + v], f[c001 + v], f[c101 + v], f[c011 + v], f[c111 + v]);
    }
}
