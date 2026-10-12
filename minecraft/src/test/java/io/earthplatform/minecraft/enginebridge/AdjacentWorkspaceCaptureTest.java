package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdjacentWorkspaceCaptureTest {
    private static final CubePos A = new CubePos(0, 8, 0), B = A.offset(1, 0, 0);

    @Test void everySharedFaceCellAndReadHaloBandIsCoherentWithoutMutatingOldCapture() {
        var a = new NativeReplacementCapture(A, new byte[8000]);
        var b = new NativeReplacementCapture(B, new byte[8000]);
        for (int x = 14; x <= 17; x++) for (int y = 128; y < 144; y++) for (int z = 0; z < 16; z++) {
            var pa = a.withReadMaterial(x, y, z, NativeReplacementCapture.DIORITE);
            var pb = b.withReadMaterial(x, y, z, NativeReplacementCapture.DIORITE);
            NativeReplacementCapture.verifySharedValues(List.of(pa, pb));
            assertEquals(NativeReplacementCapture.AIR, a.materialAt(x, y, z));
            assertEquals(NativeReplacementCapture.AIR, b.materialAt(x, y, z));
            assertNotEquals(a.contentIdentity(), pa.contentIdentity());
            assertNotEquals(b.contentIdentity(), pb.contentIdentity());
            assertEquals(pa.contentIdentity(), pa.compact().contentIdentity());
            assertEquals(pb.contentIdentity(), pb.compact().contentIdentity());
        }
    }

    @Test void prospectiveSetAndSerialCompactionHaveAtMostThreeOwnedArrays() throws Exception {
        var current = new HashMap<CubePos, NativeReplacementCapture>();
        current.put(A, new NativeReplacementCapture(A, new byte[8000]));
        current.put(B, new NativeReplacementCapture(B, new byte[8000]));
        var prospective = new HashMap<CubePos, NativeReplacementCapture>();
        for (var entry : current.entrySet())
            prospective.put(entry.getKey(), entry.getValue().withReadMaterial(15, 129, 0, NativeReplacementCapture.DIORITE));
        assertEquals(16_000, ownedBytes(current, prospective, null));
        for (CubePos position : List.of(A, B)) {
            var compact = prospective.remove(position).compact();
            assertEquals(24_000, ownedBytes(current, prospective, compact));
            current.put(position, compact);
            assertEquals(16_000, ownedBytes(current, prospective, null));
        }
        assertTrue(prospective.isEmpty()); NativeReplacementCapture.verifySharedValues(List.of(current.get(A), current.get(B)));
        var overlay = current.get(A).withReadMaterial(15, 129, 0, NativeReplacementCapture.STONE);
        assertThrows(IllegalStateException.class, () -> overlay.withReadMaterial(15, 130, 0, NativeReplacementCapture.STONE));
        assertEquals(8000, overlay.declaredBytes()); assertEquals(8000, overlay.cellCount());
    }

    private static int ownedBytes(Map<CubePos, NativeReplacementCapture> current,
            Map<CubePos, NativeReplacementCapture> prospective, NativeReplacementCapture temporary) throws Exception {
        var arrays = new IdentityHashMap<byte[], Boolean>();
        for (var capture : current.values()) collect(capture, arrays);
        for (var capture : prospective.values()) collect(capture, arrays);
        if (temporary != null) collect(temporary, arrays);
        return arrays.keySet().stream().mapToInt(value -> value.length).sum();
    }
    private static void collect(NativeReplacementCapture capture, IdentityHashMap<byte[], Boolean> arrays) throws Exception {
        Field bytes = NativeReplacementCapture.class.getDeclaredField("paletteVoxels"); bytes.setAccessible(true);
        byte[] value = (byte[]) bytes.get(capture);
        if (value != null) arrays.put(value, true);
        Field source = NativeReplacementCapture.class.getDeclaredField("source"); source.setAccessible(true);
        var parent = (NativeReplacementCapture) source.get(capture);
        if (parent != null) collect(parent, arrays);
    }
}
