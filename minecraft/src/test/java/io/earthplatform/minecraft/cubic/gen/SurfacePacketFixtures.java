package io.earthplatform.minecraft.cubic.gen;

import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;

/** Synthetic deterministic country: negative heights, shores, ice, mixed cover, forest canopy. */
public final class SurfacePacketFixtures {
    private static Registry<Biome> registry;
    public static synchronized Registry<Biome> biomes() {
        if (registry != null) return registry;
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        MappedRegistry<Biome> r = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> r.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        return registry = r.freeze();
    }
    public static final class Country extends SurfaceGenerator {
        private final int mode;
        private final Strata strata = new Strata(1, new Strata.Globe() {
            public double metres(double x, double z) { return Math.abs(z) > 8000 ? 2000 : 600; }
            public double metresPerBlock(double z) { return 1; }
            public boolean arid(double x, double z) { return true; }
        }, null);
        public Country(int mode) { super(9876, biomes(), RandomSource.create(9876)); this.mode = mode; }
        @Override protected void ground(int x, int z, int[] out) {
            out[0] = -180 + Math.floorMod(x * 17 + z * 29, 370);
            out[1] = Math.floorMod(x / 8 + z / 8, 4) == 0 ? 24 : DRY;
        }
        @Override protected Surface surface(int x, int z, int h, int w, int slope) {
            boolean cold = Math.floorMod(x + z, 3) == 0;
            var biome = holder(biomes(), cold ? Biomes.SNOWY_PLAINS : Biomes.PLAINS);
            return new Surface(biome, cold ? SNOW_BLOCK : GRASS, mode == 1 ? Blocks.RED_SANDSTONE.defaultBlockState() : DIRT,
                    mode == 4 ? 100 : 0, 4, 3, 0, 0, false);
        }
        @Override public String id() { return "surface_packet_oracle"; }
        @Override public int seaLevel() { return 24; }
        @Override protected int shoreLevel() { return 24; }
        @Override protected boolean emeralds(int maxGround) { return false; }
        @Override protected boolean giants(int x, int z) { return mode == 2; }
        @Override protected Strata.Layers layers(int x, int z) { return mode == 3 ? strata.layers(0, 9000, 62.5) : null; }
        public FarColumn column(int level, int x, int z, boolean packet) {
            FarColumn out = new FarColumn(); farColumn(level, x, z, out, packet); return out;
        }
    }
    private SurfacePacketFixtures() {}
}
