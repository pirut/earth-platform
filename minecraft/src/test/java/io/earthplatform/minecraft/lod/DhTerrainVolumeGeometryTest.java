package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Volume;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

class DhTerrainVolumeGeometryTest {
    private static final int COLOR = 0xFF8A73C5;
    private record Point(long x, long y, long z) { }

    @Test void arbitraryVolumeUsesSolidSideBoundaryForEveryDirection() {
        Volume volume = new Volume(-17, -17, -17, 1, 1, 1);
        for (Direction direction : Direction.values()) {
            for (long plane : new long[]{-18, -17, -16, 0, 1, 2}) {
                Face source = at(direction, -17, -17, plane, 18, 18);
                boolean positive = positive(direction);
                boolean owns = positive ? -17 < plane && plane <= 1 : -17 <= plane && plane < 1;
                assertEquals(owns ? List.of() : List.of(source), clip(source, List.of(), List.of(volume), 1));
            }
        }
    }

    @Test void deterministicArbitraryOverlappingVolumesMatchIndependentFullXYZCellOracle() {
        Random random = new Random(0x18C011A4L);
        for (Direction direction : Direction.values()) for (int iteration = 0; iteration < 300; iteration++) {
            Face source = at(direction, random.nextInt(33) - 16, random.nextInt(33) - 16,
                    random.nextInt(33) - 16, random.nextInt(40) + 1, random.nextInt(40) + 1);
            var volumes = new ArrayList<Volume>();
            for (int i = 0; i < 12; i++) {
                int x = random.nextInt(49) - 24, y = random.nextInt(49) - 24, z = random.nextInt(49) - 24;
                volumes.add(new Volume(x, y, z, x + random.nextInt(24) + 1,
                        y + random.nextInt(24) + 1, z + random.nextInt(24) + 1));
            }
            List<CubePos> ready = List.of(new CubePos(-1, 0, 0), new CubePos(0, 0, -1));
            var result = clip(source, ready, volumes, MAX_PIECES);
            assertOracle(source, ready, volumes, result);
            Collections.shuffle(volumes, random);
            assertEquals(result, clip(source, ready, volumes, MAX_PIECES), "Input order must not change output");
        }
    }

    @Test void clippedEighteenCubedReplacementPreservesUnownedSameXZAndExactNativeTransition() {
        Volume replacement = new Volume(-1, -1, -1, 17, 17, 17);
        Face high = new Face(Direction.UP, -1, 18, -1, 18, 18, COLOR);
        assertEquals(List.of(high), clip(high, List.of(), List.of(replacement), MAX_PIECES));
        Face base = new Face(Direction.UP, -8, 16, -8, 32, 32, COLOR);
        var nativeCube = List.of(new CubePos(0, 0, 0));
        var withoutNative = clip(base, List.of(), List.of(replacement), MAX_PIECES);
        var withNative = clip(base, nativeCube, List.of(replacement), MAX_PIECES);
        assertEquals(withoutNative, withNative, "Retiring native inside the replacement cannot reveal base");
        assertOracle(base, List.of(), List.of(replacement), withoutNative);
        assertEquals(32 * 32 - 18 * 18, area(withoutNative));
        Face wall = new Face(Direction.SOUTH, -8, -20, 17, 32, 64, COLOR);
        assertOracle(wall, nativeCube, List.of(replacement), clip(wall, nativeCube, List.of(replacement), MAX_PIECES));
    }

    @Test void overlappingAndTouchingVolumesKeepExactLShapedUnionsWithoutGapsOrDuplicates() {
        Face source = new Face(Direction.UP, -8, 1, -8, 48, 48, COLOR);
        var volumes = List.of(new Volume(-1, -1, -1, 17, 17, 17),
                new Volume(7, -1, -1, 25, 17, 17), new Volume(-1, -1, 7, 17, 17, 25));
        var result = clip(source, List.of(), volumes, MAX_PIECES);
        assertOracle(source, List.of(), volumes, result);
        var duplicates = new ArrayList<>(volumes);
        duplicates.addAll(volumes);
        assertEquals(result, clip(source, List.of(), duplicates, MAX_PIECES));
        assertTrue(result.stream().anyMatch(face -> face.x() <= 20 && face.x() + face.width() > 20
                && face.z() <= 20 && face.z() + face.height() > 20), "Unowned L-shaped corner must survive");
    }

    @Test void volumeConversionIsExactlyEquivalentToEveryExistingCubeClippingCase() {
        Random random = new Random(0xC0BEE1L);
        for (Direction direction : Direction.values()) for (int trial = 0; trial < 150; trial++) {
            Face source = at(direction, -24, -24, random.nextInt(65) - 32, 64, 64);
            var cubes = new ArrayList<CubePos>();
            for (int n = 0; n < 12; n++) cubes.add(new CubePos(random.nextInt(5) - 2, random.nextInt(5) - 2, random.nextInt(5) - 2));
            List<Volume> volumes = cubes.stream().map(cube -> new Volume(cube.minBlockX(), cube.minBlockY(), cube.minBlockZ(),
                    cube.minBlockX() + 16, cube.minBlockY() + 16, cube.minBlockZ() + 16)).toList();
            assertEquals(clip(source, cubes, MAX_PIECES), clip(source, List.of(), volumes, MAX_PIECES));
        }
    }

    @Test void endpointClippedVolumesCoverOnlyValidNativeSolidSides() {
        for (int y : new int[]{MIN_Y, MAX_Y_EXCLUSIVE - 17}) {
            Volume volume = new Volume(-1, y, -1, 17, y + 17, 17);
            for (Direction direction : Direction.values()) {
                Face face = switch (direction) {
                    case UP -> new Face(direction, -1, y + 17, -1, 18, 18, COLOR);
                    case DOWN -> new Face(direction, -1, y, -1, 18, 18, COLOR);
                    case NORTH, WEST -> new Face(direction, -1, y, -1, 18, 17, COLOR);
                    case SOUTH -> new Face(direction, -1, y, 17, 18, 17, COLOR);
                    case EAST -> new Face(direction, 17, y, -1, 18, 17, COLOR);
                };
                assertEquals(List.of(), clip(face, List.of(), List.of(volume), 1));
            }
        }
    }

    @Test void combinedInputsRetainExistingCapsAndValidateEvenAfterFullCoverage() {
        Face source = new Face(Direction.UP, 0, 1, 0, 16, 16, COLOR);
        CubePos cube = new CubePos(0, 0, 0);
        Volume volume = new Volume(0, 0, 0, 16, 16, 16);
        assertEquals(List.of(), clip(source, Collections.nCopies(MAX_READY_CUBES - 1, cube), List.of(volume), 1));
        assertThrows(IllegalArgumentException.class, () -> clip(source, Collections.nCopies(MAX_READY_CUBES, cube), List.of(volume), 1));
        assertEquals(List.of(), clip(source, List.of(), Collections.nCopies(MAX_READY_CUBES, volume), 1));
        assertThrows(IllegalArgumentException.class, () -> clip(source, List.of(), Collections.nCopies(MAX_READY_CUBES + 1, volume), 1));
        var nullAfterCoverage = new ArrayList<Volume>(); nullAfterCoverage.add(volume); nullAfterCoverage.add(null);
        assertThrows(NullPointerException.class, () -> clip(source, List.of(), nullAfterCoverage, 1));
        assertThrows(NullPointerException.class, () -> clip(source, List.of(cube), null, 1));
        Face cut = new Face(Direction.UP, -8, 1, -8, 32, 32, COLOR);
        assertThrows(IllegalArgumentException.class, () -> clip(cut, List.of(), List.of(volume), 3));
        assertEquals(4, clip(cut, List.of(), List.of(volume), 4).size());
    }

    @Test void invalidVolumeEndpointsAndOverflowAreRejectedWithoutCoordinateWrap() {
        assertThrows(IllegalArgumentException.class, () -> new Volume(0, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Volume(0, 1, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Volume(0, MIN_Y - 1, 0, 1, MIN_Y + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Volume(0, MAX_Y_EXCLUSIVE - 1, 0, 1, MAX_Y_EXCLUSIVE + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Volume(Long.MIN_VALUE, 0, 0, Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Volume(0, 0, Long.MIN_VALUE, 1, 1, Long.MAX_VALUE));
        for (long origin : new long[]{Long.MIN_VALUE, Long.MAX_VALUE - 32}) {
            Face source = new Face(Direction.UP, origin, 1, origin, 32, 32, COLOR);
            Volume volume = new Volume(origin + 7, 0, origin + 7, origin + 25, 2, origin + 25);
            assertEquals(32 * 32 - 18 * 18, area(clip(source, List.of(), List.of(volume), MAX_PIECES)));
        }
    }

    private static Face at(Direction direction, long u, long v, long plane, int width, int height) {
        return switch (direction) {
            case UP, DOWN -> new Face(direction, u, plane, v, width, height, COLOR);
            case NORTH, SOUTH -> new Face(direction, u, v, plane, width, height, COLOR);
            case WEST, EAST -> new Face(direction, plane, v, u, width, height, COLOR);
        };
    }
    private static boolean positive(Direction direction) { return direction == Direction.UP || direction == Direction.SOUTH || direction == Direction.EAST; }
    private static long area(List<Face> faces) { return faces.stream().mapToLong(face -> (long) face.width() * face.height()).sum(); }
    private static Point solidPoint(Face face, int du, int dv) {
        return switch (face.direction()) {
            case UP -> new Point(face.x() + du, face.y() - 1, face.z() + dv);
            case DOWN -> new Point(face.x() + du, face.y(), face.z() + dv);
            case NORTH -> new Point(face.x() + du, face.y() + dv, face.z());
            case SOUTH -> new Point(face.x() + du, face.y() + dv, face.z() - 1);
            case WEST -> new Point(face.x(), face.y() + dv, face.z() + du);
            case EAST -> new Point(face.x() - 1, face.y() + dv, face.z() + du);
        };
    }
    private static void assertOracle(Face source, List<CubePos> cubes, List<Volume> volumes, List<Face> result) {
        var actual = new java.util.HashSet<Point>();
        for (Face piece : result) {
            assertEquals(source.direction(), piece.direction()); assertEquals(source.argb(), piece.argb());
            for (int v = 0; v < piece.height(); v++) for (int u = 0; u < piece.width(); u++) {
                assertTrue(actual.add(solidPoint(piece, u, v)), "Clipped rectangles overlap");
            }
        }
        var expected = new java.util.HashSet<Point>();
        for (int v = 0; v < source.height(); v++) for (int u = 0; u < source.width(); u++) {
            Point p = solidPoint(source, u, v);
            boolean removed = cubes.contains(CubePos.fromBlock(p.x, p.y, p.z));
            for (Volume box : volumes) removed |= p.x >= box.minX() && p.x < box.maxXExclusive()
                    && p.y >= box.minY() && p.y < box.maxYExclusive() && p.z >= box.minZ() && p.z < box.maxZExclusive();
            if (!removed) expected.add(p);
        }
        assertEquals(expected, actual);
    }
}
