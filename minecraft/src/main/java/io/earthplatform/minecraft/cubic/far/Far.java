package io.earthplatform.minecraft.cubic.far;

import java.util.function.LongConsumer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Far terrain as a voxel pyramid, the way Voxy draws distance: level L holds 16^3-voxel sections
 * whose voxels are 2^L blocks, stacked in columns. Every level keeps a square of columns around
 * the player and the next finer level replaces its middle, so voxels grow with distance. With the
 * default detail each level reaches about 370 of its voxels from the player before the next
 * coarser one takes over, so a voxel never covers more than a few pixels, as Voxy aims for, and
 * the coarsest level (256-block voxels) reaches about 94 km of blocks, so a range like the Alaska
 * Range can stand on the horizon from 40 km away even where the map stretches each kilometre over
 * nearly two thousand blocks.
 *
 * <p>Each voxel column of a far column also carries a drop: how far its whole stack sits below the
 * voxel grid, so the top of its ground or water lands at the real height instead of a voxel
 * boundary, as Distant Horizons keeps exact heights. A water voxel's level property can still say
 * how full it is (level k above 0 fills the bottom (16 - k) / 16 of the voxel), for water that
 * stands lower than the column's top.
 */
public final class Far {
    public static final int MIN_LEVEL = 1, MAX_LEVEL = 8, SIZE = 16;
    /** Most detail a client may ask for, in {@link #HOLE} columns. */
    public static final int MAX_HOLE = 24;
    /**
     * Half-width, in columns, of the middle of each level that the next finer level takes over:
     * how many voxels each level keeps before coarser ones take over, so how many pixels a voxel
     * covers on screen. Set with {@code -Dearthplatform.far.detail} (4 to 24, default 11).
     */
    public static final int HOLE = Math.clamp(Integer.getInteger("earthplatform.far.detail", 11), 4, MAX_HOLE);
    /** Half-width of the coarsest level's square. */
    public static final int OUTER = 2 * HOLE + 1;
    /** How far a request may stray from the player, in columns of its level, before the server ignores it; covers every detail setting. */
    public static final int SLACK = 2 * MAX_HOLE + 3;
    /** Drops are kept in 1/256ths of a block. */
    public static final int DROP_UNIT = 256;
    /** Levels whose voxels are small enough (16 blocks or less) to show what players built. */
    public static final int EDIT_LEVELS = 4;
    /** Most sections one column may hold. */
    public static final int MAX_SECTIONS = 64;

    private Far() {}

    /** Distance to the nearest edge of the coarsest level's square, in blocks. */
    public static float reach() { return OUTER * (float) width(MAX_LEVEL); }

    public static int voxel(int level) { return 1 << level; }
    public static int width(int level) { return SIZE << level; }
    /** Column (or section) coordinate holding a block coordinate. */
    public static int column(int block, int level) { return block >> (level + 4); }

    public static long key(int level, int x, int z) {
        return (long) level << 60 | (x & 0x3FFFFFFFL) << 30 | (z & 0x3FFFFFFFL);
    }
    public static int level(long key) { return (int) (key >>> 60); }
    public static int x(long key) { return (int) (key << 4 >> 34); }
    public static int z(long key) { return (int) (key << 34 >> 34); }
    public static boolean valid(long key) { int level = level(key); return level >= MIN_LEVEL && level <= MAX_LEVEL; }

    /** Height of a still water block's surface within it, as the block renderer draws it. */
    public static final float WATER_SURFACE = 8.0F / 9.0F;

    /** A water voxel whose bottom {@code blocks} of {@code voxel} are under water, or null when too little is to show. */
    public static BlockState water(float blocks, int voxel) {
        int sixteenths = Math.min(16, Math.round(blocks * 16.0F / voxel));
        return sixteenths <= 0 ? null : Water.FILLED[sixteenths];
    }

    /** How much of a voxel's height a state fills: all of it, except for part-filled liquid voxels. */
    public static float fill(BlockState state) {
        if (!(state.getBlock() instanceof LiquidBlock)) return 1.0F;
        int level = state.getValue(LiquidBlock.LEVEL);
        return level == 0 || level > 15 ? 1.0F : (16 - level) / 16.0F;
    }

    private static final class Water {
        static final BlockState[] FILLED = new BlockState[17];
        static {
            for (int sixteenths = 1; sixteenths <= 16; sixteenths++) {
                FILLED[sixteenths] = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 16 - sixteenths & 15);
            }
        }
    }

    /** Every column kept around a block position, coarsest first, including the middles finer levels replace. */
    public static void around(int bx, int bz, LongConsumer out) { around(bx, bz, MIN_LEVEL, out); }

    /** The same down to level {@code finest} only, as for a player too high or fast for the finer ones to matter or keep up. */
    public static void around(int bx, int bz, int finest, LongConsumer out) {
        for (int level = MAX_LEVEL; level >= finest; level--) {
            int minX, minZ, maxX, maxZ;
            if (level == MAX_LEVEL) {
                int px = column(bx, level), pz = column(bz, level);
                minX = px - OUTER; maxX = px + OUTER; minZ = pz - OUTER; maxZ = pz + OUTER;
            } else {
                int px = 2 * column(bx, level + 1), pz = 2 * column(bz, level + 1);
                minX = px - 2 * HOLE; maxX = px + 2 * HOLE + 1; minZ = pz - 2 * HOLE; maxZ = pz + 2 * HOLE + 1;
            }
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) out.accept(key(level, x, z));
        }
    }

    /** True for the middle columns of a level around a position, which the next finer level draws instead. */
    public static boolean refined(int level, int x, int z, int bx, int bz) { return refined(level, x, z, bx, bz, MIN_LEVEL); }

    /** The same when level {@code finest} is the finest one drawn. */
    public static boolean refined(int level, int x, int z, int bx, int bz, int finest) {
        return level > finest && Math.abs(x - column(bx, level)) <= HOLE && Math.abs(z - column(bz, level)) <= HOLE;
    }

    /** True when a column is close enough to a position to be worth making for it. */
    public static boolean near(long key, int bx, int bz) {
        int level = level(key);
        return Math.abs(x(key) - column(bx, level)) <= SLACK && Math.abs(z(key) - column(bz, level)) <= SLACK;
    }
}
