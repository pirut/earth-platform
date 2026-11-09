package io.earthplatform.minecraft.cubic.earth;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.geography.globe.CanopySource;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Places as worlds made before build 70 generate them, by boxes of latitude and longitude, beside
 * the same places from data (rock, red ground and soil maps, ecoregion realms and canopy height):
 * the ground from above, a count of its blocks, and a side view of the trees along a line through
 * the middle.
 *
 * <p>Off unless asked for: {@code EARTH_DATA_BENCH=<cache dir> EARTH_DATA_PLACES=<file>
 * EARTH_DATA_OUT=<dir> ./gradlew test --tests '*DataRulesBench'}, with optional
 * {@code EARTH_DATA_SIZE} (blocks square, default 2048) and {@code EARTH_DATA_TREES=0} to leave
 * the side views out. Each line of the places file is a name, a latitude and a longitude.
 */
class DataRulesBench {
    private static final int PIXELS = 384, SLICE = 256, SLICE_HIGH = 112, SLICE_DEEP = 16;

    @Test
    @EnabledIfEnvironmentVariable(named = "EARTH_DATA_BENCH", matches = ".+")
    void compare() throws Exception {
        Path cache = Path.of(System.getenv("EARTH_DATA_BENCH"));
        Path out = Path.of(System.getenv("EARTH_DATA_OUT"));
        int size = Integer.parseInt(System.getenv().getOrDefault("EARTH_DATA_SIZE", "2048"));
        boolean trees = !"0".equals(System.getenv("EARTH_DATA_TREES"));
        Files.createDirectories(out);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        String merged = System.getProperty("earthplatform.test.minecraftJar");
        try (var jar = new java.util.zip.ZipFile(merged.replace("-merged.jar", "-client-extra-aka-minecraft-resources.jar"))) {
            net.minecraft.world.level.GrassColor.init(colours(jar, "grass"));
            net.minecraft.world.level.FoliageColor.init(colours(jar, "foliage"));
        }
        blockTags(merged.replace("-merged.jar", "-client-extra-aka-minecraft-resources.jar"));
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder ->
                biomes.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        for (String name : List.of("glacial_lake")) {
            try (var in = DataRulesBench.class.getResourceAsStream("/data/earthplatform/worldgen/biome/" + name + ".json")) {
                var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in));
                Biome biome = Biome.DIRECT_CODEC.parse(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, lookup), json).getOrThrow();
                biomes.register(net.minecraft.resources.ResourceKey.create(Registries.BIOME, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("earthplatform", name)),
                        biome, RegistrationInfo.BUILT_IN);
            }
        }
        TerrariumTileSource tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache.resolve(TerrariumTileSource.DATASET_ID),
                true, 512, 6, Duration.ofSeconds(20));
        WorldCoverSource cover = new WorldCoverSource(WorldCoverSource.AWS_WORLDCOVER, cache.resolve(WorldCoverSource.DATASET_ID), true, 96, 4, Duration.ofSeconds(30));
        ClimateSource climate = new ClimateSource(ClimateSource.CHELSA, cache.resolve(ClimateSource.DATASET_ID), true, 320, 8, Duration.ofSeconds(30));
        CanopySource canopy = new CanopySource(CanopySource.AWS_CANOPY, cache.resolve(CanopySource.DATASET_ID), true, 48, 4, Duration.ofSeconds(30));
        EarthSettings settings = EarthSettings.at(EarthSettings.DEFAULT_LATITUDE, EarthSettings.DEFAULT_LONGITUDE);
        EarthGenerator boxes = new EarthGenerator(1234L, biomes, settings, tiles, cover, climate, null);
        EarthGenerator data = new EarthGenerator(1234L, biomes, settings, tiles, cover, climate, null);
        data.data(canopy, true, true);
        StringBuilder all = new StringBuilder();
        for (String line : Files.readAllLines(Path.of(System.getenv("EARTH_DATA_PLACES")))) {
            String[] part = line.trim().split("\\s+");
            if (part.length < 3 || part[0].startsWith("#")) continue;
            int[] at = data.block(Double.parseDouble(part[1]), Double.parseDouble(part[2]));
            StringBuilder text = new StringBuilder(part[0] + " " + part[1] + ", " + part[2] + "\n");
            try {
                BufferedImage image = new BufferedImage(2 * PIXELS + 8, PIXELS + (trees ? 2 * (SLICE_HIGH + 8) : 0), BufferedImage.TYPE_INT_RGB);
                for (int i = 0; i < image.getWidth(); i++) for (int j = 0; j < image.getHeight(); j++) image.setRGB(i, j, 0xFFFFFF);
                EarthGenerator[] both = {boxes, data};
                for (int k = 0; k < 2; k++) {
                    text.append(k == 0 ? "  boxes: " : "  data:  ").append(ground(both[k], at[0], at[1], size, image, k * (PIXELS + 8))).append('\n');
                    if (trees) text.append(k == 0 ? "  trees, boxes: " : "  trees, data:  ")
                            .append(slice(both[k], at[0], at[1], image, PIXELS + 8 + k * (SLICE_HIGH + 8))).append('\n');
                }
                ImageIO.write(image, "png", out.resolve(part[0] + ".png").toFile());
                int dump = Integer.parseInt(System.getenv().getOrDefault("EARTH_DATA_DUMP", "0"));
                if (dump > 0) for (int k = 0; k < 2; k++) dump(both[k], at[0], at[1], dump, out.resolve(part[0] + (k == 0 ? "-boxes" : "-data") + ".vox"));
            } catch (RuntimeException failure) {
                text.append("  failed: ").append(failure).append('\n');
                failure.printStackTrace();
            }
            System.out.print(text);
            all.append(text);
        }
        Files.writeString(out.resolve("report.txt"), all);
    }

    /** Binds vanilla's block tags from the game jar, which trees ask for the soil they stand on and the blocks they may replace. */
    private static void blockTags(String merged) throws java.io.IOException {
        Map<String, List<String>> raw = new TreeMap<>();
        try (var jar = new java.util.zip.ZipFile(merged)) {
            String root = "data/minecraft/tags/block/";
            for (var entries = jar.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith(root) || !entry.getName().endsWith(".json")) continue;
                List<String> values = new java.util.ArrayList<>();
                try (var in = new java.io.InputStreamReader(jar.getInputStream(entry))) {
                    for (var value : com.google.gson.JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("values"))
                        values.add(value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString());
                }
                raw.put("minecraft:" + entry.getName().substring(root.length(), entry.getName().length() - 5), values);
            }
        }
        if (raw.isEmpty()) throw new java.io.IOException("no block tags in " + merged);
        var blocks = BuiltInRegistries.BLOCK;
        Map<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>, List<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>>> tags = new java.util.HashMap<>();
        for (String name : raw.keySet()) {
            List<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>> members = new java.util.ArrayList<>();
            java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>(List.of("#" + name));
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (!open.isEmpty()) {
                String next = open.pop();
                if (!seen.add(next)) continue;
                if (next.startsWith("#")) open.addAll(raw.getOrDefault(next.substring(1), List.of()).stream().map(v -> v.startsWith("#") ? v : v).toList());
                else blocks.getOptional(net.minecraft.resources.ResourceLocation.parse(next)).ifPresent(block -> members.add(blocks.wrapAsHolder(block)));
            }
            tags.put(net.minecraft.tags.TagKey.create(Registries.BLOCK, net.minecraft.resources.ResourceLocation.parse(name)), members);
        }
        blocks.bindTags(tags);
    }

    private static int[] colours(java.util.zip.ZipFile jar, String name) throws java.io.IOException {
        BufferedImage image = ImageIO.read(jar.getInputStream(jar.getEntry("assets/minecraft/textures/colormap/" + name + ".png")));
        return image.getRGB(0, 0, 256, 256, null, 0, 256);
    }

    /** Draws a square's ground from above at {@code left} and returns its commonest top blocks and trees. */
    private static String ground(EarthGenerator earth, int cx, int cz, int size, BufferedImage image, int left) {
        earth.prepare(cx, cz);
        double step = (double) size / PIXELS;
        int voxel = Math.max(1, Integer.highestOneBit((int) Math.max(1, step)));
        Map<String, Integer> tops = new TreeMap<>();
        int giants = 0, land = 0;
        for (int j = 0; j < PIXELS; j++) for (int i = 0; i < PIXELS; i++) {
            int x = (int) Math.floor(cx - size / 2.0 + (i + 0.5) * step), z = (int) Math.floor(cz - size / 2.0 + (j + 0.5) * step);
            int y = earth.surfaceY(x, z), water = earth.waterY(x, z);
            boolean wet = water != SurfaceGenerator.DRY && water > y;
            int slope = Math.abs(earth.surfaceY(x + 1, z) - earth.surfaceY(x - 1, z)) + Math.abs(earth.surfaceY(x, z + 1) - earth.surfaceY(x, z - 1));
            Surface surface = earth.surface(x, z, y, water, slope, voxel, null);
            int d = Math.max(1, (int) step);
            double dx = earth.surfaceY(x + d, z) - earth.surfaceY(x - d, z), dz = earth.surfaceY(x, z + d) - earth.surfaceY(x, z - d);
            double light = Math.clamp(1 + (dx + dz) / (2.0 * d) * 0.35, 0.55, 1.25);
            int rgb = wet ? 0x2B4F8C : ImageryCalibration.colour(surface, x, z);
            // Giant groves in magenta, so they show among the other woods.
            if (!wet && surface.broadleaf() == SurfaceGenerator.Tree.SEQUOIA) { rgb = 0xC030C0; giants++; }
            image.setRGB(left + i, j, shade(rgb, light));
            if (wet) continue;
            land++;
            tops.merge(BuiltInRegistries.BLOCK.getKey(surface.top().getBlock()).getPath() + " in "
                    + surface.biome().unwrapKey().map(key -> key.location().getPath()).orElse("?"), 1, Integer::sum);
        }
        StringBuilder text = new StringBuilder();
        int shown = land;
        tops.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
                .forEach(e -> text.append(String.format(Locale.ROOT, "%s %.0f%%, ", e.getKey(), 100.0 * e.getValue() / Math.max(1, shown))));
        return text + String.format(Locale.ROOT, "giant grove %.1f%%", 100.0 * giants / Math.max(1, land));
    }

    /**
     * Draws the trees along a west-east line through the middle, seen from the south, at {@code top},
     * and returns how tall they stand: the mean and the greatest height of the highest leaves over
     * each column that has any.
     */
    private static String slice(EarthGenerator earth, int cx, int cz, BufferedImage image, int top) {
        int x0 = cx - SLICE / 2 & ~15, z0 = cz & ~15, low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = x0; x < x0 + SLICE; x++) for (int z = z0; z < z0 + SLICE_DEEP; z++) {
            low = Math.min(low, earth.surfaceY(x, z));
            high = Math.max(high, earth.surfaceY(x, z));
        }
        int cy0 = Math.floorDiv(low - 4, 16), cy1 = Math.floorDiv(high + SLICE_HIGH, 16), base = low - 4;
        long sum = 0;
        int columns = 0, tallest = 0;
        for (int cxx = x0 >> 4; cxx < x0 + SLICE >> 4; cxx++) {
            int[][] crown = new int[16][16];
            BlockState[][][] seen = new BlockState[16][SLICE_HIGH][1];
            int[][] depth = new int[16][SLICE_HIGH];
            for (int[] row : depth) java.util.Arrays.fill(row, Integer.MAX_VALUE);
            for (int cy = cy0; cy <= cy1; cy++) {
                LevelChunkSection section = earth.generate(cxx, cy, z0 >> 4);
                for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                    BlockState state = section.getBlockState(lx, ly, lz);
                    if (state.isAir()) continue;
                    int y = cy * 16 + ly;
                    if (state.getBlock() instanceof LeavesBlock) crown[lx][lz] = Math.max(crown[lx][lz], y - earth.surfaceY((cxx << 4) + lx, z0 + lz));
                    int row = y - base;
                    // Seen from the south: the block nearest the viewer in each column of the picture.
                    if (row >= 0 && row < SLICE_HIGH && 15 - lz < depth[lx][row]) { depth[lx][row] = 15 - lz; seen[lx][row][0] = state; }
                }
            }
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) if (crown[lx][lz] > 0) { sum += crown[lx][lz]; columns++; tallest = Math.max(tallest, crown[lx][lz]); }
                for (int row = 0; row < SLICE_HIGH; row++) {
                    BlockState state = seen[lx][row][0];
                    int px = (cxx << 4) + lx - x0 + (image.getWidth() - SLICE) / 2, py = top + SLICE_HIGH - 1 - row;
                    int rgb = state == null ? 0xBFD9F2 : state.getBlock() instanceof LeavesBlock ? 0x2F6B2A : state.is(BlockTags.LOGS) ? 0x6B4A2B
                            : state.getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).col;
                    image.setRGB(px, py, shade(rgb, state == null ? 1 : 1 - depth[lx][row] * 0.02));
                }
            }
        }
        return columns == 0 ? "none" : String.format(Locale.ROOT, "crowns %.1f blocks up on average, the tallest %d", sum / (double) columns, tallest);
    }

    /** Writes the blocks of a square as colours, for a 3D picture: sizes and base height, then 3 bytes a block (0 for air), x fastest, then z, then y. */
    private static void dump(EarthGenerator earth, int cx, int cz, int size, Path file) throws java.io.IOException {
        int x0 = cx - size / 2 & ~15, z0 = cz - size / 2 & ~15, low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = x0; x < x0 + size; x += 4) for (int z = z0; z < z0 + size; z += 4) {
            low = Math.min(low, earth.surfaceY(x, z));
            high = Math.max(high, earth.surfaceY(x, z));
        }
        int cy0 = Math.floorDiv(low - 8, 16), cy1 = Math.floorDiv(high + 104, 16), ny = (cy1 - cy0 + 1) * 16;
        byte[] rgb = new byte[size * size * ny * 3];
        for (int scx = x0 >> 4; scx < x0 + size >> 4; scx++) for (int scz = z0 >> 4; scz < z0 + size >> 4; scz++) for (int cy = cy0; cy <= cy1; cy++) {
            LevelChunkSection section = earth.generate(scx, cy, scz);
            for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                BlockState state = section.getBlockState(lx, ly, lz);
                if (state.isAir()) continue;
                String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                int colour = name.equals("spruce_leaves") ? 0x3A5F3A : name.equals("birch_leaves") ? 0x6E9A4E : name.equals("jungle_leaves") ? 0x3F8A22
                        : state.getBlock() instanceof LeavesBlock ? 0x4C8A2C : name.equals("mangrove_log") ? 0x7A3B2E : name.equals("spruce_log") ? 0x4A3520
                        : name.equals("birch_log") ? 0xD8D8D0 : state.is(BlockTags.LOGS) ? 0x6B5030 : name.equals("grass_block") ? 0x6FA24A
                        : name.equals("podzol") ? 0x6A4A1E : name.equals("water") ? 0x3A62B0 : name.equals("snow") ? 0xF4F8F8
                        : state.getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).col;
                if (colour == 0) colour = 0x010101;
                int i = ((((cy - cy0) * 16 + ly) * size + (scz << 4) + lz - z0) * size + (scx << 4) + lx - x0) * 3;
                rgb[i] = (byte) (colour >> 16); rgb[i + 1] = (byte) (colour >> 8); rgb[i + 2] = (byte) colour;
            }
        }
        try (var data = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
            data.writeInt(size); data.writeInt(ny); data.writeInt(cy0 * 16);
            data.write(rgb);
        }
    }

    private static int shade(int rgb, double factor) {
        int r = (int) Math.min(255, (rgb >> 16 & 255) * factor), g = (int) Math.min(255, (rgb >> 8 & 255) * factor), b = (int) Math.min(255, (rgb & 255) * factor);
        return r << 16 | g << 8 | b;
    }
}
