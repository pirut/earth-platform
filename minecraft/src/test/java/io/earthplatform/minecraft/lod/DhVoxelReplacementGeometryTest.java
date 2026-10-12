package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry.Capture;
import io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry.Mesh;
import io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry.VoxelSource;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent cell-face oracle only; no native publication or shader claim. */
class DhVoxelReplacementGeometryTest {
    private static final CubePos ORIGIN = new CubePos(0, 0, 0);
    private static final int STONE = 0xFF898987, GOLD = 0xFFF2C94C;
    private static final Runnable LIVE = () -> {};
    private record Cell(long x, long y, long z) { }
    private record Neighbor(Direction direction, int dx, int dy, int dz) { }
    private static final List<Neighbor> NEIGHBORS = List.of(
            new Neighbor(Direction.WEST, -1, 0, 0), new Neighbor(Direction.EAST, 1, 0, 0),
            new Neighbor(Direction.DOWN, 0, -1, 0), new Neighbor(Direction.UP, 0, 1, 0),
            new Neighbor(Direction.NORTH, 0, 0, -1), new Neighbor(Direction.SOUTH, 0, 0, 1));

    @Test void readsExactlyTwentyCubedFromAllTwentySevenNeighborsAndOwnsOnlyEighteenCubed() {
        Set<Cell> visited = new HashSet<>();
        Set<CubePos> dependencies = new HashSet<>();
        var capture = capture(new CubePos(-7, -2, 3), (x, y, z) -> {
            assertTrue(visited.add(new Cell(x, y, z)), "No duplicate read");
            dependencies.add(CubePos.fromBlock(x, y, z));
            return STONE;
        }, LIVE);
        assertEquals(8000, visited.size());
        assertEquals(27, dependencies.size());
        assertEquals(8000, capture.cellCount());
        assertEquals(32000, capture.declaredBytes());
        var owned = capture.ownedVolume();
        assertEquals(18, owned.maxXExclusive() - owned.minX());
        assertEquals(18, owned.maxYExclusive() - owned.minY());
        assertEquals(18, owned.maxZExclusive() - owned.minZ());
        var read = capture.readBounds();
        assertEquals(owned.minX() - 1, read.minX());
        assertEquals(owned.minY() - 1, read.minY());
        assertEquals(owned.minZ() - 1, read.minZ());
        assertEquals(owned.maxXExclusive() + 1, read.maxXExclusive());
        assertEquals(owned.maxYExclusive() + 1, read.maxYExclusive());
        assertEquals(owned.maxZExclusive() + 1, read.maxZExclusive());
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            assertTrue(dependencies.contains(capture.editedCube().offset(dx, dy, dz)));
        }
        assertEquals(0, buildAll(capture).faceCount(), "A solid read halo must not invent outer caps");
        assertThrows(IndexOutOfBoundsException.class, () -> capture.argbAt(read.minX() - 1, read.minY(), read.minZ()));
        assertThrows(IndexOutOfBoundsException.class, () -> capture.argbAt(read.maxXExclusive(), read.minY(), read.minZ()));
    }

    @Test void allTwoCubedOccupancyPatternsMatchIndependentLocalThreeDimensionalFaceOracle() {
        for (int mask = 0; mask < 256; mask++) {
            final int pattern = mask;
            VoxelSource source = (x, y, z) -> {
                if (x < 7 || x > 8 || y < 7 || y > 8 || z < 7 || z > 8) return KNOWN_AIR;
                int bit = (int) ((y - 7) * 4 + (z - 7) * 2 + x - 7);
                return (pattern & (1 << bit)) == 0 ? KNOWN_AIR : (bit % 2 == 0 ? STONE : GOLD);
            };
            assertOracle(ORIGIN, source);
        }
    }

    @Test void excavationAtEveryCubeFaceEdgeAndCornerRetainsNeighborOwnedExposedFaces() {
        int boundaries = 0;
        for (int y : new int[]{0, 7, 15}) for (int z : new int[]{0, 7, 15}) for (int x : new int[]{0, 7, 15}) {
            if (x == 7 && y == 7 && z == 7) continue;
            boundaries++;
            VoxelSource source = (rx, ry, rz) -> rx == x && ry == y && rz == z ? KNOWN_AIR : STONE;
            Mesh mesh = assertOracle(ORIGIN, source);
            assertEquals(6, mesh.faceCount());
            long neighboringOwners = mesh.faces().stream().map(DhVoxelReplacementGeometryTest::solidCell)
                    .filter(cell -> !CubePos.fromBlock(cell.x, cell.y, cell.z).equals(ORIGIN)).count();
            int expectedShell = (x == 0 || x == 15 ? 1 : 0) + (y == 0 || y == 15 ? 1 : 0) + (z == 0 || z == 15 ? 1 : 0);
            assertEquals(expectedShell, neighboringOwners);
        }
        assertEquals(26, boundaries);
    }

    @Test void floatingBlocksCavitiesOpenExcavationsRoofsAndWallsAreExactInThreeDimensions() {
        List<VoxelSource> fixtures = List.of(
                (x, y, z) -> x == 8 && y == 8 && z == 8 ? GOLD : KNOWN_AIR,
                (x, y, z) -> x >= 5 && x <= 10 && y >= 5 && y <= 10 && z >= 5 && z <= 10 ? KNOWN_AIR : STONE,
                (x, y, z) -> x <= 8 && y >= 7 && y <= 9 && z >= 7 && z <= 9 ? KNOWN_AIR : STONE,
                (x, y, z) -> (y == 12 && x >= 2 && x <= 13 && z >= 2 && z <= 13)
                        || (x == 2 && y >= 3 && y <= 12 && z >= 2 && z <= 13) ? STONE : KNOWN_AIR);
        for (VoxelSource fixture : fixtures) assertOracle(ORIGIN, fixture);
    }

    @Test void deterministicRandomFullHaloFieldsMatchOracleWithoutDuplicatesOrUnownedFaces() {
        Random random = new Random(0x3DC011A4L);
        for (int trial = 0; trial < 40; trial++) {
            CubePos cube = new CubePos(random.nextInt(8) - 4, random.nextInt(20) - 10, random.nextInt(8) - 4);
            int[] cells = new int[MAX_READ_CELLS];
            for (int i = 0; i < cells.length; i++) cells[i] = random.nextInt(4) == 0 ? KNOWN_AIR : (random.nextBoolean() ? STONE : GOLD);
            VoxelSource source = (x, y, z) -> cells[(int) (((y - cube.minBlockY() + 2) * 20 + z - cube.minBlockZ() + 2) * 20 + x - cube.minBlockX() + 2)];
            assertOracle(cube, source);
        }
    }

    @Test void outsideOwnedShellOnlyOccludesAndNeverContributesItsOwnFaces() {
        for (Neighbor n : NEIGHBORS) {
            long x = n.dx < 0 ? -2 : n.dx > 0 ? 17 : 7;
            long y = n.dy < 0 ? -2 : n.dy > 0 ? 17 : 7;
            long z = n.dz < 0 ? -2 : n.dz > 0 ? 17 : 7;
            VoxelSource outsideOnly = (rx, ry, rz) -> rx == x && ry == y && rz == z ? STONE : KNOWN_AIR;
            assertEquals(0, assertOracle(ORIGIN, outsideOnly).faceCount());
            VoxelSource pair = (rx, ry, rz) -> (rx == x && ry == y && rz == z)
                    || (rx == x - n.dx && ry == y - n.dy && rz == z - n.dz) ? STONE : KNOWN_AIR;
            assertEquals(5, assertOracle(ORIGIN, pair).faceCount(), "Halo solid must occlude its neighboring owned solid");
        }
    }

    @Test void nativeYEndpointsReadExplicitPaddingAndNeverInventSolidOrAirCaps() {
        for (int cubeY : new int[]{-688, 553}) {
            CubePos cube = new CubePos(-1, cubeY, -1);
            Capture solid = capture(cube, (x, y, z) -> STONE, LIVE);
            assertEquals(7600, solid.cellCount());
            assertEquals(30400, solid.declaredBytes());
            assertEquals(19, solid.readBounds().sizeY());
            assertEquals(17, solid.ownedVolume().maxYExclusive() - solid.ownedVolume().minY());
            assertEquals(0, buildAll(solid).faceCount());
            VoxelSource certifiedAirPadding = (x, y, z) -> y < DhTerrainGeometry.MIN_Y || y >= DhTerrainGeometry.MAX_Y_EXCLUSIVE ? KNOWN_AIR : STONE;
            Mesh bounded = assertOracle(cube, certifiedAirPadding);
            assertEquals(18 * 18, bounded.faceCount());
            for (Face face : bounded.faces()) {
                assertEquals(cubeY < 0 ? Direction.DOWN : Direction.UP, face.direction());
                assertEquals(cubeY < 0 ? DhTerrainGeometry.MIN_Y : DhTerrainGeometry.MAX_Y_EXCLUSIVE, face.y());
            }
            assertThrows(IllegalArgumentException.class, () -> capture(cube,
                    (x, y, z) -> y < DhTerrainGeometry.MIN_Y || y >= DhTerrainGeometry.MAX_Y_EXCLUSIVE ? 1 : STONE, LIVE));
        }
    }

    @Test void signedPageBoundariesAndBothNativeEndpointsRoundTripEveryUnitFace() {
        for (int cubeY : new int[]{-688, -640, -512, -384, -256, -128, 0, 128, 256, 384, 512, 553}) {
            CubePos cube = new CubePos(-1, cubeY, -1);
            Capture capture = capture(cube, (x, y, z) -> ((x + y + z) & 1) == 0 ? STONE : KNOWN_AIR, LIVE);
            Mesh mesh = buildAll(capture);
            Set<Face> restored = new HashSet<>();
            for (Face face : mesh.faces()) {
                var pages = DhTerrainGeometry.splitPages(face, capture.readBounds().minX(), capture.readBounds().minZ());
                assertEquals(1, pages.size());
                var page = pages.getFirst();
                assertEquals(Math.floorDiv(solidCell(face).y, 2048), page.pageY2048());
                for (int field : new int[]{page.x(), page.y(), page.z(), page.width(), page.height()}) assertEquals(field, Short.toUnsignedInt((short) field));
                restored.add(new Face(page.direction(), capture.readBounds().minX() + page.x(),
                        page.pageY2048() * 2048L + page.y(), capture.readBounds().minZ() + page.z(), page.width(), page.height(), page.argb()));
            }
            assertEquals(new HashSet<>(mesh.faces()), restored);
        }
    }

    @Test void checkerboardIsCountedExactlyAndRejectedSafelyUnderExistingSingleOwnerCap() {
        Capture capture = capture(ORIGIN, (x, y, z) -> ((x + y + z) & 1) == 0 ? STONE : KNOWN_AIR, LIVE);
        Mesh mesh = buildAll(capture);
        assertEquals(17496, mesh.faceCount());
        assertEquals(17496L * 64, mesh.vertexBytes());
        AtomicInteger countingChecks = new AtomicInteger();
        assertThrows(BudgetExceeded.class, () -> build(capture, 4096, MAX_VERTEX_BYTES, countingChecks::incrementAndGet));
        assertEquals(1 + 18 * 18 * 18, countingChecks.get(), "Reject after counting and before output allocation/emission checkpoints");
        assertThrows(IllegalArgumentException.class, () -> build(capture, MAX_FACES, mesh.vertexBytes() - 1, LIVE));
        assertEquals(mesh.faces(), build(capture, mesh.faceCount(), mesh.vertexBytes(), LIVE).faces());
        assertEquals(mesh.faces(), buildAll(capture).faces(), "Budget rejection cannot mutate the previous safe mesh");
    }

    @Test void exactFaceAndByteBudgetsAcceptEmptyAndRejectRatherThanTruncate() {
        Capture empty = capture(ORIGIN, (x, y, z) -> KNOWN_AIR, LIVE);
        assertEquals(0, build(empty, 0, 0, LIVE).faceCount());
        Capture single = capture(ORIGIN, (x, y, z) -> x == 0 && y == 0 && z == 0 ? GOLD : KNOWN_AIR, LIVE);
        assertEquals(6, build(single, 6, 384, LIVE).faceCount());
        assertThrows(IllegalArgumentException.class, () -> build(single, 5, 384, LIVE));
        assertThrows(IllegalArgumentException.class, () -> build(single, 6, 383, LIVE));
        for (int bad : new int[]{-1, MAX_FACES + 1, Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class, () -> build(empty, bad, 0, LIVE));
        for (long bad : new long[]{-1, MAX_VERTEX_BYTES + 1, Long.MAX_VALUE}) assertThrows(IllegalArgumentException.class, () -> build(empty, 0, bad, LIVE));
    }

    @Test void unknownAndUnsupportedCellsAnywhereInCompleteReadHaloFailClosed() {
        for (int unsupported : new int[]{1, 0x00FFFFFF, 0x80898987}) {
            for (Cell bad : List.of(new Cell(-2, -2, -2), new Cell(8, 8, 8), new Cell(17, 17, 17))) {
                assertThrows(IllegalArgumentException.class, () -> capture(ORIGIN,
                        (x, y, z) -> x == bad.x && y == bad.y && z == bad.z ? unsupported : STONE, LIVE));
            }
        }
        var failure = new IllegalStateException("Missing source cube");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> capture(ORIGIN, (x, y, z) -> { throw failure; }, LIVE)));
    }

    @Test void captureDoesNotRetainSourceOrLeaseAndContentIdentityBindsHaloAndCoordinates() {
        AtomicInteger reads = new AtomicInteger();
        int[] externalValue = {STONE};
        VoxelSource mutableSource = (x, y, z) -> { reads.incrementAndGet(); return externalValue[0]; };
        Capture original = capture(ORIGIN, mutableSource, LIVE);
        String digest = original.contentDigest();
        assertEquals(64, digest.length());
        assertEquals(8000, reads.get());
        externalValue[0] = KNOWN_AIR;
        assertEquals(STONE, original.argbAt(0, 0, 0));
        assertEquals(0, buildAll(original).faceCount());
        assertEquals(8000, reads.get(), "Build must not consult retired source captures");
        assertEquals(digest, capture(ORIGIN, (x, y, z) -> STONE, LIVE).contentDigest());
        assertNotEquals(digest, capture(ORIGIN, mutableSource, LIVE).contentDigest());
        assertNotEquals(digest, capture(new CubePos(1, 0, 0), (x, y, z) -> STONE, LIVE).contentDigest());
        assertNotEquals(digest, capture(ORIGIN, (x, y, z) -> x == -2 && y == -2 && z == -2 ? GOLD : STONE, LIVE).contentDigest());
        assertEquals(digest, original.contentDigest());
        assertThrows(UnsupportedOperationException.class, () -> buildAll(original).faces().add(new Face(Direction.UP, 0, 1, 0, 1, 1, GOLD)));
        for (var method : Capture.class.getMethods()) assertFalse(method.getReturnType().isArray(), "Mutable arrays cannot escape");
    }

    @Test void cancellationIsDeterministicDuringCaptureCountingEmissionAndFinalReturn() {
        for (int cancelAt : new int[]{1, 2, 3, 4002, 8003}) {
            AtomicInteger checks = new AtomicInteger(), reads = new AtomicInteger();
            var cancelled = new IllegalStateException("cancel capture " + cancelAt);
            assertSame(cancelled, assertThrows(IllegalStateException.class, () -> capture(ORIGIN,
                    (x, y, z) -> { reads.incrementAndGet(); return STONE; },
                    () -> { if (checks.incrementAndGet() == cancelAt) throw cancelled; })));
            assertEquals(cancelAt, checks.get());
            assertEquals(Math.min(8000, Math.max(0, cancelAt - 3)), reads.get());
        }
        Capture capture = capture(ORIGIN, (x, y, z) -> ((x + y + z) & 1) == 0 ? STONE : KNOWN_AIR, LIVE);
        int ownedCells = 18 * 18 * 18;
        for (int cancelAt : new int[]{1, 2, ownedCells + 2, ownedCells + 3, ownedCells * 2 + 3}) {
            AtomicInteger checks = new AtomicInteger();
            var cancelled = new IllegalStateException("cancel mesh " + cancelAt);
            assertSame(cancelled, assertThrows(IllegalStateException.class, () -> build(capture, MAX_FACES, MAX_VERTEX_BYTES,
                    () -> { if (checks.incrementAndGet() == cancelAt) throw cancelled; })));
            assertEquals(cancelAt, checks.get());
        }
        assertEquals(17496, buildAll(capture).faceCount());
    }

    @Test void invalidOriginsReadExtentsAndNullsRejectBeforeSourceReads() {
        AtomicInteger reads = new AtomicInteger();
        VoxelSource source = (x, y, z) -> { reads.incrementAndGet(); return STONE; };
        for (CubePos invalid : List.of(new CubePos(0, -689, 0), new CubePos(0, 554, 0),
                new CubePos(Integer.MIN_VALUE, 0, 0), new CubePos(Integer.MAX_VALUE, 0, 0),
                new CubePos(0, 0, Integer.MIN_VALUE), new CubePos(0, 0, Integer.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class, () -> capture(invalid, source, LIVE));
        }
        assertEquals(0, reads.get());
        for (int x : new int[]{Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1}) {
            Capture valid = capture(new CubePos(x, 0, x), (rx, ry, rz) -> KNOWN_AIR, LIVE);
            assertEquals(8000, valid.cellCount());
        }
        assertThrows(IllegalArgumentException.class, () -> new ReadBounds(Long.MIN_VALUE, 0, 0, Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReadBounds(0, 0, 0, 21, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReadBounds(0, 0, 0, 0, 1, 1));
        assertThrows(NullPointerException.class, () -> capture(null, source, LIVE));
        assertThrows(NullPointerException.class, () -> capture(ORIGIN, null, LIVE));
        assertThrows(NullPointerException.class, () -> capture(ORIGIN, source, null));
        assertThrows(NullPointerException.class, () -> build(null, 0, 0, LIVE));
    }

    private static Mesh buildAll(Capture capture) { return build(capture, MAX_FACES, MAX_VERTEX_BYTES, LIVE); }

    private static Mesh assertOracle(CubePos cube, VoxelSource source) {
        Capture capture = capture(cube, source, LIVE);
        Mesh mesh = buildAll(capture);
        Set<Face> expected = new HashSet<>();
        long minY = Math.max(DhTerrainGeometry.MIN_Y, cube.minBlockY() - 1);
        long maxY = Math.min(DhTerrainGeometry.MAX_Y_EXCLUSIVE, cube.minBlockY() + 17);
        for (long y = minY; y < maxY; y++) for (long z = cube.minBlockZ() - 1; z < cube.minBlockZ() + 17; z++) {
            for (long x = cube.minBlockX() - 1; x < cube.minBlockX() + 17; x++) {
                int color = source.argbAt(x, y, z);
                if (color == KNOWN_AIR) continue;
                for (Neighbor n : NEIGHBORS) if (source.argbAt(x + n.dx, y + n.dy, z + n.dz) == KNOWN_AIR) {
                    expected.add(new Face(n.direction, x + Math.max(0, n.dx), y + Math.max(0, n.dy),
                            z + Math.max(0, n.dz), 1, 1, color));
                }
            }
        }
        Set<Face> actual = new HashSet<>(mesh.faces());
        assertEquals(mesh.faceCount(), actual.size(), "Duplicate faces");
        assertEquals(expected, actual);
        for (Face face : mesh.faces()) {
            assertEquals(1, face.width()); assertEquals(1, face.height());
            Cell cell = solidCell(face);
            assertTrue(capture.ownedVolume().contains(cell.x, cell.y, cell.z), "No read-only halo ownership");
        }
        assertEquals(mesh.faceCount() * 64L, mesh.vertexBytes());
        assertEquals(capture.ownedVolume(), mesh.ownedVolume());
        return mesh;
    }

    private static Cell solidCell(Face face) {
        return new Cell(face.x() - (face.direction() == Direction.EAST ? 1 : 0),
                face.y() - (face.direction() == Direction.UP ? 1 : 0),
                face.z() - (face.direction() == Direction.SOUTH ? 1 : 0));
    }
}
