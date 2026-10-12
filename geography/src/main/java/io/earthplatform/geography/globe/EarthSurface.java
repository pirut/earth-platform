package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;

/**
 * Engine-agnostic surface model for a whole-planet world: where each block column is on Earth, how high the real
 * terrain is there, and which block Y that becomes. Minecraft hosts (vanilla noise generation or the cubic engine)
 * consume this rather than raw tiles, so both place terrain identically for the same identity.
 */
public final class EarthSurface {
    private final GlobalElevation elevation;
    private final VerticalScale vertical;

    public EarthSurface(GlobalElevation elevation, VerticalScale vertical) {
        this.elevation = elevation;
        this.vertical = vertical;
    }

    public MercatorGrid grid() { return elevation.grid(); }
    public VerticalScale vertical() { return vertical; }
    public GlobalElevation elevation() { return elevation; }

    /**
     * Continuous surface height in block Y at a continuous block-space position. Elevation is divided by the grid's
     * metres per block first, so a coarser world (for example 4 m per block) keeps mountains in proportion.
     */
    public double surfaceY(double x, double z) {
        return vertical.surfaceY(elevation.metres(x, z) / grid().metresPerBlock(), grid().scaleFactor(z));
    }

    /** Surface height at the centre of an integer block column. */
    public double surfaceY(long x, long z) { return surfaceY(x + 0.5, z + 0.5); }

    public double elevationMetres(long x, long z) { return elevation.metres(x, z); }

    public GeoPoint geographic(long x, long z) { return grid().blockCentre(x, z); }

    /** Block column for a geographic point, rounded down. */
    public long[] block(GeoPoint point) {
        return new long[]{(long) Math.floor(grid().blockX(point)), (long) Math.floor(grid().blockZ(point))};
    }

    /**
     * Identity string that a world must pin; changing any part changes where terrain lands, so existing saves must not
     * silently pick up a different value.
     */
    public String identity() {
        return MercatorGrid.PROJECTION_ID + ";mpb=" + grid().metresPerBlock() + ";" + TerrariumTileSource.DATASET_ID + ";z=" + elevation.zoom()
            + ";" + VerticalScale.VERSION + ";" + vertical;
    }
}
