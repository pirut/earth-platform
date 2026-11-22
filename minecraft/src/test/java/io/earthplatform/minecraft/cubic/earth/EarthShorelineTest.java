package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end shoreline, barrier-inlet, coastal-pond, polder and below-sea-basin checks against
 * {@link EarthGenerator} using synthetic local Terrarium and WorldCover servers.
 */
class EarthShorelineTest {
    private static final double HOME_LAT = 36.2, HOME_LON = -75.8;
    private static final int SQUARE_SOUTH = 36, SQUARE_WEST = -78;
    private static final String SQUARE_NAME = "ESA_WorldCover_10m_2021_v200_N36W078_Map.tif";
    private static final int BLOCK_SIZE = 1024;
    private static final int BLOCK_BYTES = BLOCK_SIZE * BLOCK_SIZE;
    private static final int HEADER_OFFSET = 65536;

    private static Registry<Biome> biomes;

    @BeforeAll
    static void bootRegistries() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder ->
                registry.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        for (String name : List.of("glacial_lake")) {
            try (var in = EarthShorelineTest.class.getResourceAsStream("/data/earthplatform/worldgen/biome/" + name + ".json")) {
                var json = JsonParser.parseReader(new InputStreamReader(in));
                Biome biome = Biome.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, lookup), json).getOrThrow();
                registry.register(ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("earthplatform", name)),
                        biome, RegistrationInfo.BUILT_IN);
            }
        }
        biomes = registry;
    }

    @Test
    void narrowBarrierInletConnectsSoundToSeaAndClampsLowShore() throws Exception {
        // Place a 128-block inlet offset from 256-block SEA_CELL centres so the old 256-block search
        // would miss it, while the 64-block coverWater connectivity traces right through it.
        // The sound and low beach sit at -0.75 m (non-zero, like NAVD88-offset 3DEP, so sample[2] == 0).
        try (SyntheticWorld world = new SyntheticWorld(
                (dx, dz) -> {
                    if (dx >= 600) return -10.0;
                    if (dx >= 300) return -3.0;
                    if (dx >= 200) return Math.abs(dz - 32) < 64 ? -0.75 : 2.0;
                    if (dx >= 100) return -0.75;
                    if (dx >= -800) return -0.75;
                    return 4.0;
                },
                (dx, dz) -> {
                    if (dx >= 600) return WorldCoverSource.NO_DATA;
                    if (dx >= 300) return WorldCoverSource.WATER;
                    if (dx >= 100) return Math.abs(dz - 32) < 64 ? WorldCoverSource.WATER : WorldCoverSource.GRASSLAND;
                    if (dx >= -800) return WorldCoverSource.WATER;
                    return WorldCoverSource.GRASSLAND;
                })) {
            int[] out = new int[2];
            world.ground(-300, 200, out);
            assertEquals(EarthGenerator.SEA, out[1], "sound behind narrow barrier inlet should sit at sea level");

            world.ground(-400, -300, out);
            assertEquals(EarthGenerator.SEA, out[1], "sound away from inlet axis should still sit at sea level");

            // Low dry shore (-0.75 m, GRASSLAND) right beside the sound clamps flush to SEA rather than dipping below it.
            world.ground(120, 200, out);
            assertEquals(EarthGenerator.DRY, out[1], "low barrier flat should stay dry land");
            assertEquals(EarthGenerator.SEA, out[0], "low coastal shore with shallow negative datum offset should clamp to SEA");
        }
    }

    @Test
    void freshwaterLakeBehindDryBeachStaysAboveSeaLevel() throws Exception {
        // Case 1: A continuous 100-block dry beach separates open ocean from an elevated (+10 m, above TIDAL=6)
        // coastal lake sharing the same 256-block SEA_CELL; split-cell sea/openSea state must not cross the beach.
        try (SyntheticWorld world = new SyntheticWorld(
                (dx, dz) -> {
                    if (dx >= 300) return -10.0;
                    if (dx >= 150) return -2.0;
                    if (dx >= 50) return 11.0;
                    if (dx >= -250 && Math.abs(dz) <= 200) return 10.0;
                    return 12.0;
                },
                (dx, dz) -> {
                    if (dx >= 300) return WorldCoverSource.NO_DATA;
                    if (dx >= 150) return WorldCoverSource.WATER;
                    if (dx >= 50) return WorldCoverSource.GRASSLAND;
                    if (dx >= -250 && Math.abs(dz) <= 200) return WorldCoverSource.WATER;
                    return WorldCoverSource.GRASSLAND;
                })) {
            int[] out = new int[2];
            world.ground(200, 0, out);
            assertEquals(EarthGenerator.SEA, out[1], "ocean side should be sea");

            world.ground(-100, 0, out);
            assertTrue(out[1] >= EarthGenerator.SEA + 9,
                    "elevated coastal lake behind dry beach should stay above sea level, got waterY=" + out[1]);

            world.ground(20, 0, out);
            assertTrue(out[1] >= EarthGenerator.SEA + 9,
                    "lake shore inside a split coastal cell must not inherit ocean level, got waterY=" + out[1]);
        }

        // Case 2: A low (+2 m) freshwater lake behind a 300-block dry barrier (outside TIDAL_REACH=256)
        // exercises surface <= SEA + 2 && joinsOpenSea(x, z) directly without crossing dry land.
        try (SyntheticWorld world = new SyntheticWorld(
                (dx, dz) -> {
                    if (dx >= 500) return -10.0;
                    if (dx >= 350) return -2.0;
                    if (dx >= 50) return 4.0;
                    if (dx >= -300 && Math.abs(dz) <= 200) return 2.0;
                    return 4.0;
                },
                (dx, dz) -> {
                    if (dx >= 500) return WorldCoverSource.NO_DATA;
                    if (dx >= 350) return WorldCoverSource.WATER;
                    if (dx >= 50) return WorldCoverSource.GRASSLAND;
                    if (dx >= -300 && Math.abs(dz) <= 200) return WorldCoverSource.WATER;
                    return WorldCoverSource.GRASSLAND;
                })) {
            int[] out = new int[2];
            world.ground(400, 0, out);
            assertEquals(EarthGenerator.SEA, out[1], "ocean side should be sea");

            world.ground(-120, 0, out);
            assertEquals(EarthGenerator.SEA + 2, out[1],
                    "low freshwater lake behind continuous barrier must not join open sea");
        }
    }

    @Test
    void closedCoastalPondAtSeaLevelDoesNotDropBelowSea() throws Exception {
        // A closed coastal pond at 0 m sits inside the same water cell as deep (-15 m) ocean bathymetry.
        // Without the lakeSurface fix, cell bathymetry dragged the pond down to SEA - 2.
        try (SyntheticWorld world = new SyntheticWorld(
                (dx, dz) -> {
                    if (dx >= 160) return -15.0;
                    if (dx >= -80 && dx < -20 && Math.abs(dz) < 40) return 0.0;
                    return 0.2;
                },
                (dx, dz) -> {
                    if (dx >= 400) return WorldCoverSource.NO_DATA;
                    if (dx >= 160) return WorldCoverSource.WATER;
                    if (dx >= -80 && dx < -20 && Math.abs(dz) < 40) return WorldCoverSource.WATER;
                    return WorldCoverSource.GRASSLAND;
                })) {
            int[] out = new int[2];
            world.ground(-50, 0, out);
            assertEquals(EarthGenerator.SEA, out[1],
                    "closed coastal pond surrounded by 0.2 m land should not sink below SEA");
        }
    }

    @Test
    void deepPolderAndInlandBelowSeaBasinStayBelowSeaLevel() throws Exception {
        // Deep reclaimed polder (-5 m) behind a coastal dike stays dry below sea level.
        try (SyntheticWorld polder = new SyntheticWorld(
                (dx, dz) -> {
                    if (dx >= 400) return -10.0;
                    if (dx >= 150) return -2.0;
                    if (dx >= 50) return 3.0;
                    return -5.0;
                },
                (dx, dz) -> {
                    if (dx >= 400) return WorldCoverSource.NO_DATA;
                    if (dx >= 150) return WorldCoverSource.WATER;
                    if (dx >= 50) return WorldCoverSource.GRASSLAND;
                    return WorldCoverSource.CROPLAND;
                })) {
            int[] out = new int[2];
            polder.ground(-100, 0, out);
            assertEquals(EarthGenerator.DRY, out[1], "deep polder should remain dry");
            assertTrue(out[0] <= EarthGenerator.SEA - 4,
                    "deep polder (-5 m) must stay below sea level, got groundY=" + out[0]);
        }

        // Deep inland below-sea basin (Dead Sea analogue, -400 m) keeps its true level far below SEA.
        try (SyntheticWorld basin = new SyntheticWorld(
                (dx, dz) -> (Math.abs(dx) < 200 && Math.abs(dz) < 200) ? -400.0 : -390.0,
                (dx, dz) -> (Math.abs(dx) < 200 && Math.abs(dz) < 200) ? WorldCoverSource.WATER : WorldCoverSource.BARE)) {
            int[] out = new int[2];
            basin.ground(0, 0, out);
            assertTrue(out[1] != EarthGenerator.DRY && out[1] < EarthGenerator.SEA - 100,
                    "inland below-sea basin should keep its deep water surface, got waterY=" + out[1]);
        }
    }

    /**
     * Local HTTP fixture serving synthetic Terrarium PNGs and a multi-overview WorldCover TIFF
     * defined directly in relative block coordinates {@code (dx, dz)} around {@link #HOME_LAT}, {@link #HOME_LON}.
     */
    private static final class SyntheticWorld implements AutoCloseable {
        private final HttpServer server;
        private final TerrariumTileSource tiles;
        private final WorldCoverSource cover;
        private final EarthGenerator generator;
        private final MercatorGrid grid;
        private final int homeX, homeZ;
        private final DoubleBinaryOperator elevationAt;
        private final IntBinaryOperator coverAt;
        private final ConcurrentHashMap<String, byte[]> pngCache = new ConcurrentHashMap<>();
        private final byte[] tiffHeader;
        private final int[][] blockMeta; // [globalId] -> {overview, tileRow, tileCol}

        SyntheticWorld(DoubleBinaryOperator elevationAt, IntBinaryOperator coverAt) throws IOException {
            this.elevationAt = elevationAt;
            this.coverAt = coverAt;
            EarthSettings settings = EarthSettings.at(HOME_LAT, HOME_LON);
            this.grid = new MercatorGrid(settings.metresPerBlock());
            GeoPoint home = new GeoPoint(HOME_LAT, HOME_LON);
            this.homeX = (int) Math.floor(grid.blockX(home));
            this.homeZ = (int) Math.floor(grid.blockZ(home));

            TiffLayout layout = buildWorldCoverHeader();
            this.tiffHeader = layout.header;
            this.blockMeta = layout.blockMeta;

            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/terrarium/", this::serveTerrarium);
            server.createContext("/worldcover/", this::serveWorldCover);
            server.start();

            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            this.tiles = new TerrariumTileSource(base.resolve("terrarium/"), null, true, 128, 8, Duration.ofSeconds(10), List.of());
            this.cover = new WorldCoverSource(base.resolve("worldcover/"), null, true, 64, 8, Duration.ofSeconds(10));
            this.generator = new EarthGenerator(1234L, biomes, settings, tiles, cover, null, null);
        }

        void ground(int dx, int dz, int[] out) {
            generator.ground(homeX + dx, homeZ + dz, out);
        }

        private void serveTerrarium(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String key = path.substring("/terrarium/".length());
            byte[] png = pngCache.computeIfAbsent(key, this::renderTerrariumTile);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        }

        private byte[] renderTerrariumTile(String key) {
            String[] parts = key.replace(".png", "").split("/");
            int z = Integer.parseInt(parts[0]);
            int tx = Integer.parseInt(parts[1]);
            int ty = Integer.parseInt(parts[2]);
            double pixels = 256.0 * (1 << z);
            double[] bx = new double[256];
            double[] bz = new double[256];
            for (int i = 0; i < 256; i++) {
                double u = (tx * 256.0 + i + 0.5) / pixels;
                double v = (ty * 256.0 + i + 0.5) / pixels;
                double lon = u * 360.0 - 180.0;
                double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * v))));
                bx[i] = grid.blockX(new GeoPoint(0, lon)) - homeX;
                bz[i] = grid.blockZ(new GeoPoint(lat, 0)) - homeZ;
            }
            BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
            for (int py = 0; py < 256; py++) {
                double dz = bz[py];
                for (int px = 0; px < 256; px++) {
                    double m = elevationAt.applyAsDouble(bx[px], dz);
                    int raw = Math.max(0, Math.min(0xFFFFFF, (int) Math.round((m + 32768.0) * 256.0)));
                    image.setRGB(px, py, raw);
                }
            }
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private void serveWorldCover(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!path.endsWith("/" + SQUARE_NAME)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            long start = 0, end = tiffHeader.length - 1L;
            if (range != null && range.startsWith("bytes=")) {
                String[] bounds = range.substring("bytes=".length()).split("-");
                start = Long.parseLong(bounds[0]);
                end = Long.parseLong(bounds[1]);
            }
            byte[] slice;
            if (start == 0) {
                int len = (int) Math.min(tiffHeader.length, end - start + 1);
                slice = Arrays.copyOf(tiffHeader, len);
            } else {
                int id = (int) ((start - HEADER_OFFSET) / BLOCK_BYTES);
                int[] meta = blockMeta[id];
                slice = renderWorldCoverBlock(meta[0], meta[1], meta[2]);
            }
            exchange.sendResponseHeaders(206, slice.length);
            exchange.getResponseBody().write(slice);
            exchange.close();
        }

        private byte[] renderWorldCoverBlock(int overview, int tileRow, int tileCol) {
            int step = 1 << overview;
            double[] dx = new double[BLOCK_SIZE];
            double[] dz = new double[BLOCK_SIZE];
            for (int i = 0; i < BLOCK_SIZE; i++) {
                double fx = (tileCol * BLOCK_SIZE + i + 0.5) * step;
                double fy = (tileRow * BLOCK_SIZE + i + 0.5) * step;
                double lon = SQUARE_WEST + fx / WorldCoverSource.PIXELS_PER_DEGREE;
                double lat = (SQUARE_SOUTH + WorldCoverSource.SQUARE) - fy / WorldCoverSource.PIXELS_PER_DEGREE;
                dx[i] = grid.blockX(new GeoPoint(0, lon)) - homeX;
                dz[i] = grid.blockZ(new GeoPoint(lat, 0)) - homeZ;
            }
            byte[] block = new byte[BLOCK_BYTES];
            for (int py = 0; py < BLOCK_SIZE; py++) {
                int rdz = (int) Math.round(dz[py]);
                int rowBase = py << 10;
                for (int px = 0; px < BLOCK_SIZE; px++) {
                    block[rowBase | px] = (byte) coverAt.applyAsInt((int) Math.round(dx[px]), rdz);
                }
            }
            return block;
        }

        @Override
        public void close() {
            tiles.close();
            cover.close();
            server.stop(0);
        }
    }

    private record TiffLayout(byte[] header, int[][] blockMeta) {}

    /** Builds a classic little-endian uncompressed tiled TIFF header with overviews 0..6. */
    private static TiffLayout buildWorldCoverHeader() {
        int full = WorldCoverSource.SQUARE * WorldCoverSource.PIXELS_PER_DEGREE;
        int levels = WorldCoverSource.MAX_OVERVIEW + 1;
        int[] widths = new int[levels];
        int[] tilesAcross = new int[levels];
        int totalBlocks = 0;
        for (int ov = 0; ov < levels; ov++) {
            widths[ov] = full >> ov;
            tilesAcross[ov] = Math.ceilDiv(widths[ov], BLOCK_SIZE);
            totalBlocks += tilesAcross[ov] * tilesAcross[ov];
        }
        int[][] blockMeta = new int[totalBlocks][3];

        // 8-byte TIFF header + 7 IFDs (each 2 + 10*12 + 4 = 126 bytes) + offset/count arrays.
        ByteBuffer buf = ByteBuffer.allocate(HEADER_OFFSET).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);

        int[] ifdPos = new int[levels];
        int cursor = 8;
        for (int ov = 0; ov < levels; ov++) {
            ifdPos[ov] = cursor;
            cursor += 2 + 10 * 12 + 4;
        }

        int blockId = 0;
        for (int ov = 0; ov < levels; ov++) {
            int across = tilesAcross[ov];
            int count = across * across;
            int offsetsPos = cursor;
            int countsPos = offsetsPos + count * 4;
            cursor = countsPos + count * 4;

            for (int r = 0; r < across; r++) {
                for (int c = 0; c < across; c++) {
                    int idx = r * across + c;
                    blockMeta[blockId][0] = ov;
                    blockMeta[blockId][1] = r;
                    blockMeta[blockId][2] = c;
                    long virtualOffset = HEADER_OFFSET + (long) blockId * BLOCK_BYTES;
                    buf.putInt(offsetsPos + idx * 4, (int) virtualOffset);
                    buf.putInt(countsPos + idx * 4, BLOCK_BYTES);
                    blockId++;
                }
            }

            int p = ifdPos[ov];
            buf.putShort(p, (short) 10);
            p += 2;
            p = putTag(buf, p, 256, 4, 1, widths[ov]);        // ImageWidth
            p = putTag(buf, p, 257, 4, 1, widths[ov]);        // ImageLength
            p = putTag(buf, p, 258, 3, 1, 8);                 // BitsPerSample
            p = putTag(buf, p, 259, 3, 1, 1);                 // Compression = none
            p = putTag(buf, p, 277, 3, 1, 1);                 // SamplesPerPixel
            p = putTag(buf, p, 317, 3, 1, 1);                 // Predictor = none
            p = putTag(buf, p, 322, 4, 1, BLOCK_SIZE);        // TileWidth
            p = putTag(buf, p, 323, 4, 1, BLOCK_SIZE);        // TileLength
            p = putTag(buf, p, 324, 4, count, count == 1 ? buf.getInt(offsetsPos) : offsetsPos); // TileOffsets
            p = putTag(buf, p, 325, 4, count, count == 1 ? buf.getInt(countsPos) : countsPos);   // TileByteCounts
            buf.putInt(p, ov + 1 < levels ? ifdPos[ov + 1] : 0);
        }
        return new TiffLayout(Arrays.copyOf(buf.array(), cursor), blockMeta);
    }

    private static int putTag(ByteBuffer buf, int at, int tag, int type, int count, int valueOrOffset) {
        buf.putShort(at, (short) tag);
        buf.putShort(at + 2, (short) type);
        buf.putInt(at + 4, count);
        buf.putInt(at + 8, valueOrOffset);
        return at + 12;
    }
}
