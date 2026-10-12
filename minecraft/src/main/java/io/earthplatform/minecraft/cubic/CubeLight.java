package io.earthplatform.minecraft.cubic;

import java.util.Arrays;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Lights one cube from its 48-block neighbourhood: skylight above each column's sky top, block
 * light from emitters, both spread breadth-first. The server and the client mesher share it so
 * gameplay and rendering agree. Safe on worker threads; it only reads copied containers.
 */
public final class CubeLight {
    public static final int SIZE = 48, AREA = SIZE * SIZE, VOLUME = AREA * SIZE;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final ThreadLocal<Field> FIELDS = ThreadLocal.withInitial(Field::new);

    private CubeLight() {}

    /** Per-thread scratch for one neighbourhood: states and both light layers. */
    public static final class Field {
        public final BlockState[] states = new BlockState[VOLUME];
        public final byte[] sky = new byte[VOLUME], block = new byte[VOLUME];
        private int[] queue = new int[VOLUME];
    }

    public static int index(int x, int y, int z) { return (y * SIZE + z) * SIZE + x; }

    /**
     * Lights the neighbourhood of a cube at cube Y {@code cy}. {@code states} holds the 27 cubes
     * ordered y, then z, then x (missing cubes read as air); {@code tops} the 9 columns' sky tops.
     */
    public static Field light(@Nullable PalettedContainer<BlockState>[] states, @Nullable int[][] tops, int cy) {
        Field field = FIELDS.get();
        fill(field, states);
        light(field, tops, cy);
        return field;
    }

    /** The centre cube's light as {@code sky << 4 | block}, indexed {@code y << 8 | z << 4 | x}. */
    public static byte[] centre(Field field) {
        byte[] light = new byte[4096];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = index(x + 16, y + 16, z + 16);
            light[(y << 8) | (z << 4) | x] = (byte) (field.sky[i] << 4 | field.block[i]);
        }
        return light;
    }

    private static void fill(Field field, @Nullable PalettedContainer<BlockState>[] containers) {
        BlockState[] states = field.states;
        for (int c = 0; c < 27; c++) {
            PalettedContainer<BlockState> container = containers[c];
            int ox = (c % 3) * 16, oz = (c / 3 % 3) * 16, oy = (c / 9) * 16;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                int base = index(ox, oy + y, oz + z);
                if (container == null) { Arrays.fill(states, base, base + 16, AIR); continue; }
                for (int x = 0; x < 16; x++) states[base + x] = container.get(x, y, z);
            }
        }
    }

    /** How much light a block takes on top of the one level every step costs. */
    public static int opacity(BlockState state) {
        return state.isAir() ? 0 : state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static void light(Field field, @Nullable int[][] tops, int cy) {
        byte[] sky = field.sky, block = field.block;
        Arrays.fill(sky, (byte) 0);
        Arrays.fill(block, (byte) 0);
        int baseY = (cy - 1) << 4;
        for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
            int[] column = tops[(z >> 4) * 3 + (x >> 4)];
            int top = column == null ? CubeWorld.MIN_Y - 1 : column[(z & 15) << 4 | (x & 15)];
            for (int y = SIZE - 1; y >= 0 && baseY + y > top; y--) sky[index(x, y, z)] = 15;
        }
        int tail = 0;
        for (int i = 0; i < VOLUME; i++) {
            if (sky[i] == 15 && hasDarkNeighbour(sky, i)) field.queue[tail++] = i;
        }
        spread(field, sky, tail);
        tail = 0;
        for (int i = 0; i < VOLUME; i++) {
            int emission = field.states[i].getLightEmission();
            if (emission > 0) { block[i] = (byte) emission; field.queue[tail++] = i; }
        }
        spread(field, block, tail);
    }

    private static boolean hasDarkNeighbour(byte[] light, int i) {
        int x = i % SIZE, z = i / SIZE % SIZE, y = i / AREA;
        return (y > 0 && light[i - AREA] < 15) || (x > 0 && light[i - 1] < 15) || (x < SIZE - 1 && light[i + 1] < 15)
                || (z > 0 && light[i - SIZE] < 15) || (z < SIZE - 1 && light[i + SIZE] < 15) || (y < SIZE - 1 && light[i + AREA] < 15);
    }

    /** Breadth-first spread; every step costs at least one level, more through translucent blocks. */
    private static void spread(Field field, byte[] light, int tail) {
        int head = 0;
        while (head < tail) {
            int i = field.queue[head++];
            int level = light[i];
            if (level <= 1) continue;
            int x = i % SIZE, z = i / SIZE % SIZE, y = i / AREA;
            for (int d = 0; d < 6; d++) {
                int n;
                switch (d) {
                    case 0 -> { if (x == 0) continue; n = i - 1; }
                    case 1 -> { if (x == SIZE - 1) continue; n = i + 1; }
                    case 2 -> { if (z == 0) continue; n = i - SIZE; }
                    case 3 -> { if (z == SIZE - 1) continue; n = i + SIZE; }
                    case 4 -> { if (y == 0) continue; n = i - AREA; }
                    default -> { if (y == SIZE - 1) continue; n = i + AREA; }
                }
                int next = level - Math.max(1, opacity(field.states[n]));
                if (next > light[n]) {
                    light[n] = (byte) next;
                    if (tail == field.queue.length) field.queue = Arrays.copyOf(field.queue, field.queue.length * 2);
                    field.queue[tail++] = n;
                }
            }
        }
    }
}
