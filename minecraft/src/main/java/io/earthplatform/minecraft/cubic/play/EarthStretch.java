package io.earthplatform.minecraft.cubic.play;

import java.util.Locale;

/**
 * How stretched the map is at a latitude. An Earth world is a Mercator map scaled so a block is a
 * meter across at its home latitude. Away from the equator the same ground takes more blocks, and
 * toward it fewer, while a block of height is a meter everywhere: land north of home is drawn
 * wider than it is tall, land toward the equator narrower. These say by how much, for the places
 * where a player picks where to go or asks where they are.
 */
public final class EarthStretch {
    private EarthStretch() {}

    /**
     * How many times as wide as in life land at a latitude is drawn, in a world of that many meters
     * per block at the equator (see {@link io.earthplatform.minecraft.cubic.earth.EarthSettings}).
     */
    public static double at(double metresPerBlock, double latitude) {
        return 1.0 / (metresPerBlock * Math.cos(Math.toRadians(latitude)));
    }

    /** The same in a world whose home is at {@code home}. */
    public static double from(double home, double latitude) {
        return Math.cos(Math.toRadians(home)) / Math.cos(Math.toRadians(latitude));
    }

    /** Within 3% of life size counts as true: less than anyone could see. */
    public static boolean close(double stretch) { return Math.abs(stretch - 1.0) < 0.03; }

    /** A stretch as text, such as 1.75. */
    public static String text(double stretch) { return String.format(Locale.ROOT, "%.2f", stretch); }

    /** The latitude a new world's stretch is quoted at besides the equator: 60, or 75 for a home already that far out. */
    public static int farLatitude(double home) { return Math.abs(home) < 50 ? 60 : 75; }

    /** What /earth where says about the map at a spot. */
    public static String where(double metresPerBlock, double latitude) {
        double stretch = at(metresPerBlock, latitude);
        if (close(stretch)) return "The map is true to size here: a block is a meter across and a meter high.";
        return "The map draws land " + text(stretch) + " times as wide as in life here: a block is "
                + text(1.0 / stretch) + " m of ground across and 1 m high. It is true to size at this world's home latitude.";
    }
}
