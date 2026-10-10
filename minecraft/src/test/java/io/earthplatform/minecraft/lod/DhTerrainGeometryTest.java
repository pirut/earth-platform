package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.PageFace;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cell-exact, dependency-free checks; these do not claim a running DH renderer. */
class DhTerrainGeometryTest {
    private static final int COLOR = 0xD38A73C5;

    @Test void oneInteriorCubeSubtractsFourDisjointStripsInEveryOrientation() {
        for (Direction direction : Direction.values()) {
            Face source = at(direction, -8, -8, 8, 32, 32);
            var pieces = clip(source, List.of(new CubePos(0, 0, 0)), 4);
            assertEquals(4, pieces.size());
            assertEquals(32 * 32 - 16 * 16, area(pieces));
            assertCellExact(source, List.of(new CubePos(0, 0, 0)), pieces);
        }
    }

    @Test void deterministicRandomRectanglesMatchIndependentThreeDimensionalCellOracle() {
        Random random = new Random(0xD157A47L);
        for (Direction direction : Direction.values()) {
            for (int iteration = 0; iteration < 300; iteration++) {
                long u = random.nextInt(81) - 40, v = random.nextInt(81) - 40;
                long plane = (random.nextInt(5) - 2) * 16L + random.nextInt(3) - 1;
                Face source = at(direction, u, v, plane, random.nextInt(64) + 1, random.nextInt(64) + 1);
                var ready = new ArrayList<CubePos>();
                for (int i = 0, n = random.nextInt(33); i < n; i++) {
                    ready.add(new CubePos(random.nextInt(9) - 4, random.nextInt(9) - 4, random.nextInt(9) - 4));
                }
                if (!ready.isEmpty() && iteration % 3 == 0) ready.add(ready.getFirst());
                var pieces = clip(source, ready, MAX_PIECES);
                assertCellExact(source, ready, pieces);
                var packedAndRestored = new ArrayList<Face>();
                for (Face piece : pieces) for (PageFace page : splitPages(piece, -64, -64)) {
                    assertPackingSafe(page);
                    packedAndRestored.add(fromPage(page, -64, -64));
                }
                assertCellExact(source, ready, packedAndRestored);
                Collections.shuffle(ready, random);
                assertEquals(pieces, clip(source, ready, MAX_PIECES), "Ready order must not change the result");
            }
        }
    }

    @Test void solidSideOwnsBoundaryForAllSixDirectionsIncludingNegativeCoordinates() {
        for (Direction direction : Direction.values()) {
            for (int min : new int[]{-32, 0, 32}) {
                int index = min / 16;
                CubePos cube = switch (direction) {
                    case UP, DOWN -> new CubePos(0, index, 0);
                    case NORTH, SOUTH -> new CubePos(0, 0, index);
                    case WEST, EAST -> new CubePos(index, 0, 0);
                };
                for (long plane : new long[]{min - 1L, min, min + 1L, min + 15L, min + 16L, min + 17L}) {
                    Face source = at(direction, 0, 0, plane, 16, 16);
                    boolean positive = positive(direction);
                    boolean owns = positive ? min < plane && plane <= min + 16 : min <= plane && plane < min + 16;
                    var result = clip(source, List.of(cube), 1);
                    assertEquals(owns ? List.of() : List.of(source), result, direction + " plane " + plane);
                }
            }
        }
    }

    @Test void sameXZAtDifferentYDoesNotSuppressAndUnlistedCubesStayUnknown() {
        Face face = new Face(Direction.UP, 0, 17, 0, 16, 16, COLOR);
        assertEquals(List.of(face), clip(face, List.of(new CubePos(0, 0, 0)), 1));
        assertEquals(List.of(face), clip(face, List.of(), 1));
        assertTrue(clip(face, List.of(new CubePos(0, 1, 0)), 1).isEmpty());
        Face wall = new Face(Direction.SOUTH, 0, -16, 16, 16, 48, COLOR);
        var result = clip(wall, List.of(new CubePos(0, 0, 0)), 2);
        assertEquals(2, result.size());
        assertEquals(16 * 32, area(result));
        assertCellExact(wall, List.of(new CubePos(0, 0, 0)), result);
    }

    @Test void publishedKnownAirCubeSuppressesItsExactVolumeAndNoSurroundingAirIsInvented() {
        // A published empty native mesh is represented by its ready CubePos,
        // just like a nonempty one. Unpublished/missing cubes are not listed.
        var publishedEmpty = List.of(new CubePos(0, 0, 0));
        Face inside = new Face(Direction.UP, 0, 16, 0, 16, 16, COLOR);
        assertTrue(clip(inside, publishedEmpty, 1).isEmpty());
        Face larger = new Face(Direction.UP, -16, 16, -16, 48, 48, COLOR);
        var result = clip(larger, publishedEmpty, 4);
        assertEquals(48 * 48 - 16 * 16, area(result));
        assertCellExact(larger, publishedEmpty, result);
    }

    @Test void adjacentAndDuplicateCoverageIsAnExactUnionIncludingAnLShapedHole() {
        Face source = new Face(Direction.UP, -8, 8, -8, 48, 48, COLOR);
        var ready = List.of(new CubePos(0, 0, 0), new CubePos(1, 0, 0), new CubePos(0, 0, 1));
        var result = clip(source, ready, MAX_PIECES);
        assertEquals(48 * 48 - 3 * 16 * 16, area(result));
        assertCellExact(source, ready, result);
        var repeated = new ArrayList<>(ready);
        repeated.addAll(ready); repeated.addAll(ready);
        assertEquals(result, clip(source, repeated, MAX_PIECES));
        Face rectangle = new Face(Direction.UP, 0, 8, 0, 32, 16, COLOR);
        assertTrue(clip(rectangle, List.of(ready.get(0), ready.get(1)), 1).isEmpty());
    }

    @Test void duplicateLimitIsBoundedAndIdempotentEvenAtMaximumReadiness() {
        Face source = new Face(Direction.UP, -8, 8, -8, 32, 32, COLOR);
        var duplicate = new CubePos(0, 0, 0);
        assertEquals(clip(source, List.of(duplicate), 4),
                clip(source, Collections.nCopies(MAX_READY_CUBES, duplicate), 4));
        assertThrows(IllegalArgumentException.class,
                () -> clip(source, Collections.nCopies(MAX_READY_CUBES + 1, duplicate), MAX_PIECES));
    }

    @Test void maximumDistinctReadinessRemainsBoundedAndCellExact() {
        Face source = new Face(Direction.UP, -256, 16, -256, 512, 512, COLOR);
        var ready = new ArrayList<CubePos>();
        for (int z = -16; z < 16; z += 2) for (int x = -16; x < 16; x += 2) {
            ready.add(new CubePos(x, 0, z));
        }
        assertEquals(MAX_READY_CUBES, ready.size());
        var result = clip(source, ready, MAX_PIECES);
        assertTrue(result.size() <= MAX_PIECES);
        assertEquals(512L * 512 - 256L * 16 * 16, area(result));
        assertCellExact(source, ready, result);
    }

    @Test void realNativeExtremesClipAndPackWithoutAliasingTheirPages() {
        for (int nativeY : new int[]{-11000, 8849}) {
            var cube = CubePos.fromBlock(-8, nativeY, -8);
            for (Direction direction : Direction.values()) {
                Face face = switch (direction) {
                    case UP -> new Face(direction, -8, nativeY + 1L, -8, 1, 1, COLOR);
                    case DOWN -> new Face(direction, -8, nativeY, -8, 1, 1, COLOR);
                    case NORTH, WEST -> new Face(direction, -8, nativeY, -8, 1, 1, COLOR);
                    case SOUTH -> new Face(direction, -8, nativeY, -7, 1, 1, COLOR);
                    case EAST -> new Face(direction, -7, nativeY, -8, 1, 1, COLOR);
                };
                assertTrue(clip(face, List.of(cube), 1).isEmpty());
                var pages = splitPages(face, -16, -16);
                assertEquals(1, pages.size());
                PageFace page = pages.getFirst();
                assertEquals(Math.floorDiv(nativeY, PAGE_HEIGHT), page.pageY2048());
                assertEquals(face.y(), (long) page.pageY2048() * PAGE_HEIGHT + page.y());
                assertEquals(face, fromPage(page, -16, -16));
            }
        }
    }

    @Test void clippingAtEverySignedPageAndEnvelopeEdgeRemainsCellExactAfterPacking() {
        for (int baseY : new int[]{MIN_Y, -10240, -8192, -6144, -4096, -2048, 0, 2048, 4096, 6144, 8192, 8832}) {
            for (Direction direction : Direction.values()) {
                Face source = switch (direction) {
                    case UP, DOWN -> new Face(direction, -8, baseY + 8L, -8, 48, 48, COLOR);
                    case NORTH, SOUTH -> new Face(direction, -8, baseY, 8, 48, 32, COLOR);
                    case WEST, EAST -> new Face(direction, 8, baseY, -8, 48, 32, COLOR);
                };
                var ready = List.of(new CubePos(0, baseY / 16, 0), new CubePos(0, baseY / 16 + 1, 0));
                var result = clip(source, ready, MAX_PIECES);
                var restored = new ArrayList<Face>();
                for (Face face : result) for (PageFace page : splitPages(face, -16, -16)) {
                    assertPackingSafe(page);
                    restored.add(fromPage(page, -16, -16));
                }
                assertCellExact(source, ready, restored);
            }
        }
    }

    @Test void everyHorizontalPageBoundaryUsesTheCorrectSolidSide() {
        for (int boundary = -10240; boundary <= 8192; boundary += PAGE_HEIGHT) {
            for (int delta : new int[]{-1, 0, 1}) {
                for (Direction direction : List.of(Direction.UP, Direction.DOWN)) {
                    long y = boundary + delta;
                    Face face = new Face(direction, -7, y, -9, 15, 17, COLOR);
                    PageFace page = splitPages(face, -16, -16).getFirst();
                    assertEquals(Math.floorDiv(y - (direction == Direction.UP ? 1 : 0), PAGE_HEIGHT), page.pageY2048());
                    assertEquals(face, fromPage(page, -16, -16));
                    if (delta == 0) assertEquals(direction == Direction.UP ? PAGE_HEIGHT : 0, page.y());
                    assertPackingSafe(page);
                }
            }
        }
    }

    @Test void everyVerticalPageBoundarySplitsHalfOpenIntervalsWithoutGapsOrDuplicates() {
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            for (int boundary = -10240; boundary <= 8192; boundary += PAGE_HEIGHT) {
                Face source = new Face(direction, -4, boundary - 7L, -3, 19, 20, COLOR);
                var result = splitPages(source, -16, -16);
                assertEquals(2, result.size());
                assertEquals(7, result.get(0).height());
                assertEquals(13, result.get(1).height());
                assertEquals(PAGE_HEIGHT - 7, result.get(0).y());
                assertEquals(0, result.get(1).y());
                assertPagePartition(source, result, -16, -16);
                if (boundary + PAGE_HEIGHT <= MAX_Y_EXCLUSIVE) {
                    Face exact = new Face(direction, -4, boundary, -3, 19, PAGE_HEIGHT, COLOR);
                    var exactResult = splitPages(exact, -16, -16);
                    assertEquals(1, exactResult.size());
                    assertEquals(0, exactResult.getFirst().y());
                    assertEquals(PAGE_HEIGHT, exactResult.getFirst().height());
                }
            }
        }
    }

    @Test void completeNativeEnvelopeHasElevenSignedPagesAndExactClippedEnds() {
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
            Face face = new Face(direction, -256, MIN_Y, -256, 512, MAX_Y_EXCLUSIVE - MIN_Y, COLOR);
            var pages = splitPages(face, -256, -256);
            assertEquals(11, pages.size());
            assertEquals(-6, pages.getFirst().pageY2048());
            assertEquals(1280, pages.getFirst().y());
            assertEquals(4, pages.getLast().pageY2048());
            assertEquals(672, pages.getLast().height());
            assertPagePartition(face, pages, -256, -256);
        }
    }

    @Test void fullTileEndpointsAndLongWorldOriginsStayUnsignedShortSafe() {
        for (long origin : new long[]{-8192, 0, 1L << 50, -(1L << 50), Long.MIN_VALUE}) {
            for (Direction direction : Direction.values()) {
                Face face = direction == Direction.UP || direction == Direction.DOWN
                        ? new Face(direction, origin, -1, origin, TILE_SIZE, TILE_SIZE, COLOR)
                        : new Face(direction, origin, -1, origin, TILE_SIZE, 2, COLOR);
                var pages = splitPages(face, origin, origin);
                assertPagePartition(face, pages, origin, origin);
            }
        }
        var edge = splitPages(new Face(Direction.EAST, 4096, 0, 0, 4096, 2048, COLOR), 0, 0).getFirst();
        assertEquals(4096, edge.x());
        assertPackingSafe(edge);
    }

    @Test void envelopeEndpointsAreAllowedOnlyOnTheirOwnedSolidSide() {
        assertEquals(MIN_Y, fromPage(splitPages(new Face(Direction.DOWN, 0, MIN_Y, 0, 1, 1, COLOR), 0, 0).getFirst(), 0, 0).y());
        assertEquals(MAX_Y_EXCLUSIVE, fromPage(splitPages(new Face(Direction.UP, 0, MAX_Y_EXCLUSIVE, 0, 1, 1, COLOR), 0, 0).getFirst(), 0, 0).y());
        assertThrows(IllegalArgumentException.class, () -> new Face(Direction.UP, 0, MIN_Y, 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new Face(Direction.DOWN, 0, MAX_Y_EXCLUSIVE, 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new Face(Direction.NORTH, 0, MIN_Y - 1, 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new Face(Direction.NORTH, 0, MAX_Y_EXCLUSIVE, 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new Face(Direction.NORTH, 0, MAX_Y_EXCLUSIVE - 1, 0, 1, 2, COLOR));
    }

    @Test void budgetsRejectInsteadOfReturningPartialGeometry() {
        Face source = new Face(Direction.UP, -8, 8, -8, 32, 32, COLOR);
        var ready = List.of(new CubePos(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> clip(source, ready, 3));
        assertEquals(4, clip(source, ready, 4).size());
        for (int invalid : new int[]{-1, 0, MAX_PIECES + 1, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> clip(source, List.of(), invalid));
        }
    }

    @Test void absentAndInvalidInputsAreRejectedEvenAfterFullCoverage() {
        Face source = new Face(Direction.UP, 0, 16, 0, 16, 16, COLOR);
        assertThrows(NullPointerException.class, () -> clip(null, List.of(), 1));
        assertThrows(NullPointerException.class, () -> clip(source, null, 1));
        assertThrows(NullPointerException.class, () -> splitPages(null, 0, 0));
        var nullAfterFullCover = new ArrayList<CubePos>();
        nullAfterFullCover.add(new CubePos(0, 0, 0)); nullAfterFullCover.add(null);
        assertThrows(NullPointerException.class, () -> clip(source, nullAfterFullCover, 1));
        for (CubePos outside : List.of(new CubePos(0, -689, 0), new CubePos(0, 554, 0))) {
            assertThrows(IllegalArgumentException.class, () -> clip(source, List.of(new CubePos(0, 0, 0), outside), 1));
        }
        assertThrows(NullPointerException.class, () -> new Face(null, 0, 0, 0, 1, 1, COLOR));
        for (int dimension : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Face(Direction.UP, 0, 1, 0, dimension, 1, COLOR));
            assertThrows(IllegalArgumentException.class, () -> new Face(Direction.UP, 0, 1, 0, 1, dimension, COLOR));
        }
        assertThrows(IllegalArgumentException.class, () -> new Face(Direction.UP, Long.MAX_VALUE, 1, 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new Face(Direction.UP, 0, 1, Long.MAX_VALUE, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new Face(Direction.EAST, 0, Long.MAX_VALUE, 0, 1, 1, COLOR));
    }

    @Test void invalidTileOriginsAndUnsafePageFieldsCannotWrap() {
        Face face = new Face(Direction.UP, 0, 1, 0, 16, 16, COLOR);
        assertThrows(IllegalArgumentException.class, () -> splitPages(face, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> splitPages(face, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> splitPages(face, -4096, 0));
        assertThrows(IllegalArgumentException.class, () -> splitPages(face, 0, -4096));
        assertThrows(IllegalArgumentException.class, () -> splitPages(face, Long.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> splitPages(new Face(Direction.UP, Long.MIN_VALUE, 1, 0, 1, 1, COLOR), Long.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> splitPages(new Face(Direction.NORTH, 0, 0, 0, TILE_SIZE + 1, 1, COLOR), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PageFace(Direction.UP, 0, 0, 0, 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new PageFace(Direction.DOWN, 0, 0, PAGE_HEIGHT, 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new PageFace(Direction.NORTH, 0, 0, PAGE_HEIGHT - 1, 0, 1, 2, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new PageFace(Direction.UP, 0, TILE_SIZE, 1, 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new PageFace(Direction.UP, 0, -1, 1, 0, 1, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new PageFace(Direction.UP, 0, 0, 1, 0, Integer.MAX_VALUE, 1, COLOR));
        assertThrows(IllegalArgumentException.class, () -> new PageFace(Direction.UP, Integer.MAX_VALUE, 0, 1, 0, 1, 1, COLOR));
    }

    @Test void resultsAreImmutableAndPreserveUnmodifiedSource() {
        Face source = new Face(Direction.UP, 0, 1, 0, 16, 16, COLOR);
        var clipped = clip(source, List.of(), 1);
        assertEquals(List.of(source), clipped);
        assertThrows(UnsupportedOperationException.class, () -> clipped.add(source));
        var pages = splitPages(source, 0, 0);
        assertThrows(UnsupportedOperationException.class, () -> pages.add(pages.getFirst()));
    }

    private static Face at(Direction direction, long u, long v, long plane, int width, int height) {
        return switch (direction) {
            case UP, DOWN -> new Face(direction, u, plane, v, width, height, COLOR);
            case NORTH, SOUTH -> new Face(direction, u, v, plane, width, height, COLOR);
            case WEST, EAST -> new Face(direction, plane, v, u, width, height, COLOR);
        };
    }

    private static long u(Face f) { return f.direction() == Direction.WEST || f.direction() == Direction.EAST ? f.z() : f.x(); }
    private static long v(Face f) { return f.direction() == Direction.UP || f.direction() == Direction.DOWN ? f.z() : f.y(); }
    private static long plane(Face f) {
        return switch (f.direction()) {
            case UP, DOWN -> f.y(); case NORTH, SOUTH -> f.z(); case WEST, EAST -> f.x();
        };
    }
    private static boolean positive(Direction d) { return d == Direction.UP || d == Direction.SOUTH || d == Direction.EAST; }
    private static long area(List<Face> faces) { return faces.stream().mapToLong(f -> (long) f.width() * f.height()).sum(); }

    private static void assertCellExact(Face source, List<CubePos> ready, List<Face> pieces) {
        int[] actual = new int[source.width() * source.height()];
        for (Face piece : pieces) {
            assertEquals(source.direction(), piece.direction());
            assertEquals(source.argb(), piece.argb());
            assertEquals(plane(source), plane(piece));
            int du = Math.toIntExact(u(piece) - u(source)), dv = Math.toIntExact(v(piece) - v(source));
            assertTrue(du >= 0 && dv >= 0 && du + piece.width() <= source.width() && dv + piece.height() <= source.height());
            for (int y = dv; y < dv + piece.height(); y++) for (int x = du; x < du + piece.width(); x++) {
                int index = y * source.width() + x;
                assertEquals(0, actual[index], "Output rectangles overlap");
                actual[index]++;
            }
        }
        var readySet = new HashSet<>(ready);
        long expectedArea = 0;
        for (int dv = 0; dv < source.height(); dv++) for (int du = 0; du < source.width(); du++) {
            long u = u(source) + du, v = v(source) + dv, p = plane(source) - (positive(source.direction()) ? 1 : 0);
            CubePos owner = switch (source.direction()) {
                case UP, DOWN -> CubePos.fromBlock(u, p, v);
                case NORTH, SOUTH -> CubePos.fromBlock(u, v, p);
                case WEST, EAST -> CubePos.fromBlock(p, v, u);
            };
            int expected = readySet.contains(owner) ? 0 : 1;
            if (expected != actual[dv * source.width() + du]) fail("Wrong cell coverage for " + source + " at " + u + "," + v);
            expectedArea += expected;
        }
        assertEquals(expectedArea, area(pieces));
    }

    private static Face fromPage(PageFace page, long originX, long originZ) {
        return new Face(page.direction(), originX + page.x(), (long) page.pageY2048() * PAGE_HEIGHT + page.y(),
                originZ + page.z(), page.width(), page.height(), page.argb());
    }

    private static void assertPagePartition(Face source, List<PageFace> pages, long originX, long originZ) {
        long area = 0, nextY = source.y();
        boolean horizontal = source.direction() == Direction.UP || source.direction() == Direction.DOWN;
        for (PageFace page : pages) {
            assertPackingSafe(page);
            Face restored = fromPage(page, originX, originZ);
            assertEquals(source.direction(), restored.direction());
            assertEquals(source.argb(), restored.argb());
            assertEquals(source.x(), restored.x());
            assertEquals(source.z(), restored.z());
            assertEquals(source.width(), restored.width());
            assertEquals(nextY, restored.y());
            if (!horizontal) nextY += restored.height();
            area += (long) restored.width() * restored.height();
        }
        assertEquals((long) source.width() * source.height(), area);
        if (horizontal) assertEquals(1, pages.size());
        else assertEquals(source.y() + source.height(), nextY);
    }

    private static void assertPackingSafe(PageFace page) {
        for (int value : new int[]{page.x(), page.y(), page.z(), page.width(), page.height()}) {
            assertEquals(value, Short.toUnsignedInt((short) value));
        }
        assertTrue(page.x() >= 0 && page.x() <= TILE_SIZE);
        assertTrue(page.z() >= 0 && page.z() <= TILE_SIZE);
        assertTrue(page.y() >= 0 && page.y() <= PAGE_HEIGHT);
    }
}
