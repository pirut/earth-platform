package io.earthplatform.geography.globe;

/**
 * Web Mercator rows and latitudes on a map of square pixel tiles, shared by the sources that read
 * or lay out tiles on that grid. A map {@code size} pixels across spans 360 degrees of longitude
 * and about 85.05 degrees either side of the equator; row 0 is its northern edge.
 *
 * <p>Callers clamp or offset the results themselves: these are the bare formulas, written once so
 * every source computes them with the same floating-point steps.
 */
final class Mercator {
    private Mercator() {}

    /** Latitude in degrees of a global pixel row position on a map {@code size} pixels tall, unclamped. */
    static double latitude(double row, double size) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * row / size))));
    }

    /** Global pixel row position of a latitude {@code phi}, in radians, on a map {@code size} pixels tall, unclamped. */
    static double row(double phi, double size) {
        return (1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2 * size;
    }
}
