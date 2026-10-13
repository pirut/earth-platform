package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * Water at famous falls that the surveys cannot see, traced by hand from EOX's Sentinel-2
 * cloudless 2016 imagery (CC BY 4.0; not the later, non-commercial years) and levelled from the
 * elevation tiles around it. Victoria Falls' gorges are 60 to 120 m across and
 * 100 m deep, and neither the 30 m elevation survey nor the land cover resolves them, so the
 * Zambezi fell onto a shelf rather than into its chasm. Skógafoss's river is narrower than the
 * land cover's pixels, so it had no water to pour. And the elevation tiles flatten the Niagara
 * above the Horseshoe Falls to the level of the gorge below it, so the river above the falls lay
 * in a pit and there was no falls at all.
 *
 * <p>A {@link Course} is a river or gorge along a line, its water level and width given at each
 * point; it carves its bed and walls into the ground, or, flagged {@code wetOnly}, only sets the
 * level of water the land cover already maps. A {@link Pool} sets the level of mapped water inside
 * an outline, rising from one point to another. Where water from one meets lower water from
 * another, the generator pours it over the edge as a falls.
 */
final class KnownWater {
    /** How many blocks a carved gorge's walls rise for each block out from its water. */
    private static final double WALL = 3;
    /** How far, in blocks, beyond a river whose level only is known its level reaches the water and low ground beside it. */
    private static final double CAPTURE = 100;

    /**
     * A river or gorge: points as latitude, longitude, water level in metres and width in metres;
     * and the level of the river above its falls, if any: water mapped beside the gorge below that
     * level is the gorge's, as the spray and the cascades at its ends map, rather than standing
     * halfway up its walls.
     */
    record Course(String name, double[][] points, double depth, boolean wetOnly, double above) {}

    /** Mapped water inside an outline, its level running from {@code low} metres at one point to {@code high} at another. */
    record Pool(String name, double[][] outline, double[] lowAt, double low, double[] highAt, double high, double depth, boolean wetOnly) {}

    static final List<Course> COURSES = List.of(
            // Victoria Falls: the First Gorge under the whole lip, then the Boiling Pot and the Second and Third Gorges
            // zigzagging down to the Batoka Gorge, which the survey does see.
            // Source: traced on EOX Sentinel-2 cloudless 2016 (CC BY 4.0); levels from the elevation tiles.
            new Course("Victoria Falls, First Gorge", new double[][]{
                    {-17.92330, 25.84790, 765, 80}, {-17.92443, 25.85272, 765, 90}, {-17.92550, 25.85830, 765, 100}, {-17.92611, 25.86290, 765, 80}}, 6, false, 886),
            // Source: traced on EOX Sentinel-2 cloudless 2016 (CC BY 4.0); levels from the elevation tiles.
            new Course("Victoria Falls, Second and Third Gorges", new double[][]{
                    {-17.92550, 25.85830, 765, 70}, {-17.92762, 25.85838, 764, 60}, {-17.92876, 25.85808, 764, 60}, {-17.92925, 25.85401, 763, 60},
                    {-17.92933, 25.84971, 763, 60}, {-17.92986, 25.84778, 763, 60}, {-17.93109, 25.84843, 762, 60}, {-17.93138, 25.85401, 762, 60},
                    {-17.93170, 25.85916, 761, 60}, {-17.93272, 25.85894, 761, 60}, {-17.93374, 25.85615, 760, 60}, {-17.93456, 25.85272, 759, 60}}, 6, false, 886),
            // Skógafoss: the Skógá down its ravine to the lip, and from the plunge pool out across the plain.
            // Source: traced on EOX Sentinel-2 cloudless 2016 (CC BY 4.0); levels from the elevation tiles.
            new Course("Skógafoss, upper Skógá", new double[][]{
                    {63.53800, -19.50211, 208, 12}, {63.53685, -19.50103, 204, 12}, {63.53551, -19.50253, 190, 12}, {63.53503, -19.50511, 179, 14},
                    {63.53417, -19.50683, 172, 14}, {63.53322, -19.50768, 164, 16}, {63.53264, -19.50983, 158, 18}, {63.53226, -19.51112, 152, 22}}, 2, false, Double.NaN),
            // Source: traced on EOX Sentinel-2 cloudless 2016 (CC BY 4.0); levels from the elevation tiles.
            new Course("Skógafoss, plunge pool and lower Skógá", new double[][]{
                    {63.53211, -19.51146, 87, 36}, {63.53195, -19.51176, 87, 32}, {63.53169, -19.51219, 87, 22}, {63.53073, -19.51305, 87, 16},
                    {63.52958, -19.51361, 86, 16}, {63.52805, -19.51391, 86, 16}, {63.52652, -19.51369, 85, 16}, {63.52537, -19.51455, 85, 16}}, 3, false, 152),
            // Iguazu's Devil's Throat, a chasm 150 m across at the head of the gorge, which the survey reads as a narrow notch.
            // Source: traced on EOX Sentinel-2 cloudless 2016 (CC BY 4.0); levels from the elevation tiles.
            new Course("Iguazu Falls, Devil's Throat", new double[][]{
                    {-25.68400, -54.44120, 106, 80}, {-25.68593, -54.44056, 106, 80}, {-25.68787, -54.43970, 106, 90}, {-25.69057, -54.43863, 106, 100},
                    {-25.69289, -54.43756, 106, 120}, {-25.69463, -54.43670, 106, 150}, {-25.69579, -54.43605, 106, 160}, {-25.69637, -54.43571, 106, 120}}, 6, false, 180),
            // The Niagara gorge from the foot of the falls down through the Whirlpool Rapids to Lewiston.
            // Source: traced on EOX Sentinel-2 cloudless 2016 (CC BY 4.0); levels from the elevation tiles.
            new Course("Niagara gorge", new double[][]{
                    {43.08000, -79.07600, 100, 350}, {43.08111, -79.07585, 100, 300}, {43.08425, -79.07410, 100, 380}, {43.08738, -79.07360, 99.5, 400},
                    {43.09039, -79.06766, 99, 300}, {43.09635, -79.06380, 98.5, 300}, {43.10261, -79.06122, 98, 300}, {43.10825, -79.05951, 96, 300},
                    {43.11264, -79.05865, 93, 300}, {43.11627, -79.06345, 91, 350}, {43.12128, -79.07032, 90, 600}, {43.12316, -79.06517, 89, 350},
                    {43.12943, -79.05487, 86, 300}, {43.13694, -79.04629, 82, 300}, {43.14446, -79.04285, 79, 300}, {43.15260, -79.04371, 77, 350},
                    {43.16137, -79.04629, 71, 400}, {43.16512, -79.04886, 66, 400}}, 8, true, 157),
            // The Welland River, which joins the Niagara above the falls at the Niagara's level, but which the elevation
            // tiles read 40 to 70 m down in a trench of its own.
            // Source: the river's water in ESA WorldCover, as the generator reads it, with its mouth checked on EOX Sentinel-2
            // cloudless 2016 (CC BY 4.0); level from the elevation tiles.
            new Course("Welland River", new double[][]{
                    {43.06354, -79.04442, 171, 120}, {43.06221, -79.04910, 171, 100}, {43.05997, -79.05096, 171, 100}, {43.05781, -79.05460, 171, 100},
                    {43.05549, -79.05823, 171, 100}, {43.05250, -79.06096, 171, 100}, {43.05084, -79.06414, 171, 100}, {43.04918, -79.06868, 171, 100},
                    {43.04785, -79.07322, 171, 100}, {43.04819, -79.07795, 171, 100}, {43.04742, -79.08268, 171, 100}, {43.04742, -79.08741, 171, 100},
                    {43.04846, -79.09214, 171, 100}, {43.04915, -79.09687, 171, 100}, {43.04577, -79.10457, 171, 200},
                    {43.04494, -79.11593, 171, 200}, {43.04577, -79.12047, 171, 200}, {43.04826, -79.13183, 171, 200}, {43.04577, -79.13864, 171, 200},
                    {43.04245, -79.14546, 171, 200}, {43.04245, -79.15909, 171, 200}, {43.04162, -79.17271, 171, 200}, {43.03996, -79.17726, 171, 200},
                    {43.03581, -79.18407, 171, 200}, {43.03631, -79.19089, 171, 200}, {43.03996, -79.19997, 171, 200}, {43.03631, -79.20224, 171, 200},
                    {43.03531, -79.20906, 171, 200}, {43.03631, -79.21360, 171, 200}}, 4, true, Double.NaN));

    static final List<Pool> POOLS = List.of(
            // The upper Niagara from the Horseshoe Falls' crest up through the Cascades, two kilometres where the
            // elevation tiles read it at the gorge's level. The outline follows the crest; the land cover gives the shores.
            // Source: traced on EOX Sentinel-2 cloudless 2016 (CC BY 4.0); levels from the elevation tiles.
            new Pool("Niagara above the Horseshoe Falls", new double[][]{
                    {43.07892, -79.07789}, {43.07798, -79.07768}, {43.07735, -79.07704}, {43.07716, -79.07607}, {43.07743, -79.07511},
                    {43.07814, -79.07453}, {43.07892, -79.07446}, {43.07978, -79.07457}, {43.07955, -79.07221}, {43.07876, -79.06311},
                    {43.07900, -79.05400}, {43.06400, -79.05400}, {43.06400, -79.07000}, {43.06800, -79.07400}, {43.07484, -79.07972},
                    {43.07892, -79.07865}},
                    new double[]{43.07760, -79.07620}, 158, new double[]{43.07650, -79.06600}, 171, 6, true),
            // The Horseshoe's plunge pool inside its curve, which the land cover maps as land under the spray.
            // Source: traced on EOX Sentinel-2 cloudless 2016 (CC BY 4.0); levels from the elevation tiles.
            new Pool("Niagara, the Horseshoe Falls' plunge pool", new double[][]{
                    {43.07892, -79.07789}, {43.07798, -79.07768}, {43.07735, -79.07704}, {43.07716, -79.07607}, {43.07743, -79.07511},
                    {43.07814, -79.07453}, {43.07892, -79.07446}, {43.08000, -79.07446}, {43.08000, -79.07800}},
                    new double[]{43.07760, -79.07620}, 100, new double[]{43.08000, -79.07620}, 100, 30, false));

    /** Block-space forms of the courses and pools, for one world's grid. */
    private final List<double[][]> courses = new ArrayList<>(), pools = new ArrayList<>();
    private final double[][] courseBounds, poolBounds, poolSlopes;
    private final MercatorGrid grid;

    KnownWater(MercatorGrid grid) {
        this.grid = grid;
        courseBounds = new double[COURSES.size()][];
        for (int c = 0; c < COURSES.size(); c++) {
            double[][] points = COURSES.get(c).points(), blocks = new double[points.length][];
            double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int p = 0; p < points.length; p++) {
                GeoPoint point = new GeoPoint(points[p][0], points[p][1]);
                double x = grid.blockX(point), z = grid.blockZ(point), width = points[p][3] / grid.groundMetresPerBlock(z);
                blocks[p] = new double[]{x, z, points[p][2], width / 2};
                // Room for the walls: a gorge 200 m deep climbs out within 70 blocks.
                double reach = width / 2 + 200 / WALL;
                bounds[0] = Math.min(bounds[0], x - reach); bounds[1] = Math.min(bounds[1], z - reach);
                bounds[2] = Math.max(bounds[2], x + reach); bounds[3] = Math.max(bounds[3], z + reach);
            }
            courses.add(blocks);
            courseBounds[c] = bounds;
        }
        poolBounds = new double[POOLS.size()][];
        poolSlopes = new double[POOLS.size()][];
        for (int c = 0; c < POOLS.size(); c++) {
            Pool pool = POOLS.get(c);
            double[][] blocks = new double[pool.outline().length][];
            double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int p = 0; p < blocks.length; p++) {
                blocks[p] = block(pool.outline()[p]);
                bounds[0] = Math.min(bounds[0], blocks[p][0]); bounds[1] = Math.min(bounds[1], blocks[p][1]);
                bounds[2] = Math.max(bounds[2], blocks[p][0]); bounds[3] = Math.max(bounds[3], blocks[p][1]);
            }
            pools.add(blocks);
            poolBounds[c] = bounds;
            double[] low = block(pool.lowAt()), high = block(pool.highAt());
            poolSlopes[c] = new double[]{low[0], low[1], high[0] - low[0], high[1] - low[1]};
        }
    }

    private double[] block(double[] latLon) {
        GeoPoint point = new GeoPoint(latLon[0], latLon[1]);
        return new double[]{grid.blockX(point), grid.blockZ(point)};
    }

    /**
     * What known water says of a column, in metres: its water level, or NaN beside a gorge, where
     * only its walls are known; how high its ground may stand, or, if {@code fixed}, stands;
     * whether it holds only where the land cover maps water; and, by a gorge, the gorge's water level
     * and the level of the river above its falls ({@link Course#above}).
     */
    record Water(double level, double ground, boolean wetOnly, boolean fixed, double gorge, double above) {}

    /**
     * The known water at a column, or null. A pool's water is taken first, so a gorge's course
     * running up to a falls never lowers the river above the crest. {@code voxel} is the column's
     * width in blocks: narrow courses that carve into dry ground are left out of far terrain
     * coarser than two blocks, where they would widen into broad troughs.
     */
    Water at(double x, double z, int voxel) {
        for (int c = 0; c < pools.size(); c++) {
            double[] b = poolBounds[c];
            if (x < b[0] || z < b[1] || x > b[2] || z > b[3] || !inside(pools.get(c), x, z)) continue;
            Pool pool = POOLS.get(c);
            double[] s = poolSlopes[c];
            double t = Math.clamp(((x - s[0]) * s[2] + (z - s[1]) * s[3]) / (s[2] * s[2] + s[3] * s[3]), 0, 1);
            double level = pool.low() + (pool.high() - pool.low()) * t;
            return new Water(level, level - pool.depth(), pool.wetOnly(), true, level, Double.NaN);
        }
        Water best = null;
        for (int c = 0; c < courses.size(); c++) {
            double[] b = courseBounds[c];
            Course course = COURSES.get(c);
            if (x < b[0] || z < b[1] || x > b[2] || z > b[3] || !course.wetOnly() && voxel > 2) continue;
            double[][] points = courses.get(c);
            double nearest = Double.MAX_VALUE, level = 0, half = 0;
            for (int p = 0; p + 1 < points.length; p++) {
                double[] a = points[p], e = points[p + 1];
                double dx = e[0] - a[0], dz = e[1] - a[1];
                double t = Math.clamp(((x - a[0]) * dx + (z - a[1]) * dz) / (dx * dx + dz * dz), 0, 1);
                double px = a[0] + dx * t - x, pz = a[1] + dz * t - z, d = Math.sqrt(px * px + pz * pz);
                double w = a[3] + (e[3] - a[3]) * t;
                // Measured from the water's edge, so a wide reach next to a narrow one claims its own banks.
                if (d - w < nearest - half) { nearest = d; level = a[2] + (e[2] - a[2]) * t; half = w; }
            }
            double out = nearest - half;
            Water found;
            if (out <= 0) found = new Water(level, level - course.depth(), course.wetOnly(), false, level, course.above());
            else if (!course.wetOnly() && out * WALL < 200) found = new Water(Double.NaN, level + out * WALL, false, false, level, course.above());
            // Beside a river whose level only is known, mapped water is the river's, which the tiles may have put anywhere,
            // or, below a falls, water mapped below the river above it, as at the foot of the American Falls.
            else if (course.wetOnly() && out < CAPTURE) found = new Water(Double.NaN, Double.POSITIVE_INFINITY, true, false, level, course.above());
            else continue;
            // Water wins over walls, and the lower of two walls stands, keeping the gorge of whichever has a river above it.
            if (best == null || !Double.isNaN(found.level)) best = found;
            else if (Double.isNaN(best.level)) {
                boolean gorge = Double.isNaN(best.above) || !Double.isNaN(found.above) && found.gorge < best.gorge;
                best = new Water(Double.NaN, Math.min(best.ground, found.ground), best.wetOnly && found.wetOnly, false,
                        gorge ? found.gorge : best.gorge, gorge ? found.above : best.above);
            }
        }
        return best;
    }

    /** Whether a point lies inside an outline, by the even-odd rule. */
    private static boolean inside(double[][] outline, double x, double z) {
        boolean in = false;
        for (int i = 0, j = outline.length - 1; i < outline.length; j = i++) {
            double[] a = outline[i], b = outline[j];
            if ((a[1] > z) != (b[1] > z) && x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]) in = !in;
        }
        return in;
    }
}
