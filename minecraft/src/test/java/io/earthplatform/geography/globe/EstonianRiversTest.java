package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EstonianRiversTest {
    @Test void surveysCoverThePiritaWhereTheSatelliteMissedIt() {
        GeoPoint[] channel = {
                new GeoPoint(59.4354039, 24.9270203),
                new GeoPoint(59.4357949, 24.9251616),
                new GeoPoint(59.4346395, 24.9301091)
        };
        for (double scale : new double[]{1, 1.265, 2}) {
            MercatorGrid grid = new MercatorGrid(scale);
            for (GeoPoint point : channel)
                assertTrue(EstonianRivers.contains(grid, grid.blockX(point), grid.blockZ(point), 1), point.toString());
            GeoPoint dry = new GeoPoint(59.4354039, 24.9238203);
            assertFalse(EstonianRivers.contains(grid, grid.blockX(dry), grid.blockZ(dry), 1));
            GeoPoint outside = new GeoPoint(38.03122, 23.65955);
            assertFalse(EstonianRivers.contains(grid, grid.blockX(outside), grid.blockZ(outside), 1));
        }
    }
}
