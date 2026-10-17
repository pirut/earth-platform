package io.earthplatform.minecraft.cubic.client;

import java.util.List;
import java.util.function.Consumer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarPickStateTest {
    private static final class View {
        double x = -100.5, y = 1500, z = 20, fx = -101, fy = 1500, fz = 20;
        Quaternionf rotation = new Quaternionf();
        Matrix4f frustum = new Matrix4f();
        int columns = 1, meshes = 2, finest = 1, hole = 11;
        boolean band;

        boolean changed(FarPickState state) {
            return state.changed(x, y, z, rotation, frustum, fx, fy, fz, columns, meshes, finest, hole, band);
        }
    }

    @Test void invalidatesEverySelectionInputIncludingSubBlockMotion() {
        List<Consumer<View>> changes = List.of(v -> v.x += 0.01, v -> v.y += 0.01, v -> v.z += 0.01,
                v -> v.rotation.rotateY(0.01F), v -> v.rotation.rotateX(0.01F), v -> v.rotation.rotateZ(0.01F),
                v -> v.frustum.m00(2), v -> v.frustum.m32(2),
                v -> v.fx += 0.01, v -> v.fy += 0.01, v -> v.fz += 0.01,
                v -> v.columns++, v -> v.meshes++, v -> v.finest++, v -> v.hole++, v -> v.band = true);
        for (Consumer<View> change : changes) {
            FarPickState state = new FarPickState();
            View view = new View();
            assertTrue(view.changed(state));
            assertFalse(view.changed(state));
            change.accept(view);
            assertTrue(view.changed(state));
            assertFalse(view.changed(state));
        }
    }

    @Test void comparesValuesAndCopiesMutableInputs() {
        FarPickState state = new FarPickState();
        View view = new View();
        assertTrue(view.changed(state));
        view.rotation = new Quaternionf(view.rotation);
        view.frustum = new Matrix4f(view.frustum);
        assertFalse(view.changed(state));
        view.frustum.m00(2);
        assertTrue(view.changed(state));
        view.frustum.m00(1);
        assertTrue(view.changed(state));
        state.invalidate();
        assertTrue(view.changed(state));
    }
}
