package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
