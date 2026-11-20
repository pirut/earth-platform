package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ForestTypesTest {
    @Test
    void bundledMapKnowsTheWorldsForests() {
        ForestTypes map = ForestTypes.get();
        assertNotNull(map);
        double[] mix = new double[2];
        // The Black Forest and Finland's taiga are conifer; the Appalachians are deciduous broadleaf; the Amazon is evergreen.
        assertTrue(map.at(48.2, 8.2, mix) && mix[0] > 0.7, "Black Forest");
        assertTrue(map.at(62.0, 26.0, mix) && mix[0] > 0.7, "Finland");
        assertTrue(map.at(37.6, -80.3, mix) && mix[0] < 0.3 && mix[1] < 0.3, "Appalachians");
        assertTrue(map.at(-4.6, -63.2, mix) && mix[0] < 0.2 && mix[1] > 0.8, "Amazon");
        // Open ocean and the Sahara have none.
        assertFalse(map.at(0, -30, mix), "Atlantic");
        assertFalse(map.at(23.0, 12.0, mix), "Sahara");
    }

    @Test
    void codesDecode() {
        assertTrue(ForestTypes.needle(16 + 16 * 14 + 15) == 1 && ForestTypes.evergreen(16 + 16 * 14 + 15) == 1);
        assertTrue(ForestTypes.needle(16) == 0 && ForestTypes.evergreen(16) == 0);
    }
}
