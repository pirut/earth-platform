package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.MissingTerrainException;
import java.util.*;
import java.util.function.LongBinaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses the independent unit-face oracle with actual accepted slopes and full27 plus a permanent pin. */
@Timeout(60)
class EarthRollingSeamTest {
    @BeforeAll static void registries(){EarthRollingFarBudgetTest.registries();}
    @Test void actualSlopesCloseMovingAndPinnedThreeDimensionalSeamsWithoutDuplicateOrMissingFaces(){
        var source=EarthRollingFarBudgetTest.provider().surfaceSampler();var pin=EarthRollingFarBudgetTest.pin(source);
        var data=new DhAdaptiveTerrainData(source,EarthRollingFarBudgetTest.X,EarthRollingFarBudgetTest.Z);
        for(var offset:List.of(new int[]{0,0},new int[]{2,3},new int[]{0,16},new int[]{16,-16})){
            var center=EarthRollingFarBudgetTest.center(source,pin,offset[0],offset[1]);
            var ready=io.earthplatform.minecraft.enginebridge.RollingCubePlan.stationary(pin,center).committedTargets();
            var masked=data.clip(ready);var fallback=data.clip(List.of(),ready);
            assertTrue(masked.clippedSourceFaces()>0);assertTrue(masked.transitionFaces()>0);
            assertOracle(masked,ready,ready,source::surfaceY,center.minBlockX()-24,center.minBlockZ()-24,64);
            assertOracle(fallback,List.of(),ready,source::surfaceY,center.minBlockX()-24,center.minBlockZ()-24,64);
            if(!center.equals(pin)){
                assertOracle(masked,ready,ready,source::surfaceY,pin.minBlockX()-8,pin.minBlockZ()-8,32);
                assertOracle(fallback,List.of(),ready,source::surfaceY,pin.minBlockX()-8,pin.minBlockZ()-8,32);
            }
            var pinFaces=raster(masked,pin.minBlockX(),pin.minBlockZ(),16);
            assertTrue(pinFaces.stream().noneMatch(face->owned(face,List.of(pin))),"Unedited far source must never replace the pinned native edited volume");
            System.out.println("EARTH_ROLLING_SEAM center="+center+" pin="+pin+" targets="+ready.size()+" movingOracleWidth=64 pinOracleWidth=32");
        }
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
