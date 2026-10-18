package io.earthplatform.minecraft.cubic.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarWaterBatchPlanTest {
    @Test void capturesPositionElementOnlyAndOwnsItsCopy() {
        // Model Iris's wider interleaved format, with sentinel bytes in all extended attributes.
        ByteBuffer source = ByteBuffer.allocate(7 + 4 * 64).order(ByteOrder.nativeOrder());
        for (int i = 0; i < source.capacity(); i++) source.put(i, (byte) 0x5A);
        source.position(7);
        for (int vertex = 0; vertex < 4; vertex++) {
            int at = 7 + vertex * 64 + 8;
            source.putFloat(at, vertex + 0.25F);
            source.putFloat(at + 4, -vertex - 0.5F);
            source.putFloat(at + 8, vertex * 2.0F);
        }
        byte[] original = source.array().clone();
        var snapshot = FarWaterBatchPlan.Snapshot.copy(source, 64, 8, 4, false);
        assertNotNull(snapshot);
        assertEquals(7, source.position());
        assertArrayEquals(original, source.array(), "capturing must not change Iris/colour vertex data");
        source.putFloat(15, 900.0F);
        FarWaterBatchPlan plan = plan(snapshot, 10, 20, 30);
        ByteBuffer out = write(plan);
        for (int vertex = 0; vertex < 4; vertex++) {
            assertBits(vertex + 0.25F, out.getFloat());
            assertBits(-vertex - 0.5F, out.getFloat());
            assertBits(vertex * 2.0F, out.getFloat());
            assertBits(10, out.getFloat()); assertBits(20, out.getFloat()); assertBits(30, out.getFloat());
        }
    }

    @Test void retainsExactSubmittedPassOrderAndAllQuads() {
        var nearSurface = quad(1);
        var nearNorthWall = quad(2);
        var farSurface = quad(3);
        var farEastWall = quad(4);
        FarWaterBatchPlan deepen = new FarWaterBatchPlan(4096);
        deepen.begin();
        deepen.add(nearSurface, 0, 0, 0);
        deepen.add(farSurface, 0, 0, 0);
        assertTrue(deepen.end());
        assertEquals(8, deepen.vertices());
        assertDrawValues(write(deepen), 1, 3);

        // Restore has a different source sequence: each column's surface then selected walls.
        FarWaterBatchPlan restore = new FarWaterBatchPlan(4096);
        restore.begin();
        restore.add(nearSurface, 0, 0, 0);
        restore.add(nearNorthWall, 0, 0, 0);
        restore.add(farSurface, 0, 0, 0);
        restore.add(farEastWall, 0, 0, 0);
        assertTrue(restore.end());
        assertEquals(16, restore.vertices());
        assertDrawValues(write(restore), 1, 2, 3, 4);
        // An independently cached depth pass must not rewrite the other one's order.
        assertDrawValues(write(deepen), 1, 3);
    }

    @Test void leavesOriginalFloatOperandsForGpuAdditionAtEarthCoordinates() {
        Random random = new Random(0xEAA7);
        for (int sample = 0; sample < 256; sample++) {
            ByteBuffer local = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
            float[] positions = new float[12];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = (random.nextFloat() - 0.5F) * 8192.0F;
                local.putFloat(positions[i]);
            }
            local.flip();
            float x = (float) (-10_530_304 - (-10_530_225.5 + random.nextDouble()));
            float y = (float) (random.nextInt(100_000) - (1572.25 + random.nextDouble()));
            float z = (float) (-3_593_856 - (-3_593_832.5 + random.nextDouble()));
            ByteBuffer out = write(plan(FarWaterBatchPlan.Snapshot.copy(local, 12, 0, 4, false), x, y, z));
            for (int vertex = 0; vertex < 4; vertex++) {
                assertBits(positions[vertex * 3], out.getFloat());
                assertBits(positions[vertex * 3 + 1], out.getFloat());
                assertBits(positions[vertex * 3 + 2], out.getFloat());
                assertBits(x, out.getFloat()); assertBits(y, out.getFloat()); assertBits(z, out.getFloat());
            }
        }
    }

    @Test void cameraMeshOrderAndSelectionChangesInvalidate() {
        var a = quad(1);
        var b = quad(2);
        FarWaterBatchPlan plan = new FarWaterBatchPlan(4096);
        fill(plan, a, b, 1);
        long original = plan.revision();
        fill(plan, a, b, 1);
        assertEquals(original, plan.revision(), "unchanged views must avoid another copy/upload");
        fill(plan, a, b, Math.nextUp(1.0F));
        assertTrue(plan.revision() > original, "sub-block movement must invalidate");
        long moved = plan.revision();
        fill(plan, b, a, Math.nextUp(1.0F));
        assertTrue(plan.revision() > moved, "same meshes in a different depth order must invalidate");
        long reordered = plan.revision();
        fill(plan, b, quad(1), Math.nextUp(1.0F));
        assertTrue(plan.revision() > reordered, "a replacement upload invalidates even if its bytes match");
        long replaced = plan.revision();
        plan.begin(); plan.add(b, 0, 0, 0); assertTrue(plan.end());
        assertTrue(plan.revision() > replaced);
        assertEquals(4, plan.vertices(), "deselected/fading/covered sources must not stay in the batch");
        plan.begin(); assertTrue(plan.end());
        assertEquals(0, plan.vertices());
        assertEquals(0, write(plan).remaining());
        plan.clear();
        fill(plan, a, b, 1);
        assertTrue(plan.revision() > replaced, "world/shader reload clears the cache");
    }

    @Test void missingClosedUnsupportedAndOverBudgetSourcesRejectWholePass() {
        FarWaterBatchPlan plan = plan(quad(1), 0, 0, 0);
        plan.begin();
        plan.add(quad(2), 0, 0, 0);
        plan.add(null, 0, 0, 0); // missing copy/closed buffer: never draw a partial batch
        assertFalse(plan.end());
        assertEquals(0, plan.vertices());
        assertThrows(IllegalStateException.class, () -> plan.write(ByteBuffer.allocate(96)));
        plan.begin(); plan.add(quad(1), 0, 0, 0); assertTrue(plan.end());
        assertDrawValues(write(plan), 1);
        var closed = quad(1);
        assertEquals(48, closed.release());
        assertEquals(0, closed.release(), "closing a source twice must not release its budget twice");
        plan.begin(); plan.add(closed, 0, 0, 0); assertFalse(plan.end());

        FarWaterBatchPlan small = new FarWaterBatchPlan(96);
        small.begin(); small.add(quad(1), 0, 0, 0); small.add(quad(2), 0, 0, 0);
        assertFalse(small.end());
        assertEquals(0, small.bytes());
        small.begin(); small.add(quad(1), Float.NaN, 0, 0); assertFalse(small.end());

        ByteBuffer data = ByteBuffer.allocate(96).order(ByteOrder.nativeOrder());
        assertNull(FarWaterBatchPlan.Snapshot.copy(data, 12, 0, 4, true), "sorted indices require fallback");
        assertNull(FarWaterBatchPlan.Snapshot.copy(data, 12, 0, 3, false));
        assertNull(FarWaterBatchPlan.Snapshot.copy(data, 8, 0, 4, false));
        assertNull(FarWaterBatchPlan.Snapshot.copy(data, 16, 8, 4, false));
        assertNull(FarWaterBatchPlan.Snapshot.copy(data, 64, 0, 4, false));
        assertNull(FarWaterBatchPlan.Snapshot.copy(data, 12, 0, Integer.MAX_VALUE, false));
        data.putFloat(0, Float.POSITIVE_INFINITY);
        assertNull(FarWaterBatchPlan.Snapshot.copy(data, 12, 0, 4, false));
    }

    @Test void retentionBudgetIsReleasedAndCannotOverflow() {
        var budget = new FarWaterBatchPlan.Retention(96);
        assertTrue(budget.reserve(48));
        assertTrue(budget.reserve(48));
        assertEquals(96, budget.bytes());
        assertFalse(budget.reserve(1));
        assertFalse(budget.reserve(Integer.MAX_VALUE));
        assertFalse(budget.reserve(-1));
        budget.release(48);
        assertTrue(budget.reserve(48));
        budget.release(96);
        assertEquals(0, budget.bytes());
        assertThrows(IllegalArgumentException.class, () -> budget.release(1));
        assertThrows(IllegalArgumentException.class, () -> budget.release(-1));
    }

    @Test void batchVertexShaderChangesOnlyOffsetStorageAndKeepsGpuExpression() {
        String original = FarLodShaders.DEEPEN_VERTEX;
        String batch = FarLodShaders.BATCH_DEPTH_VERTEX;
        assertEquals(original.replace("uniform vec3 earth_Offset;", "layout(location = 1) in vec3 earth_Offset;"), batch);
        assertTrue(batch.contains("earth_ModelView * vec4(Position + earth_Offset, 1.0)"));
        assertTrue(batch.contains("gl_Position = earth_Projection * view;"));
        assertFalse(batch.contains("uniform vec3 earth_Offset;"));
        assertEquals(24, FarWaterBatchPlan.VERTEX_BYTES);
        assertEquals(12, FarWaterBatchPlan.POSITION_BYTES);
    }

    private static FarWaterBatchPlan plan(FarWaterBatchPlan.Snapshot source, float x, float y, float z) {
        FarWaterBatchPlan plan = new FarWaterBatchPlan(4096);
        plan.begin(); plan.add(source, x, y, z); assertTrue(plan.end());
        return plan;
    }

    private static FarWaterBatchPlan.Snapshot quad(float x) {
        ByteBuffer data = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder());
        for (int vertex = 0; vertex < 4; vertex++) data.putFloat(x).putFloat(0).putFloat(0);
        data.flip();
        var snapshot = FarWaterBatchPlan.Snapshot.copy(data, 12, 0, 4, false);
        assertNotNull(snapshot);
        return snapshot;
    }

    private static void fill(FarWaterBatchPlan plan, FarWaterBatchPlan.Snapshot a, FarWaterBatchPlan.Snapshot b, float x) {
        plan.begin(); plan.add(a, x, 0, 0); plan.add(b, x, 0, 0); assertTrue(plan.end());
    }

    private static ByteBuffer write(FarWaterBatchPlan plan) {
        ByteBuffer out = ByteBuffer.allocate(plan.bytes()).order(ByteOrder.nativeOrder());
        plan.write(out); out.flip();
        return out;
    }

    private static void assertDrawValues(ByteBuffer data, float... xs) {
        for (float x : xs) for (int vertex = 0; vertex < 4; vertex++) {
            assertBits(x, data.getFloat()); assertBits(0, data.getFloat()); assertBits(0, data.getFloat());
            assertBits(0, data.getFloat()); assertBits(0, data.getFloat()); assertBits(0, data.getFloat());
        }
        assertFalse(data.hasRemaining());
    }

    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
