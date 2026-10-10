package io.earthplatform.geography;

/** Versioned spherical equirectangular projection. One block = one PROJECTED metre, not a global ground-metre guarantee. */
public record EquirectangularProjection(GeoPoint origin, double standardParallel) {
    public static final String ID = "equirectangular-v1";
    public static final double RADIUS_METRES = 6_378_137.0;
    public EquirectangularProjection {
        if (origin == null || !Double.isFinite(standardParallel) || Math.abs(standardParallel) >= 89)
            throw new IllegalArgumentException("Projection origin required; standard parallel must lie inside +/-89 degrees");
    }
    public GeoPoint toGeographic(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("Non-finite projected position");
        double latitude = origin.latitude() - StrictMath.toDegrees(z / RADIUS_METRES);
        if (latitude < -90 || latitude > 90) throw new MissingTerrainException("Outside projection's polar boundary");
        double longitude = origin.longitude() + StrictMath.toDegrees(x / (RADIUS_METRES * StrictMath.cos(StrictMath.toRadians(standardParallel))));
        return new GeoPoint(latitude, longitude);
    }
    /** Samples the horizontal centre of a block. x is east, z is south, y is up. */
    public GeoPoint blockCentre(long x, long z) { return toGeographic(x + 0.5, z + 0.5); }
    public ProjectedPoint toProjected(GeoPoint point) {
        return new ProjectedPoint(RADIUS_METRES * StrictMath.toRadians(GeoPoint.wrapLongitude(point.longitude() - origin.longitude())) * StrictMath.cos(StrictMath.toRadians(standardParallel)),
                RADIUS_METRES * StrictMath.toRadians(origin.latitude() - point.latitude()));
    }
    public double widthMetres() { return 2 * StrictMath.PI * RADIUS_METRES * StrictMath.cos(StrictMath.toRadians(standardParallel)); }
    public record ProjectedPoint(double x, double z) {}
}
