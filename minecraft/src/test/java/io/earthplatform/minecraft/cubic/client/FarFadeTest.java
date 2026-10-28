package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.far.Far;
import org.junit.jupiter.api.Test;

class FarFadeTest {
    @Test void slowFadesKeepTheUsualLength() {
        for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) {
            assertEquals(FarShaders.FADE_TICKS, FarFade.target(level, 0.0), 1e-6);
            // Walking, and ordinary elytra flight past the levels whose columns take long to cross.
            assertEquals(FarShaders.FADE_TICKS, FarFade.target(level, 4.3), 1e-6);
        }
        assertEquals(FarShaders.FADE_TICKS, FarFade.target(7, 80.0), 1e-6);
    }

    @Test void jetStreamFadesLastAsLongAsCrossingAColumn() {
        // 1,440 blocks a second: a level 7 column (2,048 blocks) takes 1.42 s, a level 8 one 2.84 s.
        assertEquals(142.2, FarFade.target(7, 1440.0), 0.1);
        assertEquals(284.4, FarFade.target(8, 1440.0), 0.1);
        // Columns crossed quicker than the usual fade keep it.
        assertEquals(FarShaders.FADE_TICKS, FarFade.target(6, 3000.0), 1e-6);
    }

    @Test void longCrossingsComeBackDownSmoothly() {
        double speed = Far.width(7) * 100.0 / FarFade.MAX_TICKS;
        assertEquals(FarFade.MAX_TICKS, FarFade.target(7, speed), 0.01);
        assertEquals((FarFade.MAX_TICKS + FarShaders.FADE_TICKS) / 2.0, FarFade.target(7, speed / 1.5), 0.01);
        assertEquals(FarShaders.FADE_TICKS, FarFade.target(7, speed / 2), 0.01);
        float last = FarFade.target(7, speed);
        for (double s = speed; s > speed / 2; s -= 1.0) {
            float next = FarFade.target(7, s);
            assertTrue(Math.abs(next - last) < 2.0F, "jump at " + s);
            last = next;
        }
    }

    @Test void lengthsMoveAStepATick() {
        FarFade fade = new FarFade();
        assertEquals(FarShaders.FADE_TICKS, fade.ticks(8));
        fade.tick(1440.0);
        assertEquals(FarShaders.FADE_TICKS + Math.round(FarFade.STEP), fade.ticks(8));
        for (int i = 0; i < 100; i++) fade.tick(1440.0);
        assertEquals(284, fade.ticks(8));
        assertEquals(142, fade.ticks(7));
        assertEquals(71, fade.ticks(6));
        assertEquals(FarShaders.FADE_TICKS, fade.ticks(5));
        assertEquals(Far.MAX_LEVEL + 2, fade.table().length);
        assertEquals(284.0F, fade.table()[8]);
        // Stopping at once: fades under way shorten a step a tick rather than end together.
        fade.tick(0.0);
        assertEquals(284 - Math.round(FarFade.STEP), fade.ticks(8));
    }

    @Test void aFadeUnderWayNeverGoesBack() {
        // How far through a fade is: its age over its length. Lengthening a step a tick (a tick is five
        // hundredths) keeps that from falling as the fade ages.
        FarFade fade = new FarFade();
        double last = 0;
        for (int age = 0; age <= 300; age += 5) {
            double through = Math.min(1.0, age / (double) fade.ticks(8));
            assertTrue(through >= last, "went back at " + age);
            last = through;
            fade.tick(1440.0);
        }
    }

    @Test void theLeadIsOnlyFlyingFast() {
        assertNull(FarLead.at(100, 200, 4.3, 0.0));
        assertNull(FarLead.at(100, 200, 120.0, 100.0));
        int[] lead = FarLead.at(100, 200, 1440.0, -300.0);
        assertEquals(100 + 4320, lead[0]);
        assertEquals(200 - 900, lead[1]);
    }

    @Test void shadersTakeEachLevelsFadeLength() {
        assertTrue(FarShaders.VERTEX.contains("uniform float earth_FadeTicks[" + (Far.MAX_LEVEL + 2) + "];"));
        assertTrue(FarShaders.VERTEX.contains("earth_FadeTicks[shift - 4]"));
        assertFalse(FarShaders.fade(FarGpu.FADE_IN).contains("EARTH_FADE_TICKS"));
    }
}
