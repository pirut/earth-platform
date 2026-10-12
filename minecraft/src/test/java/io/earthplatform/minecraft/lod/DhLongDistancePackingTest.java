package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.lod.DhLongDistanceGeometryTest.*;
import static io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** CPU-only official DH3.3.3 packing proof. No Minecraft initialization or GL calls. */
class DhLongDistancePackingTest {
    private static DhOfficialRuntime runtime() throws Exception {
        String jar = System.getProperty("earthplatform.lodProbeJar", System.getenv("EARTH_DH_PROBE_JAR"));
        assumeTrue(jar != null && !jar.isBlank(), "Explicit pinned official artifact required");
        return DhOfficialRuntime.open(Path.of(jar));
    }

    @Test void actualOfficialVerticesKeepLongWorldExtentsNativeEndpointsAndFixedPayloadCaps() throws Exception {
        var runtime = runtime();
        for (long y : new long[]{-11000, 8849}) for (long[] center : List.of(new long[]{128, -128}, new long[]{-20_037_632, 20_037_504})) {
            long cx = center[0], cz = center[1];
            var ready = nativeWindow(cx + 112, y, cz - 112);
            ready.add(CubePos.fromBlock(cx + 1152, y, cz + 1152));
            var geometry = data(source((x, z) -> y), cx, cz);
            for (var mask : List.of(ready, List.<CubePos>of())) {
                var batch = geometry.clip(mask, ready);
                long bytes = 0, packedArea = 0;
                boolean hasLargeLocal = false;
                for (var tile : batch.tiles().entrySet()) {
                    var packed = runtime.buildRectangles(tile.getValue());
                    assertTrue(packed.length > 0 && packed.length % 64 == 0 && packed.length <= tile.getValue().size() * 64,
                            "official builder may merge adjacent coplanar quads");
                    var immutable = new DhPreparedVertices(packed);
                    assertArrayEquals(packed, immutable.copyBytes());
                    bytes += immutable.byteCount();
                    var b = ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
                    for (int i = 0; i < packed.length; i += 16) {
                        int x = Short.toUnsignedInt(b.getShort(i)), pageY = Short.toUnsignedInt(b.getShort(i + 2));
                        int z = Short.toUnsignedInt(b.getShort(i + 4));
                        assertTrue(x >= 0 && x <= 4096); assertTrue(z >= 0 && z <= 4096);
                        assertTrue(pageY > 0 && pageY <= 2048);
                        hasLargeLocal |= x > 256 || z > 256;
                        assertEquals(y + 1, tile.getKey().pageY() * 2048L + pageY);
                        long worldX = tile.getKey().originX() + x, worldZ = tile.getKey().originZ() + z;
                        assertTrue(worldX >= cx - 4096 && worldX <= cx + 4096);
                        assertTrue(worldZ >= cz - 4096 && worldZ <= cz + 4096);
                    }
                    for (int quad = 0; quad < packed.length; quad += 64) {
                        int minX = 4096, minZ = 4096, maxX = 0, maxZ = 0;
                        var corners = new HashSet<String>();
                        for (int vertex = 0; vertex < 4; vertex++) {
                            int at = quad + vertex * 16;
                            int x = Short.toUnsignedInt(b.getShort(at)), z = Short.toUnsignedInt(b.getShort(at + 4));
                            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                            corners.add(x + "," + z);
                        }
                        assertEquals(Set.of(minX + "," + minZ, maxX + "," + minZ, minX + "," + maxZ, maxX + "," + maxZ), corners);
                        packedArea += (long) (maxX - minX) * (maxZ - minZ);
                    }
                }
                assertTrue(hasLargeLocal, "proof must use real larger-than-legacy tile coordinates");
                assertTrue(bytes <= batch.faces() * 64L);
                assertEquals(topArea(batch), packedArea, "official merging preserves exact rendered area");
                long declaredBytes = 4096 + batch.faces() * 64L + bytes;
                assertTrue(declaredBytes <= 5L * 1024 * 1024, "prepared result bound");
                assertTrue(declaredBytes * 4 <= 20L * 1024 * 1024, "four-entry CPU cache bound");
                long gpuBytes = bytes + bytes / 64 * 24; // The adapter charges 6 uint indices per quad too.
                assertTrue(gpuBytes * 2 <= 8L * 1024 * 1024, "old + replacement vertex and index accounting bound");
                assertTrue(batch.tiles().size() * 2 <= 128, "old + replacement physical owner bound");
                assertEquals(AREA - (mask.isEmpty() ? 0 : 10L * 256), topArea(batch));
                bounded(batch);
            }
        }
    }

    @Test void officialUnsignedShortCoordinatesRoundTripEveryDirectionAt4096AndSignedPageBoundaries() throws Exception {
        var runtime = runtime();
        for (int page : new int[]{-6, -1, 0, 4}) for (var direction : DhTerrainGeometry.Direction.values()) {
            // page-6 starts below MIN_Y, page4 has only a partial final native page.
            int localY = page == -6 ? 1288 : page == 4 ? 513 : 1920;
            var face = new DhTerrainGeometry.PageFace(direction, page, 3968, localY, 3968, 128, 128, 0xff2f73b9);
            byte[] packed = runtime.buildRectangles(List.of(face));
            assertEquals(64, packed.length);
            var points = new HashSet<String>(); var b = ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
            for (int i = 0; i < 4; i++) points.add(Short.toUnsignedInt(b.getShort(i * 16)) + ","
                    + Short.toUnsignedInt(b.getShort(i * 16 + 2)) + "," + Short.toUnsignedInt(b.getShort(i * 16 + 4)));
            assertEquals(corners(face), points, "official unsigned short roundtrip " + direction + " page " + page);
        }
        var endpoint = new DhTerrainGeometry.PageFace(UP, -1, 3968, 2048, 3968, 128, 128, -1);
        assertEquals(corners(endpoint), points(runtime.buildRectangles(List.of(endpoint))));
    }

    @Test void realRingTransitionWallsAndTopsPackToTheirExactNativeCorners() throws Exception {
        var runtime = runtime();
        var batch = data(source((x, z) -> 1000 + Math.floorDiv(x + 3 * z, 128)), 0, 0).clip(List.of());
        int checked = 0;
        var directions = EnumSet.noneOf(DhTerrainGeometry.Direction.class);
        for (var tile : batch.tiles().entrySet()) for (var face : tile.getValue()) {
            long x = tile.getKey().originX() + face.x(), z = tile.getKey().originZ() + face.z();
            boolean ringEdge = Math.abs(x) == 512 || Math.abs(x) == 1024 || Math.abs(z) == 512 || Math.abs(z) == 1024;
            if (!ringEdge || face.width() < 16) continue;
            assertEquals(corners(face), points(runtime.buildRectangles(List.of(face))));
            checked++; directions.add(face.direction());
        }
        assertTrue(checked > 100); assertTrue(directions.contains(UP));
        assertTrue(directions.stream().anyMatch(d -> d != UP));
    }

    @Test void admittedHostCornersSnapBoundariesAndEndpointMasksPackBelowExhaustiveFlatBounds() throws Exception {
        var runtime = runtime();
        var snapping = Class.forName("io.earthplatform.minecraft.coregate.RollingFarPublication")
                .getDeclaredMethod("snappedCoordinate", long.class, long.class, DhLodLayout.class);
        snapping.setAccessible(true);
        for (int height : new int[]{-11000, 128, 8849})
            for (int[] near : List.of(new int[]{-32, -32}, new int[]{-32, 31}, new int[]{31, -32}, new int[]{31, 31},
                    new int[]{-8, -8}, new int[]{7, 7}, new int[]{8, 8}, new int[]{0, 0})) {
                var pin = CubePos.fromBlock(0, height, 0);
                long farX = (long) snapping.invoke(null, 0L, (long) near[0], DhLodLayout.LONG_4096);
                long farZ = (long) snapping.invoke(null, 0L, (long) near[1], DhLodLayout.LONG_4096);
                // Endpoint filtering follows the actual host's eligible targets.
                var ready = eligibleTargets(pin, new CubePos(near[0], pin.y(), near[1]));
                var data = data(source((x, z) -> height), farX, farZ);
                var counts = data.partitionCounts(ready);
                var batch = data.clip(ready);
                assertEquals(counts.sampledCells(), batch.sampledCells());
                assertEquals(counts.sampledCells(), batch.sourceFaces());
                assertEquals(0, batch.transitionFaces());
                assertTrue(batch.faces() <= counts.sampledCells());
                assertTrue(batch.tiles().size() <= counts.flatTileOwners());
                assertTrue(batch.tiles().values().stream().allMatch(f -> f.size() <= counts.maxFlatPageFaces()));
                assertTrue(batch.sampledCells() <= 12088 && batch.tiles().size() <= 47);
                long bytes = 0;
                for (var entry : batch.tiles().entrySet()) {
                    var packed = runtime.buildRectangles(entry.getValue());
                    assertTrue(packed.length > 0 && packed.length <= entry.getValue().size() * 64L);
                    var values = ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
                    for (int offset = 0; offset < packed.length; offset += 16)
                        assertEquals(height + 1L, entry.getKey().pageY() * 2048L + Short.toUnsignedInt(values.getShort(offset + 2)));
                    bytes += packed.length;
                }
                assertTrue(4096 + batch.faces() * 64L + bytes <= 4096 + 12088 * 128L);
                assertTrue((bytes + bytes / 64 * 24) * 2 <= 8L * 1024 * 1024);
            }
    }

    private static Set<String> points(byte[] packed) {
        assertEquals(64, packed.length);
        var result = new HashSet<String>(); var b = ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) result.add(Short.toUnsignedInt(b.getShort(i * 16)) + ","
                + Short.toUnsignedInt(b.getShort(i * 16 + 2)) + "," + Short.toUnsignedInt(b.getShort(i * 16 + 4)));
        return result;
    }
    private static Set<String> corners(DhTerrainGeometry.PageFace f) {
        var points = new HashSet<String>();
        for (int u : new int[]{0, f.width()}) for (int v : new int[]{0, f.height()}) {
            int x = f.x(), y = f.y(), z = f.z();
            switch (f.direction()) {
                case UP, DOWN -> { x += u; z += v; }
                case NORTH, SOUTH -> { x += u; y += v; }
                case WEST, EAST -> { z += u; y += v; }
            }
            points.add(x + "," + y + "," + z);
        }
        return points;
    }
}
