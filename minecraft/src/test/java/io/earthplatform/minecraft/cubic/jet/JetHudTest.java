package io.earthplatform.minecraft.cubic.jet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JetHudTest {
    @Test void hintsNearTheWindAndShowsSpeedAndHeadingInIt() {
        assertNull(JetHud.message(3000, 30, 1, 0));
        assertTrue(JetHud.message(8500, 30, 1, 0).getString().contains("9,200"));
        assertNull(JetHud.message(8500, 30, -1, 0), "no climbing hint while coming down");
        String riding = JetHud.message(10000, 1650, 0, -90).getString();
        assertTrue(riding.contains("1,650") && riding.contains("east"), riding);
        assertTrue(JetHud.message(10000, 1220, 0, 90).getString().contains("west"));
        assertTrue(JetHud.message(10000, 1300, 0, 180).getString().contains("north"));
        assertTrue(JetHud.message(5000, 800, -1, 0).getString().contains("slowing down"));
    }
}
