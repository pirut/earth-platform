package io.earthplatform.minecraft.cubic.gen;

import io.earthplatform.minecraft.cubic.far.Far;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/**
 * A world made from a height and water level per block column: subclasses say how high the ground
 * is, where water stands and what covers each column, and this class turns that into cubes with
 * caves, ores, trees and plants, and into far-terrain voxel columns that follow the same rules.
 */
public abstract class SurfaceGenerator implements CubeGenerator {
    /** Water level of a dry column. */
    public static final int DRY = Integer.MIN_VALUE;
    protected static final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState(),
            DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState(), DIRT = Blocks.DIRT.defaultBlockState(),
            GRASS = Blocks.GRASS_BLOCK.defaultBlockState(), SAND = Blocks.SAND.defaultBlockState(),
            GRAVEL = Blocks.GRAVEL.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState(),
            SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState(), SNOW = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1),
            LAVA = Blocks.LAVA.defaultBlockState(), MUD = Blocks.MUD.defaultBlockState();

    /** Tree shapes: rainforest trees with vines and bushes under them, flat-topped acacias, and mangroves on roots. */
    public enum Tree { OAK, BIRCH, SPRUCE, JUNGLE, ACACIA, MANGROVE }

    /**
     * What covers a column. {@code trees} is the percent of 7-block cells holding a tree, whose kind
     * is spruce for a roll under {@code spruce} tenths, then birch for the next {@code birch}
     * tenths, else {@code broadleaf}. Plants: a roll in thousandths under {@code grass} gives grass
     * (or a fern when {@code ferns}), else under {@code flowers} a flower; on sand they are dead
     * bushes and cacti.
     */
    public record Surface(Holder<Biome> biome, BlockState top, BlockState under, int trees, int spruce, int birch,
                          int grass, int flowers, boolean ferns, Tree broadleaf) {
        public Surface(Holder<Biome> biome, BlockState top, BlockState under, int trees, int spruce, int birch, int grass, int flowers, boolean ferns) {
            this(biome, top, under, trees, spruce, birch, grass, flowers, ferns, Tree.OAK);
        }
        public Tree tree(int roll) { return roll < spruce ? Tree.SPRUCE : roll < spruce + birch ? Tree.BIRCH : broadleaf; }
    }

    protected final long seed;
    protected final IdMap<Holder<Biome>> biomeIds;
    private final ImprovedNoise tunnelA, tunnelB, cavern, lavaNoise;
    private final ConcurrentHashMap<Long, Column> columns = new ConcurrentHashMap<>();

    /** Cave noise is drawn from {@code random} after whatever the subclass drew from it. */
    protected SurfaceGenerator(long seed, Registry<Biome> biomes, RandomSource random) {
        this.seed = seed;
        this.biomeIds = biomes.asHolderIdMap();
        tunnelA = new ImprovedNoise(random); tunnelB = new ImprovedNoise(random); cavern = new ImprovedNoise(random);
        lavaNoise = new ImprovedNoise(random);
    }
    protected static Holder<Biome> holder(Registry<Biome> registry, ResourceKey<Biome> key) { return registry.getHolderOrThrow(key); }

    // What subclasses provide -------------------------------------------------------------------

    /** Ground height (top solid block) in {@code out[0]} and the top water block in {@code out[1]}, or {@link #DRY}. */
    protected abstract void ground(int x, int z, int[] out);

    /** The same at a far level's voxel centre; levels with big voxels may use coarser, cheaper data. */
    protected void farGround(int level, int x, int z, int[] out) { ground(x, z, out); }

    /** The cover of a column, from its ground, water and slope (summed block steps across it). */
    protected abstract Surface surface(int x, int z, int ground, int water, int slope);

    /** The same for a far level's voxel centre; levels with big voxels may use coarser, cheaper data. */
    protected Surface farSurface(int level, int x, int z, int ground, int water, int slope) { return surface(x, z, ground, water, slope); }

    /** Columns at most two above this level keep caves well under the ground, so seabeds and shores hold their water. */
    protected abstract int shoreLevel();

    /** Whether a cube column whose highest ground is {@code maxGround} is mountainous enough for emeralds. */
    protected abstract boolean emeralds(int maxGround);

    // Columns ------------------------------------------------------------------------------------

    /** Heights and water for a cube column with a one-block border, plus per-column cover. */
    private record Column(int[] height, int[] water, Surface[] surface, int min, int max, int maxWater) {}

    private static long columnKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    private Column column(int cx, int cz) {
        long key = columnKey(cx, cz);
        Column cached = columns.get(key);
        if (cached != null) return cached;
        int[] h = new int[18 * 18], w = new int[18 * 18], sample = new int[2];
        int x0 = cx << 4, z0 = cz << 4;
        for (int dz = -1; dz <= 16; dz++) for (int dx = -1; dx <= 16; dx++) {
            ground(x0 + dx, z0 + dz, sample);
            h[(dz + 1) * 18 + dx + 1] = sample[0];
            w[(dz + 1) * 18 + dx + 1] = sample[1];
        }
        Surface[] surface = new Surface[256];
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, maxWater = DRY;
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int i = (dz + 1) * 18 + dx + 1, y = h[i];
            min = Math.min(min, y); max = Math.max(max, y); maxWater = Math.max(maxWater, w[i]);
            int slope = Math.abs(h[i + 1] - h[i - 1]) + Math.abs(h[i + 18] - h[i - 18]);
            surface[dz * 16 + dx] = surface(x0 + dx, z0 + dz, y, w[i], slope);
        }
        Column made = new Column(h, w, surface, min, max, maxWater);
        if (columns.size() > 8192) columns.clear();
        columns.putIfAbsent(key, made);
        return made;
    }

    @Override public final int surfaceY(int x, int z) {
        Column cached = columns.get(columnKey(x >> 4, z >> 4));
        if (cached != null) return cached.height[((z & 15) + 1) * 18 + (x & 15) + 1];
        int[] sample = new int[2];
        ground(x, z, sample);
        return sample[0];
    }

    @Override public final int waterY(int x, int z) {
        Column cached = columns.get(columnKey(x >> 4, z >> 4));
        if (cached != null) return cached.water[((z & 15) + 1) * 18 + (x & 15) + 1];
        int[] sample = new int[2];
        ground(x, z, sample);
        return sample[1];
    }

    // Far terrain: voxel columns sampled from the same height, cover and tree rules -------------

    @Override public void farColumn(int level, int columnX, int columnZ, FarColumn out) {
        int v = 1 << level, width = 16 * v, x0 = columnX * width, z0 = columnZ * width, half = v >> 1;
        int[] ground = new int[18 * 18], water = new int[18 * 18], sample = new int[2];
        for (int j = 0; j < 18; j++) for (int i = 0; i < 18; i++) {
            farGround(level, x0 + (i - 1) * v + half, z0 + (j - 1) * v + half, sample);
            // Far below a water surface nothing shows, so deep seabeds are raised to save sections.
            int floor = sample[1] == DRY ? Integer.MIN_VALUE : sample[1] - width;
            ground[j * 18 + i] = Math.max(sample[0], floor);
            water[j * 18 + i] = sample[1];
        }
        @SuppressWarnings("unchecked") Holder<Biome>[] biome = new Holder[256];
        BlockState[] top = new BlockState[256], under = new BlockState[256];
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
            int g = (cz + 1) * 18 + cx + 1, h = ground[g], cell = cz * 16 + cx;
            int wx = x0 + cx * v + half, wz = z0 + cz * v + half;
            int slope = (Math.abs(ground[g + 1] - ground[g - 1]) + Math.abs(ground[g + 18] - ground[g - 18])) / v;
            Surface surface = farSurface(level, wx, wz, h, water[g], slope);
            biome[cell] = surface.biome();
            top[cell] = surface.top();
            under[cell] = v > 2 ? STONE : surface.under();
            // Voxels wider than a tree: forests show as leaves on the ground voxel, as a downsampled forest does.
            if (v >= 8) { BlockState leaves = canopy(surface, wx, wz, v); if (leaves != null) top[cell] = leaves; }
            min = Math.min(min, h);
            max = Math.max(max, Math.max(h, water[g]));
        }
        if (v <= 4) max += TALLEST + 1;
        int bottom = (min - v) >> (level + 4), highest = max >> (level + 4);
        // Columns taller than the section budget keep their top, where the view is.
        bottom = Math.max(bottom, highest - Far.MAX_SECTIONS + 1);
        int sections = highest - bottom + 1, base = bottom * width;
        out.bottom = bottom;
        for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
            int g = (cz + 1) * 18 + cx + 1;
            out.drops[cz * 16 + cx] = drop(ground[g], water[g], base, v);
        }
        LevelChunkSection[] stack = new LevelChunkSection[sections];
        for (int si = 0; si < sections; si++) {
            LevelChunkSection section = new LevelChunkSection(
                    new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES), farBiomes(biome));
            int sectionY = (bottom + si) * width;
            for (int vy = 0; vy < 16; vy++) {
                int y0 = sectionY + vy * v;
                for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
                    int g = (cz + 1) * 18 + cx + 1;
                    BlockState state = voxel(ground[g], water[g], y0, v, top[cz * 16 + cx], under[cz * 16 + cx]);
                    if (state != AIR) section.setBlockState(cx, vy, cz, state, false);
                }
            }
            stack[si] = section;
        }
        if (v <= 4) farTrees(level, x0, z0, bottom, stack);
        for (LevelChunkSection section : stack) {
            section.recalcBlockCounts();
            out.sections.add(section.hasOnlyAir() ? null : section);
        }
    }

    /**
     * The block a voxel of {@code v} blocks starting at y0 stands for: ground once any of it is,
     * its top block if the voxel above is not ground, and under standing water the water's top
     * voxel is all water, so shallow lakes still show from afar. The column's drop then lowers the
     * voxels so the top one ends at the real height.
     */
    private static BlockState voxel(int h, int water, int y0, int v, BlockState top, BlockState under) {
        int ceiling = Integer.MAX_VALUE;
        if (water != DRY && water > h) {
            ceiling = Math.floorDiv(water, v) * v;
            if (y0 >= ceiling) return y0 == ceiling ? WATER : AIR;
        }
        if (h < y0) return ceiling == Integer.MAX_VALUE ? AIR : WATER;
        if (h < y0 + v || y0 + v == ceiling) return top;
        return h < y0 + 2 * v || y0 + 2 * v == ceiling ? under : STONE;
    }

    /**
     * How far a voxel column's stack sits below the voxel grid, in 1/{@link Far#DROP_UNIT}
     * blocks, so the top of its highest ground or water voxel lands on the real surface: the top
     * of the ground, or the water's surface a little under the top of its top block, as near
     * water is drawn.
     */
    private static short drop(int h, int water, int base, int v) {
        double surface = (water != DRY && water > h ? water + Far.WATER_SURFACE : h + 1) - base;
        int row = (int) Math.ceil(surface / v) - 1;
        double drop = (row + 1) * (double) v - surface;
        return (short) Math.clamp(Math.round(drop * Far.DROP_UNIT), 0, v * Far.DROP_UNIT - 1);
    }

    private PalettedContainer<Holder<Biome>> farBiomes(Holder<Biome>[] biome) {
        PalettedContainer<Holder<Biome>> container = new PalettedContainer<>(biomeIds, biome[8 * 16 + 8], PalettedContainer.Strategy.SECTION_BIOMES);
        for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) {
            Holder<Biome> b = biome[(qz * 4 + 1) * 16 + qx * 4 + 1];
            for (int qy = 0; qy < 4; qy++) container.set(qx, qy, qz, b);
        }
        return container;
    }

    /** Rasterises the real trees into voxels of 2 or 4 blocks: a voxel turns to leaves or log once a quarter of it is. */
    private void farTrees(int level, int x0, int z0, int bottom, LevelChunkSection[] stack) {
        int v = 1 << level, width = 16 * v, height = stack.length * 16, baseY = bottom * width;
        int[] leafCount = new int[16 * 16 * height], logCount = new int[leafCount.length];
        BlockState[] leafKind = new BlockState[leafCount.length], logKind = new BlockState[leafCount.length];
        int minCellX = Math.floorDiv(x0 - REACH, CELL), maxCellX = Math.floorDiv(x0 + width + REACH, CELL);
        int minCellZ = Math.floorDiv(z0 - REACH, CELL), maxCellZ = Math.floorDiv(z0 + width + REACH, CELL);
        TreeSink sink = (x, y, z, state, force) -> {
            int vx = (x - x0) >> level, vz = (z - z0) >> level, vy = (y - baseY) >> level;
            if (vx < 0 || vz < 0 || vx > 15 || vz > 15 || vy < 0 || vy >= height || state == DIRT) return;
            int i = (vy * 16 + vz) * 16 + vx;
            if (state.getBlock() instanceof LeavesBlock) { leafCount[i]++; leafKind[i] = state; }
            else { logCount[i]++; logKind[i] = state; }
        };
        for (int gz = minCellZ; gz <= maxCellZ; gz++) for (int gx = minCellX; gx <= maxCellX; gx++) tree(gx, gz, baseY, baseY + height * v - 1, sink);
        int quarter = Math.max(1, v * v * v / 4);
        for (int i = 0; i < leafCount.length; i++) {
            if (leafCount[i] < quarter && logCount[i] < quarter) continue;
            int vx = i & 15, vz = i >> 4 & 15, vy = i >> 8;
            LevelChunkSection section = stack[vy >> 4];
            if (!section.getBlockState(vx, vy & 15, vz).isAir()) continue;
            BlockState state = logCount[i] >= quarter && logCount[i] * 2 >= leafCount[i] ? logKind[i] : leafKind[i];
            if (state != null) section.setBlockState(vx, vy & 15, vz, state, false);
        }
    }

    /**
     * Leaves for a far cell that trees cover, or null. Uses tree()'s placement odds, since a voxel
     * many blocks wide cannot follow individual trees.
     */
    private BlockState canopy(Surface surface, int wx, int wz, int v) {
        if (surface.top() != GRASS && surface.top() != MUD || surface.trees() <= 0) return null;
        long r = mix(wx, v, wz);
        // Crowns cover a bit over half of each tree's cell.
        if (Math.floorMod(r, 1000) >= surface.trees() * 6) return null;
        return switch (surface.tree((int) Math.floorMod(r >>> 24, 10))) {
            case SPRUCE -> Blocks.SPRUCE_LEAVES.defaultBlockState();
            case BIRCH -> Blocks.BIRCH_LEAVES.defaultBlockState();
            case OAK -> Blocks.OAK_LEAVES.defaultBlockState();
            case JUNGLE -> Blocks.JUNGLE_LEAVES.defaultBlockState();
            case ACACIA -> Blocks.ACACIA_LEAVES.defaultBlockState();
            case MANGROVE -> Blocks.MANGROVE_LEAVES.defaultBlockState();
        };
    }

    // Cubes --------------------------------------------------------------------------------------

    @Override public LevelChunkSection generate(int cx, int cy, int cz) {
        Column col = column(cx, cz);
        Holder<Biome> fallback = col.surface[8 * 16 + 8].biome();
        LevelChunkSection section = new LevelChunkSection(
                new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES),
                biomes(col, fallback));
        int y0 = cy << 4, x0 = cx << 4, z0 = cz << 4;
        // Empty sky cubes: nothing generated, not even trees, above the tallest possible tree.
        if (y0 > Math.max(col.max + 16, col.maxWater)) { section.recalcBlockCounts(); return section; }
        boolean underground = y0 + 15 < col.min - 6;
        double[] caves = caveField(x0, y0, z0, col);
        int shore = shoreLevel() + 2;
        for (int ly = 0; ly < 16; ly++) {
            int y = y0 + ly;
            for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int ci = lz * 16 + lx, gi = (lz + 1) * 18 + lx + 1, h = col.height[gi];
                Surface surface = col.surface[ci];
                BlockState state;
                if (y > h) state = y <= col.water[gi] ? WATER : AIR;
                else {
                    int depth = h - y;
                    if (depth == 0) state = surface.top();
                    else if (depth < 4) state = surface.under();
                    else state = depth > 160 + ((x0 + lx) * 31 + (z0 + lz) * 17 + y * 7 & 7) ? DEEPSLATE : STONE;
                    boolean nearWater = h <= shore || col.water[gi] != DRY;
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
            Holder<Biome> b = col.surface[(qz * 4 + 1) * 16 + qx * 4 + 1].biome();
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

    /** Tree placement cell size, how far a tree reaches from its trunk, and how far above the ground its top can be. */
    private static final int CELL = 7, REACH = 5, TALLEST = 16;
    private static final BlockState CACTUS = Blocks.CACTUS.defaultBlockState();
    private void decorate(LevelChunkSection section, int cx, int cy, int cz, Column col) {
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        // Ground cover in the cube that holds y = h + 1.
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int ci = lz * 16 + lx, gi = (lz + 1) * 18 + lx + 1, h = col.height[gi], y = h + 1;
            if (y + 2 < y0 || y > y0 + 15 || col.water[gi] != DRY) continue;
            Surface surface = col.surface[ci];
            BlockState top = surface.top();
            long r = mix(x0 + lx, h, z0 + lz);
            BlockState cover = null;
            int roll = (int) Math.floorMod(r, 1000);
            if (top == SNOW_BLOCK) cover = SNOW;
            // Sand only grows anything in desert scrub: dead bushes, and cacti on every other column
            // (so no two touch) with no higher ground beside them.
            else if (top == SAND && roll < surface.flowers()) {
                if (roll < surface.grass()) cover = Blocks.DEAD_BUSH.defaultBlockState();
                else if (((x0 + lx | z0 + lz) & 1) == 0 && col.height[gi - 1] <= h && col.height[gi + 1] <= h
                        && col.height[gi - 18] <= h && col.height[gi + 18] <= h) {
                    for (int k = 1 + (int) Math.floorMod(r >>> 20, 3); k > 0; k--) {
                        int cy0 = y + k - 1 - y0;
                        if (cy0 >= 0 && cy0 < 16 && section.getBlockState(lx, cy0, lz).isAir()) section.setBlockState(lx, cy0, lz, CACTUS, false);
                    }
                }
            }
            else if (top == GRASS) {
                if (roll < surface.grass()) cover = surface.ferns() && roll % 3 == 0 ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
                else if (roll < surface.flowers()) cover = switch ((int) (r >>> 20 & 3)) {
                    case 0 -> Blocks.POPPY.defaultBlockState(); case 1 -> Blocks.DANDELION.defaultBlockState();
                    case 2 -> Blocks.CORNFLOWER.defaultBlockState(); default -> Blocks.AZURE_BLUET.defaultBlockState(); };
            }
            if (cover != null && y <= y0 + 15 && y >= y0 && section.getBlockState(lx, y - y0, lz).isAir()) section.setBlockState(lx, y - y0, lz, cover, false);
        }
        // Trees from every cell whose crown can reach this cube.
        int minCellX = Math.floorDiv(x0 - REACH, CELL), maxCellX = Math.floorDiv(x0 + 15 + REACH, CELL);
        int minCellZ = Math.floorDiv(z0 - REACH, CELL), maxCellZ = Math.floorDiv(z0 + 15 + REACH, CELL);
        TreeSink sink = (x, y, z, state, force) -> put(section, x0, y0, z0, x, y, z, state, force);
        for (int gz = minCellZ; gz <= maxCellZ; gz++) for (int gx = minCellX; gx <= maxCellX; gx++) tree(gx, gz, y0, y0 + 15, sink);
    }

    /** Receives a tree's blocks; {@code force} marks trunk blocks that replace whatever is there. */
    private interface TreeSink { void put(int x, int y, int z, BlockState state, boolean force); }

    /** The tree of one placement cell, if it has one reaching heights minY..maxY. */
    private void tree(int gx, int gz, int minY, int maxY, TreeSink sink) {
        long r = mix(gx * 7919, 31, gz * 104729);
        int tx = gx * CELL + (int) Math.floorMod(r, CELL - 1), tz = gz * CELL + (int) Math.floorMod(r >>> 8, CELL - 1);
        int h = surfaceY(tx, tz);
        if (h + TALLEST < minY || h - 1 > maxY) return;
        int slope = Math.abs(surfaceY(tx + 1, tz) - surfaceY(tx - 1, tz)) + Math.abs(surfaceY(tx, tz + 1) - surfaceY(tx, tz - 1));
        Surface surface = surface(tx, tz, h, waterY(tx, tz), slope);
        if (Math.floorMod(r >>> 16, 100) >= surface.trees()) return;
        Tree kind = surface.tree((int) Math.floorMod(r >>> 24, 10));
        switch (kind) {
            case JUNGLE -> { jungle(tx, tz, h, r, sink); return; }
            case ACACIA -> { acacia(tx, tz, h, r, sink); return; }
            default -> {}
        }
        int trunk = kind == Tree.SPRUCE ? 7 + (int) Math.floorMod(r >>> 30, 4) : 4 + (int) Math.floorMod(r >>> 30, 3);
        BlockState log = switch (kind) {
            case BIRCH -> Blocks.BIRCH_LOG.defaultBlockState(); case SPRUCE -> Blocks.SPRUCE_LOG.defaultBlockState();
            case MANGROVE -> Blocks.MANGROVE_LOG.defaultBlockState(); default -> Blocks.OAK_LOG.defaultBlockState(); };
        BlockState leaves = switch (kind) {
            case BIRCH -> Blocks.BIRCH_LEAVES.defaultBlockState(); case SPRUCE -> Blocks.SPRUCE_LEAVES.defaultBlockState();
            case MANGROVE -> Blocks.MANGROVE_LEAVES.defaultBlockState(); default -> Blocks.OAK_LEAVES.defaultBlockState(); };
        leaves = leaves.setValue(LeavesBlock.DISTANCE, 1);
        // Mangroves stand on a tangle of roots above the mud.
        int foot = h;
        if (kind == Tree.MANGROVE) {
            BlockState roots = Blocks.MANGROVE_ROOTS.defaultBlockState();
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 && dz != 0) continue;
                sink.put(tx + dx, h + 1, tz + dz, roots, false);
                if (dx != 0 || dz != 0) sink.put(tx + 2 * dx, h + 1, tz + 2 * dz, roots, false);
            }
            foot = h + 1;
        }
        int top = foot + trunk;
        if (kind == Tree.SPRUCE) {
            for (int y = h + 2; y <= top + 1; y++) {
                int fromTop = top + 1 - y;
                int radius = fromTop == 0 ? 0 : Math.min(3, 1 + (fromTop + ((y & 1) == 0 ? 1 : 0)) / 2);
                for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.abs(dx) + Math.abs(dz) > radius + 1 || (radius > 0 && Math.abs(dx) == radius && Math.abs(dz) == radius)) continue;
                    sink.put(tx + dx, y, tz + dz, leaves, false);
                }
            }
        } else {
            for (int y = top - 2; y <= top + 1; y++) {
                int radius = y >= top ? 1 : 2;
                for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                    if (radius == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2 && Math.floorMod(mix(tx + dx, y, tz + dz), 2) == 0) continue;
                    if (y == top + 1 && Math.abs(dx) == 1 && Math.abs(dz) == 1) continue;
                    sink.put(tx + dx, y, tz + dz, leaves, false);
                }
            }
        }
        for (int y = foot + 1; y <= top; y++) sink.put(tx, y, tz, log, true);
        sink.put(tx, h, tz, kind == Tree.MANGROVE ? Blocks.MUDDY_MANGROVE_ROOTS.defaultBlockState() : DIRT, true);
    }

    /**
     * A rainforest tree: a tall trunk, a giant one in four on a 2x2 trunk, under a broad flat crown
     * hung with vines, with a bush beside it for the undergrowth. Trees of different heights layer
     * the canopy as a real rainforest's does.
     */
    private void jungle(int tx, int tz, int h, long r, TreeSink sink) {
        BlockState log = Blocks.JUNGLE_LOG.defaultBlockState();
        BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        boolean giant = Math.floorMod(r >>> 40, 4) == 0;
        int width = giant ? 2 : 1, radius = giant ? 4 : 3;
        int top = h + (giant ? 12 + (int) Math.floorMod(r >>> 30, 3) : 7 + (int) Math.floorMod(r >>> 30, 5));
        // Crown centre, doubled so a 2x2 trunk's lies between its logs.
        int cx2 = 2 * tx + width - 1, cz2 = 2 * tz + width - 1;
        for (int y = top - 1; y <= top + 1; y++) {
            int ry = y == top + 1 ? radius - 2 : radius;
            int reach = 2 * ry + width - 1;
            for (int z = tz - ry; z <= tz + ry + width - 1; z++) for (int x = tx - ry; x <= tx + ry + width - 1; x++) {
                int dx = 2 * x - cx2, dz = 2 * z - cz2, d = dx * dx + dz * dz;
                if (d > reach * reach + 2) continue;
                // Ragged edges.
                if (d > (reach - 2) * (reach - 2) && Math.floorMod(mix(x, y, z), 3) == 0) continue;
                sink.put(x, y, z, leaves, false);
                // Vines hang from the crown's lower rim.
                if (y == top - 1) {
                    long v = mix(x, y * 3 + 1, z);
                    if (Math.floorMod(v, 4) != 0) continue;
                    int len = 1 + (int) Math.floorMod(v >>> 8, 5);
                    hang(sink, x, y, z, cx2, cz2, reach, len);
                }
            }
        }
        for (int y = h + 1; y <= top; y++) for (int dz = 0; dz < width; dz++) for (int dx = 0; dx < width; dx++) sink.put(tx + dx, y, tz + dz, log, true);
        for (int dz = 0; dz < width; dz++) for (int dx = 0; dx < width; dx++) sink.put(tx + dx, h, tz + dz, DIRT, true);
        // A bush three blocks away on each axis, still in this tree's cell.
        int bx = tx - Math.floorMod(tx, CELL) + (Math.floorMod(tx, CELL) + 3) % (CELL - 1);
        int bz = tz - Math.floorMod(tz, CELL) + (Math.floorMod(tz, CELL) + 3) % (CELL - 1);
        int bh = surfaceY(bx, bz);
        if (Math.abs(bh - h) > 1 || waterY(bx, bz) != DRY) return;
        BlockState bushLeaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        for (int y = bh + 1; y <= bh + 2; y++) {
            int rb = y == bh + 1 ? 2 : 1;
            for (int dz = -rb; dz <= rb; dz++) for (int dx = -rb; dx <= rb; dx++) {
                if (Math.abs(dx) + Math.abs(dz) > rb + (rb == 2 && Math.floorMod(mix(bx + dx, y, bz + dz), 2) == 0 ? 1 : 0)) continue;
                sink.put(bx + dx, y, bz + dz, bushLeaves, false);
            }
        }
        sink.put(bx, bh + 1, bz, log, true);
    }

    /**
     * Vines down the outward side of a crown block whose neighbour that way is outside the crown,
     * attached to the leaves and then to the vine above. The crown is centred on (cx2, cz2) / 2.
     */
    private static void hang(TreeSink sink, int x, int y, int z, int cx2, int cz2, int reach, int len) {
        int dx = 2 * x - cx2, dz = 2 * z - cz2, sx = Integer.signum(dx), sz = Integer.signum(dz);
        boolean alongX = sx != 0 && (sz == 0 || (x + z & 1) == 0);
        int vx = alongX ? x + sx : x, vz = alongX ? z : z + sz;
        int ox = 2 * vx - cx2, oz = 2 * vz - cz2;
        if (ox * ox + oz * oz <= reach * reach + 2) return;
        BooleanProperty face = alongX ? (sx > 0 ? VineBlock.WEST : VineBlock.EAST) : (sz > 0 ? VineBlock.NORTH : VineBlock.SOUTH);
        BlockState vine = Blocks.VINE.defaultBlockState().setValue(face, true);
        for (int k = 0; k < len; k++) sink.put(vx, y - k, vz, vine, false);
    }

    /** A savanna acacia: a trunk that leans after a few blocks under a wide, flat crown. */
    private void acacia(int tx, int tz, int h, long r, TreeSink sink) {
        BlockState log = Blocks.ACACIA_LOG.defaultBlockState();
        BlockState leaves = Blocks.ACACIA_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        int trunk = 5 + (int) Math.floorMod(r >>> 30, 3), bend = 2 + (int) Math.floorMod(r >>> 34, 2);
        int dir = (int) Math.floorMod(r >>> 36, 4), sx = dir == 0 ? 1 : dir == 1 ? -1 : 0, sz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
        int x = tx, z = tz, top = h + trunk;
        for (int y = h + 1; y <= top; y++) {
            if (y > h + bend && y < top && Math.abs(x - tx) + Math.abs(z - tz) < 2) { x += sx; z += sz; }
            sink.put(x, y, z, log, true);
        }
        sink.put(tx, h, tz, DIRT, true);
        for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
            int a = Math.abs(dx) + Math.abs(dz);
            if (a <= 4 && !(a == 4 && Math.floorMod(mix(x + dx, top, z + dz), 2) == 0)) sink.put(x + dx, top + 1, z + dz, leaves, false);
            if (a <= 1) sink.put(x + dx, top + 2, z + dz, leaves, false);
        }
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
        if (emeralds(col.max)) vein(section, random, depth, 4, 300, 1, 1, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
    }
    private static void vein(LevelChunkSection section, RandomSource random, int depth, int minDepth, int maxDepth,
                             int tries, int size, Block ore, Block deep) {
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

    protected final long mix(long x, long y, long z) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return h;
    }
}
