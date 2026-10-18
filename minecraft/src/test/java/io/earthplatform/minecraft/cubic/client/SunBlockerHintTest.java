package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SunBlockerHintTest {
    // Boolean oracle: an earlier blocker returns the same 1.0. Only a clipping terminator before
    // the hinted index can make a later sampled blocker change the original answer.
    private static boolean original(int termination, int blockers) {
        for (int i = 0; i < termination; i++) if ((blockers & 1 << i) != 0) return true;
        return false;
    }
    private static boolean hinted(int termination, int blockers, int candidate) {
        if (candidate >= 0 && candidate < 32 && candidate < termination && (blockers & 1 << candidate) != 0) return true;
        return original(termination, blockers);
    }
    @Test void currentPrefixAndBlockerValidationPreserveOriginalBooleanForEveryIndexAndTerminator() {
        Random random = new Random(907);
        for (int termination = 0; termination <= 32; termination++) for (int hint = -1; hint <= 32; hint++) {
            for (int n = 0; n < 128; n++) {
                int blockers = random.nextInt();
                assertEquals(original(termination, blockers), hinted(termination, blockers, hint));
            }
            for (int blocker = 0; blocker < 32; blocker++)
                assertEquals(original(termination, 1 << blocker), hinted(termination, 1 << blocker, hint));
        }
    }
    @Test void originalFallbackAndAoArithmeticRemainPresentAndFloatSteppingIsNotReplacedByPowers() {
        String original = FarLodShaders.SUN, hinted = FarLodShaders.SUN_HINTS;
        String ao = original.substring(original.indexOf("float earth_occlusion"));
        assertTrue(hinted.contains(ao.replace("earth_shadow = vec2(0.0);", "earth_shadow = vec2(0.0);\n    earth_hint = 0.0;")));
        assertTrue(hinted.contains("for (int i = 0; i < 32; i++, t *= 1.3)"));
        assertTrue(hinted.contains("for (int j = 0; j < 32; j++, hintedT *= 1.3)"));
        assertFalse(hinted.contains("pow("));
        assertTrue(hinted.contains("hintedClip.w <= 0.0"));
        assertTrue(hinted.contains("greaterThan(hintedUv, vec2(1.0))"));
        assertTrue(hinted.contains("lessThan(hintedUv, vec2(0.0))"));
        assertTrue(hinted.contains("isnan(hintedClip)"));
        assertTrue(hinted.contains("hintedBehind - hintedFront < 30.0 + 0.3 * hintedT"));
    }
}
