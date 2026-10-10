package io.earthplatform.geography.composition;

/** Explicit orthometric metre references. No aliases for ambiguous "sea level" and no implicit transformation. */
public enum VerticalDatum {
    EGM2008_METRES("EPSG:3855"),
    EGM96_METRES("EPSG:5773");
    private final String authorityCode;
    VerticalDatum(String authorityCode) { this.authorityCode = authorityCode; }
    public String authorityCode() { return authorityCode; }
    public static VerticalDatum fromAuthorityCode(String code) {
        for (VerticalDatum value : values()) if (value.authorityCode.equals(code)) return value;
        throw new IllegalArgumentException("Unknown or unsupported vertical reference: " + code);
    }
}
