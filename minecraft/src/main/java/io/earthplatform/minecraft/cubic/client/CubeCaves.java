package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.CubeKey;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;

/**
 * Cave culling for near cubes, as vanilla culls its sections: which cubes the camera can see into through open faces.
 * Each cube's mesh notes which of its six faces its air (anything not a full opaque block) joins to which others
 * ({@link #of}). A search from the camera's cube steps into a neighbour only through a face the cube it is in joins to
 * the face it came in by, never back toward the camera along any direction it has already stepped, and past
 * {@link #CHECK_FROM} blocks only where the line from the neighbour back to the camera runs through cubes it has
 * reached. Cubes it never reaches are hidden behind rock from every point of the camera's cube, so they are not drawn
 * in the view (the shadow pass still draws them all).
 *
 * <p>Cubes not loaded, or not meshed yet, count as open: what the search knows nothing of never hides anything. With
 * {@code -Dearthplatform.cubes.caveCull=false} every cube in the frustum is drawn, as before.
 */
final class CubeCaves {
    static final boolean ON = !"false".equals(System.getProperty("earthplatform.cubes.caveCull"));
    /** Every face joined to every face (and to itself): air, or a cube not meshed yet. */
    static final long OPEN = (1L << 36) - 1;
    /** Blocks from the camera past which a step must also see back to the camera through reached cubes (vanilla's 60). */
    static final int CHECK_FROM = 60;
    /** {@code -Dearthplatform.cubes.caveLine=false} leaves out the look back to the camera past {@link #CHECK_FROM}. */
    static final boolean LINE = !"false".equals(System.getProperty("earthplatform.cubes.caveLine"));
    private static final Direction[] DIRECTIONS = Direction.values();

    private CubeCaves() {}

    /** Bit {@code from * 6 + to} for each pair of faces (by {@link Direction#ordinal}) the cube's air joins. */
    static long of(VisibilitySet set) {
        long bits = 0;
        for (Direction from : DIRECTIONS) for (Direction to : DIRECTIONS) {
            if (set.visibilityBetween(from, to)) bits |= 1L << (from.ordinal() * 6 + to.ordinal());
        }
        return bits;
    }

    static boolean joins(long visibility, int from, int to) { return (visibility >>> (from * 6 + to) & 1) != 0; }

    private static final int[] DX = new int[6], DY = new int[6], DZ = new int[6], OPPOSITE = new int[6];
    static {
        for (Direction d : DIRECTIONS) {
            DX[d.ordinal()] = d.getStepX(); DY[d.ordinal()] = d.getStepY(); DZ[d.ordinal()] = d.getStepZ();
            OPPOSITE[d.ordinal()] = d.getOpposite().ordinal();
        }
    }

    /**
     * The cubes the camera at block ({@code camX}, {@code camY}, {@code camZ}) can see into, searching only inside the
     * cube box {@code [minX..maxX] x [minY..maxY] x [minZ..maxZ]}; null when the camera's cube lies outside it (high
     * above the loaded cubes, say), where nothing is culled. {@code visibility} holds each meshed cube's faces; cubes
     * missing from it are open.
     */
    static LongOpenHashSet reach(double camX, double camY, double camZ, Long2LongOpenHashMap visibility,
                                 int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int sx = (int) Math.floor(camX) >> 4, sy = (int) Math.floor(camY) >> 4, sz = (int) Math.floor(camZ) >> 4;
        if (sx < minX || sx > maxX || sy < minY || sy > maxY || sz < minZ || sz > maxZ) return null;
        int width = maxX - minX + 1, height = maxY - minY + 1, depth = maxZ - minZ + 1;
        int capacity = width * height * depth;
        LongOpenHashSet reached = new LongOpenHashSet(Math.min(capacity, 1 << 16));
        // Breadth first, so each cube is reached by its shortest path, as vanilla's search does.
        long[] keys = new long[Math.min(capacity, 1 << 16)];
        byte[] entered = new byte[keys.length], travelled = new byte[keys.length];
        int head = 0, tail = 0;
        long start = CubeKey.pack(sx, sy, sz);
        reached.add(start);
        keys[tail] = start; entered[tail] = -1; travelled[tail] = 0; tail++;
        long checkFrom = (long) CHECK_FROM * CHECK_FROM;
        while (head < tail) {
            long key = keys[head];
            int from = entered[head], mask = travelled[head];
            head++;
            int x = CubeKey.x(key), y = CubeKey.y(key), z = CubeKey.z(key);
            long faces = from < 0 ? OPEN : visibility.getOrDefault(key, OPEN);
            for (int d = 0; d < 6; d++) {
                // Never back toward the camera along a direction already stepped.
                if ((mask & 1 << OPPOSITE[d]) != 0) continue;
                // Out through face d only if the air joins it to the face this cube was entered by.
                if (from >= 0 && !joins(faces, OPPOSITE[from], d)) continue;
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) continue;
                long next = CubeKey.pack(nx, ny, nz);
                if (reached.contains(next)) continue;
                double cx = (nx << 4) + 8 - camX, cy = (ny << 4) + 8 - camY, cz = (nz << 4) + 8 - camZ;
                if (LINE && cx * cx + cy * cy + cz * cz > checkFrom && !seesBack(nx, ny, nz, sx, sy, sz, camX, camY, camZ, reached)) continue;
                reached.add(next);
                if (tail == keys.length) {
                    int grown = Math.min(capacity, keys.length * 2);
                    keys = java.util.Arrays.copyOf(keys, grown);
                    entered = java.util.Arrays.copyOf(entered, grown);
                    travelled = java.util.Arrays.copyOf(travelled, grown);
                }
                keys[tail] = next; entered[tail] = (byte) d; travelled[tail] = (byte) (mask | 1 << d); tail++;
            }
        }
        return reached;
    }

    /**
     * Whether the line from cube (x, y, z)'s middle back to the camera runs only through cubes already reached, looked
     * at every half cube along it (vanilla looks along it the same way, in its own steps). A cube past a wall the search
     * went round, down a long tunnel, fails it: the wall stands between it and the camera.
     */
    private static boolean seesBack(int x, int y, int z, int sx0, int sy0, int sz0, double camX, double camY, double camZ,
                                    LongOpenHashSet reached) {
        // The search goes out a step at a time, so every cube fewer steps from the camera's than this one has been
        // reached already if it ever will be; those as many steps away or more may not have been yet, and are not asked.
        int steps0 = Math.abs(x - sx0) + Math.abs(y - sy0) + Math.abs(z - sz0);
        double px = (x << 4) + 8, py = (y << 4) + 8, pz = (z << 4) + 8;
        double dx = camX - px, dy = camY - py, dz = camZ - pz;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) (length / 8.0);
        double sx = dx / length * 8.0, sy = dy / length * 8.0, sz = dz / length * 8.0;
        for (int i = 1; i < steps; i++) {
            px += sx; py += sy; pz += sz;
            int qx = (int) Math.floor(px) >> 4, qy = (int) Math.floor(py) >> 4, qz = (int) Math.floor(pz) >> 4;
            if (Math.abs(qx - sx0) + Math.abs(qy - sy0) + Math.abs(qz - sz0) >= steps0) continue;
            if (!reached.contains(CubeKey.pack(qx, qy, qz))) return false;
        }
        return true;
    }

    /** Whether the box {@code [x0..x1] x [y0..y1] x [z0..z1]}, slid any distance along (sx, sy, sz), meets {@code into}. */
    static boolean shadowReaches(double x0, double y0, double z0, double x1, double y1, double z1, double sx, double sy, double sz, double[] into) {
        double from = 0.0, to = Double.POSITIVE_INFINITY;
        double[] low = {x0, y0, z0}, high = {x1, y1, z1}, slide = {sx, sy, sz};
        for (int axis = 0; axis < 3; axis++) {
            // low + t * slide <= into's high, and high + t * slide >= into's low.
            double a = into[axis + 3] - low[axis], b = into[axis] - high[axis], d = slide[axis];
            if (Math.abs(d) < 1.0e-9) {
                if (a < 0.0 || b > 0.0) return false;
            } else {
                double t0 = b / d, t1 = a / d;
                from = Math.max(from, Math.min(t0, t1));
                to = Math.min(to, Math.max(t0, t1));
                if (from > to) return false;
            }
        }
        return true;
    }
}
