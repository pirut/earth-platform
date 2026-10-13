package io.earthplatform.minecraft.cubic.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/**
 * Vanilla's small underground features, rebuilt for cubes: amethyst geodes and dungeons. Vanilla's
 * structures proper (mineshafts, villages, ancient cities and the rest) come from vanilla's own
 * code, through {@link VanillaStructures}.
 *
 * <p>Cubes generate one at a time and are rebuilt from the seed until edited, so every structure
 * is a pure function of its position: each one is planned from its own seeded random and the
 * ground as generated (see {@link Underground#solid}), and each cube draws the parts of every
 * nearby structure that fall inside it.
 */
final class Structures {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(),
            COBBLE = Blocks.COBBLESTONE.defaultBlockState(), MOSSY = Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
            SPAWNER = Blocks.SPAWNER.defaultBlockState();

    private final Underground ground;
    private final long seed;
    private final ImprovedNoise geodeNoise;
    private final ConcurrentHashMap<Long, Optional<Geode>> geodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Optional<Dungeon>> dungeons = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Optional<Fossil>> fossils = new ConcurrentHashMap<>();

    Structures(Underground ground, long seed) {
        this.ground = ground;
        this.seed = seed;
        this.geodeNoise = new ImprovedNoise(new LegacyRandomSource(seed ^ 0x47454F4445L));
    }

    /** Draws every structure part inside a cube, in vanilla's order: geodes, then dungeons. */
    void place(LevelChunkSection section, int cx, int cy, int cz, List<CompoundTag> blockEntities, List<CompoundTag> entities) {
        Cut cut = new Cut(section, cx, cy, cz, blockEntities, entities);
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            cached(geodes, cx + dx, cy + dy, cz + dz, this::planGeode).ifPresent(g -> drawGeode(g, cut));
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            cached(fossils, cx + dx, cy + dy, cz + dz, this::planFossil).ifPresent(f -> drawFossil(f, cut));
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            cached(dungeons, cx + dx, cy + dy, cz + dz, this::planDungeon).ifPresent(d -> drawDungeon(d, cut));
    }

    private interface Planner<T> { Optional<T> plan(int x, int y, int z); }

    private static <T> Optional<T> cached(ConcurrentHashMap<Long, Optional<T>> cache, int x, int y, int z, Planner<T> planner) {
        long key = ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
        Optional<T> plan = cache.get(key);
        if (plan != null) return plan;
        plan = planner.plan(x, y, z);
        if (cache.size() > 16384) cache.clear();
        cache.putIfAbsent(key, plan);
        return plan;
    }

    /** Writes into one cube only, and collects the block entities and entities that start in it. */
    private static final class Cut {
        final LevelChunkSection section;
        final int x0, y0, z0;
        final List<CompoundTag> blockEntities, entities;
        Cut(LevelChunkSection section, int cx, int cy, int cz, List<CompoundTag> blockEntities, List<CompoundTag> entities) {
            this.section = section; this.x0 = cx << 4; this.y0 = cy << 4; this.z0 = cz << 4;
            this.blockEntities = blockEntities; this.entities = entities;
        }
        boolean inside(int x, int y, int z) { return x >= x0 && y >= y0 && z >= z0 && x < x0 + 16 && y < y0 + 16 && z < z0 + 16; }
        BlockState get(int x, int y, int z) { return section.getBlockState(x - x0, y - y0, z - z0); }
        void set(int x, int y, int z, BlockState state) {
            if (inside(x, y, z) && !get(x, y, z).is(Blocks.BEDROCK)) section.setBlockState(x - x0, y - y0, z - z0, state, false);
        }
        void blockEntity(int x, int y, int z, CompoundTag tag) {
            if (!inside(x, y, z)) return;
            tag.putInt("x", x); tag.putInt("y", y); tag.putInt("z", z);
            blockEntities.add(tag);
        }
    }

    private static CompoundTag spawner(String mob) {
        CompoundTag tag = new CompoundTag(), data = new CompoundTag(), entity = new CompoundTag();
        tag.putString("id", "minecraft:mob_spawner");
        entity.putString("id", mob);
        data.put("entity", entity);
        tag.put("SpawnData", data);
        return tag;
    }

    private static CompoundTag chest(String lootTable, long lootSeed) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:chest");
        tag.putString("LootTable", lootTable);
        tag.putLong("LootTableSeed", lootSeed);
        return tag;
    }

    private int depthUnder(int x, int y, int z) { return ground.depth(x, y, z); }

    // Fossils ------------------------------------------------------------------------------------

    /** A buried skeleton: a spine along one axis with ribs and a skull, some bone turned to coal (or deep down, diamond). */
    private record Fossil(int x, int y, int z, boolean alongX, int length, boolean deep, long salt) {}

    /** Vanilla's rates: one try in 64 chunk columns each over vanilla heights 0 to 64, and -64 to -8 for the deep kind. */
    private Optional<Fossil> planFossil(int cx, int cy, int cz) {
        RandomSource random = new LegacyRandomSource(seed ^ ground.mix(cx, cy, cz) ^ 0x464F5353494CL);
        if (random.nextInt(256) != 0) return Optional.empty();
        int x = (cx << 4) + random.nextInt(16), y = (cy << 4) + random.nextInt(16), z = (cz << 4) + random.nextInt(16);
        int vy = Underground.vanillaY(y, ground.floor(x, z));
        boolean deep = vy < 0;
        if (deep ? vy < -64 || vy > -8 : vy > 64) return Optional.empty();
        if (depthUnder(x, y, z) < 12 || !ground.solid(x, y, z)) return Optional.empty();
        return Optional.of(new Fossil(x, y, z, random.nextBoolean(), 5 + random.nextInt(5), deep, random.nextLong()));
    }

    private void drawFossil(Fossil f, Cut cut) {
        BlockState bone = Blocks.BONE_BLOCK.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                f.alongX ? Direction.Axis.X : Direction.Axis.Z);
        BlockState upright = Blocks.BONE_BLOCK.defaultBlockState();
        BlockState ore = f.deep ? Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState() : Blocks.COAL_ORE.defaultBlockState();
        for (int i = 0; i < f.length; i++) {
            put(f, cut, i, 0, 0, bone, ore);
            if (i % 2 == 1 && i < f.length - 2) for (int s = -1; s <= 1; s += 2) {
                put(f, cut, i, 1, s, upright, ore); put(f, cut, i, 0, 2 * s, upright, ore); put(f, cut, i, -1, 2 * s, upright, ore);
            }
        }
        // The skull: a 2x2x2 block at the far end.
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++) put(f, cut, f.length + a, b, c, upright, ore);
    }

    /** One fossil block, {@code along} the spine, {@code up} and {@code side} of it; some decay away, some are ore; only in rock. */
    private void put(Fossil f, Cut cut, int along, int up, int side, BlockState bone, BlockState ore) {
        int x = f.x + (f.alongX ? along : side), y = f.y + up, z = f.z + (f.alongX ? side : along);
        if (!cut.inside(x, y, z) || !Underground.rock(cut.get(x, y, z))) return;
        int roll = (int) Math.floorMod(ground.mix(x, y, z) ^ f.salt, 100);
        if (roll < 10) return;
        cut.set(x, y, z, roll < 20 ? ore : bone);
    }

    // Geodes -------------------------------------------------------------------------------------

    private record Point(int x, int y, int z, int offset) {}
    private record Geode(List<Point> points, List<Point> crack, double crackSize, double layer, long salt) {}

    /** Vanilla's rate: one try in 24 chunk columns, spread over vanilla heights -58 to 30. */
    private Optional<Geode> planGeode(int cx, int cy, int cz) {
        RandomSource random = new LegacyRandomSource(seed ^ ground.mix(cx, cy, cz) ^ 0x47454F4445L);
        if (random.nextInt(24 * 88) >= 16) return Optional.empty();
        int x = (cx << 4) + random.nextInt(16), y = (cy << 4) + random.nextInt(16), z = (cz << 4) + random.nextInt(16);
        int vy = Underground.vanillaY(y, ground.floor(x, z));
        if (vy < -58 || vy > 30 || depthUnder(x, y, z) < 24) return Optional.empty();
        int count = 3 + random.nextInt(2), invalid = 0;
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Point p = new Point(x + 4 + random.nextInt(3), y + 4 + random.nextInt(3), z + 4 + random.nextInt(3), 1 + random.nextInt(2));
            if (!ground.solid(p.x, p.y, p.z) && ++invalid > 1) return Optional.empty();
            points.add(p);
        }
        double layer = count / 6.0;
        double crackSize = 1 / Math.sqrt(2 + random.nextDouble() / 2 + (count > 3 ? layer : 0));
        List<Point> crack = new ArrayList<>();
        if (random.nextFloat() < 0.95F) {
            int side = random.nextInt(4), out = count * 2 + 1;
            int ox = side == 0 || side == 2 ? out : 0, oz = side == 1 || side == 2 ? out : 0;
            for (int h : new int[]{7, 5, 1}) crack.add(new Point(x + ox, y + h, z + oz, 2));
        }
        return Optional.of(new Geode(points, crack, crackSize, layer, random.nextLong()));
    }

    private enum Shell { NONE, AIR, INNER, MIDDLE, OUTER }

    private Shell shell(Geode g, int x, int y, int z) {
        double noise = geodeNoise.noise(x / 16.0, y / 16.0, z / 16.0) * 0.05, d = 0, c = 0;
        for (Point p : g.points) d += Mth.invSqrt(dist(x, y, z, p) + p.offset) + noise;
        for (Point p : g.crack) c += Mth.invSqrt(dist(x, y, z, p) + p.offset) + noise;
        if (d < 1 / Math.sqrt(4.2 + g.layer)) return Shell.NONE;
        if (!g.crack.isEmpty() && c >= g.crackSize && d < 1 / Math.sqrt(1.7)) return Shell.AIR;
        if (d >= 1 / Math.sqrt(1.7)) return Shell.AIR;
        if (d >= 1 / Math.sqrt(2.2 + g.layer)) return Shell.INNER;
        if (d >= 1 / Math.sqrt(3.2 + g.layer)) return Shell.MIDDLE;
        return Shell.OUTER;
    }
    private static double dist(int x, int y, int z, Point p) {
        double dx = x - p.x, dy = y - p.y, dz = z - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void drawGeode(Geode g, Cut cut) {
        Point first = g.points.get(0);
        for (int y = Math.max(cut.y0, first.y - 16); y <= Math.min(cut.y0 + 15, first.y + 12); y++)
            for (int z = Math.max(cut.z0, first.z - 16); z <= Math.min(cut.z0 + 15, first.z + 12); z++)
                for (int x = Math.max(cut.x0, first.x - 16); x <= Math.min(cut.x0 + 15, first.x + 12); x++) {
                    Shell shell = shell(g, x, y, z);
                    if (shell == Shell.NONE) continue;
                    long r = ground.mix(x ^ g.salt, y, z);
                    BlockState state = switch (shell) {
                        case AIR -> AIR;
                        case INNER -> budding(r) ? Blocks.BUDDING_AMETHYST.defaultBlockState() : Blocks.AMETHYST_BLOCK.defaultBlockState();
                        case MIDDLE -> Blocks.CALCITE.defaultBlockState();
                        default -> Blocks.SMOOTH_BASALT.defaultBlockState();
                    };
                    cut.set(x, y, z, state);
                }
        // Crystals grow from some budding blocks into the hollow, on the first open side.
        for (int y = Math.max(cut.y0, first.y - 16); y <= Math.min(cut.y0 + 15, first.y + 12); y++)
            for (int z = Math.max(cut.z0, first.z - 16); z <= Math.min(cut.z0 + 15, first.z + 12); z++)
                for (int x = Math.max(cut.x0, first.x - 16); x <= Math.min(cut.x0 + 15, first.x + 12); x++) {
                    if (!cut.get(x, y, z).isAir() || shell(g, x, y, z) != Shell.AIR) continue;
                    for (Direction d : Direction.values()) {
                        int bx = x - d.getStepX(), by = y - d.getStepY(), bz = z - d.getStepZ();
                        if (shell(g, bx, by, bz) != Shell.INNER) continue;
                        long r = ground.mix(bx ^ g.salt, by, bz);
                        if (!budding(r) || Math.floorMod(r >>> 20, 100) >= 35 || firstOpen(g, bx, by, bz) != d) continue;
                        Block bud = switch ((int) Math.floorMod(r >>> 28, 4)) {
                            case 0 -> Blocks.SMALL_AMETHYST_BUD; case 1 -> Blocks.MEDIUM_AMETHYST_BUD; case 2 -> Blocks.LARGE_AMETHYST_BUD;
                            default -> Blocks.AMETHYST_CLUSTER;
                        };
                        cut.set(x, y, z, bud.defaultBlockState().setValue(AmethystClusterBlock.FACING, d));
                        break;
                    }
                }
    }
    private static boolean budding(long r) { return Math.floorMod(r, 1000) < 83; }
    @Nullable private Direction firstOpen(Geode g, int x, int y, int z) {
        for (Direction d : Direction.values()) if (shell(g, x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()) == Shell.AIR) return d;
        return null;
    }

    // Dungeons -----------------------------------------------------------------------------------

    private record Dungeon(int x, int y, int z, int rx, int rz, String mob, List<int[]> chests, long salt) {}

    private static final String[] DUNGEON_MOBS = {"minecraft:skeleton", "minecraft:zombie", "minecraft:zombie", "minecraft:spider"};

    /**
     * Vanilla's monster room: a cobblestone room of 5 or 7 by 5 or 7 with a spawner, needing
     * solid floor and roof and one to five openings at floor level. As in vanilla, most tries fail.
     */
    private Optional<Dungeon> planDungeon(int cx, int cy, int cz) {
        RandomSource random = new LegacyRandomSource(seed ^ ground.mix(cx, cy, cz) ^ 0x44554E47L);
        for (int attempt = 0; attempt < 3; attempt++) {
            int x = (cx << 4) + random.nextInt(16), y = (cy << 4) + random.nextInt(16), z = (cz << 4) + random.nextInt(16);
            int rx = 2 + random.nextInt(2), rz = 2 + random.nextInt(2);
            if (depthUnder(x, y, z) < 12 || Underground.vanillaY(y, ground.floor(x, z)) <= Underground.LAVA_TOP + 1) continue;
            if (!ground.solid(x, y - 1, z) || !ground.solid(x, y + 4, z) || !fits(x, y, z, rx, rz)) continue;
            List<int[]> chests = new ArrayList<>();
            for (int c = 0; c < 2; c++) for (int t = 0; t < 3; t++) {
                int px = x + random.nextInt(rx * 2 + 1) - rx, pz = z + random.nextInt(rz * 2 + 1) - rz;
                Direction wall = wallOf(x, z, rx, rz, px, pz, y);
                if (wall == null || (px == x && pz == z) || chests.stream().anyMatch(o -> o[0] == px && o[2] == pz)) continue;
                chests.add(new int[]{px, y, pz, wall.getOpposite().get2DDataValue()});
                break;
            }
            return Optional.of(new Dungeon(x, y, z, rx, rz, DUNGEON_MOBS[random.nextInt(DUNGEON_MOBS.length)], chests, random.nextLong()));
        }
        return Optional.empty();
    }

    private boolean fits(int x, int y, int z, int rx, int rz) {
        // Openings first: most tries sit in plain rock and have none.
        int openings = 0;
        for (int dx = -rx - 1; dx <= rx + 1; dx++) for (int dz = -rz - 1; dz <= rz + 1; dz++) {
            boolean wall = Math.abs(dx) == rx + 1 || Math.abs(dz) == rz + 1;
            if (wall && !ground.solid(x + dx, y, z + dz) && !ground.solid(x + dx, y + 1, z + dz) && ++openings > 5) return false;
        }
        if (openings == 0) return false;
        for (int dx = -rx - 1; dx <= rx + 1; dx++) for (int dz = -rz - 1; dz <= rz + 1; dz++)
            if (!ground.solid(x + dx, y - 1, z + dz) || !ground.solid(x + dx, y + 4, z + dz)) return false;
        return true;
    }

    /** The one solid wall beside an inside floor spot, as vanilla wants for a chest, or null for none or several. */
    @Nullable private Direction wallOf(int x, int z, int rx, int rz, int px, int pz, int y) {
        Direction found = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int qx = px + d.getStepX(), qz = pz + d.getStepZ();
            boolean wall = Math.abs(qx - x) == rx + 1 || Math.abs(qz - z) == rz + 1;
            if (wall && ground.solid(qx, y, qz)) {
                if (found != null) return null;
                found = d;
            }
        }
        return found;
    }

    private void drawDungeon(Dungeon d, Cut cut) {
        for (int dx = -d.rx - 1; dx <= d.rx + 1; dx++) for (int dy = -1; dy <= 4; dy++) for (int dz = -d.rz - 1; dz <= d.rz + 1; dz++) {
            int x = d.x + dx, y = d.y + dy, z = d.z + dz;
            if (!cut.inside(x, y, z)) continue;
            boolean shell = dy == -1 || dy == 4 || Math.abs(dx) == d.rx + 1 || Math.abs(dz) == d.rz + 1;
            if (!shell) { cut.set(x, y, z, AIR); continue; }
            // Vanilla walls over open space give way; walls in rock turn to cobblestone.
            if (dy > -1 && !ground.solid(x, y - 1, z)) cut.set(x, y, z, AIR);
            else if (ground.solid(x, y, z)) cut.set(x, y, z, dy == -1 && Math.floorMod(ground.mix(x ^ d.salt, y, z), 4) != 0 ? MOSSY : COBBLE);
        }
        if (cut.inside(d.x, d.y, d.z)) {
            cut.set(d.x, d.y, d.z, SPAWNER);
            cut.blockEntity(d.x, d.y, d.z, spawner(d.mob));
        }
        int i = 0;
        for (int[] c : d.chests) {
            if (!cut.inside(c[0], c[1], c[2])) { i++; continue; }
            cut.set(c[0], c[1], c[2], Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.from2DDataValue(c[3])));
            cut.blockEntity(c[0], c[1], c[2], chest("minecraft:chests/simple_dungeon", d.salt + 31L * i++));
        }
    }
}
