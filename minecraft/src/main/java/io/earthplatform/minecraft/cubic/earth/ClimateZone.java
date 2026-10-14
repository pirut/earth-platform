package io.earthplatform.minecraft.cubic.earth;

import static io.earthplatform.geography.globe.ClimateSource.*;

/**
 * Broad climate zones that the Earth's biomes are chosen by: the Köppen-Geiger class of a place,
 * moved to the zone its own height gives where that differs from its 1 km pixel's. A peak in a
 * temperate pixel is tundra, a valley in a tundra pixel is subarctic, a tropical highland is
 * temperate and a high hot desert is a cold one.
 */
enum ClimateZone {
    RAINFOREST, TROPICAL_DRY, HOT_DESERT, COLD_DESERT, HOT_STEPPE, COLD_STEPPE, MEDITERRANEAN, SUBTROPICAL,
    OCEANIC, SUBPOLAR_OCEANIC, CONTINENTAL, SUBARCTIC, TUNDRA, ICE;

    /** Warmest-quarter temperatures under which ground is tundra (a warmest month under about 10 °C) or ice. */
    static final double TUNDRA_WARMEST = 9, ICE_WARMEST = 0;

    static ClimateZone of(int koppen, double mean, double warmest) {
        if (warmest < ICE_WARMEST) return ICE;
        if (warmest < TUNDRA_WARMEST) return TUNDRA;
        return switch (koppen) {
            case AF, AM -> mean < 18 ? OCEANIC : RAINFOREST;
            case AS, AW -> mean < 18 ? OCEANIC : TROPICAL_DRY;
            case BWH, BWK -> mean >= 18 ? HOT_DESERT : COLD_DESERT;
            case BSH, BSK -> mean >= 18 ? HOT_STEPPE : COLD_STEPPE;
            // Dry-summer continental climates are Mediterranean mountains (the Sierra Nevada, the Zagros),
            // but where winters are long and hard, as in the Yukon's dry valleys, they are boreal.
            case CSA, CSB, CSC -> MEDITERRANEAN;
            case DSA, DSB -> mean < 3 ? SUBARCTIC : MEDITERRANEAN;
            case CFA, CWA -> SUBTROPICAL;
            case CFB, CWB -> OCEANIC;
            case CFC, CWC -> SUBPOLAR_OCEANIC;
            case DFA, DFB, DWA, DWB -> CONTINENTAL;
            case DFC, DFD, DSC, DSD, DWC, DWD, ET, EF -> SUBARCTIC;
            default -> mean >= 18 ? TROPICAL_DRY : mean >= 10 ? SUBTROPICAL : mean >= 0 ? CONTINENTAL : SUBARCTIC;
        };
    }
}
