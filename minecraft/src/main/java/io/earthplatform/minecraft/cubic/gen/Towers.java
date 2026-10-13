package io.earthplatform.minecraft.cubic.gen;

import io.earthplatform.geography.GeoPoint;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Tower karst and pillar forests, which the elevation tiles are too coarse to hold. Ha Long Bay's
 * limestone towers stand sheer-sided out of the sea, a hundred or two metres tall and about as
 * wide, where the tiles have them as rounded cones; Guilin's crowd so close that the tiles merge
 * each cluster into one broad hill; Zhangjiajie's sandstone pillars, thousands of them a few dozen
 * metres across, are not in them at all, only the gorges they stand in. Towers take a steep
 * profile between the ground round them and their own tops; clustered peaks rise from a low
 * pedestal of their hill, each on its own site; pillars rise from the slopes of their gorges
 * toward the rims.
 */
public final class Towers {
    /** What the towers need from the world: an elevation model fine enough to see a tower, and the map projection. */
    public interface Globe {
        /** Ground height in metres above the sea at a block position. */
        double metres(double x, double z);
        GeoPoint geographic(double x, double z);
        double metresPerBlock(double z);
    }

    enum Kind { TOWERS, PEAKS, PILLARS }

    /**
     * A region of tower karst, clustered peaks or pillars between latitudes and longitudes. Towers
     * find their tops within {@code reach} metres and stand their walls {@code wall} of the way up
     * from the low ground round them; the other kinds use neither.
     */
    record Region(String name, Kind kind, double wall, double reach, double south, double west, double north, double east) {
        /** How far inside the region a point lies, from 0 at its edge to 1 a fade's width in, or less than 0 outside. */
        double inside(double lat, double lon) {
            double edge = Math.min(Math.min(lat - south, north - lat), Math.min(lon - west, east - lon));
            return edge / FADE;
        }
    }

    /** Degrees over which a region's shaping fades in from its edge. */
    private static final double FADE = 0.02;

    static final List<Region> REGIONS = List.of(
            new Region("Wulingyuan, Zhangjiajie", Kind.PILLARS, 0, 0, 29.29, 110.37, 29.42, 110.58),
            new Region("Ha Long, Bai Tu Long and Lan Ha Bays", Kind.TOWERS, 0.27, 90, 20.66, 106.93, 21.25, 107.78),
            new Region("Guilin and Yangshuo", Kind.PEAKS, 0, 0, 24.64, 110.18, 25.36, 110.64),
            new Region("Trang An and Tam Coc", Kind.PEAKS, 0, 0, 20.16, 105.80, 20.33, 106.00),
            new Region("Phang Nga Bay and Krabi", Kind.TOWERS, 0.27, 90, 7.95, 98.45, 8.42, 98.92));

    /** Blocks across a cell of the top and base envelopes. */
    private static final int TOP_CELL = 16, BASE_CELL = 64;
    /** Metres round a tower within which the low ground at its foot is sought. */
    private static final double BASE_REACH = 260;
    /** Metres round a pillar within which the rim of its gorge is sought. */
    private static final double RIM_REACH = 320;
    /** Rise in metres from base to top under which hills stay as they are, and over which they are towers. */
    private static final double LOW_RISE = 25, TALL_RISE = 50;
    /** Metres between pillar sites, and their footprints' radii. */
    private static final double PILLAR_SPACING = 75, PILLAR_MIN_RADIUS = 6, PILLAR_MAX_RADIUS = 15;
    /** Share of pillar sites with a pillar on them. */
    private static final double PILLAR_SHARE = 0.7;
    /** Metres a gorge must lie below its rim for pillars to stand in it. */
    private static final double PILLAR_DEPTH = 40;
    /** Metres between peak sites of clustered karst, and the share of them with a peak. */
    private static final double PEAK_SPACING = 240, PEAK_SHARE = 0.85;
    /** Share of a hill's rise above its foot that the ground between clustered peaks keeps. */
    private static final double PEAK_SADDLE = 0.3;

    private final long seed;
    private final Globe globe;
    private final SimplexNoise outline;
    /** Lowest and highest ground of each envelope cell, by cell size. */
    private final ConcurrentHashMap<Long, float[]> topCells = new ConcurrentHashMap<>(), baseCells = new ConcurrentHashMap<>();
    private final Envelope bases = new Envelope(BASE_CELL, BASE_REACH, false), rims = new Envelope(BASE_CELL, RIM_REACH, true);
    private final Map<Region, Envelope> tops = new HashMap<>();
    private final ConcurrentHashMap<Long, double[]> pillars = new ConcurrentHashMap<>(), peaks = new ConcurrentHashMap<>();

    public Towers(long seed, Globe globe) {
        this.seed = seed;
        this.globe = globe;
        this.outline = new SimplexNoise(new LegacyRandomSource(seed ^ 0x544F57455253L));
        for (Region region : REGIONS) if (region.kind == Kind.TOWERS) tops.put(region, new Envelope(TOP_CELL, region.reach, true));
    }

    /**
     * Ground metres at a block position, {@code metres} as the elevation has them, shaped into
     * towers, peaks or pillars where the column lies in one of the {@link #REGIONS}. Far levels
     * {@code voxel} blocks across widen peaks and pillars by about a third of a voxel, so thin ones
     * still show, and keep none past 64 and 32 blocks.
     */
    public double shape(int x, int z, double metres, int voxel) {
        if (metres <= 0) return metres;
        GeoPoint p = globe.geographic(x + 0.5, z + 0.5);
        for (Region region : REGIONS) {
            double weight = region.inside(p.latitude(), p.longitude());
            if (weight <= 0) continue;
            double shaped = switch (region.kind) {
                case TOWERS -> tower(region, x, z, metres);
                case PEAKS -> peak(x, z, metres, voxel);
                case PILLARS -> pillar(x, z, metres, voxel);
            };
            return Mth.lerp(Math.min(1, weight), metres, shaped);
        }
        return metres;
    }

    /**
     * A hill made a tower: height above the low ground round it, as a share of its top's, keeps a
     * gentle foot up to the region's wall, climbs nearly all the rest in a sheer face, and over
     * that keeps the hill's own rounded crown. Where the wall stands wanders a little, so a
     * tower's outline is not a contour of the hill's.
     */
    private double tower(Region region, int x, int z, double metres) {
        double top = tops.get(region).at(x, z), base = Math.max(0, bases.at(x, z));
        double rise = top - base;
        if (rise < LOW_RISE || metres >= top || metres <= base) return metres;
        double s = (metres - base) / rise;
        double wall = region.wall + 0.07 * outline.getValue(x / 37.0, z / 37.0), a = wall - 0.08, b = wall + 0.08;
        double foot = 0.6 * a, crown = 0.9;
        double t = s < a ? s * foot / a : s < b ? foot + (crown - foot) * smooth((s - a) / (b - a)) : crown + (1 - crown) * (s - b) / (1 - b);
        double shaped = base + t * rise;
        return Mth.lerp(Mth.clamp((rise - LOW_RISE) / (TALL_RISE - LOW_RISE), 0, 1), metres, shaped);
    }

    /**
     * Ground of clustered karst, where the elevation holds a cluster of peaks only as one broad
     * hill: the hill sinks to a low pedestal, and on it peak sites each hold a peak or not, as tall
     * as the hill is at the peak's centre, sheer-sided under a rounded crown and ragged in outline.
     * Far levels past 64 blocks keep the hill.
     */
    private double peak(int x, int z, double metres, int voxel) {
        if (voxel > 64) return metres;
        double base = Math.max(0, bases.at(x, z));
        if (metres <= base) return metres;
        double spacing = Math.round(PEAK_SPACING / globe.metresPerBlock(z));
        int ci = Mth.floor(x / spacing), cj = Mth.floor(z / spacing);
        double ragged = 6 * outline.getValue(x / 23.0, z / 23.0) + 2.5 * outline.getValue(x / 7.0 + 40, z / 7.0);
        double rise = metres - base, best = base + PEAK_SADDLE * rise;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            double[] site = peakSite(ci + di, cj + dj, spacing);
            if (site == null) continue;
            double dx = x + 0.5 - site[0], dz = z + 0.5 - site[1];
            double q = (Math.sqrt(dx * dx + dz * dz) + ragged) / (site[2] + 0.35 * (voxel - 1));
            if (q >= 1) continue;
            // A rounded crown to seven tenths of the way out, then a sheer face.
            double f = q < 0.7 ? 1 - 0.5 * q * q : 0.755 * smooth((1 - q) / 0.3);
            best = Math.max(best, site[4] + (site[3] - site[4]) * f);
        }
        // Low hills stay as they are.
        return Mth.lerp(Mth.clamp((rise - LOW_RISE) / (TALL_RISE - LOW_RISE), 0, 1), metres, best);
    }

    /** A peak site's centre x and z, radius in blocks, top and foot in metres, or null where none stands. */
    private double[] peakSite(int i, int j, double spacing) {
        long key = (long) i << 32 | j & 0xFFFFFFFFL;
        double[] known = peaks.get(key);
        if (known != null) return known.length == 0 ? null : known;
        double[] site = new double[0];
        if (unit(i, j, 10) < PEAK_SHARE) {
            double cx = (i + 0.2 + 0.6 * unit(i, j, 11)) * spacing, cz = (j + 0.2 + 0.6 * unit(i, j, 12)) * spacing;
            double top = globe.metres(cx, cz), foot = Math.max(0, bases.at(cx, cz));
            if (top - foot > TALL_RISE) {
                double radius = spacing * (0.3 + 0.22 * unit(i, j, 13));
                // Peaks stand a little above the hill, whose survey rounds their tops off.
                site = new double[]{cx, cz, radius, top + 0.15 * (top - foot) * unit(i, j, 14), foot + PEAK_SADDLE * (top - foot)};
            }
        }
        if (peaks.size() > 1 << 16) peaks.clear();
        peaks.putIfAbsent(key, site);
        return site.length == 0 ? null : site;
    }

    /**
     * Ground under a pillar of a pillar forest: the pillar sites round the column each hold a
     * pillar or not, and one standing in a gorge rises from the gorge's side toward its rim, to a
     * height of its own, over a footprint with a ragged edge. Off the pillars the ground is the
     * gorge's own.
     */
    private double pillar(int x, int z, double metres, int voxel) {
        if (voxel > 32) return metres;
        // Whole blocks, so every column in the region finds the same sites.
        double spacing = Math.round(PILLAR_SPACING / globe.metresPerBlock(z));
        int ci = Mth.floor(x / spacing), cj = Mth.floor(z / spacing);
        double best = metres;
        // Ragged edges: the distance to a pillar's centre wanders by a few blocks.
        double ragged = 2.5 * outline.getValue(x / 5.0, z / 5.0) + 1.2 * outline.getValue(x / 2.3 + 40, z / 2.3);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            double[] site = site(ci + di, cj + dj, spacing);
            if (site == null) continue;
            double dx = x + 0.5 - site[0], dz = z + 0.5 - site[1];
            double d = Math.sqrt(dx * dx + dz * dz) + ragged, r = site[2] + 0.35 * (voxel - 1);
            if (d >= r) continue;
            double q = d / r;
            best = Math.max(best, site[3] - 3 * q * q);
        }
        return best;
    }

    /** A pillar site's centre x and z, radius in blocks and top in metres, or null where none stands. */
    private double[] site(int i, int j, double spacing) {
        long key = (long) i << 32 | j & 0xFFFFFFFFL;
        double[] known = pillars.get(key);
        if (known != null) return known.length == 0 ? null : known;
        double[] site = new double[0];
        double cx = (i + 0.15 + 0.7 * unit(i, j, 1)) * spacing, cz = (j + 0.15 + 0.7 * unit(i, j, 2)) * spacing;
        // Pillars crowd some gorges and leave others to the forest.
        double crowd = Mth.clamp(0.4 + 0.9 * outline.getValue(cx / 800.0 + 70, cz / 800.0), 0, 1);
        if (unit(i, j, 0) < PILLAR_SHARE * crowd) {
            double floor = globe.metres(cx, cz), rim = rims.at(cx, cz);
            if (rim - floor > PILLAR_DEPTH) {
                double metresPerBlock = globe.metresPerBlock(cz);
                double radius = (PILLAR_MIN_RADIUS + (PILLAR_MAX_RADIUS - PILLAR_MIN_RADIUS) * unit(i, j, 3)) / metresPerBlock;
                // Pillars come close to the rim, some all the way, and stand at least a third of the gorge's depth.
                double top = floor + (rim - floor) * (0.55 + 0.45 * Math.sqrt(unit(i, j, 4)));
                site = new double[]{cx, cz, radius, top};
            }
        }
        if (pillars.size() > 1 << 16) pillars.clear();
        pillars.putIfAbsent(key, site);
        return site.length == 0 ? null : site;
    }

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
            long key = i << 32 ^ (j & 0xFFFFFFFFL);
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
        long key = i << 32 ^ (j & 0xFFFFFFFFL);
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

    /** A number in [0, 1) for a pillar site and one of its properties. */
    private double unit(int i, int j, int property) { return (mix(i, j, 0x50494C4C00L + property) >>> 11) * 0x1.0p-53; }

    private long mix(long x, long y, long z) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return h;
    }
}
