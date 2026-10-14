package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.globe.MercatorGrid;

/**
 * What an Earth world is pinned to when it is made: its home point, where new players arrive, and
 * the grid's scale. One block is one metre across at the home latitude.
 */
public record EarthSettings(double latitude, double longitude, double metresPerBlock) {
    /** El Capitan Meadow in Yosemite Valley. */
    public static final double DEFAULT_LATITUDE = 37.7240, DEFAULT_LONGITUDE = -119.6365;

    public EarthSettings {
        if (!(Math.abs(latitude) <= 80) || !Double.isFinite(longitude) || !(metresPerBlock > 0)) throw new IllegalArgumentException("Invalid Earth settings");
    }

    /** Settings for a world whose home is a latitude and longitude. */
    public static EarthSettings at(double latitude, double longitude) {
        return new EarthSettings(latitude, longitude, 1.0 / Math.cos(Math.toRadians(latitude)));
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

    public String projection() { return MercatorGrid.PROJECTION_ID; }
}
