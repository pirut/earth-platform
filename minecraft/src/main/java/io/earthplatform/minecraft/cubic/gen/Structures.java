package io.earthplatform.minecraft.cubic.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/**
 * Vanilla's natural underground structures, rebuilt for cubes: amethyst geodes, dungeons and
 * abandoned mineshafts. Villages, outposts and other settlements are left out on purpose, as the
 * Earth is wild land only.
 *
 * <p>Cubes generate one at a time and are rebuilt from the seed until edited, so every structure
 * is a pure function of its position: each one is planned from its own seeded random and the
 * ground as generated (see {@link Underground#solid}), and each cube draws the parts of every
 * nearby structure that fall inside it.
 */
final class Structures {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), PLANKS = Blocks.OAK_PLANKS.defaultBlockState(),
            FENCE = Blocks.OAK_FENCE.defaultBlockState(), COBWEB = Blocks.COBWEB.defaultBlockState(),
            COBBLE = Blocks.COBBLESTONE.defaultBlockState(), MOSSY = Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
            SPAWNER = Blocks.SPAWNER.defaultBlockState(), DIRT = Blocks.DIRT.defaultBlockState();

    private final Underground ground;
    private final long seed;
    private final ImprovedNoise geodeNoise;
    private final ConcurrentHashMap<Long, Optional<Geode>> geodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Optional<Dungeon>> dungeons = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Optional<Mineshaft>> mineshafts = new ConcurrentHashMap<>();

    Structures(Underground ground, long seed) {
        this.ground = ground;
        this.seed = seed;
        this.geodeNoise = new ImprovedNoise(new LegacyRandomSource(seed ^ 0x47454F4445L));
    }

    /** Draws every structure part inside a cube, in vanilla's order: geodes, then mineshafts, then dungeons. */
    void place(LevelChunkSection section, int cx, int cy, int cz, List<CompoundTag> blockEntities, List<CompoundTag> entities) {
        Cut cut = new Cut(section, cx, cy, cz, blockEntities, entities);
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            cached(geodes, cx + dx, cy + dy, cz + dz, this::planGeode).ifPresent(g -> drawGeode(g, cut));
        int x0 = cx << 4, z0 = cz << 4;
        for (int rz = Math.floorDiv(z0 - MINESHAFT_REACH, REGION); rz <= Math.floorDiv(z0 + 15 + MINESHAFT_REACH, REGION); rz++)
            for (int rx = Math.floorDiv(x0 - MINESHAFT_REACH, REGION); rx <= Math.floorDiv(x0 + 15 + MINESHAFT_REACH, REGION); rx++)
                cached(mineshafts, rx, 0, rz, (x, y, z) -> planMineshaft(x, z)).ifPresent(m -> drawMineshaft(m, cut));
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

    // Mineshafts ---------------------------------------------------------------------------------

    /** One try per 128-block region, about vanilla's 0.4% of chunks; pieces stay within 80 blocks of the start. */
    private static final int REGION = 128, SPREAD = 80, MINESHAFT_REACH = SPREAD + 24;

    private enum Kind { ROOM, CORRIDOR, CROSSING, TALL_CROSSING, STAIRS_DOWN, STAIRS_UP }

    /**
     * A piece laid out along {@code dir} from its entrance (the first block of its centre line at
     * floor height): {@code length} blocks long, {@code half} blocks to each side. Rooms use their box only.
     */
    private record Piece(Kind kind, int x0, int y0, int z0, int x1, int y1, int z1, Direction dir, int ex, int ey, int ez,
                         int length, int half, boolean rails, boolean spiders, int chestAt, long salt) {
        boolean overlaps(Piece o) { return x0 <= o.x1 && x1 >= o.x0 && y0 <= o.y1 && y1 >= o.y0 && z0 <= o.z1 && z1 >= o.z0; }
        boolean touches(Cut c) { return x0 - 1 < c.x0 + 16 && x1 + 1 >= c.x0 && y0 - 1 < c.y0 + 16 && y1 + 1 >= c.y0 && z0 - 1 < c.z0 + 16 && z1 + 1 >= c.z0; }
    }
    private record Mineshaft(List<Piece> pieces, int x0, int y0, int z0, int x1, int y1, int z1) {}

    private Optional<Mineshaft> planMineshaft(int rx, int rz) {
        RandomSource random = new LegacyRandomSource(seed ^ ground.mix(rx, 0x4D494E45L, rz));
        if (random.nextInt(4) != 0) return Optional.empty();
        int sx = rx * REGION + random.nextInt(REGION), sz = rz * REGION + random.nextInt(REGION);
        int sy = ground.floor(sx, sz) - 64 + (-30 + random.nextInt(70));
        int w = 7 + random.nextInt(6), l = 7 + random.nextInt(6), h = 4 + random.nextInt(3);
        if (depthUnder(sx, sy + h, sz) < 16) return Optional.empty();
        List<Piece> pieces = new ArrayList<>();
        Piece room = new Piece(Kind.ROOM, sx, sy, sz, sx + w - 1, sy + h - 1, sz + l - 1, Direction.NORTH, sx, sy, sz, 0, 0, false, false, -1, random.nextLong());
        pieces.add(room);
        Layout layout = new Layout(random, pieces, sx, sz);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            int exits = 1 + random.nextInt(2);
            for (int e = 0; e < exits; e++) {
                int along = 1 + random.nextInt(Math.max(1, (side.getAxis() == Direction.Axis.X ? l : w) - 2));
                int ex = switch (side) { case EAST -> room.x1 + 1; case WEST -> room.x0 - 1; default -> room.x0 + along; };
                int ez = switch (side) { case SOUTH -> room.z1 + 1; case NORTH -> room.z0 - 1; default -> room.z0 + along; };
                layout.next(side, ex, sy, ez, 1);
            }
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (Piece p : pieces) {
            x0 = Math.min(x0, p.x0); y0 = Math.min(y0, p.y0); z0 = Math.min(z0, p.z0);
            x1 = Math.max(x1, p.x1); y1 = Math.max(y1, p.y1); z1 = Math.max(z1, p.z1);
        }
        return Optional.of(new Mineshaft(pieces, x0, y0, z0, x1, y1, z1));
    }

    /** Lays out pieces depth first from one random, as vanilla's mineshaft does. */
    private static final class Layout {
        final RandomSource random;
        final List<Piece> pieces;
        final int sx, sz;
        Layout(RandomSource random, List<Piece> pieces, int sx, int sz) { this.random = random; this.pieces = pieces; this.sx = sx; this.sz = sz; }

        void next(Direction dir, int ex, int ey, int ez, int depth) {
            if (depth > 8 || Math.abs(ex - sx) > SPREAD || Math.abs(ez - sz) > SPREAD) return;
            int roll = random.nextInt(100);
            if (roll >= 80) crossing(dir, ex, ey, ez, depth, random.nextInt(4) == 0);
            else if (roll >= 70) stairs(dir, ex, ey, ez, depth, random.nextBoolean());
            else corridor(dir, ex, ey, ez, depth);
        }

        @Nullable Piece add(Kind kind, Direction dir, int ex, int ey, int ez, int length, int half, int height, int below, boolean rails, boolean spiders, int chestAt) {
            Direction side = dir.getClockWise();
            int ax = ex + dir.getStepX() * (length - 1), az = ez + dir.getStepZ() * (length - 1);
            int x0 = Math.min(ex, ax) - Math.abs(side.getStepX()) * half, x1 = Math.max(ex, ax) + Math.abs(side.getStepX()) * half;
            int z0 = Math.min(ez, az) - Math.abs(side.getStepZ()) * half, z1 = Math.max(ez, az) + Math.abs(side.getStepZ()) * half;
            Piece piece = new Piece(kind, x0, ey - below, z0, x1, ey + height - 1, z1, dir, ex, ey, ez, length, half, rails, spiders, chestAt, random.nextLong());
            for (Piece other : pieces) if (other.overlaps(piece)) return null;
            pieces.add(piece);
            return piece;
        }

        void corridor(Direction dir, int ex, int ey, int ez, int depth) {
            boolean rails = random.nextInt(3) == 0, spiders = !rails && random.nextInt(23) == 0;
            for (int segments = random.nextInt(3) + 2; segments > 0; segments--) {
                int length = segments * 5;
                int chestAt = random.nextInt(6) == 0 ? random.nextInt(length) : -1;
                if (chestAt % 5 == 2) chestAt = -1;
                Piece piece = add(Kind.CORRIDOR, dir, ex, ey, ez, length, 1, 3, 0, rails, spiders, chestAt);
                if (piece == null) continue;
                // Side passages now and then, as vanilla's corridors branch.
                for (int a = 5; a < length; a += 5) {
                    if (random.nextInt(8) != 0) continue;
                    Direction side = random.nextBoolean() ? dir.getClockWise() : dir.getCounterClockWise();
                    next(side, ex + dir.getStepX() * a + side.getStepX() * 2, ey, ez + dir.getStepZ() * a + side.getStepZ() * 2, depth + 1);
                }
                next(dir, ex + dir.getStepX() * length, ey, ez + dir.getStepZ() * length, depth + 1);
                return;
            }
        }

        void crossing(Direction dir, int ex, int ey, int ez, int depth, boolean tall) {
            Piece piece = add(tall ? Kind.TALL_CROSSING : Kind.CROSSING, dir, ex, ey, ez, 5, 2, tall ? 7 : 3, 0, false, false, -1);
            if (piece == null) return;
            int mx = ex + dir.getStepX() * 2, mz = ez + dir.getStepZ() * 2;
            next(dir, ex + dir.getStepX() * 5, ey, ez + dir.getStepZ() * 5, depth + 1);
            for (Direction side : new Direction[]{dir.getClockWise(), dir.getCounterClockWise()}) {
                if (random.nextInt(4) != 0) next(side, mx + side.getStepX() * 3, ey, mz + side.getStepZ() * 3, depth + 1);
                if (tall && random.nextInt(2) == 0) next(side, mx + side.getStepX() * 3, ey + 4, mz + side.getStepZ() * 3, depth + 1);
            }
        }

        void stairs(Direction dir, int ex, int ey, int ez, int depth, boolean up) {
            Piece piece = up ? add(Kind.STAIRS_UP, dir, ex, ey, ez, 9, 1, 8, 0, false, false, -1)
                    : add(Kind.STAIRS_DOWN, dir, ex, ey, ez, 9, 1, 3, 5, false, false, -1);
            if (piece != null) next(dir, ex + dir.getStepX() * 9, ey + (up ? 5 : -5), ez + dir.getStepZ() * 9, depth + 1);
        }
    }

    private void drawMineshaft(Mineshaft m, Cut cut) {
        if (m.x0 - 1 >= cut.x0 + 16 || m.x1 + 1 < cut.x0 || m.y0 - 1 >= cut.y0 + 16 || m.y1 + 1 < cut.y0 || m.z0 - 1 >= cut.z0 + 16 || m.z1 + 1 < cut.z0) return;
        for (Piece p : m.pieces) if (p.touches(cut)) drawPiece(p, cut);
    }

    private void drawPiece(Piece p, Cut cut) {
        for (int y = Math.max(cut.y0, p.y0 - 1); y <= Math.min(cut.y0 + 15, p.y1); y++)
            for (int z = Math.max(cut.z0, p.z0); z <= Math.min(cut.z0 + 15, p.z1); z++)
                for (int x = Math.max(cut.x0, p.x0); x <= Math.min(cut.x0 + 15, p.x1); x++) {
                    // Never close under the ground, or under the sea or a lake.
                    if (depthUnder(x, y, z) < ground.lid(x, z)) continue;
                    BlockState here = cut.get(x, y, z);
                    BlockState state = p.kind == Kind.ROOM ? room(p, x, y, z) : passage(p, x, y, z, here.isAir() || here.liquid());
                    if (state == null) continue;
                    cut.set(x, y, z, state);
                    if (state == SPAWNER) cut.blockEntity(x, y, z, spawner("minecraft:cave_spider"));
                    if (state.is(Blocks.RAIL) && p.chestAt >= 0 && along(p, x, z) == p.chestAt && y == p.ey && side(p, x, z) == 0 && cut.inside(x, y, z)) {
                        cut.entities.add(minecart(x, y, z, p.salt));
                    }
                }
    }

    /** A room: dirt floor, open above it. */
    @Nullable private static BlockState room(Piece p, int x, int y, int z) {
        if (y == p.y0 - 1) return DIRT;
        return AIR;
    }

    private static int along(Piece p, int x, int z) { return (x - p.ex) * p.dir.getStepX() + (z - p.ez) * p.dir.getStepZ(); }
    private static int side(Piece p, int x, int z) {
        Direction s = p.dir.getClockWise();
        return (x - p.ex) * s.getStepX() + (z - p.ez) * s.getStepZ();
    }

    /** Corridors, crossings and stairs: open passage with plank floors over gaps, oak supports, rails and webs. */
    @Nullable private BlockState passage(Piece p, int x, int y, int z, boolean open) {
        int a = along(p, x, z), c = side(p, x, z), floor = p.ey;
        if (p.kind == Kind.STAIRS_DOWN) floor = p.ey - Mth.clamp(a - 1, 0, 5);
        if (p.kind == Kind.STAIRS_UP) floor = p.ey + Mth.clamp(a - 1, 0, 5);
        int h = y - floor, height = p.kind == Kind.TALL_CROSSING ? 7 : 3;
        if (h == -1) return open ? PLANKS : null;
        if (h < 0 || h >= height) return null;
        long r = ground.mix(x ^ p.salt, y, z);
        int roll = (int) Math.floorMod(r, 100);
        if (p.kind == Kind.TALL_CROSSING && Math.abs(c) == 1 && (a == 1 || a == 3)) return PLANKS;
        if (p.kind != Kind.CORRIDOR) return AIR;
        if (a % 5 == 2) {
            if (h == 2) return PLANKS;
            if (Math.abs(c) == 1) return FENCE;
        }
        if (p.spiders) {
            if (a == p.length / 2 && c == 0 && h == 0) return SPAWNER;
            if (roll < 30) return COBWEB;
        } else if (h == 2 && c != 0 && roll < 6) return COBWEB;
        if (h == 0 && c == 0 && (p.rails && roll < 70 || a == p.chestAt)) {
            RailShape shape = p.dir.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
            return Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape);
        }
        return AIR;
    }

    private static CompoundTag minecart(int x, int y, int z, long salt) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:chest_minecart");
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x + 0.5)); pos.add(DoubleTag.valueOf(y + 0.0625)); pos.add(DoubleTag.valueOf(z + 0.5));
        tag.put("Pos", pos);
        tag.putString("LootTable", "minecraft:chests/abandoned_mineshaft");
        tag.putLong("LootTableSeed", salt);
        return tag;
    }
}
