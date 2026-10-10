package io.earthplatform.geography;

/** Geographic registration is the centre of the northwest SAMPLE, with positive steps east/south. */
public record TileSpec(String id, String path, String sha256, double west, double north,
                       double stepLon, double stepLat, int width, int height) {
    public static final int MAX_CELLS = 4_194_304;
    public TileSpec {
        if (id == null || id.isBlank() || path == null || path.isBlank() || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Tile id, location and pinned lowercase SHA-256 required");
        if (width < 2 || height < 2 || (long) width * height > MAX_CELLS || !Double.isFinite(west) || !Double.isFinite(north)
            || !Double.isFinite(stepLon) || !Double.isFinite(stepLat) || stepLon <= 0 || stepLat <= 0
            || stepLon * (width - 1) > 360 || north > 90 || north - stepLat * (height - 1) < -90)
            throw new IllegalArgumentException("Invalid raster registration or dimensions");
    }
    public long encodedBytes() { return 44L + (long) width * height * 4; }
    public double sampleX(GeoPoint point) {
        double delta = GeoPoint.wrapLongitude(point.longitude() - west);
        if (delta < -1e-10) delta += 360;
        return delta / stepLon;
    }
    public double sampleY(GeoPoint point) { return (north - point.latitude()) / stepLat; }
    /** Only the interpolation-safe sample-centre footprint is covered. Importers should retain a one-sample halo. */
    public boolean covers(GeoPoint point) {
        double x = sampleX(point), y = sampleY(point);
        return x >= -1e-8 && x <= width - 1 + 1e-8 && y >= -1e-8 && y <= height - 1 + 1e-8;
    }
}
