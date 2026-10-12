package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.ArenaDefinition;
import io.earthplatform.minecraft.enginebridge.ArenaTerrainSource;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Final production partition + source census, with a stronger all-Y native-mask monotonicity proof. */
@Timeout(180)
class DhArenaSourceBudgetTest {
    static final ArenaTerrainSource SOURCE = ArenaTerrainSource.FIXED;
    static final DhTerrainGeometry.Volume ARENA = new DhTerrainGeometry.Volume(-1, 127, -1, 33, 161, 33);
    static final SceneSurfaceSampler SAMPLER = new SceneSurfaceSampler() {
        public long surfaceY(long x, long z) { return SOURCE.baseSurfaceY(x, z); }
        public int argb(long x, long z, long surfaceY) { return SOURCE.baseArgb(x, z); }
        public String identity() { return SOURCE.identity(); }
        public boolean supportsFootprintCoverage() { return true; }
        public boolean supportsConcurrentReads() { return true; }
        public boolean knownFootprint(long x0, long z0, long x1, long z1) { return SOURCE.knownFootprint(x0, z0, x1, z1); }
    };

    static List<CubePos> refinement(int cx, int cz) {
        var result = new ArrayList<CubePos>();
        for (int z = cz - 1; z <= cz + 1; z++) for (int x = cx - 1; x <= cx + 1; x++)
            result.add(new CubePos(x, 9, z));
        result.addAll(ArenaDefinition.FIXED.positions());
        return List.copyOf(result);
    }
    static long farCenter(int coordinate) { return 256L * Math.floorDiv(16 * coordinate + 136, 256); }
    static DhAdaptiveTerrainData terrain(int cx, int cz) {
        return new DhAdaptiveTerrainData(SAMPLER, farCenter(cx), farCenter(cz), DhLodLayout.ARENA_4096);
    }

    @Test void distinctLayoutKeepsAllFrozenLayoutsAndCapsUnchanged() {
        assertEquals(List.of(new DhLodLayout.Ring(128, 8, 512), new DhLodLayout.Ring(256, 16, 512),
                new DhLodLayout.Ring(512, 32, 512), new DhLodLayout.Ring(1024, 64, 1024),
                new DhLodLayout.Ring(4096, 128, 4096)), DhLodLayout.ARENA_4096.rings());
        assertEquals(new DhLodLayout.Ring(512, 16, 256), DhLodLayout.LONG_4096.rings().getFirst());
        assertEquals(List.of(new DhLodLayout.Ring(512, 16, 256)), DhLodLayout.FIXED_512.rings());
        assertEquals("arena-4096-v2-three-smooth-hills", DhLodLayout.ARENA_4096.id());
        assertEquals(256, DhLodLayout.ARENA_4096.snapBlocks());
        assertEquals(128, DhLodLayout.ARENA_4096.centerAlignmentBlocks());
        for (int size : new int[] {1, 2, 4}) assertEquals(32, DhLodLayout.ARENA_4096.tileSizeForCell(size));
        for (int size : new int[] {8, 16, 32}) assertEquals(512, DhLodLayout.ARENA_4096.tileSizeForCell(size));
        assertTrue(DhLodLayout.ARENA_4096.identity().endsWith(":fine-cell-below=8"));
        for (var legacy : List.of(DhLodLayout.FIXED_512, DhLodLayout.LONG_4096)) {
            assertEquals(32, legacy.tileSizeForCell(8));
            assertFalse(legacy.identity().contains(":fine-cell-below="));
        }
        assertEquals(12288, DhAdaptiveTerrainData.MAX_CELLS);
        assertEquals(32768, DhAdaptiveTerrainData.MAX_FACES);
        assertEquals(4096, DhAdaptiveTerrainData.MAX_PAGE_FACES);
        assertEquals(64, DhAdaptiveTerrainData.MAX_TILES);
        assertTrue(DhLodLayout.ARENA_4096.requiresFootprintCoverage());
        assertNotEquals(DhLodLayout.LONG_4096.identity(), DhLodLayout.ARENA_4096.identity());
    }

    @Test void all1024ProductionTemplatesFitAndEveryPossibleNativeYMaskCannotIncreaseFaceCount() {
        int maxLeaves = 0, minLeaves = Integer.MAX_VALUE, maxFaces = 0, maxOwners = 0, maxPage = 0, maxSource = 0;
        long potentiallyClippedUnitFaces = 0; var centers = new TreeSet<Long>();
        for (int cx = -16; cx <= 15; cx++) for (int cz = -16; cz <= 15; cz++) {
            var data = terrain(cx, cz); var refined = refinement(cx, cz);
            var batch = data.clip(List.of(), refined, List.of(ARENA), () -> {});
            int independent = independentLeafCount(cx, cz);
            assertEquals(independent, batch.sampledCells(), "Independent quadtree arithmetic versus actual production leaves");
            assertEquals(independent, data.partitionCounts(refined).sampledCells());
            assertEquals(batch.sampledCells(), batch.knownCells()); assertEquals(0, batch.unknownCells());
            assertEquals(batch.sampledCells(), batch.fullySupportedCells());
            assertTrue(batch.faces() <= 15887); assertTrue(batch.tiles().size() <= 42);
            assertTrue(batch.sourceFaces() <= 32768);
            int counted = 0;
            for (var entry : batch.tiles().entrySet()) {
                assertEquals(0, entry.getKey().pageY());
                assertTrue(entry.getValue().size() <= 4056);
                counted += entry.getValue().size(); maxPage = Math.max(maxPage, entry.getValue().size());
                for (var face : entry.getValue()) {
                    // Exact X/Z solid-side projection: every possibly owned face is1x1.
                    // Every integral16m cube mask therefore keeps or deletes the whole face;
                    // it cannot split a face or create an owner. The separate modular mask
                    // test additionally guards the count/subset rule for narrow height2 strips.
                    if (projectsIntoNear(face, entry.getKey(), cx, cz)) {
                        assertEquals(1, face.width()); assertEquals(1, face.height());
                        for (var unit : units(face)) {
                            assertExactNativeSourceFace(unit, entry.getKey());
                            potentiallyClippedUnitFaces++;
                        }
                    }
                }
            }
            assertEquals(batch.faces(), counted);
            maxLeaves = Math.max(maxLeaves, batch.sampledCells()); minLeaves = Math.min(minLeaves, batch.sampledCells());
            maxFaces = Math.max(maxFaces, batch.faces()); maxOwners = Math.max(maxOwners, batch.tiles().size());
            maxSource = Math.max(maxSource, batch.sourceFaces()); centers.add(farCenter(cx)); centers.add(farCenter(cz));
        }
        assertEquals(11476, maxLeaves); assertEquals(9976, minLeaves);
        assertEquals(DhArenaAdmission.MAX_BASE_LEAVES, maxLeaves);
        assertEquals(DhArenaAdmission.MAX_BASE_FACES, maxFaces);
        assertEquals(DhArenaAdmission.MAX_BASE_OWNERS, maxOwners);
        assertEquals(17043, maxSource);
        assertEquals(15887, maxFaces); assertEquals(42, maxOwners); assertEquals(4056, maxPage);
        assertTrue(potentiallyClippedUnitFaces > 1_000_000);
        assertEquals(Set.of(-256L, 0L, 256L), centers);
        // Prospective exact arena census may consume at most the remainder, never an unconditional edit promise.
        assertEquals(22, 64 - maxOwners); assertEquals(16881, 32768 - maxFaces);
        assertTrue(2L * (maxFaces + 16881) * 88 <= 8L * 1024 * 1024);
        assertTrue(4096L + 11664L + 128L * (maxFaces + 16881) <= 5L * 1024 * 1024);
        assertTrue(4L * (4096L + 11664L + 128L * (maxFaces + 16881)) <= 20L * 1024 * 1024);
        System.out.println("ARENA_SOURCE_CENSUS templates=1024 minLeaves=" + minLeaves + " maxLeaves=" + maxLeaves
                + " maxSourceFaces=" + maxSource + " maxBaseFaces=" + maxFaces + " maxBaseOwners=" + maxOwners
                + " maxPerOwner=" + maxPage + " pages=[0] unitMaskFaces=" + potentiallyClippedUnitFaces
                + " nativeYMask=any-integral-Y-monotone replacementFaceAllowance=16881 replacementOwnerAllowance=22");
        System.out.println("ARENA_SOURCE_IDENTITY " + SOURCE.identity());
        System.out.println("ARENA_LAYOUT_IDENTITY " + DhLodLayout.ARENA_4096.identity());
    }

    @Test void actualProductionClippingAtEveryAllowedYAndCornerSeamCasesIsAnExactSubset() {
        int cases = 0;
        for (int cx : new int[] {-16, -9, -8, -1, 0, 1, 7, 8, 9, 15})
            for (int cz : new int[] {-16, -1, 0, 1, 15}) {
                var data = terrain(cx, cz); var refined = refinement(cx, cz);
                var base = data.clip(List.of(), refined, List.of(ARENA), () -> {});
                var original = new HashMap<DhGeographicLodData.TileKey, Set<DhTerrainGeometry.PageFace>>();
                var sourceUnits = new HashMap<DhGeographicLodData.TileKey, Set<DhTerrainGeometry.PageFace>>();
                base.tiles().forEach((key, faces) -> {
                    original.put(key, new HashSet<>(faces));
                    var set = new HashSet<DhTerrainGeometry.PageFace>();
                    for (var face : faces) if (projectsIntoNear(face, key, cx, cz)) set.addAll(units(face));
                    sourceUnits.put(key, set);
                });
                for (int cy = 6; cy <= 15; cy++) {
                    var ready = new ArrayList<CubePos>();
                    for (int y = cy - 1; y <= cy + 1; y++) for (int z = cz - 1; z <= cz + 1; z++)
                        for (int x = cx - 1; x <= cx + 1; x++) ready.add(new CubePos(x, y, z));
                    var clipped = data.clip(ready, refined, List.of(ARENA), () -> {});
                    assertTrue(clipped.faces() <= base.faces()); assertTrue(clipped.tiles().size() <= base.tiles().size());
                    clipped.tiles().forEach((key, faces) -> {
                        assertTrue(original.containsKey(key), "No invented owner");
                        assertTrue(faces.size() <= base.tiles().get(key).size(), "Native clipping cannot increase page count");
                        assertTrue(original.get(key).containsAll(faces), "Final unit-step source preserves whole face identity");
                        for (var face : faces) if (!original.get(key).contains(face)) {
                            assertEquals(1, face.width()); assertTrue(face.height() <= 2);
                            assertTrue(sourceUnits.get(key).containsAll(units(face)), "Every shortened face is an exact source subset");
                        }
                    });
                    cases++;
                }
            }
        assertEquals(500, cases);
        System.out.println("ARENA_SOURCE_Y_MASK actualProductionCases=" + cases + " centerY=[6,15] exactSubset=true");
    }

    private static List<DhTerrainGeometry.PageFace> units(DhTerrainGeometry.PageFace face) {
        if (face.direction() == DhTerrainGeometry.Direction.UP || face.direction() == DhTerrainGeometry.Direction.DOWN)
            return List.of(face);
        var result = new ArrayList<DhTerrainGeometry.PageFace>();
        for (int y = 0; y < face.height(); y++) result.add(new DhTerrainGeometry.PageFace(face.direction(),
                face.pageY2048(), face.x(), face.y() + y, face.z(), face.width(), 1, face.argb()));
        return result;
    }

    private static boolean projectsIntoNear(DhTerrainGeometry.PageFace f, DhGeographicLodData.TileKey key, int cx, int cz) {
        long x = key.originX() + f.x(), z = key.originZ() + f.z();
        long x0 = cx * 16L - 16, x1 = cx * 16L + 32, z0 = cz * 16L - 16, z1 = cz * 16L + 32;
        return switch (f.direction()) {
            case UP, DOWN -> x < x1 && x + f.width() > x0 && z < z1 && z + f.height() > z0;
            case EAST -> x0 < x && x <= x1 && z < z1 && z + f.width() > z0;
            case WEST -> x0 <= x && x < x1 && z < z1 && z + f.width() > z0;
            case SOUTH -> z0 < z && z <= z1 && x < x1 && x + f.width() > x0;
            case NORTH -> z0 <= z && z < z1 && x < x1 && x + f.width() > x0;
        };
    }

    private static void assertExactNativeSourceFace(DhTerrainGeometry.PageFace f, DhGeographicLodData.TileKey key) {
        long x = key.originX() + f.x(), y = f.pageY2048() * 2048L + f.y(), z = key.originZ() + f.z();
        int dx = 0, dy = 0, dz = 0;
        switch (f.direction()) {
            case UP -> { y--; dy = 1; }
            case DOWN -> dy = -1;
            case EAST -> { x--; dx = 1; }
            case WEST -> dx = -1;
            case SOUTH -> { z--; dz = 1; }
            case NORTH -> dz = -1;
        }
        assertFalse(ARENA.contains(x, y, z), "The far base never owns the exact arena's solid voxels");
        int material = SOURCE.materialAt(x, y, z);
        assertNotEquals(ArenaTerrainSource.AIR, material);
        assertEquals(ArenaTerrainSource.AIR, SOURCE.materialAt(x + dx, y + dy, z + dz));
        int color = ArenaTerrainSource.argb(material);
        if (dx != 0 || dz != 0) {
            float factor = dx != 0 ? 0.72f : 0.85f;
            color = 0xff000000 | Math.round(((color >>> 16) & 255) * factor) << 16
                    | Math.round(((color >>> 8) & 255) * factor) << 8 | Math.round((color & 255) * factor);
        }
        assertEquals(color, f.argb(), "Far unit face retains the exact native material color and established directional shading");
    }

    private static int independentLeafCount(int cx, int cz) {
        int count = 768 + 3840; // 1024m/64m and 4096m/128m rings cannot meet either refinement
        long fx = farCenter(cx), fz = farCenter(cz);
        // Independent grid: 8m inside128,16m inside256,32m inside512, excluding inner squares.
        for (int[] ring : new int[][] {{128, 8, 0}, {256, 16, 128}, {512, 32, 256}}) {
            int radius = ring[0], size = ring[1], inner = ring[2];
            for (long z = fz - radius; z < fz + radius; z += size)
                for (long x = fx - radius; x < fx + radius; x += size) {
                    if (inner != 0 && x >= fx - inner && x < fx + inner && z >= fz - inner && z < fz + inner) continue;
                    count += descendants(x, z, size, cx * 16L - 17, cz * 16L - 17, cx * 16L + 33, cz * 16L + 33);
                }
        }
        return count;
    }
    private static int descendants(long x, long z, int size, long x0, long z0, long x1, long z1) {
        boolean touchesNear = x < x1 && x + size > x0 && z < z1 && z + size > z0;
        boolean touchesArena = x < 33 && x + size > -1 && z < 33 && z + size > -1;
        if (size == 1 || (!touchesNear && !touchesArena)) return 1;
        int half = size / 2;
        return descendants(x, z, half, x0, z0, x1, z1) + descendants(x + half, z, half, x0, z0, x1, z1)
                + descendants(x, z + half, half, x0, z0, x1, z1) + descendants(x + half, z + half, half, x0, z0, x1, z1);
    }
}
