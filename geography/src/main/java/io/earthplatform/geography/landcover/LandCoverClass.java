package io.earthplatform.geography.landcover;

/** ESA WorldCover 2021 v200 Map codes; UNKNOWN is the source no-data code, not grass. */
public enum LandCoverClass {
    UNKNOWN(0), TREE_COVER(10), SHRUBLAND(20), GRASSLAND(30), CROPLAND(40),
    BUILT_UP(50), BARE_SPARSE_VEGETATION(60), SNOW_AND_ICE(70),
    PERMANENT_WATER(80), HERBACEOUS_WETLAND(90), MANGROVES(95), MOSS_AND_LICHEN(100);

    private final int code;
    LandCoverClass(int code) { this.code = code; }
    public int code() { return code; }
    public static LandCoverClass fromCode(int code) {
        return switch (code) {
            case 0 -> UNKNOWN;
            case 10 -> TREE_COVER;
            case 20 -> SHRUBLAND;
            case 30 -> GRASSLAND;
            case 40 -> CROPLAND;
            case 50 -> BUILT_UP;
            case 60 -> BARE_SPARSE_VEGETATION;
            case 70 -> SNOW_AND_ICE;
            case 80 -> PERMANENT_WATER;
            case 90 -> HERBACEOUS_WETLAND;
            case 95 -> MANGROVES;
            case 100 -> MOSS_AND_LICHEN;
            default -> throw new IllegalArgumentException("Unsupported WorldCover class code: " + code);
        };
    }
}
