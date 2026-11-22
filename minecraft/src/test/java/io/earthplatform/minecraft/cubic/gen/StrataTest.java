package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StrataTest {
    @BeforeAll static void registries() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    /** A plateau at 2,000 m cut by a canyon 16 km wide with its floor at 600 m, in a dry climate, or a wet one for x < -100,000. */
    private static Strata canyon() {
        return new Strata(1, new Strata.Globe() {
            @Override public double metres(double x, double z) { return Math.abs(z) > 8000 ? 2000 : 600; }
            @Override public double metresPerBlock(double z) { return 1; }
            @Override public boolean arid(double x, double z) { return x >= -100_000; }
        }, null);
    }

    @Test void datumFollowsThePlateauAndFallsAwayFromIt() {
        Strata strata = canyon();
        assertEquals(2000, strata.datum(0, 20_000), 1);
        double middle = strata.datum(0, 0);
        assertTrue(middle < 2000 - 100 && middle > 2000 - Strata.DIP * 9000, "datum mid-canyon " + middle);
    }

    @Test void onlyLayeredCountryHasLayers() {
        Strata strata = canyon();
        assertNotNull(strata.layers(0, 0, 62.5));
        assertNull(strata.layers(-200_000, 0, 62.5));
    }

    @Test void rockFollowsTheSequenceDownToTheBasement() {
        Strata.Layers rock = canyon().layers(0, 9_000, 62.5);
        // Kaibab Limestone at the rim: pale terracotta; Vishnu Schist (deepslate, tuff, granite) and the basement in the inner gorge.
        var rim = rock.at(62 + 1995);
        assertTrue(rim.is(Blocks.WHITE_TERRACOTTA) || rim.is(Blocks.LIGHT_GRAY_TERRACOTTA), rim.toString());
        var gorge = rock.at(62 + 2000 - 1400);
        assertTrue(gorge.is(Blocks.DEEPSLATE) || gorge.is(Blocks.GRANITE) || gorge.is(Blocks.TUFF), gorge.toString());
        assertTrue(rock.at(62 - 2000).is(Blocks.DEEPSLATE));
    }

    @Test void terracesKeepTheGroundInOrderAndTheFormationsInPlace() {
        Strata.Layers rock = canyon().layers(0, 9_000, 62.5);
        double last = Double.NEGATIVE_INFINITY;
        for (double m = 500; m <= 2100; m += 0.125) {
            double t = rock.terrace(m);
            assertTrue(t >= last - 1e-9, "terrace falls at " + m);
            assertTrue(Math.abs(t - m) < 160, "terrace strays at " + m);
            last = t;
        }
        // Plateau tops stay as they are.
        assertEquals(1995, rock.terrace(1995), 1e-9);
    }

    @Test void flatCanyonFloorsPreserveMappedRiparianVegetationWhileWallsStayRock() {
        var biomes = SurfacePacketFixtures.biomes();
        var badlands = biomes.getHolderOrThrow(Biomes.BADLANDS);
        var forest = biomes.getHolderOrThrow(Biomes.FOREST);
        var plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        Strata strata = new Strata(1, new Strata.Globe() {
            // Zion-like canyon: 2,000 m rim cut 1,000 m down to a flat 1,000 m valley floor.
            @Override public double metres(double x, double z) { return Math.abs(z) > 4000 ? 2000 : 1000; }
            @Override public double metresPerBlock(double z) { return 1; }
            @Override public boolean arid(double x, double z) { return true; }
        }, badlands);
        Strata.Layers floor = strata.layers(0, 0, 62.5);
        assertNotNull(floor);
        int floorY = 62 + 1000;

        Surface cottonwoods = new Surface(forest, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                50, 0, 0, 260, 280, false);
        Surface dressedTrees = floor.dress(cottonwoods, floorY, 0.2, 1);
        assertEquals(50, dressedTrees.trees(), "flat canyon floor should preserve mapped riparian trees");
        assertTrue(dressedTrees.top().is(Blocks.GRASS_BLOCK));
        assertTrue(dressedTrees.biome().is(Biomes.FOREST));

        Surface meadow = new Surface(plains, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                0, 0, 0, 350, 360, false);
        Surface dressedMeadow = floor.dress(meadow, floorY, 0.2, 1);
        assertEquals(350, dressedMeadow.grass(), "flat canyon floor should preserve valley greenery");
        assertTrue(dressedMeadow.top().is(Blocks.GRASS_BLOCK));

        // Steep canyon walls still show bare strata rock even if mapped with trees.
        Surface wall = floor.dress(cottonwoods, floorY, 1.5, 1);
        assertEquals(0, wall.trees());
        assertEquals(0, wall.grass());
        assertTrue(wall.biome().is(Biomes.BADLANDS));
        assertFalse(wall.top().is(Blocks.GRASS_BLOCK));

        // Barren canyon flats (no trees, no grass) stay dressed in strata rock.
        Surface barren = new Surface(plains, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                0, 0, 0, 0, 0, false);
        Surface dressedBarren = floor.dress(barren, floorY, 0.2, 1);
        assertTrue(dressedBarren.biome().is(Biomes.BADLANDS));
    }

    @Test void strataBoundariesFeatherInsteadOfCuttingAlongKnifeEdge() {
        Strata strata = canyon();
        // Walk across the wet/arid transition (around x = -110_000) and confirm non-null and null columns interleave.
        int switches = 0;
        Boolean prev = null;
        for (int x = -115_000; x <= -95_000; x++) {
            Strata.Layers at = strata.layers(x, 0, 62.5);
            boolean present = at != null;
            if (prev != null && prev != present) switches++;
            prev = present;
        }
        assertTrue(switches > 5, "outer strata boundary should feather across multiple transitions, got " + switches);

        // Two adjacent sequences: deep Grand Canyon cut (x < 0) meeting high Bryce tableland (x >= 0).
        Strata seam = new Strata(2, new Strata.Globe() {
            @Override public double metres(double x, double z) {
                if (x < 0) return Math.abs(z) > 6000 ? 2000 : 500;
                return Math.abs(z) > 6000 ? 2700 : 2100;
            }
            @Override public double metresPerBlock(double z) { return 1; }
            @Override public boolean arid(double x, double z) { return true; }
        }, null);
        int seqSwitches = 0;
        String lastSeq = null;
        for (int x = -15_000; x <= 20_000; x++) {
            Strata.Layers at = seam.layers(x, 0, 62.5);
            if (at == null) continue;
            String name = at.sequenceName();
            if (lastSeq != null && !lastSeq.equals(name)) seqSwitches++;
            lastSeq = name;
        }
        assertTrue(seqSwitches > 5, "sequence boundary should feather instead of snapping once, got " + seqSwitches);
    }
}
