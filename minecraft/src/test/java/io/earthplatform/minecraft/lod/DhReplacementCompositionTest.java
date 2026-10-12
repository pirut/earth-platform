package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.lod.DhGeographicLodData.TileKey;
import io.earthplatform.minecraft.lod.DhReplacementComposition.Declined;
import io.earthplatform.minecraft.lod.DhReplacementComposition.Result;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry.VoxelSource;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongBinaryOperator;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.lod.DhReplacementComposition.*;
import static org.junit.jupiter.api.Assertions.*;

class DhReplacementCompositionTest {
    private static final int COLOR = 0xFF898987, GOLD = 0xFFF2C94C;
    private static final Runnable LIVE = () -> {};
    private static final CubePos CUBE = new CubePos(0, 1, 0);
    private static final VoxelSource CHECKER = (x, y, z) -> ((x + y + z) & 1) == 0 ? COLOR : 0;
    private static final WorldIdentity WORLD = new WorldIdentity(7, "analytic", "v1", "flat", "opaque-v1");
    private static final DhReplacementInput.AuthorityGuard GUARD = new DhReplacementInput.AuthorityGuard() {
        public boolean isCurrent(DhReplacementInput.Certificate certificate) { return true; }
        public boolean permitsRetainedPresentation(DhReplacementInput.Certificate certificate) { return true; }
    };

    @Test void absentReplacementPreservesLegacyBatchesAndBaseTileStringsExactly() {
        for (DhLodLayout layout : List.of(DhLodLayout.FIXED_512,DhLodLayout.LONG_4096,DhLodLayout.ARENA_4096)) for (long x : new long[]{0, -129, 20_037_505}) {
            List<CubePos> ready = List.of(CubePos.fromBlock(x, 1000, -x));
            SceneSurfaceSampler source = source((bx, bz) -> 1000);
            var expected = new DhAdaptiveTerrainData(source, x, -x, layout).clip(ready);
            Result result = build(source, x, -x, layout, ready, null, MAX_RESULT_BYTES, LIVE);
            assertEquals(expected, result.batch()); assertEquals(expected.toString(), result.batch().toString());
            assertEquals(0, result.replacementSourceFaces()); assertEquals(0, result.replacementOwners());
        }
        assertThrows(Declined.class,()->build(source((x,z)->1000),0,0,DhLodLayout.EARTH_ALPINE_2048,List.of(),null,MAX_RESULT_BYTES,LIVE));
        assertEquals("TileKey[originX=-32, originZ=64, pageY=-6]", new TileKey(-32, 64, -6).toString());
        assertEquals(new TileKey(0, 0, 0), new TileKey(0, 0, 0, "", 0));
        assertNotEquals(new TileKey(0, 0, 0), new TileKey(0, 0, 0, "a".repeat(64), 0));
        assertNotEquals(new TileKey(0, 0, 0, "a".repeat(64), 0), new TileKey(0, 0, 0, "a".repeat(64), 1));
        assertThrows(IllegalArgumentException.class, () -> new TileKey(0, 0, 0, "", 1));
        assertThrows(IllegalArgumentException.class, () -> new TileKey(0, 0, 0, "not-a-digest", 0));
        assertThrows(IllegalArgumentException.class, () -> new TileKey(0, 0, 0, "a".repeat(64), 8));
    }

    @Test void exactExcavationAndFloatingBlockReplaceOnlyOwnedXYZAndStayAfterPinRetirement() {
        CubePos cube = new CubePos(0, 0, 0);
        VoxelSource voxels = (x, y, z) -> x == 9 && y == 10 && z == 9 ? GOLD
                : x == 0 && y == 7 && z == 0 ? 0 : y <= 7 ? COLOR : 0;
        var replacement = data(cube, voxels, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        Result after = compose(source((x, z) -> 7), List.of(), replacement);
        Result before = compose(source((x, z) -> 7), List.of(cube), replacement);
        assertEquals(baseTiles(before), baseTiles(after), "Native retirement cannot restore unedited base");
        assertEquals(before.batch().refinedCells(), after.batch().refinedCells());
        assertTrue(after.batch().refinedCells() > 0);
        assertEquals(334, after.replacementSourceFaces());
        assertEquals(334, after.replacementVisibleFaces());
        assertTrue(before.replacementVisibleFaces() < after.replacementVisibleFaces());
        assertReplacementOracle(after, replacement, List.of());
        assertReplacementOracle(before, replacement, List.of(cube));
        assertTrue(replacementFaces(after).stream().anyMatch(f -> f.direction() == Direction.EAST && f.x() == 0 && f.y() == 7));
        assertTrue(replacementFaces(after).stream().anyMatch(f -> f.argb() == GOLD));
        long baseTopArea = baseTiles(after).values().stream().flatMap(List::stream).mapToLong(f -> (long) f.width() * f.height()).sum();
        assertEquals(8192L * 8192 - 18 * 18, baseTopArea);
        assertPooled(after, replacement);
    }

    @Test void wrongYBaseSurfaceSurvivesExactReplacementWithoutBlanketXZMasking() {
        var replacement = data(CUBE, CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        Result result = compose(source((x, z) -> 1000), List.of(), replacement);
        long baseTopArea = baseTiles(result).values().stream().flatMap(List::stream).mapToLong(f -> (long) f.width() * f.height()).sum();
        assertEquals(8192L * 8192, baseTopArea);
        assertTrue(result.batch().refinedCells() > 0);
        assertEquals(17496, result.replacementVisibleFaces());
    }

    @Test void checkerboardAndFlatBaseSharePooledBoundsAndSplitDeterministicSignedOwners() {
        for (CubePos cube : List.of(CUBE, new CubePos(-1, -1, -1), new CubePos(-1, -688, -1), new CubePos(0, 553, 0))) {
            var replacement = data(cube, CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
            Result result = compose(source((x, z) -> cube.minBlockY() + 7), List.of(), replacement);
            assertPooled(result, replacement);
            assertReplacementOracle(result, replacement, List.of());
            assertTrue(result.replacementOwners() >= 7);
            assertTrue(result.batch().tiles().entrySet().stream().anyMatch(e -> !e.getKey().replacementIdentity().isEmpty() && e.getValue().size() == 4096));
            assertTrue(result.batch().tiles().keySet().stream().anyMatch(key -> key.piece() > 0));
            assertEquals(result, compose(source((x, z) -> cube.minBlockY() + 7), List.of(), replacement));
            Map<String, Integer> nextPiece = new HashMap<>();
            for (TileKey key : result.batch().tiles().keySet()) if (!key.replacementIdentity().isEmpty()) {
                String bucket = key.originX() + "/" + key.originZ() + "/" + key.pageY();
                assertEquals(nextPiece.getOrDefault(bucket, 0).intValue(), key.piece());
                nextPiece.put(bucket, key.piece() + 1);
                assertEquals(0, Math.floorMod(key.originX(), 32)); assertEquals(0, Math.floorMod(key.originZ(), 32));
                assertEquals(replacement.identity(), key.replacementIdentity());
            }
        }
    }

    @Test void baseAndReplacementKeysNeverAliasAndAuthorityIdentityChangesEveryPiece() {
        var a = data(CUBE, CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        var b = data(CUBE, CHECKER, "b", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        Result before = compose(source((x, z) -> 1000), List.of(), a);
        Result after = compose(source((x, z) -> 1000), List.of(), b);
        assertEquals(baseTiles(before), baseTiles(after));
        assertEquals(replacementFaces(before), replacementFaces(after));
        Set<TileKey> oldKeys = replacementKeys(before), newKeys = replacementKeys(after);
        assertTrue(oldKeys.stream().noneMatch(newKeys::contains));
        assertTrue(oldKeys.stream().anyMatch(key -> baseTiles(before).containsKey(new TileKey(key.originX(), key.originZ(), key.pageY()))));
    }

    @Test void nativeReadyClipsOnlyExactSolidOwnersIncludingTheCollarAndAllAirIsAuthoritative() {
        var replacement = data(CUBE, CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        var ready = new ArrayList<CubePos>();
        for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) ready.add(CUBE.offset(x, y, z));
        Result hidden = compose(source((x, z) -> 23), ready, replacement);
        assertEquals(17496, hidden.replacementSourceFaces());
        assertEquals(0, hidden.replacementVisibleFaces()); assertEquals(0, hidden.replacementOwners());
        assertReplacementOracle(hidden, replacement, ready);
        var oneNeighbor = List.of(CUBE.offset(-1, 0, 0));
        Result partial = compose(source((x, z) -> 23), oneNeighbor, replacement);
        assertReplacementOracle(partial, replacement, oneNeighbor);
        assertTrue(partial.replacementVisibleFaces() > 0 && partial.replacementVisibleFaces() < 17496);
        var air = data(CUBE, (x, y, z) -> 0, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        Result empty = compose(source((x, z) -> 23), List.of(), air);
        assertEquals(0, empty.replacementSourceFaces()); assertEquals(0, empty.replacementOwners());
        assertEquals(8192L * 8192 - 324, baseTiles(empty).values().stream().flatMap(List::stream).mapToLong(f -> (long) f.width() * f.height()).sum());
    }

    @Test void exactCombinedByteLimitIncludesCaptureAndPreservesPreviousResultOnRefusal() {
        var replacement = data(CUBE, CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        Result expected = compose(source((x, z) -> 1000), List.of(), replacement);
        long exact = 4096 + replacement.declaredBytes() + expected.batch().faces() * 128L;
        Result tight = build(source((x, z) -> 1000), 0, 0, DhLodLayout.LONG_4096, List.of(), replacement, exact, LIVE);
        assertEquals(expected, tight);
        assertThrows(Declined.class, () -> build(source((x, z) -> 1000), 0, 0, DhLodLayout.LONG_4096, List.of(), replacement, exact - 1, LIVE));
        assertEquals(expected, compose(source((x, z) -> 1000), List.of(), replacement));
        assertThrows(Declined.class, () -> build(source((x, z) -> 1000), 0, 0, DhLodLayout.LONG_4096, List.of(), replacement, MAX_RESULT_BYTES + 1, LIVE));
    }

    @Test void combinedFacesCanRefuseEvenWhenBothStandaloneGeometriesFitTheirOwnCaps() {
        var replacement = data(CUBE, CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        SceneSurfaceSampler source = source((x, z) -> 1000 + ((Math.floorDiv(x, 16) + Math.floorDiv(z, 16)) & 1));
        var base = new DhAdaptiveTerrainData(source, 0, 0, DhLodLayout.LONG_4096)
                .clip(List.of(), List.of(CUBE), List.of(replacement.capture().ownedVolume()), LIVE);
        assertTrue(base.faces() <= 32768 && base.tiles().size() <= 64);
        assertTrue(base.faces() + 17496 > 32768);
        Declined declined = assertThrows(Declined.class, () -> compose(source, List.of(), replacement));
        assertTrue(declined.getMessage().contains("Combined base/replacement face or byte budget"), declined.getMessage());
    }

    @Test void combinedOwnerPoolRejectsAReplacementBeyondSixtyFourExistingOwners() {
        SceneSurfaceSampler sparse = new SceneSurfaceSampler() {
            public String identity() { return "sparse-owner-cap"; }
            public boolean supportsFootprintCoverage() { return true; }
            public boolean knownFootprint(long x, long z, long maxX, long maxZ) {
                long localX = Math.floorMod(x, 256), localZ = Math.floorMod(z, 256);
                return maxX - x == 16 && maxZ - z == 16 && localZ == 64 && localX >= 64 && localX <= 160 && (localX - 64) % 32 == 0;
            }
            public long surfaceY(long x, long z) { return 100 + (Math.floorMod(x, 256) - 72) / 32 * 2048; }
            public int argb(long x, long z, long y) { return COLOR; }
        };
        var replacement = data(CUBE, (x, y, z) -> x == 8 && y == 20 && z == 8 ? COLOR : 0, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        var base = new DhAdaptiveTerrainData(sparse, 0, 0).clip(List.of(), List.of(CUBE), List.of(replacement.capture().ownedVolume()), LIVE);
        assertEquals(64, base.faces()); assertEquals(64, base.tiles().size());
        Declined declined = assertThrows(Declined.class, () -> build(sparse, 0, 0, DhLodLayout.FIXED_512, List.of(), replacement, MAX_RESULT_BYTES, LIVE));
        assertTrue(declined.getMessage().contains("Combined base/replacement owner budget"));
    }

    @Test void unsupportedPoliciesAndOutOfViewOwnedCollarsDeclineBeforeAnyTerrainRead() {
        AtomicInteger reads = new AtomicInteger();
        SceneSurfaceSampler source = source((x, z) -> { reads.incrementAndGet(); return 1000; });
        var unsupported = data(CUBE, CHECKER, "a", "unknown-material-policy");
        assertThrows(Declined.class, () -> compose(source, List.of(), unsupported));
        for (CubePos outside : List.of(new CubePos(256, 1, 0), new CubePos(-256, 1, 0), new CubePos(0, 1, 256), new CubePos(0, 1, -256))) {
            var replacement = data(outside, CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
            assertThrows(Declined.class, () -> compose(source, List.of(), replacement));
        }
        assertEquals(0, reads.get());
        var inside = data(new CubePos(255, 1, 0), (x, y, z) -> 0, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        // x=4080 owns through4097, so even the positive-side collar outside the view must reject.
        assertThrows(Declined.class, () -> compose(source, List.of(), inside));
        assertEquals(0, reads.get());
    }

    @Test void typedAdaptiveBudgetRefusesButUnexpectedSourceFailuresAndCancellationPropagateUnchanged() {
        var replacement = data(CUBE, CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        var dense = new ArrayList<CubePos>();
        for (int z = -8; z < 8; z++) for (int x = -8; x < 8; x++) dense.add(new CubePos(x, 62, z));
        assertThrows(Declined.class, () -> compose(source((x, z) -> 1000), dense, replacement));
        var failure = new IllegalArgumentException("Unexpected source corruption");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> compose(source((x, z) -> { throw failure; }), List.of(), replacement)));
        var runtimeFailure = new IllegalStateException("Source read failed");
        assertSame(runtimeFailure, assertThrows(IllegalStateException.class, () -> compose(source((x, z) -> { throw runtimeFailure; }), List.of(), replacement)));
        AtomicInteger checks = new AtomicInteger();
        build(source((x, z) -> 1000), 0, 0, DhLodLayout.LONG_4096, List.of(), replacement, MAX_RESULT_BYTES, checks::incrementAndGet);
        int complete = checks.get();
        for (int stop : new int[]{1, complete / 2, complete}) {
            checks.set(0); var cancellation = new CancellationException("stop " + stop);
            assertSame(cancellation, assertThrows(CancellationException.class, () -> build(source((x, z) -> 1000), 0, 0, DhLodLayout.LONG_4096,
                    List.of(), replacement, MAX_RESULT_BYTES, () -> { if (checks.incrementAndGet() == stop) throw cancellation; })));
        }
    }

    @Test void inputReadinessIsSnapshottedBeforeAnySourceCallbackAndOutputIsImmutable() {
        var ready = new ArrayList<CubePos>();
        var replacement = data(CUBE, CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
        SceneSurfaceSampler mutating = source((x, z) -> { if (ready.isEmpty()) ready.add(CUBE); return 1000; });
        Result result = compose(mutating, ready, replacement);
        assertEquals(17496, result.replacementVisibleFaces());
        assertThrows(UnsupportedOperationException.class, () -> result.batch().tiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.batch().tiles().values().iterator().next().clear());
    }

    @Test void pinnedOfficialCpuPackingPreservesReplacementPiecesAtSignedPagesAndNativeEndpoints() throws Exception {
        String jar = System.getProperty("earthplatform.lodProbeJar", System.getenv("EARTH_DH_PROBE_JAR"));
        org.junit.jupiter.api.Assumptions.assumeTrue(jar != null && !jar.isBlank(), "Explicit pinned official artifact required");
        var runtime = DhOfficialRuntime.open(java.nio.file.Path.of(jar));
        long checkedFaces = 0;
        int checkedPieces = 0;
        long peakDeclaredBytes = 0, peakGpuUnionBytes = 0;
        for (int y : new int[]{-688, -128, 0, 128, 553}) {
            var replacement = data(new CubePos(-1, y, -1), CHECKER, "a", DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
            Result result = compose(source((x, z) -> y * 16L + 7), List.of(), replacement);
            long totalBytes = 0;
            for (var entry : result.batch().tiles().entrySet()) {
                var key = entry.getKey(); var faces = entry.getValue();
                assertTrue(faces.size() <= 4096);
                byte[] bytes = runtime.buildRectangles(faces);
                assertTrue(bytes.length > 0 && bytes.length % 64 == 0 && bytes.length <= faces.size() * 64L);
                totalBytes += bytes.length;
                if (key.replacementIdentity().isEmpty()) continue;
                checkedPieces++; checkedFaces += faces.size();
                var expected = new HashSet<Set<String>>();
                for (var face : faces) assertTrue(expected.add(corners(face)), "No duplicated input geometry");
                var actual = new HashSet<Set<String>>();
                var packed = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder());
                for (int quad = 0; quad < bytes.length; quad += 64) {
                    var points = new HashSet<String>();
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int offset = quad + vertex * 16;
                        int px = Short.toUnsignedInt(packed.getShort(offset));
                        int py = Short.toUnsignedInt(packed.getShort(offset + 2));
                        int pz = Short.toUnsignedInt(packed.getShort(offset + 4));
                        assertTrue(px >= 0 && px <= 32 && pz >= 0 && pz <= 32 && py >= 0 && py <= 2048);
                        long nativeY = key.pageY() * 2048L + py;
                        assertTrue(nativeY >= DhTerrainGeometry.MIN_Y && nativeY <= DhTerrainGeometry.MAX_Y_EXCLUSIVE);
                        points.add(px + "," + py + "," + pz);
                    }
                    assertTrue(actual.add(points), "No duplicated official quad");
                }
                // Checkerboard unit faces cannot merge coplanarly, so this checks
                // every original face's exact four corners after official packing.
                assertEquals(expected, actual);
            }
            long declaredBytes = 4096 + replacement.declaredBytes() + result.batch().faces() * 64L + totalBytes;
            long gpuUnionBytes = (totalBytes + totalBytes / 64 * 24) * 2;
            assertTrue(declaredBytes <= MAX_RESULT_BYTES);
            assertTrue(gpuUnionBytes <= 8L * 1024 * 1024);
            peakDeclaredBytes = Math.max(peakDeclaredBytes, declaredBytes);
            peakGpuUnionBytes = Math.max(peakGpuUnionBytes, gpuUnionBytes);
        }
        assertTrue(checkedPieces >= 35); assertTrue(checkedFaces >= 80000);
        System.out.println("007 exact replacement official CPU packing: fixtures=5, pieces=" + checkedPieces
                + ", exactUnitFaces=" + checkedFaces + ", peakDeclaredResultBytes=" + peakDeclaredBytes
                + ", peakOldNewGpuAccountingBytes=" + peakGpuUnionBytes);
    }

    private static Set<String> corners(DhTerrainGeometry.PageFace face) {
        var points = new HashSet<String>();
        for (int u : new int[]{0, face.width()}) for (int v : new int[]{0, face.height()}) {
            int x = face.x(), y = face.y(), z = face.z();
            switch (face.direction()) {
                case UP, DOWN -> { x += u; z += v; }
                case NORTH, SOUTH -> { x += u; y += v; }
                case WEST, EAST -> { z += u; y += v; }
            }
            points.add(x + "," + y + "," + z);
        }
        return points;
    }

    private static SceneSurfaceSampler source(LongBinaryOperator height) {
        return new SceneSurfaceSampler() {
            public String identity() { return "replacement-composition-oracle"; }
            public long surfaceY(long x, long z) { return height.applyAsLong(x, z); }
            public int argb(long x, long z, long y) { return COLOR; }
            public boolean supportsFootprintCoverage() { return true; }
            public boolean knownFootprint(long x, long z, long maxX, long maxZ) { return true; }
        };
    }
    private static DhReplacementInput.Data data(CubePos cube, VoxelSource source, String digestCharacter, String policy) {
        var certificate = new DhReplacementInput.Certificate(WORLD, 1, 1, 1, cube, 1,
                digestCharacter.repeat(64), "c".repeat(64), policy);
        var capture = DhVoxelReplacementGeometry.capture(cube, source, LIVE);
        return new DhReplacementInput(certificate, capture, GUARD).data();
    }
    private static Result compose(SceneSurfaceSampler source, List<CubePos> ready, DhReplacementInput.Data data) {
        return build(source, 0, 0, DhLodLayout.LONG_4096, ready, data, MAX_RESULT_BYTES, LIVE);
    }
    private static Map<TileKey, List<DhTerrainGeometry.PageFace>> baseTiles(Result result) {
        var base = new HashMap<TileKey, List<DhTerrainGeometry.PageFace>>();
        result.batch().tiles().forEach((key, faces) -> { if (key.replacementIdentity().isEmpty()) base.put(key, faces); });
        return base;
    }
    private static Set<TileKey> replacementKeys(Result result) {
        var keys = new HashSet<>(result.batch().tiles().keySet()); keys.removeIf(key -> key.replacementIdentity().isEmpty()); return keys;
    }
    private static List<Face> replacementFaces(Result result) {
        var faces = new ArrayList<Face>();
        result.batch().tiles().forEach((key, pages) -> {
            if (!key.replacementIdentity().isEmpty()) for (var p : pages) faces.add(new Face(p.direction(), key.originX() + p.x(),
                    p.pageY2048() * 2048L + p.y(), key.originZ() + p.z(), p.width(), p.height(), p.argb()));
        });
        return faces;
    }
    private static void assertReplacementOracle(Result result, DhReplacementInput.Data data, List<CubePos> ready) {
        Set<Face> expected = new HashSet<>();
        var exact = DhVoxelReplacementGeometry.build(data.capture(), 32768, 5L * 1024 * 1024, LIVE);
        for (Face face : exact.faces()) expected.addAll(DhTerrainGeometry.clip(face, ready, 1));
        List<Face> actual = replacementFaces(result);
        assertEquals(actual.size(), new HashSet<>(actual).size(), "No duplicate pieces");
        assertEquals(expected, new HashSet<>(actual));
        assertEquals(expected.size(), result.replacementVisibleFaces());
        assertEquals(replacementKeys(result).size(), result.replacementOwners());
        for (var entry : baseTiles(result).entrySet()) for (var page : entry.getValue()) {
            Face face = new Face(page.direction(), entry.getKey().originX() + page.x(), page.pageY2048() * 2048L + page.y(),
                    entry.getKey().originZ() + page.z(), page.width(), page.height(), page.argb());
            assertEquals(List.of(face), DhTerrainGeometry.clip(face, ready, List.of(data.capture().ownedVolume()), 4096));
        }
    }
    private static void assertPooled(Result result, DhReplacementInput.Data data) {
        assertTrue(result.batch().faces() <= 32768); assertTrue(result.batch().tiles().size() <= 64);
        assertEquals(result.batch().faces(), result.batch().tiles().values().stream().mapToInt(List::size).sum());
        assertTrue(result.batch().tiles().values().stream().allMatch(faces -> faces.size() <= 4096));
        long declared = 4096 + data.declaredBytes() + result.batch().faces() * 128L;
        assertTrue(declared <= MAX_RESULT_BYTES);
        assertTrue(declared * 4 <= 20L * 1024 * 1024);
        assertTrue(result.batch().faces() * (64L + 24) * 2 <= 8L * 1024 * 1024);
        assertTrue(result.batch().tiles().size() * 2 <= 128);
    }
}
