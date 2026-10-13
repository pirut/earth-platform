package io.earthplatform.minecraft.cubic.gen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MangroveRootsBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One tree as block offsets from its origin, the first block above the ground its trunk stands
 * on, placeable in any of four quarter turns. Blocks below the origin are ground the tree changed
 * (dirt under the trunk, podzol, roots in mud): they follow each column's own ground height, so a
 * tree grown on flat ground sits on any slope. Above it, trunks (logs, stems, roots) replace what
 * is there and crowns (leaves, vines, cocoa, propagules, nests) only fill open space.
 */
final class TreeShape {
    static final byte GROUND = 0, TRUNK = 1, CROWN = 2;

    /** Receives a tree's blocks. */
    interface Sink { void put(int x, int y, int z, BlockState state, byte kind); }

    private static final BlockState NEST = Blocks.BEE_NEST.defaultBlockState().setValue(BeehiveBlock.FACING, Direction.SOUTH);
    private static final Rotation[] TURNS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};

    private final int[] at;
    private final short[] look;
    private final BlockState[][] palette;
    private final byte[] kinds;
    final int minY, maxY;
    private final int[] minX = new int[4], maxX = new int[4], minZ = new int[4], maxZ = new int[4];
    /** The chance of a bee nest, and the packed spots one can take in each quarter turn. */
    private final float bees;
    private final int[][] nests = new int[4][];

    private static int pack(int dx, int dy, int dz) { return (dx + 512) | (dz + 512) << 10 | (dy + 512) << 20; }
    private static int dx(int p) { return (p & 1023) - 512; }
    private static int dz(int p) { return (p >> 10 & 1023) - 512; }
    private static int dy(int p) { return (p >> 20 & 1023) - 512; }
    /** An offset turned clockwise by {@code turn} quarters, as {@link Rotation} turns facings. */
    private static int turnX(int turn, int dx, int dz) { return switch (turn) { case 1 -> -dz; case 2 -> -dx; case 3 -> dz; default -> dx; }; }
    private static int turnZ(int turn, int dx, int dz) { return switch (turn) { case 1 -> dx; case 2 -> -dz; case 3 -> -dx; default -> dz; }; }

    /** A shape from blocks keyed by packed offset ({@link BlockPos#asLong}), with a chance of a bee nest on its trunk. */
    TreeShape(Long2ObjectMap<BlockState> blocks, float bees) {
        this.bees = bees;
        at = new int[blocks.size()];
        look = new short[blocks.size()];
        Object2IntOpenHashMap<BlockState> index = new Object2IntOpenHashMap<>();
        List<BlockState> states = new ArrayList<>();
        int lowY = Integer.MAX_VALUE, highY = Integer.MIN_VALUE, lowX = Integer.MAX_VALUE, highX = Integer.MIN_VALUE,
                lowZ = Integer.MAX_VALUE, highZ = Integer.MIN_VALUE, i = 0;
        int lowestLeaf = Integer.MAX_VALUE, lowestLog = Integer.MAX_VALUE;
        IntOpenHashSet taken = new IntOpenHashSet();
        IntArrayList logs = new IntArrayList();
        for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            BlockState state = entry.getValue();
            int id = index.computeIfAbsent(state, s -> { states.add((BlockState) s); return states.size() - 1; });
            at[i] = pack(x, y, z);
            look[i++] = (short) id;
            lowY = Math.min(lowY, y); highY = Math.max(highY, y);
            lowX = Math.min(lowX, x); highX = Math.max(highX, x);
            lowZ = Math.min(lowZ, z); highZ = Math.max(highZ, z);
            if (y < 0) continue;
            taken.add(pack(x, y, z));
            if (state.getBlock() instanceof LeavesBlock) lowestLeaf = Math.min(lowestLeaf, y);
            else if (state.getBlock() instanceof RotatedPillarBlock) { lowestLog = Math.min(lowestLog, y); logs.add(pack(x, y, z)); }
        }
        minY = lowY; maxY = highY;
        palette = new BlockState[4][states.size()];
        kinds = new byte[states.size()];
        for (int s = 0; s < states.size(); s++) {
            BlockState state = states.get(s);
            for (int turn = 0; turn < 4; turn++) palette[turn][s] = state.rotate(TURNS[turn]);
            Block block = state.getBlock();
            kinds[s] = block instanceof RotatedPillarBlock || block == Blocks.MUSHROOM_STEM || block instanceof MangroveRootsBlock ? TRUNK : CROWN;
        }
        for (int turn = 0; turn < 4; turn++) {
            int ax = turnX(turn, lowX, lowZ), bx = turnX(turn, highX, highZ), az = turnZ(turn, lowX, lowZ), bz = turnZ(turn, highX, highZ);
            minX[turn] = Math.min(ax, bx); maxX[turn] = Math.max(ax, bx);
            minZ[turn] = Math.min(az, bz); maxZ[turn] = Math.max(az, bz);
        }
        // As vanilla's beehive decorator: beside a log just under the crown, on a side other than
        // north, facing south with open air in front.
        int nestY = lowestLeaf == Integer.MAX_VALUE ? lowestLog + 1 : Math.max(lowestLeaf - 1, lowestLog + 1);
        for (int turn = 0; turn < 4; turn++) {
            if (bees <= 0) { nests[turn] = new int[0]; continue; }
            IntOpenHashSet turned = new IntOpenHashSet();
            for (int p : taken) turned.add(pack(turnX(turn, dx(p), dz(p)), dy(p), turnZ(turn, dx(p), dz(p))));
            IntArrayList spots = new IntArrayList();
            for (int p : logs) {
                if (dy(p) != nestY) continue;
                int x = turnX(turn, dx(p), dz(p)), z = turnZ(turn, dx(p), dz(p));
                for (Direction side : new Direction[]{Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                    int sx = x + side.getStepX(), sz = z + side.getStepZ();
                    if (!turned.contains(pack(sx, nestY, sz)) && !turned.contains(pack(sx, nestY, sz + 1))) spots.add(pack(sx, nestY, sz));
                }
            }
            nests[turn] = spots.toIntArray();
        }
    }

    int blocks() { return at.length; }

    /**
     * Writes the blocks that fall in the box (x0..x1, y0..y1, z0..z1) for a tree whose origin is
     * at (ox, oy, oz), turned {@code turn} quarters; {@code ground} gives a column's ground height.
     * {@code seed} decides the bee nest.
     */
    void place(int ox, int oy, int oz, int turn, long seed, int x0, int y0, int z0, int x1, int y1, int z1,
               IntBinaryOperator ground, Sink sink) {
        if (ox + maxX[turn] < x0 || ox + minX[turn] > x1 || oz + maxZ[turn] < z0 || oz + minZ[turn] > z1) return;
        if (oy + maxY < y0 || oy + minY - 8 > y1) return;
        BlockState[] states = palette[turn];
        for (int i = 0; i < at.length; i++) {
            int p = at[i], dx = dx(p), dy = dy(p), dz = dz(p);
            int x = ox + turnX(turn, dx, dz), z = oz + turnZ(turn, dx, dz);
            if (x < x0 || x > x1 || z < z0 || z > z1) continue;
            int y;
            byte kind;
            if (dy < 0) {
                kind = GROUND;
                y = ground.applyAsInt(x, z) + 1 + dy;
                // Ground well off the origin's height is a drop or a wall beside the tree, not under it.
                if (Math.abs(y - (oy + dy)) > 3) continue;
            } else {
                kind = kinds[look[i]];
                y = oy + dy;
            }
            if (y >= y0 && y <= y1) sink.put(x, y, z, states[look[i]], kind);
        }
        int[] spots = nests[turn];
        if (bees > 0 && spots.length > 0 && (seed >>> 40 & 0xFFFFFF) < bees * 0x1000000) {
            int spot = spots[(int) Math.floorMod(seed >>> 8, spots.length)];
            int x = ox + dx(spot), y = oy + dy(spot), z = oz + dz(spot);
            if (x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1) sink.put(x, y, z, NEST, CROWN);
        }
    }
}
