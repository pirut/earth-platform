package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.CubeKey;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.BitSet;
import javax.annotation.Nullable;

/**
 * A mesh job's near-cube coverage. First reduce the near cubes to fully covered horizontal
 * voxel footprints, then turn their consecutive vertical runs into voxel-row bits. A voxel
 * and its faces only test a bit, rather than repeating the same cube lookups up to seven times.
 * The extra row and column on every edge include the mesher's neighbour queries. Their drops
 * come from each known cardinal neighbour's {@code side.drops} (falling back to the clamped
 * edge when absent), matching {@link FarMesher#drawn}.
 */
final class FarCoverage {
    private static final int WIDTH = 18, DROP_UNIT = 256;
    private final int level, x0, z0, bottom, rows;
    private final char[] drops;
    @Nullable private final FarMesher.Side[] sides;
    @Nullable private final LongOpenHashSet drawn;
    private final boolean referenceOnly;
    @Nullable private final BitSet[] covered;
    @Nullable private final int[][] cubeSpans;
    @Nullable private final int[][] anyCubeSpans;

    FarCoverage(int level, int x0, int z0, int bottom, int rows, char[] drops, @Nullable LongOpenHashSet drawn) {
        this(level, x0, z0, bottom, rows, drops, null, drawn);
    }

    FarCoverage(int level, int x0, int z0, int bottom, int rows, char[] drops,
                @Nullable FarMesher.Side[] sides, @Nullable LongOpenHashSet drawn) {
        this.level = level;
        this.x0 = x0;
        this.z0 = z0;
        this.bottom = bottom;
        this.rows = rows;
        this.drops = drops;
        this.sides = sides;
        this.drawn = drawn;
        int v = 1 << level, cubes = Math.max(1, v >> 4);
        // Keep the reference's int arithmetic/key wrapping even outside normal world coordinates.
        // The ordinary world fits CubeKey; a job straddling its limits takes the exact slow path.
        referenceOnly = !fits(x0, z0, bottom, rows, v, cubes);
        if (drawn == null || drawn.isEmpty() || referenceOnly) {
            covered = null;
            cubeSpans = null;
            anyCubeSpans = null;
            return;
        }

        int baseX = x0 >> 4, baseZ = z0 >> 4;
        int minX = (x0 - v) >> 4, maxX = (x0 + 16 * v) >> 4;
        int minZ = (z0 - v) >> 4, maxZ = (z0 + 16 * v) >> 4;
        Long2ObjectOpenHashMap<IntArrayList> columns = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<IntArrayList> anyColumns = null;
        if (cubes == 1) {
            for (LongIterator it = drawn.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                int x = CubeKey.x(key), z = CubeKey.z(key);
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) add(columns, x, CubeKey.y(key), z);
            }
        } else {
            Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
            for (LongIterator it = drawn.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                int x = baseX + Math.floorDiv(CubeKey.x(key) - baseX, cubes) * cubes;
                int z = baseZ + Math.floorDiv(CubeKey.z(key) - baseZ, cubes) * cubes;
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) counts.addTo(CubeKey.pack(x, CubeKey.y(key), z), 1);
            }
            if (!counts.isEmpty()) {
                anyColumns = new Long2ObjectOpenHashMap<>();
                int full = cubes * cubes;
                for (var entry : counts.long2IntEntrySet()) {
                    long key = entry.getLongKey();
                    int cx = CubeKey.x(key), cy = CubeKey.y(key), cz = CubeKey.z(key);
                    add(anyColumns, cx, cy, cz);
                    if (entry.getIntValue() == full) add(columns, cx, cy, cz);
                }
            }
        }
        if (columns.isEmpty() && (anyColumns == null || anyColumns.isEmpty())) {
            covered = null;
            cubeSpans = null;
            anyCubeSpans = null;
            return;
        }

        if (columns.isEmpty()) {
            covered = null;
            cubeSpans = null;
        } else {
            Long2ObjectOpenHashMap<int[]> runs = new Long2ObjectOpenHashMap<>();
            for (var entry : columns.long2ObjectEntrySet()) runs.put(entry.getLongKey(), runs(entry.getValue()));
            covered = new BitSet[WIDTH * WIDTH];
            cubeSpans = new int[WIDTH * WIDTH][];
            long baseY = bottom * (16L * v);
            for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) {
                int[] spans = runs.get(CubeKey.column((x0 + x * v) >> 4, (z0 + z * v) >> 4));
                if (spans == null) continue;
                int idx = (z + 1) * WIDTH + x + 1;
                cubeSpans[idx] = spans;
                int drop = FarMesher.drop(drops, sides, x, z);
                int ceilDrop = (drop + DROP_UNIT - 1) / DROP_UNIT, floorDrop = drop / DROP_UNIT;
                BitSet bits = null;
                for (int i = 0; i < spans.length; i += 2) {
                    // Both ends of the lowered voxel must fit inside one uninterrupted run of
                    // covered cube rows. A fractional drop can make it touch an extra lower cube.
                    long low = Math.ceilDiv(spans[i] * 16L + ceilDrop - baseY, v);
                    long high = Math.floorDiv(spans[i + 1] * 16L + 15 + floorDrop - (v - 1) - baseY, v);
                    int from = (int) Math.max(-1, low), to = (int) Math.min(rows, high);
                    if (from > to) continue;
                    if (bits == null) bits = new BitSet(rows + 2);
                    bits.set(from + 1, to + 2);
                }
                covered[idx] = bits;
            }
        }

        if (cubes == 1) {
            anyCubeSpans = cubeSpans;
        } else if (anyColumns == null || anyColumns.isEmpty()) {
            anyCubeSpans = null;
        } else {
            Long2ObjectOpenHashMap<int[]> anyRuns = new Long2ObjectOpenHashMap<>();
            for (var entry : anyColumns.long2ObjectEntrySet()) anyRuns.put(entry.getLongKey(), runs(entry.getValue()));
            anyCubeSpans = new int[WIDTH * WIDTH][];
            for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) {
                int[] spans = anyRuns.get(CubeKey.column((x0 + x * v) >> 4, (z0 + z * v) >> 4));
                if (spans != null) anyCubeSpans[(z + 1) * WIDTH + x + 1] = spans;
            }
        }
    }

    boolean drawn(int x, int vy, int z) {
        if (drawn == null || drawn.isEmpty()) return false;
        if (referenceOnly || x < -1 || x > 16 || z < -1 || z > 16 || vy < -1 || vy > rows) return reference(x, vy, z);
        if (covered == null) return false;
        BitSet bits = covered[(z + 1) * WIDTH + x + 1];
        return bits != null && bits.get(vy + 1);
    }

    boolean cubeDrawn(int x, int cy, int z) {
        if (drawn == null || drawn.isEmpty()) return false;
        if (referenceOnly || x < -1 || x > 16 || z < -1 || z > 16) return cubeReference(x, cy, z);
        if (cubeSpans == null) return false;
        return containsSpan(cubeSpans[(z + 1) * WIDTH + x + 1], cy);
    }

    boolean anyCubeDrawn(int x, int cy, int z) {
        if (drawn == null || drawn.isEmpty()) return false;
        if (referenceOnly || x < -1 || x > 16 || z < -1 || z > 16) return anyCubeReference(x, cy, z);
        if (anyCubeSpans == null) return false;
        return containsSpan(anyCubeSpans[(z + 1) * WIDTH + x + 1], cy);
    }

    private static boolean containsSpan(@Nullable int[] spans, int cy) {
        if (spans == null) return false;
        for (int i = 0; i < spans.length; i += 2) {
            if (cy < spans[i]) return false;
            if (cy <= spans[i + 1]) return true;
        }
        return false;
    }

    private static void add(Long2ObjectOpenHashMap<IntArrayList> columns, int x, int y, int z) {
        columns.computeIfAbsent(CubeKey.column(x, z), unused -> new IntArrayList()).add(y);
    }

    /** Inclusive first/last cube rows of each uninterrupted covered run. */
    private static int[] runs(IntArrayList ys) {
        int[] sorted = ys.toIntArray();
        Arrays.sort(sorted);
        IntArrayList spans = new IntArrayList();
        int start = sorted[0], end = start;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] == end + 1) end = sorted[i];
            else { spans.add(start); spans.add(end); start = end = sorted[i]; }
        }
        spans.add(start);
        spans.add(end);
        return spans.toIntArray();
    }

    private static boolean fits(int x0, int z0, int bottom, int rows, int v, int cubes) {
        long minX = Math.floorDiv((long) x0 - v, 16), maxX = Math.floorDiv((long) x0 + 16L * v, 16) + cubes - 1;
        long minZ = Math.floorDiv((long) z0 - v, 16), maxZ = Math.floorDiv((long) z0 + 16L * v, 16) + cubes - 1;
        long baseY = bottom * (16L * v), minY = Math.floorDiv(baseY - v - 256, 16);
        long maxY = Math.floorDiv(baseY + (rows + 1L) * v, 16);
        return minX >= -(1 << 21) && maxX < (1 << 21) && minZ >= -(1 << 21) && maxZ < (1 << 21)
                && minY >= -(1 << 19) && maxY < (1 << 19);
    }

    /** The original loop, for unusual queries outside the precomputed mesher footprint. */
    private boolean reference(int x, int vy, int z) {
        int v = 1 << level;
        int drop = FarMesher.drop(drops, sides, x, z);
        int bx = x0 + x * v, by = bottom * (16 << level) + vy * v, bz = z0 + z * v;
        int low = by - (drop + DROP_UNIT - 1) / DROP_UNIT, high = by + v - 1 - drop / DROP_UNIT, cubes = Math.max(1, v >> 4);
        for (int cy = low >> 4; cy <= high >> 4; cy++) for (int cz = bz >> 4; cz < (bz >> 4) + cubes; cz++)
            for (int cx = bx >> 4; cx < (bx >> 4) + cubes; cx++) if (!drawn.contains(CubeKey.pack(cx, cy, cz))) return false;
        return true;
    }

    private boolean cubeReference(int x, int cy, int z) {
        int v = 1 << level, cubes = Math.max(1, v >> 4);
        int bx = x0 + x * v, bz = z0 + z * v;
        for (int cz = bz >> 4; cz < (bz >> 4) + cubes; cz++)
            for (int cx = bx >> 4; cx < (bx >> 4) + cubes; cx++)
                if (!drawn.contains(CubeKey.pack(cx, cy, cz))) return false;
        return true;
    }

    private boolean anyCubeReference(int x, int cy, int z) {
        int v = 1 << level, cubes = Math.max(1, v >> 4);
        int bx = x0 + x * v, bz = z0 + z * v;
        for (int cz = bz >> 4; cz < (bz >> 4) + cubes; cz++)
            for (int cx = bx >> 4; cx < (bx >> 4) + cubes; cx++)
                if (drawn.contains(CubeKey.pack(cx, cy, cz))) return true;
        return false;
    }
}
