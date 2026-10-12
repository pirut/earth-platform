package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent source/partition/XYZ/cap adversaries. Pure geometry, never a GPU proof. */
class DhLongDistanceIndependentReviewTest {
    private record Rect(long x, long z, int size) {
        boolean contains(long px, long pz) { return x <= px && px < x + size && z <= pz && pz < z + size; }
    }
    private static class Source implements SceneSurfaceSampler {
        final List<Rect> proofs = new ArrayList<>();
        final List<List<Long>> samples = new ArrayList<>();
        Predicate<Rect> supported = r -> true;
        long height = 1000; int colors; String identity = "independent-long-distance-source";
        boolean capable = true;
        public boolean supportsConcurrentReads() { return true; }
        public boolean supportsFootprintCoverage() { return capable; }
        public boolean knownFootprint(long x, long z, long ex, long ez) {
            assertEquals(ex - x, ez - z); var r = new Rect(x, z, Math.toIntExact(ex - x)); proofs.add(r); return supported.test(r);
        }
        public long surfaceY(long x, long z) { samples.add(List.of(x, z)); return height; }
        public int argb(long x, long z, long y) { colors++; return 0xff91a5bc; }
        public String identity() { return identity; }
    }
    private static DhAdaptiveTerrainData.Batch build(Source source, long x, long z, List<CubePos> ready, List<CubePos> refine) {
        return new DhAdaptiveTerrainData(source, x, z, DhLodLayout.LONG_4096).clip(ready, refine);
    }
    private static void caps(DhAdaptiveTerrainData.Batch batch) {
        assertTrue(batch.sampledCells() <= 12288); assertTrue(batch.faces() <= 32768);
        assertTrue(batch.tiles().size() <= 64); assertTrue(batch.tiles().values().stream().allMatch(v -> v.size() <= 4096));
        assertTrue(4096L + batch.faces() * 128L <= 5L * 1024 * 1024);
        assertThrows(UnsupportedOperationException.class, () -> batch.tiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> batch.tiles().values().iterator().next().clear());
    }
    private static void exactPartition(List<Rect> leaves, long cx, long cz) {
        Set<Rect> distinct = new HashSet<>(leaves); assertEquals(leaves.size(), distinct.size());
        long area = 0;
        for (Rect leaf : leaves) {
            assertTrue(leaf.size > 0 && leaf.size <= 128 && (leaf.size & (leaf.size - 1)) == 0);
            assertEquals(0, Math.floorMod(leaf.x, leaf.size)); assertEquals(0, Math.floorMod(leaf.z, leaf.size));
            assertTrue(leaf.x >= cx - 4096 && leaf.z >= cz - 4096 && leaf.x + leaf.size <= cx + 4096 && leaf.z + leaf.size <= cz + 4096);
            // Aligned dyadic squares overlap iff one is an ancestor of the other.
            for (int parent = leaf.size * 2; parent <= 128; parent *= 2)
                assertFalse(distinct.contains(new Rect(Math.floorDiv(leaf.x, parent) * parent, Math.floorDiv(leaf.z, parent) * parent, parent)), "Overlapping leaves");
            area += (long) leaf.size * leaf.size;
        }
        assertEquals(8192L * 8192, area, "No padding, overlap or hole in requested square");
    }
    private static long topArea(DhAdaptiveTerrainData.Batch batch) {
        return batch.tiles().values().stream().flatMap(List::stream).filter(f -> f.direction() == DhTerrainGeometry.Direction.UP)
                .mapToLong(f -> (long) f.width() * f.height()).sum();
    }

    @Test void unchangedDefaultsAndExplicitLongPolicyNeverConflate() {
        var source = new Source();
        var fixed = new DhAdaptiveTerrainData(source, -1, 127);
        var distant = new DhAdaptiveTerrainData(source, -1, 127, DhLodLayout.LONG_4096);
        assertEquals(DhLodLayout.FIXED_512, fixed.layout()); assertEquals(-528, fixed.originX()); assertEquals(-400, fixed.originZ());
        assertEquals(-4224, distant.originX()); assertEquals(-4096, distant.originZ());
        assertNotEquals(fixed.layoutIdentity(), distant.layoutIdentity());
        assertThrows(UnsupportedOperationException.class, () -> DhLodLayout.LONG_4096.rings().clear());
        assertThrows(ArithmeticException.class, () -> new DhAdaptiveTerrainData(source, Long.MAX_VALUE, 0, DhLodLayout.LONG_4096));
        assertThrows(ArithmeticException.class, () -> new DhAdaptiveTerrainData(source, Long.MIN_VALUE, 0, DhLodLayout.LONG_4096));
        assertTrue(source.proofs.isEmpty());
        source.capable = false;
        assertThrows(IllegalArgumentException.class, () -> new DhAdaptiveTerrainData(source, 0, 0, DhLodLayout.LONG_4096));
        assertTrue(source.samples.isEmpty());
    }

    @Test void fullLongSquareHasExactly8704SourceLeavesAndNoHiddenMetreGrid() {
        for (long[] center : List.of(new long[]{0,0}, new long[]{-128,128}, new long[]{-30_000_128,30_000_000})) {
            var source = new Source(); var batch = build(source, center[0], center[1], List.of(), List.of());
            assertEquals(8704, batch.sampledCells()); assertEquals(8704, source.proofs.size());
            assertEquals(8704, source.samples.size()); assertEquals(8704, source.colors);
            assertEquals(Set.of(16,64,128), source.proofs.stream().map(Rect::size).collect(java.util.stream.Collectors.toSet()));
            assertEquals(4096, source.proofs.stream().filter(r -> r.size == 16).count());
            assertEquals(768, source.proofs.stream().filter(r -> r.size == 64).count());
            assertEquals(3840, source.proofs.stream().filter(r -> r.size == 128).count());
            exactPartition(source.proofs, center[0], center[1]); caps(batch); assertEquals(8192L * 8192, topArea(batch));
        }
    }

    @Test void oneMetreCollarSurvivesEveryRingBoundaryAndSignedGlobalCoordinates() {
        long cx = -30_000_128, cz = 30_000_000;
        for (int offset : new int[]{-1040,-1024,-528,-512,-16,0,496,512,1008,1024}) {
            var source = new Source(); var cube = CubePos.fromBlock(cx + offset, 1000, cz - 16);
            var batch = build(source, cx, cz, List.of(), List.of(cube));
            exactPartition(source.proofs, cx, cz); caps(batch);
            Set<Rect> leaves = new HashSet<>(source.proofs);
            for (long z = cube.minBlockZ()-1; z < cube.minBlockZ()+17; z++) for (long x = cube.minBlockX()-1; x < cube.minBlockX()+17; x++)
                assertTrue(leaves.contains(new Rect(x,z,1)), "Exact metre collar at tier seam");
            assertEquals(8192L * 8192, topArea(batch));
        }
    }

    @Test void suppressionUsesExactSignedXyzIncludingFullHeightEndpointOwnership() {
        for (long y : new long[]{-11008,-2049,-1,0,2047,8863}) {
            var source = new Source(); source.height = y;
            var matching = CubePos.fromBlock(-16,y,16); var elsewhere = new CubePos(matching.x(), matching.y() == 553 ? 552 : matching.y()+1, matching.z());
            var visible = build(source, -128, 0, List.of(elsewhere), List.of(matching));
            var hidden = build(newHeight(y), -128, 0, List.of(matching), List.of(matching));
            assertEquals(8192L * 8192, topArea(visible)); assertEquals(8192L * 8192 - 256, topArea(hidden)); caps(hidden);
            for (var entry : hidden.tiles().entrySet()) for (var face : entry.getValue()) {
                assertEquals(entry.getKey().pageY(), face.pageY2048());
                assertEquals(y + 1, (long)face.pageY2048() * 2048 + face.y());
            }
        }
    }
    private static Source newHeight(long y) { var source = new Source(); source.height = y; return source; }

    @Test void unsupportedFarSliverNeverUsesItsKnownCenterOrFabricatesWall() {
        var source = new Source(); source.supported = r -> r.x + r.size <= 4094;
        var batch = build(source, 0, 0, List.of(), List.of());
        assertEquals(64, batch.footprintRejectedCells()); assertEquals(8640, batch.knownCells());
        assertFalse(source.samples.stream().anyMatch(p -> p.getFirst() == 4032));
        for (var entry : batch.tiles().entrySet()) for (var f : entry.getValue()) {
            assertEquals(DhTerrainGeometry.Direction.UP, f.direction());
            assertTrue(entry.getKey().originX() + f.x() + f.width() <= 3968);
        }
        assertEquals(8192L * (8192 - 128), topArea(batch));
    }

    @Test void budgetOverflowFailsBeforeAnySourceReadAndCancellationNeverReturnsPartialBatch() {
        var dense = new ArrayList<CubePos>();
        for (int z=-8;z<8;z++) for (int x=-8;x<8;x++) dense.add(new CubePos(x,62,z));
        var source = new Source();
        assertThrows(IllegalArgumentException.class, () -> build(source,0,0,List.of(),dense));
        assertTrue(source.proofs.isEmpty()); assertTrue(source.samples.isEmpty());
        var cancelled = new AtomicBoolean(); var interrupted = new Source();
        interrupted.supported = r -> { if(interrupted.proofs.size()==7) cancelled.set(true); return true; };
        assertThrows(CancellationException.class, () -> new DhAdaptiveTerrainData(interrupted,0,0,DhLodLayout.LONG_4096)
                .clip(List.of(),List.of(),()-> { if(cancelled.get()) throw new CancellationException("Review cancellation"); }));
        assertEquals(7, interrupted.proofs.size()); assertTrue(interrupted.samples.size()<=7); assertTrue(interrupted.colors<=7);
    }

    @Test void materialDomainAndSourceIdentityCannotSilentlyDegradeIntoAValidBatch() {
        var translucent = new Source() { public int argb(long x,long z,long y) { return 0x7fffffff; } };
        assertThrows(IllegalArgumentException.class, () -> build(translucent,0,0,List.of(),List.of()));
        for (long height : new long[]{-11009,8864}) assertThrows(IllegalArgumentException.class, () -> build(newHeight(height),0,0,List.of(),List.of()));
        var changed = new Source(); changed.supported = r -> { changed.identity="changed"; return true; };
        assertThrows(IllegalStateException.class, () -> build(changed,0,0,List.of(),List.of()));
        var absent = new Source(); absent.supported = r -> false;
        assertThrows(IllegalArgumentException.class, () -> build(absent,0,0,List.of(),List.of()));
        assertEquals(8704,absent.proofs.size()); assertTrue(absent.samples.isEmpty()); assertEquals(0,absent.colors);
    }
}
