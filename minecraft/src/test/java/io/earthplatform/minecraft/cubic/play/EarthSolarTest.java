package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import org.junit.jupiter.api.Test;

class EarthSolarTest {
    private static final int YEAR = EarthSolar.DEFAULT_YEAR_DAYS;
    private static final long NORTHERN_SUMMER_SOLSTICE_DAY = YEAR / 4;     // day 24: declination +23.44°
    private static final long SOUTHERN_SUMMER_SOLSTICE_DAY = 3 * YEAR / 4; // day 72: declination -23.44°

    @Test void primeMeridianEquinoxMatchesMinecraftClock() {
        // Year length 0 holds perpetual equinox (declination = 0°) so dayTime maps cleanly to MC's clock.
        assertEquals(0.75F, EarthSolar.timeOfDay(0L, 0.0F, 0.0, 0.0, 0), 1e-5F);
        assertEquals(0.00F, EarthSolar.timeOfDay(6000L, 0.0F, 0.0, 0.0, 0), 1e-5F);
        assertEquals(0.25F, EarthSolar.timeOfDay(12000L, 0.0F, 0.0, 0.0, 0), 1e-5F);
        assertEquals(0.50F, EarthSolar.timeOfDay(18000L, 0.0F, 0.0, 0.0, 0), 1e-5F);
    }

    @Test void longitudeSeparatesAustraliaAndCanadaByAboutFifteenHours() {
        double sydneyLat = -33.87, sydneyLon = 151.21;
        double ottawaLat = 45.42, ottawaLon = -75.70;

        // Solar hour angle difference is ~226.91° (~15.13 hours): east is ahead, west is behind.
        double sydneyH = EarthSolar.localSolarHourAngle(0.25, sydneyLon);
        double ottawaH = EarthSolar.localSolarHourAngle(0.25, ottawaLon);
        double aheadHours = Math.floorMod(Math.round(Math.toDegrees(sydneyH - ottawaH) * 100.0), 36000L) / 1500.0;
        assertEquals(15.13, aheadHours, 0.05, "Sydney should be ~15.13 hours ahead of Ottawa");

        // At 06:00 UTC (dayTime = 0, equinox): Sydney is ~16:05 (daylight), Ottawa is ~00:57 (night).
        float sydneyMorningUtc = EarthSolar.timeOfDay(0L, 0.0F, sydneyLat, sydneyLon, 0);
        float ottawaMorningUtc = EarthSolar.timeOfDay(0L, 0.0F, ottawaLat, ottawaLon, 0);
        assertTrue(EarthSolar.isDay(sydneyMorningUtc), "Sydney should be in daylight at 06:00 UTC: " + sydneyMorningUtc);
        assertFalse(EarthSolar.isDay(ottawaMorningUtc), "Ottawa should be in night at 06:00 UTC: " + ottawaMorningUtc);
        assertEquals(0, EarthSolar.skyDarken(sydneyMorningUtc, 0.0F, 0.0F));
        assertEquals(11, EarthSolar.skyDarken(ottawaMorningUtc, 0.0F, 0.0F));

        // At 12:00 UTC (dayTime = 6000, equinox): Sydney is ~22:05 (night), Ottawa is ~06:57 (daylight).
        float sydneyNoonUtc = EarthSolar.timeOfDay(6000L, 0.0F, sydneyLat, sydneyLon, 0);
        float ottawaNoonUtc = EarthSolar.timeOfDay(6000L, 0.0F, ottawaLat, ottawaLon, 0);
        assertFalse(EarthSolar.isDay(sydneyNoonUtc), "Sydney should be at night at 12:00 UTC: " + sydneyNoonUtc);
        assertTrue(EarthSolar.isDay(ottawaNoonUtc), "Ottawa should be in daylight at 12:00 UTC: " + ottawaNoonUtc);
    }

    @Test void seasonsFlipBetweenNorthernAndSouthernHemispheres() {
        long northSummerStart = NORTHERN_SUMMER_SOLSTICE_DAY * 24000L;
        long southSummerStart = SOUTHERN_SUMMER_SOLSTICE_DAY * 24000L;

        double northSummerDecl = EarthSolar.solarDeclinationDeg(NORTHERN_SUMMER_SOLSTICE_DAY, YEAR);
        double southSummerDecl = EarthSolar.solarDeclinationDeg(SOUTHERN_SUMMER_SOLSTICE_DAY, YEAR);
        assertEquals(EarthSolar.AXIAL_TILT_DEG, northSummerDecl, 1e-6);
        assertEquals(-EarthSolar.AXIAL_TILT_DEG, southSummerDecl, 1e-6);

        // Northern summer solstice: Canada (+55°N) has long days (> 16h), Patagonia (-55°S) has short winter days (< 8h).
        double canadaSummerHours = measuredDaylightHours(northSummerStart, 55.0, -100.0, YEAR);
        double patagoniaWinterHours = measuredDaylightHours(northSummerStart, -55.0, -70.0, YEAR);
        assertTrue(canadaSummerHours > 16.5, "Canada +55°N summer day should exceed 16.5h, got " + canadaSummerHours);
        assertTrue(patagoniaWinterHours < 7.5, "Patagonia -55°S winter day should be under 7.5h, got " + patagoniaWinterHours);
        assertEquals(24.0, canadaSummerHours + patagoniaWinterHours, 0.15);

        // Southern summer solstice: flipped!
        double canadaWinterHours = measuredDaylightHours(southSummerStart, 55.0, -100.0, YEAR);
        double patagoniaSummerHours = measuredDaylightHours(southSummerStart, -55.0, -70.0, YEAR);
        assertTrue(canadaWinterHours < 7.5, "Canada +55°N winter day should be under 7.5h, got " + canadaWinterHours);
        assertTrue(patagoniaSummerHours > 16.5, "Patagonia -55°S summer day should exceed 16.5h, got " + patagoniaSummerHours);

        // Sydney (-33.87°S) vs Ottawa (+45.42°N) experience opposite seasons.
        assertTrue(EarthSolar.dayLengthHours(-33.87, northSummerDecl) < 10.0);
        assertTrue(EarthSolar.dayLengthHours(45.42, northSummerDecl) > 15.0);
        assertTrue(EarthSolar.dayLengthHours(-33.87, southSummerDecl) > 14.0);
        assertTrue(EarthSolar.dayLengthHours(45.42, southSummerDecl) < 9.0);
    }

    @Test void equinoxAndEquatorKeepTwelveHourDayNightSymmetry() {
        // At equator, every day of the year has 12h day / 12h night.
        for (long day : new long[] {0, NORTHERN_SUMMER_SOLSTICE_DAY, YEAR / 2, SOUTHERN_SUMMER_SOLSTICE_DAY}) {
            double hours = measuredDaylightHours(day * 24000L, 0.0, 20.0, YEAR);
            assertEquals(12.0, hours, 0.05, "Equator should have 12h daylight on day " + day);
        }

        // With yearLengthDays = 0 (perpetual equinox), every latitude has 12h day / 12h night.
        for (double lat : new double[] {-80.0, -55.0, -33.87, 0.0, 45.42, 78.0}) {
            double hours = measuredDaylightHours(0L, lat, -45.0, 0);
            assertEquals(12.0, hours, 0.05, "Equinox should have 12h daylight at lat " + lat);
        }
    }

    @Test void polarDayAndPolarNightBehaveSmoothlyWithoutSettingOrRising() {
        double svalbardLat = 78.22, svalbardLon = 15.65;
        long northSummer = NORTHERN_SUMMER_SOLSTICE_DAY * 24000L;
        long northWinter = SOUTHERN_SUMMER_SOLSTICE_DAY * 24000L;

        // Svalbard in northern summer: midnight sun (strictly daytime all 24h).
        float summerMin = 1.0F, summerMax = 0.0F;
        for (int tick = 0; tick < 24000; tick += 20) {
            float t = EarthSolar.timeOfDay(northSummer + tick, 0.0F, svalbardLat, svalbardLon, YEAR);
            assertTrue(EarthSolar.isDay(t), "Svalbard summer must stay in daylight at tick " + tick + ", got " + t);
            assertTrue(t >= 0.0F && t < 0.25F, "Midnight sun should stay in [0, 0.25), got " + t);
            assertTrue(EarthSolar.skyDarken(t, 0.0F, 0.0F) < 4, "Midnight sun should stay bright");
            summerMin = Math.min(summerMin, t);
            summerMax = Math.max(summerMax, t);
        }
        assertTrue(summerMax > summerMin + 0.05F, "Midnight sun should dip toward the horizon at solar midnight");

        // Svalbard in northern winter: polar night (strictly nighttime all 24h).
        float winterMin = 1.0F, winterMax = 0.0F;
        for (int tick = 0; tick < 24000; tick += 20) {
            float t = EarthSolar.timeOfDay(northWinter + tick, 0.0F, svalbardLat, svalbardLon, YEAR);
            assertFalse(EarthSolar.isDay(t), "Svalbard winter must stay in night at tick " + tick + ", got " + t);
            assertTrue(t > 0.25F && t <= 0.50F, "Polar night should stay in (0.25, 0.50], got " + t);
            assertTrue(EarthSolar.skyDarken(t, 0.0F, 0.0F) >= 4, "Polar night should remain dark");
            winterMin = Math.min(winterMin, t);
            winterMax = Math.max(winterMax, t);
        }
        assertTrue(winterMax > winterMin + 0.05F, "Polar night should brighten slightly toward twilight at solar noon");

        // Antarctica (-78°S) has the opposite polar regime on the same day.
        assertFalse(EarthSolar.isDay(EarthSolar.timeOfDay(northSummer + 6000L, 0.0F, -78.0, 0.0, YEAR)));
        assertTrue(EarthSolar.isDay(EarthSolar.timeOfDay(northWinter + 6000L, 0.0F, -78.0, 0.0, YEAR)));
    }

    @Test void continuousAcrossWraparoundAndFiniteEverywhere() {
        double[] latitudes = {-85.0, -78.0, -60.0, -45.0, 0.0, 45.0, 60.0, 78.0, 85.0};
        long[] seasonDays = {0L, NORTHERN_SUMMER_SOLSTICE_DAY, YEAR / 2, SOUTHERN_SUMMER_SOLSTICE_DAY};

        for (long day : seasonDays) {
            long base = day * 24000L;
            for (double lat : latitudes) {
                float previous = EarthSolar.timeOfDay(base, 0.0F, lat, 37.5, YEAR);
                for (int tick = 10; tick <= 24000; tick += 10) {
                    float current = EarthSolar.timeOfDay(base + tick, 0.0F, lat, 37.5, YEAR);
                    assertTrue(Float.isFinite(current) && current >= 0.0F && current < 1.0F,
                            "Out of [0, 1) at day=" + day + " lat=" + lat + " tick=" + tick + ": " + current);
                    float diff = Math.abs(current - previous);
                    float circularStep = Math.min(diff, 1.0F - diff);
                    assertTrue(circularStep < 0.01F,
                            "Discontinuity " + circularStep + " at day=" + day + " lat=" + lat + " tick=" + tick);
                    previous = current;
                }
            }
        }

        // Continuity across H = -π..+π (midnight wraparound, sunrise, noon, sunset) right at the polar circles.
        for (double lat : new double[] {-66.6, -66.56, -66.5, -66.0, 66.0, 66.5, 66.56, 66.6}) {
            for (double decl : new double[] {-EarthSolar.AXIAL_TILT_DEG, 0.0, EarthSolar.AXIAL_TILT_DEG}) {
                int steps = 2400;
                float previous = EarthSolar.timeOfDayForDeclination(-Math.PI, lat, decl);
                for (int i = 1; i <= steps; i++) {
                    double h = -Math.PI + 2.0 * Math.PI * i / steps;
                    float current = EarthSolar.timeOfDayForDeclination(h, lat, decl);
                    assertTrue(Float.isFinite(current) && current >= 0.0F && current < 1.0F);
                    float diff = Math.abs(current - previous);
                    float circularStep = Math.min(diff, 1.0F - diff);
                    assertTrue(circularStep < 0.01F,
                            "Polar-circle discontinuity " + circularStep + " at lat=" + lat + " decl=" + decl + " step=" + i);
                    previous = current;
                }
            }
        }

        // Sweep full lat/lon grid, negative and large dayTime, and partial ticks.
        for (int lat = -85; lat <= 85; lat += 5) {
            for (int lon = -180; lon <= 180; lon += 15) {
                for (long t : new long[] {-50_000L, 0L, 12_345L, 100_000_000L}) {
                    float value = EarthSolar.timeOfDay(t, 0.5F, lat, lon, YEAR);
                    assertTrue(Float.isFinite(value) && value >= 0.0F && value < 1.0F,
                            "Invalid value at lat=" + lat + " lon=" + lon + " t=" + t + ": " + value);
                }
            }
        }

        // Non-finite inputs fall back safely without NaN.
        float safe = EarthSolar.timeOfDay(6000L, Float.NaN, Double.NaN, Double.POSITIVE_INFINITY, YEAR);
        assertTrue(Float.isFinite(safe) && safe >= 0.0F && safe < 1.0F);
    }

    @Test void partialTickInterpolatesSmoothly() {
        float t0 = EarthSolar.timeOfDay(6000L, 0.0F, 0.0, 0.0, 0);
        float tHalf = EarthSolar.timeOfDay(6000L, 0.5F, 0.0, 0.0, 0);
        float t1 = EarthSolar.timeOfDay(6001L, 0.0F, 0.0, 0.0, 0);
        assertTrue(tHalf > t0 && tHalf < t1, "Expected " + t0 + " < " + tHalf + " < " + t1);
        assertEquals((t0 + t1) / 2.0F, tHalf, 1e-6F);
    }

    @Test void geographicHelperClampsOutsideMercatorBounds() {
        MercatorGrid grid = new MercatorGrid(1.0);
        double beyondPole = grid.worldWidthBlocks();
        GeoPoint north = EarthSolar.geographic(grid, 0.0, -beyondPole);
        GeoPoint south = EarthSolar.geographic(grid, 0.0, beyondPole);
        assertEquals(MercatorGrid.MAX_LATITUDE, north.latitude(), 1e-6);
        assertEquals(-MercatorGrid.MAX_LATITUDE, south.latitude(), 1e-6);
    }

    @Test void skyDarkenMatchesVanillaNoonMidnightAndWeather() {
        assertEquals(0, EarthSolar.skyDarken(0.0F, 0.0F, 0.0F));
        assertEquals(11, EarthSolar.skyDarken(0.5F, 0.0F, 0.0F));
        assertTrue(EarthSolar.skyDarken(0.0F, 1.0F, 0.0F) > 0, "Rain should darken clear noon");
        assertTrue(EarthSolar.skyDarken(0.0F, 1.0F, 1.0F) > EarthSolar.skyDarken(0.0F, 1.0F, 0.0F),
                "Thunder should darken further than rain alone");
    }

    private static double measuredDaylightHours(long dayStartTick, double latitudeDeg, double longitudeDeg, int yearDays) {
        int step = 20;
        int daySteps = 0, totalSteps = 0;
        for (int tick = 0; tick < 24000; tick += step) {
            float t = EarthSolar.timeOfDay(dayStartTick + tick, 0.0F, latitudeDeg, longitudeDeg, yearDays);
            if (EarthSolar.isDay(t)) daySteps++;
            totalSteps++;
        }
        return 24.0 * daySteps / totalSteps;
    }
}
