package io.earthplatform.minecraft.scene;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneProjectionTest {
    @Test void sharedWideDepthRangePreservesNativeFovAspectZoomAndInputMatrix() {
        for (float aspect : new float[]{1, 16f / 9f, 9f / 16f}) {
            var original = new Matrix4f().setPerspective((float) Math.toRadians(83), aspect, 0.1f, 128)
                    .m20(0.125f).m21(-0.25f);
            var before = new Matrix4f(original);
            var widened = SceneSession.wideProjection(original);
            assertNotSame(original, widened);
            assertEquals(before, original);
            float[] expected = before.get(new float[16]), actual = widened.get(new float[16]);
            for (int i = 0; i < 16; i++) if (i != 10 && i != 14) assertEquals(expected[i], actual[i], "matrix component " + i);
            assertEquals(-(2048f + 0.05f) / (2048f - 0.05f), widened.m22());
            assertEquals(-(2 * 2048f * 0.05f) / (2048f - 0.05f), widened.m32());
            assertEquals(-1, depth(widened, 0.05f), 1e-5);
            assertEquals(1, depth(widened, 2048f), 1e-5);
            assertTrue(depth(widened, 0.025f) < -1);
            assertTrue(depth(widened, 512f) < 1);
            assertTrue(depth(widened, 4096f) > 1);
        }
    }

    private static float depth(Matrix4f matrix, float distance) {
        var clip = new Vector4f(0, 0, -distance, 1).mul(matrix);
        return clip.z / clip.w;
    }

    @Test void orthographicAndInvalidPerspectiveCamerasAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SceneSession.wideProjection(new Matrix4f()));
        assertThrows(IllegalArgumentException.class,
                () -> SceneSession.wideProjection(new Matrix4f().setOrtho(-1, 1, -1, 1, 0.05f, 2048)));
        var perspective = new Matrix4f().setPerspective((float) Math.toRadians(70), 1, 0.05f, 128);
        assertThrows(IllegalArgumentException.class, () -> SceneSession.wideProjection(new Matrix4f(perspective).m23(-0.5f)));
        assertThrows(IllegalArgumentException.class, () -> SceneSession.wideProjection(new Matrix4f(perspective).m33(1)));
    }
}
