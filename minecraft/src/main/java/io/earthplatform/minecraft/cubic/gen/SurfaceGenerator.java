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
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
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

    /**
     * Tree kinds, grown by {@link Trees} with vanilla's tree features: oak (with fancy oak and
     * birch as the biome has them), birch, spruce and pine, jungle trees, acacia, mangrove, dark
     * oak, cherry and azalea. Sequoia marks a giant sequoia grove: giants stand among its spruces.
     */
    public enum Tree { OAK, BIRCH, SPRUCE, JUNGLE, ACACIA, MANGROVE, DARK_OAK, CHERRY, AZALEA, SEQUOIA }

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
    private final Vegetation vegetation = new Vegetation(this);

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

    /** Whether giant sequoia groves may be near a column; their trees stand far taller than any other. */
    protected boolean giants(int x, int z) { return false; }

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

    /** A column's cover, from the cube column cache when it holds the column. */
    final Surface coverAt(int x, int z, int ground, int water) {
        Column cached = columns.get(columnKey(x >> 4, z >> 4));
        if (cached != null) return cached.surface[(z & 15) * 16 + (x & 15)];
        int slope = Math.abs(surfaceY(x + 1, z) - surfaceY(x - 1, z)) + Math.abs(surfaceY(x, z + 1) - surfaceY(x, z - 1));
        return surface(x, z, ground, water, slope);
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
        BlockState[] top = new BlockState[256], under = new BlockState[256], crown = new BlockState[256];
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
            int g = (cz + 1) * 18 + cx + 1, h = ground[g], cell = cz * 16 + cx;
            int wx = x0 + cx * v + half, wz = z0 + cz * v + half;
            int slope = (Math.abs(ground[g + 1] - ground[g - 1]) + Math.abs(ground[g + 18] - ground[g - 18])) / v;
            Surface surface = farSurface(level, wx, wz, h, water[g], slope);
            biome[cell] = surface.biome();
            top[cell] = surface.top();
            under[cell] = v > 2 ? STONE : surface.under();
            // Voxels wider than a tree show forests as leaves: at 8 blocks a voxel above the ground, about a tree's
            // height, so they meet the rasterised trees nearer; wider voxels would stand too tall, so leaves lie on the ground voxel.
            if (v >= 8) {
                BlockState leaves = water[g] > h ? null : canopy(surface, wx, wz, v);
                if (leaves != null && v == 8) crown[cell] = leaves;
                else if (leaves != null) top[cell] = leaves;
            }
            min = Math.min(min, h);
            max = Math.max(max, Math.max(h, water[g]));
        }
        // Room for trees: drawn as they are out to 4-block voxels, sequoias out to 16.
        boolean giants = v <= 16 && giants(x0 + width / 2, z0 + width / 2);
        if (giants) max += Sequoia.TALLEST + 1;
        else if (v <= 4) max += Trees.TALLEST + 1;
        else if (v == 8) max += v;
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
                    if (state == AIR && crown[cz * 16 + cx] != null && ground[g] < y0 && ground[g] >= y0 - v) state = crown[cz * 16 + cx];
                    if (state != AIR) section.setBlockState(cx, vy, cz, state, false);
                }
            }
            stack[si] = section;
        }
        if (v <= 4 || giants) farTrees(level, x0, z0, bottom, stack, v > 4);
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

    /**
     * Rasterises the real trees into voxels of 2 or 4 blocks, or only the sequoias into wider
     * ones: a voxel turns to leaves or log once a quarter of it is (an eighth for sequoias, whose
     * crowns are looser than a voxel that wide).
     */
    private void farTrees(int level, int x0, int z0, int bottom, LevelChunkSection[] stack, boolean giantsOnly) {
        int v = 1 << level, width = 16 * v, height = stack.length * 16, baseY = bottom * width;
        int[] leafCount = new int[16 * 16 * height], logCount = new int[leafCount.length], snowCount = new int[leafCount.length];
        BlockState[] leafKind = new BlockState[leafCount.length], logKind = new BlockState[leafCount.length];
        TreeShape.Sink sink = (x, y, z, state, kind) -> {
            if (kind == TreeShape.GROUND) return;
            int vx = (x - x0) >> level, vz = (z - z0) >> level, vy = (y - baseY) >> level;
            if (vx < 0 || vz < 0 || vx > 15 || vz > 15 || vy < 0 || vy >= height) return;
            int i = (vy * 16 + vz) * 16 + vx;
            Block block = state.getBlock();
            // Crowns and trunks only: vines, cocoa and roots are too small to show.
            if (block instanceof LeavesBlock || block instanceof HugeMushroomBlock) { leafCount[i]++; leafKind[i] = state; }
            else if (block instanceof RotatedPillarBlock && block != Blocks.MUDDY_MANGROVE_ROOTS) { logCount[i]++; logKind[i] = state; }
            else if (block == Blocks.SNOW) snowCount[i]++;
        };
        vegetation.trees(x0, baseY, z0, x0 + width - 1, baseY + height * v - 1, z0 + width - 1, giantsOnly, sink);
        int quarter = Math.max(1, v * v * v / (giantsOnly ? 8 : 4));
        for (int i = 0; i < leafCount.length; i++) {
            if (leafCount[i] < quarter && logCount[i] < quarter) continue;
            int vx = i & 15, vz = i >> 4 & 15, vy = i >> 8;
            LevelChunkSection section = stack[vy >> 4];
            if (!section.getBlockState(vx, vy & 15, vz).isAir()) continue;
            BlockState state = logCount[i] >= quarter && logCount[i] * 2 >= leafCount[i] ? logKind[i] : leafKind[i];
            // Leaves under snow that covers a quarter of the voxel's top, lying in it or just above, show white.
            int snow = snowCount[i] + (i + 256 < snowCount.length ? snowCount[i + 256] : 0);
            if (state == leafKind[i] && snow > 0 && snow * 4 >= v * v) state = SNOW_BLOCK;
            if (state != null) section.setBlockState(vx, vy & 15, vz, state, false);
        }
    }

    /**
     * Leaves for a far cell that trees cover, or null. Uses tree()'s placement odds, since a voxel
     * many blocks wide cannot follow individual trees.
     */
    private BlockState canopy(Surface surface, int wx, int wz, int v) {
        if (surface.top() != GRASS && surface.top() != MUD && !surface.top().is(Blocks.PODZOL) || surface.trees() <= 0) return null;
        long r = mix(wx, v, wz);
        Tree kind = surface.tree((int) Math.floorMod(r >>> 24, 10));
        // A tree's crown covers about 20 columns: thousandths covered, from its attempts per 256 columns.
        if (Math.floorMod(r, 1000) >= Math.min(950, surface.trees() * Trees.full(kind) * 3 / 4)) return null;
        // Where snow falls it lies on most crowns, as it does on the trees nearer.
        return Trees.snowy(surface) && Math.floorMod(r >>> 40, 3) != 0 ? SNOW_BLOCK : Vegetation.leaves(kind);
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
        if (y0 > Math.max(col.max + vegetation.tallest(cx, cz), col.maxWater)) { section.recalcBlockCounts(); return section; }
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
        if (!underground) vegetation.decorate(section, x0, y0, z0, col.height, col.water, col.surface);
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
