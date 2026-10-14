package io.earthplatform.minecraft.cubic.play.client;

/** The globe's path for a trip: great-circle steps and how far out to pull back. */
final class FlightPath {
    private FlightPath() {}

    /** How far the globe pulls back: far enough to see both ends of the trip, and always a good way out. */
    static double farZoom(double fromLat, double fromLon, double toLat, double toLon, double nearZoom) {
        double angle = angle(fromLat, fromLon, toLat, toLon);
        double fit = Math.log(1.74 / Math.max(angle, 1e-6)) / Math.log(2);
        return clamp(fit, 0.15, nearZoom - 4);
    }

    /** The angle between two points as seen from the Earth's centre, in radians. */
    static double angle(double lat1, double lon1, double lat2, double lon2) {
        double[] a = unit(lat1, lon1), b = unit(lat2, lon2);
        return Math.acos(clamp(a[0] * b[0] + a[1] * b[1] + a[2] * b[2], -1, 1));
    }

    private static double[] unit(double lat, double lon) {
        double phi = Math.toRadians(lat), lambda = Math.toRadians(lon);
        return new double[]{Math.cos(phi) * Math.cos(lambda), Math.cos(phi) * Math.sin(lambda), Math.sin(phi)};
    }

    /** The point a fraction {@code t} of the way along the great circle between two points. */
    static double[] along(double lat1, double lon1, double lat2, double lon2, double t) {
        double[] a = unit(lat1, lon1), b = unit(lat2, lon2);
        double angle = Math.acos(clamp(a[0] * b[0] + a[1] * b[1] + a[2] * b[2], -1, 1));
        // Exactly opposite points have no one great circle between them; any will do.
        if (Math.PI - angle < 1e-6) return along(lat1, lon1, lat2 > 0 ? lat2 - 0.01 : lat2 + 0.01, lon2 + 0.01, t);
        double wa, wb;
        if (angle < 1e-9) { wa = 1 - t; wb = t; }
        else { wa = Math.sin((1 - t) * angle) / Math.sin(angle); wb = Math.sin(t * angle) / Math.sin(angle); }
        double x = wa * a[0] + wb * b[0], y = wa * a[1] + wb * b[1], z = wa * a[2] + wb * b[2];
        return new double[]{Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y))), Math.toDegrees(Math.atan2(y, x))};
    }

    static double smooth(double t) {
        t = clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
