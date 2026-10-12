package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.MissingTerrainException;

/**
 * Spherical Web Mercator block grid, projection id {@code web-mercator-v1}.
 *
 * <p>X points east, Z points south, origin at (0°, 0°). One block spans {@code metresPerBlock} projected metres; at the
 * equator that equals ground metres, elsewhere ground metres per block are {@code metresPerBlock / scaleFactor}. The
 * projection is conformal: local shapes and angles are preserved everywhere, so terrain is never stretched in one axis.
 * Block coordinates are a linear transform of slippy-map pixel coordinates, so tiled Web Mercator rasters need no
 * per-sample trigonometry. Latitudes beyond ±{@value #MAX_LATITUDE} have no coverage and fail explicitly.
 */
public record MercatorGrid(double metresPerBlock) {
    public static final double RADIUS = 6_378_137.0;
    public static final double MAX_LATITUDE = 85.05112877980659;
    public static final String PROJECTION_ID = "web-mercator-v1";

    public MercatorGrid {
        if (!Double.isFinite(metresPerBlock) || metresPerBlock <= 0) throw new IllegalArgumentException("Invalid metres per block");
    }

    /** Circumference in blocks; also the full north/south extent of the covered square. */
    public double worldWidthBlocks() { return 2 * Math.PI * RADIUS / metresPerBlock; }

    public double blockX(GeoPoint point) { return Math.toRadians(point.longitude()) * RADIUS / metresPerBlock; }

    public double blockZ(GeoPoint point) {
        double lat = point.latitude();
        if (Math.abs(lat) > MAX_LATITUDE) throw new MissingTerrainException("Latitude outside Web Mercator coverage: " + lat);
        double phi = Math.toRadians(lat);
        return -Math.log(Math.tan(Math.PI / 4 + phi / 2)) * RADIUS / metresPerBlock;
    }

    /** Geographic coordinate of a continuous block-space position; X wraps around the globe. */
    public GeoPoint geographic(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("Non-finite block position");
        double half = worldWidthBlocks() / 2;
        if (Math.abs(z) > half) throw new MissingTerrainException("Block Z outside Web Mercator coverage: " + z);
        double lon = Math.toDegrees(x * metresPerBlock / RADIUS);
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(-z * metresPerBlock / RADIUS)) - Math.PI / 2);
        return new GeoPoint(lat, lon);
    }

    /** Centre of the integer block column, matching how a voxel at (x, z) occupies [x, x+1). */
    public GeoPoint blockCentre(long x, long z) { return geographic(x + 0.5, z + 0.5); }

    /** Mercator scale factor sec(latitude): projected metres per ground metre at this Z. */
    public double scaleFactor(double z) { return Math.cosh(z * metresPerBlock / RADIUS); }

    /** Ground metres covered by one block at this Z. */
    public double groundMetresPerBlock(double z) { return metresPerBlock / scaleFactor(z); }

    /** Continuous global slippy-map pixel coordinate (256-pixel tiles) for block-space X at a zoom level. */
    public double pixelX(double x, int zoom) { return (x / worldWidthBlocks() + 0.5) * pixelsAt(zoom); }

    public double pixelY(double z, int zoom) { return (z / worldWidthBlocks() + 0.5) * pixelsAt(zoom); }

    /** Blocks spanned by one source pixel at this zoom; constant everywhere in this projection. */
    public double blocksPerPixel(int zoom) { return worldWidthBlocks() / pixelsAt(zoom); }

    static double pixelsAt(int zoom) {
        if (zoom < 0 || zoom > 22) throw new IllegalArgumentException("Unsupported zoom " + zoom);
        return 256.0 * (1L << zoom);
    }
}
