package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongBinaryOperator;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/** Complete square coverage, variable-detail seams and authoritative 3D subtraction; no GL. */
class DhLongDistanceGeometryTest {
    static final long AREA = 8192L * 8192;
    static SceneSurfaceSampler source(LongBinaryOperator height) {
        return new SceneSurfaceSampler() {
            public String identity() { return "long-geometry-oracle-v1"; }
            public long surfaceY(long x, long z) { return height.applyAsLong(x, z); }
            public int argb(long x, long z, long y) { return 0xffbbccdd; }
            public boolean supportsFootprintCoverage() { return true; }
            public boolean knownFootprint(long x, long z, long maxX, long maxZ) { return true; }
        };
    }
    static DhAdaptiveTerrainData data(SceneSurfaceSampler source, long x, long z) {
        return new DhAdaptiveTerrainData(source, x, z, DhLodLayout.LONG_4096);
    }
    static List<CubePos> nativeWindow(long x, long y, long z) {
        CubePos at = CubePos.fromBlock(x, y, z);
        int centerY = Math.max(-687, Math.min(552, at.y()));
        var cubes = new ArrayList<CubePos>();
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            cubes.add(new CubePos(at.x() + dx, centerY + dy, at.z() + dz));
        return cubes;
    }
    static List<DhTerrainGeometry.Face> nativeFaces(DhAdaptiveTerrainData.Batch batch) {
        var faces = new ArrayList<DhTerrainGeometry.Face>();
        batch.tiles().forEach((key, values) -> values.forEach(f -> faces.add(new DhTerrainGeometry.Face(f.direction(),
                key.originX() + f.x(), f.pageY2048() * 2048L + f.y(), key.originZ() + f.z(), f.width(), f.height(), f.argb()))));
        return faces;
    }
    static long topArea(DhAdaptiveTerrainData.Batch batch) {
        return nativeFaces(batch).stream().filter(f -> f.direction() == UP).mapToLong(f -> (long) f.width() * f.height()).sum();
    }
    static void bounded(DhAdaptiveTerrainData.Batch batch) {
        assertTrue(batch.sampledCells() <= 12288);
        assertTrue(batch.sourceFaces() <= 32768);
        assertTrue(batch.faces() <= 32768);
        assertTrue(batch.tiles().size() <= 64);
        assertTrue(batch.tiles().values().stream().allMatch(f -> f.size() <= 4096));
        assertEquals(batch.sampledCells(), batch.knownCells() + batch.unknownCells());
    }

    @Test void immutableCommonLayoutBindsDetailFullNativeExtentAndCheckedNegativeAlignment() {
        var small = DhLodLayout.FIXED_512;
        var large = DhLodLayout.LONG_4096;
        assertEquals(512, small.radiusBlocks()); assertEquals(64, small.snapBlocks());
        assertEquals(16, small.centerAlignmentBlocks()); assertEquals(16, small.outerCellSize());
        assertEquals(4096, large.radiusBlocks()); assertEquals(256, large.snapBlocks());
        assertEquals(128, large.centerAlignmentBlocks()); assertEquals(128, large.outerCellSize());
        assertEquals(-128, large.normalizeCenter(-1)); assertEquals(-256, large.normalizeCenter(-129));
        assertEquals(0, large.normalizeCenter(127)); assertEquals(128, large.normalizeCenter(128));
        assertEquals(-11008, large.minNativeY()); assertEquals(8864, large.maxNativeYExclusive());
        assertNotEquals(small.identity(), large.identity());
        assertTrue(large.identity().contains("full-xyz-clip=true"));
        assertTrue(large.identity().contains("complete-footprint-required=true"));
        assertThrows(UnsupportedOperationException.class, () -> large.rings().clear());
        for (long invalid : new long[]{Long.MIN_VALUE, Long.MAX_VALUE})
            assertThrows(ArithmeticException.class, () -> large.normalizeCenter(invalid));
        assertThrows(IllegalArgumentException.class, () -> large.tileSizeForCell(3));
        assertThrows(IllegalArgumentException.class, () -> large.tileSizeForCell(256));
    }

    @Test void legacyDefaultRetainsFrozen003BatchBytesAtPositiveNegativeAndDistantAnchors() throws Exception {
        // SHA-256 of complete ordered Batch.toString() from the frozen003 implementation,
        // including every face, tile, clipping result, sampling count and publication order.
        String[] expected = {"1eafcb14623084404e3c0b80e8ec09375749a41d084eab595af78915a25c6988",
                "6eceea0da443d3f1b0aec290aec22add62d18276f34f186f2221078db1edf25b",
                "5e561035df13c7b549d194801a9ff5205fa5023720d902b08a9f5f7044974568",
                "bca88933838b71a7b933b1f4ae568f8cb7c9452fbba93c1970812487bd5211fc",
                "8e54043b3036044b01d3593c81b3d4d7b88fbdca3552e53f0b622d5360f4a7d6",
                "776966b7b61154b552306e031327fc31825617ea2c4e3e039cb1023245d8d938"};
        int index = 0;
        for (long[] center : List.of(new long[]{0, 0}, new long[]{-272, 240}, new long[]{20_037_505, -20_037_505})) {
            SceneSurfaceSampler oldSource = new SceneSurfaceSampler() {
                public String identity() { return "legacy-fixed-v1"; }
                public long surfaceY(long x, long z) { return 1000 + Math.floorMod(Math.floorDiv(x + z, 4), 32); }
                public int argb(long x, long z, long y) { return 0xffbbccdd; }
            };
            for (boolean refine : new boolean[]{false, true}) {
                var mask = refine ? List.of(CubePos.fromBlock(center[0], 1000, center[1])) : List.<CubePos>of();
                var batch = new DhAdaptiveTerrainData(oldSource, center[0], center[1]).clip(mask);
                assertEquals(batch, new DhAdaptiveTerrainData(oldSource, center[0], center[1], DhLodLayout.FIXED_512).clip(mask));
                assertEquals(expected[index++], HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(batch.toString().getBytes(StandardCharsets.UTF_8))));
            }
        }
    }

    @Test void complete8192SquareHasExactly8704BaseLeavesAndAllThreeResolutions() {
        AtomicInteger reads = new AtomicInteger();
        var data = data(source((x, z) -> { reads.incrementAndGet(); return 1000; }), -1, 255);
        assertEquals(0, reads.get());
        var batch = data.clip(List.of());
        assertEquals(8704, batch.sampledCells()); assertEquals(8704, batch.fullySupportedCells());
        assertEquals(8704, reads.get()); assertEquals(AREA, topArea(batch));
        assertEquals(-4224, data.originX()); assertEquals(-3968, data.originZ());
        var widths = new TreeMap<Integer, Integer>();
        for (var f : nativeFaces(batch)) {
            assertEquals(UP, f.direction()); assertEquals(f.width(), f.height());
            assertTrue(f.x() >= data.originX() && f.x() + f.width() <= data.originX() + 8192);
            assertTrue(f.z() >= data.originZ() && f.z() + f.height() <= data.originZ() + 8192);
            widths.merge(f.width(), 1, Integer::sum);
        }
        assertEquals(Map.of(16, 4096, 64, 768, 128, 3840), widths);
        assertWholeSquare(batch, data.originX(), data.originZ());
        assertEquals(DhLodLayout.LONG_4096, data.layout());
        bounded(batch);
    }

    @Test void offCenter27AndRemotePin28StayBoundedAtNativeEndpointsAndWorldNegativeCoordinates() {
        int peakOwners = 0, peakLeaves = 0;
        for (long[] center : List.of(new long[]{0, 0}, new long[]{128, -128}, new long[]{20_037_504, -20_037_632}))
            for (int offset : new int[]{-128, -16, 0, 112}) for (int pin : new int[]{320, 512, 1024, 1152, 3968})
                for (long y : new long[]{-11000, 8849}) {
                    long cx = center[0], cz = center[1];
                    var cubes = nativeWindow(cx + offset, y, cz - offset);
                    assertEquals(27, cubes.size());
                    cubes.add(CubePos.fromBlock(cx + pin, y, cz + pin));
                    assertEquals(28, new HashSet<>(cubes).size());
                    var batch = data(source((x, z) -> y), cx, cz).clip(cubes);
                    bounded(batch); assertEquals(AREA - 10L * 256, topArea(batch));
                    assertTrue(batch.sampledCells() <= 12106);
                    assertEquals(0, batch.unknownCells()); assertEquals(batch.knownCells(), batch.fullySupportedCells());
                    for (var face : nativeFaces(batch)) {
                        assertEquals(y + 1, face.y());
                        assertEquals(UP, face.direction());
                    }
                    peakLeaves = Math.max(peakLeaves, batch.sampledCells());
                    peakOwners = Math.max(peakOwners, batch.tiles().size());
                }
        assertEquals(12106, peakLeaves);
        assertTrue(peakOwners <= 64);
    }

    @Test void exactOneMetreCollarAlsoRefinesAcrossRingSeamsWithoutSuppressingWrongY() {
        LongBinaryOperator height = (x, z) -> 1000 + Math.floorDiv(x + 3 * z, 128);
        for (long[] pos : List.of(new long[]{512, 0}, new long[]{-512, 512}, new long[]{1024, -1024}, new long[]{-1024, 0})) {
            var cube = CubePos.fromBlock(pos[0], 8849, pos[1]);
            var data = data(source(height), 0, 0);
            var batch = data.clip(List.of(cube));
            assertEquals(0, batch.clippedSourceFaces());
            assertEquals(batch, data.clip(List.of(), List.of(cube)));
            var tops = nativeFaces(batch).stream().filter(f -> f.direction() == UP).toList();
            for (long z = pos[1] - 1; z < pos[1] + 17; z++) for (long x = pos[0] - 1; x < pos[0] + 17; x++) {
                long xx = x, zz = z;
                var matches = tops.stream().filter(f -> f.x() <= xx && xx < f.x() + f.width()
                        && f.z() <= zz && zz < f.z() + f.height()).toList();
                assertEquals(1, matches.size()); assertEquals(1, matches.getFirst().width());
                assertEquals(1, matches.getFirst().height()); assertEquals(height.applyAsLong(x, z) + 1, matches.getFirst().y());
            }
            assertTrue(batch.transitionFaces() > 0); bounded(batch);
            assertOracle(batch, List.of(cube), List.of(cube), height, pos[0] - 48, pos[1] - 48, 112);
        }
    }

    @Test void independentUnitOracleClosesEverySignedRingEdgeAndCorner() {
        LongBinaryOperator height = (x, z) -> 1000 + Math.floorDiv(3 * x + z, 128);
        var batch = data(source(height), 0, 0).clip(List.of());
        for (long[] edge : List.of(new long[]{512, 0}, new long[]{-512, 0}, new long[]{0, 512}, new long[]{0, -512},
                new long[]{512, 512}, new long[]{-512, -512}, new long[]{1024, 0}, new long[]{-1024, 0},
                new long[]{0, 1024}, new long[]{0, -1024}, new long[]{1024, 1024}, new long[]{-1024, -1024}))
            assertOracle(batch, List.of(), List.of(), height, edge[0] - 64, edge[1] - 64, 128);
        assertTrue(batch.transitionFaces() > 0); bounded(batch);
    }

    @Test void authoritativeFull3DClippingKeepsSurfaceAboveAndBelowNearSlab() {
        LongBinaryOperator height = (x, z) -> Math.max(-1000, Math.min(1000, 3 * x + 2 * z - 24));
        var ready = List.of(new CubePos(0, 0, 0));
        var batch = data(source(height), 0, 0).clip(ready);
        assertOracle(batch, ready, ready, height, -1, -1, 18);
        var units = raster(batch, -1, -1, 18);
        assertTrue(units.contains(new Unit(UP, 0, -23, 0)));
        assertTrue(units.contains(new Unit(UP, 15, 22, 0)));
        assertFalse(units.contains(new Unit(UP, 8, 1, 0)));
        assertTrue(units.stream().anyMatch(f -> f.direction == WEST && f.x == 15 && f.y >= 16));
        var reordered = List.of(ready.getFirst(), ready.getFirst());
        assertEquals(batch, data(source(height), 0, 0).clip(reordered));
    }

    @Test void footprintSupportOmissionNeverInventsBoundaryWallsOrReadsKnownCentersOutsideWholeSupport() {
        AtomicInteger support = new AtomicInteger(), reads = new AtomicInteger();
        SceneSurfaceSampler source = new SceneSurfaceSampler() {
            public String identity() { return "bounded-raster-support"; }
            public boolean supportsFootprintCoverage() { return true; }
            public boolean knownFootprint(long x, long z, long maxX, long maxZ) {
                support.incrementAndGet(); return x >= -1000 && maxX <= 1000 && z >= -1000 && maxZ <= 1000;
            }
            public long surfaceY(long x, long z) { reads.incrementAndGet(); return 1000; }
            public int argb(long x, long z, long y) { return -1; }
        };
        var batch = data(source, 0, 0).clip(List.of());
        assertEquals(8704, support.get()); assertEquals(batch.knownCells(), reads.get());
        assertEquals(batch.unknownCells(), batch.footprintRejectedCells());
        assertEquals(batch.knownCells(), batch.fullySupportedCells());
        assertTrue(batch.unknownCells() > 0); assertTrue(topArea(batch) < 4_000_000L);
        for (var f : nativeFaces(batch)) {
            assertEquals(UP, f.direction()); assertTrue(f.x() >= -1000 && f.x() + f.width() <= 1000);
            assertTrue(f.z() >= -1000 && f.z() + f.height() <= 1000);
        }
    }

    @Test void missingFineCellsRemainUnknownWithoutSkirtsAndExact3DOracleAgrees() {
        LongBinaryOperator height = (x, z) -> {
            if (x >= 7) throw new MissingTerrainException("unknown fine strip");
            return 1000 + Math.floorDiv(x + 2 * z, 128);
        };
        var ready = List.of(new CubePos(0, 62, 0));
        var batch = data(source(height), 0, 0).clip(ready);
        assertTrue(batch.unknownCells() > 0);
        assertOracle(batch, ready, ready, height, -48, -48, 112);
        assertTrue(nativeFaces(batch).stream().noneMatch(f -> (f.direction() == EAST || f.direction() == WEST) && f.x() == 7));
    }

    @Test void unsupportedSourcePartitionAndInvalidReadinessFailBeforeProviderReads() {
        AtomicInteger reads = new AtomicInteger();
        SceneSurfaceSampler unsupported = new SceneSurfaceSampler() {
            public String identity() { return "sample-only"; }
            public long surfaceY(long x, long z) { reads.incrementAndGet(); return 1000; }
            public int argb(long x, long z, long y) { return -1; }
        };
        assertThrows(IllegalArgumentException.class, () -> data(unsupported, 0, 0));
        var data = data(source((x, z) -> { reads.incrementAndGet(); return 1000; }), 0, 0);
        var tooDense = new ArrayList<CubePos>();
        for (int z = -8; z < 8; z++) for (int x = -8; x < 8; x++) tooDense.add(new CubePos(x, 62, z));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> data.clip(tooDense)).getMessage().contains("before sampling"));
        assertThrows(IllegalArgumentException.class, () -> data.clip(List.of(new CubePos(0, 554, 0))));
        assertThrows(IllegalArgumentException.class, () -> data.clip(Collections.nCopies(257, new CubePos(0, 62, 0))));
        assertEquals(0, reads.get());
        assertEquals(8704, data.clip(List.of()).sampledCells());
    }

    @Test void sourceAndPageCapsStayFailClosedWithoutRaisingLimitsOrReturningPartialBatch() {
        var sourceOverflow = data(source((x, z) -> x + z), 0, 0);
        var ready = nativeWindow(0, 0, 0); ready.add(CubePos.fromBlock(1152, 0, 1152));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> sourceOverflow.clip(ready)).getMessage().contains("Source face budget"));
        var pageOverflow = data(source((x, z) -> Math.floorMod(Math.floorDiv(x, 16), 2) == 0 ? -11000 : 8849), 0, 0);
        assertThrows(IllegalArgumentException.class, () -> pageOverflow.clip(List.of()));
        assertEquals(12288, DhAdaptiveTerrainData.MAX_CELLS); assertEquals(32768, DhAdaptiveTerrainData.MAX_FACES);
        assertEquals(64, DhAdaptiveTerrainData.MAX_TILES); assertEquals(4096, DhAdaptiveTerrainData.MAX_PAGE_FACES);
    }

    @Test void mutableSourceIdentityCapabilityAndCancellationFailClosed() {
        AtomicInteger reads = new AtomicInteger();
        SceneSurfaceSampler unstable = new SceneSurfaceSampler() {
            public String identity() { return reads.get() == 0 ? "before" : "after"; }
            public boolean supportsFootprintCoverage() { return true; }
            public boolean knownFootprint(long x, long z, long xx, long zz) { return true; }
            public long surfaceY(long x, long z) { reads.incrementAndGet(); return 1000; }
            public int argb(long x, long z, long y) { return -1; }
        };
        var data = data(unstable, 0, 0);
        assertThrows(IllegalStateException.class, () -> data.clip(List.of()));
        int prior = reads.get();
        assertThrows(IllegalStateException.class, () -> data.clip(List.of())); assertEquals(prior, reads.get());
        AtomicInteger capabilityReads = new AtomicInteger();
        SceneSurfaceSampler changingCapability = new SceneSurfaceSampler() {
            public String identity() { return "fixed-source-changing-support"; }
            public boolean supportsFootprintCoverage() { return capabilityReads.get() == 0; }
            public boolean knownFootprint(long x, long z, long xx, long zz) { return true; }
            public long surfaceY(long x, long z) { capabilityReads.incrementAndGet(); return 1000; }
            public int argb(long x, long z, long y) { return -1; }
        };
        assertThrows(IllegalStateException.class, () -> data(changingCapability, 0, 0).clip(List.of()));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> data(source((x, z) -> 1000), 0, 0)
                .clip(List.of(), List.of(), () -> { if (checks.incrementAndGet() == 40) throw new CancellationException(); }));
        assertEquals(40, checks.get());
    }

    /** Sweep every distinct strip; each must contain one disjoint exact-width X partition. */
    private static void assertWholeSquare(DhAdaptiveTerrainData.Batch batch, long minX, long minZ) {
        var tops = nativeFaces(batch).stream().filter(f -> f.direction() == UP).toList();
        var edges = new TreeSet<Long>();
        tops.forEach(f -> { edges.add(f.z()); edges.add(f.z() + f.height()); });
        assertEquals(minZ, edges.getFirst()); assertEquals(minZ + 8192, edges.getLast());
        Long[] values = edges.toArray(Long[]::new);
        for (int i = 0; i < values.length - 1; i++) {
            long z = values[i];
            var covering = tops.stream().filter(f -> f.z() <= z && f.z() + f.height() > z)
                    .sorted(Comparator.comparingLong(DhTerrainGeometry.Face::x)).toList();
            long next = minX;
            for (var face : covering) { assertEquals(next, face.x(), "hole or overlap in horizontal partition"); next += face.width(); }
            assertEquals(minX + 8192, next);
        }
    }


    @Test void everyAdmittedYAndEndpointPinTemplateHasTheSameCanonicalXZSubdivision() {
        // All 1242 admitted Y centers retain at least two eligible Y layers.
        // X/Z projection is therefore the same 3x3 footprint for every Y, plus
        // the fixed pin footprint. Exact XYZ duplicate-pin membership may differ.
        // Pin-inside, pin-on-corner and pin-outside cases exhaust that distinction;
        // the preceding test exhausts every translated admitted X/Z combination.
        int templates = 0;
        for (int pinY : new int[]{-688, 8, 553}) for (int[] xz : List.of(new int[]{0, 0}, new int[]{1, 1}, new int[]{2, 0}, new int[]{31, -32})) {
            var pin = new CubePos(0, pinY, 0);
            var expected = projectedXZ(eligibleTargets(pin, new CubePos(xz[0], 8, xz[1])));
            for (int y = RollingCubePlan.MIN_WINDOW_CENTER_Y; y <= RollingCubePlan.MAX_WINDOW_CENTER_Y; y++) {
                var targets = eligibleTargets(pin, new CubePos(xz[0], y, xz[1]));
                assertTrue(targets.size() >= 18 && targets.size() <= 28);
                assertTrue(targets.stream().allMatch(p -> p.minBlockY() >= -11008 && p.minBlockY() + 16 <= 8864));
                assertEquals(expected, projectedXZ(targets));
                templates++;
            }
        }
        assertEquals(14904, templates);
        // Direct production partition equivalence at both endpoint eligible masks,
        // across same-XZ/different-Y ownership and disjoint original pin masks.
        AtomicInteger callbacks = new AtomicInteger();
        var partition = data(unreadSource(callbacks), 256, -512);
        for (int pinY : new int[]{-688, 8, 553}) {
            var pin = new CubePos(0, pinY, 0);
            var baseline = partition.partitionCounts(eligibleTargets(pin, new CubePos(23, 8, -32)));
            for (int y : new int[]{-688, -687, -686, 7, 8, 9, 10, 551, 552, 553})
                assertEquals(baseline, partition.partitionCounts(eligibleTargets(pin, new CubePos(23, y, -32))));
        }
        assertEquals(0, callbacks.get());
    }

    @Test void purePartitionRejectionAndActualClipBothRejectUnadmittedDenseMasksBeforeTerrainCallbacks() {
        AtomicInteger callbacks = new AtomicInteger();
        var data = data(unreadSource(callbacks), 0, 0);
        var dense = new ArrayList<CubePos>();
        for (int z = -8; z < 8; z++) for (int x = -8; x < 8; x++) dense.add(new CubePos(x, 8, z));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> data.partitionCounts(dense)).getMessage().contains("before sampling"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> data.clip(dense)).getMessage().contains("before sampling"));
        assertEquals(0, callbacks.get());
        assertEquals(8704, data.partitionCounts(List.of()).sampledCells());
        assertEquals(0, callbacks.get());
    }

    private static SceneSurfaceSampler unreadSource(AtomicInteger callbacks) {
        return new SceneSurfaceSampler() {
            public String identity() { return "no-terrain-callback-partition-proof"; }
            public boolean supportsFootprintCoverage() { return true; }
            public boolean knownFootprint(long x, long z, long xx, long zz) { callbacks.incrementAndGet(); throw new AssertionError("No coverage callback permitted"); }
            public long surfaceY(long x, long z) { callbacks.incrementAndGet(); throw new AssertionError("No height callback permitted"); }
            public int argb(long x, long z, long y) { callbacks.incrementAndGet(); throw new AssertionError("No appearance callback permitted"); }
        };
    }
    static List<CubePos> eligibleTargets(CubePos pin, CubePos center) {
        return RollingCubePlan.stationary(pin, center).committedTargets().stream()
                .filter(p -> p.minBlockY() >= -11008 && p.minBlockY() + 16 <= 8864).toList();
    }
    private static Set<String> projectedXZ(List<CubePos> cubes) {
        var result = new TreeSet<String>(); cubes.forEach(p -> result.add(p.x() + "," + p.z())); return result;
    }

    private record Unit(DhTerrainGeometry.Direction direction, long x, long y, long z) { }
    /** Independent per-column ring/sample lookup, never using production partition/edge/clip. */
    private static long oracleTop(long x, long z, List<CubePos> refinement, LongBinaryOperator height) {
        int size = Math.abs(x < 0 ? x + 1 : x) < 512 && Math.abs(z < 0 ? z + 1 : z) < 512 ? 16
                : Math.abs(x < 0 ? x + 1 : x) < 1024 && Math.abs(z < 0 ? z + 1 : z) < 1024 ? 64 : 128;
        while (size > 1) {
            long cellX = Math.floorDiv(x, size) * size, cellZ = Math.floorDiv(z, size) * size;
            boolean intersects = false;
            for (CubePos cube : refinement) if (cellX < cube.minBlockX() + 17 && cellX + size > cube.minBlockX() - 1
                    && cellZ < cube.minBlockZ() + 17 && cellZ + size > cube.minBlockZ() - 1) { intersects = true; break; }
            if (!intersects) break;
            size /= 2;
        }
        try { return height.applyAsLong(Math.floorDiv(x, size) * size + size / 2, Math.floorDiv(z, size) * size + size / 2) + 1; }
        catch (MissingTerrainException absent) { return Long.MIN_VALUE; }
    }
    private static boolean owned(Unit face, List<CubePos> ready) {
        long x = face.x - (face.direction == EAST ? 1 : 0), y = face.y - (face.direction == UP ? 1 : 0);
        long z = face.z - (face.direction == SOUTH ? 1 : 0);
        return ready.stream().anyMatch(cube -> x >= cube.minBlockX() && x < cube.minBlockX() + 16
                && y >= cube.minBlockY() && y < cube.minBlockY() + 16 && z >= cube.minBlockZ() && z < cube.minBlockZ() + 16);
    }
    private static void assertOracle(DhAdaptiveTerrainData.Batch batch, List<CubePos> ready,
                                     List<CubePos> refinement, LongBinaryOperator height, long x0, long z0, int width) {
        var expected = new HashSet<Unit>(); long[][] top = new long[width][width];
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) top[z][x] = oracleTop(x0 + x, z0 + z, refinement, height);
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            long here = top[z][x]; if (here == Long.MIN_VALUE) continue;
            expected.add(new Unit(UP, x0 + x, here, z0 + z));
            if (x > 0 && top[z][x - 1] != Long.MIN_VALUE) for (long y = Math.min(here, top[z][x - 1]); y < Math.max(here, top[z][x - 1]); y++)
                expected.add(new Unit(top[z][x - 1] > here ? EAST : WEST, x0 + x, y, z0 + z));
            if (z > 0 && top[z - 1][x] != Long.MIN_VALUE) for (long y = Math.min(here, top[z - 1][x]); y < Math.max(here, top[z - 1][x]); y++)
                expected.add(new Unit(top[z - 1][x] > here ? SOUTH : NORTH, x0 + x, y, z0 + z));
        }
        expected.removeIf(f -> owned(f, ready));
        assertEquals(expected, raster(batch, x0, z0, width), "exact top/wall unit geometry including 3D mask");
    }
    private static Set<Unit> raster(DhAdaptiveTerrainData.Batch batch, long x0, long z0, int width) {
        var actual = new HashSet<Unit>();
        for (var f : nativeFaces(batch)) {
            if (f.direction() == UP) {
                for (long z = Math.max(z0, f.z()); z < Math.min(z0 + width, f.z() + f.height()); z++)
                    for (long x = Math.max(x0, f.x()); x < Math.min(x0 + width, f.x() + f.width()); x++)
                        assertTrue(actual.add(new Unit(UP, x, f.y(), z)), "duplicate top");
            } else if (f.direction() == EAST || f.direction() == WEST) {
                if (f.x() <= x0 || f.x() >= x0 + width) continue;
                for (long z = Math.max(z0, f.z()); z < Math.min(z0 + width, f.z() + f.width()); z++)
                    for (long y = f.y(); y < f.y() + f.height(); y++) assertTrue(actual.add(new Unit(f.direction(), f.x(), y, z)), "duplicate X wall");
            } else if (f.direction() == NORTH || f.direction() == SOUTH) {
                if (f.z() <= z0 || f.z() >= z0 + width) continue;
                for (long x = Math.max(x0, f.x()); x < Math.min(x0 + width, f.x() + f.width()); x++)
                    for (long y = f.y(); y < f.y() + f.height(); y++) assertTrue(actual.add(new Unit(f.direction(), x, y, f.z())), "duplicate Z wall");
            } else fail("unexpected bottom");
        }
        return actual;
    }
}
