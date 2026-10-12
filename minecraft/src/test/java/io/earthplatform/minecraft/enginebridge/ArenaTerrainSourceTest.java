package io.earthplatform.minecraft.enginebridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent scalar equations and all 36-cubed read-halo cells; no engine bootstrap. */
class ArenaTerrainSourceTest {
    private final ArenaTerrainSource source = ArenaTerrainSource.FIXED;

    @Test void compactSmoothHillsHaveExactReliefOpenExteriorAndBoundedUnitSlope() {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int z = -368; z <= 368; z++) for (int x = -336; x <= 352; x++) {
            int height = source.baseSurfaceY(x, z);
            assertEquals(referenceHeight(x, z), height, "Independent scalar policy");
            assertEquals(height, source.surfaceY(x, z));
            assertTrue(Math.abs(height - source.baseSurfaceY(x + 1L, z)) <= 1);
            assertTrue(Math.abs(height - source.baseSurfaceY(x, z + 1L)) <= 1);
            min = Math.min(min, height); max = Math.max(max, height);
        }
        assertEquals(144, min); assertEquals(208, max); assertEquals(64, max - min);
        assertEquals(208, source.baseSurfaceY(-144, 48));
        assertEquals(196, source.baseSurfaceY(96, -160));
        assertEquals(188, source.baseSurfaceY(176, 144));
        for (long extreme : new long[] {Long.MIN_VALUE, Long.MIN_VALUE + 16, Long.MAX_VALUE - 16, Long.MAX_VALUE}) {
            assertEquals(144, source.baseSurfaceY(extreme, 16));
            assertEquals(144, source.baseSurfaceY(16, extreme));
            assertEquals(144, source.baseSurfaceY(extreme, extreme));
        }
        // An entire distant square perimeter is low, so there is no enclosing raised rim.
        for (int t = -512; t <= 512; t++) {
            assertEquals(144, source.baseSurfaceY(t, -512));
            assertEquals(144, source.baseSurfaceY(t, 512));
            assertEquals(144, source.baseSurfaceY(-512, t));
            assertEquals(144, source.baseSurfaceY(512, t));
        }
    }

    private static int referenceHeight(int x, int z) {
        double amplitude = 0;
        int[][] hills = {{-144, 48, 176, 176, 64}, {96, -160, 192, 192, 52}, {176, 144, 160, 208, 44}};
        for (int[] h : hills) {
            long dx = x - h[0], dz = z - h[1];
            double r2 = (double) (dx * dx) / (h[2] * h[2]) + (double) (dz * dz) / (h[3] * h[3]);
            if (r2 < 1) amplitude = Math.max(amplitude, h[4] * ((1 - r2) * (1 - r2)));
        }
        long dx = x - 16L, dz = z - 16L;
        double radius = StrictMath.sqrt(dx * dx + dz * dz);
        double t = Math.max(0, Math.min(1, (radius - 28) / 84));
        return 144 + (int) StrictMath.floor(amplitude * (t * t * (3 - 2 * t)));
    }

    @Test void completeOpaqueColumnsAgreeAtEveryOwnedAndReadShellCell() {
        var d = source.definition(); int outside = 0, changedInside = 0;
        for (long y = d.readMinY(); y < d.readMaxYExclusive(); y++)
            for (long z = d.readMinZ(); z < d.readMaxZExclusive(); z++)
                for (long x = d.readMinX(); x < d.readMaxXExclusive(); x++) {
                    int base = source.baseMaterialAt(x, y, z), actual = source.materialAt(x, y, z);
                    assertTrue(actual >= 0 && actual <= 2);
                    if (!d.editable(x, y, z)) { assertEquals(base, actual); outside++; }
                    else if (base != actual) changedInside++;
                }
        assertEquals(36 * 36 * 36 - 32 * 32 * 32, outside);
        assertTrue(changedInside > 500, "Real caves, arch and marked boundary differ from heightfield");
        for (int z = -320; z <= 352; z += 7) for (int x = -320; x <= 352; x += 7) {
            int height = source.baseSurfaceY(x, z), material = height >= 184 ? 2 : 1;
            assertEquals(material, source.baseMaterialAt(x, height, z));
            assertEquals(material, source.baseMaterialAt(x, height - 200L, z));
            assertEquals(0, source.baseMaterialAt(x, height + 1L, z));
            assertEquals(ArenaTerrainSource.argb(material), source.baseArgb(x, z));
            assertEquals(255, source.baseArgb(x, z) >>> 24);
        }
    }

    @Test void generatedCaveArchAndBorderAreGenuinelyThreeDimensional() {
        assertEquals(0, source.materialAt(18, 138, 18));
        assertEquals(1, source.materialAt(18, 141, 18)); // chamber ceiling
        assertEquals(1, source.materialAt(18, 135, 18)); // chamber floor
        assertEquals(0, source.materialAt(21, 144, 21)); // daylight shaft
        assertEquals(1, source.baseMaterialAt(21, 144, 21)); // far base deliberately does not carve it
        assertEquals(0, source.materialAt(8, 148, 5)); // arch passage
        assertEquals(2, source.materialAt(8, 151, 5)); // overhang roof
        assertEquals(2, source.materialAt(4, 145, 5)); // support reaches ground
        assertEquals(2, source.materialAt(0, 144, 15)); // editable border only
        assertEquals(1, source.materialAt(-1, 144, 15)); // immutable collar unchanged
        assertEquals(0, source.materialAt(8, 153, 5));
    }

    @Test void identityAndCoveragePinTheCompleteFixedPolicyWithoutEngineState() {
        assertEquals("643517cefb6fa527380f203c504d4c02912740cdbf1d066509c2e9d7eb71c2fa", source.identity());
        assertEquals(source.identity(), new ArenaTerrainSource(ArenaDefinition.FIXED).identity());
        assertTrue(source.identity().matches("[0-9a-f]{64}"));
        assertTrue(source.canonicalIdentity().contains(source.definition().identity()));
        assertTrue(source.canonicalIdentity().startsWith("earth-arena-terrain-v2-three-smooth-hills:"));
        assertTrue(source.canonicalIdentity().contains("roof-y[151,153)"));
        for (int x = 15; x <= 16; x++) for (int y = 143; y <= 144; y++) for (int z = 15; z <= 16; z++)
            assertEquals(ArenaTerrainSource.STONE, source.materialAt(x, y, z), "All eight scenario seam cells unchanged");
        assertEquals(ArenaTerrainSource.STONE, source.materialAt(8, 144, 12));
        assertEquals(ArenaTerrainSource.AIR, source.materialAt(8, 145, 12));
        assertEquals(ArenaTerrainSource.AIR, source.materialAt(8, 146, 12));
        assertTrue(source.knownFootprint(Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(source.knownFootprint(0, 0, 0, 1));
        assertFalse(source.knownFootprint(0, 0, 1, -1));
        assertThrows(NullPointerException.class, () -> new ArenaTerrainSource(null));
        assertThrows(IllegalArgumentException.class, () -> ArenaTerrainSource.argb(3));
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) assertEquals(144, source.baseSurfaceY(x, z));
    }

    @Test void everyBaselineBodyCubeOutsideArenaDependenciesFitsNativeFullCubeMeshCap() {
        // All 5-cubed body-window positions for centers X/Z[-16,15], Y[6,15].
        // Exhaustive support proof below establishes unit cardinal slope. A base column
        // owns one top and at most four descending edges, each one metre high.
        // Thus ANY 16-cubed base mesh has <=256 tops +1024 sides, zero down faces.
        int maxFaces = 0, countedCubes = 0; long totalFaces = 0;
        var dependencies = new java.util.HashSet<>(source.definition().dependencies());
        for (int cz = -18; cz <= 17; cz++) for (int cx = -18; cx <= 17; cx++) {
            int[] facesByCubeY = new int[14];
            for (int z = cz * 16; z < cz * 16 + 16; z++) for (int x = cx * 16; x < cx * 16 + 16; x++) {
                int top = source.baseSurfaceY(x, z), lowerNeighbors = 0;
                facesByCubeY[Math.floorDiv(top, 16) - 4]++;
                for (int[] delta : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    int neighbor = source.baseSurfaceY(x + delta[0], z + delta[1]);
                    assertTrue(Math.abs(top - neighbor) <= 1);
                    if (top > neighbor) {
                        lowerNeighbors++;
                        for (int y = neighbor + 1; y <= top; y++) facesByCubeY[Math.floorDiv(y, 16) - 4]++;
                    }
                }
                assertTrue(lowerNeighbors <= 4, "All four exposed cardinal edges counted, including exact hill peaks");
            }
            for (int cy = 4; cy <= 17; cy++) {
                if (dependencies.contains(new io.earthplatform.core.CubePos(cx, cy, cz))) continue;
                int faces = facesByCubeY[cy - 4];
                assertTrue(faces <= 1280);
                assertTrue(faces * 4L * 52 <= 1024 * 1024, "Actual 52-byte shader stride, four vertices per opaque face");
                maxFaces = Math.max(maxFaces, faces); totalFaces += faces; countedCubes++;
            }
        }
        assertEquals(36 * 36 * 14 - 64, countedCubes);
        assertEquals(513, maxFaces);
        System.out.println("ARENA_BASE_NATIVE_CENSUS cubes=" + countedCubes + " maxExposedFaces=" + maxFaces
                + " maxActualStrideBytes=" + maxFaces * 4L * 52 + " analyticFaceUpper=1280 analyticByteUpper=266240"
                + " totalExposedFaces=" + totalFaces + " excludes=64-exact-arena-dependencies");
    }
}
