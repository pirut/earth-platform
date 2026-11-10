package io.earthplatform.minecraft.cubic.gen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The giant trees of worlds from build 70 on, 64 to 94 m tall, in three forms. A giant sequoia is a fluted red column
 * on a flared foot, bare for its lower two fifths, under a rounded dome of heavy upswept limbs that each end in a
 * billow of foliage. A coast redwood and a giant fir or spruce are tapering spires of drooping boughs in whorls, each
 * bough carrying flat pads of foliage, the redwood in red bark and the fir in dark. Bark is wood blocks, so no log
 * rings show where a trunk steps in or a limb ends, with darker wood in the furrows between its ridges; foliage is
 * persistent leaves, so a billow can be wider than leaves would live from a log.
 */
final class Giants {
    static final int SEQUOIA = 0, REDWOOD = 1, FIR = 2;
    private static final int VARIANTS = 12;
    private static final BlockState LEAVES = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true),
            PODZOL = Blocks.PODZOL.defaultBlockState(), COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    private static final BlockState[] BARK = {Blocks.MANGROVE_WOOD.defaultBlockState(), Blocks.MANGROVE_WOOD.defaultBlockState(), Blocks.SPRUCE_WOOD.defaultBlockState()},
            FURROW = {Blocks.SPRUCE_WOOD.defaultBlockState(), Blocks.SPRUCE_WOOD.defaultBlockState(), Blocks.DARK_OAK_WOOD.defaultBlockState()};
    private static final TreeShape[][] shapes = new TreeShape[3][];

    private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
    private final Random random;
    private final int salt;
    private final BlockState bark, furrow;

    private Giants(long seed, int form) {
        random = new Random(seed);
        salt = (int) (seed >>> 20);
        bark = BARK[form];
        furrow = FURROW[form];
    }

    /** A form's giants, grown once. */
    static TreeShape[] shapes(int form) {
        TreeShape[] made = shapes[form];
        if (made == null) synchronized (shapes) {
            made = shapes[form];
            if (made == null) {
                made = new TreeShape[VARIANTS];
                for (int i = 0; i < VARIANTS; i++) made[i] = grow(0x61A27L * (form + 1) + i * 0x9E3779B97F4A7C15L, form);
                shapes[form] = made;
            }
        }
        return made;
    }

    static TreeShape grow(long seed, int form) {
        Giants tree = new Giants(seed, form);
        double base = form == SEQUOIA ? tree.sequoia() : tree.spire(form == REDWOOD);
        tree.duff(base);
        // Nothing past what a grove's cubes look for: 16 blocks from the axis, and Sequoia.TALLEST above the foot.
        tree.blocks.keySet().removeIf(key -> Math.abs(BlockPos.getX(key)) > Trees.REACH || Math.abs(BlockPos.getZ(key)) > Trees.REACH || BlockPos.getY(key) >= Sequoia.TALLEST);
        return new TreeShape(tree.blocks, 0);
    }

    // The three forms -------------------------------------------------------------------------------

    private double sequoia() {
        int height = 72 + random.nextInt(23);
        double base = 3.5 + random.nextDouble() * 1.1;
        int crownBase = (int) (height * (0.40 + random.nextDouble() * 0.09));
        double widest = 10 + random.nextDouble() * 2;
        double under = base * (1 - 0.32 * (crownBase / (double) height) / 0.45);
        Radius radius = y -> {
            double u = y / height, ub = crownBase / (double) height;
            return u < ub ? base * (1 - 0.32 * u / 0.45) : under + (1.1 - under) * Math.pow((u - ub) / (1 - ub), 1.25);
        };
        trunk(height, radius, 7 + random.nextInt(3), random.nextDouble() * Math.PI * 2, 0.32, 5 + random.nextInt(3), 7 + random.nextInt(3), 1.8 + random.nextDouble() * 0.8, 0);
        // Heavy limbs up the crown, each a quarter turn and more round from the last: out level, then up, to a billow.
        int limbs = 15 + random.nextInt(5), top = height - 7;
        double angle = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < limbs; i++) {
            double u = (i + random.nextDouble() * 0.6) / limbs, y0 = crownBase + u * (top - crownBase);
            angle += 2.39996 + (random.nextDouble() - 0.5) * 0.5;
            double from = radius.at(y0) - 1, reach = Math.max(from + 4, widest * dome(u) * (0.72 + 0.28 * random.nextDouble()));
            double rise = reach * (0.35 + 0.4 * random.nextDouble());
            int steps = (int) (reach * 2);
            double[][] path = new double[steps + 1][];
            for (int j = 0; j <= steps; j++) {
                double q = j / (double) steps, d = from + (reach - from) * q;
                path[j] = new double[]{d * Math.cos(angle), y0 + rise * q * q - 0.6 * Math.sin(q * Math.PI), d * Math.sin(angle)};
            }
            limb(path, reach > 7 ? 1.25 : 0.7);
            double[] end = path[steps], middle = path[(int) (steps * 0.55)];
            double big = Math.min(reach > 6 ? 3.9 + random.nextDouble() * 1.3 : 3.0 + random.nextDouble() * 0.8, 15.4 - reach);
            billow(end[0], end[1] + 1.5, end[2], big, big * 0.85, 0.5, 0.22, 3.2);
            double mid = 3.0 + random.nextDouble();
            double mx = (random.nextDouble() - 0.5) * 2, mz = (random.nextDouble() - 0.5) * 2;
            if (reach > 6.5) billow(middle[0] + mx, middle[1] + 2, middle[2] + mz, mid, mid * 0.85, 0.5, 0.22, 3.2);
            // Foliage against the trunk in the upper crown, where the limbs are short.
            if (u > 0.45) billow((from + 2.5) * Math.cos(angle + 1.3), y0 + 2, (from + 2.5) * Math.sin(angle + 1.3), 2.8, 2.2, 0.6, 0.22, 3.2);
            if (random.nextBoolean()) {
                // A spray off the limb's side, to its own smaller billow.
                double aside = angle + (random.nextBoolean() ? 1 : -1) * (0.7 + random.nextDouble() * 0.5), out = reach * 0.72, size = 2.8 + random.nextDouble() * 0.9;
                double[] start = path[(int) (steps * 0.45)];
                double[][] spray = new double[9][];
                for (int j = 0; j < 9; j++) {
                    double q = j / 8.0;
                    spray[j] = new double[]{start[0] + (out * Math.cos(aside) - start[0]) * q, start[1] + (end[1] - start[1] + 1) * q, start[2] + (out * Math.sin(aside) - start[2]) * q};
                }
                limb(spray, 0.6);
                billow(spray[8][0], spray[8][1] + 1, spray[8][2], size, size * 0.85, 0.5, 0.22, 3.2);
            }
        }
        // The rounded top.
        double cap = 4.6 + random.nextDouble();
        billow(0, height - 4, 0, cap, 4.4, 0.7, 0.2, 3.2);
        for (int i = 0; i < 3; i++) {
            double around = random.nextDouble() * Math.PI * 2, d = 2.5 + random.nextDouble() * 1.5, size = 3.8 + random.nextDouble();
            billow(d * Math.cos(around), height - 9 - random.nextDouble() * 3, d * Math.sin(around), size, 3.0, 0.5, 0.22, 3.2);
        }
        // Stubs of shed limbs on the bare trunk.
        for (int i = 2 + random.nextInt(3); i > 0; i--) {
            int y = crownBase / 2 + random.nextInt(Math.max(1, crownBase / 2 - 4));
            double around = random.nextDouble() * Math.PI * 2, from = radius.at(y) - 1, length = 2.5 + random.nextDouble() * 2;
            int steps = (int) (length * 2);
            double[][] stub = new double[steps + 1][];
            for (int j = 0; j <= steps; j++) stub[j] = new double[]{(from + j * 0.5) * Math.cos(around), y + j * 0.15, (from + j * 0.5) * Math.sin(around)};
            limb(stub, 0.6);
        }
        return base;
    }

    /** How wide a sequoia's crown is from its base (0) to its top (1), as a share of its widest: a dome on a short skirt. */
    private static double dome(double u) {
        if (u < 0.22) return 0.62 + 0.38 * u / 0.22;
        if (u < 0.58) return 1;
        double k = (u - 0.58) / 0.42;
        return Math.sqrt(Math.max(0, 1 - k * k)) * 0.92 + 0.08;
    }

    private double spire(boolean red) {
        int height = 64 + random.nextInt(29);
        double base = (red ? 2.7 : 2.3) + random.nextDouble() * 0.8;
        int crownBase = (int) (height * (0.30 + random.nextDouble() * 0.12));
        double widest = 8 + random.nextDouble() * 2.2;
        Radius radius = y -> base + (0.7 - base) * Math.pow(y / height, 1.15);
        trunk(height - 3, radius, 5 + random.nextInt(3), random.nextDouble() * Math.PI * 2, 0.28, 4 + random.nextInt(3), 5, 1.3 + random.nextDouble() * 0.5, red ? 0.02 : 0);
        double angle = random.nextDouble() * Math.PI * 2;
        for (int y = crownBase; y < height - 7; ) {
            double u = (y - crownBase) / (double) (height - crownBase);
            int boughs = u > 0.5 ? 3 : 4;
            for (int i = 0; i < boughs; i++) {
                double a = angle + i * Math.PI * 2 / boughs + (random.nextDouble() - 0.5) * 0.6;
                if (random.nextDouble() < 0.10) continue;
                double from = radius.at(y) - 1, reach = 2.6 + from + widest * Math.pow(1 - u, 0.85) * (0.6 + 0.4 * random.nextDouble());
                // The lowest boughs are ragged, some long and some lost.
                if (u < 0.12) reach = from + (reach - from) * (0.6 + 0.4 * random.nextDouble());
                double droop = 0.10 + 0.18 * random.nextDouble();
                int steps = (int) (reach * 2);
                double[][] path = new double[steps + 1][];
                for (int j = 0; j <= steps; j++) {
                    double q = j / (double) steps, d = from + (reach - from) * q;
                    path[j] = new double[]{d * Math.cos(a), y - droop * (reach - from) * Math.sin(q * Math.PI * 0.85) + 0.8 * q * q, d * Math.sin(a)};
                }
                limb(path, 0.7);
                // Flat pads of foliage along the bough, the widest at its end.
                for (int pad = 0; pad < 3; pad++) {
                    double[] at = path[(int) (steps * (pad == 0 ? 1 : pad == 1 ? 0.62 : 0.3))];
                    double out = Math.hypot(at[0], at[2]);
                    double size = Math.min((2.0 + reach * 0.22) * (pad == 0 ? 1 : pad == 1 ? 0.9 : 0.7) * (0.85 + 0.3 * random.nextDouble()), 15.6 - out);
                    if (size < 1.2 || pad == 2 && out < from + 2.8) continue;
                    billow(at[0], at[1] + 0.6, at[2], size, 1.6, 0.7, 0.38, 2.4);
                }
            }
            angle += 0.9 + random.nextDouble() * 0.5;
            y += 3 + (random.nextBoolean() ? 1 : 0) + (u < 0.3 ? 1 : 0);
        }
        // The spire.
        for (int y = height - 9; y <= height; y++) billow(0, y, 0, 0.6 + 2.6 * (height - y) / 9.0, 0.9, 1, 0.25, 2);
        return base;
    }

    // Wood and foliage ------------------------------------------------------------------------------

    @FunctionalInterface private interface Radius { double at(double y); }

    /**
     * A trunk to {@code height}: round, with {@code flutes} ridges of bark running up it ({@code twist} radians round
     * per block) and darker wood in the furrows between, and at its foot {@code roots} buttresses reaching
     * {@code rootOut} blocks further and dying away by {@code rootHigh}.
     */
    private void trunk(int height, Radius radius, int flutes, double phase, double depth, int roots, int rootHigh, double rootOut, double twist) {
        for (int y = 0; y < height; y++) {
            double r = radius.at(y);
            int reach = (int) Math.ceil(r + rootOut + 1);
            for (int dz = -reach; dz <= reach; dz++) for (int dx = -reach; dx <= reach; dx++) {
                double d = Math.hypot(dx, dz), angle = Math.atan2(dz, dx);
                double ridge = Math.sin(flutes * angle + phase + y * twist), edge = r + depth * ridge;
                if (y < rootHigh) {
                    double lobe = Math.pow(Math.max(0, Math.cos(roots * (angle - phase))), 3), low = 1 - y / (double) rootHigh;
                    edge += rootOut * low * low * (0.25 + 0.75 * lobe);
                }
                if (d > edge) continue;
                blocks.put(BlockPos.asLong(dx, y, dz), ridge < -0.72 && d > edge - 1.05 && r > 2.2 ? furrow : bark);
            }
        }
    }

    /** A limb along a path, {@code thick} blocks in radius where it leaves the trunk and one block wide by its end. */
    private void limb(double[][] path, double thick) {
        for (int i = 0; i < path.length; i++) {
            double r = thick + (0.5 - thick) * i / Math.max(1, path.length - 1);
            int x = (int) Math.round(path[i][0]), y = (int) Math.round(path[i][1]), z = (int) Math.round(path[i][2]);
            if (r < 0.8) { wood(x, y, z); continue; }
            int reach = (int) Math.ceil(r);
            for (int dy = -reach; dy <= reach; dy++) for (int dz = -reach; dz <= reach; dz++) for (int dx = -reach; dx <= reach; dx++)
                if (dx * dx + dy * dy + dz * dz <= r * r + 0.3) wood(x + dx, y + dy, z + dz);
        }
    }

    private void wood(int x, int y, int z) {
        if (y < 0) return;
        long key = BlockPos.asLong(x, y, z);
        if (blocks.get(key) != furrow) blocks.put(key, bark);
    }

    /**
     * A mass of foliage about a point: {@code wide} blocks across in radius and {@code high} above the point, hanging
     * {@code under} of that below it, its surface pushed in and out by smooth noise ({@code rough}, over
     * {@code grain} blocks) so that it billows rather than being a ball.
     */
    private void billow(double cx, double cy, double cz, double wide, double high, double under, double rough, double grain) {
        double below = Math.max(0.8, high * under);
        int x0 = (int) Math.round(cx), y0 = (int) Math.round(cy), z0 = (int) Math.round(cz), reach = (int) wide + 2;
        for (int dy = -(int) Math.ceil(below) - 1; dy <= (int) Math.ceil(high) + 1; dy++) for (int dz = -reach; dz <= reach; dz++) for (int dx = -reach; dx <= reach; dx++) {
            int x = x0 + dx, y = y0 + dy, z = z0 + dz;
            if (y < 1) continue;
            double kx = (x - cx) / wide, kz = (z - cz) / wide, ky = (y - cy) / (y >= cy ? high : below);
            if (kx * kx + kz * kz + ky * ky + rough * noise(x / grain, y / grain, z / grain) <= 1) blocks.putIfAbsent(BlockPos.asLong(x, y, z), LEAVES);
        }
    }

    /** Duff of needles round the foot: podzol with a little coarse dirt, laid on the ground's own height. */
    private void duff(double base) {
        int litter = (int) Math.ceil(base + 5);
        for (int dz = -litter; dz <= litter; dz++) for (int dx = -litter; dx <= litter; dx++) {
            if (Math.hypot(dx, dz) > base + 5 - random.nextDouble() * 2) continue;
            blocks.put(BlockPos.asLong(dx, -1, dz), random.nextInt(6) == 0 ? COARSE_DIRT : PODZOL);
        }
    }

    /** Smooth noise from -1 to 1. */
    private double noise(double x, double y, double z) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double fx = x - x0, fy = y - y0, fz = z - z0, value = 0;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = i >> 1 & 1, dz = i >> 2;
            value += (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz) * corner(x0 + dx, y0 + dy, z0 + dz);
        }
        return value;
    }

    private double corner(int x, int y, int z) {
        int n = x * 374761393 + y * 668265263 + z * 0x7FFFFFFF + salt * 1274126177;
        n = (n ^ n >>> 13) * 1274126177;
        return ((n ^ n >>> 16) & 0xFFFF) / 32767.5 - 1;
    }
}
