package io.earthplatform.minecraft.cubic.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;

class TowersTest {
    private static Towers towers(DoubleBinaryOperator ground) {
        return new Towers(1, new Towers.Globe() {
            @Override public double metres(double x, double z) { return ground.applyAsDouble(x, z); }
            @Override public double metresPerBlock(double z) { return 1; }
        });
    }

    /** A rounded hill {@code rise} m over ground at 5 m, centred at (32, 32), half its rise {@code half} m out from its top. */
    private static double hill(double x, double z, double rise, double half) {
        double r = Math.hypot(x - 32, z - 32), reach = half / Math.pow(0.5, 2 / 3.0);
        return 5 + rise * Math.max(0, 1 - Math.pow(r / reach, 1.5));
    }

    @Test void aNarrowHillOnAPlainBecomesATower() {
        Towers towers = towers((x, z) -> hill(x, z, 120, 95));
        assertEquals(1, towers.towerness(32, 32), 0.01);
        // High on its flank the ground climbs to the crown; low on it, it keeps a gentle foot.
        double high = hill(92, 32, 120, 95), low = hill(167, 32, 120, 95);
        assertTrue(towers.shape(92, 32, high, 1) > high + 10, "flank " + high + " -> " + towers.shape(92, 32, high, 1));
        assertTrue(towers.shape(167, 32, low, 1) < low, "foot " + low + " -> " + towers.shape(167, 32, low, 1));
        // Far levels whose voxels are wider than the tower keep the tiles' form.
        assertEquals(high, towers.shape(92, 32, high, 128), 1e-9);
    }

    @Test void broadHillsStayAsTheyAre() {
        Towers towers = towers((x, z) -> hill(x, z, 120, 600));
        for (int x = -600; x <= 600; x += 40) {
            double m = hill(x, 32, 120, 600);
            assertEquals(m, towers.shape(x, 32, m, 1), 1e-9, "at " + x);
        }
    }

    @Test void aKnollAmongGulliesStaysAsItIs() {
        // Upland at 80 m cut every 150 m by gullies down to the sea, and a knoll on it 80 m higher.
        DoubleBinaryOperator ground = (x, z) -> {
            double g = Math.floorMod((long) Math.floor(x), 150) < 20 || Math.floorMod((long) Math.floor(z + 60), 150) < 20 ? 0 : 80;
            return Math.max(g, hill(x, z, 155, 95));
        };
        Towers towers = towers(ground);
        assertEquals(0, towers.towerness(32, 32), 1e-9);
    }
}
