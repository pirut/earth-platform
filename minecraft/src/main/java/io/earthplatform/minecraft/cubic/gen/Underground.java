package io.earthplatform.minecraft.cubic.gen;

import io.earthplatform.minecraft.cubic.CubeWorld;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/**
 * Everything under the ground of a {@link SurfaceGenerator} world: rock layers, caves, lava,
 * ore and rock veins, cave plants and cave biomes, plus the natural structures in
 * {@link Structures}.
 *
 * <p>Vanilla lays these out by absolute height under a world whose plains sit near Y 64. Here
 * the ground can stand anywhere from the deep seabed to Everest, so the layers hang from a
 * <em>floor</em> instead: the lowest ground around a column, smoothed over a few hundred blocks.
 * A block's vanilla-equivalent height is {@code y - floor + 64}, so deepslate starts 64 blocks
 * under a valley or seabed, diamonds and lava lie about 120 under it, and a mountain is solid
 * rock above its valleys with iron, coal and emeralds as vanilla's mountains have. Below the
 * depth where vanilla's world ends, the bottom layers carry on down to bedrock at the true
 * bottom of the world.
 */
final class Underground {
    static final BlockState AIR = Blocks.AIR.defaultBlockState(), STONE = Blocks.STONE.defaultBlockState(),
            DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState(), LAVA = Blocks.LAVA.defaultBlockState(),
            BEDROCK = Blocks.BEDROCK.defaultBlockState(), GRAVEL = Blocks.GRAVEL.defaultBlockState();

    /** Vanilla heights where deepslate starts to mix in and where caves below fill with lava. */
    static final int DEEPSLATE_TOP = 8, LAVA_TOP = -54;
    /** Ore and rock odds below vanilla's bottom stay as they are at its bottom, and above its mountains as at their tops. */
    private static final int ORE_FLOOR = -60, ORE_CEILING = 320;

    private final SurfaceGenerator terrain;
    private final long seed;
    private final ImprovedNoise tunnelA, tunnelB, cavern, lavaNoise, noodleA, noodleB, noodleRegion, entrance, lush, dripstone, deepDark, veinToggle, veinA, veinB;
    private final Holder<Biome> lushCaves, dripstoneCaves, deepDarkCaves;
    private final ConcurrentHashMap<Long, Integer> floorNodes = new ConcurrentHashMap<>();
    final Structures structures;

    /**
     * The first four noises come from the world's shared random in the order caves always drew
     * them, so tunnels and caverns stay where they were; the rest come from their own random.
     */
    Underground(SurfaceGenerator terrain, long seed, Registry<Biome> biomes, RandomSource shared) {
        this.terrain = terrain;
        this.seed = seed;
        tunnelA = new ImprovedNoise(shared); tunnelB = new ImprovedNoise(shared); cavern = new ImprovedNoise(shared);
        lavaNoise = new ImprovedNoise(shared);
        RandomSource own = new LegacyRandomSource(seed ^ 0x554E44455247524FL);
        noodleA = new ImprovedNoise(own); noodleB = new ImprovedNoise(own); noodleRegion = new ImprovedNoise(own);
        entrance = new ImprovedNoise(own); lush = new ImprovedNoise(own); dripstone = new ImprovedNoise(own);
        deepDark = new ImprovedNoise(own);
        veinToggle = new ImprovedNoise(own); veinA = new ImprovedNoise(own); veinB = new ImprovedNoise(own);
        lushCaves = biomes.getHolderOrThrow(Biomes.LUSH_CAVES);
        dripstoneCaves = biomes.getHolderOrThrow(Biomes.DRIPSTONE_CAVES);
        deepDarkCaves = biomes.getHolderOrThrow(Biomes.DEEP_DARK);
        structures = new Structures(this, seed);
    }

    // Floor ---------------------------------------------------------------------------------------

    private static final int NODE = 128, REACH = 128, STEP = 64;

    /** The smoothed lowest ground around a column, from which the layers hang. Never above the column's own ground. */
    int floor(int x, int z, int ground) {
        int nx = Math.floorDiv(x, NODE), nz = Math.floorDiv(z, NODE);
        double tx = (x - nx * NODE) / (double) NODE, tz = (z - nz * NODE) / (double) NODE;
        double f = Mth.lerp2(tx, tz, node(nx, nz), node(nx + 1, nz), node(nx, nz + 1), node(nx + 1, nz + 1));
        return Math.min(ground, Mth.floor(f));
    }
    int floor(int x, int z) { return floor(x, z, terrain.surfaceY(x, z)); }

    private int node(int nx, int nz) {
        long key = ((long) nx << 32) | (nz & 0xFFFFFFFFL);
        Integer cached = floorNodes.get(key);
        if (cached != null) return cached;
        int x = nx * NODE, z = nz * NODE, low = Integer.MAX_VALUE;
        for (int dz = -REACH; dz <= REACH; dz += STEP) for (int dx = -REACH; dx <= REACH; dx += STEP) low = Math.min(low, terrain.surfaceY(x + dx, z + dz));
        if (floorNodes.size() > 65536) floorNodes.clear();
        floorNodes.putIfAbsent(key, low);
        return low;
    }

    /** Vanilla's height for a block {@code y} over a column floor. */
    static int vanillaY(int y, int floor) { return y - floor + 64; }

    // Rock and caves -----------------------------------------------------------------------------

    /** Stone, deepslate or bedrock for solid rock at a height, before veins. */
    BlockState rock(int x, int y, int z, int floor) {
        if (y <= CubeWorld.MIN_Y + 4 && y - CubeWorld.MIN_Y < 1 + Math.floorMod(mix(x, y, z), 5)) return BEDROCK;
        int vy = vanillaY(y, floor);
        BlockState vein = largeVein(x, y, z, vy);
        if (vein != null) return vein;
        if (vy < 0) return DEEPSLATE;
        if (vy >= DEEPSLATE_TOP) return STONE;
        // Vanilla's blend: the chance of deepslate falls from all to none over eight blocks.
        return Math.floorMod(mix(x, y, z) >>> 7, DEEPSLATE_TOP) >= vy ? DEEPSLATE : STONE;
    }

    private static final BlockState GRANITE = Blocks.GRANITE.defaultBlockState(), TUFF = Blocks.TUFF.defaultBlockState(),
            COPPER_ORE = Blocks.COPPER_ORE.defaultBlockState(), DEEP_IRON_ORE = Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
            RAW_COPPER = Blocks.RAW_COPPER_BLOCK.defaultBlockState(), RAW_IRON = Blocks.RAW_IRON_BLOCK.defaultBlockState();

    /**
     * Vanilla's large ore veins: long winding bands of granite with copper (vanilla Y 0 to 50) or
     * tuff with iron (-60 to -8), with the odd raw metal block. Same thresholds as vanilla's
     * veinifier, fading out over 20 blocks at either end of the range.
     */
    @Nullable private BlockState largeVein(int x, int y, int z, int vy) {
        if (vy < -60 || vy > 50 || vy > -8 && vy < 0) return null;
        boolean copper = vy >= 0;
        double toggle = veinToggle.noise(x / 96.0, y / 96.0, z / 96.0);
        if (copper != toggle > 0) return null;
        int edge = copper ? Math.min(vy, 50 - vy) : Math.min(vy + 60, -8 - vy);
        double strength = Math.abs(toggle) + Mth.clampedMap(edge, 0, 20, -0.2, 0);
        if (strength < 0.4) return null;
        long r = mix(x, y ^ 0x5645494EL, z);
        if ((r >>> 11 & 0xFFFF) > 0.7 * 65536) return null;
        if (Math.max(Math.abs(veinA.noise(x / 26.0, y / 26.0, z / 26.0)), Math.abs(veinB.noise(x / 26.0, y / 26.0, z / 26.0))) >= 0.08) return null;
        if ((r >>> 27 & 0xFFFF) < Mth.clampedMap(Math.abs(toggle), 0.4, 0.6, 0.1, 0.3) * 65536)
            return (r >>> 43 & 0xFF) < 5 ? (copper ? RAW_COPPER : RAW_IRON) : copper ? COPPER_ORE : DEEP_IRON_ORE;
        return copper ? GRANITE : TUFF;
    }

    /** What fills a cave at a height: air, or lava at the vanilla depth where lava lies. */
    static BlockState cavity(int y, int floor) { return vanillaY(y, floor) < LAVA_TOP ? LAVA : AIR; }

    /** The cave noise at a grid node: tunnels, noodles and caverns. Nodes sit every 4 blocks. */
    private double caveNode(int x, int y, int z) {
        double a = tunnelA.noise(x / 56.0, y / 34.0, z / 56.0), b = tunnelB.noise(x / 56.0, y / 34.0, z / 56.0);
        double tunnel = (0.018 - (a * a + b * b)) * 12;
        double big = cavern.noise(x / 110.0, y / 60.0, z / 110.0) - 0.42;
        double value = Math.max(tunnel, big);
        // Thin winding noodle caves, in some regions only.
        if (noodleRegion.noise(x / 300.0, y / 200.0, z / 300.0) > 0.05) {
            double c = noodleA.noise(x / 22.0, y / 18.0, z / 22.0), d = noodleB.noise(x / 22.0, y / 18.0, z / 22.0);
            value = Math.max(value, (0.0035 - (c * c + d * d)) * 40);
        }
        return value;
    }

    /** Cave noise on the 4-block grid of one cube, 5³ nodes. */
    double[] caveField(int x0, int y0, int z0) {
        double[] field = new double[5 * 5 * 5];
        for (int iy = 0; iy < 5; iy++) for (int iz = 0; iz < 5; iz++) for (int ix = 0; ix < 5; ix++)
            field[(iy * 5 + iz) * 5 + ix] = caveNode(x0 + ix * 4, y0 + iy * 4, z0 + iz * 4);
        return field;
    }

    static boolean cave(double[] f, int lx, int ly, int lz) {
        int ix = lx >> 2, iy = ly >> 2, iz = lz >> 2;
        double tx = (lx & 3) / 4.0, ty = (ly & 3) / 4.0, tz = (lz & 3) / 4.0;
        double c000 = f[(iy * 5 + iz) * 5 + ix], c100 = f[(iy * 5 + iz) * 5 + ix + 1];
        double c010 = f[((iy + 1) * 5 + iz) * 5 + ix], c110 = f[((iy + 1) * 5 + iz) * 5 + ix + 1];
        double c001 = f[(iy * 5 + iz + 1) * 5 + ix], c101 = f[(iy * 5 + iz + 1) * 5 + ix + 1];
        double c011 = f[((iy + 1) * 5 + iz + 1) * 5 + ix], c111 = f[((iy + 1) * 5 + iz + 1) * 5 + ix + 1];
        return Mth.lerp3(tx, ty, tz, c000, c100, c010, c110, c001, c101, c011, c111) > 0;
    }

    /** The same test at one block, exactly as a cube's field gives it. */
    private boolean cave(int x, int y, int z) {
        int gx = x & ~3, gy = y & ~3, gz = z & ~3;
        double tx = (x - gx) / 4.0, ty = (y - gy) / 4.0, tz = (z - gz) / 4.0;
        return Mth.lerp3(tx, ty, tz, caveNode(gx, gy, gz), caveNode(gx + 4, gy, gz), caveNode(gx, gy + 4, gz), caveNode(gx + 4, gy + 4, gz),
                caveNode(gx, gy, gz + 4), caveNode(gx + 4, gy, gz + 4), caveNode(gx, gy + 4, gz + 4), caveNode(gx + 4, gy + 4, gz + 4)) > 0;
    }

    /**
     * How deep under its ground a column's caves may reach up to. Caves stay well under seabeds
     * and shores so water stays put, and a few blocks under other ground, except for cave mouths
     * opening on steep rocky slopes.
     */
    int caveRoof(int x, int z, int ground, int water, int slope) {
        if (ground <= terrain.shoreLevel() + 2 || water != SurfaceGenerator.DRY) return 15;
        if (slope >= 8 && entrance.noise(x / 90.0, 0, z / 90.0) > 0.25) return 0;
        return 6;
    }

    /** How many blocks of ground structures leave over themselves: more under water, so seabeds and lakes hold. */
    int lid(int x, int z) {
        int ground = terrain.surfaceY(x, z);
        return ground <= terrain.shoreLevel() + 2 || terrain.waterY(x, z) != SurfaceGenerator.DRY ? 16 : 8;
    }

    /** Whether a block is solid ground as generated, before veins, plants and structures. */
    boolean solid(int x, int y, int z) {
        int ground = terrain.surfaceY(x, z);
        if (y > ground) return false;
        int depth = ground - y;
        if (depth < caveRoofAt(x, z, ground)) return true;
        return !cave(x, y, z);
    }
    private int caveRoofAt(int x, int z, int ground) {
        int slope = Math.abs(terrain.surfaceY(x + 1, z) - terrain.surfaceY(x - 1, z)) + Math.abs(terrain.surfaceY(x, z + 1) - terrain.surfaceY(x, z - 1));
        return caveRoof(x, z, ground, terrain.waterY(x, z), slope);
    }

    /** Depth of a block under its column's ground. */
    int depth(int x, int y, int z) { return terrain.surfaceY(x, z) - y; }

    // Cave kinds and biomes ----------------------------------------------------------------------

    enum Kind { PLAIN, LUSH, DRIPSTONE, DEEP_DARK }

    /** The kind of caves in a cube lying wholly under the ground, chosen by broad 3D regions as vanilla's cave biomes are. */
    Kind kind(int cx, int cy, int cz) {
        int x = (cx << 4) + 8, y = (cy << 4) + 8, z = (cz << 4) + 8;
        int vy = vanillaY(y, floor(x, z));
        if (vy > 100) return Kind.PLAIN;
        if (vy < -4 && deepDark.noise(x / 240.0, y / 120.0, z / 240.0) > 0.22) return Kind.DEEP_DARK;
        if (vy > -40 && lush.noise(x / 280.0, y / 140.0, z / 280.0) > 0.32) return Kind.LUSH;
        if (dripstone.noise(x / 300.0, y / 150.0, z / 300.0) > 0.32) return Kind.DRIPSTONE;
        return Kind.PLAIN;
    }

    @Nullable Holder<Biome> biome(Kind kind) {
        return switch (kind) { case LUSH -> lushCaves; case DRIPSTONE -> dripstoneCaves; case DEEP_DARK -> deepDarkCaves; case PLAIN -> null; };
    }

    // After the rock is laid: veins, cave plants, structures -------------------------------------

    /**
     * Adds a cube's rock and ore veins, cave plants and structures. {@code kind} is the cube's
     * cave kind, or null when it holds the surface.
     */
    void finish(LevelChunkSection section, int cx, int cy, int cz, @Nullable Kind kind,
                List<CompoundTag> blockEntities, List<CompoundTag> entities) {
        veins(section, cx, cy, cz);
        if (kind != null) plants(section, cx, cy, cz, kind);
        structures.place(section, cx, cy, cz, blockEntities, entities);
    }

    // Veins --------------------------------------------------------------------------------------

    private enum Spread { UNIFORM, TRIANGLE }
    private enum Replace { ORE, ROCK }

    /** A vanilla ore or rock feature: tries per chunk column over a vanilla height range. */
    private record Vein(Block stone, @Nullable Block deep, double perChunk, int size, Spread spread, int low, int high,
                        Replace replace, boolean mountains) {}

    private static final List<Vein> ROCK_VEINS = List.of(
            new Vein(Blocks.DIRT, null, 7, 33, Spread.UNIFORM, 0, 160, Replace.ROCK, false),
            new Vein(Blocks.GRAVEL, null, 14, 33, Spread.UNIFORM, -64, 320, Replace.ROCK, false),
            new Vein(Blocks.GRANITE, null, 1 / 6.0, 64, Spread.UNIFORM, 64, 128, Replace.ROCK, false),
            new Vein(Blocks.GRANITE, null, 2, 64, Spread.UNIFORM, 0, 60, Replace.ROCK, false),
            new Vein(Blocks.DIORITE, null, 1 / 6.0, 64, Spread.UNIFORM, 64, 128, Replace.ROCK, false),
            new Vein(Blocks.DIORITE, null, 2, 64, Spread.UNIFORM, 0, 60, Replace.ROCK, false),
            new Vein(Blocks.ANDESITE, null, 1 / 6.0, 64, Spread.UNIFORM, 64, 128, Replace.ROCK, false),
            new Vein(Blocks.ANDESITE, null, 2, 64, Spread.UNIFORM, 0, 60, Replace.ROCK, false),
            new Vein(Blocks.TUFF, null, 2, 64, Spread.UNIFORM, -64, 0, Replace.ROCK, false));

    private static final List<Vein> ORE_VEINS = List.of(
            new Vein(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 30, 17, Spread.UNIFORM, 136, 320, Replace.ORE, false),
            new Vein(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 20, 17, Spread.TRIANGLE, 0, 192, Replace.ORE, false),
            new Vein(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 90, 9, Spread.TRIANGLE, 80, 384, Replace.ORE, false),
            new Vein(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 10, 9, Spread.TRIANGLE, -24, 56, Replace.ORE, false),
            new Vein(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 10, 4, Spread.UNIFORM, -64, 72, Replace.ORE, false),
            new Vein(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 16, 10, Spread.TRIANGLE, -16, 112, Replace.ORE, false),
            new Vein(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 4, 9, Spread.TRIANGLE, -64, 32, Replace.ORE, false),
            new Vein(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 0.5, 9, Spread.UNIFORM, -64, -48, Replace.ORE, false),
            new Vein(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 4, 8, Spread.UNIFORM, -64, 15, Replace.ORE, false),
            new Vein(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 8, 8, Spread.TRIANGLE, -96, -32, Replace.ORE, false),
            new Vein(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 2, 7, Spread.TRIANGLE, -32, 32, Replace.ORE, false),
            new Vein(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 4, 7, Spread.UNIFORM, -64, 64, Replace.ORE, false),
            new Vein(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 7, 4, Spread.TRIANGLE, -144, 16, Replace.ORE, false),
            new Vein(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 4, 8, Spread.TRIANGLE, -144, 16, Replace.ORE, false),
            new Vein(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 1 / 9.0, 12, Spread.TRIANGLE, -144, 16, Replace.ORE, false),
            new Vein(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 100, 3, Spread.TRIANGLE, -16, 480, Replace.ORE, true));

    /** Vanilla spreads its tries over a 384-block chunk column; this is the share falling in one 16-block layer. */
    private static double share(Vein vein, int vy) {
        int y = Mth.clamp(vy, ORE_FLOOR, ORE_CEILING);
        if (y < vein.low || y > vein.high) return 0;
        double span = vein.high - vein.low + 1;
        if (vein.spread == Spread.UNIFORM) return 16 / span;
        double half = span / 2, mid = vein.low + half;
        return 16 * (1 - Math.abs(y - mid) / half) / half;
    }

    /**
     * Veins of every cube around this one, clipped to it, so veins cross cube faces whole. Each
     * cube's tries come from its own seed, so whichever cube asks gets the same veins.
     */
    private void veins(LevelChunkSection section, int cx, int cy, int cz) {
        for (List<Vein> pass : List.of(ROCK_VEINS, ORE_VEINS)) {
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                int nx = cx + dx, ny = cy + dy, nz = cz + dz;
                int x = (nx << 4) + 8, z = (nz << 4) + 8, ground = terrain.surfaceY(x, z);
                // Veins lie in rock: none from cubes wholly above their ground.
                if ((ny << 4) > ground + 16) continue;
                int vy = vanillaY((ny << 4) + 8, floor(x, z, ground));
                boolean mountains = terrain.emeralds(ground);
                RandomSource random = new LegacyRandomSource(seed ^ mix(nx, ny, nz) ^ (pass == ORE_VEINS ? 0x4F5245L : 0x524F434BL));
                for (Vein vein : pass) {
                    double expected = vein.mountains && !mountains ? 0 : vein.perChunk * share(vein, vy);
                    int tries = (int) expected;
                    if (random.nextDouble() < expected - tries) tries++;
                    for (int t = 0; t < tries; t++) {
                        blob(section, cx, cy, cz, vein, random,
                                (nx << 4) + random.nextInt(16), (ny << 4) + random.nextInt(16), (nz << 4) + random.nextInt(16));
                    }
                }
            }
        }
    }

    /** Vanilla's ore blob: spheres strung along a short random line, swelling in the middle. */
    private static void blob(LevelChunkSection section, int cx, int cy, int cz, Vein vein, RandomSource random, int x, int y, int z) {
        int size = vein.size;
        float angle = random.nextFloat() * Mth.PI, reach = size / 8.0F;
        double x0 = x + Mth.sin(angle) * reach, x1 = x - Mth.sin(angle) * reach;
        double z0 = z + Mth.cos(angle) * reach, z1 = z - Mth.cos(angle) * reach;
        double y0 = y + random.nextInt(3) - 2, y1 = y + random.nextInt(3) - 2;
        int bx = cx << 4, by = cy << 4, bz = cz << 4;
        for (int i = 0; i < size; i++) {
            float t = i / (float) size;
            double px = Mth.lerp(t, x0, x1), py = Mth.lerp(t, y0, y1), pz = Mth.lerp(t, z0, z1);
            double spread = random.nextDouble() * size / 16.0;
            double r = ((Mth.sin(Mth.PI * t) + 1) * spread + 1) / 2.0;
            int minX = Math.max(bx, Mth.floor(px - r)), maxX = Math.min(bx + 15, Mth.floor(px + r));
            int minY = Math.max(by, Mth.floor(py - r)), maxY = Math.min(by + 15, Mth.floor(py + r));
            int minZ = Math.max(bz, Mth.floor(pz - r)), maxZ = Math.min(bz + 15, Mth.floor(pz + r));
            for (int qy = minY; qy <= maxY; qy++) for (int qz = minZ; qz <= maxZ; qz++) for (int qx = minX; qx <= maxX; qx++) {
                double ex = (qx + 0.5 - px) / r, ey = (qy + 0.5 - py) / r, ez = (qz + 0.5 - pz) / r;
                if (ex * ex + ey * ey + ez * ez >= 1) continue;
                BlockState here = section.getBlockState(qx & 15, qy & 15, qz & 15);
                BlockState next = replaced(vein, here);
                if (next != null) section.setBlockState(qx & 15, qy & 15, qz & 15, next, false);
            }
        }
    }

    @Nullable private static BlockState replaced(Vein vein, BlockState here) {
        if (vein.replace == Replace.ROCK) return rock(here) ? vein.stone.defaultBlockState() : null;
        if (here.is(Blocks.STONE) || here.is(Blocks.GRANITE) || here.is(Blocks.DIORITE) || here.is(Blocks.ANDESITE)) return vein.stone.defaultBlockState();
        if (here.is(Blocks.DEEPSLATE) || here.is(Blocks.TUFF)) return vein.deep.defaultBlockState();
        return null;
    }

    // Cave plants --------------------------------------------------------------------------------

    private static final BlockState LICHEN_UP = Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.UP), true),
            LICHEN_DOWN = Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true),
            DRIPSTONE_BLOCK = Blocks.DRIPSTONE_BLOCK.defaultBlockState(), MOSS = Blocks.MOSS_BLOCK.defaultBlockState(),
            CLAY = Blocks.CLAY.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState(), SCULK = Blocks.SCULK.defaultBlockState(),
            SHRIEKER = Blocks.SCULK_SHRIEKER.defaultBlockState().setValue(net.minecraft.world.level.block.SculkShriekerBlock.CAN_SUMMON, true),
            VEIN_UP = Blocks.SCULK_VEIN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.UP), true),
            VEIN_DOWN = Blocks.SCULK_VEIN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);

    /**
     * Glow lichen in any cave; dripstone, or moss with azaleas, vines and pools, in their
     * regions. Plants need the block beside them inside this cube, so the cube's outer layers
     * above and below go bare.
     */
    private void plants(LevelChunkSection section, int cx, int cy, int cz, Kind kind) {
        RandomSource random = new LegacyRandomSource(seed ^ mix(cx, cy, cz) ^ 0x504C414E54L);
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        for (int ly = 1; ly < 15; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            BlockState here = section.getBlockState(lx, ly, lz);
            if (!here.isAir()) continue;
            BlockState below = section.getBlockState(lx, ly - 1, lz), above = section.getBlockState(lx, ly + 1, lz);
            boolean floor = below.isSolid(), ceiling = above.isSolid();
            if (!floor && !ceiling) continue;
            int roll = random.nextInt(1000);
            switch (kind) {
                case PLAIN -> {
                    if (ceiling && roll < 20) section.setBlockState(lx, ly, lz, LICHEN_UP, false);
                    else if (floor && roll < 26) section.setBlockState(lx, ly, lz, LICHEN_DOWN, false);
                }
                case DRIPSTONE -> {
                    if (floor && stone(below)) section.setBlockState(lx, ly - 1, lz, DRIPSTONE_BLOCK, false);
                    if (ceiling && stone(above)) section.setBlockState(lx, ly + 1, lz, DRIPSTONE_BLOCK, false);
                    if (floor && roll < 70) spike(section, lx, ly, lz, Direction.UP, 1 + random.nextInt(4));
                    else if (ceiling && roll < 150) spike(section, lx, ly, lz, Direction.DOWN, 1 + random.nextInt(5));
                }
                case DEEP_DARK -> {
                    // Sculk in patches a few blocks across, with sensors, shriekers and the odd catalyst on it.
                    boolean patch = Math.floorMod(mix(x0 + lx >> 2, y0 + ly >> 2, z0 + lz >> 2), 100) < 60;
                    if (floor && stone(below) && patch) {
                        section.setBlockState(lx, ly - 1, lz, SCULK, false);
                        if (roll < 12) section.setBlockState(lx, ly, lz, Blocks.SCULK_SENSOR.defaultBlockState(), false);
                        else if (roll < 16) section.setBlockState(lx, ly, lz, SHRIEKER, false);
                        else if (roll < 18) section.setBlockState(lx, ly - 1, lz, Blocks.SCULK_CATALYST.defaultBlockState(), false);
                        else if (roll < 120) section.setBlockState(lx, ly, lz, VEIN_DOWN, false);
                    } else if (ceiling && stone(above) && patch) {
                        section.setBlockState(lx, ly + 1, lz, SCULK, false);
                    } else if (ceiling && roll < 60) section.setBlockState(lx, ly, lz, VEIN_UP, false);
                    else if (floor && roll < 60) section.setBlockState(lx, ly, lz, VEIN_DOWN, false);
                }
                case LUSH -> {
                    if (floor && stone(below)) {
                        section.setBlockState(lx, ly - 1, lz, roll < 30 ? CLAY : MOSS, false);
                        if (roll < 30 && lx > 0 && lx < 15 && lz > 0 && lz < 15 && enclosed(section, lx, ly - 1, lz)) section.setBlockState(lx, ly - 1, lz, WATER, false);
                        else if (roll < 330) section.setBlockState(lx, ly, lz, Blocks.MOSS_CARPET.defaultBlockState(), false);
                        else if (roll < 450) section.setBlockState(lx, ly, lz, Blocks.SHORT_GRASS.defaultBlockState(), false);
                        else if (roll < 490) section.setBlockState(lx, ly, lz, Blocks.AZALEA.defaultBlockState(), false);
                        else if (roll < 510) section.setBlockState(lx, ly, lz, Blocks.FLOWERING_AZALEA.defaultBlockState(), false);
                        else if (roll < 524) smallDripleaf(section, lx, ly, lz, random);
                        else if (roll < 532) bigDripleaf(section, lx, ly, lz, 1 + random.nextInt(4), random);
                    } else if (ceiling) {
                        if (stone(above) && roll > 940) {
                            section.setBlockState(lx, ly + 1, lz, Blocks.ROOTED_DIRT.defaultBlockState(), false);
                            section.setBlockState(lx, ly, lz, Blocks.HANGING_ROOTS.defaultBlockState(), false);
                            continue;
                        }
                        if (stone(above)) section.setBlockState(lx, ly + 1, lz, MOSS, false);
                        if (roll < 160) vine(section, lx, ly, lz, 1 + random.nextInt(6), random);
                        else if (roll < 164) section.setBlockState(lx, ly, lz, Blocks.SPORE_BLOSSOM.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private static boolean stone(BlockState state) { return rock(state); }

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** A two-block small dripleaf, where it fits in the cube. */
    private static void smallDripleaf(LevelChunkSection section, int lx, int ly, int lz, RandomSource random) {
        if (ly + 1 > 15 || !section.getBlockState(lx, ly + 1, lz).isAir()) return;
        Direction facing = SIDES[random.nextInt(4)];
        BlockState lower = Blocks.SMALL_DRIPLEAF.defaultBlockState().setValue(net.minecraft.world.level.block.SmallDripleafBlock.FACING, facing);
        section.setBlockState(lx, ly, lz, lower.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), false);
        section.setBlockState(lx, ly + 1, lz, lower.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), false);
    }

    /** A big dripleaf on a stem {@code height} blocks tall in all, shortened to the air above it in the cube. */
    private static void bigDripleaf(LevelChunkSection section, int lx, int ly, int lz, int height, RandomSource random) {
        int top = ly;
        while (top - ly + 1 < height && top + 1 <= 15 && section.getBlockState(lx, top + 1, lz).isAir() && (top + 2 > 15 || section.getBlockState(lx, top + 2, lz).isAir())) top++;
        Direction facing = SIDES[random.nextInt(4)];
        for (int y = ly; y < top; y++)
            section.setBlockState(lx, y, lz, Blocks.BIG_DRIPLEAF_STEM.defaultBlockState().setValue(net.minecraft.world.level.block.BigDripleafStemBlock.FACING, facing), false);
        section.setBlockState(lx, top, lz, Blocks.BIG_DRIPLEAF.defaultBlockState().setValue(net.minecraft.world.level.block.BigDripleafBlock.FACING, facing), false);
    }

    /** Vanilla's base stone: what rock veins replace, and what cave plants grow from. Checked by block, as tags may be unbound. */
    static boolean rock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.GRANITE || block == Blocks.DIORITE
                || block == Blocks.ANDESITE || block == Blocks.TUFF;
    }

    /** A water block in a moss floor holds when the floor around it at its level is solid. */
    private static boolean enclosed(LevelChunkSection section, int lx, int ly, int lz) {
        if (ly < 1 || section.getBlockState(lx, ly - 1, lz).isAir()) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState side = section.getBlockState(lx + d.getStepX(), ly, lz + d.getStepZ());
            if (side.isAir() || side.is(Blocks.LAVA)) return false;
        }
        return true;
    }

    /** A pointed dripstone of up to {@code length} blocks growing from a floor (UP) or ceiling (DOWN). */
    private static void spike(LevelChunkSection section, int lx, int ly, int lz, Direction direction, int length) {
        int n = 0, step = direction.getStepY();
        while (n < length && ly + n * step >= 0 && ly + n * step <= 15 && section.getBlockState(lx, ly + n * step, lz).isAir()) n++;
        // Leave a block of air at the tip so stalactites and stalagmites don't fuse.
        if (n > 1 && (ly + n * step < 0 || ly + n * step > 15 || !section.getBlockState(lx, ly + n * step, lz).isAir())) n--;
        for (int i = 0; i < n; i++) {
            int fromTip = n - 1 - i;
            DripstoneThickness thickness = fromTip == 0 ? DripstoneThickness.TIP : fromTip == 1 ? DripstoneThickness.FRUSTUM
                    : i == 0 ? DripstoneThickness.BASE : DripstoneThickness.MIDDLE;
            section.setBlockState(lx, ly + i * step, lz, Blocks.POINTED_DRIPSTONE.defaultBlockState()
                    .setValue(PointedDripstoneBlock.TIP_DIRECTION, direction).setValue(PointedDripstoneBlock.THICKNESS, thickness), false);
        }
    }

    /** Cave vines hanging from a ceiling, some with glow berries. */
    private static void vine(LevelChunkSection section, int lx, int ly, int lz, int length, RandomSource random) {
        int n = 0;
        while (n < length && ly - n >= 0 && section.getBlockState(lx, ly - n, lz).isAir()) n++;
        for (int i = 0; i < n; i++) {
            Block block = i == n - 1 ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT;
            section.setBlockState(lx, ly - i, lz, block.defaultBlockState().setValue(CaveVines.BERRIES, random.nextInt(5) == 0), false);
        }
    }

    long mix(long x, long y, long z) { return terrain.mix(x, y, z); }
}
