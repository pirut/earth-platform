package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.geography.globe.ClimateSource;
import javax.annotation.Nullable;

/**
 * General, physically grounded regional weather profile for any point on Earth.
 *
 * <p>Derived solely from latitude, elevation, hemisphere-aware seasonal phase, and optional CHELSA
 * climatology (Koppen class, mean temperature, warmest-quarter temperature, and annual
 * precipitation). No city, state, country, or coordinate tables are used anywhere.
 */
public record EarthWeatherProfile(
        double temperatureC,
        double seaLevelMeanC,
        double dewpointC,
        double moisture,
        double cloudiness,
        double rainChance,
        double rainIntensity,
        double stormEnergy,
        double windShear,
        double tornadoPotential,
        double hurricanePotential,
        double seaSurfaceTempC
) {
    /**
     * Computes a regional weather profile at {@code (latitude, longitude)} and {@code elevationMetres}
     * above sea level for {@code yearFraction} in {@code [0, 1)} (0 = Jan 1, 0.5 = Jul 1).
     */
    public static EarthWeatherProfile of(
            double latitude,
            double longitude,
            double elevationMetres,
            double yearFraction,
            @Nullable ClimateSource.Climate climate
    ) {
        double lat = clamp(latitude, -90.0, 90.0);
        double absLat = Math.abs(lat);
        double elev = Math.max(0.0, Double.isFinite(elevationMetres) ? elevationMetres : 0.0);
        double year = ((yearFraction % 1.0) + 1.0) % 1.0;

        // Hemisphere-aware seasonal sines:
        // summer peaks mid-July (NH) / mid-January (SH), matching PM Weather's SeasonHandler.
        double hemi = lat >= 0.0 ? 1.0 : -1.0;
        double summer = hemi * Math.sin(2.0 * Math.PI * (year - 3.5 / 12.0));
        // Baroclinic spring peak ~1.5 months ahead of thermal summer (mid-May NH / mid-Nov SH).
        double spring = hemi * Math.sin(2.0 * Math.PI * (year - 2.0 / 12.0));
        // Ocean thermal lag ~1.5 months after summer solstice (early Sep NH / early Mar SH).
        double lateSummer = hemi * Math.sin(2.0 * Math.PI * (year - 5.0 / 12.0));

        double zonalSeaMean = 27.0 - 0.0074 * absLat * absLat;
        double meanTemp;
        double seaLevelMean;
        double amplitude;
        double annualRainMm;
        int koppen;

        if (climate != null) {
            koppen = climate.koppen();
            meanTemp = climate.meanTemperature();
            seaLevelMean = meanTemp + ClimateSource.LAPSE_RATE * elev;
            amplitude = Math.max(1.5, (climate.warmestQuarter() - meanTemp) * 1.15);
            annualRainMm = Math.max(0.0, climate.precipitation());
        } else {
            koppen = ClimateSource.NO_DATA;
            seaLevelMean = zonalSeaMean;
            meanTemp = seaLevelMean - ClimateSource.LAPSE_RATE * elev;
            amplitude = clamp(2.0 + 0.25 * Math.max(0.0, absLat - 10.0), 2.0, 14.0);
            annualRainMm = 900.0 * Math.exp(-Math.pow((absLat - 5.0) / 14.0, 2.0))
                    + 750.0 * Math.exp(-Math.pow((absLat - 52.0) / 16.0, 2.0))
                    + 250.0;
        }

        double temperatureC = meanTemp + amplitude * summer;

        // Continentality from seasonal temperature amplitude relative to a maritime baseline.
        double maritimeAmp = 1.5 + 0.10 * absLat;
        double continentality = clamp((amplitude - maritimeAmp) / 9.0, 0.0, 1.0);

        boolean isDesert = koppen == ClimateSource.BWH || koppen == ClimateSource.BWK;
        boolean isSteppe = koppen == ClimateSource.BSH || koppen == ClimateSource.BSK;
        boolean isOceanic = koppen == ClimateSource.CFB || koppen == ClimateSource.CFC;
        boolean isMonsoon = isDryWinterMonsoon(koppen);
        boolean isMediterranean = isDrySummer(koppen);

        // Seasonal rainfall distribution driven by Koppen dry-season regime.
        double seasonalRainFactor;
        if (koppen == ClimateSource.AM) {
            seasonalRainFactor = 0.25 + 1.65 * Math.pow(0.5 + 0.5 * summer, 1.5);
        } else if (isMonsoon) {
            seasonalRainFactor = 0.12 + 1.88 * Math.pow(0.5 + 0.5 * summer, 1.7);
        } else if (isMediterranean) {
            seasonalRainFactor = 0.20 + 1.55 * Math.pow(0.5 - 0.5 * summer, 1.5);
        } else if (isOceanic) {
            seasonalRainFactor = 1.0 - 0.15 * summer;
        } else if (koppen == ClimateSource.CFA || koppen == ClimateSource.DFA
                || koppen == ClimateSource.DFB || koppen == ClimateSource.BSK) {
            seasonalRainFactor = 0.90 + 0.30 * Math.max(0.0, spring);
        } else {
            seasonalRainFactor = 1.0;
        }

        double seasonalRainMm = annualRainMm * seasonalRainFactor;

        // Potential evapotranspiration proxy (Thornthwaite-like) and moisture index.
        double petMm = Math.max(300.0, 500.0 + 38.0 * Math.max(0.0, temperatureC));
        double humidityIndex = clamp(seasonalRainMm / petMm, 0.0, 2.2);
        if (isDesert) {
            humidityIndex = Math.min(humidityIndex, 0.16);
        } else if (isSteppe) {
            humidityIndex = Math.min(humidityIndex, 0.45);
        }

        double moisture = clamp(0.06 + 0.72 * (1.0 - Math.exp(-1.15 * humidityIndex)), 0.04, 0.96);
        if (isDesert) moisture = Math.min(moisture, 0.14);

        double depression = (1.0 - moisture) * (isDesert ? 22.0 : isSteppe ? 17.0 : 14.0) + 1.2;
        double dewpointC = Math.min(temperatureC - 0.5, temperatureC - depression);

        // Cloud cover: overcast in oceanic storm tracks, rainforests, and wet monsoons; clear in deserts.
        double marineCloudBoost = isOceanic ? 0.24 : (koppen == ClimateSource.AF ? 0.12 : 0.0);
        double aridCloudScale = isDesert ? 0.22 : (isSteppe ? 0.65 : 1.0);
        double cloudiness = clamp(
                (0.12 + 0.58 * clamp(humidityIndex / 1.4, 0.0, 1.0) + marineCloudBoost) * aridCloudScale,
                0.04,
                0.94
        );

        // Precipitation frequency and Clausius-Clapeyron rain intensity.
        double rainBase = 1.0 - Math.exp(-1.10 * humidityIndex);
        double rainChance = clamp(
                (rainBase + (isOceanic ? 0.14 : 0.0)) * (isDesert ? 0.12 : isSteppe ? 0.55 : 1.0),
                0.01,
                0.95
        );
        double warmMoistureCapacity = clamp(Math.exp(0.048 * (temperatureC - 12.0)), 0.35, 2.2);
        double rainIntensity = clamp(
                (0.18 + 0.62 * clamp(humidityIndex / 1.5, 0.0, 1.0)) * warmMoistureCapacity * (isDesert ? 0.20 : 1.0),
                0.03,
                1.0
        );

        // Mid-latitude jet stream vertical wind shear (peaks ~38 deg in spring/cool seasons).
        double jetBelt = Math.exp(-Math.pow((absLat - 38.0) / 14.0, 2.0));
        double shearSeason = 0.68 + 0.32 * clamp(0.55 * spring - 0.45 * summer, -1.0, 1.0);
        double windShear = clamp(0.08 + 0.86 * jetBelt * shearSeason, 0.05, 0.96);

        // Convective storm energy (CAPE proxy): requires warm air, humid boundary layer, and modest elevation.
        double warmFactor = clamp((temperatureC - 12.0) / 13.0, 0.0, 1.0);
        double dewFactor = clamp((dewpointC - 7.0) / 11.0, 0.0, 1.0);
        double elevFactor = clamp(1.0 - Math.max(0.0, elev - 1400.0) / 2200.0, 0.12, 1.0);
        double lapseBoost = 0.72 + 0.45 * continentality;
        // Dry boundary-layer air entrains into updrafts and suppresses convective initiation.
        double moistureEntrainment = smoothstep(0.10, 0.38, moisture);
        double stormEnergy = clamp(
                warmFactor * dewFactor * moistureEntrainment * elevFactor * lapseBoost * (isDesert ? 0.08 : 1.0),
                0.0,
                1.0
        );

        // Supercell tornado potential: CAPE + jet-stream wind shear + continental baroclinic contrast + plains terrain.
        double plainsTerrain = clamp(1.0 - Math.max(0.0, elev - 900.0) / 1400.0, 0.06, 1.0);
        double continentalDryline = 0.22 + 0.78 * continentality;
        double springBoost = 0.65 + 0.45 * Math.max(0.0, spring);
        double tornadoPotential = clamp(
                Math.pow(stormEnergy * windShear, 0.72) * continentalDryline * plainsTerrain * springBoost
                        * (isOceanic ? 0.12 : 1.0) * (isDesert ? 0.04 : 1.0),
                0.0,
                1.0
        );

        // Sea surface temperature estimate using latitude, ocean thermal lag, and regional warmth anomaly.
        double warmAnomaly = clamp(seaLevelMean - zonalSeaMean, -4.0, 4.0);
        double sstBase = 28.5 - 0.0068 * absLat * absLat + warmAnomaly * 0.75;
        double oceanAmp = clamp(0.8 + 0.18 * Math.min(absLat, 35.0), 0.8, 6.2);
        double seaSurfaceTempC = clamp(sstBase + oceanAmp * lateSummer, -1.8, 32.0);

        // Tropical cyclone potential: Coriolis band (~6..34 deg), warm SST (> 25.5 C), coastal/low-elevation,
        // humid air, and low vertical wind shear.
        double coriolis = smoothstep(4.5, 11.0, absLat);
        double tropicalBelt = coriolis * (1.0 - smoothstep(28.0, 39.0, absLat));
        double sstFactor = clamp((seaSurfaceTempC - 25.0) / 4.2, 0.0, 1.0);
        double coastalFactor = clamp(1.0 - Math.max(0.0, continentality - 0.35) * 1.6, 0.05, 1.0);
        double cycloneElevFactor = clamp(1.0 - elev / 550.0, 0.0, 1.0);
        double humidFactor = clamp(moisture / 0.42, 0.0, 1.0);
        double lowShearFactor = clamp(1.15 - windShear * 0.75, 0.25, 1.0);
        double hurricanePotential = clamp(
                tropicalBelt * sstFactor * coastalFactor * cycloneElevFactor * humidFactor * lowShearFactor
                        * (isDesert ? 0.10 : 1.0),
                0.0,
                1.0
        );

        return new EarthWeatherProfile(
                temperatureC,
                seaLevelMean,
                dewpointC,
                moisture,
                cloudiness,
                rainChance,
                rainIntensity,
                stormEnergy,
                windShear,
                tornadoPotential,
                hurricanePotential,
                seaSurfaceTempC
        );
    }

    private static boolean isDryWinterMonsoon(int koppen) {
        return switch (koppen) {
            case ClimateSource.AM, ClimateSource.AW,
                 ClimateSource.CWA, ClimateSource.CWB, ClimateSource.CWC,
                 ClimateSource.DWA, ClimateSource.DWB, ClimateSource.DWC, ClimateSource.DWD -> true;
            default -> false;
        };
    }

    private static boolean isDrySummer(int koppen) {
        return switch (koppen) {
            case ClimateSource.AS,
                 ClimateSource.CSA, ClimateSource.CSB, ClimateSource.CSC,
                 ClimateSource.DSA, ClimateSource.DSB, ClimateSource.DSC, ClimateSource.DSD -> true;
            default -> false;
        };
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
