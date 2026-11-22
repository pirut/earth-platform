package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.globe.BteDymaxionProjection;
import io.earthplatform.geography.globe.GlobeProjection;
import io.earthplatform.geography.globe.MercatorGrid;

/**
 * What an Earth world is pinned to when it is made: its home point, where new players arrive, the
 * grid's scale, and its map projection. In Web Mercator one block is one metre across at the home
 * latitude; in BTE Dymaxion one block is nominally one metre across continents (~1:1 scale).
 */
public record EarthSettings(double latitude, double longitude, double metresPerBlock, String projection) {
    /** El Capitan Meadow in Yosemite Valley. */
    public static final double DEFAULT_LATITUDE = 37.7240, DEFAULT_LONGITUDE = -119.6365;

    public EarthSettings(double latitude, double longitude, double metresPerBlock) {
        this(latitude, longitude, metresPerBlock, MercatorGrid.PROJECTION_ID);
    }

    public EarthSettings {
        if (!(Math.abs(latitude) <= 80) || !Double.isFinite(longitude) || !(metresPerBlock > 0))
            throw new IllegalArgumentException("Invalid Earth settings");
        if (projection == null || !GlobeProjection.supported(projection))
            throw new IllegalArgumentException("Unsupported Earth projection: " + projection);
    }

    /** Settings for a Web Mercator world whose home is a latitude and longitude. */
    public static EarthSettings at(double latitude, double longitude) {
        return at(latitude, longitude, MercatorGrid.PROJECTION_ID);
    }

    /** Settings for a world with the given projection whose home is a latitude and longitude. */
    public static EarthSettings at(double latitude, double longitude, String projection) {
        double metresPerBlock = BteDymaxionProjection.PROJECTION_ID.equals(projection)
                ? 1.0
                : 1.0 / Math.cos(Math.toRadians(latitude));
        return new EarthSettings(latitude, longitude, metresPerBlock, projection);
    }

    /** Home from {@code -Dearthplatform.earth.home=LAT,LON}, else Yosemite Valley. */
    public static EarthSettings defaults() {
        String home = System.getProperty("earthplatform.earth.home");
        if (home != null) {
            String[] parts = home.split(",");
            return at(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
        }
        return at(DEFAULT_LATITUDE, DEFAULT_LONGITUDE);
    }
}
