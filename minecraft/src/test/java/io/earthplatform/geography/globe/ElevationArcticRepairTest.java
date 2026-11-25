package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.earthplatform.geography.MissingTerrainException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleBinaryOperator;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link CopernicusFill#GEOID_ID} regional land-only datum subtraction, legacy v1/v2 pin
 * parity, filled-fjord rejection, and {@link TerrariumTileSource#V2_ID} un-layered z11 repair.
 */
class ElevationArcticRepairTest {
    private static final int SIDE = TerrariumTileSource.TILE_SIZE;

    /** Smooth synthetic ground at (lat, lon): land (> 0) outside [0.4, 0.6] latitude sea band, 0 inside. */
    private static double smoothGround(double lat, double lon) {
        double inLat = lat - Math.floor(lat);
        if (inLat > 0.4 && inLat < 0.6) return 0.0;
        return 80.0 + 30.0 * Math.sin((lat - 63.0) * 6.0) + 15.0 * Math.cos((lon + 20.0) * 5.0);
    }

    /** Builds a 4-level tiled float32 deflated COG matching {@link CopernicusSource}'s reader. */
    private static byte[] syntheticCog(int south, int west, int size, int tile, DoubleBinaryOperator ground) {
        return syntheticCog(south, west, size, size, tile, ground);
    }

    /** Builds a 4-level tiled float32 deflated COG with explicit width and height (for latitude-band width tests). */
    private static byte[] syntheticCog(int south, int west, int width0, int height0, int tile, DoubleBinaryOperator ground) {
        int levels = 4;
        List<byte[]> blocks = new ArrayList<>();
        int[] firstBlock = new int[levels], width = new int[levels], height = new int[levels];
        for (int level = 0; level < levels; level++) {
            int w = width0 >> level, h = height0 >> level;
            width[level] = w;
            height[level] = h;
            firstBlock[level] = blocks.size();
            int across = Math.ceilDiv(w, tile), down = Math.ceilDiv(h, tile);
            for (int br = 0; br < down; br++) for (int bc = 0; bc < across; bc++) {
                byte[] raw = new byte[tile * tile * 4];
                for (int y = 0; y < tile; y++) {
                    int at = y * tile * 4;
                    for (int x = 0; x < tile; x++) {
                        int row = br * tile + y, col = bc * tile + x;
                        float v = row < h && col < w
                                ? (float) ground.applyAsDouble(south + 1 - (row + 0.5) / h, west + (col + 0.5) / w)
                                : 0f;
                        int bits = Float.floatToIntBits(v);
                        for (int plane = 0; plane < 4; plane++) raw[at + plane * tile + x] = (byte) (bits >>> (24 - 8 * plane));
                    }
                    for (int i = tile * 4 - 1; i > 0; i--) raw[at + i] -= raw[at + i - 1];
                }
                Deflater deflater = new Deflater();
                deflater.setInput(raw);
                deflater.finish();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
                deflater.end();
                blocks.add(out.toByteArray());
            }
        }
        int entries = 11, ifdBytes = 2 + entries * 12 + 4;
        int headerEnd = 8;
        int[] ifdAt = new int[levels], arraysAt = new int[levels];
        for (int level = 0; level < levels; level++) {
            int n = Math.ceilDiv(width[level], tile) * Math.ceilDiv(height[level], tile);
            ifdAt[level] = headerEnd;
            arraysAt[level] = headerEnd + ifdBytes;
            headerEnd += ifdBytes + n * 8;
        }
        int total = headerEnd;
        for (byte[] b : blocks) total += b.length;
        ByteBuffer file = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifdAt[0]);
        int data = headerEnd;
        int[] blockAt = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) { blockAt[i] = data; data += blocks.get(i).length; }
        for (int level = 0; level < levels; level++) {
            int n = Math.ceilDiv(width[level], tile) * Math.ceilDiv(height[level], tile);
            file.position(ifdAt[level]);
            file.putShort((short) entries);
            int[][] tags = {
                    {256, 4, 1, width[level]}, {257, 4, 1, height[level]}, {258, 3, 1, 32}, {259, 3, 1, 8},
                    {277, 3, 1, 1}, {317, 3, 1, 3}, {322, 3, 1, tile}, {323, 3, 1, tile},
                    {324, 4, n, n == 1 ? blockAt[firstBlock[level]] : arraysAt[level]},
                    {325, 4, n, n == 1 ? blocks.get(firstBlock[level]).length : arraysAt[level] + 4 * n},
                    {339, 3, 1, 3}
            };
            for (int[] t : tags) {
                file.putShort((short) t[0]).putShort((short) t[1]).putInt(t[2]);
                if (t[1] == 3) file.putShort((short) t[3]).putShort((short) 0);
                else file.putInt(t[3]);
            }
            file.putInt(level + 1 < levels ? ifdAt[level + 1] : 0);
            file.position(arraysAt[level]);
            for (int i = 0; i < n; i++) file.putInt(blockAt[firstBlock[level] + i]);
            for (int i = 0; i < n; i++) file.putInt(blocks.get(firstBlock[level] + i).length);
        }
        file.position(headerEnd);
        for (byte[] b : blocks) file.put(b);
        return file.array();
    }

    private static HttpServer cogServer(Map<String, byte[]> files) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/cop/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/cop/".length());
            int slash = path.indexOf('/');
            String key = slash >= 0 ? path.substring(slash + 1) : path;
            byte[] file = files.get(key);
            if (file == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            String[] span = range.substring("bytes=".length()).split("-");
            int from = Integer.parseInt(span[0]), to = Math.min(file.length - 1, Integer.parseInt(span[1]));
            byte[] body = Arrays.copyOfRange(file, from, to + 1);
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static double tileLat(int zoom, int tileY, int py) {
        long size = (long) SIDE << zoom;
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (tileY * (long) SIDE + py + 0.5) / size))));
    }

    private static double tileLon(int zoom, int tileX, int px) {
        long size = (long) SIDE << zoom;
        return (tileX * (long) SIDE + px + 0.5) * 360.0 / size - 180.0;
    }

    private static int mercatorTileX(int zoom, double lon) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
    }

    private static int mercatorTileY(int zoom, double lat) {
        double rad = Math.toRadians(lat);
        return (int) Math.floor((1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * (1 << zoom));
    }

    @Test
    void glo30FillV3PreservesSharpCliffReliefWhileShiftingEllipsoidDatum() throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ElevationArcticRepairTest::smoothGround));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            double datumOffset = 55.0;
            // Coarse check tiles: smooth ground shifted uniformly by +55 m on land, 0 on sea.
            CopernicusFill.Tiles checkTiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double g = smoothGround(lat, tileLon(z, x, px));
                        t[py * SIDE + px] = g == 0.0 ? 0f : (float) (g + datumOffset);
                    }
                }
                return t;
            };
            CopernicusFill v2 = new CopernicusFill(dem, checkTiles, (lat, lon) -> false, 16);
            CopernicusFill v3 = new CopernicusFill(dem, checkTiles, (lat, lon) -> false, true, 16);
            assertEquals(CopernicusFill.ICE_ID, v2.id());
            assertEquals(CopernicusFill.GEOID_ID, v3.id());

            // Zoom 15 tile in land band (63.75 N, -19.5 E) with +55 m offset + sharp 2 m cliff/canyon relief.
            int zoom = 15;
            int tx = mercatorTileX(zoom, -19.5), ty = mercatorTileY(zoom, 63.75);
            float[] fine = new float[SIDE * SIDE];
            float[] expectedOrthometric = new float[SIDE * SIDE];
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, ty, py);
                for (int px = 0; px < SIDE; px++) {
                    double lon = tileLon(zoom, tx, px);
                    double g = smoothGround(lat, lon);
                    // Add sharp 2-pixel canyon (-28 m) and sheer cliff (+22 m) absent from smooth 30 m Copernicus.
                    double relief = (px >= 120 && px <= 125) ? -28.0 : (px >= 126 && px <= 135 ? 22.0 : 0.0);
                    expectedOrthometric[py * SIDE + px] = (float) (g + relief);
                    fine[py * SIDE + px] = (float) (g + relief + datumOffset);
                }
            }

            float[] v2Out = fine.clone();
            v2.apply(v2Out, zoom, tx, ty);
            float[] v3Out = fine.clone();
            v3.apply(v3Out, zoom, tx, ty);

            // v2 replaces everything with smooth Copernicus (wiping out the 50 m cliff/canyon step!).
            double v2Step = v2Out[128 * SIDE + 130] - v2Out[128 * SIDE + 122];
            assertTrue(Math.abs(v2Step) < 5.0, "v2 legacy flattens ArcticDEM cliff to Copernicus, got step " + v2Step);

            // v3 shifts down by ~55 m AND preserves the full 50 m sharp cliff/canyon relief!
            assertEquals(datumOffset, v3.datum(63.75, -19.5), 1.0, "estimated regional datum should be ~55 m");
            assertEquals(0.0, v3.weight(63.75, -19.5), 1e-3, "coherent datum offset should keep Copernicus blend weight 0");
            double v3Step = v3Out[128 * SIDE + 130] - v3Out[128 * SIDE + 122];
            assertEquals(50.0, v3Step, 1.0, "v3 must preserve sharp 50 m canyon-to-cliff relief");
            assertEquals(expectedOrthometric[128 * SIDE + 122], v3Out[128 * SIDE + 122], 1.5f, "canyon floor shifted to orthometric datum");
            assertEquals(expectedOrthometric[128 * SIDE + 130], v3Out[128 * SIDE + 130], 1.5f, "cliff brink shifted to orthometric datum");
            assertEquals(expectedOrthometric[64 * SIDE + 64], v3Out[64 * SIDE + 64], 1.5f, "ambient ground shifted to orthometric datum");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3RejectsFilledFjordAndNeverTurnsCopernicusLandNegative() throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ElevationArcticRepairTest::smoothGround));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            // Case A: Aligned land (0 m offset) beside an 85 m filled fjord where cop == 0.
            CopernicusFill.Tiles alignedWithFjord = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double g = smoothGround(lat, tileLon(z, x, px));
                        t[py * SIDE + px] = g == 0.0 ? 85f : (float) g;
                    }
                }
                return t;
            };
            CopernicusFill v3Aligned = new CopernicusFill(dem, alignedWithFjord, (lat, lon) -> false, true, 16);
            assertEquals(0.0, v3Aligned.datum(63.5, -19.5), 1e-3, "filled fjord must never be mistaken for regional datum offset");
            assertEquals(1.0, v3Aligned.weight(63.5, -19.5), 1e-3, "filled fjord over cop == 0 must get full Copernicus weight");
            int zoom = 13;
            int tx = mercatorTileX(zoom, -19.5), fjordY = mercatorTileY(zoom, 63.5);
            float[] fjordTile = alignedWithFjord.tile(zoom, tx, fjordY).clone();
            v3Aligned.apply(fjordTile, zoom, tx, fjordY);
            int openSea = 0;
            for (float v : fjordTile) if (v == 0f) openSea++;
            assertTrue(openSea > SIDE * SIDE * 3 / 4, "filled fjord should become open sea (0f), got " + openSea);

            // Case B: +55 m ArcticDEM land beside an 86 m filled fjord (like Isafjordur).
            CopernicusFill.Tiles arcticWithFjord = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double g = smoothGround(lat, tileLon(z, x, px));
                        t[py * SIDE + px] = g == 0.0 ? 86f : (float) (g + 55.0);
                    }
                }
                return t;
            };
            CopernicusFill v3Arctic = new CopernicusFill(dem, arcticWithFjord, (lat, lon) -> false, true, 16);
            assertEquals(55.0, v3Arctic.datum(63.75, -19.5), 1.5, "land datum should ignore filled fjord");
            assertEquals(1.0, v3Arctic.weight(63.5, -19.5), 1e-3, "filled fjord in ArcticDEM region must still be removed");
            float[] arcticFjord = arcticWithFjord.tile(zoom, tx, fjordY).clone();
            v3Arctic.apply(arcticFjord, zoom, tx, fjordY);
            int arcticSea = 0;
            for (float v : arcticFjord) if (v == 0f) arcticSea++;
            assertTrue(arcticSea > SIDE * SIDE * 3 / 4, "ArcticDEM filled fjord should become open sea, got " + arcticSea);

            // Low coastal land (t = 54.5 m in a +55 m datum region where cop > SEA_CRUMB) must not turn negative.
            int landY = mercatorTileY(zoom, 63.75);
            float[] lowLand = arcticWithFjord.tile(zoom, tx, landY).clone();
            lowLand[128 * SIDE + 128] = 54.5f;
            v3Arctic.apply(lowLand, zoom, tx, landY);
            assertTrue(lowLand[128 * SIDE + 128] > 0f, "datum subtraction must never turn Copernicus-confirmed land negative");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3LeavesAlreadyAlignedCoastUntouchedAndLegacyV1V2Match() throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ElevationArcticRepairTest::smoothGround));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            CopernicusFill.Tiles aligned = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double g = smoothGround(lat, tileLon(z, x, px));
                        t[py * SIDE + px] = (float) g;
                    }
                }
                return t;
            };
            CopernicusFill v1 = new CopernicusFill(dem, aligned, 16);
            CopernicusFill v2 = new CopernicusFill(dem, aligned, (lat, lon) -> false, 16);
            CopernicusFill v3 = new CopernicusFill(dem, aligned, (lat, lon) -> false, true, 16);

            int zoom = 13;
            int tx = mercatorTileX(zoom, -19.5), coastY = mercatorTileY(zoom, 63.6);
            float[] raw = aligned.tile(zoom, tx, coastY);
            float[] out1 = raw.clone(), out2 = raw.clone(), out3 = raw.clone();
            v1.apply(out1, zoom, tx, coastY);
            v2.apply(out2, zoom, tx, coastY);
            v3.apply(out3, zoom, tx, coastY);
            assertArrayEquals(raw, out1, "v1 leaves aligned coast untouched");
            assertArrayEquals(raw, out2, "v2 leaves aligned coast untouched");
            assertArrayEquals(raw, out3, "v3 leaves aligned coast bit-for-bit untouched");
            assertEquals(0.0, v3.datum(63.6, -19.5), 1e-6);
            assertEquals(0.0, v3.weight(63.6, -19.5), 1e-6);
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void terrariumV2RepairsAgainstUnlayeredCleanedZ11AndHandlesZ11BorderCleanly() throws Exception {
        // Synthetic tile server: z11 tiles are flat 100 m (except western neighbour z11 at 120 m);
        // z15 tile on the western border of its z11 parent (ox == 0) is valid 100 m except one -20,000 m void pixel.
        // An overlay layer shifts z11 tiles by +500 m (like REMA/CopernicusFill shifting z11 away from raw Terrarium).
        int refX = 1000, refY = 600;
        int z15X = refX << 4, z15Y = refY << 4; // ox == 0, oy == 0 on z11 border!
        float[] z11Raw = new float[SIDE * SIDE];
        Arrays.fill(z11Raw, 100f);
        float[] z11West = new float[SIDE * SIDE];
        Arrays.fill(z11West, 120f);
        float[] z15Raw = new float[SIDE * SIDE];
        Arrays.fill(z15Raw, 100f);
        // One genuine broken void pixel near the interior and one right at the western border (px = 0).
        z15Raw[128 * SIDE + 128] = -20_000f;
        z15Raw[128 * SIDE + 0] = -20_000f;

        byte[] z11Png = TerrariumTileSource.encode(z11Raw);
        byte[] z11WestPng = TerrariumTileSource.encode(z11West);
        byte[] z15Png = TerrariumTileSource.encode(z15Raw);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/tiles/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/tiles/".length());
            byte[] body;
            if (path.equals("15/" + z15X + "/" + z15Y + ".png")) body = z15Png;
            else if (path.equals("11/" + (refX - 1) + "/" + refY + ".png")) body = z11WestPng;
            else body = z11Png;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tiles/");
        // Overlay layer that only shifts z11 by +500 m (simulating datum difference between z11 layer and raw Terrarium).
        ElevationLayer z11ShiftLayer = new ElevationLayer() {
            @Override public String id() { return "test-z11-shift"; }
            @Override public void refine(int zoom, int x, int y, float[] values) {
                if (zoom == TerrariumTileSource.REFERENCE_ZOOM) {
                    for (int i = 0; i < values.length; i++) values[i] += 500f;
                }
            }
        };
        try (var v1 = new TerrariumTileSource(base, null, true, 64, 4, Duration.ofSeconds(5), List.of(z11ShiftLayer), false);
             var v2 = new TerrariumTileSource(base, null, true, 64, 4, Duration.ofSeconds(5), List.of(z11ShiftLayer), true)) {
            float[] tileV1 = v1.tile(15, z15X, z15Y);
            float[] tileV2 = v2.tile(15, z15X, z15Y);

            // In v1, layered z11 (600 m) poisons repair(), overwriting every valid 100 m pixel of z15 with ~600 m!
            assertEquals(600f, tileV1[64 * SIDE + 64], 1f, "v1 legacy compares against layered z11");

            // In v2, repair() compares against un-layered cleaned(z11) (100 m), keeping valid 100 m pixels intact!
            assertEquals(100f, tileV2[64 * SIDE + 64], 0.1f, "v2 preserves valid fine pixels against un-layered z11");
            // Genuine -20,000 m void pixels (both interior and z11-border) are cleanly repaired!
            assertEquals(100f, tileV2[128 * SIDE + 128], 1f, "v2 repairs interior void cleanly");
            assertEquals(100f, tileV2[128 * SIDE + 0], 5f, "v2 repairs z11-border void cleanly using neighbour z11");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3PreservesValidBathymetryNextToFilledFjordWhileRepairingLandVoids() throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ElevationArcticRepairTest::smoothGround));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            // Sea band (0.4, 0.6): west half (lon < -19.5) is a +85 m filled fjord; east half is valid -20 m bathymetry.
            CopernicusFill.Tiles tiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        double g = smoothGround(lat, lon);
                        if (g == 0.0) {
                            t[py * SIDE + px] = lon < -19.5 ? 85f : -20f;
                        } else {
                            // Put an explicit coastal land void (-200 m) on land at lon in (-19.4, -19.3), lat in (63.7, 63.8).
                            t[py * SIDE + px] = (lat > 63.7 && lat < 63.8 && lon > -19.4 && lon < -19.3) ? -200f : (float) g;
                        }
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, tiles, (lat, lon) -> false, true, 16);

            // Tile straddling the filled-fjord / -20 m bathymetry boundary at (63.5, -19.5).
            int zoom = 13;
            int tx = mercatorTileX(zoom, -19.5), ty = mercatorTileY(zoom, 63.5);
            float[] tile = tiles.tile(zoom, tx, ty).clone();
            v3.apply(tile, zoom, tx, ty);
            int bathyChecked = 0, fjordCleared = 0;
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, ty, py);
                if (lat - Math.floor(lat) <= 0.42 || lat - Math.floor(lat) >= 0.58) continue;
                for (int px = 0; px < SIDE; px++) {
                    double lon = tileLon(zoom, tx, px);
                    float v = tile[py * SIDE + px];
                    if (lon > -19.49) {
                        assertEquals(-20f, v, 1e-4f, "valid -20 m bathymetry next to filled fjord must never be erased");
                        bathyChecked++;
                    }
                }
            }
            // Check the adjacent western tile (fully inside the +85 m filled fjord) is cleared to exact 0f.
            float[] westTile = tiles.tile(zoom, tx - 1, ty).clone();
            v3.apply(westTile, zoom, tx - 1, ty);
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, ty, py);
                if (lat - Math.floor(lat) <= 0.42 || lat - Math.floor(lat) >= 0.58) continue;
                for (int px = 0; px < SIDE; px++) {
                    if (westTile[py * SIDE + px] == 0f) fjordCleared++;
                }
            }
            assertTrue(bathyChecked > 100, "expected valid bathymetry samples, got " + bathyChecked);
            assertTrue(fjordCleared > 100, "expected filled fjord pixels cleared to 0f, got " + fjordCleared);

            // Explicit land void (-200 m where Copernicus has ~80 m land) must still be repaired!
            int vx = mercatorTileX(zoom, -19.35), vy = mercatorTileY(zoom, 63.75);
            float[] voidTile = tiles.tile(zoom, vx, vy).clone();
            v3.apply(voidTile, zoom, vx, vy);
            double expectedLand = smoothGround(tileLat(zoom, vy, 128), tileLon(zoom, vx, 128));
            assertEquals(expectedLand, voidTile[128 * SIDE + 128], 5.0, "explicit land void must still be repaired to Copernicus");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3EmitsOpenSeaForCoastalRampsArtifactsAndArcticDemSeaShelves() throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ElevationArcticRepairTest::smoothGround));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            // Land has +55 m datum offset; sea band (0.4, 0.6) has 0f in check tiles.
            CopernicusFill.Tiles checkTiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double g = smoothGround(lat, tileLon(z, x, px));
                        t[py * SIDE + px] = g == 0.0 ? 0f : (float) (g + 55.0);
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, checkTiles, (lat, lon) -> false, true, 16);

            // Pick a z15 tile just offshore of the 63.60 coastline (lat ~63.595) where cop == 0 and datum ~+55 m!
            int zoom = 15;
            int tx = mercatorTileX(zoom, -19.5), ty = mercatorTileY(zoom, 63.595);
            double midLat = tileLat(zoom, ty, 128), midLon = tileLon(zoom, tx, 128);
            assertEquals(0.0, dem.metres(midLat, midLon, 0), 1e-3, "test pixel must be confirmed sea in Copernicus");
            assertTrue(v3.datum(midLat, midLon) > 40.0, "test pixel near coast should carry positive regional datum, got " + v3.datum(midLat, midLon));

            float[] fine = new float[SIDE * SIDE];
            Arrays.fill(fine, 0f);
            // Positive coastal ramp (10 m), artifact (30 m), and ArcticDEM sea/fjord pixels (datum ± 3 m: 58 m & 52 m).
            fine[128 * SIDE + 125] = 10f;
            fine[128 * SIDE + 126] = 30f;
            fine[128 * SIDE + 127] = 58f;
            fine[128 * SIDE + 128] = 52f;
            v3.apply(fine, zoom, tx, ty);

            assertEquals(0f, fine[128 * SIDE + 125], 0f, "coastal ramp (10 m) over sea with +55 m datum must become exact 0f");
            assertEquals(0f, fine[128 * SIDE + 126], 0f, "coastal artifact (30 m) over sea with +55 m datum must become exact 0f");
            assertEquals(0f, fine[128 * SIDE + 127], 0f, "ArcticDEM sea pixel (datum + 3 m) must become exact 0f, not +3 m shelf");
            assertEquals(0f, fine[128 * SIDE + 128], 0f, "ArcticDEM sea pixel (datum - 3 m) must become exact 0f, not -3 m trench");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3PreservesCopernicusCoastalLandDepressionAndCorrectsDatumOverSubtraction() throws Exception {
        // Custom ground:
        // - lat >= 63.5: coastal land at +15 m near lon < -19.5, +100 m elsewhere; Terrarium = cop + 55 m (offset region)
        // - lat in (63.2, 63.5): aligned land at +100 m (Terrarium == cop == 100 m) to test datum spillover over-subtraction!
        // - lat <= 63.2: below-sea-level land depression at -10 m in Copernicus
        DoubleBinaryOperator ground = (lat, lon) -> {
            if (lat <= 63.2) return -10.0;
            if (lat >= 63.5 && lon < -19.5) return 15.0;
            return 100.0;
        };
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            CopernicusFill.Tiles tiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        double g = ground.applyAsDouble(lat, lon);
                        // Offset region north of 63.5 (+55 m); aligned region south of 63.5 (0 m offset).
                        t[py * SIDE + px] = lat >= 63.5 ? (float) (g + 55.0) : (float) g;
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, tiles, (lat, lon) -> false, true, 16);

            // 1. Copernicus-confirmed coastal land (c = 15 m) under over-subtracted datum (t = 50, datum = +55 -> base = -5 <= 0)
            //    must preserve c = 15 m (NOT flatten to 0.01 m!).
            int zoom = 15;
            int tx = mercatorTileX(zoom, -19.7), ty = mercatorTileY(zoom, 63.7);
            float[] coastal = tiles.tile(zoom, tx, ty).clone();
            coastal[128 * SIDE + 128] = 50f;
            v3.apply(coastal, zoom, tx, ty);
            assertEquals(15f, coastal[128 * SIDE + 128], 1.0f,
                    "Copernicus-confirmed 15 m coastal land under base <= 0 must preserve c, not 0.01 m");

            // 2. Datum spillover onto aligned land just south of 63.5 must correct back toward Copernicus (100 m)
            //    instead of sinking by tens of metres!
            int sx = mercatorTileX(zoom, -19.3), sy = mercatorTileY(zoom, 63.495);
            float[] spillover = tiles.tile(zoom, sx, sy).clone();
            v3.apply(spillover, zoom, sx, sy);
            float minSpill = Float.MAX_VALUE;
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, sy, py);
                if (lat >= 63.5) continue;
                for (int px = 0; px < SIDE; px++) minSpill = Math.min(minSpill, spillover[py * SIDE + px]);
            }
            assertTrue(minSpill > 85f, "datum spillover onto aligned 100 m land must not sink ground deeply, min=" + minSpill);

            // 3. Below-sea-level land depression (c = -10 m) under positive datum must never dig deeper than Copernicus.
            CopernicusFill.Tiles shiftedDepression = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        double g = ground.applyAsDouble(lat, lon);
                        t[py * SIDE + px] = g > 0 ? (float) (g + 55.0) : 5f;
                    }
                }
                return t;
            };
            CopernicusFill v3Dep = new CopernicusFill(dem, shiftedDepression, (lat, lon) -> false, true, 16);
            int dx = mercatorTileX(zoom, -19.3), dy = mercatorTileY(zoom, 63.195);
            float[] depTile = shiftedDepression.tile(zoom, dx, dy).clone();
            v3Dep.apply(depTile, zoom, dx, dy);
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, dy, py);
                if (lat >= 63.19) continue;
                assertTrue(depTile[py * SIDE + 128] >= -10.5f,
                        "datum subtraction must never dig below Copernicus -10 m depression, got " + depTile[py * SIDE + 128]);
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3HandlesSignedNegativeRegionalDatumOffset() throws Exception {
        // Land >= 120 m so a -55 m regional datum keeps Terrarium positive (65+ m); sea band (0.4, 0.6) is 0 m.
        DoubleBinaryOperator ground = (lat, lon) -> {
            double inLat = lat - Math.floor(lat);
            if (inLat > 0.4 && inLat < 0.6) return 0.0;
            return 160.0 + 25.0 * Math.sin((lat - 63.0) * 6.0) + 10.0 * Math.cos((lon + 20.0) * 5.0);
        };
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            double datumOffset = -55.0;
            CopernicusFill.Tiles tiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        double g = ground.applyAsDouble(lat, lon);
                        if (g == 0.0) t[py * SIDE + px] = lon < -19.5 ? 0f : -20f;
                        else t[py * SIDE + px] = lat >= 63.6 ? (float) (g + datumOffset) : (float) g;
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, tiles, (lat, lon) -> false, true, 16);

            // North land has -55 m coherent datum; south land is aligned (0 m datum).
            assertEquals(datumOffset, v3.datum(63.75, -19.5), 1.0, "must estimate signed negative datum ~-55 m");
            assertEquals(0.0, v3.weight(63.75, -19.5), 1e-3, "coherent negative datum keeps Copernicus blend weight 0");
            assertEquals(0.0, v3.datum(63.2, -19.5), 1e-3, "aligned southern land keeps datum 0");

            // Fine tile on shifted land with sharp 40 m cliff relief is lifted UP by +55 m with cliff intact!
            int zoom = 15;
            int tx = mercatorTileX(zoom, -19.5), ty = mercatorTileY(zoom, 63.75);
            float[] fine = new float[SIDE * SIDE];
            float[] expected = new float[SIDE * SIDE];
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, ty, py);
                for (int px = 0; px < SIDE; px++) {
                    double g = ground.applyAsDouble(lat, tileLon(zoom, tx, px));
                    double relief = (px >= 120 && px <= 125) ? -20.0 : (px >= 126 && px <= 135 ? 20.0 : 0.0);
                    expected[py * SIDE + px] = (float) (g + relief);
                    fine[py * SIDE + px] = (float) (g + relief + datumOffset);
                }
            }
            v3.apply(fine, zoom, tx, ty);
            assertEquals(40.0, fine[128 * SIDE + 130] - fine[128 * SIDE + 122], 1.0, "negative datum shift must preserve 40 m cliff");
            assertEquals(expected[128 * SIDE + 130], fine[128 * SIDE + 130], 1.5f, "cliff lifted to orthometric datum");

            // Near-coast sea tile under negative datum: 0f and -20f stay untouched; +2f sea noise becomes 0f (not +57 m!).
            int seaY = mercatorTileY(zoom, 63.595);
            float[] seaTile = new float[SIDE * SIDE];
            Arrays.fill(seaTile, 0f);
            seaTile[128 * SIDE + 126] = 0f;
            seaTile[128 * SIDE + 127] = -20f;
            seaTile[128 * SIDE + 128] = 2f;
            v3.apply(seaTile, zoom, tx, seaY);
            assertEquals(0f, seaTile[128 * SIDE + 126], 0f, "open sea stays 0f under negative datum");
            assertEquals(-20f, seaTile[128 * SIDE + 127], 1e-4f, "valid bathymetry stays -20f under negative datum");
            assertEquals(0f, seaTile[128 * SIDE + 128], 0f, "tiny +2 m sea noise over sea must not lift to +57 m");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3EliminatesSeamsAcrossPartDegreeWidthChangeAndCoverageBoundaries() throws Exception {
        // Test all three seam classes in one fast local COG fixture:
        // - Square (59, -20): 512x512 (128x128 at CHECK_OVERVIEW=2)
        // - Square (60, -20): 256x512 (64x128 at CHECK_OVERVIEW=2 — latitude-band column width halves!)
        // - Square (59, -21) & (60, -21): MISSING (open ocean 404 — coverage edge at lon = -20.0!)
        // - Also include (58, -20), (61, -20), (59, -19), (60, -19) for smooth interiors.
        DoubleBinaryOperator ground = (lat, lon) -> {
            // Small island around (59.25, -19.5) surrounded by sea so node (59.25, -19.5) sees ~16 land samples
            // (12 shifted + 4 aligned), genuinely exercising the < 16 early-exit boundary across the 0.25° part border!
            if (lat < 59.45) {
                boolean island = Math.abs(lat - 59.25) < 0.009 && Math.abs(lon - (-19.5)) < 0.009;
                if (!island) return 0.0;
            }
            return 100.0 + 20.0 * Math.sin(lat * 4.0) + 10.0 * Math.cos(lon * 4.0);
        };
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 58; s <= 61; s++) {
            for (int w = -20; w <= -19; w++) {
                int w0 = s >= 60 ? 256 : 512;
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, w0, 512, 128, ground));
            }
        }
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            // Spatially varying datum: +55 m everywhere north of 59.5, EXCEPT a small isolated 10-sample shifted patch
            // straddling the 0.25° part border at (59.25, -19.5) to exercise the 8..15 sample early-exit boundary!
            CopernicusFill.Tiles tiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        double g = ground.applyAsDouble(lat, lon);
                        double shift = 0.0;
                        if (lat >= 59.5) {
                            // Smoothly modulated regional shift +35..+55 m crossing the 60.0° width-change border & 59.75° part border.
                            shift = 45.0 + 10.0 * Math.sin((lon + 20.0) * Math.PI);
                        } else if (g > 0.0 && Math.abs(lat - 59.25) < 0.009 && lon >= -19.506 && lon <= -19.493) {
                            // ~12 (< 16) canonical land samples shifted by +55 m straddling the 59.25° part border!
                            shift = 55.0;
                        }
                        t[py * SIDE + px] = (float) (g + shift);
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, tiles, (lat, lon) -> false, true, 64);

            // 1. 0.25° part boundary continuity (datum AND weight) at 59.75° (WIDE = 20 margin) & 59.25° (8..15 sample edge case).
            double eps = 1e-5;
            for (double lon = -19.8; lon <= -19.2; lon += 0.1) {
                assertEquals(v3.datum(59.75 - eps, lon), v3.datum(59.75 + eps, lon), 0.1,
                        "datum must be continuous across 0.25° part border at 59.75, lon=" + lon);
                assertEquals(v3.weight(59.75 - eps, lon), v3.weight(59.75 + eps, lon), 0.02,
                        "weight must be continuous across 0.25° part border at 59.75, lon=" + lon);
            }
            assertEquals(v3.datum(59.25 - eps, -19.5), v3.datum(59.25 + eps, -19.5), 0.1,
                    "datum must agree across 0.25° part border even for 8..15 shifted samples");
            assertEquals(v3.weight(59.25 - eps, -19.5), v3.weight(59.25 + eps, -19.5), 0.02,
                    "weight must agree across 0.25° part border even for 8..15 shifted samples");

            // 2. 1° degree-square boundary continuity across column-width change (128 -> 64 cols/deg at lat = 60.0°).
            for (double lon = -19.85; lon <= -19.15; lon += 0.1) {
                double southDatum = v3.datum(60.0 - eps, lon);
                double northDatum = v3.datum(60.0 + eps, lon);
                assertTrue(southDatum > 30.0, "expected active datum south of 60°, got " + southDatum);
                assertEquals(southDatum, northDatum, 0.15,
                        "datum must be continuous across 1° square border with width change at lon=" + lon);
            }

            // 3. Missing-square coverage boundary at lon = -20.0° (west square -21 is missing): smooth taper to 0, NO jump!
            assertEquals(0.0, v3.datum(60.55, -20.0 - 1e-4), 1e-6, "missing square must return datum 0");
            double justInside = v3.datum(60.55, -20.0 + 1e-4);
            double inland = v3.datum(60.55, -19.95);
            assertTrue(inland > 35.0, "inland datum north of latitude taper should be full regional offset, got " + inland);
            assertTrue(Math.abs(justInside) < 1.5,
                    "datum must smoothly taper to 0 at missing-square coverage edge (no 55 m jump), got " + justInside);
            assertTrue(Math.abs(v3.datum(59.75, -20.0 + 1e-4)) < 1.5,
                    "datum inside latitude taper must also smoothly taper to 0 at missing-square coverage edge");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3SkipsWorldCoverOnAlignedLandRepairsSunkIceOnShiftedGlacierAndPrefetchesDatum() throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ElevationArcticRepairTest::smoothGround));
        AtomicInteger cogRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/cop/", exchange -> {
            cogRequests.incrementAndGet();
            String path = exchange.getRequestURI().getPath().substring("/cop/".length());
            int slash = path.indexOf('/');
            String key = slash >= 0 ? path.substring(slash + 1) : path;
            byte[] file = files.get(key);
            if (file == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            String[] span = range.substring("bytes=".length()).split("-");
            int from = Integer.parseInt(span[0]), to = Math.min(file.length - 1, Integer.parseInt(span[1]));
            byte[] body = Arrays.copyOfRange(file, from, to + 1);
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            // 1. Aligned land: WorldCover ice.at() must NOT be called at all!
            AtomicInteger alignedIceCalls = new AtomicInteger();
            CopernicusFill.Tiles aligned = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) t[py * SIDE + px] = (float) smoothGround(lat, tileLon(z, x, px));
                }
                return t;
            };
            CopernicusFill v3Aligned = new CopernicusFill(dem, aligned, (lat, lon) -> {
                alignedIceCalls.incrementAndGet();
                return false;
            }, true, 16);
            int zoom = 15;
            int tx = mercatorTileX(zoom, -19.5), ty = mercatorTileY(zoom, 63.75);
            v3Aligned.apply(aligned.tile(zoom, tx, ty).clone(), zoom, tx, ty);
            assertEquals(0, alignedIceCalls.get(), "WorldCover ice.at() must never be queried on aligned land");

            // 2. Datum-shifted (+55 m) glacier with a -30 m sunk pit (t = cop + 55 - 30 = cop + 25):
            //    loop 1 has cop - t = -25 <= AGREE, loop 2 uncovers cop - tAdj = +30 > AGREE and queries ice.at().
            CopernicusFill.Tiles shiftedGlacier = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        double g = smoothGround(lat, lon);
                        if (g == 0.0) { t[py * SIDE + px] = 0f; continue; }
                        boolean pit = Math.abs(lat - 63.75) < 0.008 && Math.abs(lon - (-19.5)) < 0.008;
                        t[py * SIDE + px] = (float) (g + 55.0 - (pit ? 30.0 : 0.0));
                    }
                }
                return t;
            };
            CopernicusFill.Ice glacierIce = (lat, lon) -> Math.abs(lat - 63.75) < 0.012 && Math.abs(lon - (-19.5)) < 0.012;
            CopernicusFill v3Glacier = new CopernicusFill(dem, shiftedGlacier, glacierIce, true, 16);
            assertEquals(1.0, v3Glacier.weight(63.75, -19.5), 1e-3, "sunk ice on datum-shifted glacier must get full Copernicus weight");
            float[] glacierTile = shiftedGlacier.tile(zoom, tx, ty).clone();
            v3Glacier.apply(glacierTile, zoom, tx, ty);
            int bestPy = 0, bestPx = 0;
            for (int py = 0; py < SIDE; py++)
                if (Math.abs(tileLat(zoom, ty, py) - 63.75) < Math.abs(tileLat(zoom, ty, bestPy) - 63.75)) bestPy = py;
            for (int px = 0; px < SIDE; px++)
                if (Math.abs(tileLon(zoom, tx, px) - (-19.5)) < Math.abs(tileLon(zoom, tx, bestPx) - (-19.5))) bestPx = px;
            double expectedCop = smoothGround(tileLat(zoom, ty, bestPy), tileLon(zoom, tx, bestPx));
            assertEquals(expectedCop, glacierTile[bestPy * SIDE + bestPx], 2.0, "sunk ice pit must be lifted back to Copernicus");

            // 3. prefetch() on a datum-shifted tile (weight == 0, datum != 0) must prefetch full-res Copernicus blocks.
            try (var demPrefetch = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
                CopernicusFill.Tiles pureShifted = (z, x, y) -> {
                    float[] t = new float[SIDE * SIDE];
                    for (int py = 0; py < SIDE; py++) {
                        double lat = tileLat(z, y, py);
                        for (int px = 0; px < SIDE; px++) {
                            double g = smoothGround(lat, tileLon(z, x, px));
                            t[py * SIDE + px] = g == 0.0 ? 0f : (float) (g + 55.0);
                        }
                    }
                    return t;
                };
                CopernicusFill v3Prefetch = new CopernicusFill(demPrefetch, pureShifted, (lat, lon) -> false, true, 16);
                // First prime the check parts only, then verify prefetch fetches the finer overview block!
                assertEquals(55.0, v3Prefetch.datum(63.75, -19.5), 1.5);
                assertEquals(0.0, v3Prefetch.weight(63.75, -19.5), 1e-3);
                int beforePrefetch = cogRequests.get();
                v3Prefetch.prefetch(zoom, tx, ty);
                assertTrue(cogRequests.get() > beforePrefetch,
                        "prefetch() in glo30-fill-v3 must prefetch Copernicus blocks when datum != 0");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void terrariumV2PropagatesTransientNeighbourZ11MissWithoutPoisoningCacheAndSharesConcurrentCleaned() throws Exception {
        int refX = 1000, refY = 600;
        int z15X = refX << 4, z15Y = refY << 4; // ox == 0, oy == 0 on western z11 border!
        float[] z11Parent = new float[SIDE * SIDE];
        Arrays.fill(z11Parent, 100f);
        float[] z11West = new float[SIDE * SIDE];
        Arrays.fill(z11West, 500f);
        float[] z15Raw = new float[SIDE * SIDE];
        Arrays.fill(z15Raw, 100f);
        // 10x10 void block flush against western border (px = 0..9, py = 123..133) so (0, 128) relies purely on expected[i]
        // (bilinear blend of west z11 = 500 m and parent z11 = 100 m -> ~287.5 m, vs 100 m if wrongly clamped!).
        for (int py = 123; py <= 133; py++)
            for (int px = 0; px <= 9; px++)
                z15Raw[py * SIDE + px] = -20_000f;

        byte[] parentPng = TerrariumTileSource.encode(z11Parent);
        byte[] westPng = TerrariumTileSource.encode(z11West);
        byte[] z15Png = TerrariumTileSource.encode(z15Raw);

        AtomicBoolean failWest = new AtomicBoolean(true);
        AtomicInteger concurrentCleanHits = new AtomicInteger();
        CountDownLatch slowRelease = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/tiles/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/tiles/".length());
            if (path.equals("11/" + (refX - 1) + "/" + refY + ".png") && failWest.get()) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (path.equals("11/" + (refX + 5) + "/" + refY + ".png")) {
                concurrentCleanHits.incrementAndGet();
                try { slowRelease.await(); } catch (InterruptedException ignored) {}
            }
            byte[] body;
            if (path.equals("15/" + z15X + "/" + z15Y + ".png")) body = z15Png;
            else if (path.equals("11/" + (refX - 1) + "/" + refY + ".png")) body = westPng;
            else body = parentPng;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tiles/");
        try (var v2 = new TerrariumTileSource(base, null, true, 64, 8, Duration.ofSeconds(5), List.of(), true)) {
            // 1. Transient neighbour z11 failure must propagate MissingTerrainException and NOT cache a clamped tile!
            long failuresBefore = v2.stats().failures();
            assertThrows(MissingTerrainException.class, () -> v2.tile(15, z15X, z15Y),
                    "transient neighbour z11 miss must propagate instead of caching clamped parent");
            assertTrue(v2.stats().failures() > failuresBefore, "failures counter must increment on cleaned()/tile() miss");

            // 2. Once neighbour z11 recovers, retry succeeds and uses the real 500 m western neighbour (~287.5 m, NOT 100 m!).
            failWest.set(false);
            float[] recovered = v2.tile(15, z15X, z15Y);
            assertEquals(287.5f, recovered[128 * SIDE + 0], 5f,
                    "recovered z15 border repair must use 500 m western neighbour (~287.5 m), not poisoned 100 m clamp");

            // 3. Concurrent cleaned() requests for the same tile share one single download.
            ExecutorService pool = Executors.newFixedThreadPool(6);
            try {
                List<Future<float[]>> futures = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    futures.add(pool.submit(() -> v2.cleaned(11, refX + 5, refY)));
                }
                Thread.sleep(100);
                slowRelease.countDown();
                float[] first = futures.get(0).get();
                for (Future<float[]> f : futures) assertSame(first, f.get(), "concurrent cleaned() must share single-flight result");
                assertEquals(1, concurrentCleanHits.get(), "concurrent cleaned() must only download tile once");
            } finally {
                pool.shutdownNow();
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3PreservesValidDeepCoastalBathymetryWithoutVoidSentinel() throws Exception {
        // West half (lon < -19.5): sharp coastline aligned on an overview-2 boundary (lat = 63.5).
        // East half (lon >= -19.5): steep off-grid coastline where coastal overview-2 row 63 (center 63.50390625)
        // averages +36 m land while Terrarium has genuine -900 m bathymetry (triggering landVoid / nearLandVoid)
        // with NO -32768 void sentinel anywhere.
        DoubleBinaryOperator ground = (lat, lon) -> {
            if (lon < -19.5) return lat >= 63.5 ? 100.0 : 0.0;
            if (lat >= 63.508) return 100.0;
            if (lat >= 63.503) return 36.0;
            return 0.0;
        };
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            CopernicusFill.Tiles tiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        double coast = lon < -19.5 ? 63.5 : 63.506;
                        t[py * SIDE + px] = lat >= coast ? 100f : -900f;
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, tiles, (lat, lon) -> false, true, 16);
            int zoom = 15;

            // 1. Aligned overview-2 coastline (west half): fine sea preserves -900 m untouched.
            int txAligned = mercatorTileX(zoom, -19.75), tyAligned = mercatorTileY(zoom, 63.496);
            float[] fineAligned = tiles.tile(zoom, txAligned, tyAligned).clone();
            v3.apply(fineAligned, zoom, txAligned, tyAligned);
            int checkedAligned = 0;
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, tyAligned, py);
                if (lat >= 63.499) continue;
                for (int px = 0; px < SIDE; px++) {
                    assertEquals(-900f, fineAligned[py * SIDE + px], 1e-4f,
                            "valid -900 m deep coastal bathymetry without void sentinel must never be erased to 0f");
                    checkedAligned++;
                }
            }
            assertTrue(checkedAligned > 1000, "expected aligned deep coastal bathymetry pixels checked, got " + checkedAligned);

            // 2. Off-grid mixed-overview coastline (east half): overview-2 sees +36 m mixed land while Terrarium is -900 m
            //    (marking landVoid/nearLandVoid), yet fine sea (cop == 0) must still preserve exact -900 m!
            assertEquals(36.0, dem.metres(63.50390625, -19.25, 2), 0.1,
                    "off-grid coastal overview-2 cell must average +36 m mixed land to trigger landVoid");
            int txMixed = mercatorTileX(zoom, -19.25), tyMixed = mercatorTileY(zoom, 63.500);
            float[] fineMixed = tiles.tile(zoom, txMixed, tyMixed).clone();
            v3.apply(fineMixed, zoom, txMixed, tyMixed);
            int checkedMixed = 0;
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, tyMixed, py);
                if (lat >= 63.502) continue;
                for (int px = 0; px < SIDE; px++) {
                    double lon = tileLon(zoom, txMixed, px);
                    if (dem.metres(lat, lon, 0) > 0.01) continue;
                    assertEquals(-900f, fineMixed[py * SIDE + px], 1e-4f,
                            "mixed-overview landVoid without void sentinel must never erase fine -900 m bathymetry to 0f");
                    checkedMixed++;
                }
            }
            assertTrue(checkedMixed > 1000, "expected mixed-overview deep coastal bathymetry pixels checked, got " + checkedMixed);

            // 3. Positive coastal pixel (+2 m) over fine Copernicus sea (cop == 0) with zero datum and no void sentinel
            //    inside the negOverSea nearLandVoid halo (rows 64..65, centers 63.4961 & 63.4883) adjacent to the mixed
            //    overview-2 +36 m / -900 m cell: weight must stay 0 so the +2 m pixel stays +2 m (not flattened to 0 m).
            assertEquals(0.0, v3.weight(63.493, -19.25), 1e-6,
                    "nearLandVoid on negOverSea cells must not leak into weight");
            assertEquals(0.0, v3.datum(63.493, -19.25), 1e-6,
                    "zero-datum coastal fixture must keep datum at 0");
            int tyCoastalPos = mercatorTileY(zoom, 63.493);
            float[] finePos = tiles.tile(zoom, txMixed, tyCoastalPos).clone();
            int seededPos = 0;
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, tyCoastalPos, py);
                if (lat > 63.496 || lat < 63.489) continue;
                for (int px = 0; px < SIDE; px++) {
                    double lon = tileLon(zoom, txMixed, px);
                    if (dem.metres(lat, lon, 0) > 0.01) continue;
                    finePos[py * SIDE + px] = 2f;
                    seededPos++;
                }
            }
            assertTrue(seededPos > 1000, "expected >1000 positive coastal pixels seeded over fine sea, got " + seededPos);
            v3.apply(finePos, zoom, txMixed, tyCoastalPos);
            int checkedPos = 0;
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, tyCoastalPos, py);
                if (lat > 63.496 || lat < 63.489) continue;
                for (int px = 0; px < SIDE; px++) {
                    double lon = tileLon(zoom, txMixed, px);
                    if (dem.metres(lat, lon, 0) > 0.01) continue;
                    assertEquals(2f, finePos[py * SIDE + px], 1e-4f,
                            "positive +2 m coastal pixel over fine sea adjacent to mixed-overview landVoid must not be flattened to 0 m");
                    checkedPos++;
                }
            }
            assertEquals(seededPos, checkedPos);
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3RepairsNarrowFjordVoidRampOverMixedOverviewAndFineLandOverSeaOverview() throws Exception {
        // Case A (lon < -19.5): narrow fjord where overview-2 center (63.50390625) sees +60 m land (rawCop > SEA_CRUMB)
        //   while overview-0 row 255 (lat in (63.500, 63.502)) resolves fine sea (0 m), with a -32768 void sentinel nearby.
        // Case B (lon >= -19.5): coastal islet where overview-2 centers (63.5039, 63.4961) see sea (rawCop == 0),
        //   main land sits north of 63.51 (+80 m), and overview-0 resolves +38.4 m land inside (63.498, 63.502),
        //   under an explicit -200 m coastal land void ramp.
        DoubleBinaryOperator ground = (lat, lon) -> {
            if (lon < -19.5) {
                return (lat > 63.498 && lat < 63.502) ? 0.0 : 60.0;
            }
            if (lat >= 63.51) return 80.0;
            if (lat > 63.498 && lat < 63.502) return 38.4;
            return 0.0;
        };
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            CopernicusFill.Tiles tiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        if (lon < -19.5) {
                            // Narrow-fjord -32768 void sentinel just north of the strip + -200 m ramp over the strip.
                            if (lat >= 63.508 && lat <= 63.516 && lon > -19.78 && lon < -19.72) t[py * SIDE + px] = -32768f;
                            else if (lat >= 63.49 && lat <= 63.52 && lon > -19.80 && lon < -19.70) t[py * SIDE + px] = -200f;
                            else t[py * SIDE + px] = 60f;
                        } else {
                            // Explicit -200 m coastal void ramp straddling land (>= 63.51) and overview-2 sea (< 63.51).
                            if (lat >= 63.49 && lat <= 63.525 && lon > -19.30 && lon < -19.20) t[py * SIDE + px] = -200f;
                            else t[py * SIDE + px] = (float) ground.applyAsDouble(lat, lon);
                        }
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, tiles, (lat, lon) -> false, true, 16);
            int zoom = 15;

            // Case A: overview-2 sees land (+60 m), fine overview-0 sees sea (0 m); -200 m ramp must repair to exact 0f.
            int fx = mercatorTileX(zoom, -19.75), fy = mercatorTileY(zoom, 63.500);
            int fPy = -1, fPx = 128;
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, fy, py);
                if (lat > 63.4992 && lat < 63.5008) { fPy = py; break; }
            }
            assertTrue(fPy >= 0, "expected fine pixel inside narrow fjord sea strip");
            double fjordLat = tileLat(zoom, fy, fPy), fjordLon = tileLon(zoom, fx, fPx);
            assertTrue(dem.metres(fjordLat, fjordLon, 2) > 10.0, "overview-2 must see mixed/land (> SEA_CRUMB)");
            assertEquals(0.0, dem.metres(fjordLat, fjordLon, 0), 1e-3, "fine overview-0 must resolve sea");
            float[] fjordTile = tiles.tile(zoom, fx, fy).clone();
            fjordTile[fPy * SIDE + fPx] = -200f;
            v3.apply(fjordTile, zoom, fx, fy);
            assertEquals(0f, fjordTile[fPy * SIDE + fPx], 0f,
                    "narrow-fjord -32768 void ramp where overview-2 sees land must still repair fine sea pixel to 0f");

            // Case B: overview-2 sees sea (0 m), fine overview-0 resolves +38.4 m land; -200 m ramp must repair to +38.4 m!
            int ix = mercatorTileX(zoom, -19.25), iy = mercatorTileY(zoom, 63.500);
            int iPy = -1, iPx = 128;
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, iy, py);
                if (lat > 63.4992 && lat < 63.5008) { iPy = py; break; }
            }
            assertTrue(iPy >= 0, "expected fine pixel inside coastal islet");
            double isletLat = tileLat(zoom, iy, iPy), isletLon = tileLon(zoom, ix, iPx);
            assertEquals(0.0, dem.metres(isletLat, isletLon, 2), 1e-3, "overview-2 must see sea (rawCop == 0)");
            assertEquals(38.4, dem.metres(isletLat, isletLon, 0), 0.1, "fine overview-0 must resolve +38.4 m land");
            float[] isletTile = tiles.tile(zoom, ix, iy).clone();
            isletTile[iPy * SIDE + iPx] = -200f;
            v3.apply(isletTile, zoom, ix, iy);
            assertEquals(38.4f, isletTile[iPy * SIDE + iPx], 0.5f,
                    "explicit -200 m void ramp over overview-2 sea must repair fine Copernicus land to +38.4 m");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3PreservesPerCornerMaxWeightAndLandVoidBeforeInterpolation() throws Exception {
        // Verify per-corner max(weight, landVoid) is computed BEFORE bilinear interpolation both across a part
        // boundary (west half: row 63 in partRow=1 with weight=1, landVoid=0 vs row 64 in partRow=2 with
        // weight=0, landVoid=1) and inside a single part (east half: row 64 vs row 65 inside partRow=2).
        DoubleBinaryOperator ground = (lat, lon) -> {
            if (lon < -19.5) {
                if (lat >= 63.502) return 80.0;
                if (lat > 63.498) return 38.4;
                return 0.0;
            }
            if (lat >= 63.494) return 80.0;
            if (lat > 63.490) return 38.4;
            return 0.0;
        };
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            CopernicusFill.Tiles tiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        if (lon > -19.80 && lon < -19.70) {
                            // Row 63 (63.5039) gets -30 m (>= VOID_SHALLOW = -40 -> weight=1, landVoid=0, landVoid flag set);
                            // Row 64 (63.4961) sees sea overview-2 with -200 m (< VOID_SHALLOW -> weight=0, landVoid=1).
                            if (lat >= 63.501 && lat <= 63.52) t[py * SIDE + px] = -30f;
                            else if (lat >= 63.48 && lat < 63.501) t[py * SIDE + px] = -200f;
                            else t[py * SIDE + px] = (float) ground.applyAsDouble(lat, lon);
                        } else if (lon > -19.30 && lon < -19.20) {
                            // Row 64 (63.4961) gets -30 m (weight=1, landVoid=0);
                            // Row 65 (63.4883) sees sea overview-2 with -200 m (weight=0, landVoid=1).
                            if (lat >= 63.493 && lat <= 63.51) t[py * SIDE + px] = -30f;
                            else if (lat >= 63.475 && lat < 63.493) t[py * SIDE + px] = -200f;
                            else t[py * SIDE + px] = (float) ground.applyAsDouble(lat, lon);
                        } else {
                            t[py * SIDE + px] = (float) ground.applyAsDouble(lat, lon);
                        }
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, tiles, (lat, lon) -> false, true, 32);
            int zoom = 15;

            // 1. Cross-part boundary midpoint (row 63 in partRow=1 with landVoid==null, weight=1 vs row 64 in partRow=2
            //    with weight=0, landVoid=1): midpoint weight == 0.5, yet per-corner max(weight, landVoid) == 1.0!
            assertEquals(1.0, v3.weight(63.50390625, -19.75), 1e-6, "land overview-2 corner (row 63) must have weight == 1");
            assertEquals(0.0, v3.weight(63.49609375, -19.75), 1e-6, "sea overview-2 corner (row 64) must have weight == 0");
            int wx = mercatorTileX(zoom, -19.75), wy = mercatorTileY(zoom, 63.500);
            int wPy = -1, wPx = 128;
            double bestDist = Double.MAX_VALUE;
            for (int py = 0; py < SIDE; py++) {
                double d = Math.abs(tileLat(zoom, wy, py) - 63.500);
                if (d < bestDist) { bestDist = d; wPy = py; }
            }
            double wLat = tileLat(zoom, wy, wPy), wLon = tileLon(zoom, wx, wPx);
            double wMid = v3.weight(wLat, wLon);
            assertTrue(wMid > 0.45 && wMid < 0.55, "expected midpoint weight near 0.5, got " + wMid);
            assertEquals(38.4, dem.metres(wLat, wLon, 0), 0.1, "fine overview-0 at midpoint must be +38.4 m");
            float[] westTile = tiles.tile(zoom, wx, wy).clone();
            westTile[wPy * SIDE + wPx] = -200f;
            v3.apply(westTile, zoom, wx, wy);
            assertEquals(38.4f, westTile[wPy * SIDE + wPx], 0.1f,
                    "midpoint between weight=1/landVoid=0 and weight=0/landVoid=1 across part boundary must repair to +38.4 m");

            // Also verify insidePart fast path where here.landVoid == null and weight == 1 (between rows 62 & 63):
            int iyLand = mercatorTileY(zoom, 63.5078125);
            int lPy = 128;
            double lLat = tileLat(zoom, iyLand, lPy), lLon = tileLon(zoom, wx, wPx);
            assertEquals(1.0, v3.weight(lLat, lLon), 1e-6, "interior partRow=1 land corner must have weight == 1");
            assertEquals(80.0, dem.metres(lLat, lLon, 0), 0.1, "interior land must be +80.0 m");
            float[] landTile = tiles.tile(zoom, wx, iyLand).clone();
            landTile[lPy * SIDE + wPx] = -200f;
            v3.apply(landTile, zoom, wx, iyLand);
            assertEquals(80.0f, landTile[lPy * SIDE + wPx], 0.1f,
                    "insidePart with null landVoid and weight == 1 must still carry out[3] = out[0] = 1 and repair to +80 m");

            // 2. Intra-part midpoint (row 64 weight=1/landVoid=0 vs row 65 weight=0/landVoid=1, both in partRow=2):
            assertEquals(1.0, v3.weight(63.49609375, -19.25), 1e-6, "land overview-2 corner (row 64) must have weight == 1");
            assertEquals(0.0, v3.weight(63.48828125, -19.25), 1e-6, "sea overview-2 corner (row 65) must have weight == 0");
            int ex = mercatorTileX(zoom, -19.25), ey = mercatorTileY(zoom, 63.4921875);
            int ePy = -1, ePx = 128;
            bestDist = Double.MAX_VALUE;
            for (int py = 0; py < SIDE; py++) {
                double d = Math.abs(tileLat(zoom, ey, py) - 63.4921875);
                if (d < bestDist) { bestDist = d; ePy = py; }
            }
            double eLat = tileLat(zoom, ey, ePy), eLon = tileLon(zoom, ex, ePx);
            double eMid = v3.weight(eLat, eLon);
            assertTrue(eMid > 0.45 && eMid < 0.55, "expected intra-part midpoint weight near 0.5, got " + eMid);
            assertEquals(38.4, dem.metres(eLat, eLon, 0), 0.1, "fine overview-0 at intra-part midpoint must be +38.4 m");
            float[] eastTile = tiles.tile(zoom, ex, ey).clone();
            eastTile[ePy * SIDE + ePx] = -200f;
            v3.apply(eastTile, zoom, ex, ey);
            assertEquals(38.4f, eastTile[ePy * SIDE + ePx], 0.1f,
                    "midpoint between weight=1/landVoid=0 and weight=0/landVoid=1 inside same part must repair to +38.4 m");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3GatesLandVoidWeightAwayFromNegativeCopernicusDepressions() throws Exception {
        // Inside a nearLandVoid halo (weight == 0, voidWeight == 0, landVoidWeight == 1), positive fine Copernicus land
        // (+38.4 m) repairs via landVoidWeight, whereas a negative Copernicus elevation < -SEA_CRUMB (-20 m below-sea-level
        // depression) must NOT apply landVoidWeight and must leave t unrepaired.
        DoubleBinaryOperator ground = (lat, lon) -> {
            if (lat >= 63.51) return 80.0;
            if (lat > 63.498 && lat < 63.502) return lon < -19.25 ? 38.4 : -20.0;
            return 0.0;
        };
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            CopernicusFill.Tiles tiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        if (lat >= 63.49 && lat <= 63.525 && lon > -19.35 && lon < -19.15) t[py * SIDE + px] = -200f;
                        else t[py * SIDE + px] = (float) ground.applyAsDouble(lat, lon);
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, tiles, (lat, lon) -> false, true, 32);
            int zoom = 15;

            // Positive control in the same nearLandVoid halo: confirms weight == 0, datum == 0, and landVoidWeight == 1.
            int posX = mercatorTileX(zoom, -19.30), ty = mercatorTileY(zoom, 63.500);
            int py = -1, px = 128;
            for (int r = 0; r < SIDE; r++) {
                double lat = tileLat(zoom, ty, r);
                if (lat > 63.4992 && lat < 63.5008) { py = r; break; }
            }
            assertTrue(py >= 0, "expected fine pixel inside test strip");
            double lat = tileLat(zoom, ty, py), posLon = tileLon(zoom, posX, px);
            assertEquals(0.0, v3.weight(lat, posLon), 1e-6, "control cell must have weight == 0");
            assertEquals(0.0, v3.datum(lat, posLon), 1e-6, "control cell must have datum == 0");
            assertEquals(0.0, dem.metres(lat, posLon, 2), 1e-3, "control overview-2 must see sea");
            assertEquals(38.4, dem.metres(lat, posLon, 0), 0.1, "control fine overview-0 must see +38.4 m land");
            float[] posTile = tiles.tile(zoom, posX, ty).clone();
            posTile[py * SIDE + px] = -200f;
            v3.apply(posTile, zoom, posX, ty);
            assertEquals(38.4f, posTile[py * SIDE + px], 0.1f,
                    "control +38.4 m pixel confirms landVoidWeight == 1 is active in this halo");

            // Negative below-sea-level Copernicus depression (-20 m < -SEA_CRUMB) in the same nearLandVoid halo:
            int negX = mercatorTileX(zoom, -19.20);
            double negLon = tileLon(zoom, negX, px);
            assertEquals(0.0, v3.weight(lat, negLon), 1e-6, "depression cell must have weight == 0");
            assertEquals(0.0, v3.datum(lat, negLon), 1e-6, "depression cell must have datum == 0");
            assertEquals(0.0, dem.metres(lat, negLon, 2), 1e-3, "depression overview-2 must see sea");
            assertEquals(-20.0, dem.metres(lat, negLon, 0), 0.1, "depression fine overview-0 must see -20 m");
            float[] negTile = tiles.tile(zoom, negX, ty).clone();
            negTile[py * SIDE + px] = -200f;
            v3.apply(negTile, zoom, negX, ty);
            assertEquals(-200f, negTile[py * SIDE + px], 1e-4f,
                    "negative Copernicus elevation < -SEA_CRUMB inside nearLandVoid halo must NOT apply landVoidWeight");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3OfflineInteriorPartDoesNotQueryUncachedSwNeighbourSquare() throws Exception {
        // Only cache square (63, -20) on disk; SW neighbours (62, -21), (62, -20), (63, -21) are NOT cached.
        DoubleBinaryOperator ground = (lat, lon) -> 120.0 + 15.0 * Math.sin(lat * 4.0);
        Map<String, byte[]> files = Map.of(
                CopernicusSource.name(63, -20) + ".tif", syntheticCog(63, -20, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        Path cacheRoot = Files.createTempDirectory("cop-offline-interior");
        try {
            // Prime disk cache for square (63, -20) ONLY while online.
            try (var online = new CopernicusSource(base, cacheRoot, true, 32, 4, Duration.ofSeconds(5))) {
                assertTrue(online.covers(63.5, -19.5));
                assertFalse(Double.isNaN(online.metres(63.62, -19.62, 2)));
                assertFalse(Double.isNaN(online.metres(63.62, -19.62, 0)));
            }
            // Reopen strictly OFFLINE (allowNetwork = false): any lookup of (62, -21), (62, -20), or (63, -21) throws!
            try (var offline = new CopernicusSource(base, cacheRoot, false, 32, 4, Duration.ofSeconds(5))) {
                CopernicusFill.Tiles shifted = (z, x, y) -> {
                    float[] t = new float[SIDE * SIDE];
                    for (int py = 0; py < SIDE; py++) {
                        double lat = tileLat(z, y, py);
                        for (int px = 0; px < SIDE; px++) {
                            t[py * SIDE + px] = (float) (ground.applyAsDouble(lat, tileLon(z, x, px)) + 55.0);
                        }
                    }
                    return t;
                };
                CopernicusFill v3 = new CopernicusFill(offline, shifted, (lat, lon) -> false, true, 16);
                // Interior part (partRow=1, partColumn=1 -> lat in [63.5, 63.75], lon in [-19.75, -19.5]):
                // must complete cleanly offline without touching uncached SW neighbour squares!
                assertEquals(55.0, v3.datum(63.62, -19.62), 1.0, "offline interior part must estimate datum cleanly");
                int zoom = 15;
                int tx = mercatorTileX(zoom, -19.62), ty = mercatorTileY(zoom, 63.62);
                float[] fine = shifted.tile(zoom, tx, ty).clone();
                assertDoesNotThrow(() -> v3.apply(fine, zoom, tx, ty),
                        "offline interior part must not throw MissingTerrainException for uncached SW neighbour");
                double expected = ground.applyAsDouble(tileLat(zoom, ty, 128), tileLon(zoom, tx, 128));
                assertEquals(expected, fine[128 * SIDE + 128], 1.5, "offline interior part must calibrate cleanly");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
            try (var walk = Files.walk(cacheRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void glo30FillV3RejectsForestCanopyNegativeOffsetAtBothTemperateAndBorealLatitudes() throws Exception {
        // Coherent -14 m bare-earth lidar vs DSM canopy offset at 45°N (inside SRTM) AND 62°N (boreal north of 60°N),
        // alongside coastal low beaches (3 m) and sea (0 m), must both yield datum == 0 and leave coast untouched.
        DoubleBinaryOperator ground = (lat, lon) -> {
            double inLat = lat - Math.floor(lat);
            if (inLat < 0.25) return 0.0;
            if (inLat < 0.30) return 3.0; // low coastal beach
            return 140.0 + 10.0 * Math.sin(lat * 5.0);
        };
        Map<String, byte[]> files = new HashMap<>();
        for (int s : new int[] {44, 45, 46, 61, 62, 63})
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 64, 4, Duration.ofSeconds(5))) {
            CopernicusFill.Tiles forestTiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double g = ground.applyAsDouble(lat, tileLon(z, x, px));
                        // Tall forest canopy: bare-earth Terrarium sits 14 m below Copernicus DSM on forested inland,
                        // while beach/sea are aligned.
                        t[py * SIDE + px] = g > 20.0 ? (float) (g - 14.0) : (float) g;
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, forestTiles, (lat, lon) -> false, true, 32);

            assertEquals(0.0, v3.datum(45.6, -19.5), 1e-6, "45°N temperate forest (-14 m) must produce datum == 0");
            assertEquals(0.0, v3.datum(62.6, -19.5), 1e-6, "62°N boreal forest (-14 m) must produce datum == 0");
            assertEquals(0.0, v3.weight(45.6, -19.5), 1e-6, "45°N forest must not blend to canopy DSM");
            assertEquals(0.0, v3.weight(62.6, -19.5), 1e-6, "62°N forest must not blend to canopy DSM");

            // Coastal beaches at both 45°N and 62°N stay completely untouched!
            int zoom = 15;
            for (double beachLat : new double[] {45.275, 62.275}) {
                int tx = mercatorTileX(zoom, -19.5), ty = mercatorTileY(zoom, beachLat);
                float[] before = forestTiles.tile(zoom, tx, ty).clone();
                float[] after = before.clone();
                v3.apply(after, zoom, tx, ty);
                assertArrayEquals(before, after, 0f, "coastal beach at " + beachLat + "°N must stay untouched");
            }

            // Directly pin NEGATIVE_OFFSET_AGREE = 22 with a stronger -18 m coherent boreal canopy offset at 62°N.
            CopernicusFill.Tiles tallCanopyTiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double g = ground.applyAsDouble(lat, tileLon(z, x, px));
                        t[py * SIDE + px] = g > 20.0 ? (float) (g - 18.0) : (float) g;
                    }
                }
                return t;
            };
            CopernicusFill v3Tall = new CopernicusFill(dem, tallCanopyTiles, (lat, lon) -> false, true, 32);
            assertEquals(0.0, v3Tall.datum(62.6, -19.5), 1e-6, "62°N -18 m coherent offset must stay below NEGATIVE_OFFSET_AGREE = 22");
            assertEquals(0.0, v3Tall.weight(62.6, -19.5), 1e-6, "62°N -18 m boreal canopy must not blend to Copernicus DSM");
            int btx = mercatorTileX(zoom, -19.5), bty = mercatorTileY(zoom, 62.275);
            float[] tallBefore = tallCanopyTiles.tile(zoom, btx, bty).clone();
            float[] tallAfter = tallBefore.clone();
            v3Tall.apply(tallAfter, zoom, btx, bty);
            assertArrayEquals(tallBefore, tallAfter, 0f, "62°N coastal beach under -18 m boreal canopy must stay untouched");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3CalibratesCoherentNegativeGlacierDatumWhileRepairingLocalizedSunkPit() throws Exception {
        DoubleBinaryOperator ground = (lat, lon) -> 200.0 + 20.0 * Math.sin((lat - 64.0) * 5.0) + 10.0 * Math.cos((lon + 20.0) * 4.0);
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 63; s <= 65; s++)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            double datumOffset = -55.0;
            CopernicusFill.Tiles glacierTiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double lon = tileLon(z, x, px);
                        double g = ground.applyAsDouble(lat, lon);
                        boolean pit = Math.abs(lat - 64.25) < 0.008 && Math.abs(lon - (-19.5)) < 0.008;
                        t[py * SIDE + px] = (float) (g + datumOffset - (pit ? 30.0 : 0.0));
                    }
                }
                return t;
            };
            // Entire region is mapped as snow/ice in WorldCover.
            CopernicusFill v3 = new CopernicusFill(dem, glacierTiles, (lat, lon) -> true, true, 16);

            // 1. Coherent -55 m glacier offset at 64.75°N calibrates datum ≈ -55 m (NOT discarded by ice.at())
            //    and preserves a sharp +20 m fine-resolution glacier feature!
            assertEquals(datumOffset, v3.datum(64.75, -19.5), 1.5, "coherent -55 m glacier offset must calibrate datum");
            assertEquals(0.0, v3.weight(64.75, -19.5), 1e-3, "coherent -55 m glacier must keep Copernicus blend weight 0");
            int zoom = 15;
            int tx = mercatorTileX(zoom, -19.5), ty = mercatorTileY(zoom, 64.75);
            float[] fine = new float[SIDE * SIDE];
            float[] expected = new float[SIDE * SIDE];
            for (int py = 0; py < SIDE; py++) {
                double lat = tileLat(zoom, ty, py);
                for (int px = 0; px < SIDE; px++) {
                    double g = ground.applyAsDouble(lat, tileLon(zoom, tx, px));
                    double ridge = (px >= 120 && px <= 135) ? 20.0 : 0.0;
                    expected[py * SIDE + px] = (float) (g + ridge);
                    fine[py * SIDE + px] = (float) (g + ridge + datumOffset);
                }
            }
            v3.apply(fine, zoom, tx, ty);
            assertEquals(20.0, fine[128 * SIDE + 128] - fine[128 * SIDE + 100], 1.0,
                    "coherent negative glacier datum must preserve +20 m fine glacier feature");
            assertEquals(expected[128 * SIDE + 128], fine[128 * SIDE + 128], 1.5f,
                    "fine glacier feature must be lifted to orthometric height");

            // 2. Localized -30 m sunk glacier pit at (64.25, -19.5) still repairs cleanly to Copernicus!
            assertEquals(1.0, v3.weight(64.25, -19.5), 1e-3, "localized sunk glacier pit must still get full repair weight");
            int pitY = mercatorTileY(zoom, 64.25);
            float[] pitTile = glacierTiles.tile(zoom, tx, pitY).clone();
            v3.apply(pitTile, zoom, tx, pitY);
            int bestPy = 0, bestPx = 0;
            for (int py = 0; py < SIDE; py++)
                if (Math.abs(tileLat(zoom, pitY, py) - 64.25) < Math.abs(tileLat(zoom, pitY, bestPy) - 64.25)) bestPy = py;
            for (int px = 0; px < SIDE; px++)
                if (Math.abs(tileLon(zoom, tx, px) - (-19.5)) < Math.abs(tileLon(zoom, tx, bestPx) - (-19.5))) bestPx = px;
            double expectedCop = ground.applyAsDouble(tileLat(zoom, pitY, bestPy), tileLon(zoom, tx, bestPx));
            assertEquals(expectedCop, pitTile[bestPy * SIDE + bestPx], 2.0, "localized sunk glacier pit must repair to Copernicus");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void terrariumV2PolarNorthEdgeCornerClampsOnlyYAndStillWrapsXNeighbour() throws Exception {
        // North-west corner of the Mercator z11 grid: refX = 0, refY = 0 (wrapped west neighbour is refX = 2047, refY = 0!).
        int refTiles = 1 << TerrariumTileSource.REFERENCE_ZOOM;
        int refX = 0, refY = 0;
        int westWrappedX = refTiles - 1;
        int z15X = 0, z15Y = 0; // ox == 0, oy == 0: touches BOTH north Mercator edge (ny = -1) AND antimeridian west wrap!
        float[] z11Parent = new float[SIDE * SIDE];
        Arrays.fill(z11Parent, 100f);
        float[] z11WestWrapped = new float[SIDE * SIDE];
        Arrays.fill(z11WestWrapped, 500f);
        float[] z15Raw = new float[SIDE * SIDE];
        Arrays.fill(z15Raw, 100f);
        // 10x100 void block flush against NW corner (py = 0..9, px = 0..99, wider than TileSeams's 12" whole-degree
        // voidSlot reach at lon = -180°): pixel (0, 0) reaches repairWithBorder and samples (gy = -1, gx = -1).
        // With Y-only clamping + X wrapping, both (gy=-1, gx=-1) and (gy=0, gx=-1) read 500 m -> ~287.5 m;
        // falling back to parent reference[0][0] = 100 m on ny < 0 would instead give ~199.6 m!
        for (int py = 0; py <= 9; py++)
            for (int px = 0; px <= 99; px++)
                z15Raw[py * SIDE + px] = -20_000f;

        byte[] parentPng = TerrariumTileSource.encode(z11Parent);
        byte[] westPng = TerrariumTileSource.encode(z11WestWrapped);
        byte[] z15Png = TerrariumTileSource.encode(z15Raw);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/tiles/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/tiles/".length());
            byte[] body;
            if (path.equals("15/" + z15X + "/" + z15Y + ".png")) body = z15Png;
            else if (path.equals("11/" + westWrappedX + "/" + refY + ".png")) body = westPng;
            else body = parentPng;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tiles/");
        try (var v2 = new TerrariumTileSource(base, null, true, 64, 4, Duration.ofSeconds(5), List.of(), true)) {
            float[] repaired = v2.tile(15, z15X, z15Y);
            assertEquals(287.5f, repaired[0], 5f,
                    "north Mercator edge corner must clamp only Y and retain wrapped X neighbour (~287.5 m, not ~199.6 m)");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3SubMetreDatumAppliesLandFloorAndSeaZeroWithoutZeroingPositiveCoast() throws Exception {
        // Square (63, -20) has a +55 m regional datum offset while western neighbour (63, -21) is missing,
        // so datum tapers smoothly to 0 at lon = -20.0, producing 0 < datum < 1.0 with weight == 0.0 just inside.
        DoubleBinaryOperator ground = (lat, lon) -> {
            if (lat > 63.495 && lat < 63.505) return 0.0;      // narrow confirmed sea strip
            if (lat >= 63.505 && lat < 63.520) return 0.05;    // low Copernicus-confirmed coastal land (> SEA_CRUMB)
            return 100.0;
        };
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 62; s <= 64; s++)
            for (int w = -20; w <= -19; w++) // intentionally omit w = -21
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 256, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            CopernicusFill.Tiles tiles = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        double g = ground.applyAsDouble(lat, tileLon(z, x, px));
                        t[py * SIDE + px] = g == 0.0 ? 0f : (float) (g + 55.0);
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, tiles, (lat, lon) -> false, true, 32);

            int zoom = 15;
            int tx = mercatorTileX(zoom, -19.99988);
            int tySea = mercatorTileY(zoom, 63.500);
            int tyLand = mercatorTileY(zoom, 63.512);

            // Locate a z15 column just inside lon = -20.0 where 0.5 < datum < 0.95 and weight == 0.0.
            int targetPx = -1;
            double seaLat = tileLat(zoom, tySea, 128);
            double landLat = tileLat(zoom, tyLand, 128);
            for (int px = 0; px < SIDE; px++) {
                double lon = tileLon(zoom, tx, px);
                if (lon <= -20.0 || lon >= -19.998) continue;
                double dSea = v3.datum(seaLat, lon), dLand = v3.datum(landLat, lon);
                if (dSea > 0.5 && dSea < 0.95 && dLand > 0.5 && dLand < 0.95
                        && v3.weight(seaLat, lon) == 0.0 && v3.weight(landLat, lon) == 0.0) {
                    targetPx = px;
                    break;
                }
            }
            assertTrue(targetPx >= 0, "expected z15 column with 0.5 < datum < 0.95 and weight == 0.0");
            double targetLon = tileLon(zoom, tx, targetPx);
            double dSea = v3.datum(seaLat, targetLon);
            double dLand = v3.datum(landLat, targetLon);
            assertTrue(dSea > 0.5 && dSea < 1.0 && dLand > 0.5 && dLand < 1.0,
                    "sub-metre datum expected, got sea=" + dSea + ", land=" + dLand);
            assertEquals(0.0, v3.weight(seaLat, targetLon), 1e-6, "expected weight == 0 over sea test pixel");
            assertEquals(0.0, v3.weight(landLat, targetLon), 1e-6, "expected weight == 0 over land test pixel");
            assertEquals(0.0, dem.metres(seaLat, targetLon, 0), 1e-4, "expected fine Copernicus sea");
            double cLand = dem.metres(landLat, targetLon, 0);
            assertTrue(cLand > 0.01 && cLand < 0.4, "expected low fine Copernicus land > SEA_CRUMB, got " + cLand);

            // 1. Low +0.4 m pixel over Copernicus land with sub-metre datum (+0.5..0.95 m, weight == 0)
            //    must NOT be submerged negative (must stay >= LAND_FLOOR).
            float[] landTile = tiles.tile(zoom, tx, tyLand).clone();
            landTile[128 * SIDE + targetPx] = 0.4f;
            v3.apply(landTile, zoom, tx, tyLand);
            assertTrue(landTile[128 * SIDE + targetPx] >= 0.01f,
                    "sub-metre datum over Copernicus land must apply LAND_FLOOR instead of going negative, got "
                            + landTile[128 * SIDE + targetPx]);

            // 2. Low +0.4 m pixel over Copernicus sea becomes exact 0f; +2.0 m coastal pixel over sea
            //    with weight == 0 is NOT zeroed by seaCandidate (stays 2.0 - datum > 1.0 m).
            float[] seaTile = tiles.tile(zoom, tx, tySea).clone();
            int pyLow = 128, pyPos = 129;
            double posLat = tileLat(zoom, tySea, pyPos);
            assertEquals(0.0, dem.metres(posLat, targetLon, 0), 1e-4, "expected fine Copernicus sea at pyPos");
            double dPos = v3.datum(posLat, targetLon);
            seaTile[pyLow * SIDE + targetPx] = 0.4f;
            seaTile[pyPos * SIDE + targetPx] = 2.0f;
            v3.apply(seaTile, zoom, tx, tySea);
            assertEquals(0f, seaTile[pyLow * SIDE + targetPx], 0f,
                    "sub-metre datum pushing +0.4 m over Copernicus sea below 0 must emit exact 0f");
            assertEquals((float) (2.0 - dPos), seaTile[pyPos * SIDE + targetPx], 0.02f,
                    "positive +2.0 m coastal pixel with sub-metre datum and weight == 0 must NOT be zeroed by seaCandidate");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void glo30FillV3TapersRegionalDatumAcrossNorthAndSouthSrtmBoundaries() throws Exception {
        // Strong +55 m coherent offset across both North (59..60°N) and South (-56..-55°S) SRTM boundaries.
        DoubleBinaryOperator ground = (lat, lon) -> 150.0 + 15.0 * Math.sin(lat * 4.0) + 10.0 * Math.cos(lon * 3.0);
        Map<String, byte[]> files = new HashMap<>();
        int[] souths = {57, 58, 59, 60, 61, -58, -57, -56, -55, -54};
        for (int s : souths)
            for (int w = -21; w <= -19; w++)
                files.put(CopernicusSource.name(s, w) + ".tif", syntheticCog(s, w, 512, 128, ground));
        HttpServer server = cogServer(files);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try (var dem = new CopernicusSource(base, null, true, 64, 4, Duration.ofSeconds(5))) {
            CopernicusFill.Tiles shifted = (z, x, y) -> {
                float[] t = new float[SIDE * SIDE];
                for (int py = 0; py < SIDE; py++) {
                    double lat = tileLat(z, y, py);
                    for (int px = 0; px < SIDE; px++) {
                        t[py * SIDE + px] = (float) (ground.applyAsDouble(lat, tileLon(z, x, px)) + 55.0);
                    }
                }
                return t;
            };
            CopernicusFill v3 = new CopernicusFill(dem, shifted, (lat, lon) -> false, true, 64);

            // Inside SRTM (<= 59.0°N and >= -55.0°S): datum must be exact 0.
            assertEquals(0.0, v3.datum(58.5, -19.5), 1e-6, "inside SRTM at 58.5°N must have datum == 0");
            assertEquals(0.0, v3.datum(59.0, -19.5), 1e-6, "at 59.0°N taper start must have datum == 0");
            assertEquals(0.0, v3.datum(-54.5, -19.5), 1e-6, "inside SRTM at -54.5°S must have datum == 0");
            assertEquals(0.0, v3.datum(-55.0, -19.5), 1e-6, "at -55.0°S taper start must have datum == 0");

            // Right at the SRTM boundaries (60.0°N and -56.0°S): full +55 m datum with no residual step!
            assertEquals(55.0, v3.datum(60.0, -19.5), 1.0, "60.0°N SRTM boundary must reach full regional datum");
            assertEquals(55.0, v3.datum(-56.0, -19.5), 1.0, "-56.0°S SRTM boundary must reach full regional datum");

            // Smooth monotonic intermediate ramp across 59.0..60.0°N and -55.0..-56.0°S.
            double n25 = v3.datum(59.25, -19.5), n50 = v3.datum(59.5, -19.5), n75 = v3.datum(59.75, -19.5);
            assertTrue(n25 > 0.0 && n25 < n50 && n50 < n75 && n75 < 55.0,
                    "north SRTM taper must increase monotonically: " + n25 + ", " + n50 + ", " + n75);
            assertEquals(27.5, n50, 1.5, "59.5°N midpoint must sit at half regional datum");

            double s25 = v3.datum(-55.25, -19.5), s50 = v3.datum(-55.5, -19.5), s75 = v3.datum(-55.75, -19.5);
            assertTrue(s25 > 0.0 && s25 < s50 && s50 < s75 && s75 < 55.0,
                    "south SRTM taper must increase monotonically: " + s25 + ", " + s50 + ", " + s75);
            assertEquals(27.5, s50, 1.5, "-55.5°S midpoint must sit at half regional datum");
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }
}
