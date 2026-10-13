package io.earthplatform.minecraft.cubic.gen;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Hills that stand alone and sheer, which the elevation tiles are too coarse to hold: tower karst
 * as in Ha Long Bay, Phang Nga, Guilin, Trang An and Viñales, sea stacks, granite domes such as
 * the Sugarloaf, and desert buttes such as Monument Valley's. They rise a hundred or two metres from flat ground or the sea, about as wide as
 * they are tall, where the tiles have them as rounded cones. Nothing marks where they are but
 * their shape in the tiles: a hill at least {@value #TALL_RISE} m over the ground round it, narrow
 * for its height, standing on ground that lies low all round it and over much of the land round
 * it. Rolling hills, mountain ranges, volcanic cones, knolls among gullies and a range's edge
 * beside a plain all fail one of those, and stay as they are.
 * Such a hill takes a steep profile between the ground round it and its top.
 *
 * <p>Clusters the tiles merge into one broad hill, as many of Guilin's, and pillars the tiles do not
 * hold at all, as Zhangjiajie's among their gorges, show no such shape, so they keep the tiles' form.
 */
public final class Towers {
    /** What the towers need from the world: an elevation model fine enough to see a tower, and the map projection's scale. */
    public interface Globe {
        /** Ground height in metres above the sea at a block position. */
        double metres(double x, double z);
        double metresPerBlock(double z);
    }

    /** Blocks across a cell of the top and base envelopes, and of the survey for hilltops. */
    private static final int TOP_CELL = 16, BASE_CELL = 64, HILL_CELL = 64;
    /** Blocks across a cell of the field of how much the hills round a column are towers. */
    private static final int FIELD_CELL = 16;
    /** Hill cells across a block whose towers are listed together, so the field looks up a few blocks rather than every cell near it. */
    private static final int BLOCK_CELLS = 4;
    /** Metres round a column within which its top, and the low ground at its foot, are sought. */
    private static final double TOP_REACH = 90, BASE_REACH = 260;
    /** Rise in metres from base to top under which hills stay as they are, and over which they are towers. */
    private static final double LOW_RISE = 25, TALL_RISE = 50;
    /** Share of a tower's rise up to which its foot climbs gently before its walls. */
    private static final double WALL = 0.27;
    /**
     * Metres round a hilltop surveyed for its shape: its base is the lowest ground within it, its
     * width its area above half its rise, and the ground lies low round it where the circle at
     * this distance lies within a fifth of its rise of the base.
     */
    private static final double HILL_REACH = 300;
    /** Rays and steps along each that the survey of a hilltop samples. */
    private static final int RAYS = 16, STEPS = 15;
    /** Rise over the radius of its area above half its rise from which a hill is narrow enough for a tower, fully so a tenth over. */
    private static final double NARROW = 0.4;
    /** Share of the circle round a hill lying low from which it stands alone, fully so a tenth over. */
    private static final double ALONE = 0.45;
    /**
     * Share of the ground from half to all of {@value #HILL_REACH} m round a hill lying low from which
     * it stands on a plain or the sea, fully so a tenth over. A knoll among ridges and gullies, as in
     * loess or on a volcano's flank, has the circle low only where gullies cross it.
     */
    private static final double PLAIN = 0.28;
    /** Metres from its top within which a tower is shaped in full, and past which not at all. */
    private static final double NEAR = 150, FAR = 300;
    /** Far levels' voxel size past which towers keep the tiles' form: a few voxels across, the cones read the same. */
    private static final int MAX_VOXEL = 32;

    private final Globe globe;
    private final SimplexNoise outline;
    /** Lowest and highest ground of each envelope cell, by cell size. */
    private final ConcurrentHashMap<Long, float[]> topCells = new ConcurrentHashMap<>(), baseCells = new ConcurrentHashMap<>();
    private final Envelope bases = new Envelope(BASE_CELL, BASE_REACH, false), tops = new Envelope(TOP_CELL, TOP_REACH, true);
    /** The towers of each block of hill cells: each one's top, x and z, and how much it is a tower. */
    private final ConcurrentHashMap<Long, double[][]> blocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Float> field = new ConcurrentHashMap<>();

    public Towers(long seed, Globe globe) {
        this.globe = globe;
        this.outline = new SimplexNoise(new LegacyRandomSource(seed ^ 0x544F57455253L));
    }

    /**
     * Ground metres at a block position, {@code metres} as the elevation has them, shaped into a
     * tower's profile as far as the hills round the column are towers ({@link #towerness}).
     */
    public double shape(int x, int z, double metres, int voxel) {
        if (metres <= 0 || voxel > MAX_VOXEL) return metres;
        double weight = towerness(x + 0.5, z + 0.5);
        if (weight <= 0) return metres;
        return Mth.lerp(weight, metres, tower(x, z, metres));
    }

    /** Whether a block position lies by a tower ({@link #towerness}), where small steep islands are real towers. */
    public boolean covers(int x, int z) { return towerness(x + 0.5, z + 0.5) > 0; }

    /**
     * A hill made a tower: height above the low ground round it, as a share of its top's, keeps a
     * gentle foot up to its wall, climbs nearly all the rest in a sheer face, and over that keeps
     * the hill's own rounded crown. Where the wall stands wanders a little, so a tower's outline is
     * not a contour of the hill's.
     */
    private double tower(int x, int z, double metres) {
        double top = tops.at(x, z), base = Math.max(0, bases.at(x, z));
        double rise = top - base;
        if (rise < LOW_RISE || metres >= top || metres <= base) return metres;
        double s = (metres - base) / rise;
        double wall = WALL + 0.07 * outline.getValue(x / 37.0, z / 37.0), a = wall - 0.08, b = wall + 0.08;
        double foot = 0.6 * a, crown = 0.9;
        double t = s < a ? s * foot / a : s < b ? foot + (crown - foot) * smooth((s - a) / (b - a)) : crown + (1 - crown) * (s - b) / (1 - b);
        double shaped = base + t * rise;
        return Mth.lerp(Mth.clamp((rise - LOW_RISE) / (TALL_RISE - LOW_RISE), 0, 1), metres, shaped);
    }

    /**
     * How much the hills round a block position are towers, from 0 to 1: the most of any hilltop
     * near it, in full within {@value #NEAR} m of the top and fading out by {@value #FAR} m.
     * Bilinear between the corners of a coarse grid, each holding the most at that corner.
     */
    double towerness(double x, double z) {
        double gx = x / FIELD_CELL, gz = z / FIELD_CELL;
        long i = (long) Math.floor(gx), j = (long) Math.floor(gz);
        double fx = gx - i, fz = gz - j;
        // Far voxels' middles lie on corners.
        if (fx == 0 && fz == 0) return fieldCorner(i, j);
        return Mth.lerp(fz, Mth.lerp(fx, fieldCorner(i, j), fieldCorner(i + 1, j)), Mth.lerp(fx, fieldCorner(i, j + 1), fieldCorner(i + 1, j + 1)));
    }

    private float fieldCorner(long i, long j) {
        long key = it.unimi.dsi.fastutil.HashCommon.mix(i << 32 ^ (j & 0xFFFFFFFFL));
        Float known = field.get(key);
        if (known != null) return known;
        double cx = i * (double) FIELD_CELL, cz = j * (double) FIELD_CELL, metresPerBlock = globe.metresPerBlock(cz);
        double far = FAR / metresPerBlock, near = NEAR / metresPerBlock;
        int span = HILL_CELL * BLOCK_CELLS;
        float best = 0;
        for (long bj = Math.floorDiv((long) Math.floor(cz - far), span); bj <= Math.floorDiv((long) Math.floor(cz + far), span); bj++)
            for (long bi = Math.floorDiv((long) Math.floor(cx - far), span); bi <= Math.floorDiv((long) Math.floor(cx + far), span); bi++)
                for (double[] hill : towers(bi, bj)) {
                    if (hill[2] <= best) continue;
                    double d = Math.hypot(hill[0] - cx, hill[1] - cz);
                    if (d >= far) continue;
                    best = (float) Math.max(best, hill[2] * (d <= near ? 1 : smooth((far - d) / (far - near))));
                }
        if (field.size() > 1 << 18) field.clear();
        field.put(key, best);
        return best;
    }

    /** The towers whose tops lie in a block of hill cells, each as {@link #survey} finds it. */
    private double[][] towers(long bi, long bj) {
        long key = it.unimi.dsi.fastutil.HashCommon.mix(bi << 32 ^ (bj & 0xFFFFFFFFL));
        double[][] known = blocks.get(key);
        if (known != null) return known;
        double[][] found = new double[0][];
        for (int dj = 0; dj < BLOCK_CELLS; dj++) for (int di = 0; di < BLOCK_CELLS; di++) {
            double[] hill = survey(bi * BLOCK_CELLS + di, bj * BLOCK_CELLS + dj);
            if (hill.length == 0) continue;
            found = Arrays.copyOf(found, found.length + 1);
            found[found.length - 1] = hill;
        }
        if (blocks.size() > 1 << 14) blocks.clear();
        blocks.putIfAbsent(key, found);
        return found;
    }

    private static final double[] NONE = new double[0];

    /**
     * A hill cell's top and how much its hill is a tower: its highest ground, where that is the
     * highest of the cells round it, a hill {@value #TALL_RISE} m or more over the lowest ground
     * within {@value #HILL_REACH} m, narrow for its rise ({@value #NARROW}) and with the ground low
     * round most of it ({@value #ALONE}) and over much of the land round it ({@value #PLAIN}). Empty
     * where the cell holds no such top.
     */
    private double[] survey(long i, long j) {
        float[] own = cell(HILL_CELL, i, j);
        // Most of the world has no hill a tower's height over the ground round it: the cell's samples and the low
        // ground nearby, both kept for the envelopes, tell at once.
        double x0 = i * (double) HILL_CELL, z0 = j * (double) HILL_CELL;
        if (own[1] - Math.max(0, bases.at(x0 + HILL_CELL / 2.0, z0 + HILL_CELL / 2.0)) < LOW_RISE) return NONE;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) if ((di != 0 || dj != 0) && cell(HILL_CELL, i + di, j + dj)[1] > own[1]) return NONE;
        // The top, to an eighth of the cell.
        double tx = x0, tz = z0, top = -Double.MAX_VALUE;
        for (int b = 0; b < 8; b++) for (int a = 0; a < 8; a++) {
            double x = x0 + (a + 0.5) * HILL_CELL / 8, z = z0 + (b + 0.5) * HILL_CELL / 8, m = globe.metres(x, z);
            if (m > top) { top = m; tx = x; tz = z; }
        }
        top = Math.max(0, top);
        double metresPerBlock = globe.metresPerBlock(tz), step = HILL_REACH / STEPS / metresPerBlock;
        double[] ground = new double[RAYS * STEPS];
        double base = top;
        for (int r = 0; r < RAYS; r++) {
            double angle = 2 * Math.PI * r / RAYS, dx = Math.cos(angle) * step, dz = Math.sin(angle) * step;
            for (int s = 0; s < STEPS; s++) {
                double m = Math.max(0, globe.metres(tx + dx * (s + 1), tz + dz * (s + 1)));
                ground[r * STEPS + s] = m;
                base = Math.min(base, m);
            }
        }
        double rise = top - base;
        if (rise < TALL_RISE - 10) return NONE;
        // Area above half the rise, each sample standing for its share of the ring it lies on; the share of the
        // outermost ring lying low; and of the outer half of the circle.
        double area = 0, low = 0, plain = 0, ringStep = HILL_REACH / STEPS;
        for (int r = 0; r < RAYS; r++) {
            for (int s = 0; s < STEPS; s++) {
                double m = ground[r * STEPS + s];
                if (m > base + rise / 2) area += 2 * Math.PI * (s + 1) * ringStep * ringStep / RAYS;
                if (s >= STEPS / 2 && m <= base + 0.2 * rise) plain++;
            }
            if (ground[r * STEPS + STEPS - 1] <= base + 0.2 * rise) low++;
        }
        area += Math.PI * ringStep * ringStep / 4;
        double narrow = rise / Math.sqrt(area / Math.PI), alone = low / RAYS;
        plain /= RAYS * (STEPS - STEPS / 2);
        double weight = ramp(rise, TALL_RISE - 10, TALL_RISE + 10) * ramp(narrow, NARROW, NARROW + 0.1) * ramp(alone, ALONE, ALONE + 0.1) * ramp(plain, PLAIN, PLAIN + 0.1);
        return weight <= 0 ? NONE : new double[]{tx, tz, weight};
    }

    private static double ramp(double v, double from, double to) { return Mth.clamp((v - from) / (to - from), 0, 1); }

    /**
     * The highest or lowest ground within {@code reach} metres of a block position, smoothed:
     * bilinear between cell corners, each holding the extreme of the cells around it.
     */
    private final class Envelope {
        private final int cell;
        private final double reach;
        private final boolean high;
        private final ConcurrentHashMap<Long, Float> corners = new ConcurrentHashMap<>();

        Envelope(int cell, double reach, boolean high) { this.cell = cell; this.reach = reach; this.high = high; }

        double at(double x, double z) {
            double gx = x / cell, gz = z / cell;
            long i = (long) Math.floor(gx), j = (long) Math.floor(gz);
            double fx = gx - i, fz = gz - j;
            return Mth.lerp(fz, Mth.lerp(fx, corner(i, j), corner(i + 1, j)), Mth.lerp(fx, corner(i, j + 1), corner(i + 1, j + 1)));
        }

        private float corner(long i, long j) {
            long key = it.unimi.dsi.fastutil.HashCommon.mix(i << 32 ^ (j & 0xFFFFFFFFL));
            Float known = corners.get(key);
            if (known != null) return known;
            double cellMetres = cell * globe.metresPerBlock(j * (double) cell);
            int n = (int) Math.ceil(reach / cellMetres);
            float best = high ? -Float.MAX_VALUE : Float.MAX_VALUE;
            for (int dj = -n; dj < n; dj++) for (int di = -n; di < n; di++) {
                // Distance from the corner to the nearest point of the cell.
                double nx = di >= 0 ? di : di + 1, nz = dj >= 0 ? dj : dj + 1;
                if (Math.sqrt(nx * nx + nz * nz) * cellMetres > reach) continue;
                float[] extremes = cell(cell, i + di, j + dj);
                best = high ? Math.max(best, extremes[1]) : Math.min(best, extremes[0]);
            }
            if (corners.size() > 1 << 16) corners.clear();
            corners.put(key, best);
            return best;
        }
    }

    /** The lowest and highest of 4x4 elevation samples across a cell. */
    private float[] cell(int cell, long i, long j) {
        ConcurrentHashMap<Long, float[]> cells = cell == TOP_CELL ? topCells : baseCells;
        long key = it.unimi.dsi.fastutil.HashCommon.mix(i << 32 ^ (j & 0xFFFFFFFFL));
        float[] known = cells.get(key);
        if (known != null) return known;
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (int b = 0; b < 4; b++) for (int a = 0; a < 4; a++) {
            float m = (float) globe.metres((i + (a + 0.5) / 4) * cell, (j + (b + 0.5) / 4) * cell);
            low = Math.min(low, m);
            high = Math.max(high, m);
        }
        float[] extremes = {low, high};
        if (cells.size() > 1 << 18) cells.clear();
        cells.put(key, extremes);
        return extremes;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }
}
