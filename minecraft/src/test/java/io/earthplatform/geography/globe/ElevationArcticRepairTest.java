package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        int levels = 4;
        List<byte[]> blocks = new ArrayList<>();
        int[] firstBlock = new int[levels], width = new int[levels];
        for (int level = 0; level < levels; level++) {
            int w = size >> level;
            width[level] = w;
            firstBlock[level] = blocks.size();
            int across = Math.ceilDiv(w, tile);
            for (int br = 0; br < across; br++) for (int bc = 0; bc < across; bc++) {
                byte[] raw = new byte[tile * tile * 4];
                for (int y = 0; y < tile; y++) {
                    int at = y * tile * 4;
                    for (int x = 0; x < tile; x++) {
                        int row = br * tile + y, col = bc * tile + x;
                        float v = row < w && col < w
                                ? (float) ground.applyAsDouble(south + 1 - (row + 0.5) / w, west + (col + 0.5) / w)
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
            int n = Math.ceilDiv(width[level], tile) * Math.ceilDiv(width[level], tile);
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
            int n = Math.ceilDiv(width[level], tile) * Math.ceilDiv(width[level], tile);
            file.position(ifdAt[level]);
            file.putShort((short) entries);
            int[][] tags = {
                    {256, 4, 1, width[level]}, {257, 4, 1, width[level]}, {258, 3, 1, 32}, {259, 3, 1, 8},
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
}
