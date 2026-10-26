package io.earthplatform.minecraft.cubic.gen;

import io.earthplatform.minecraft.cubic.far.Far;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

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
            FALLING_WATER = Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 8),
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
    private final Registry<Biome> biomeRegistry;
    private final Underground below;
    /** Vanilla's structures, once a server level is attached; until then cubes are terrain only. */
    @Nullable private volatile VanillaStructures vanilla;
    /** Base cubes (before vanilla structures) that structure layout reads, by section key. */
    private final ConcurrentHashMap<Long, LevelChunkSection> bases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Column> columns = new ConcurrentHashMap<>();
    private final Vegetation vegetation = new Vegetation(this);

    /** Cave noise is drawn from {@code random} after whatever the subclass drew from it. */
    protected SurfaceGenerator(long seed, Registry<Biome> biomes, RandomSource random) {
        this.seed = seed;
        this.biomeIds = biomes.asHolderIdMap();
        this.biomeRegistry = biomes;
        below = new Underground(this, seed, biomes, random);
    }
    Underground underground() { return below; }
    protected static Holder<Biome> holder(Registry<Biome> registry, ResourceKey<Biome> key) { return registry.getHolderOrThrow(key); }

    /** Fractal noise: {@code octaves} layers of {@code noise}, each half as strong and 2.03x as fine, averaged by weight. */
    protected static double fbm(SimplexNoise noise, double x, double z, int octaves) {
        double sum = 0, amplitude = 1, norm = 0;
        for (int i = 0; i < octaves; i++) { sum += noise.getValue(x, z) * amplitude; norm += amplitude; amplitude *= 0.5; x *= 2.03; z *= 2.03; }
        return sum / norm;
    }

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

    /** Whether the water over a column is iced over: by default wherever its biome freezes. */
    protected boolean iced(Surface surface, int x, int z) { return surface.biome().value().getBaseTemperature() < 0.15f; }
    /** The rock layers a column shows in its cliff faces, or null for plain stone. */
    protected Strata.Layers layers(int x, int z) { return null; }

    /**
     * Whether open air at height {@code y} of column {@code i} of an 18-wide grid lies beside higher
     * water, where water steps down: there it pours over the edge as falling water, rather than
     * standing as a still wall at the edge.
     */
    private static boolean falls(int[] water, int i, int y) {
        return water[i - 1] >= y || water[i + 1] >= y || water[i - 18] >= y || water[i + 18] >= y;
    }

    /**
     * Whether a column's under block is the country rock itself, as sandstone, terracotta and chalk are,
     * so its cliff faces show it rather than plain stone: desert cliffs and monoliths such as Uluru
     * otherwise stood as grey stone walls under a few blocks of red.
     */
    private static boolean bedrock(BlockState under) {
        return under.is(Blocks.SANDSTONE) || under.is(Blocks.RED_SANDSTONE) || under.is(BlockTags.TERRACOTTA) || under.is(Blocks.CALCITE);
    }

    // Columns ------------------------------------------------------------------------------------

    /** Heights and water for a cube column with a one-block border, plus per-column cover. */
    private record Column(int[] height, int[] water, Surface[] surface, Strata.Layers[] layers, int min, int max, int maxWater) {}

    /**
     * Keys of the generators' caches go through a mix: a boxed Long hashes as x ^ z, so nearby
     * cells shared a handful of buckets, and lookups crawled through the bins' trees.
     */
    private static long columnKey(int cx, int cz) { return it.unimi.dsi.fastutil.HashCommon.mix(((long) cx << 32) | (cz & 0xFFFFFFFFL)); }

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
        Strata.Layers[] layers = new Strata.Layers[256];
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, maxWater = DRY;
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int i = (dz + 1) * 18 + dx + 1, y = h[i];
            min = Math.min(min, y); max = Math.max(max, y); maxWater = Math.max(maxWater, w[i]);
            int slope = Math.abs(h[i + 1] - h[i - 1]) + Math.abs(h[i + 18] - h[i - 18]);
            surface[dz * 16 + dx] = surface(x0 + dx, z0 + dz, y, w[i], slope);
            layers[dz * 16 + dx] = layers(x0 + dx, z0 + dz);
        }
        Column made = new Column(h, w, surface, layers, min, max, maxWater);
        if (columns.size() > 8192) columns.clear();
        columns.putIfAbsent(key, made);
        return made;
    }

    /** The cube column's own lowest ground, from the column its cubes are made from. */
    @Override public final int lowestGround(int cubeX, int cubeZ) { return column(cubeX, cubeZ).min; }

    @Override public final int surfaceY(int x, int z) {
        Column cached = columns.get(columnKey(x >> 4, z >> 4));
        if (cached != null) return cached.height[((z & 15) + 1) * 18 + (x & 15) + 1];
        return (int) (groundOf(x, z) >> 32);
    }

    /** Ground and water of columns asked about before their cube column is made, packed as ground << 32 | water. */
    private final ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLongArray> grounds = new ConcurrentHashMap<>();
    private static final long UNKNOWN = Long.MIN_VALUE;

    /**
     * A column's ground and water, remembered: dungeons and cave roofs ask about the columns round a cube
     * before those columns' cubes are made, many times over, and each answer reads the whole survey.
     */
    private long groundOf(int x, int z) {
        long key = columnKey(x >> 4, z >> 4);
        java.util.concurrent.atomic.AtomicLongArray known = grounds.get(key);
        if (known == null) {
            java.util.concurrent.atomic.AtomicLongArray fresh = new java.util.concurrent.atomic.AtomicLongArray(256);
            for (int i = 0; i < 256; i++) fresh.set(i, UNKNOWN);
            if (grounds.size() > 8192) grounds.clear();
            known = grounds.putIfAbsent(key, fresh);
            if (known == null) known = fresh;
        }
        int i = (z & 15) << 4 | x & 15;
        long packed = known.get(i);
        if (packed != UNKNOWN) return packed;
        int[] sample = new int[2];
        ground(x, z, sample);
        packed = (long) sample[0] << 32 | sample[1] & 0xFFFFFFFFL;
        known.set(i, packed);
        return packed;
    }

    /** A column's cover, from the cube column cache when it holds the column. */
    final Surface coverAt(int x, int z, int ground, int water) {
        Column cached = columns.get(columnKey(x >> 4, z >> 4));
        if (cached != null) return cached.surface[(z & 15) * 16 + (x & 15)];
        int slope = Math.abs(surfaceY(x + 1, z) - surfaceY(x - 1, z)) + Math.abs(surfaceY(x, z + 1) - surfaceY(x, z - 1));
        return surface(x, z, ground, water, slope);
    }

    /** Whether trees grow in a column (the same rule tree placement uses), for callers outside generation. */
    public final boolean wooded(int x, int z) {
        int ground = surfaceY(x, z), water = waterY(x, z);
        if (water >= ground) return false;
        Surface surface = coverAt(x, z, ground, water);
        return surface.trees() > 0 && (surface.top() == GRASS || surface.top() == MUD || surface.top().is(Blocks.PODZOL));
    }

    @Override public final int waterY(int x, int z) {
        Column cached = columns.get(columnKey(x >> 4, z >> 4));
        if (cached != null) return cached.water[((z & 15) + 1) * 18 + (x & 15) + 1];
        return (int) groundOf(x, z);
    }

    // Far terrain: voxel columns sampled from the same height, cover and tree rules -------------

    private static final boolean SURFACE_PACKETS = Boolean.getBoolean("earthplatform.far.surfacePackets");

    @Override public void farColumn(int level, int columnX, int columnZ, FarColumn out) {
        farColumn(level, columnX, columnZ, out, SURFACE_PACKETS);
    }

    /** Explicit control for the dense oracle; never changes near cube generation. */
    final void farColumn(int level, int columnX, int columnZ, FarColumn out, boolean packets) {
        farColumn(level, columnX, columnZ, out, packets, true);
    }

    /** Bounded direct prototype: null requires the caller to use the existing dense path. */
    @Nullable public final FarSurfacePacket surfacePacket(int level, int columnX, int columnZ) {
        if (level < Far.MIN_LEVEL || level > Far.MAX_LEVEL) throw new IllegalArgumentException("Unsupported far level");
        FarColumn out = new FarColumn();
        farColumn(level, columnX, columnZ, out, true, false);
        return out.packet;
    }

    private void farColumn(int level, int columnX, int columnZ, FarColumn out, boolean packets, boolean materialize) {
        out.packet = null;
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
        boolean[] ice = new boolean[256];
        Strata.Layers[] rock = new Strata.Layers[256];
        int[] pours = new int[256];
        java.util.Arrays.fill(pours, Integer.MIN_VALUE);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
            int g = (cz + 1) * 18 + cx + 1, h = ground[g], cell = cz * 16 + cx;
            int wx = x0 + cx * v + half, wz = z0 + cz * v + half;
            int slope = (Math.abs(ground[g + 1] - ground[g - 1]) + Math.abs(ground[g + 18] - ground[g - 18])) / v;
            Surface surface = farSurface(level, wx, wz, h, water[g], slope);
            biome[cell] = surface.biome();
            top[cell] = surface.top();
            ice[cell] = water[g] > h && iced(surface, wx, wz);
            // Cold dry ground under the snow Vegetation lays on it nearer.
            if (water[g] <= h && Trees.snowy(surface) && !top[cell].is(Blocks.ICE) && !top[cell].is(Blocks.PACKED_ICE)
                    && !top[cell].is(Blocks.POWDER_SNOW)) top[cell] = SNOW_BLOCK;
            under[cell] = v > 2 && !bedrock(surface.under()) ? STONE : surface.under();
            rock[cell] = layers(wx, wz);
            // Voxels wider than a tree show forests as leaves: at 8 blocks a voxel above the ground, about a tree's
            // height, so they meet the rasterised trees nearer; wider voxels would stand too tall, so leaves lie on the ground voxel.
            if (v >= 8) {
                BlockState leaves = water[g] > h ? null : canopy(surface, wx, wz, v);
                if (leaves != null && v == 8) crown[cell] = leaves;
                else if (leaves != null) top[cell] = leaves;
            }
            min = Math.min(min, h);
            max = Math.max(max, Math.max(h, water[g]));
            // Where water steps down beside this column it pours over the edge here, as it does up close, rather
            // than leaving a dark slot under the higher water's lip.
            int beside = Math.max(Math.max(water[g - 1], water[g + 1]), Math.max(water[g - 18], water[g + 18]));
            if (beside != DRY && beside > Math.max(h, water[g])) {
                pours[cell] = Math.floorDiv(beside, v) * v;
                max = Math.max(max, beside);
            }
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
        // First bounded case: no rasterized trees or vertical strata. Every unsupported case
        // retains the original dense path, including all fine far levels and giant groves.
        if (packets && v >= 8 && !giants && java.util.Arrays.stream(rock).allMatch(java.util.Objects::isNull)) {
            int[] runOffsets = new int[257];
            long[] packedRuns = new long[1024];
            int totalRuns = 0;
            int rows = sections * 16;
            int[] cuts = new int[23], runEnds = new int[23];
            BlockState[] runStates = new BlockState[23];
            for (int cell = 0; cell < 256; cell++) {
                int g = ((cell >> 4) + 1) * 18 + (cell & 15) + 1;
                int count = 2; cuts[0] = 0; cuts[1] = rows;
                // All discrete state changes lie next to one of these exact integer thresholds.
                for (int k = 0; k < 3; k++) {
                    int threshold = k == 0 ? ground[g] : k == 1 ? water[g] : pours[cell];
                    long row = Math.floorDiv((long) threshold, v) - (long) bottom * 16;
                    for (int delta = -3; delta <= 3; delta++) cuts[count++] = (int) Math.clamp(row + delta, 0, rows);
                }
                java.util.Arrays.sort(cuts, 0, count);
                int from = 0, runCount = 0;
                for (int ci = 0; ci < count; ci++) {
                    int to = cuts[ci];
                    if (to <= from) continue;
                    int y0 = base + from * v;
                    BlockState state = voxel(ground[g], water[g], y0, v, top[cell], under[cell]);
                    if (state == WATER && ice[cell] && y0 + v > water[g]) state = Blocks.ICE.defaultBlockState();
                    if (ground[g] >= y0 + v && state != WATER && state == STONE && bedrock(under[cell])) state = under[cell];
                    if (state == AIR && crown[cell] != null && ground[g] < y0 && ground[g] >= y0 - v) state = crown[cell];
                    if (state == AIR && y0 <= pours[cell]) state = WATER;
                    if (runCount == 0 || runStates[runCount - 1] != state) runStates[runCount++] = state;
                    runEnds[runCount - 1] = to;
                    from = to;
                }
                if (totalRuns + runCount > packedRuns.length) packedRuns = java.util.Arrays.copyOf(packedRuns, packedRuns.length * 2);
                for (int i = 0; i < runCount; i++) packedRuns[totalRuns++] = (long) runEnds[i] << 32 | (Block.getId(runStates[i]) & 0xffffffffL);
                runOffsets[cell + 1] = totalRuns;
            }
            java.util.List<Holder<Biome>> quarts = new java.util.ArrayList<>(16);
            for (int qz = 0; qz < 4; qz++) for (int qx = 0; qx < 4; qx++) quarts.add(biome[(qz * 4 + 1) * 16 + qx * 4 + 1]);
            out.packet = FarSurfacePacket.generated(level, bottom, rows, out.drops, biome[8 * 16 + 8], quarts, runOffsets, packedRuns);
            // Legacy adapter preserves current wire/mesh/edit ordering until a direct consumer is validated.
            if (materialize) out.sections.addAll(out.packet.materialize(biomeIds));
            return;
        }
        if (!materialize) return; // Unsupported direct request: caller retains the dense fallback.
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
                    if (state == WATER && ice[cz * 16 + cx] && y0 + v > water[g]) state = Blocks.ICE.defaultBlockState();
                    if (ground[g] >= y0 + v && state != WATER) {
                        if (rock[cz * 16 + cx] != null) state = rock[cz * 16 + cx].at(y0 + (v >> 1));
                        else if (state == STONE && bedrock(under[cz * 16 + cx])) state = under[cz * 16 + cx];
                    }
                    if (state == AIR && crown[cz * 16 + cx] != null && ground[g] < y0 && ground[g] >= y0 - v) state = crown[cz * 16 + cx];
                    if (state == AIR && y0 <= pours[cz * 16 + cx]) state = WATER;
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
            // Crowns' top leaves under snow that covers a quarter of the voxel, lying in it or just above, show white;
            // the leaves below them stay green, as the trees' sides do nearer.
            boolean above = i + 256 < snowCount.length;
            int snow = snowCount[i] + (above ? snowCount[i + 256] : 0);
            boolean covered = above && (leafCount[i + 256] >= quarter || logCount[i + 256] >= quarter);
            if (state == leafKind[i] && !covered && snow > 0 && snow * 4 >= v * v) state = SNOW_BLOCK;
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
        // Thousandths covered, from the trees' attempts per 256 columns. Crowns standing a voxel tall (8 blocks) show
        // their shaded sides and hide the ground between them, so a crown covering about 20 columns looks as dense as
        // the trees drawn nearer. Leaves lying flat on wider voxels show neither, so they cover what a crown's 4-block
        // voxels fill nearer, about 80 columns, overlapping at random: 1 - e^-(trees per column x 80). Otherwise
        // forests turn paler and browner past the last standing crowns.
        int attempts = surface.trees() * Trees.full(kind);
        int covered = v == 8 ? attempts * 3 / 4 : (int) (1000 * (1 - Math.exp(-attempts / 25600.0 * 80)));
        if (Math.floorMod(r, 1000) >= Math.min(950, covered)) return null;
        // Where snow falls it lies on most crowns, as it does on the trees nearer.
        return Trees.snowy(surface) && Math.floorMod(r >>> 40, 3) != 0 ? SNOW_BLOCK : Vegetation.leaves(kind);
    }

    // Cubes --------------------------------------------------------------------------------------

    @Override public LevelChunkSection generate(int cx, int cy, int cz) { return generate(cx, cy, cz, new ArrayList<>(), new ArrayList<>()); }

    @Override public void attach(ServerLevel level) { vanilla = new VanillaStructures(level, new StructureHost(), seed); }

    @Override public LevelChunkSection generate(int cx, int cy, int cz, List<CompoundTag> blockEntities, List<CompoundTag> entities) {
        LevelChunkSection section = terrain(cx, cy, cz, blockEntities, entities);
        Vegetation.bees(section, cx << 4, cy << 4, cz << 4, blockEntities);
        VanillaStructures structures = vanilla;
        if (structures != null) {
            structures.place(section, cx, cy, cz, blockEntities, entities);
            section.recalcBlockCounts();
        }
        dropStrayBlockEntities(section, cx, cy, cz, blockEntities);
        return section;
    }

    /**
     * Drops block entity tags whose block a later step replaced, such as a dungeon chest carved
     * out by a neighbouring dungeon's room, so a cube's block entities always match its blocks.
     */
    private static void dropStrayBlockEntities(LevelChunkSection section, int cx, int cy, int cz, List<CompoundTag> blockEntities) {
        blockEntities.removeIf(tag -> {
            if (tag.getInt("x") >> 4 != cx || tag.getInt("y") >> 4 != cy || tag.getInt("z") >> 4 != cz) return false; // another cube's
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("id"));
            BlockEntityType<?> type = id == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
            BlockState state = section.getBlockState(tag.getInt("x") & 15, tag.getInt("y") & 15, tag.getInt("z") & 15);
            return type == null || !type.isValid(state);
        });
    }

    @Override public @Nullable net.minecraft.core.BlockPos locate(java.util.function.Predicate<net.minecraft.world.level.levelgen.structure.Structure> structure, int x, int z, int radius) {
        VanillaStructures structures = vanilla;
        return structures == null ? null : structures.locate(structure, x, z, radius);
    }

    @Override public List<net.minecraft.world.level.levelgen.structure.StructureStart> structureStarts(int chunkX, int chunkZ) {
        VanillaStructures structures = vanilla;
        return structures == null ? List.of() : structures.startsAt(chunkX, chunkZ);
    }

    @Override public @Nullable net.minecraft.world.level.levelgen.structure.StructureStart structureStart(net.minecraft.world.level.levelgen.structure.Structure structure, int chunkX, int chunkZ) {
        VanillaStructures structures = vanilla;
        return structures == null ? null : structures.startOf(structure, chunkX, chunkZ);
    }

    @Override public @Nullable net.minecraft.world.level.levelgen.structure.StructureStart placedStructure(net.minecraft.world.level.levelgen.structure.Structure structure, int chunkX, int chunkZ) {
        VanillaStructures structures = vanilla;
        return structures == null ? null : structures.placed(structure, new net.minecraft.world.level.ChunkPos(chunkX, chunkZ));
    }

    @Override public @Nullable net.minecraft.world.level.chunk.ChunkGenerator structureView() {
        VanillaStructures structures = vanilla;
        return structures == null ? null : structures.view();
    }

    /** Whether a structure clears the ground at a column around its buildings, so no tree grows there. */
    boolean cleared(int x, int z) {
        VanillaStructures structures = vanilla;
        return structures != null && structures.clears(x, z);
    }

    @Override public String structuresAt(int x, int y, int z) {
        VanillaStructures structures = vanilla;
        return structures == null ? "" : structures.describe(x, y, z);
    }

    @Override public @Nullable com.mojang.datafixers.util.Pair<net.minecraft.core.BlockPos, Holder<Biome>> findBiome(
            java.util.function.Predicate<Holder<Biome>> biomes, net.minecraft.core.BlockPos from, int radius, int step) {
        int[] sample = new int[2];
        // Searching the caves costs the most; skip it when the biome sought is none of theirs.
        boolean caves = false;
        for (Underground.Kind kind : Underground.Kind.values()) {
            Holder<Biome> cave = below.biome(kind);
            caves |= cave != null && biomes.test(cave);
        }
        for (net.minecraft.core.BlockPos.MutableBlockPos at : net.minecraft.core.BlockPos.spiralAround(net.minecraft.core.BlockPos.ZERO,
                Math.floorDiv(radius, step), net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH)) {
            int x = from.getX() + at.getX() * step, z = from.getZ() + at.getZ() * step;
            ground(x, z, sample);
            int ground = sample[0], water = sample[1];
            Holder<Biome> top = coverAt(x, z, ground, water).biome();
            if (biomes.test(top)) return com.mojang.datafixers.util.Pair.of(new net.minecraft.core.BlockPos(x, Math.max(ground, water) + 1, z), top);
            // Lush and dripstone caves and the deep dark, every 32 blocks down to vanilla's Y -64 under the local floor.
            if (!caves) continue;
            int floor = below.floor(x, z);
            for (int y = ground - 32; y >= floor - 128; y -= 32) {
                Holder<Biome> cave = below.biome(below.kind(x >> 4, y >> 4, z >> 4));
                if (cave != null && biomes.test(cave)) return com.mojang.datafixers.util.Pair.of(new net.minecraft.core.BlockPos(x, y, z), cave);
            }
        }
        return null;
    }

    /** The biome vanilla code sees at a block: the cave biome deep under the ground, else the column's. */
    Holder<Biome> biomeAt(int x, int y, int z) {
        Column col = column(x >> 4, z >> 4);
        int cy = y >> 4;
        if ((cy << 4) + 15 < col.min - 6) {
            Holder<Biome> cave = below.biome(below.kind(x >> 4, cy, z >> 4));
            if (cave != null) return cave;
        }
        return col.surface[(z & 15) * 16 + (x & 15)].biome();
    }

    /**
     * The biome at a block worked out from that spot alone, without building and caching its whole
     * column, for biome searches (/locate biome, biome compasses) that sample far and wide.
     */
    @Override public Holder<Biome> biome(int x, int y, int z) {
        int[] sample = new int[2];
        ground(x + 1, z, sample); int east = sample[0];
        ground(x - 1, z, sample); int west = sample[0];
        ground(x, z + 1, sample); int south = sample[0];
        ground(x, z - 1, sample); int north = sample[0];
        ground(x, z, sample);
        int cy = y >> 4;
        if ((cy << 4) + 15 < Math.min(Math.min(east, west), Math.min(Math.min(south, north), sample[0])) - 6) {
            Holder<Biome> cave = below.biome(below.kind(x >> 4, cy, z >> 4));
            if (cave != null) return cave;
        }
        return surface(x, z, sample[0], sample[1], Math.abs(east - west) + Math.abs(south - north)).biome();
    }

    /** What vanilla structure code reads of this world: the terrain before structures. */
    private final class StructureHost implements VanillaStructures.Host {
        @Override public int ground(int x, int z) { return surfaceY(x, z); }
        @Override public int water(int x, int z) { return waterY(x, z); }
        @Override public Holder<Biome> biome(int x, int y, int z) { return biomeAt(x, y, z); }
        @Override public int floor(int x, int z) { return below.floor(x, z); }
        @Override public int seaLevel() { return SurfaceGenerator.this.seaLevel() + 1; }
        @Override public java.util.stream.Stream<Holder<Biome>> possibleBiomes() {
            return biomeRegistry.holders().filter(h -> h.is(BiomeTags.IS_OVERWORLD)).map(h -> (Holder<Biome>) h);
        }
        @Override public BlockState fill(int x, int y, int z, int depth) {
            Surface surface = column(x >> 4, z >> 4).surface[(z & 15) * 16 + (x & 15)];
            return depth <= 0 ? surface.top() : depth < 4 ? surface.under() : below.rock(x, y, z, below.floor(x, z));
        }
        @Nullable @Override public LevelChunkSection cube(int cx, int cy, int cz) {
            Column col = column(cx, cz);
            if (cy << 4 > Math.max(col.max + 16, col.maxWater)) return null;
            long key = SectionPos.asLong(cx, cy, cz);
            LevelChunkSection cached = bases.get(key);
            if (cached != null) return cached;
            LevelChunkSection made = terrain(cx, cy, cz, new ArrayList<>(), new ArrayList<>());
            if (bases.size() > 4096) bases.clear();
            bases.putIfAbsent(key, made);
            return made;
        }
    }

    /** A cube as generated before vanilla structures: terrain, caves, ores, plants and this generator's own structures. */
    private LevelChunkSection terrain(int cx, int cy, int cz, List<CompoundTag> blockEntities, List<CompoundTag> entities) {
        Column col = column(cx, cz);
        Holder<Biome> fallback = col.surface[8 * 16 + 8].biome();
        int y0 = cy << 4, x0 = cx << 4, z0 = cz << 4;
        boolean underground = y0 + 15 < col.min - 6;
        Underground.Kind caves = underground ? below.kind(cx, cy, cz) : null;
        VanillaStructures structures = vanilla;
        if (caves != null && structures != null) {
            Holder<Biome> sole = structures.soleBiome(cx, cy, cz);
            for (Underground.Kind kind : Underground.Kind.values()) if (sole != null && sole.equals(below.biome(kind))) caves = kind;
        }
        Holder<Biome> caveBiome = caves == null ? null : below.biome(caves);
        LevelChunkSection section = new LevelChunkSection(
                new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES),
                caveBiome != null ? new PalettedContainer<>(biomeIds, caveBiome, PalettedContainer.Strategy.SECTION_BIOMES) : biomes(col, fallback));
        // Empty sky cubes: nothing generated, not even trees, above the tallest possible tree.
        if (y0 > Math.max(col.max + vegetation.tallest(cx, cz), col.maxWater)) { section.recalcBlockCounts(); return section; }
        double[] caveField = y0 > col.max ? null : below.caveField(x0, y0, z0);
        int[] floor = new int[256], roof = new int[256];
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int ci = lz * 16 + lx, gi = (lz + 1) * 18 + lx + 1, h = col.height[gi];
            floor[ci] = below.floor(x0 + lx, z0 + lz, h);
            int slope = Math.abs(col.height[gi + 1] - col.height[gi - 1]) + Math.abs(col.height[gi + 18] - col.height[gi - 18]);
            roof[ci] = below.caveRoof(x0 + lx, z0 + lz, h, col.water[gi], slope);
        }
        for (int ly = 0; ly < 16; ly++) {
            int y = y0 + ly;
            for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int ci = lz * 16 + lx, gi = (lz + 1) * 18 + lx + 1, h = col.height[gi];
                Surface surface = col.surface[ci];
                BlockState state;
                if (y > h) state = y <= col.water[gi] ? WATER : falls(col.water, gi, y) ? FALLING_WATER : AIR;
                else {
                    int depth = h - y;
                    if (depth == 0) state = surface.top();
                    else if (depth < 4) state = surface.under();
                    else state = below.rock(x0 + lx, y, z0 + lz, floor[ci]);
                    Strata.Layers rock = col.layers[ci];
                    if (rock != null && rock.shows(depth, col.height, gi)) state = rock.at(y);
                    else if (rock == null && depth >= 4 && bedrock(surface.under()) && Strata.Layers.face(depth, col.height, gi)) state = surface.under();
                    // Cave mouths open the top blocks only where nothing grows on them.
                    boolean open = depth >= roof[ci] && (depth > 1 || surface.trees() == 0 && (Underground.rock(surface.top()) || surface.top() == GRAVEL));
                    if (open && state != Underground.BEDROCK && Underground.cave(caveField, lx, ly, lz)) state = Underground.cavity(y, floor[ci]);
                }
                if (state != AIR) section.setBlockState(lx, ly, lz, state, false);
            }
        }
        if (!underground) vegetation.decorate(section, x0, y0, z0, col.height, col.water, col.surface);
        if (y0 <= col.max) below.finish(section, cx, cy, cz, caves, blockEntities, entities);
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

    protected final long mix(long x, long y, long z) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return h;
    }
}
