package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Every compact-support column and complete native cube, plus the exact flat-exterior proof. */
class ArenaHillsNativeEnvelopeTest {
    private static final ArenaTerrainSource SOURCE = ArenaTerrainSource.FIXED;
    private static final int[][] CARDINAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] NEIGHBORS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    @Test void everySourceAffectedWholeNativeCubeFitsTheUnchangedCap() {
        var counts = new TreeMap<CubePos, Integer>();
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, maxSlope = 0, columns = 0;
        // Complete support x[-320,336),z[-352,352), expanded by one full native cube.
        // Every unscanned column is identically144; no feature exists outside the fixed arena.
        for (int cz = -23; cz <= 22; cz++) for (int cx = -21; cx <= 21; cx++) {
            for (int cy = 7; cy <= 13; cy++) counts.put(new CubePos(cx, cy, cz), 0);
            for (int z = cz * 16; z < (cz + 1) * 16; z++) for (int x = cx * 16; x < (cx + 1) * 16; x++) {
                int top = SOURCE.baseSurfaceY(x, z); columns++;
                min = Math.min(min, top); max = Math.max(max, top);
                counts.merge(CubePos.fromBlock(x, top, z), 1, Integer::sum);
                for (int[] delta : CARDINAL) {
                    int neighbor = SOURCE.baseSurfaceY(x + delta[0], z + delta[1]);
                    int difference = top - neighbor;
                    maxSlope = Math.max(maxSlope, Math.abs(difference));
                    assertTrue(Math.abs(difference) <= 1, "Complete source cardinal slope census");
                    for (int y = neighbor + 1; y <= top; y++) counts.merge(CubePos.fromBlock(x, y, z), 1, Integer::sum);
                }
            }
        }
        // Replace every feature/edit-sensitive whole cube with a six-neighbor voxel oracle.
        // This counts the cave, shaft, roof underside, all border materials, and cross-cube faces.
        for (CubePos cube : SOURCE.definition().dependencies()) {
            int faces = 0;
            for (long y = cube.minBlockY(); y < cube.minBlockY() + 16; y++)
                for (long z = cube.minBlockZ(); z < cube.minBlockZ() + 16; z++)
                    for (long x = cube.minBlockX(); x < cube.minBlockX() + 16; x++) {
                        if (SOURCE.materialAt(x, y, z) == ArenaTerrainSource.AIR) continue;
                        for (int[] delta : NEIGHBORS)
                            if (SOURCE.materialAt(x + delta[0], y + delta[1], z + delta[2]) == ArenaTerrainSource.AIR) faces++;
                    }
            assertNotNull(counts.replace(cube, faces));
        }
        int largest = 0; CubePos largestCube = null; long totalFaces = 0;
        for (var entry : counts.entrySet()) {
            int faces = entry.getValue(); totalFaces += faces;
            assertTrue(faces <= 1280, "One top and at most four unit sides per base column, or exact feature census");
            assertTrue(faces * 4L * 52 <= 1_048_576, "Unchanged whole native mesh byte cap");
            if (faces > largest) { largest = faces; largestCube = entry.getKey(); }
        }
        assertEquals(144, min); assertEquals(208, max); assertEquals(1, maxSlope);
        assertEquals(513, largest); assertEquals(106704, largest * 4L * 52);
        assertEquals(new CubePos(-5, 11, 3), largestCube);
        assertEquals(43 * 46 * 256, columns); assertEquals(43 * 46 * 7, counts.size());
        // Infinite exterior: flat144 gives256 UP faces in cubeY9, zero faces at every otherY.
        assertEquals(144, SOURCE.baseSurfaceY(-337, 0));
        assertEquals(144, SOURCE.baseSurfaceY(352, 0));
        assertEquals(144, SOURCE.baseSurfaceY(0, -369));
        assertEquals(144, SOURCE.baseSurfaceY(0, 368));
        System.out.println("ARENA_HILLS_COMPLETE_NATIVE_CENSUS columns=" + columns + " wholeCubes=" + counts.size()
                + " exactFeatureCubes=64 minY=" + min + " maxY=" + max + " maxCardinalSlope=" + maxSlope
                + " maxExposedFaces=" + largest + " maxActualStrideBytes=" + largest * 4L * 52
                + " largestCube=" + largestCube + " totalFaces=" + totalFaces
                + " exterior=flat144,256tops-at-cubeY9,zero-otherY analyticBaseFaceUpper=1280 analyticBaseBytesUpper=266240");
    }

    @Test void completeReadHaloBytesMatchTheFrozen028SourceExactly() throws Exception {
        byte[] cells = new byte[36 * 36 * 36]; int i = 0;
        for (int y = 126; y < 162; y++) for (int z = -2; z < 34; z++) for (int x = -2; x < 34; x++)
            cells[i++] = (byte) SOURCE.materialAt(x, y, z);
        assertEquals("6d3b84e305bab1a45172eb2a9f78c78cda601b353a3752567795c2b28ef846be",
                HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(cells)));
        assertEquals("50bdd343a0c58e77accc4072df927d01df8b69d3b1b7be8d76a7e2bd74aaedb2", SOURCE.definition().identity());
    }

    @Test void completeArenaAndSpawnReadHaloStayOnTheFlatClearing() {
        var d = SOURCE.definition();
        for (long z = d.readMinZ(); z < d.readMaxZExclusive(); z++)
            for (long x = d.readMinX(); x < d.readMaxXExclusive(); x++) assertEquals(144, SOURCE.baseSurfaceY(x, z));
        for (int z = -12; z <= 44; z++) for (int x = -12; x <= 44; x++)
            if ((x - 16) * (x - 16) + (z - 16) * (z - 16) <= 28 * 28) assertEquals(144, SOURCE.baseSurfaceY(x, z));
        // Fixed spawn8.5,145.01,12.5 has a solid floor and two clear body cells.
        assertEquals(ArenaTerrainSource.STONE, SOURCE.materialAt(8, 144, 12));
        assertEquals(ArenaTerrainSource.AIR, SOURCE.materialAt(8, 145, 12));
        assertEquals(ArenaTerrainSource.AIR, SOURCE.materialAt(8, 146, 12));
    }
}
