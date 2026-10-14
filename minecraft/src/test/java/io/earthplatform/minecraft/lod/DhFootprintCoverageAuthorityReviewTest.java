package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.DatasetProvenance;
import io.earthplatform.geography.EquirectangularProjection;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.OfflineTerrain;
import io.earthplatform.geography.RasterTile;
import io.earthplatform.geography.TileSpec;
import io.earthplatform.geography.WorldManifest;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Independent continuous-footprint omission and integration tests; no visual or GL claim. */
class DhFootprintCoverageAuthorityReviewTest {
    private record Rectangle(long x0, long z0, long x1, long z1) {
        long size() { return x1 - x0; }
        boolean contains(long x, long z) { return x0 <= x && x < x1 && z0 <= z && z < z1; }
    }
    private static class Source implements SceneSurfaceSampler {
        final List<Rectangle> footprints = new ArrayList<>();
        final List<List<Long>> centers = new ArrayList<>();
        final Predicate<Rectangle> proof;
        int colors; boolean capability = true; String identity = "independent-footprint-source";
        Source(Predicate<Rectangle> proof) { this.proof = proof; }
        public boolean supportsFootprintCoverage() { return capability; }
        public boolean knownFootprint(long x0, long z0, long x1, long z1) {
            var rectangle = new Rectangle(x0, z0, x1, z1); footprints.add(rectangle); return proof.test(rectangle);
        }
        public long surfaceY(long x, long z) { centers.add(List.of(x, z)); return 1000; }
        public int argb(long x, long z, long y) { colors++; return 0xffaabbcc; }
        public String identity() { return identity; }
    }
    private static DhAdaptiveTerrainData.Batch build(Source source) { return new DhAdaptiveTerrainData(source, 0, 0).clip(List.of()); }
    private static List<DhTerrainGeometry.Face> faces(DhAdaptiveTerrainData.Batch batch) {
        var all = new ArrayList<DhTerrainGeometry.Face>();
        batch.tiles().forEach((owner, values) -> values.forEach(face -> all.add(new DhTerrainGeometry.Face(face.direction(),
                owner.originX() + face.x(), (long)face.pageY2048() * 2048 + face.y(), owner.originZ() + face.z(),
                face.width(), face.height(), face.argb()))));
        return all;
    }

    @Test void rejectedHalfWindowNeverCallsHeightOrColorAndNeverInventsBoundarySkirts() {
        var source = new Source(r -> r.x1() <= 0); var batch = build(source);
        assertEquals(4096, source.footprints.size()); assertEquals(2048, source.centers.size());
        assertEquals(2048, source.colors); assertEquals(4096, batch.sampledCells());
        assertEquals(2048, batch.centerSampledCells()); assertEquals(2048, batch.footprintRejectedCells());
        assertEquals(2048, batch.fullySupportedCells()); assertEquals(2048, batch.knownCells()); assertEquals(2048, batch.unknownCells());
        assertTrue(source.centers.stream().allMatch(p -> p.getFirst() < 0));
        for (var face : faces(batch)) {
            assertEquals(DhTerrainGeometry.Direction.UP, face.direction(), "Unknown boundary must not get fabricated walls");
            assertTrue(face.x() + face.width() <= 0);
        }
    }

    @Test void twoMetreUnsupportedOverhangOmitsWholeCoarseLeafDespiteKnownCenter() {
        var source = new Source(r -> r.x1() <= 510); var batch = build(source);
        assertEquals(64, batch.footprintRejectedCells()); assertEquals(4032, batch.centerSampledCells());
        assertTrue(source.footprints.stream().anyMatch(r -> r.x0() == 496 && r.x1() == 512));
        assertFalse(source.centers.stream().anyMatch(p -> p.getFirst() == 504),
                "The valid central point must not stand in for its unsupported rectangle");
        assertTrue(faces(batch).stream().allMatch(f -> f.x() + f.width() <= 496));
        assertEquals(1000, source.surfaceY(504, 8), "Center-only sampling really is known in this counterexample");
    }

    @Test void internalMissingFootprintProducesAHoleWithoutHeightQueriesOrSurroundingWalls() {
        var hole = new Rectangle(0, 0, 16, 16);
        var source = new Source(r -> { if (r.equals(hole)) throw new MissingTerrainException("Interior support hole"); return true; });
        var batch = build(source);
        assertEquals(1, batch.footprintRejectedCells()); assertEquals(1, batch.unknownCells());
        assertEquals(4095, batch.centerSampledCells()); assertEquals(4095, batch.fullySupportedCells());
        assertFalse(source.centers.contains(List.of(8L, 8L)));
        for (var face : faces(batch)) {
            assertEquals(DhTerrainGeometry.Direction.UP, face.direction());
            assertFalse(face.x() < hole.x1() && hole.x0() < face.x() + face.width()
                    && face.z() < hole.z1() && hole.z0() < face.z() + face.height());
        }
    }

    @Test void refinedProofReceivesEveryExactHalfOpenLeafIncludingNativeOneMetreHalo() {
        var source = new Source(r -> true);
        var batch = new DhAdaptiveTerrainData(source, 0, 0).clip(List.of(), List.of(new CubePos(-1, 62, 0)));
        assertEquals(batch.sampledCells(), source.footprints.size()); assertEquals(source.footprints.size(), source.centers.size());
        assertEquals(batch.knownCells(), batch.fullySupportedCells()); assertEquals(0, batch.footprintRejectedCells());
        assertEquals(Set.of(1L, 2L, 4L, 8L, 16L), source.footprints.stream().map(Rectangle::size).collect(java.util.stream.Collectors.toSet()));
        for (int i = 0; i < source.footprints.size(); i++) {
            var r = source.footprints.get(i); var p = source.centers.get(i);
            assertEquals(r.size(), r.z1() - r.z0()); assertTrue(r.size() > 0 && r.size() <= 16);
            assertEquals(r.x0() + r.size()/2, p.getFirst()); assertEquals(r.z0() + r.size()/2, p.getLast());
            assertTrue(r.x0() >= -512 && r.z0() >= -512 && r.x1() <= 512 && r.z1() <= 512);
        }
        for (long z = -1; z < 17; z++) for (long x = -17; x < 1; x++) {
            long px = x, pz = z;
            var matching = source.footprints.stream().filter(r -> r.contains(px, pz)).toList();
            assertEquals(1, matching.size()); assertEquals(1, matching.getFirst().size());
        }
        assertEquals(1024L * 1024, source.footprints.stream().mapToLong(r -> r.size() * r.size()).sum());
        assertTrue(batch.sampledCells() <= DhAdaptiveTerrainData.MAX_CELLS);
        assertTrue(batch.faces() <= DhAdaptiveTerrainData.MAX_FACES);
        assertTrue(batch.tiles().size() <= DhAdaptiveTerrainData.MAX_TILES);
        assertTrue(batch.tiles().values().stream().allMatch(v -> v.size() <= DhAdaptiveTerrainData.MAX_PAGE_FACES));
    }

    @Test void successfulProofDoesNotHideLaterMissingHeightOrAppearance() {
        for (boolean appearance : new boolean[]{false, true}) {
            var source = new Source(r -> true) {
                public long surfaceY(long x, long z) {
                    super.surfaceY(x, z); if (!appearance && x >= 0) throw new MissingTerrainException("Height absent"); return 1000;
                }
                public int argb(long x, long z, long y) {
                    super.argb(x, z, y); if (appearance && x >= 0) throw new MissingTerrainException("Appearance absent"); return -1;
                }
            };
            var batch = build(source);
            assertEquals(4096, batch.centerSampledCells()); assertEquals(0, batch.footprintRejectedCells());
            assertEquals(2048, batch.knownCells()); assertEquals(2048, batch.unknownCells());
            assertEquals(2048, batch.fullySupportedCells(), "Only successful source-backed cells are counted");
        }
    }

    @Test void legacyKnownCentersAreExplicitlyUncertifiedAndDoNotInvokeUnsupportedCapability() {
        var source = new Source(r -> { throw new AssertionError("Unsupported proof must not be queried"); });
        source.capability = false; var batch = build(source);
        assertEquals(4096, batch.knownCells()); assertEquals(4096, batch.centerSampledCells());
        assertEquals(0, batch.fullySupportedCells()); assertEquals(0, batch.footprintRejectedCells());
        assertTrue(source.footprints.isEmpty());
    }

    @Test void unexpectedProofFailureIsFatalRatherThanConvertedToUnknownOrKnown() {
        for (RuntimeException fault : List.of(new UnsupportedOperationException("Unsupported proof"), new IllegalStateException("Failed proof"))) {
            var source = new Source(r -> { throw fault; });
            assertSame(fault, assertThrows(RuntimeException.class, () -> build(source)));
            assertEquals(1, source.footprints.size()); assertTrue(source.centers.isEmpty()); assertEquals(0, source.colors);
        }
    }

    @Test void sourceIdentityOrCoverageCapabilityChangesCannotPublishABatch() {
        for (boolean changeCapability : new boolean[]{false, true}) {
            var source = new Source(r -> true) {
                public boolean knownFootprint(long x0, long z0, long x1, long z1) {
                    boolean answer = super.knownFootprint(x0, z0, x1, z1);
                    if (changeCapability) this.capability = false; else identity = "changed-source";
                    return answer;
                }
            };
            var data = new DhAdaptiveTerrainData(source, 0, 0);
            assertThrows(IllegalStateException.class, () -> data.clip(List.of()));
            int attempts = source.footprints.size();
            assertThrows(IllegalStateException.class, () -> data.clip(List.of()));
            assertEquals(attempts, source.footprints.size(), "A changed pinned source must fail before more support queries");
        }
    }

    @Test void cancellationDuringSupportQueriesStopsAtTheNextBoundedLeafAndReturnsNoGeometry() {
        var cancelled = new AtomicBoolean(); var queries = new AtomicInteger();
        var source = new Source(r -> { if (queries.incrementAndGet() == 10) cancelled.set(true); return true; });
        assertThrows(CancellationException.class, () -> new DhAdaptiveTerrainData(source, 0, 0).clip(List.of(), List.of(),
                () -> { if (cancelled.get()) throw new CancellationException("Review cancellation"); }));
        assertEquals(10, queries.get()); assertTrue(source.centers.size() <= 10); assertTrue(source.colors <= 10);
    }

    @Test void overBudgetPartitionAndAllUnknownCoverageCannotReturnPartialGeometry() {
        var dense = new ArrayList<CubePos>();
        for (int z = -8; z < 8; z++) for (int x = -8; x < 8; x++) dense.add(new CubePos(x, 62, z));
        var source = new Source(r -> true);
        assertThrows(IllegalArgumentException.class, () -> new DhAdaptiveTerrainData(source, 0, 0).clip(List.of(), dense));
        assertTrue(source.footprints.isEmpty()); assertTrue(source.centers.isEmpty());
        var absent = new Source(r -> false);
        assertThrows(IllegalArgumentException.class, () -> build(absent));
        assertEquals(4096, absent.footprints.size()); assertTrue(absent.centers.isEmpty()); assertEquals(0, absent.colors);
    }

    @Test void multipleFiniteOverlappingTilesNeverPretendUnsupportedStitchingIsProven() throws Exception {
        var a = tile("a", 0); var b = tile("b", 0);
        var pointA = new GeoPoint(3.5, .5); var pointB = new GeoPoint(2.5, 1.5);
        assertTrue(a.hasFiniteRectangleSupport(pointA, pointB)); assertTrue(b.hasFiniteRectangleSupport(pointA, pointB));
        var projection = new EquirectangularProjection(new GeoPoint(0, 0), 0);
        var provenance = new DatasetProvenance("review", "1", "EGM2008", 1, "test", URI.create("https://example.com"), "synthetic", "review");
        var manifest = new WorldManifest("review", 1, "1", projection, provenance, List.of(a.spec(), b.spec()), "f".repeat(64), "palette");
        var constructor = OfflineTerrain.class.getDeclaredConstructor(WorldManifest.class, Map.class); constructor.setAccessible(true);
        var stitched = constructor.newInstance(manifest, Map.of(a.spec(), a, b.spec(), b));
        assertFalse(stitched.hasFiniteRectangleSupport(pointA, pointB));
        var single = constructor.newInstance(manifest, Map.of(a.spec(), a));
        assertTrue(single.hasFiniteRectangleSupport(pointA, pointB));
    }


    private static RasterTile tile(String id, double west) {
        var spec = new TileSpec(id, id + ".egp", "a".repeat(64), west, 4, 1, 1, 5, 5);
        var heights = new float[25]; Arrays.fill(heights, 1000); return new RasterTile(spec, heights);
    }
    private static List<String> methodEvents(String type, String name) throws Exception {
        var result = new ArrayList<String>();
        try (var stream = DhFootprintCoverageAuthorityReviewTest.class.getClassLoader().getResourceAsStream(type + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    if (!method.equals(name)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean iface) { result.add("call:" + owner + "." + name); }
                        public void visitLdcInsn(Object value) { result.add("constant:" + value); }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return result;
    }
}
