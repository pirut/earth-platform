package io.earthplatform.minecraft.cubic.gen;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Procedural engine test world, deliberately placed far above vanilla's build limit: hills around
 * Y 2400 rising to snowy ridges near Y 3300, with lakes, caves, ores and trees. It exists to prove
 * the cubic engine before the Earth layer plugs in its own generator.
 */
public final class HighlandsGenerator implements CubeGenerator {
    public static final int SEA = 2380, BASE = 2400, TREELINE = 2950, SNOWLINE = 3020;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState(),
            DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState(), DIRT = Blocks.DIRT.defaultBlockState(),
            GRASS = Blocks.GRASS_BLOCK.defaultBlockState(), SAND = Blocks.SAND.defaultBlockState(),
            GRAVEL = Blocks.GRAVEL.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState(),
            SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState(), SNOW = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1),
            LAVA = Blocks.LAVA.defaultBlockState();

    private final long seed;
    private final SimplexNoise continent, ridge, mask, hills, detail, moisture, snow;
    private final ImprovedNoise tunnelA, tunnelB, cavern, lavaNoise;
    private final Holder<Biome> plains, forest, birch, taiga, meadow, snowy, peaks, beach;
    private final ConcurrentHashMap<Long, Column> columns = new ConcurrentHashMap<>();
    private final net.minecraft.core.IdMap<Holder<Biome>> biomeIds;

    public HighlandsGenerator(long seed, Registry<Biome> biomes) {
        this.seed = seed;
        this.biomeIds = biomes.asHolderIdMap();
        RandomSource random = new LegacyRandomSource(seed ^ 0x4541525448L);
        continent = new SimplexNoise(random); ridge = new SimplexNoise(random); mask = new SimplexNoise(random);
        hills = new SimplexNoise(random); detail = new SimplexNoise(random); moisture = new SimplexNoise(random);
        snow = new SimplexNoise(random);
        tunnelA = new ImprovedNoise(random); tunnelB = new ImprovedNoise(random); cavern = new ImprovedNoise(random);
        lavaNoise = new ImprovedNoise(random);
        plains = holder(biomes, Biomes.PLAINS); forest = holder(biomes, Biomes.FOREST); birch = holder(biomes, Biomes.BIRCH_FOREST);
        taiga = holder(biomes, Biomes.TAIGA); meadow = holder(biomes, Biomes.MEADOW); snowy = holder(biomes, Biomes.SNOWY_SLOPES);
        peaks = holder(biomes, Biomes.JAGGED_PEAKS); beach = holder(biomes, Biomes.BEACH);
    }
    private static Holder<Biome> holder(Registry<Biome> registry, ResourceKey<Biome> key) { return registry.getHolderOrThrow(key); }

    @Override public String id() { return "highlands-v1"; }

    private static double fbm(SimplexNoise noise, double x, double z, int octaves) {
        double sum = 0, amplitude = 1, norm = 0;
        for (int i = 0; i < octaves; i++) { sum += noise.getValue(x, z) * amplitude; norm += amplitude; amplitude *= 0.5; x *= 2.03; z *= 2.03; }
        return sum / norm;
    }

    @Override public int surfaceY(int x, int z) {
        double c = fbm(continent, x / 3200.0, z / 3200.0, 3);
        double r = 1 - Math.abs(fbm(ridge, x / 760.0, z / 760.0, 4));
        double m = Mth.smoothstep(Mth.clamp((fbm(mask, x / 2600.0, z / 2600.0, 2) + 0.15) / 0.7, 0, 1));
        double h = BASE + c * 110 + m * r * r * r * 950 + fbm(hills, x / 190.0, z / 190.0, 3) * 34 + fbm(detail, x / 38.0, z / 38.0, 2) * 4;
        return Mth.floor(h);
    }
    private int snowline(int x, int z) { return SNOWLINE + (int) (snow.getValue(x / 90.0, z / 90.0) * 50); }
    private double wetness(int x, int z) { return fbm(moisture, x / 900.0, z / 900.0, 2); }

    /** Heights for a cube column with a one-block border, plus per-column biome and surface. */
    private record Column(int[] height, Holder<Biome>[] biome, BlockState[] surface, int min, int max) {}

    private Column column(int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        Column cached = columns.get(key);
        if (cached != null) return cached;
        int[] h = new int[18 * 18];
        int x0 = cx << 4, z0 = cz << 4;
        for (int dz = -1; dz <= 16; dz++) for (int dx = -1; dx <= 16; dx++) h[(dz + 1) * 18 + dx + 1] = surfaceY(x0 + dx, z0 + dz);
        @SuppressWarnings("unchecked") Holder<Biome>[] biome = new Holder[256];
        BlockState[] surface = new BlockState[256];
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int i = (dz + 1) * 18 + dx + 1, y = h[i];
            min = Math.min(min, y); max = Math.max(max, y);
            int slope = Math.abs(h[i + 1] - h[i - 1]) + Math.abs(h[i + 18] - h[i - 18]);
            int wx = x0 + dx, wz = z0 + dz;
            double wet = wetness(wx, wz);
            Holder<Biome> b; BlockState top;
            if (y <= SEA + 1) { b = y < SEA - 2 ? plains : beach; top = y < SEA - 3 ? GRAVEL : SAND; }
            else if (y >= snowline(wx, wz)) { b = slope > 9 ? peaks : snowy; top = slope > 12 ? STONE : SNOW_BLOCK; }
            else if (y > TREELINE) { b = wet > 0.1 ? taiga : meadow; top = slope > 7 ? STONE : GRASS; }
            else { b = wet > 0.25 ? birch : wet > -0.25 ? forest : plains; top = slope > 8 ? STONE : GRASS; }
            biome[dz * 16 + dx] = b; surface[dz * 16 + dx] = top;
        }
        Column made = new Column(h, biome, surface, min, max);
        if (columns.size() > 8192) columns.clear();
        columns.putIfAbsent(key, made);
        return made;
    }

    @Override public LevelChunkSection generate(int cx, int cy, int cz) {
        Column col = column(cx, cz);
        Holder<Biome> fallback = col.biome[8 * 16 + 8];
        LevelChunkSection section = new LevelChunkSection(
                new PalettedContainer<>(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES),
                biomes(col, fallback));
        int y0 = cy << 4, x0 = cx << 4, z0 = cz << 4;
        // Empty sky cubes: nothing generated, not even trees, above the tallest possible tree.
        if (y0 > Math.max(col.max + 16, SEA)) { section.recalcBlockCounts(); return section; }
        boolean underground = y0 + 15 < col.min - 6;
        double[] caves = caveField(x0, y0, z0, col);
        for (int ly = 0; ly < 16; ly++) {
            int y = y0 + ly;
            for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int ci = lz * 16 + lx, h = col.height[(lz + 1) * 18 + lx + 1];
                BlockState state;
                if (y > h) state = y <= SEA ? WATER : AIR;
                else {
                    int depth = h - y;
                    if (depth == 0) state = col.surface[ci];
                    else if (depth < 4) state = col.surface[ci] == SAND || col.surface[ci] == GRAVEL ? col.surface[ci]
                            : col.surface[ci] == STONE ? STONE : DIRT;
                    else state = depth > 160 + ((x0 + lx) * 31 + (z0 + lz) * 17 + y * 7 & 7) ? DEEPSLATE : STONE;
                    boolean nearWater = h <= SEA + 2;
                    if (depth > (nearWater ? 14 : 5) && cave(caves, lx, ly, lz)) {
                        state = depth > 420 && lavaNoise.noise((x0 + lx) / 40.0, y / 12.0, (z0 + lz) / 40.0) > 0.35 && ly < 4 ? LAVA : AIR;
                    }
                }
                if (state != AIR) section.setBlockState(lx, ly, lz, state, false);
            }
        }
        if (!underground) decorate(section, cx, cy, cz, col);
        ores(section, cx, cy, cz, col);
        section.recalcBlockCounts();
        return section;
    }

    private PalettedContainer<Holder<Biome>> biomes(Column col, Holder<Biome> fallback) {
        PalettedContainer<Holder<Biome>> container = new PalettedContainer<>(biomeIds, fallback, PalettedContainer.Strategy.SECTION_BIOMES);
        for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) {
            Holder<Biome> b = col.biome[(qz * 4 + 1) * 16 + qx * 4 + 1];
            for (int qy = 0; qy < 4; qy++) container.set(qx, qy, qz, b);
        }
        return container;
    }

    /** Trilinear cave field sampled every 4 blocks: tunnels plus occasional caverns. */
    private double[] caveField(int x0, int y0, int z0, Column col) {
        double[] field = new double[5 * 5 * 5];
        if (y0 > col.max) return field;
        for (int iy = 0; iy < 5; iy++) for (int iz = 0; iz < 5; iz++) for (int ix = 0; ix < 5; ix++) {
            double x = x0 + ix * 4, y = y0 + iy * 4, z = z0 + iz * 4;
            double a = tunnelA.noise(x / 56.0, y / 34.0, z / 56.0), b = tunnelB.noise(x / 56.0, y / 34.0, z / 56.0);
            double tunnel = 0.018 - (a * a + b * b);
            double big = cavern.noise(x / 110.0, y / 60.0, z / 110.0) - 0.42;
            field[(iy * 5 + iz) * 5 + ix] = Math.max(tunnel * 12, big);
        }
        return field;
    }
    private static boolean cave(double[] f, int lx, int ly, int lz) {
        int ix = lx >> 2, iy = ly >> 2, iz = lz >> 2;
        double tx = (lx & 3) / 4.0, ty = (ly & 3) / 4.0, tz = (lz & 3) / 4.0;
        double c000 = f[(iy * 5 + iz) * 5 + ix], c100 = f[(iy * 5 + iz) * 5 + ix + 1];
        double c010 = f[((iy + 1) * 5 + iz) * 5 + ix], c110 = f[((iy + 1) * 5 + iz) * 5 + ix + 1];
        double c001 = f[(iy * 5 + iz + 1) * 5 + ix], c101 = f[(iy * 5 + iz + 1) * 5 + ix + 1];
        double c011 = f[((iy + 1) * 5 + iz + 1) * 5 + ix], c111 = f[((iy + 1) * 5 + iz + 1) * 5 + ix + 1];
        double v = Mth.lerp3(tx, ty, tz, c000, c100, c010, c110, c001, c101, c011, c111);
        return v > 0;
    }

    // Decoration: trees, plants and snow --------------------------------------------------------

    private static final int CELL = 7;
    private void decorate(LevelChunkSection section, int cx, int cy, int cz, Column col) {
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        // Ground cover in the cube that holds y = h + 1.
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int ci = lz * 16 + lx, h = col.height[(lz + 1) * 18 + lx + 1], y = h + 1;
            if (y < y0 || y > y0 + 15 || h < SEA) continue;
            BlockState top = col.surface[ci];
            long r = mix(x0 + lx, h, z0 + lz);
            BlockState cover = null;
            if (top == SNOW_BLOCK) cover = SNOW;
            else if (top == GRASS) {
                int roll = (int) Math.floorMod(r, 1000);
                Holder<Biome> b = col.biome[ci];
                if (roll < (b == plains || b == meadow ? 220 : 90)) cover = b == taiga && roll % 3 == 0 ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
                else if (roll < (b == meadow ? 300 : 232)) cover = switch ((int) (r >>> 20 & 3)) {
                    case 0 -> Blocks.POPPY.defaultBlockState(); case 1 -> Blocks.DANDELION.defaultBlockState();
                    case 2 -> Blocks.CORNFLOWER.defaultBlockState(); default -> Blocks.AZURE_BLUET.defaultBlockState(); };
            }
            if (cover != null && section.getBlockState(lx, y - y0, lz).isAir()) section.setBlockState(lx, y - y0, lz, cover, false);
        }
        // Trees from every cell whose crown can reach this cube.
        int minCellX = Math.floorDiv(x0 - 3, CELL), maxCellX = Math.floorDiv(x0 + 18, CELL);
        int minCellZ = Math.floorDiv(z0 - 3, CELL), maxCellZ = Math.floorDiv(z0 + 18, CELL);
        for (int gz = minCellZ; gz <= maxCellZ; gz++) for (int gx = minCellX; gx <= maxCellX; gx++) tree(section, x0, y0, z0, gx, gz);
    }

    private void tree(LevelChunkSection section, int x0, int y0, int z0, int gx, int gz) {
        long r = mix(gx * 7919, 31, gz * 104729);
        int tx = gx * CELL + (int) Math.floorMod(r, CELL - 1), tz = gz * CELL + (int) Math.floorMod(r >>> 8, CELL - 1);
        int h = surfaceY(tx, tz);
        if (h <= SEA + 1 || h > TREELINE + 60) return;
        int slope = Math.abs(surfaceY(tx + 1, tz) - surfaceY(tx - 1, tz)) + Math.abs(surfaceY(tx, tz + 1) - surfaceY(tx, tz - 1));
        if (slope > 6) return;
        double wet = wetness(tx, tz);
        boolean high = h > TREELINE;
        int chance = high ? 55 : wet > 0.25 ? 85 : wet > -0.25 ? 80 : 12; // percent of cells with a tree
        if (Math.floorMod(r >>> 16, 100) >= chance) return;
        if (h + 12 < y0 || h - 1 > y0 + 15) return;
        int kind = high || (wet > 0.1 && h > TREELINE - 80) ? 2 : wet > 0.25 ? (Math.floorMod(r >>> 24, 10) < 7 ? 1 : 0) : (Math.floorMod(r >>> 24, 10) < 2 ? 1 : 0);
        int trunk = kind == 2 ? 7 + (int) Math.floorMod(r >>> 30, 4) : 4 + (int) Math.floorMod(r >>> 30, 3);
        BlockState log = switch (kind) { case 1 -> Blocks.BIRCH_LOG.defaultBlockState(); case 2 -> Blocks.SPRUCE_LOG.defaultBlockState(); default -> Blocks.OAK_LOG.defaultBlockState(); };
        BlockState leaves = switch (kind) { case 1 -> Blocks.BIRCH_LEAVES.defaultBlockState(); case 2 -> Blocks.SPRUCE_LEAVES.defaultBlockState(); default -> Blocks.OAK_LEAVES.defaultBlockState(); };
        leaves = leaves.setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 1);
        int top = h + trunk;
        if (kind == 2) {
            for (int y = h + 2; y <= top + 1; y++) {
                int fromTop = top + 1 - y;
                int radius = fromTop == 0 ? 0 : Math.min(3, 1 + (fromTop + ((y & 1) == 0 ? 1 : 0)) / 2);
                for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.abs(dx) + Math.abs(dz) > radius + 1 || (radius > 0 && Math.abs(dx) == radius && Math.abs(dz) == radius)) continue;
                    put(section, x0, y0, z0, tx + dx, y, tz + dz, leaves, false);
                }
            }
        } else {
            for (int y = top - 2; y <= top + 1; y++) {
                int radius = y >= top ? 1 : 2;
                for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                    if (radius == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2 && Math.floorMod(mix(tx + dx, y, tz + dz), 2) == 0) continue;
                    if (y == top + 1 && Math.abs(dx) == 1 && Math.abs(dz) == 1) continue;
                    put(section, x0, y0, z0, tx + dx, y, tz + dz, leaves, false);
                }
            }
        }
        for (int y = h + 1; y <= top; y++) put(section, x0, y0, z0, tx, y, tz, log, true);
        put(section, x0, y0, z0, tx, h, tz, DIRT, true);
    }
    private static void put(LevelChunkSection section, int x0, int y0, int z0, int x, int y, int z, BlockState state, boolean force) {
        int lx = x - x0, ly = y - y0, lz = z - z0;
        if (lx < 0 || ly < 0 || lz < 0 || lx > 15 || ly > 15 || lz > 15) return;
        BlockState here = section.getBlockState(lx, ly, lz);
        if (force || here.isAir() || here.canBeReplaced()) section.setBlockState(lx, ly, lz, state, false);
    }

    // Ores: blobs placed per cube from its own seeded random, by depth below the ground ---------

    private void ores(LevelChunkSection section, int cx, int cy, int cz, Column col) {
        int depth = col.height[8 * 18 + 9] - ((cy << 4) + 8);
        if (depth < 4) return;
        RandomSource random = new LegacyRandomSource(seed ^ mix(cx, cy, cz));
        vein(section, random, depth, 0, 200, 9, 14, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        vein(section, random, depth, 6, 400, 5, 8, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
        vein(section, random, depth, 0, 140, 4, 9, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        vein(section, random, depth, 90, 600, 1, 7, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE);
        vein(section, random, depth, 150, 900, 2, 7, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        vein(section, random, depth, 120, 700, 1, 6, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        vein(section, random, depth, 220, 100000, 1, 5, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        if (col.max > SNOWLINE - 200) vein(section, random, depth, 4, 300, 1, 1, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
    }
    private static void vein(LevelChunkSection section, RandomSource random, int depth, int minDepth, int maxDepth,
                             int tries, int size, net.minecraft.world.level.block.Block ore, net.minecraft.world.level.block.Block deep) {
        if (depth < minDepth || depth > maxDepth) { random.nextInt(); return; }
        for (int t = 0; t < tries; t++) {
            int x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
            for (int i = 0; i < size; i++) {
                BlockState here = section.getBlockState(x, y, z);
                if (here == STONE) section.setBlockState(x, y, z, ore.defaultBlockState(), false);
                else if (here == DEEPSLATE) section.setBlockState(x, y, z, deep.defaultBlockState(), false);
                switch (random.nextInt(6)) {
                    case 0 -> x = Math.min(15, x + 1); case 1 -> x = Math.max(0, x - 1);
                    case 2 -> y = Math.min(15, y + 1); case 3 -> y = Math.max(0, y - 1);
                    case 4 -> z = Math.min(15, z + 1); default -> z = Math.max(0, z - 1);
                }
            }
        }
    }

    private long mix(long x, long y, long z) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return h;
    }
}
