package io.earthplatform.minecraft.cubic.gen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Giant sequoias at their real size: 70 to 94 m tall on trunks about 6 to 9 m across, flared at
 * the foot and bare for the lower half, under a rounded crown of big upswept branches that end in
 * clumps of foliage. The bark is mangrove wood's red-brown, and the duff around the foot podzol.
 */
final class Sequoia {
    /** The tallest a sequoia reaches above its origin (its crown spreads at most 16 blocks from the trunk's axis). */
    static final int TALLEST = 98;
    private static final int VARIANTS = 12;
    private static final BlockState LOG = Blocks.MANGROVE_LOG.defaultBlockState(),
            LEAVES = Blocks.SPRUCE_LEAVES.defaultBlockState(), PODZOL = Blocks.PODZOL.defaultBlockState(),
            COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    private static volatile TreeShape[] shapes;

    private Sequoia() {}

    /** The sequoias every grove picks from, grown once. */
    static TreeShape[] shapes() {
        TreeShape[] made = shapes;
        if (made == null) {
            made = new TreeShape[VARIANTS];
            for (int i = 0; i < VARIANTS; i++) made[i] = grow(0x5E9A01AL * 31 + i * 0x9E3779B97F4A7C15L);
            shapes = made;
        }
        return made;
    }

    /** The trunk's radius at a height: about 4 m near the ground over a flared foot, narrowing to about 1 m at the top. */
    private static double radius(double base, int height, double y) {
        double r = base * (1 - 0.72 * Math.pow(y / height, 1.1));
        if (y < 5) r += 1.7 * Math.pow(1 - y / 5, 2);
        return r;
    }

    /** How wide the crown is, as a share of its widest, from its base (0) to the top (1): rounded, widest a third of the way up. */
    private static double crown(double u) {
        if (u < 0.3) return 0.55 + 0.45 * u / 0.3;
        double k = (u - 0.3) / 0.85;
        return Math.sqrt(Math.max(0, 1 - k * k));
    }

    static TreeShape grow(long seed) {
        RandomSource random = RandomSource.create(seed);
        int height = 70 + random.nextInt(25);
        double base = 3.0 + random.nextDouble() * 1.4;
        int crownBase = (int) (height * (0.42 + random.nextDouble() * 0.12));
        double widest = 8.5 + random.nextDouble() * 3.5;
        double phase = random.nextDouble() * Math.PI * 2;
        Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();

        // The trunk, with shallow vertical ridges in its bark.
        BlockState upright = LOG.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        for (int y = 0; y < height; y++) {
            double r = radius(base, height, y);
            int reach = (int) Math.ceil(r + 0.5);
            for (int dz = -reach; dz <= reach; dz++) for (int dx = -reach; dx <= reach; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz), angle = Math.atan2(dz, dx);
                if (d <= r + 0.35 * Math.sin(7 * angle + phase + y / 9.0)) blocks.put(BlockPos.asLong(dx, y, dz), upright);
            }
        }
        // Stubs of shed branches on the bare lower trunk.
        int stubs = 3 + random.nextInt(4);
        for (int i = 0; i < stubs; i++) {
            int y = 12 + random.nextInt(Math.max(1, crownBase - 14));
            branch(blocks, random, base, height, y, random.nextDouble() * Math.PI * 2, radius(base, height, y) + 2 + random.nextInt(2), 0.3, false);
        }
        // The crown: whorls of three or four branches climbing the upper trunk, with foliage hugging the trunk between them.
        for (double y = crownBase; y < height - 4; y += 1.8 + random.nextDouble() * 1.4) {
            int count = 3 + random.nextInt(2);
            double start = random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < count; i++) {
                double angle = start + i * Math.PI * 2 / count + (random.nextDouble() - 0.5);
                double u = (y - crownBase) / (height - crownBase);
                double reach = Math.max(radius(base, height, y) + 2, widest * crown(u) * (0.75 + 0.25 * random.nextDouble()));
                branch(blocks, random, base, height, (int) y, angle, reach, 0.35 + random.nextDouble() * 0.45, true);
            }
            double around = random.nextDouble() * Math.PI * 2, r = radius(base, height, y) + 1;
            clump(blocks, random, (int) Math.round(r * Math.cos(around)), (int) y + 2, (int) Math.round(r * Math.sin(around)), 2.4);
        }
        // The top: foliage round the trunk's tip.
        clump(blocks, random, 0, height - 1, 0, 3.2);
        clump(blocks, random, 0, height - 5, 0, 3.6);

        // Duff of needles at the foot: podzol with a little coarse dirt, under the ground's own height.
        int litter = (int) Math.ceil(base + 5);
        for (int dz = -litter; dz <= litter; dz++) for (int dx = -litter; dx <= litter; dx++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > base + 5 - random.nextDouble() * 2) continue;
            blocks.put(BlockPos.asLong(dx, -1, dz), random.nextInt(6) == 0 ? COARSE_DIRT : PODZOL);
        }
        leafDistances(blocks);
        return new TreeShape(blocks, 0);
    }

    /**
     * A branch from the trunk's surface outward along {@code angle}, rising {@code rise} blocks per
     * block out, to {@code reach} from the axis; with {@code leafy}, clumps of foliage along it and
     * a big one at its end.
     */
    private static void branch(Long2ObjectOpenHashMap<BlockState> blocks, RandomSource random, double base, int height, int y,
                               double angle, double reach, double rise, boolean leafy) {
        double cos = Math.cos(angle), sin = Math.sin(angle), start = radius(base, height, y) - 0.5;
        BlockState log = LOG.setValue(RotatedPillarBlock.AXIS, Math.abs(cos) > Math.abs(sin) ? Direction.Axis.X : Direction.Axis.Z);
        boolean thick = leafy && reach > 7;
        double nextClump = start + 2.5;
        int x = 0, z = 0, top = y;
        for (double s = start; s <= reach; s += 0.5) {
            x = (int) Math.round(s * cos);
            z = (int) Math.round(s * sin);
            top = (int) Math.round(y + (s - start) * rise);
            if (top >= height) break;
            blocks.put(BlockPos.asLong(x, top, z), log);
            if (thick && s < start + 3) blocks.put(BlockPos.asLong(x, top + 1, z), log);
            if (leafy && s >= nextClump) {
                clump(blocks, random, x, top + 1, z, 2.2 + random.nextDouble() * 1.0);
                nextClump = s + 1.8 + random.nextDouble() * 1.2;
            }
        }
        if (leafy) clump(blocks, random, x, top + 1, z, 3.0 + random.nextDouble() * 0.6);
    }

    /** A flattened clump of foliage about (cx, cy, cz), ragged at its edge, around whatever wood is there. */
    private static void clump(Long2ObjectOpenHashMap<BlockState> blocks, RandomSource random, int cx, int cy, int cz, double radius) {
        int r = (int) Math.ceil(radius), ry = (int) Math.ceil(radius * 0.6);
        for (int dy = -ry; dy <= ry; dy++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            double k = (dx * dx + dz * dz) / (radius * radius) + (dy * dy) / (radius * radius * 0.36);
            if (k > 1 || k > 0.6 && random.nextInt(4) == 0) continue;
            blocks.putIfAbsent(BlockPos.asLong(cx + dx, cy + dy, cz + dz), LEAVES);
        }
    }

    /** Sets each leaf's distance from the wood, as vanilla trees have them, and drops leaves too far to stay. */
    private static void leafDistances(Long2ObjectOpenHashMap<BlockState> blocks) {
        Long2IntOpenHashMap distance = new Long2IntOpenHashMap();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (var entry : blocks.long2ObjectEntrySet()) {
            if (entry.getValue().getBlock() instanceof RotatedPillarBlock && BlockPos.getY(entry.getLongKey()) >= 0) {
                distance.put(entry.getLongKey(), 0);
                queue.enqueue(entry.getLongKey());
            }
        }
        while (!queue.isEmpty()) {
            long at = queue.dequeueLong();
            int next = distance.get(at) + 1;
            if (next > 6) continue;
            for (Direction side : Direction.values()) {
                long n = BlockPos.offset(at, side);
                BlockState there = blocks.get(n);
                if (there == null || !(there.getBlock() instanceof LeavesBlock) || distance.containsKey(n)) continue;
                distance.put(n, next);
                queue.enqueue(n);
            }
        }
        var entries = blocks.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!(entry.getValue().getBlock() instanceof LeavesBlock)) continue;
            int d = distance.getOrDefault(entry.getLongKey(), 7);
            if (d > 6) entries.remove();
            else entry.setValue(entry.getValue().setValue(LeavesBlock.DISTANCE, d));
        }
    }
}
