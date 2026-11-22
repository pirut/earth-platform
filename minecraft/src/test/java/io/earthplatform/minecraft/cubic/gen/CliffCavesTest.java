package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.function.IntBinaryOperator;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Sheer cliffs and narrow steep ridges keep horizontal rock cover so giant vanilla caverns do not
 * blow holes out of cliff faces or undercut ridge tops into floating islands.
 */
class CliffCavesTest {
    private static Registry<Biome> biomes;

    @BeforeAll static void registries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes = registry.freeze();
    }

    private static final class SyntheticGenerator extends SurfaceGenerator {
        private final IntBinaryOperator height;
        private final Surface stoneSurface;

        SyntheticGenerator(long seed, IntBinaryOperator height) {
            super(seed, biomes, new LegacyRandomSource(seed));
            this.height = height;
            Holder<Biome> plains = holder(biomes, Biomes.PLAINS);
            this.stoneSurface = new Surface(plains, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), 0, 0, 0, 0, 0, false);
            underground().vanillaCaves(true);
        }

        @Override public String id() { return "synthetic-cliff-test"; }
        @Override public int seaLevel() { return 0; }
        @Override public boolean barren(int x, int z, int y) { return true; }
        @Override protected int shoreLevel() { return 0; }
        @Override protected boolean emeralds(int maxGround) { return false; }
        @Override protected void ground(int x, int z, int[] out) {
            out[0] = height.applyAsInt(x, z);
            out[1] = DRY;
        }
        @Override protected Surface surface(int x, int z, int y, int water, int slope) { return stoneSurface; }
    }

    @Test
    void sheerCliffFaceHasNoCavernHolesWhileDeepInteriorStillHasCaves() {
        // Sheer 100-block vertical cliff along x = 0 (valley y = 100 for x < 0, mountain y = 200 for x >= 0).
        SyntheticGenerator gen = new SyntheticGenerator(42L, (x, z) -> x >= 0 ? 200 : 100);
        int faceHoles = 0, interiorCaves = 0;
        // Scan a 128-block-long stretch of the sheer cliff face (x = 1..4 inside the cliff, y = 112..191).
        for (int cz = 0; cz < 8; cz++) {
            for (int cy = 7; cy <= 11; cy++) {
                LevelChunkSection face = gen.generate(0, cy, cz, new ArrayList<>(), new ArrayList<>());
                for (int lz = 0; lz < 16; lz++) for (int ly = 0; ly < 16; ly++) {
                    for (int lx = 1; lx <= 4; lx++) {
                        BlockState s = face.getBlockState(lx, ly, lz);
                        if (s.isAir() || s.is(Blocks.WATER) || s.is(Blocks.LAVA)) faceHoles++;
                    }
                }
                // Deep inside the mountain (x = 32..63), vanilla caves should still carve normally.
                for (int cx = 2; cx < 4; cx++) {
                    LevelChunkSection deep = gen.generate(cx, cy, cz, new ArrayList<>(), new ArrayList<>());
                    for (int lz = 0; lz < 16; lz++) for (int ly = 0; ly < 16; ly++) for (int lx = 0; lx < 16; lx++) {
                        BlockState s = deep.getBlockState(lx, ly, lz);
                        if (s.isAir() || s.is(Blocks.WATER) || s.is(Blocks.LAVA)) interiorCaves++;
                    }
                }
            }
        }
        assertEquals(0, faceHoles, "sheer cliff shell (x = 1..4) must stay solid behind the cliff face");
        assertTrue(interiorCaves > 500, "deep mountain interior should still contain vanilla caves, found " + interiorCaves);
    }

    @Test
    void narrowSteepRidgeIsNeverSeveredIntoFloatingIslands() {
        // Narrow 8-block-wide steep ridge (x = 4..11 at y = 200, surrounding terrain y = 140).
        SyntheticGenerator gen = new SyntheticGenerator(99L, (x, z) -> (x >= 4 && x <= 11) ? 200 : 140);
        int ridgeCavities = 0;
        for (int cz = 0; cz < 8; cz++) {
            for (int cy = 9; cy <= 12; cy++) {
                LevelChunkSection sec = gen.generate(0, cy, cz, new ArrayList<>(), new ArrayList<>());
                for (int lz = 0; lz < 16; lz++) for (int ly = 0; ly < 16; ly++) {
                    int y = (cy << 4) + ly;
                    if (y > 200 || y < 144) continue;
                    for (int lx = 5; lx <= 10; lx++) {
                        BlockState s = sec.getBlockState(lx, ly, lz);
                        if (s.isAir() || s.is(Blocks.WATER) || s.is(Blocks.LAVA)) ridgeCavities++;
                    }
                }
            }
        }
        assertEquals(0, ridgeCavities, "narrow steep ridge core must remain solid and never undercut into floating islands");
    }

    @Test
    void caveCeilingMatchesVerticalRoofOnGentleSlopes() {
        // Gentle slope < 45 degrees (slope 0.5 in x): caveCeiling must equal ground - roof exactly.
        SyntheticGenerator gen = new SyntheticGenerator(7L, (x, z) -> 100 + Math.floorDiv(x, 2));
        Underground under = gen.underground();
        for (int x = -10; x <= 10; x++) {
            int g = gen.surfaceY(x, 0);
            assertEquals(g - 6, under.caveCeiling(x, 0, g, 6), "x=" + x);
            assertEquals(g - 15, under.caveCeiling(x, 0, g, 15), "x=" + x);
        }
    }
}
