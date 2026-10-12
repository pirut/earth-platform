package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingEditPolicyTest {
    @Test void stableIdsAndReplacementConstructorKeepExplicitCompatibility() {
        assertEquals("interior-v1", RollingEditPolicy.INTERIOR_V1.id());
        assertEquals("full-cube-v1", RollingEditPolicy.FULL_CUBE_V1.id());
        var legacy = new RollingCubeActor.ReplacementPolicy("provider", "material", "detail", 1);
        assertSame(RollingEditPolicy.INTERIOR_V1, legacy.editPolicy());
        var full = new RollingCubeActor.ReplacementPolicy("provider", "material", "detail", 1,
                RollingEditPolicy.FULL_CUBE_V1);
        assertSame(RollingEditPolicy.FULL_CUBE_V1, full.editPolicy());
        assertEquals(legacy.providerIdentity(), full.providerIdentity());
        assertEquals(legacy.materialPolicy(), full.materialPolicy());
        assertEquals(legacy.detailPolicy(), full.detailPolicy());
        assertEquals(legacy.resourceGeneration(), full.resourceGeneration());
        assertThrows(NullPointerException.class,
                () -> new RollingCubeActor.ReplacementPolicy("provider", "material", "detail", 1, null));
    }

    @Test void everyBlockAndAll26BoundaryCategoriesMatchForEverySignedOctant() {
        for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            CubePos original = new CubePos(sx * 2, sy * 8, sz * 3);
            int interior = 0, shell = 0;
            Set<String> categories = new HashSet<>();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                long bx = original.minBlockX() + x, by = original.minBlockY() + y, bz = original.minBlockZ() + z;
                boolean expectedInterior = x > 0 && x < 15 && y > 0 && y < 15 && z > 0 && z < 15;
                assertTrue(RollingEditPolicy.FULL_CUBE_V1.permits(original, bx, by, bz));
                assertEquals(expectedInterior, RollingEditPolicy.INTERIOR_V1.permits(original, bx, by, bz));
                assertEquals(RollingCubePlan.isEditableInterior(original, bx, by, bz),
                        RollingEditPolicy.INTERIOR_V1.permits(original, bx, by, bz));
                if (expectedInterior) interior++;
                else { shell++; categories.add(category(x) + ":" + category(y) + ":" + category(z)); }
            }
            assertEquals(2744, interior); assertEquals(1352, shell); assertEquals(26, categories.size());
            assertEquals(6, categories.stream().filter(c -> c.chars().filter(ch -> ch == 'i').count() == 2).count());
            assertEquals(12, categories.stream().filter(c -> c.chars().filter(ch -> ch == 'i').count() == 1).count());
            assertEquals(8, categories.stream().filter(c -> c.chars().noneMatch(ch -> ch == 'i')).count());
        }
    }

    @Test void all26NeighborCategoriesAndExtremeLongCoordinatesAreRejectedWithoutWrapping() {
        for (CubePos original : new CubePos[]{new CubePos(0, 0, 0), new CubePos(-1, -1, -1), new CubePos(2, 8, 3)}) {
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                long x = original.minBlockX() + outside(dx), y = original.minBlockY() + outside(dy),
                        z = original.minBlockZ() + outside(dz);
                for (RollingEditPolicy policy : RollingEditPolicy.values()) assertFalse(policy.permits(original, x, y, z));
            }
            for (long extreme : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, (long) Integer.MIN_VALUE * 16 - 1,
                    (long) Integer.MAX_VALUE * 16 + 16}) for (RollingEditPolicy policy : RollingEditPolicy.values()) {
                assertFalse(policy.permits(original, extreme, original.minBlockY() + 7, original.minBlockZ() + 7));
                assertFalse(policy.permits(original, original.minBlockX() + 7, extreme, original.minBlockZ() + 7));
                assertFalse(policy.permits(original, original.minBlockX() + 7, original.minBlockY() + 7, extreme));
            }
        }
        // Truncating negative division would accidentally admit these blocks to cube zero.
        assertFalse(RollingEditPolicy.FULL_CUBE_V1.permits(new CubePos(0, 0, 0), -1, -1, -1));
        assertTrue(RollingEditPolicy.FULL_CUBE_V1.permits(new CubePos(-1, -1, -1), -1, -1, -1));
        assertTrue(RollingEditPolicy.FULL_CUBE_V1.permits(new CubePos(-1, -1, -1), -16, -16, -16));
    }

    @Test void validNativeEndpointsAreIncludedButPaddingAndUnrepresentableNativeDomainsAreNot() {
        for (int x : new int[]{RollingCubePlan.NATIVE_XZ_MIN, RollingCubePlan.NATIVE_XZ_MAX})
            for (int z : new int[]{RollingCubePlan.NATIVE_XZ_MIN, RollingCubePlan.NATIVE_XZ_MAX})
                for (int y : new int[]{RollingCubePlan.GAMEPLAY_MIN_Y / 16,
                        RollingCubePlan.GAMEPLAY_MAX_Y_EXCLUSIVE / 16 - 1}) {
                    CubePos p = new CubePos(x, y, z);
                    for (int lx : new int[]{0, 15}) for (int ly : new int[]{0, 15}) for (int lz : new int[]{0, 15})
                        assertTrue(RollingEditPolicy.FULL_CUBE_V1.permits(p,
                                p.minBlockX() + lx, p.minBlockY() + ly, p.minBlockZ() + lz));
                }
        for (CubePos outside : new CubePos[]{
                new CubePos(0, RollingCubePlan.GAMEPLAY_MIN_Y / 16 - 1, 0),
                new CubePos(0, RollingCubePlan.GAMEPLAY_MAX_Y_EXCLUSIVE / 16, 0),
                new CubePos(RollingCubePlan.NATIVE_XZ_MIN - 1, 0, 0),
                new CubePos(RollingCubePlan.NATIVE_XZ_MAX + 1, 0, 0),
                new CubePos(0, 0, RollingCubePlan.NATIVE_XZ_MIN - 1),
                new CubePos(0, 0, RollingCubePlan.NATIVE_XZ_MAX + 1),
                new CubePos(0, RollingCubePlan.NATIVE_Y_MIN - 1, 0),
                new CubePos(0, RollingCubePlan.NATIVE_Y_MAX + 1, 0)}) {
            assertFalse(RollingEditPolicy.FULL_CUBE_V1.permits(outside,
                    outside.minBlockX() + 7, outside.minBlockY() + 7, outside.minBlockZ() + 7));
        }
    }

    private static char category(int local) { return local == 0 ? '0' : local == 15 ? 'h' : 'i'; }
    private static int outside(int direction) { return direction < 0 ? -1 : direction > 0 ? 16 : 7; }
}
