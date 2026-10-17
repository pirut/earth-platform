package io.earthplatform.minecraft.cubic;

/**
 * What a player looks at loads first: cubes, far columns and the data under them are made, sent
 * and meshed in order of how far they are, with ground out of view counted as further off than it
 * is. Nothing is left out, so ground behind still comes in, only after what is in view at the same
 * distance. {@code -Dearthplatform.view.order=false} makes and meshes near cubes by distance alone, as
 * before; far columns always leaned this way, by {@link #BEHIND}.
 */
public final class ViewOrder {
    private ViewOrder() {}

    public static final boolean ON = !"false".equals(System.getProperty("earthplatform.view.order"));
    /**
     * How many times as far ground straight behind counts as. Ground within 60 degrees of the look
     * counts as it is: a view is about 100 degrees across, and that leaves room to glance aside.
     */
    public static final double BEHIND = Math.max(1.0, Double.parseDouble(System.getProperty("earthplatform.view.behind", "4")));
    /** Directions a look is rounded to for cube orders: 22.5 degrees each. */
    public static final int SECTORS = 16;

    /**
     * How many times as far a place counts as, from 1 in view to {@link #BEHIND} straight behind.
     * Places closer than {@code close} count as they are, as the ground under the player.
     */
    public static double later(double dx, double dz, double lookX, double lookZ, double close) {
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < close) return 1.0;
        double facing = (dx * lookX + dz * lookZ) / length;
        return facing >= 0.5 ? 1.0 : 1.0 + (BEHIND - 1.0) * (0.5 - facing) / 1.5;
    }

    /** Minecraft's yaw in degrees to a horizontal look: x and z of a unit vector. */
    public static double lookX(double yawDegrees) { return -Math.sin(Math.toRadians(yawDegrees)); }
    public static double lookZ(double yawDegrees) { return Math.cos(Math.toRadians(yawDegrees)); }

    /** The sector a yaw falls in, or -1 when view order is off. */
    public static int sector(double yawDegrees) {
        return ON ? Math.floorMod((int) Math.round(yawDegrees / (360.0 / SECTORS)), SECTORS) : -1;
    }

    /** A sector's middle yaw in degrees. */
    public static double sectorYaw(int sector) { return sector * (360.0 / SECTORS); }
}
