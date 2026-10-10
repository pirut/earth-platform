package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongBinaryOperator;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

class DhAdaptiveTerrainDataTest {
    private static SceneSurfaceSampler source(LongBinaryOperator height) {
        return new SceneSurfaceSampler() {
            public long surfaceY(long x, long z) { return height.applyAsLong(x, z); }
            public int argb(long x, long z, long y) { return 0xffbbccdd; }
            public String identity() { return "adaptive-oracle-fixed-v1"; }
        };
    }

    @Test void emptyReadyIsExactlyTheOldCoarseGeometryWithoutConstructorReads() {
        AtomicInteger reads = new AtomicInteger();
        var sampler = source((x, z) -> { reads.incrementAndGet(); return 1000; });
        var adaptive = new DhAdaptiveTerrainData(sampler, 20_037_505, -20_037_505);
        assertEquals(0, reads.get());
        var batch = adaptive.clip(List.of());
        assertEquals(4096, reads.get());
        assertEquals(4096, batch.sampledCells());
        assertEquals(4096, batch.knownCells());
        assertEquals(0, batch.unknownCells());
        assertEquals(0, batch.refinedCells());
        assertEquals(0, batch.transitionFaces());
        assertEquals(4096, batch.sourceFaces());
        assertEquals(4096, batch.faces());
        var coarse = new DhGeographicLodData(source((x, z) -> 1000), 20_037_505, -20_037_505).clip(List.of());
        assertEquals(coarse.tiles(), batch.tiles());
        assertThrows(UnsupportedOperationException.class, () -> batch.tiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> batch.tiles().values().iterator().next().clear());
    }

    @Test void exactNativeMetreHaloAndAllGradedLeafSizesArePresent() {
        long bx = -272, bz = 240;
        var height = (LongBinaryOperator) (x, z) -> -2049 + Math.floorDiv(5 * (x - bx) + 3 * (z - bz), 4);
        var cube = CubePos.fromBlock(bx, -2049, bz);
        var batch = new DhAdaptiveTerrainData(source(height), bx, bz).clip(List.of(), List.of(cube));
        var tops = nativeFaces(batch).stream().filter(f -> f.direction() == UP).toList();
        assertEquals(Set.of(1, 2, 4, 8, 16), tops.stream().map(DhTerrainGeometry.Face::width).collect(java.util.stream.Collectors.toSet()));
        for (long z = bz - 1; z < bz + 17; z++) for (long x = bx - 1; x < bx + 17; x++) {
            long columnX = x, columnZ = z;
            var matches = tops.stream().filter(f -> f.x() <= columnX && columnX < f.x() + f.width()
                    && f.z() <= columnZ && columnZ < f.z() + f.height()).toList();
            assertEquals(1, matches.size(), "exactly one top at " + x + "," + z);
            var face = matches.getFirst();
            assertEquals(1, face.width());
            assertEquals(1, face.height());
            assertEquals(height.applyAsLong(x, z) + 1, face.y());
        }
        assertTrue(batch.transitionFaces() > 0);
    }

    @Test void independentUnitColumnOracleClosesFineCoarseEdgesAtArbitraryNativeAndPageBoundaries() {
        for (long[] anchor : List.of(new long[]{16, -16}, new long[]{-272, 240}, new long[]{256, -256})) {
            long bx = anchor[0], bz = anchor[1];
            LongBinaryOperator height = (x, z) -> -2049 + Math.floorDiv(5 * (x - bx) + 3 * (z - bz), 4);
            var ready = List.of(CubePos.fromBlock(bx, -2049, bz), CubePos.fromBlock(bx, -2033, bz),
                    CubePos.fromBlock(bx - 32, -2081, bz + 32));
            var batch = new DhAdaptiveTerrainData(source(height), bx + 7, bz + 9).clip(ready);
            assertOracle(batch, ready, ready, height, bx - 48, bz - 48, 112);
            assertTrue(batch.clippedSourceFaces() > 0);
            assertTrue(batch.transitionFaces() > 0);
            assertTrue(batch.tiles().keySet().stream().anyMatch(k -> k.pageY() == -2));
            assertTrue(batch.tiles().keySet().stream().anyMatch(k -> k.pageY() == -1));
            for (var faces : batch.tiles().values()) for (var f : faces) {
                assertTrue(f.x() >= 0 && f.x() <= 256);
                assertTrue(f.z() >= 0 && f.z() <= 256);
                assertTrue(f.y() >= 0 && f.y() <= 2048);
                assertTrue(f.direction() == UP || f.y() + f.height() <= 2048);
            }
        }
    }

    @Test void wrongYRefinesButNeverSuppressesAndReadyOrderDoesNotChangeOutput() {
        var a = new CubePos(-1, 553, 0);
        var b = new CubePos(0, 553, -1);
        var data = new DhAdaptiveTerrainData(source((x, z) -> 1000 + Math.floorDiv(x + z, 4)), 0, 0);
        var wrong = data.clip(List.of(a, b));
        assertEquals(0, wrong.clippedSourceFaces());
        assertEquals(data.clip(List.of(), List.of(a, b)).tiles(), wrong.tiles());
        assertEquals(wrong, data.clip(List.of(b, a, a)));
        assertTrue(wrong.refinedCells() > 0);
    }

    @Test void surfaceRisingOutOfReadyYSlabKeepsExactFarFacesAndJoinsNativeHaloCulling() {
        LongBinaryOperator height = (x, z) -> 3 * x + 2 * z - 24;
        var ready = List.of(new CubePos(0, 0, 0));
        var data = new DhAdaptiveTerrainData(source(height), 0, 0);
        var clipped = data.clip(ready);
        var complete = data.clip(List.of(), ready);
        assertOracle(clipped, ready, ready, height, -1, -1, 18);
        var far = raster(clipped, -1, -1, 18);
        var whole = raster(complete, -1, -1, 18);
        // Independently mesh the actual ready 16^3 block volume, checking each
        // neighbor against the known native-height halo even outside this cube.
        var nativeMesh = new HashSet<Unit>();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
            if (y > height.applyAsLong(x, z)) continue;
            if (y + 1 > height.applyAsLong(x, z)) nativeMesh.add(new Unit(UP, x, y + 1, z));
            if (y > height.applyAsLong(x - 1, z)) nativeMesh.add(new Unit(WEST, x, y, z));
            if (y > height.applyAsLong(x + 1, z)) nativeMesh.add(new Unit(EAST, x + 1, y, z));
            if (y > height.applyAsLong(x, z - 1)) nativeMesh.add(new Unit(NORTH, x, y, z));
            if (y > height.applyAsLong(x, z + 1)) nativeMesh.add(new Unit(SOUTH, x, y, z + 1));
        }
        assertFalse(nativeMesh.isEmpty());
        assertTrue(java.util.Collections.disjoint(nativeMesh, far), "No near/far duplicate unit faces");
        var combined = new HashSet<>(far); combined.addAll(nativeMesh);
        assertEquals(whole, combined, "Known-halo native culling plus far mesh is a closed exact surface");
        assertTrue(far.contains(new Unit(UP, 0, -23, 0)), "Below-slab far top must survive");
        assertTrue(far.contains(new Unit(UP, 15, 22, 0)), "Above-slab far top must survive");
        assertFalse(far.contains(new Unit(UP, 8, 1, 0)), "Only ready slab-owned top is suppressed");
        assertTrue(far.stream().anyMatch(f -> f.direction == WEST && f.x == 15 && f.y >= 16),
                "Above-slab side wall must survive even within ready XZ footprint");
    }

    @Test void unknownMetreCoverageHasNeitherTopsNorInventedWallsAndMatchesOracle() {
        LongBinaryOperator height = (x, z) -> {
            if (x >= 7) throw new MissingTerrainException("unknown fine columns");
            return 2047 + Math.floorDiv(x + 2 * z, 4);
        };
        var ready = List.of(new CubePos(0, 127, 0));
        var batch = new DhAdaptiveTerrainData(source(height), 0, 0).clip(ready);
        assertTrue(batch.unknownCells() > 0);
        assertEquals(batch.sampledCells(), batch.knownCells() + batch.unknownCells());
        assertOracle(batch, ready, ready, height, -48, -48, 112);
        assertTrue(nativeFaces(batch).stream().noneMatch(f -> (f.direction() == EAST || f.direction() == WEST) && f.x() == 7));
    }

    @Test void retainedProfileWithEmptyMaskRestoresEveryTopAndCountsOnlyCurrentBatch() {
        AtomicInteger reads = new AtomicInteger();
        var data = new DhAdaptiveTerrainData(source((x, z) -> { reads.incrementAndGet(); return 1000; }), 0, 0);
        var profile = List.of(new CubePos(0, 62, 0));
        var clipped = data.clip(profile);
        int firstReads = reads.get();
        var retained = data.clip(List.of(), profile);
        assertEquals(firstReads, clipped.sampledCells());
        assertEquals(retained.sampledCells(), reads.get() - firstReads);
        assertEquals(clipped.sampledCells(), retained.sampledCells());
        assertEquals(clipped.refinedCells(), retained.refinedCells());
        assertEquals(0, retained.clippedSourceFaces());
        assertEquals(256, retained.faces() - clipped.faces());
        assertEquals(1024L * 1024, nativeFaces(retained).stream().filter(f -> f.direction() == UP)
                .mapToLong(f -> (long) f.width() * f.height()).sum());
        var coarse = data.clip(List.of());
        assertEquals(4096, coarse.sampledCells());
        assertEquals(0, coarse.refinedCells());
        assertEquals(4096, coarse.faces());
        assertTrue(retained.sampledCells() > coarse.sampledCells());
    }

    @Test void denseRetainedProfileUsesBoundedMicrotileOwnersInsteadOfOverflowingOnePage() {
        // Nine near columns wholly inside a single 256m tile. Keeping its empty
        // mask must restore the full fine surface, not exceed the 4096 face cap.
        var profile = new ArrayList<CubePos>();
        for (int z = 2; z <= 4; z++) for (int x = 2; x <= 4; x++) profile.add(new CubePos(x, 62, z));
        var data = new DhAdaptiveTerrainData(source((x, z) -> 1000 + x + z), 48, 48);
        var batch = data.clip(List.of(), profile);
        assertTrue(batch.faces() > 12000);
        assertTrue(batch.tiles().keySet().stream().anyMatch(k -> Math.floorMod(k.originX(), 256) != 0));
        assertTrue(batch.tiles().size() <= DhAdaptiveTerrainData.MAX_TILES);
        assertTrue(batch.tiles().values().stream().allMatch(f -> f.size() <= DhAdaptiveTerrainData.MAX_PAGE_FACES));
        assertOracle(batch, List.of(), profile, (x, z) -> 1000 + x + z, 0, 0, 112);
    }

    @Test void overBudgetPartitionAndInvalidProfilesFailBeforeAnyProviderRead() {
        AtomicInteger reads = new AtomicInteger();
        var data = new DhAdaptiveTerrainData(source((x, z) -> { reads.incrementAndGet(); return 1000; }), 0, 0);
        var tooDense = new ArrayList<CubePos>();
        for (int z = -8; z < 8; z++) for (int x = -8; x < 8; x++) tooDense.add(new CubePos(x, 62, z));
        var failure = assertThrows(IllegalArgumentException.class, () -> data.clip(List.of(), tooDense));
        assertTrue(failure.getMessage().contains("before sampling"));
        assertEquals(0, reads.get());
        assertThrows(IllegalArgumentException.class, () -> data.clip(List.of(new CubePos(0, 554, 0))));
        assertEquals(0, reads.get());
        tooDense.add(new CubePos(100, 62, 100));
        assertThrows(IllegalArgumentException.class, () -> data.clip(tooDense));
        assertEquals(0, reads.get());
        var valid = data.clip(List.of());
        assertEquals(4096, valid.sampledCells());
        assertEquals(4096, reads.get());
    }

    @Test void identityChangesUnknownOnlyAndOutOfEnvelopeFailClosed() {
        AtomicInteger reads = new AtomicInteger();
        var unstable = new SceneSurfaceSampler() {
            public long surfaceY(long x, long z) { reads.incrementAndGet(); return 1000; }
            public int argb(long x, long z, long y) { return -1; }
            public String identity() { return reads.get() == 0 ? "old" : "new"; }
        };
        var data = new DhAdaptiveTerrainData(unstable, 0, 0);
        assertThrows(IllegalStateException.class, () -> data.clip(List.of()));
        int previous = reads.get();
        assertThrows(IllegalStateException.class, () -> data.clip(List.of()));
        assertEquals(previous, reads.get());
        assertThrows(IllegalArgumentException.class, () -> new DhAdaptiveTerrainData(source((x, z) -> {
            throw new MissingTerrainException("unknown");
        }), 0, 0).clip(List.of()));
        for (long invalid : new long[]{-11009, 8864})
            assertThrows(IllegalArgumentException.class, () -> new DhAdaptiveTerrainData(source((x, z) -> invalid), 0, 0).clip(List.of()));
    }

    @Test void sourceAndPageBudgetsRejectRatherThanTruncate() {
        // A tall alternating step splits into many signed pages and exceeds the
        // owner/page budgets, even though its 4096 direct samples are bounded.
        var hugeSteps = new DhAdaptiveTerrainData(source((x, z) -> Math.floorMod(Math.floorDiv(x, 16), 2) == 0 ? -11000 : 8849), 0, 0);
        assertThrows(IllegalArgumentException.class, () -> hugeSteps.clip(List.of()));
    }

    private record Unit(DhTerrainGeometry.Direction direction, long x, long y, long z) { }

    private static List<DhTerrainGeometry.Face> nativeFaces(DhAdaptiveTerrainData.Batch batch) {
        var result = new ArrayList<DhTerrainGeometry.Face>();
        batch.tiles().forEach((owner, faces) -> faces.forEach(f -> result.add(new DhTerrainGeometry.Face(f.direction(),
                owner.originX() + f.x(), (long) f.pageY2048() * 2048 + f.y(), owner.originZ() + f.z(),
                f.width(), f.height(), f.argb()))));
        return result;
    }

    /** Independent per-column lookup, no production partition/edge/clip helpers. */
    private static long oracleTop(long x, long z, List<CubePos> refinement, LongBinaryOperator height) {
        int size = 16;
        while (size > 1) {
            long cellX = Math.floorDiv(x, size) * size, cellZ = Math.floorDiv(z, size) * size;
            boolean overlap = false;
            for (CubePos cube : refinement) {
                if (cellX <= cube.minBlockX() + 16 && cellX + size > cube.minBlockX() - 1
                        && cellZ <= cube.minBlockZ() + 16 && cellZ + size > cube.minBlockZ() - 1) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) break;
            size /= 2;
        }
        long sampleX = Math.floorDiv(x, size) * size + size / 2;
        long sampleZ = Math.floorDiv(z, size) * size + size / 2;
        try { return height.applyAsLong(sampleX, sampleZ) + 1; }
        catch (MissingTerrainException absent) { return Long.MIN_VALUE; }
    }

    private static boolean owned(Unit face, List<CubePos> ready) {
        long x = face.x - (face.direction == EAST ? 1 : 0);
        long y = face.y - (face.direction == UP ? 1 : 0);
        long z = face.z - (face.direction == SOUTH ? 1 : 0);
        return ready.stream().anyMatch(cube -> x >= cube.minBlockX() && x < cube.minBlockX() + 16
                && y >= cube.minBlockY() && y < cube.minBlockY() + 16
                && z >= cube.minBlockZ() && z < cube.minBlockZ() + 16);
    }

    private static void assertOracle(DhAdaptiveTerrainData.Batch batch, List<CubePos> ready,
                                     List<CubePos> refinement, LongBinaryOperator height,
                                     long x0, long z0, int width) {
        var expected = new HashSet<Unit>();
        long[][] tops = new long[width][width];
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++)
            tops[z][x] = oracleTop(x0 + x, z0 + z, refinement, height);
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            long top = tops[z][x];
            if (top == Long.MIN_VALUE) continue;
            expected.add(new Unit(UP, x0 + x, top, z0 + z));
            if (x > 0 && tops[z][x - 1] != Long.MIN_VALUE) {
                long west = tops[z][x - 1];
                for (long y = Math.min(west, top); y < Math.max(west, top); y++)
                    expected.add(new Unit(west > top ? EAST : WEST, x0 + x, y, z0 + z));
            }
            if (z > 0 && tops[z - 1][x] != Long.MIN_VALUE) {
                long north = tops[z - 1][x];
                for (long y = Math.min(north, top); y < Math.max(north, top); y++)
                    expected.add(new Unit(north > top ? SOUTH : NORTH, x0 + x, y, z0 + z));
            }
        }
        expected.removeIf(face -> owned(face, ready));
        var actual = raster(batch, x0, z0, width);
        var missing = new HashSet<>(expected); missing.removeAll(actual);
        var extra = new HashSet<>(actual); extra.removeAll(expected);
        assertTrue(missing.isEmpty(), () -> "Unclosed/missing unit faces: " + missing.stream().limit(8).toList());
        assertTrue(extra.isEmpty(), () -> "Invented/overlapping unit faces: " + extra.stream().limit(8).toList());
    }

    private static Set<Unit> raster(DhAdaptiveTerrainData.Batch batch, long x0, long z0, int width) {
        var actual = new HashSet<Unit>();
        for (var face : nativeFaces(batch)) {
            if (face.direction() == UP) {
                for (long z = Math.max(z0, face.z()); z < Math.min(z0 + width, face.z() + face.height()); z++)
                    for (long x = Math.max(x0, face.x()); x < Math.min(x0 + width, face.x() + face.width()); x++)
                        assertTrue(actual.add(new Unit(UP, x, face.y(), z)), "duplicate top");
            } else if (face.direction() == EAST || face.direction() == WEST) {
                if (face.x() <= x0 || face.x() >= x0 + width) continue;
                for (long z = Math.max(z0, face.z()); z < Math.min(z0 + width, face.z() + face.width()); z++)
                    for (long y = face.y(); y < face.y() + face.height(); y++)
                        assertTrue(actual.add(new Unit(face.direction(), face.x(), y, z)), "duplicate X wall");
            } else if (face.direction() == SOUTH || face.direction() == NORTH) {
                if (face.z() <= z0 || face.z() >= z0 + width) continue;
                for (long x = Math.max(x0, face.x()); x < Math.min(x0 + width, face.x() + face.width()); x++)
                    for (long y = face.y(); y < face.y() + face.height(); y++)
                        assertTrue(actual.add(new Unit(face.direction(), x, y, face.z())), "duplicate Z wall");
            } else fail("Unexpected bottom face");
        }
        return actual;
    }
}
