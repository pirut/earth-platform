package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.cubic.CubicSettings;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for BuildTheEarth upright conformal Modified Airocean ({@link BteDymaxionProjection})
 * coordinate parity, round-trip accuracy, scale, out-of-net open ocean handling, local east vectors,
 * and world settings persistence.
 */
class BteDymaxionProjectionTest {
    private static final BteDymaxionProjection BTE = new BteDymaxionProjection();

    @Test void matchesOfficialBteReferenceCoordinates() {
        // Public BuildTheEarth / terraminusminus ProjectionTest landmarks (lon, lat) -> (x, z).
        double[][] references = {
                {48.856667, 2.350987, 2851660.278582057, -5049718.243628887},
                {40.714268, -74.005974, -8526456.75523275, -6021812.714103152},
                {51.500000, -0.166670, 2774758.1546624764, -5411708.236500686},
                {39.907500, 116.397230, 11571988.173618957, -6472387.375809908},
                {47.606210, -122.332070, -12410431.110669583, -6894851.702710003},
                {-33.875113, 151.208666, 20001061.636216827, -2223355.8371363534},
                {48.87378100000001, 2.295026, 2848192.3338641203, -5053053.018157968},
                {48.8926507, 2.236214, 2844585.5271490104, -5056657.959395678},
                {48.853474, 2.349270, 2851410.680220599, -5049403.7778784195},
                {48.853065, 2.348969, 2851372.726732094, -5049365.549214174},
        };
        for (double[] ref : references) {
            double lat = ref[0], lon = ref[1], expectedX = ref[2], expectedZ = ref[3];
            double[] block = BTE.toBlock(lat, lon);
            assertEquals(expectedX, block[0], 0.01, "X mismatch at " + lat + "," + lon);
            assertEquals(expectedZ, block[1], 0.01, "Z mismatch at " + lat + "," + lon);

            GeoPoint back = BTE.tryGeographic(expectedX, expectedZ);
            assertNotNull(back, "Inverse returned null at " + expectedX + "," + expectedZ);
            assertEquals(lat, back.latitude(), 1e-4, "Lat mismatch at " + expectedX + "," + expectedZ);
            assertEquals(lon, back.longitude(), 1e-4, "Lon mismatch at " + expectedX + "," + expectedZ);
        }
    }

    @Test void roundTripsGlobalLandmarksAndMaintainsNearUnitScale() {
        double[][] landmarks = {
                {40.7128, -74.0060},   // New York
                {51.5074, -0.1278},    // London
                {35.6762, 139.6503},   // Tokyo
                {-33.8688, 151.2093},  // Sydney
                {-22.9068, -43.1729},  // Rio de Janeiro
                {61.2181, -149.9003},  // Anchorage
                {64.1466, -21.9426},   // Reykjavik
                {-33.9249, 18.4241},   // Cape Town
                {-77.8419, 166.6863},  // McMurdo Station
                {89.5, 0.0},           // Near North Pole
                {-89.5, 120.0},        // Near South Pole
                {0.0, 0.0},            // Null Island
                {0.0, 179.5},          // Antimeridian east
                {0.0, -179.5},         // Antimeridian west
                {37.7456, -119.5332},  // Yosemite
                {27.9881, 86.9250},    // Mount Everest
                {59.4370, 24.7536},    // Tallinn
        };
        for (double[] pt : landmarks) {
            double lat = pt[0], lon = pt[1];
            double[] block = BTE.toBlock(lat, lon);
            assertTrue(Double.isFinite(block[0]) && Double.isFinite(block[1]));
            GeoPoint back = BTE.tryGeographic(block[0], block[1]);
            assertNotNull(back, "tryGeographic returned null for " + lat + "," + lon);
            assertEquals(lat, back.latitude(), 0.01, "Latitude round-trip error at " + lat + "," + lon);
            double dLon = Math.abs(back.longitude() - lon);
            if (dLon > 180) dLon = 360 - dLon;
            assertTrue(dLon < 0.01, "Longitude round-trip error " + dLon + " at " + lat + "," + lon);

            // BTE Modified Airocean keeps scale near ~1 m/block across the globe (within conformal face variation ~0.8-1.4 m/block).
            double scale = BTE.groundMetresPerBlock(block[0], block[1]);
            assertTrue(scale > 0.7 && scale < 1.5, "Unexpected local ground scale " + scale + " at " + lat + "," + lon);
        }
    }

    @Test void outOfNetPointsReturnNullAndGlobalElevationReturnsOpenOcean() {
        double[][] voids = {
                {50_000_000.0, 50_000_000.0},
                {-50_000_000.0, -50_000_000.0},
                {0.0, 25_000_000.0},
                {0.0, -25_000_000.0},
                {15_000_000.0, 15_000_000.0},
        };
        float[] flatLandTile = new float[256 * 256];
        Arrays.fill(flatLandTile, 123.0f);
        GlobalElevation dem = new GlobalElevation(BTE, (z, tx, ty) -> flatLandTile, 10);
        double[] sample = new double[3];

        for (double[] pt : voids) {
            assertNull(BTE.tryGeographic(pt[0], pt[1]), "Expected null outside net at " + pt[0] + "," + pt[1]);
            assertNull(BTE.tryBlockCentre((int) pt[0], (int) pt[1]));
            double metres = dem.sample(pt[0], pt[1], sample);
            assertEquals(GlobalElevation.OPEN_OCEAN_METRES, metres, 1e-6);
            assertEquals(0.0, sample[0], 1e-6);
            assertEquals(0.0, sample[1], 1e-6);
            assertEquals(1.0, sample[2], 1e-6);
        }
    }

    @Test void eastUnitPointsGeographicallyEastAndHasUnitLength() {
        double[][] places = {
                {40.7128, -74.0060},   // New York
                {51.5074, -0.1278},    // London
                {35.6762, 139.6503},   // Tokyo
                {-33.8688, 151.2093},  // Sydney
                {-22.9068, -43.1729},  // Rio
        };
        for (double[] pt : places) {
            double[] b = BTE.toBlock(pt[0], pt[1]);
            double[] east = BTE.eastUnit(b[0], b[1]);
            double len = Math.hypot(east[0], east[1]);
            assertEquals(1.0, len, 1e-6);
            GeoPoint stepped = BTE.tryGeographic(b[0] + east[0] * 100.0, b[1] + east[1] * 100.0);
            assertNotNull(stepped);
            double dLon = stepped.longitude() - pt[1];
            if (dLon > 180) dLon -= 360;
            if (dLon < -180) dLon += 360;
            assertTrue(dLon > 0, "eastUnit did not increase longitude at " + pt[0] + "," + pt[1]);
            assertTrue(Math.abs(stepped.latitude() - pt[0]) < 1e-4, "eastUnit drifted in latitude at " + pt[0] + "," + pt[1]);
        }
    }

    @Test void projectionFlagsAndSettingsRoundTrip(@TempDir Path dir) {
        assertEquals(BteDymaxionProjection.PROJECTION_ID, BTE.id());
        assertFalse(BTE.isWebMercator());
        assertFalse(BTE.wrapsEastWest());
        assertTrue(GlobeProjection.supported(MercatorGrid.PROJECTION_ID));
        assertTrue(GlobeProjection.supported(BteDymaxionProjection.PROJECTION_ID));
        assertFalse(GlobeProjection.supported("unknown-projection"));

        // BTE settings default metresPerBlock to 1.0 regardless of starting latitude.
        EarthSettings bte = EarthSettings.at(64.1466, -21.9426, BteDymaxionProjection.PROJECTION_ID);
        assertEquals(1.0, bte.metresPerBlock(), 1e-9);
        assertEquals(BteDymaxionProjection.PROJECTION_ID, bte.projection());

        // Mercator settings remain unchanged (1 / cos(lat)).
        EarthSettings merc = EarthSettings.at(64.1466, -21.9426);
        assertEquals(MercatorGrid.PROJECTION_ID, merc.projection());
        assertEquals(1.0 / Math.cos(Math.toRadians(64.1466)), merc.metresPerBlock(), 1e-9);

        // CubicSettings pins and reloads earth.projection cleanly.
        Path bteDir = dir.resolve("bte");
        CubicSettings.pinEarth(bteDir, bte, "Reykjavik");
        EarthSettings loaded = readEarthProjection(bteDir);
        assertEquals(BteDymaxionProjection.PROJECTION_ID, loaded.projection());
        assertEquals(1.0, loaded.metresPerBlock(), 1e-9);
        assertEquals(64.1466, loaded.latitude(), 1e-6);
        assertEquals(-21.9426, loaded.longitude(), 1e-6);

        Path mercDir = dir.resolve("merc");
        CubicSettings.pinEarth(mercDir, merc, "Reykjavik");
        EarthSettings loadedMerc = readEarthProjection(mercDir);
        assertEquals(MercatorGrid.PROJECTION_ID, loadedMerc.projection());
        assertEquals(merc.metresPerBlock(), loadedMerc.metresPerBlock(), 1e-9);
    }

    private static EarthSettings readEarthProjection(Path folder) {
        try {
            var props = new java.util.Properties();
            try (var in = java.nio.file.Files.newInputStream(folder.resolve("generator.properties"))) {
                props.load(in);
            }
            return new EarthSettings(
                    Double.parseDouble(props.getProperty("earth.latitude")),
                    Double.parseDouble(props.getProperty("earth.longitude")),
                    Double.parseDouble(props.getProperty("earth.metresPerBlock")),
                    props.getProperty("earth.projection", MercatorGrid.PROJECTION_ID));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
