package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.far.Far;
import javax.annotation.Nullable;

/**
 * Where far terrain sharpens for a zoomed view (a spyglass, a zoom mod, a narrow field of view). The
 * levels are rings around the player, chosen by distance alone, so zoomed in on a mountain 8 km off
 * its coarse level 4 and 5 voxels filled the screen, ten times as big as they looked unzoomed,
 * seams and all. Zoomed, a column in the view splits into its finer level out to the zoom times as
 * far as the player's own rings reach (see {@link #refined}): through the zoom every level then
 * looks the size it does unzoomed, and meets the next at the same small seam, all across the view.
 * Only the columns in the zoomed view count (see {@link #inView}); unzoomed nothing changes.
 *
 * <p>An earlier try gave the ground the look met a second middle of its own, as a player standing
 * there would have. Its rings cut across the view wherever the ground came nearer or went farther
 * than that one spot, so levels four apart met in plain sight, with tall walls between them.
 *
 * <p>Everything here is rounded (the zoom to half steps of two, the camera to {@link #CAMERA}
 * blocks, the look to a quarter of the view), so the walk, which depends on it, is walked again only
 * when it changes that much, not every frame of a zoom mod's smooth zoom or a slow pan; the cone is
 * that much wider than the view to cover it.
 */
final class FarZoom {
    /** The least zoom that sharpens: a sprinting or dynamic field of view zooms out, so this is well clear of them. */
    static final double MIN = 1.6;
    /**
     * The most zoom it counts, and the narrowest view whose cone it follows: enough for a zoom mod's
     * narrowest view (Zume's is a 1 degree field of view, about 80 times). At 32, the most it once
     * counted, such a view asked for the ground in a 32x view's cone, two and a half times as wide
     * each way as what it shows: looking from Mount Rainier at the Goat Rocks 56 km off, 7,428
     * columns where its own wants 4,012, and the view took twice as long to sharpen. Where the
     * columns pass {@link #MAX_COLUMNS}, less of the zoom counts, as before.
     */
    static final double MAX = 128.0;
    /** The most columns asked for a zoomed view at once, on top of the player's own; {@code -Dearthplatform.far.zoomColumns} sets fewer or more. */
    static final int MAX_COLUMNS = Math.max(1, Integer.getInteger("earthplatform.far.zoomColumns", 8000));
    /**
     * The most zoom counted, in half steps of two (see {@link #steps}): all of {@link #MAX} unless
     * {@code -Dearthplatform.far.zoomSteps} sets fewer. The view's cone still follows the whole zoom.
     */
    static final int MAX_STEPS = Math.clamp(Integer.getInteger("earthplatform.far.zoomSteps", 14), 1, 14);
    /** Blocks the camera's position is rounded to. */
    private static final int CAMERA = 32;

    /** The zoom, rounded down to a power of the square root of two. */
    final double zoom;
    /** The camera's block position and height over the ground, rounded to {@link #CAMERA} blocks. */
    final int x, y, z, height;
    /**
     * The look's yaw and pitch in steps of {@link #step} radians, the view's spread in hundredths,
     * and its width over its height in hundredths, or 0 where only the cone round its corners counts.
     */
    private final int yaw, pitch, spreadSteps, zoomSteps, aspectSteps;
    /** The view's own zoom in half steps of two, which the cone follows; {@link #zoomSteps} may count less of it (see {@link #limited}). */
    private final int viewSteps;
    /** The look, and the cosine of the cone's half angle. */
    private final double dx, dy, dz, cone;
    /**
     * The four sides of the view, as the normals of the planes through the camera they lie in,
     * pointing out of the view, each widened by the rounding of the look; none where
     * {@link #aspectSteps} is 0 or the view is wider than a half turn.
     */
    private final double[][] sides;

    private FarZoom(int zoomSteps, int x, int y, int z, int height, int yaw, int pitch, int spreadSteps, int viewSteps, int aspectSteps) {
        this.zoomSteps = zoomSteps;
        this.viewSteps = viewSteps;
        this.zoom = Math.pow(2, zoomSteps / 2.0);
        this.x = x;
        this.y = y;
        this.z = z;
        this.height = height;
        this.yaw = yaw;
        this.pitch = pitch;
        this.spreadSteps = spreadSteps;
        double half = Math.atan(spreadSteps / 100.0 / Math.pow(2, viewSteps / 2.0)), step = step(half);
        double yawRadians = yaw * step, pitchRadians = pitch * step;
        dx = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        dy = -Math.sin(pitchRadians);
        dz = Math.cos(yawRadians) * Math.cos(pitchRadians);
        cone = Math.cos(Math.min(Math.PI / 2, half + step));
        this.aspectSteps = aspectSteps;
        sides = aspectSteps > 0 ? sides(Math.tan(half), aspectSteps / 100.0, step, yawRadians, pitchRadians) : new double[0][];
    }

    /**
     * The sides of a view whose corners are {@code corner} (a tangent) from its middle and whose width
     * is {@code aspect} times its height, looking along yaw and pitch: each side's angle from the middle
     * widened by {@code step}, as the look may be that far off.
     */
    private static double[][] sides(double corner, double aspect, double step, double yaw, double pitch) {
        double up = corner / Math.sqrt(1 + aspect * aspect), across = up * aspect;
        double wide = Math.atan(across) + step, high = Math.atan(up) + step;
        if (wide >= Math.PI / 2 || high >= Math.PI / 2) return new double[0][];
        double[] forward = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        // The view's right and its up, at right angles to the look and to each other.
        double[] right = {Math.cos(yaw), 0, Math.sin(yaw)};
        double[] top = {right[1] * forward[2] - right[2] * forward[1], right[2] * forward[0] - right[0] * forward[2], right[0] * forward[1] - right[1] * forward[0]};
        double tanWide = Math.tan(wide), tanHigh = Math.tan(high);
        double[][] sides = new double[4][3];
        for (int i = 0; i < 3; i++) {
            sides[0][i] = right[i] - tanWide * forward[i];
            sides[1][i] = -right[i] - tanWide * forward[i];
            sides[2][i] = top[i] - tanHigh * forward[i];
            sides[3][i] = -top[i] - tanHigh * forward[i];
        }
        return sides;
    }

    /** The step the look is rounded to: a quarter of the view's half angle. */
    private static double step(double half) { return half / 4; }

    /** The zoom counted, in half steps of two: each one more about doubles the columns the view wants. */
    int steps() { return zoomSteps; }

    /** The same view counting at most {@code steps} of its zoom (see {@link #steps}). */
    FarZoom limited(int steps) {
        return steps >= zoomSteps ? this : new FarZoom(steps, x, y, z, height, yaw, pitch, spreadSteps, viewSteps, aspectSteps);
    }

    /**
     * The zoomed view, or null unzoomed. {@code zoom} is how much narrower the view is than the game's
     * field of view, {@code spread} the tangent of the angle from the look to the view's corners at
     * that field of view; {@code yaw} and {@code pitch} in degrees as the game has them. The previous
     * one comes back while nothing has changed past its rounding. Only the cone round the view's
     * corners counts: {@link #at(double, double, double, double, double, double, double, float, float, FarZoom)}
     * with the view's shape cuts it to the view itself.
     */
    @Nullable static FarZoom at(double zoom, double spread, double x, double y, double z, double height, float yaw, float pitch,
            @Nullable FarZoom previous) {
        return at(zoom, spread, 0, x, y, z, height, yaw, pitch, previous);
    }

    /**
     * The same for a view {@code aspect} times as wide as it is high: a column counts only where it
     * can show inside the view's four sides, not anywhere in the cone round its corners, which on a
     * wide screen took in about twice the ground above and below the view.
     */
    @Nullable static FarZoom at(double zoom, double spread, double aspect, double x, double y, double z, double height, float yaw, float pitch,
            @Nullable FarZoom previous) {
        if (!(zoom >= MIN)) return null;
        int zoomSteps = (int) Math.floor(2 * Math.log(Math.min(zoom, MAX)) / Math.log(2));
        int spreadSteps = (int) Math.ceil(spread * 100);
        double step = step(Math.atan(spreadSteps / 100.0 / Math.pow(2, zoomSteps / 2.0)));
        FarZoom zoomed = new FarZoom(Math.min(zoomSteps, MAX_STEPS), round(x), round(y), round(z), round(Math.max(0, height)),
                (int) Math.round(Math.toRadians(yaw) / step), (int) Math.round(Math.toRadians(pitch) / step), spreadSteps, zoomSteps,
                aspect > 0 ? Math.max(1, (int) Math.floor(aspect * 100)) : 0);
        return zoomed.equals(previous) ? previous : zoomed;
    }

    private static int round(double blocks) { return (int) Math.round(blocks / CAMERA) * CAMERA; }

    /**
     * True for a column the zoomed view draws its next finer level in: up to the zoom times as far from
     * the camera as a level's middle reaches round the player (see {@link Far#refined}), and in the view.
     * {@code column} is the column there when held, whose height bounds where it can show; for one not
     * held, only how far off it is counts.
     */
    boolean refined(int level, int cx, int cz, @Nullable Box column) { return refined(level, cx, cz, column, null); }

    /** As {@link #refined(int, int, int, Box)}, and false too for a column the ground nearer the camera hides from it. */
    boolean refined(int level, int cx, int cz, @Nullable Box column, @Nullable Horizon horizon) {
        if (level <= Far.MIN_LEVEL) return false;
        int width = Far.width(level);
        // To the column's middle: round the player, a column whose middle is a hole's width off is the
        // last one its level splits, so unzoomed this splits none the player's own levels do not.
        // A column held is as far above or below as its own ground, not the ground under the camera:
        // up high, a mountain top near the camera is near however deep the valley under it.
        double ox = (cx + 0.5) * width - x, oz = (cz + 0.5) * width - z;
        double vertical = column == null ? height : Math.max(0, Math.max(column.lowY() - y, y - column.highY()));
        if (Math.max(Math.sqrt(ox * ox + oz * oz), vertical) > Far.hole(level) * width * zoom) return false;
        if (column == null) return true;
        return inView(cx, cz, width, column.lowY(), column.highY())
                && (horizon == null || !horizon.hidden((cx + 0.5) * width, (cz + 0.5) * width, width / 2.0, column.highY() + 2 * column.voxel()));
    }

    /** A horizon for this view, to be filled with the ground the columns held show (see {@link Horizon}). */
    Horizon horizon() { return new Horizon(); }

    /** Whether a column centered at block {@code cx}, {@code cz} and {@code width} wide can lie in the zoomed view's directions at all, seen from above. */
    boolean across(double cx, double cz, double width) {
        double ox = cx - x, oz = cz - z, distance = Math.sqrt(ox * ox + oz * oz), radius = width * 0.75 + CAMERA;
        if (distance <= radius) return true;
        double flat = Math.sqrt(dx * dx + dz * dz), half = Math.acos(cone);
        // Looking steeply down or up, the cone spans every direction round.
        if (flat <= Math.sin(half)) return true;
        double spread = Math.asin(Math.sin(half) / flat) + Math.asin(radius / distance);
        double along = (ox * dx + oz * dz) / (distance * flat);
        return Math.acos(Math.max(-1, Math.min(1, along))) <= spread;
    }

    /**
     * The ground nearer the camera, as the steepest it rises in each direction round within each
     * distance, so a zoomed view need not sharpen the columns behind a hill: through a zoom the far
     * side of a near ridge is most of what would otherwise be asked for, out to the whole far distance.
     * Every guess here leans toward seeing: the ground counts only as high as the lowest it is in a
     * column, less a couple of that column's voxels, and only in directions it fully covers; a column
     * counts as high as its highest ground, plus as much, in every direction it may reach; and the
     * camera counts as above where it is rounded to.
     */
    final class Horizon {
        /** Directions round, and distances, in powers of two blocks. */
        private static final int BINS = 4096, BANDS = 26;
        private static final double BIN = 2 * Math.PI / BINS;
        /** The steepest ground at each band and direction, from all of it ending nearer than the band begins once {@link #finish}ed. */
        private final float[] steepest = new float[BANDS * BINS];
        private final double eye = y + CAMERA, slack = CAMERA * 0.75;

        private Horizon() { java.util.Arrays.fill(steepest, Float.NEGATIVE_INFINITY); }

        /** Takes in ground no lower than {@code low} across the square {@code width} wide centered at block {@code cx}, {@code cz}, made of voxels {@code voxel} blocks high. */
        void add(double cx, double cz, double width, double low, double voxel) {
            double ox = cx - x, oz = cz - z, distance = Math.sqrt(ox * ox + oz * oz), radius = width / 2;
            double near = distance - radius - slack, far = distance + radius + slack;
            if (near <= 2 * CAMERA) return;
            double ground = low - 2 * voxel - eye, steep = ground / (ground > 0 ? far : near);
            // The circle inside the square, less what the camera's rounding may turn it by.
            double spread = Math.asin(radius / distance) - Math.asin(Math.min(1, slack / distance));
            if (spread <= 0) return;
            double angle = Math.atan2(oz, ox);
            int from = (int) Math.ceil((angle - spread) / BIN), to = (int) Math.floor((angle + spread) / BIN) - 1;
            int band = Math.min(BANDS - 1, 64 - Long.numberOfLeadingZeros((long) Math.ceil(far)));
            for (int bin = from; bin <= to; bin++) {
                int at = band * BINS + Math.floorMod(bin, BINS);
                if (steep > steepest[at]) steepest[at] = (float) steep;
            }
        }

        /** Carries the ground of each band out to all those farther. */
        void finish() {
            for (int band = 1; band < BANDS; band++) for (int bin = 0; bin < BINS; bin++) {
                int at = band * BINS + bin;
                steepest[at] = Math.max(steepest[at], steepest[at - BINS]);
            }
        }

        /** Whether the ground nearer hides all of the square {@code width} wide centered at block {@code cx}, {@code cz}, up to height {@code high}. */
        boolean hidden(double cx, double cz, double width, double high) {
            double ox = cx - x, oz = cz - z, distance = Math.sqrt(ox * ox + oz * oz), radius = width * Math.sqrt(0.5);
            double near = distance - radius - slack, far = distance + radius + slack;
            if (near <= 2 * CAMERA) return false;
            double top = high - eye, steep = top / (top > 0 ? near : far);
            double spread = Math.asin(Math.min(1, (radius + slack) / distance));
            if (spread >= Math.PI / 2) return false;
            double angle = Math.atan2(oz, ox);
            // The band the column begins in holds only ground ending nearer than that band begins.
            int band = Math.min(BANDS - 1, 63 - Long.numberOfLeadingZeros((long) near));
            for (int bin = (int) Math.floor((angle - spread) / BIN), to = (int) Math.floor((angle + spread) / BIN); bin <= to; bin++) {
                if (!(steepest[band * BINS + Math.floorMod(bin, BINS)] > steep)) return false;
            }
            return true;
        }
    }

    /** Whether a column's box, between block heights {@code bottom} and {@code top}, can show in the zoomed view. */
    private boolean inView(int cx, int cz, int width, double bottom, double top) {
        double ox = (cx + 0.5) * width - x, oy = (bottom + top) / 2 - y, oz = (cz + 0.5) * width - z;
        double half = (top - bottom) / 2, radius = Math.sqrt(0.5 * width * width + half * half);
        double distance = Math.sqrt(ox * ox + oy * oy + oz * oz);
        // The camera is rounded, so a column this near may hold it.
        if (distance <= radius + CAMERA) return true;
        double along = (ox * dx + oy * dy + oz * dz) / distance;
        // Within the cone, widened by the angle the column's own size takes up.
        double wider = Math.asin(Math.min(1, (radius + CAMERA) / distance));
        if (Math.acos(Math.max(-1, Math.min(1, along))) > Math.acos(cone) + wider) return false;
        // Inside the four sides, the box grown by how far the camera may be from where it is rounded to.
        double across = width / 2.0 + CAMERA, up = half + CAMERA;
        for (double[] side : sides) {
            if (ox * side[0] + oy * side[1] + oz * side[2] > across * (Math.abs(side[0]) + Math.abs(side[2])) + up * Math.abs(side[1])) return false;
        }
        return true;
    }

    /** How near the middle of the view a column is, as the cosine of its angle from the look: higher is nearer. */
    double central(long key) {
        int level = Far.level(key), width = Far.width(level);
        double ox = (Far.x(key) + 0.5) * width - x, oz = (Far.z(key) + 0.5) * width - z, oy = -height;
        double distance = Math.sqrt(ox * ox + oy * oy + oz * oz);
        return distance == 0 ? 1 : (ox * dx + oy * dy + oz * dz) / distance;
    }

    /** The lowest and highest ground in a column, in blocks, for {@link #refined}. */
    interface Box {
        double lowY();
        double highY();
        /** The height of the voxels the heights were read from, in blocks. */
        double voxel();
    }

    @Override public boolean equals(Object other) {
        return other instanceof FarZoom zoomed && zoomed.zoomSteps == zoomSteps && zoomed.viewSteps == viewSteps && zoomed.x == x && zoomed.y == y && zoomed.z == z
                && zoomed.height == height && zoomed.yaw == yaw && zoomed.pitch == pitch && zoomed.spreadSteps == spreadSteps && zoomed.aspectSteps == aspectSteps;
    }

    @Override public int hashCode() {
        int hash = zoomSteps * 31 + viewSteps;
        for (int value : new int[] {x, y, z, height, yaw, pitch, spreadSteps, aspectSteps}) hash = hash * 31 + value;
        return hash;
    }

    @Override public String toString() { return String.format("%.1fx", zoom); }
}
