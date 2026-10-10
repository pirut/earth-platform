package io.earthplatform.geography;

/** WGS84 longitude/latitude in degrees. Longitude has a canonical [-180,180) representation. */
public record GeoPoint(double latitude, double longitude) {
    public GeoPoint {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90 || !Double.isFinite(longitude))
            throw new IllegalArgumentException("Invalid WGS84 coordinate");
        longitude = wrapLongitude(longitude);
    }
    public static double wrapLongitude(double degrees) {
        if (!Double.isFinite(degrees)) throw new IllegalArgumentException("Non-finite longitude");
        double r = degrees % 360.0;
        if (r >= 180) r -= 360;
        if (r < -180) r += 360;
        return r == 0 ? 0 : r;
    }
}
