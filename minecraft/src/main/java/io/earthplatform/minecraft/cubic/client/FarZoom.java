package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.far.Far;
import javax.annotation.Nullable;

/**
 * Where far terrain sharpens for a zoomed view (a spyglass, a zoom mod, a low field of view). The
 * levels are rings around the player, chosen by distance alone, so zoomed in on a mountain 8 km off
 * its coarse level 4 and 5 voxels filled the screen, ten times as big as they looked unzoomed,
 * seams and all. Zoomed, the ground the player looks at gets a second middle of its own (see
 * {@link Far#refined}): the one a player standing nearer would have, as far off as the zoom brings
 * the ground (its distance over the zoom), so a voxel there looks about as big as unzoomed. Only
 * the columns in the zoomed view are asked for (see {@link #inView}), and unzoomed nothing changes.
 *
 * @param ex the eye's block x, put back to the start of its column at the level above the finest,
 *     which is all {@link Far#refined} reads of it there: the walk only changes when that does
 * @param finest the finest level the eye would draw, as {@link FarRenderer#finest} gives for its height
 */
record FarZoom(int ex, int ez, int finest) {
    /** The least zoom that sharpens: a sprinting or dynamic field of view zooms out, so this is well clear of them. */
    static final double MIN = 1.6;
    /** The most it counts, about a spyglass and a half: past that the eye's levels need more columns than they are worth. */
    static final double MAX = 16.0;
    /** The most columns asked for a zoomed view at once, on top of the player's own. */
    static final int MAX_COLUMNS = 3000;
    /** The most blocks above and below the ground at its middle a column's ground can reach, for {@link #inView}. */
    private static final double RELIEF = 384;

    /** The ground's height under a block position, from the far columns held. */
    interface Ground { double at(int bx, int bz, double fallback); }

    /** True for the middle columns of a level the eye draws its next finer level in. */
    boolean refined(int level, int x, int z) { return Far.refined(level, x, z, ex, ez, finest); }

    /**
     * The zoomed view's eye, or null unzoomed or looking at no ground the far terrain holds. {@code zoom}
     * is how much narrower the view is than the game's field of view; {@code previous} is kept while the
     * eye has moved by a column or less at the level above its finest, so a slow pan does not walk again
     * every few blocks.
     */
    @Nullable static FarZoom at(double zoom, double x, double y, double z, double dx, double dy, double dz, double reach,
            Ground ground, @Nullable FarZoom previous) {
        if (!(zoom >= MIN)) return null;
        zoom = Math.min(zoom, MAX);
        double hit = hit(x, y, z, dx, dy, dz, reach, ground);
        if (hit < 0) return null;
        // As far from the ground the zoom shows as it looks from there unzoomed, along the same line.
        double back = hit / zoom, ex = x + dx * (hit - back), ey = y + dy * (hit - back), ez = z + dz * (hit - back);
        int bx = (int) Math.floor(ex), bz = (int) Math.floor(ez);
        double height = Math.max(0, ey - ground.at(bx, bz, ey));
        int finest = FarRenderer.finest(height, 0, previous == null ? Far.MIN_LEVEL : previous.finest);
        int level = Math.min(Far.MAX_LEVEL, finest + 1), width = Far.width(level);
        if (previous != null && previous.finest == finest
                && Math.abs(Far.column(bx, level) - Far.column(previous.ex, level)) <= 1
                && Math.abs(Far.column(bz, level) - Far.column(previous.ez, level)) <= 1) return previous;
        return new FarZoom(Far.column(bx, level) * width, Far.column(bz, level) * width, finest);
    }

    /** How far along the look the ground is, or -1 when it is not within {@code reach}. */
    private static double hit(double x, double y, double z, double dx, double dy, double dz, double reach, Ground ground) {
        double t = 0, last = 0;
        while (t < reach) {
            // Steps of a sixty-fourth of the distance: about a pixel at a spyglass's zoom, and few of them.
            double step = Math.max(8, t / 64);
            t += step;
            double px = x + dx * t, py = y + dy * t, pz = z + dz * t;
            if (py <= ground.at((int) Math.floor(px), (int) Math.floor(pz), Double.NEGATIVE_INFINITY)) {
                // Halved back to within a block or so of where the look meets the ground.
                double low = last, high = t;
                while (high - low > 1) {
                    double middle = (low + high) / 2;
                    if (y + dy * middle <= ground.at((int) Math.floor(x + dx * middle), (int) Math.floor(z + dz * middle), Double.NEGATIVE_INFINITY)) high = middle;
                    else low = middle;
                }
                return high;
            }
            last = t;
        }
        return -1;
    }

    /**
     * Whether a column can show in the zoomed view: its ground, {@link #RELIEF} up and down, is within
     * the view's cone around the look, {@code spread} being the tangent of the angle from the look to
     * the view's corners.
     */
    static boolean inView(long key, double x, double y, double z, double dx, double dy, double dz, double spread, Ground ground) {
        int level = Far.level(key), width = Far.width(level);
        double cx = (Far.x(key) + 0.5) * width, cz = (Far.z(key) + 0.5) * width;
        double cy = ground.at((int) Math.floor(cx), (int) Math.floor(cz), y);
        double ox = cx - x, oy = cy - y, oz = cz - z, distance = Math.sqrt(ox * ox + oy * oy + oz * oz);
        // Ground rises about twice a column's width across it at the steepest.
        double relief = Math.min(RELIEF, 2.0 * width), radius = Math.sqrt(0.5 * width * width + relief * relief);
        if (distance <= radius) return true;
        double along = (ox * dx + oy * dy + oz * dz) / distance;
        if (along <= 0) return false;
        double angle = Math.acos(Math.min(1, along)), cone = Math.atan(spread) + Math.asin(radius / distance);
        return angle <= cone;
    }
}
