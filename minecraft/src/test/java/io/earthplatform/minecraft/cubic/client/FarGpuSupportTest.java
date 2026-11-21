package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The far-terrain GL gate, without a context: OpenGL 3.3 and newer stay as they were. */
class FarGpuSupportTest {
    @Test void openGl33AndNewerKeepTheSameGlslVersion() {
        assertEquals("#version 330 core\n", version(false, false, false, false, false, false, false, true));
        assertEquals("#version 400 core\n", version(false, false, false, false, false, false, true, true));
        assertEquals("#version 410 core\n", version(false, false, false, false, false, true, true, true));
        assertEquals("#version 420 core\n", version(false, false, false, false, true, true, true, true));
        assertEquals("#version 430 core\n", version(false, false, false, true, true, true, true, true));
        assertEquals("#version 440 core\n", version(false, false, true, true, true, true, true, true));
        assertEquals("#version 450 core\n", version(false, true, true, true, true, true, true, true));
        assertEquals("#version 460 core\n", version(true, true, true, true, true, true, true, true));
        // A higher version flag wins on its own, as it does when only that capability is set.
        assertEquals("#version 460 core\n", version(true, false, false, false, false, false, false, false));
    }

    @Test void openGl32FallsBackToGlsl150WithOnlyTheExtensionsPresent() {
        String both = FarGpu.glslVersion(false, false, false, false, false, false, false, false, true, true);
        assertEquals("""
                #version 150 core
                #extension GL_ARB_shader_bit_encoding : enable
                #extension GL_ARB_explicit_attrib_location : enable
                """, both);
        String bitCastOnly = FarGpu.glslVersion(false, false, false, false, false, false, false, false, true, false);
        assertEquals("#version 150 core\n#extension GL_ARB_shader_bit_encoding : enable\n", bitCastOnly);
        String locationsOnly = FarGpu.glslVersion(false, false, false, false, false, false, false, false, false, true);
        assertEquals("#version 150 core\n#extension GL_ARB_explicit_attrib_location : enable\n", locationsOnly);
        assertEquals("#version 150 core\n", FarGpu.glslVersion(false, false, false, false, false, false, false, false, false, false));
        // Extensions never ride along on a 3.3 context: that header is unchanged.
        assertEquals("#version 330 core\n", FarGpu.glslVersion(false, false, false, false, false, false, false, true, true, true));
    }

    @Test void supportedFollowsTheDrawFeaturesRatherThanTheOpenGl33Flag() {
        assertTrue(FarGpu.supported(true, true, true, true, true));
        assertFalse(FarGpu.supported(true, true, true, true, false), "uintBitsToFloat needs GLSL 330 or its extension");
        assertFalse(FarGpu.supported(false, true, true, true, true));
        assertFalse(FarGpu.supported(true, false, true, true, true));
        assertFalse(FarGpu.supported(true, true, false, true, true));
        assertFalse(FarGpu.supported(true, true, true, false, true));
    }

    private static String version(boolean gl46, boolean gl45, boolean gl44, boolean gl43, boolean gl42, boolean gl41, boolean gl40, boolean gl33) {
        return FarGpu.glslVersion(gl46, gl45, gl44, gl43, gl42, gl41, gl40, gl33, false, false);
    }
}
