package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * Water at famous falls that the surveys cannot see, its lines taken from OpenStreetMap (Map data
 * © OpenStreetMap contributors, ODbL) and levelled from the elevation tiles around it. Each entry
 * names the OSM ways it comes from; nothing here is traced from imagery. Victoria Falls' gorges
 * are 60 to 120 m across and 100 m deep, and neither the 30 m elevation survey nor the land cover
 * resolves them, so the Zambezi fell onto a shelf rather than into its chasm. Skógafoss's river is
 * narrower than the land cover's pixels, so it had no water to pour. And the elevation tiles
 * flatten the Niagara above the Horseshoe Falls to the level of the gorge below it, so the river
 * above the falls lay in a pit and there was no falls at all.
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
            // Source: OpenStreetMap, midway between the lip (waterfall relation 17642899) and the gorge's far shore (ways
            // 1501079424 and 676839237 of riverbank relation 49740);
            // levels and widths from the elevation tiles and the land cover.
            new Course("Victoria Falls, First Gorge", new double[][]{
                    {-17.92293, 25.84751, 765, 60}, {-17.92361, 25.85018, 765, 110}, {-17.92396, 25.85124, 765, 100}, {-17.92450, 25.85300, 765, 80},
                    {-17.92472, 25.85418, 765, 60}, {-17.92465, 25.85533, 765, 130}, {-17.92504, 25.85652, 765, 120}, {-17.92525, 25.85796, 765, 110},
                    {-17.92530, 25.85853, 765, 130}, {-17.92548, 25.86007, 765, 110}, {-17.92581, 25.86149, 765, 100}, {-17.92616, 25.86273, 765, 70},
                    {-17.92630, 25.86292, 765, 60}}, 6, false, 886),
            // Source: OpenStreetMap, the Zambezi (relation 318455, ways 765343870 and 717945896);
            // levels and widths from the elevation tiles and the land cover.
            new Course("Victoria Falls, Second and Third Gorges", new double[][]{
                    {-17.92608, 25.85828, 764.5, 67.5}, {-17.92747, 25.85856, 764, 60.5}, {-17.92818, 25.85785, 764, 60}, {-17.92849, 25.85673, 763.5, 60},
                    {-17.92884, 25.85567, 763.5, 60}, {-17.92919, 25.85242, 763, 60}, {-17.92941, 25.85112, 763, 60}, {-17.92954, 25.85004, 763, 60},
                    {-17.92982, 25.84873, 763, 60}, {-17.92997, 25.84770, 763, 60}, {-17.93075, 25.84811, 762.5, 60}, {-17.93116, 25.84921, 762, 60},
                    {-17.93143, 25.85108, 762, 60}, {-17.93139, 25.85275, 762, 60}, {-17.93133, 25.85507, 762, 60}, {-17.93156, 25.85610, 761.5, 60},
                    {-17.93138, 25.85829, 761, 60}, {-17.93140, 25.85950, 761, 60}, {-17.93237, 25.85919, 761, 60}, {-17.93383, 25.85640, 760, 60},
                    {-17.93470, 25.85357, 759, 60}, {-17.93507, 25.85236, 759, 60}}, 6, false, 886),
            // Skógafoss: the Skógá down its ravine to the lip, and from the plunge pool out across the plain.
            // Source: OpenStreetMap, the Skógá (way 178845397) down to the Skógafoss node (270937065);
            // levels and widths from the elevation tiles and the land cover.
            new Course("Skógafoss, upper Skógá", new double[][]{
                    {63.53803, -19.50206, 208, 12}, {63.53776, -19.50127, 207, 12}, {63.53740, -19.50070, 205.5, 12}, {63.53683, -19.50081, 204, 12},
                    {63.53637, -19.50154, 199, 12}, {63.53580, -19.50214, 193, 12}, {63.53550, -19.50293, 188.5, 12.5}, {63.53548, -19.50430, 183.5, 13},
                    {63.53514, -19.50503, 179.5, 14}, {63.53473, -19.50590, 176, 14}, {63.53440, -19.50678, 173, 14}, {63.53355, -19.50733, 167, 15.5},
                    {63.53318, -19.50798, 163.5, 16}, {63.53301, -19.50925, 160, 17.5}, {63.53269, -19.51015, 157, 18.5}, {63.53210, -19.51129, 152, 22}}, 2, false, Double.NaN),
            // Source: OpenStreetMap, the Skógá (way 178845397) below the Skógafoss node (270937065);
            // levels and widths from the elevation tiles and the land cover.
            new Course("Skógafoss, plunge pool and lower Skógá", new double[][]{
                    {63.53184, -19.51167, 87, 36}, {63.53119, -19.51257, 87, 19}, {63.53047, -19.51334, 87, 16}, {63.52990, -19.51383, 86, 16},
                    {63.52867, -19.51403, 86, 16}, {63.52784, -19.51421, 86, 16}, {63.52720, -19.51402, 85.5, 16}, {63.52645, -19.51335, 85, 16},
                    {63.52574, -19.51348, 85, 16}, {63.52527, -19.51435, 85, 16}}, 3, false, 152),
            // Iguazu's Devil's Throat, a chasm 150 m across at the head of the gorge, which the survey reads as a narrow notch.
            // Source: OpenStreetMap, the Río Iguazú (way 27996034, relation 2394867) from the gorge down to Salto Unión
            // (node 2456390419);
            // levels and widths from the elevation tiles and the land cover.
            new Course("Iguazu Falls, Devil's Throat", new double[][]{
                    {-25.68397, -54.44154, 106, 80}, {-25.68554, -54.44108, 106, 80}, {-25.68801, -54.44028, 106, 89.5}, {-25.69098, -54.43876, 106, 102.5},
                    {-25.69295, -54.43783, 106, 119.5}, {-25.69519, -54.43662, 106, 154}, {-25.69559, -54.43634, 106, 158}, {-25.69618, -54.43589, 106, 135}}, 6, false, 180),
            // The Niagara gorge from the foot of the falls down through the Whirlpool Rapids to Lewiston.
            // Source: OpenStreetMap, the Niagara River (relation 2245991, ways 261003808, 55590736 and 326968399);
            // levels and widths from the elevation tiles and the land cover.
            new Course("Niagara gorge", new double[][]{
                    {43.08052, -79.07625, 100, 327.5}, {43.08567, -79.07224, 100, 390}, {43.08850, -79.07000, 99, 347}, {43.09099, -79.06676, 99, 300},
                    {43.09596, -79.06403, 98.5, 300}, {43.09899, -79.06231, 98.5, 300}, {43.10314, -79.05940, 97.5, 300}, {43.10806, -79.05778, 96, 300},
                    {43.11191, -79.05903, 93.5, 300}, {43.11521, -79.06142, 91.5, 332}, {43.12057, -79.06854, 90, 1100}, {43.12593, -79.06014, 87.5, 326.5},
                    {43.12775, -79.05589, 86.5, 308.5}, {43.13196, -79.05200, 84.5, 300}, {43.13492, -79.04948, 83, 300}, {43.13765, -79.04457, 81.5, 300},
                    {43.14226, -79.04235, 79.5, 300}, {43.14569, -79.04236, 78.5, 307.5}, {43.14910, -79.04259, 78, 328}, {43.15239, -79.04449, 77, 349},
                    {43.15787, -79.04559, 73.5, 380.5}, {43.16206, -79.04665, 70, 400}, {43.16451, -79.04855, 67, 400}}, 8, true, 157),
            // The Welland River, which joins the Niagara above the falls at the Niagara's level, but which the elevation
            // tiles read 40 to 70 m down in a trench of its own.
            // Source: the river's water in ESA WorldCover, as the generator reads it; level from the elevation tiles.
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
            // Source: OpenStreetMap, the Horseshoe Falls' upstream edge (way 56539663) and Goat Island's south shore (way
            // 3743454), closed by a box around the river and the low ground beside it that the elevation tiles sink; the low
            // end at the Horseshoe Falls node (708267915); levels from the elevation tiles.
            new Pool("Niagara above the Horseshoe Falls", new double[][]{
                    {43.07867, -79.07844}, {43.07831, -79.07791}, {43.07787, -79.07766}, {43.07759, -79.07732}, {43.07733, -79.07674},
                    {43.07715, -79.07641}, {43.07712, -79.07605}, {43.07684, -79.07550}, {43.07682, -79.07500}, {43.07690, -79.07466},
                    {43.07708, -79.07427}, {43.07759, -79.07396}, {43.07762, -79.07329}, {43.07787, -79.07342}, {43.07795, -79.07382},
                    {43.07837, -79.07413}, {43.07875, -79.07431}, {43.07911, -79.07437}, {43.07936, -79.07451}, {43.07967, -79.07450},
                    {43.07993, -79.07450}, {43.07997, -79.07466}, {43.07940, -79.07319}, {43.07858, -79.07173}, {43.07846, -79.06992},
                    {43.07873, -79.06789}, {43.07890, -79.06643}, {43.07854, -79.06456}, {43.07849, -79.06299}, {43.07849, -79.06113},
                    {43.07952, -79.06032}, {43.07900, -79.05400}, {43.06400, -79.05400}, {43.06400, -79.07000}, {43.06800, -79.07400},
                    {43.07500, -79.08000}},
                    new double[]{43.07725, -79.07506}, 158, new double[]{43.07650, -79.06600}, 171, 6, true),
            // The Horseshoe's plunge pool inside its curve, which the land cover maps as land under the spray.
            // Source: OpenStreetMap, the Horseshoe Falls' upstream edge (way 56539663), closed along 43.08 N; level from the
            // elevation tiles.
            new Pool("Niagara, the Horseshoe Falls' plunge pool", new double[][]{
                    {43.07867, -79.07844}, {43.07831, -79.07791}, {43.07787, -79.07766}, {43.07759, -79.07732}, {43.07733, -79.07674},
                    {43.07715, -79.07641}, {43.07712, -79.07605}, {43.07684, -79.07550}, {43.07682, -79.07500}, {43.07690, -79.07466},
                    {43.07708, -79.07427}, {43.07759, -79.07396}, {43.07762, -79.07329}, {43.07787, -79.07342}, {43.07795, -79.07382},
                    {43.07837, -79.07413}, {43.07875, -79.07431}, {43.07911, -79.07437}, {43.07936, -79.07451}, {43.07967, -79.07450},
                    {43.07993, -79.07450}, {43.07997, -79.07466}, {43.08000, -79.07466}, {43.08000, -79.07844}},
                    new double[]{43.07725, -79.07506}, 100, new double[]{43.08000, -79.07620}, 100, 30, false));

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
