package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The noodles, put together from vanilla's noises so their parts blend apart, match vanilla's own noodle function for the same seed. */
class VanillaCavesTest {
    private static final long SEED = 987654321L;
    private static HolderLookup.Provider lookup;

    @BeforeAll static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        lookup = VanillaRegistries.createLookup();
    }

    /** A vanilla density function by name, its noises made as a vanilla world of {@link #SEED} makes them. */
    private static DensityFunction vanilla(String name) {
        PositionalRandomFactory random = new XoroshiroRandomSource(SEED).forkPositional();
        HolderGetter<NormalNoise.NoiseParameters> noises = lookup.lookupOrThrow(Registries.NOISE);
        DensityFunction function = lookup.lookupOrThrow(Registries.DENSITY_FUNCTION)
                .getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.withDefaultNamespace(name))).value();
        return function.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction f) { return f; }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
                return new DensityFunction.NoiseHolder(holder.noiseData(), Noises.instantiate(noises, random, holder.noiseData().unwrapKey().orElseThrow()));
            }
        });
    }

    private interface Part { double at(int x, int y, int z); }

    private static void matches(String name, Part ours) {
        DensityFunction theirs = vanilla(name);
        Random random = new Random(1);
        for (int i = 0; i < 3000; i++) {
            int x = random.nextInt(20000) - 10000, y = random.nextInt(384) - 64, z = random.nextInt(20000) - 10000;
            assertEquals(theirs.compute(new DensityFunction.SinglePointContext(x, y, z)), ours.at(x, y, z), 1e-9, name + " at " + x + " " + y + " " + z);
        }
    }

    @Test void noodlesMatchVanilla() {
        VanillaCaves caves = new VanillaCaves(SEED);
        matches("overworld/caves/noodle", caves::noodle);
    }
}
