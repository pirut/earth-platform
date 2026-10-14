package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.lod.DhReplacementComposition.Declined;
import io.earthplatform.minecraft.lod.DhReplacementComposition.Result;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Volume;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static io.earthplatform.minecraft.lod.DhReplacementComposition.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.MIN_Y;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.MAX_Y_EXCLUSIVE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate 008 proof: removing the original cube's edit shell does not widen its edit
 * ownership. Independent cell-face oracle, explicit 27 immutable source cubes,
 * and actual composition output. Native-ready faces below are an oracle for
 * ownership, not an assertion that a renderer published a native mesh.
 */
class DhBoundaryReplacementCompositionTest {
    private static final int AIR = 0, STONE = 1, DIORITE = 2;
    // Same pinned material colors as RollingReplacementCapture, no gold fixture.
    private static final int STONE_ARGB = 0xff808080, DIORITE_ARGB = 0xffdedede;
    private static final Runnable LIVE = () -> {};
    private static final WorldIdentity WORLD = new WorldIdentity(8, "analytic-native-cube", "v1", "opaque-flat", "unit-opaque-v1");
    private static final DhReplacementInput.AuthorityGuard GUARD = new DhReplacementInput.AuthorityGuard() {
        public boolean isCurrent(DhReplacementInput.Certificate certificate) { return true; }
        public boolean permitsRetainedPresentation(DhReplacementInput.Certificate certificate) { return true; }
    };
    private record Cell(long x, long y, long z) {
        CubePos cube() { return CubePos.fromBlock(x, y, z); }
    }
    private record Unit(Cell solid, Direction direction) { }
    private record Neighbor(Direction direction, int x, int y, int z) { }
    private static final List<Neighbor> NEIGHBORS = List.of(
            new Neighbor(Direction.WEST, -1, 0, 0), new Neighbor(Direction.EAST, 1, 0, 0),
            new Neighbor(Direction.DOWN, 0, -1, 0), new Neighbor(Direction.UP, 0, 1, 0),
            new Neighbor(Direction.NORTH, 0, 0, -1), new Neighbor(Direction.SOUTH, 0, 0, 1));
    private record Boundary(int x, int y, int z) {
        int axes() { return (x == 7 ? 0 : 1) + (y == 7 ? 0 : 1) + (z == 7 ? 0 : 1); }
        Cell at(CubePos cube) { return new Cell(cube.minBlockX() + x, cube.minBlockY() + y, cube.minBlockZ() + z); }
        @Override public String toString() { return (axes() == 1 ? "face" : axes() == 2 ? "edge" : "corner") + "(" + x + "," + y + "," + z + ")"; }
    }
    private static Stream<Boundary> boundaries() {
        var result = new ArrayList<Boundary>();
        for (int y : new int[]{0, 7, 15}) for (int z : new int[]{0, 7, 15}) for (int x : new int[]{0, 7, 15})
            if (x != 7 || y != 7 || z != 7) result.add(new Boundary(x, y, z));
        return result.stream();
    }

    @Test void categoriesAreExactlySixFacesTwelveEdgesAndEightCorners() {
        var categories = boundaries().toList();
        assertEquals(26, new HashSet<>(categories).size());
        assertEquals(6, categories.stream().filter(b -> b.axes() == 1).count());
        assertEquals(12, categories.stream().filter(b -> b.axes() == 2).count());
        assertEquals(8, categories.stream().filter(b -> b.axes() == 3).count());
    }

    @Test void boundaryScopeKeepsTheExistingSharedResourceCaps() {
        assertEquals(12288, DhAdaptiveTerrainData.MAX_CELLS);
        assertEquals(32768, DhAdaptiveTerrainData.MAX_FACES);
        assertEquals(64, DhAdaptiveTerrainData.MAX_TILES);
        assertEquals(4096, DhAdaptiveTerrainData.MAX_PAGE_FACES);
        assertEquals(256, DhTerrainGeometry.MAX_READY_CUBES);
        assertEquals(8000, DhVoxelReplacementGeometry.MAX_READ_CELLS);
        assertEquals(32768, DhVoxelReplacementGeometry.MAX_FACES);
        assertEquals(5L * 1024 * 1024, DhVoxelReplacementGeometry.MAX_VERTEX_BYTES);
        assertEquals(5L * 1024 * 1024, MAX_RESULT_BYTES);
        assertEquals(4096, RESULT_OVERHEAD_BYTES);
        assertEquals(128, DECLARED_BYTES_PER_FACE);
    }

    @ParameterizedTest(name = "boundary excavation {0}") @MethodSource("boundaries")
    void everyBoundaryExcavationKeepsNeighborFacesThroughNativeMaskChanges(Boundary boundary) {
        CubePos cube = new CubePos(-1, -128, -1);
        Cell edit = boundary.at(cube);
        var fixture = new FlatFixture(cube, cube.minBlockY() + 17, Map.of(edit, AIR));
        var data = fixture.capture();
        Result initial = compose(fixture, List.of(), data);
        Map<Unit, Integer> cavity = cavityFaces(initial, edit);
        assertEquals(6, cavity.size(), "The explicit halo encloses each excavated boundary cell");
        assertEquals(boundary.axes(), cavity.keySet().stream().filter(f -> !f.solid.cube().equals(cube)).count(),
                "Each touched cube plane exposes a neighbor-owned solid face");
        for (List<CubePos> mask : changingMasks(cube)) assertComposedOracle(fixture, data, mask, compose(fixture, mask, data));
        assertEquals(initial, compose(fixture, List.of(), data), "Native retirement restores the same immutable replacement");
        Map<Unit, Integer> afterCenterReady = cavityFaces(compose(fixture, List.of(cube), data), edit);
        assertEquals(boundary.axes(), afterCenterReady.size(), "Masking only the original cube must retain neighbor cavity faces");
        assertTrue(afterCenterReady.keySet().stream().noneMatch(f -> f.solid.cube().equals(cube)));
    }

    @ParameterizedTest(name = "surface and diorite {0}") @MethodSource("boundaries")
    void everyBoundarySurfaceRemovalAndDioritePlacementHasExactComposedUnion(Boundary boundary) {
        CubePos cube = new CubePos(-3, 0, -1);
        Cell edit = boundary.at(cube);
        for (FlatFixture fixture : List.of(
                new FlatFixture(cube, edit.y, Map.of(edit, AIR)),
                new FlatFixture(cube, cube.minBlockY() - 2, Map.of(edit, DIORITE)),
                new FlatFixture(cube, edit.y, Map.of(edit, DIORITE)))) {
            var data = fixture.capture();
            for (List<CubePos> mask : changingMasks(cube)) assertComposedOracle(fixture, data, mask, compose(fixture, mask, data));
            Result visible = compose(fixture, List.of(), data);
            long diorite = replacementUnits(visible).values().stream().filter(color -> color == DIORITE_ARGB).count();
            assertEquals(fixture.edits.get(edit) == AIR ? 0 : fixture.surface < edit.y ? 6 : 1, diorite);
        }
    }

    @ParameterizedTest(name = "native endpoints {0}") @MethodSource("boundaries")
    void everyBoundaryCategoryComposesAtBothClippedNativeYEndpoints(Boundary boundary) {
        for (int cubeY : new int[]{-688, 553}) {
            CubePos cube = new CubePos(-1, cubeY, -1);
            Cell edit = boundary.at(cube);
            long surface = Math.min(cube.minBlockY() + 17, MAX_Y_EXCLUSIVE - 1);
            var fixture = new FlatFixture(cube, surface, Map.of(edit, AIR));
            var data = fixture.capture();
            assertEquals(7600, data.capture().cellCount());
            assertEquals(30400, data.declaredBytes());
            assertEquals(19, data.capture().readBounds().sizeY());
            assertEquals(cubeY < 0 ? MIN_Y : cube.minBlockY() - 1, data.capture().ownedVolume().minY());
            assertEquals(cubeY < 0 ? cube.minBlockY() + 17 : MAX_Y_EXCLUSIVE, data.capture().ownedVolume().maxYExclusive());
            assertEquals(cubeY < 0 ? STONE_ARGB : 0,
                    data.capture().argbAt(edit.x, cubeY < 0 ? MIN_Y - 1 : MAX_Y_EXCLUSIVE, edit.z),
                    "Endpoint padding must come from explicit provider-style neighbor snapshots");
            for (List<CubePos> mask : changingMasks(cube)) assertComposedOracle(fixture, data, mask, compose(fixture, mask, data));
            Map<Unit, Integer> faces = replacementUnits(compose(fixture, List.of(), data));
            assertTrue(faces.keySet().stream().allMatch(f -> f.solid.y >= MIN_Y && f.solid.y < MAX_Y_EXCLUSIVE));
            assertTrue(faces.keySet().stream().noneMatch(f -> f.direction == Direction.DOWN && f.solid.y == MIN_Y),
                    "Known solid padding below the lower endpoint cannot invent an underside cap");
        }
    }

    @Test void allSixShellPlanesCanBeEditedTogetherWithoutChangingAnyNeighborVoxel() {
        CubePos cube = new CubePos(-1, -1, -1);
        var edits = new HashMap<Cell, Integer>();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15)
                edits.put(new Cell(cube.minBlockX() + x, cube.minBlockY() + y, cube.minBlockZ() + z), AIR);
        assertEquals(1352, edits.size());
        var fixture = new FlatFixture(cube, cube.minBlockY() + 17, edits);
        var data = fixture.capture();
        for (List<CubePos> mask : changingMasks(cube)) assertComposedOracle(fixture, data, mask, compose(fixture, mask, data));
        Map<Unit, Integer> visible = replacementUnits(compose(fixture, List.of(), data));
        assertEquals(6 * 16 * 16, visible.keySet().stream().filter(f -> !f.solid.cube().equals(cube))
                .filter(f -> adjacentToEdit(f, edits)).count(), "The six untouched neighbors own the outer cavity walls");
        assertEquals(6 * 14 * 14, visible.keySet().stream().filter(f -> f.solid.cube().equals(cube)).count());
    }

    @Test void negativeCoordinatesAndEverySignedPageTransitionKeepSolidSideOwnership() {
        for (int cubeY : new int[]{-640, -512, -384, -256, -128, 0, 128, 256, 384, 512}) {
            CubePos cube = new CubePos(-3, cubeY, -1);
            Cell edit = new Boundary(0, 0, 15).at(cube);
            var fixture = new FlatFixture(cube, cube.minBlockY(), Map.of(edit, AIR));
            var data = fixture.capture();
            Result result = compose(fixture, List.of(), data);
            assertComposedOracle(fixture, data, List.of(), result);
            Set<Integer> pages = new HashSet<>();
            result.batch().tiles().forEach((key, faces) -> {
                if (key.replacementIdentity().isEmpty()) return;
                assertTrue(key.originX() < 0);
                for (var p : faces) {
                    Cell solid = solidCell(global(key, p));
                    assertEquals(Math.floorDiv(solid.y, 2048), key.pageY());
                    assertEquals(key.pageY(), p.pageY2048());
                    assertEquals(Math.floorDiv(solid.x, 32) * 32, key.originX());
                    assertEquals(Math.floorDiv(solid.z, 32) * 32, key.originZ());
                    pages.add(key.pageY());
                    for (int value : new int[]{p.x(), p.y(), p.z(), p.width(), p.height()})
                        assertEquals(value, Short.toUnsignedInt((short) value));
                }
            });
            assertEquals(Set.of(cubeY / 128 - 1, cubeY / 128), pages,
                    "The bottom-corner excavation owns faces on both signed pages");
        }
    }

    @Test void exactSharedBytesAndFacesRejectWithoutMutatingLastSafeBoundaryResult() {
        CubePos cube = new CubePos(-1, -128, -1);
        var edits = new HashMap<Cell, Integer>();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            edits.put(new Cell(cube.minBlockX() + x, cube.minBlockY() + y, cube.minBlockZ() + z),
                    ((x + y + z) & 1) == 0 ? DIORITE : AIR);
        var fixture = new FlatFixture(cube, cube.minBlockY() + 15, edits);
        var data = fixture.capture();
        Result safe = compose(fixture, List.of(), data);
        assertComposedOracle(fixture, data, List.of(), safe);
        long exact = RESULT_OVERHEAD_BYTES + data.declaredBytes() + safe.batch().faces() * DECLARED_BYTES_PER_FACE;
        assertEquals(safe, build(fixture, cube.minBlockX(), cube.minBlockZ(), DhLodLayout.LONG_4096, List.of(), data, exact, LIVE));
        assertThrows(Declined.class, () -> build(fixture, cube.minBlockX(), cube.minBlockZ(), DhLodLayout.LONG_4096,
                List.of(), data, exact - 1, LIVE));
        // Even fully native-hidden replacement faces must be reserved before allocation.
        int sourceFaces = safe.replacementSourceFaces();
        assertThrows(DhVoxelReplacementGeometry.BudgetExceeded.class, () -> DhVoxelReplacementGeometry.build(
                data.capture(), sourceFaces - 1, sourceFaces * 64L, LIVE));
        assertThrows(DhVoxelReplacementGeometry.BudgetExceeded.class, () -> DhVoxelReplacementGeometry.build(
                data.capture(), sourceFaces, sourceFaces * 64L - 1, LIVE));
        assertThrows(Declined.class, () -> build(fixture, cube.minBlockX(), cube.minBlockZ(), DhLodLayout.LONG_4096,
                List.of(), data, MAX_RESULT_BYTES + 1, LIVE));
        assertEquals(safe, compose(fixture, List.of(), data));
        assertThrows(UnsupportedOperationException.class, () -> safe.batch().tiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> safe.batch().tiles().values().iterator().next().clear());
        System.out.println("008 shared-pool boundary fixture: sourceFaces=" + sourceFaces + ", totalFaces=" + safe.batch().faces()
                + ", owners=" + safe.batch().tiles().size() + ", exactPreparedBytes=" + exact);
    }

    @Test void fullyNativeHiddenBoundaryReplacementStillReservesOriginalSourceFaces() {
        CubePos cube = new CubePos(0, 1, 0);
        var edits = new HashMap<Cell, Integer>();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            edits.put(new Cell(x, cube.minBlockY() + y, z), ((x + y + z) & 1) == 0 ? DIORITE : AIR);
        var fixture = new FlatFixture(cube, 31, edits);
        var data = fixture.capture();
        // A fully hidden mesh is still charged for source faces before replacement allocation.
        var allReady = changingMasks(cube).get(4);
        Result hidden = compose(fixture, allReady, data);
        assertEquals(0, hidden.replacementVisibleFaces());
        assertTrue(hidden.replacementSourceFaces() > 0);
        long visibleOnly = RESULT_OVERHEAD_BYTES + data.declaredBytes() + hidden.batch().faces() * DECLARED_BYTES_PER_FACE;
        assertThrows(Declined.class, () -> build(fixture, 0, 0, DhLodLayout.LONG_4096, allReady, data, visibleOnly, LIVE));
        assertEquals(hidden, compose(fixture, allReady, data));
        System.out.println("008 hidden boundary source reservation: sourceFaces=" + hidden.replacementSourceFaces()
                + ", visibleReplacementFaces=0, visibleOnlyBudgetRejected=" + visibleOnly);
    }

    @Test void combinedOwnerPoolRejectsBoundaryPlacementBeyondExistingSixtyFourOwners() {
        CubePos cube = new CubePos(0, 1, 0);
        Cell boundary = new Boundary(0, 0, 0).at(cube);
        var fixture = new FlatFixture(cube, 14, Map.of(boundary, DIORITE));
        var data = fixture.capture();
        SceneSurfaceSampler sparse = new SceneSurfaceSampler() {
            public String identity() { return "boundary-combined-owner-cap"; }
            public boolean supportsFootprintCoverage() { return true; }
            public boolean knownFootprint(long x, long z, long maxX, long maxZ) {
                long localX = Math.floorMod(x, 256), localZ = Math.floorMod(z, 256);
                return maxX - x == 16 && maxZ - z == 16 && localZ == 64
                        && localX >= 64 && localX <= 160 && (localX - 64) % 32 == 0;
            }
            public long surfaceY(long x, long z) { return 100 + (Math.floorMod(x, 256) - 72) / 32 * 2048; }
            public int argb(long x, long z, long y) { return STONE_ARGB; }
        };
        var base = new DhAdaptiveTerrainData(sparse, 0, 0, DhLodLayout.FIXED_512)
                .clip(List.of(), List.of(cube), List.of(data.capture().ownedVolume()), LIVE);
        assertEquals(64, base.tiles().size());
        assertEquals(64, base.faces());
        Declined decline = assertThrows(Declined.class, () -> build(sparse, 0, 0, DhLodLayout.FIXED_512,
                List.of(), data, MAX_RESULT_BYTES, LIVE));
        assertTrue(decline.getMessage().contains("Combined base/replacement owner budget"));
        assertEquals(base, new DhAdaptiveTerrainData(sparse, 0, 0, DhLodLayout.FIXED_512)
                .clip(List.of(), List.of(cube), List.of(data.capture().ownedVolume()), LIVE));
    }

    @Test void absentExplicitNeighborHaloFailsClosedAndCannotInventBoundaryAir() {
        CubePos cube = new CubePos(-1, 8, -1);
        Cell edit = new Boundary(0, 0, 0).at(cube);
        var fixture = new FlatFixture(cube, 145, Map.of(edit, AIR));
        Map<CubePos, ImmutableCube> incomplete = new HashMap<>(fixture.cubes);
        incomplete.remove(cube.offset(-1, -1, -1));
        assertThrows(IllegalStateException.class, () -> DhVoxelReplacementGeometry.capture(cube,
                (x, y, z) -> read(incomplete, x, y, z), LIVE));
        assertThrows(IllegalArgumentException.class, () -> new FlatFixture(cube, 145,
                Map.of(new Cell(cube.minBlockX() - 1, cube.minBlockY(), cube.minBlockZ()), AIR)));
    }

    /** Same FLAT equation as AnalyticNativeCubeProvider, materialized into explicit immutable cubes. */
    private static final class FlatFixture implements SceneSurfaceSampler {
        final CubePos cube;
        final long surface;
        final Map<Cell, Integer> edits;
        final Map<CubePos, ImmutableCube> cubes;
        FlatFixture(CubePos cube, long surface, Map<Cell, Integer> edits) {
            this.cube = cube; this.surface = surface; this.edits = Map.copyOf(edits);
            for (var e : edits.entrySet()) if (!e.getKey().cube().equals(cube) || e.getValue() < AIR || e.getValue() > DIORITE)
                throw new IllegalArgumentException("Edits belong only to the original cube and pinned three-state palette");
            var generated = new LinkedHashMap<CubePos, ImmutableCube>();
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                CubePos owner = cube.offset(dx, dy, dz);
                int[] states = new int[4096];
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var cell = new Cell(owner.minBlockX() + x, owner.minBlockY() + y, owner.minBlockZ() + z);
                    states[CubePos.index(x, y, z)] = edits.getOrDefault(cell, cell.y <= surface ? STONE : AIR);
                }
                generated.put(owner, ImmutableCube.copyOf(states));
            }
            cubes = Map.copyOf(generated);
            assertEquals(27, cubes.size());
        }
        int argbAt(long x, long y, long z) { return read(cubes, x, y, z); }
        DhReplacementInput.Data capture() {
            var capture = DhVoxelReplacementGeometry.capture(cube, this::argbAt, LIVE);
            var certificate = new DhReplacementInput.Certificate(WORLD, 1, 1, 1, cube, 1,
                    "a".repeat(64), "b".repeat(64), DhReplacementInput.ANALYTIC_MATERIAL_POLICY);
            return new DhReplacementInput(certificate, capture, GUARD).data();
        }
        public String identity() { return "boundary-008-opaque-flat:" + surface; }
        public long surfaceY(long x, long z) { return surface; }
        public int argb(long x, long z, long y) { return STONE_ARGB; }
        public boolean supportsFootprintCoverage() { return true; }
        public boolean knownFootprint(long x, long z, long maxX, long maxZ) { return true; }
    }
    private static int read(Map<CubePos, ImmutableCube> cubes, long x, long y, long z) {
        var cube = cubes.get(CubePos.fromBlock(x, y, z));
        if (cube == null) throw new IllegalStateException("Unknown explicit halo cube");
        return switch (cube.get(CubePos.local(x), CubePos.local(y), CubePos.local(z))) {
            case AIR -> 0;
            case STONE -> STONE_ARGB;
            case DIORITE -> DIORITE_ARGB;
            default -> throw new IllegalArgumentException("Unsupported replacement material");
        };
    }
    private static List<List<CubePos>> changingMasks(CubePos cube) {
        var all = new ArrayList<CubePos>();
        for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            CubePos next = cube.offset(x, y, z);
            if (next.minBlockY() >= MIN_Y && next.minBlockY() + 16 <= MAX_Y_EXCLUSIVE) all.add(next);
        }
        return List.of(List.of(), List.of(cube), List.of(cube.offset(-1, 0, 0)),
                List.of(cube.offset(0, cube.y() == 553 ? -2 : 2, 0)), all, List.of());
    }
    private static Result compose(FlatFixture fixture, List<CubePos> ready, DhReplacementInput.Data data) {
        return build(fixture, fixture.cube.minBlockX(), fixture.cube.minBlockZ(), DhLodLayout.LONG_4096,
                ready, data, MAX_RESULT_BYTES, LIVE);
    }
    private static Volume region(CubePos cube) {
        return new Volume(cube.minBlockX() - 3, Math.max(MIN_Y, cube.minBlockY() - 3), cube.minBlockZ() - 3,
                cube.minBlockX() + 20, Math.min(MAX_Y_EXCLUSIVE, cube.minBlockY() + 20), cube.minBlockZ() + 20);
    }
    private static boolean inside(Volume volume, Cell cell) {
        return cell.x >= volume.minX() && cell.x < volume.maxXExclusive() && cell.y >= volume.minY()
                && cell.y < volume.maxYExclusive() && cell.z >= volume.minZ() && cell.z < volume.maxZExclusive();
    }
    private static Map<Unit, Integer> oracle(FlatFixture fixture, Volume region) {
        var expected = new HashMap<Unit, Integer>();
        for (long y = region.minY(); y < region.maxYExclusive(); y++) for (long z = region.minZ(); z < region.maxZExclusive(); z++)
            for (long x = region.minX(); x < region.maxXExclusive(); x++) {
                int color = fixture.argbAt(x, y, z);
                if (color == 0) continue;
                for (Neighbor neighbor : NEIGHBORS) if (fixture.argbAt(x + neighbor.x, y + neighbor.y, z + neighbor.z) == 0)
                    expected.put(new Unit(new Cell(x, y, z), neighbor.direction), color);
            }
        return expected;
    }
    private static void assertComposedOracle(FlatFixture fixture, DhReplacementInput.Data data, List<CubePos> ready, Result result) {
        Volume region = region(fixture.cube), owned = data.capture().ownedVolume();
        Map<Unit, Integer> full = oracle(fixture, region);
        Set<CubePos> mask = new HashSet<>(ready);
        var expectedLod = new HashMap<Unit, Integer>();
        var nativeOwned = new HashMap<Unit, Integer>();
        full.forEach((face, color) -> (mask.contains(face.solid.cube()) ? nativeOwned : expectedLod).put(face, color));
        var actual = new HashMap<Unit, Integer>();
        var actualReplacement = new HashMap<Unit, Integer>();
        long[] baseArea = {0};
        var baseFaces = new ArrayList<Face>();
        result.batch().tiles().forEach((key, faces) -> faces.forEach(page -> {
            Face face = global(key, page);
            boolean replacement = !key.replacementIdentity().isEmpty();
            if (replacement) {
                assertEquals(data.identity(), key.replacementIdentity());
                assertEquals(1, face.width()); assertEquals(1, face.height());
                Unit unit = new Unit(solidCell(face), face.direction());
                assertTrue(inside(owned, unit.solid));
                assertFalse(mask.contains(unit.solid.cube()));
                assertNull(actualReplacement.put(unit, face.argb()), "Duplicate replacement unit face");
            } else {
                assertEquals(Direction.UP, face.direction()); assertEquals(fixture.surface + 1, face.y());
                assertEquals(STONE_ARGB, face.argb());
                baseArea[0] += (long) face.width() * face.height();
                baseFaces.add(face);
            }
            for (Unit unit : unitsIn(face, region)) {
                assertEquals(replacement, inside(owned, unit.solid), "Exact XYZ ownership separates base and replacement");
                assertNull(actual.put(unit, face.argb()), "Duplicate base/replacement face at " + unit);
            }
        }));
        assertEquals(expectedLod, actual, "No composed LOD hole, extra face, wrong material, or incorrect native-mask ownership");
        var expectedReplacement = oracle(fixture, owned);
        int sourceCount = expectedReplacement.size();
        expectedReplacement.keySet().removeIf(f -> mask.contains(f.solid.cube()));
        assertEquals(expectedReplacement, actualReplacement);
        assertEquals(sourceCount, result.replacementSourceFaces());
        assertEquals(expectedReplacement.size(), result.replacementVisibleFaces());
        nativeOwned.forEach((face, color) -> assertNull(actual.put(face, color), "Native and LOD ownership cannot overlap"));
        assertEquals(full, actual, "Adding independently known native-owned faces closes every local unit-face hole");
        // Global flat top area is checked without expanding the 8192-square far view into 67 million cells.
        Set<Cell> suppressed = new HashSet<>();
        var volumes = new ArrayList<Volume>(); volumes.add(owned);
        for (CubePos cube : ready) volumes.add(new Volume(cube.minBlockX(), cube.minBlockY(), cube.minBlockZ(),
                cube.minBlockX() + 16, cube.minBlockY() + 16, cube.minBlockZ() + 16));
        for (Volume volume : volumes) if (fixture.surface >= volume.minY() && fixture.surface < volume.maxYExclusive())
            for (long z = volume.minZ(); z < volume.maxZExclusive(); z++) for (long x = volume.minX(); x < volume.maxXExclusive(); x++)
                suppressed.add(new Cell(x, fixture.surface, z));
        assertEquals(8192L * 8192 - suppressed.size(), baseArea[0], "Global unedited base has exactly the expected XYZ masks");
        assertFlatBasePartition(fixture, baseFaces, volumes);
        assertPooled(result, data);
    }
    /** Sweep all far-base rectangles, proving global disjointness without a 67-million-cell raster. */
    private static void assertFlatBasePartition(FlatFixture fixture, List<Face> faces, List<Volume> masks) {
        record Event(long z, boolean start, Face face) { }
        var events = new ArrayList<Event>();
        long centerX = DhLodLayout.LONG_4096.normalizeCenter(fixture.cube.minBlockX());
        long centerZ = DhLodLayout.LONG_4096.normalizeCenter(fixture.cube.minBlockZ());
        for (Face face : faces) {
            assertTrue(face.x() >= centerX - 4096 && face.x() + face.width() <= centerX + 4096);
            assertTrue(face.z() >= centerZ - 4096 && face.z() + face.height() <= centerZ + 4096);
            for (Volume mask : masks) if (fixture.surface >= mask.minY() && fixture.surface < mask.maxYExclusive())
                assertFalse(face.x() < mask.maxXExclusive() && face.x() + face.width() > mask.minX()
                        && face.z() < mask.maxZExclusive() && face.z() + face.height() > mask.minZ(),
                        "Base rectangle must not overlap any replacement or native-ready solid ownership");
            events.add(new Event(face.z(), true, face));
            events.add(new Event(face.z() + face.height(), false, face));
        }
        events.sort(Comparator.comparingLong(Event::z).thenComparing(Event::start)); // Half-open: remove before adding.
        var active = new TreeMap<Long, Face>();
        for (Event event : events) {
            Face face = event.face();
            if (!event.start()) {
                assertEquals(face, active.remove(face.x()));
                continue;
            }
            var before = active.floorEntry(face.x());
            var after = active.ceilingEntry(face.x());
            assertTrue(before == null || before.getKey() + before.getValue().width() <= face.x(), "Global base overlap on west side");
            assertTrue(after == null || after.getKey() >= face.x() + face.width(), "Global base overlap on east side");
            assertNull(active.put(face.x(), face));
        }
        assertTrue(active.isEmpty());
        // All rectangles are disjoint, inside the view, and outside every ownership mask.
        // The exact allowed-area equality above therefore also proves global base completeness.
    }

    private static void assertPooled(Result result, DhReplacementInput.Data data) {
        assertTrue(result.batch().faces() <= 32768);
        assertTrue(result.batch().tiles().size() <= 64);
        assertTrue(result.batch().sampledCells() <= 12288);
        assertEquals(result.batch().faces(), result.batch().tiles().values().stream().mapToInt(List::size).sum());
        assertTrue(result.batch().tiles().values().stream().allMatch(faces -> faces.size() <= 4096));
        long bytes = RESULT_OVERHEAD_BYTES + data.declaredBytes() + result.batch().faces() * DECLARED_BYTES_PER_FACE;
        assertTrue(bytes <= 5L * 1024 * 1024);
        assertTrue(bytes * 4 <= 20L * 1024 * 1024);
        assertTrue(result.batch().faces() * 88L * 2 <= 8L * 1024 * 1024);
        assertTrue(result.batch().tiles().size() * 2 <= 128);
    }
    private static Face global(DhGeographicLodData.TileKey key, DhTerrainGeometry.PageFace face) {
        assertEquals(key.pageY(), face.pageY2048());
        return new Face(face.direction(), key.originX() + face.x(), face.pageY2048() * 2048L + face.y(),
                key.originZ() + face.z(), face.width(), face.height(), face.argb());
    }
    private static Cell solidCell(Face face) {
        return new Cell(face.x() - (face.direction() == Direction.EAST ? 1 : 0),
                face.y() - (face.direction() == Direction.UP ? 1 : 0), face.z() - (face.direction() == Direction.SOUTH ? 1 : 0));
    }
    private static List<Unit> unitsIn(Face face, Volume volume) {
        Cell start = solidCell(face);
        var units = new ArrayList<Unit>();
        long u0, u1, v0, v1;
        switch (face.direction()) {
            case UP, DOWN -> {
                if (start.y < volume.minY() || start.y >= volume.maxYExclusive()) return units;
                u0 = Math.max(start.x, volume.minX()); u1 = Math.min(start.x + face.width(), volume.maxXExclusive());
                v0 = Math.max(start.z, volume.minZ()); v1 = Math.min(start.z + face.height(), volume.maxZExclusive());
                for (long z = v0; z < v1; z++) for (long x = u0; x < u1; x++) units.add(new Unit(new Cell(x, start.y, z), face.direction()));
            }
            case NORTH, SOUTH -> {
                if (start.z < volume.minZ() || start.z >= volume.maxZExclusive()) return units;
                u0 = Math.max(start.x, volume.minX()); u1 = Math.min(start.x + face.width(), volume.maxXExclusive());
                v0 = Math.max(start.y, volume.minY()); v1 = Math.min(start.y + face.height(), volume.maxYExclusive());
                for (long y = v0; y < v1; y++) for (long x = u0; x < u1; x++) units.add(new Unit(new Cell(x, y, start.z), face.direction()));
            }
            case WEST, EAST -> {
                if (start.x < volume.minX() || start.x >= volume.maxXExclusive()) return units;
                u0 = Math.max(start.z, volume.minZ()); u1 = Math.min(start.z + face.width(), volume.maxZExclusive());
                v0 = Math.max(start.y, volume.minY()); v1 = Math.min(start.y + face.height(), volume.maxYExclusive());
                for (long y = v0; y < v1; y++) for (long z = u0; z < u1; z++) units.add(new Unit(new Cell(start.x, y, z), face.direction()));
            }
        }
        return units;
    }
    private static Map<Unit, Integer> replacementUnits(Result result) {
        var units = new HashMap<Unit, Integer>();
        result.batch().tiles().forEach((key, faces) -> {
            if (!key.replacementIdentity().isEmpty()) for (var page : faces) {
                Face face = global(key, page);
                assertNull(units.put(new Unit(solidCell(face), face.direction()), face.argb()));
            }
        });
        return units;
    }
    private static boolean adjacentToEdit(Unit face, Map<Cell, Integer> edits) {
        Neighbor n = NEIGHBORS.stream().filter(next -> next.direction == face.direction).findFirst().orElseThrow();
        return edits.getOrDefault(new Cell(face.solid.x + n.x, face.solid.y + n.y, face.solid.z + n.z), STONE) == AIR;
    }
    private static Map<Unit, Integer> cavityFaces(Result result, Cell edited) {
        var cavity = replacementUnits(result);
        cavity.keySet().removeIf(f -> !adjacentToEdit(f, Map.of(edited, AIR)));
        return cavity;
    }
    private static String geometricUnit(Direction direction, int x, int y, int z) {
        int fixed = switch (direction) { case WEST, EAST -> 0; case UP, DOWN -> 1; case NORTH, SOUTH -> 2; };
        return fixed + ":" + x + "," + y + "," + z;
    }
}
