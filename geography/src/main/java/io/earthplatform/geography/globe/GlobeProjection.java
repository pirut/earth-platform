package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.MissingTerrainException;

/**
 * Maps between geographic coordinates (latitude, longitude in degrees) and continuous Minecraft
 * block-space coordinates {@code (x, z)}.
 *
 * <p>Implementations include {@link MercatorGrid} ({@code web-mercator-v1}) and
 * {@link BteDymaxionProjection} ({@code bte-dymaxion-v1}). Callers handling arbitrary block
 * coordinates should prefer {@link #tryGeographic(double, double)}, which returns {@code null}
 * outside a projection's coverage (such as outside the unfolded Dymaxion icosahedron net) instead
 * of throwing {@link MissingTerrainException}.
 */
public interface GlobeProjection {
    /** Stable projection identifier written to {@code generator.properties} and network payloads. */
    String id();

    /** Nominal metres per block (equator scale for Web Mercator, ~1.0 for standard BTE Dymaxion). */
    double metresPerBlock();

    /**
     * Geographic coordinate of a continuous block-space position, or {@code null} when
     * {@code (x, z)} lies outside the projection's coverage.
     */
    GeoPoint tryGeographic(double x, double z);

    /**
     * Geographic coordinate of a continuous block-space position; throws
     * {@link MissingTerrainException} outside coverage.
     */
    default GeoPoint geographic(double x, double z) {
        GeoPoint point = tryGeographic(x, z);
        if (point == null) throw new MissingTerrainException("Block position outside " + id() + " coverage: " + x + ", " + z);
        return point;
    }

    /** Centre of the integer block column {@code (x, z)}, throwing outside coverage. */
    default GeoPoint blockCentre(long x, long z) { return geographic(x + 0.5, z + 0.5); }

    /** Centre of the integer block column {@code (x, z)}, or {@code null} outside coverage. */
    default GeoPoint tryBlockCentre(long x, long z) { return tryGeographic(x + 0.5, z + 0.5); }

    /** Forward projection from {@code (latitude, longitude)} in degrees to continuous block {@code {x, z}}. */
    double[] toBlock(double latitude, double longitude);

    /** Forward projection from a {@link GeoPoint} to continuous block {@code {x, z}}. */
    default double[] toBlock(GeoPoint point) { return toBlock(point.latitude(), point.longitude()); }

    /** Local ground metres covered by one block at {@code (x, z)}. */
    double groundMetresPerBlock(double x, double z);

    /** Whether block X wraps continuously east-west around the globe. */
    boolean wrapsEastWest();

    /** Nominal circumference of the Earth in blocks at {@link #metresPerBlock()}. */
    double worldWidthBlocks();

    /**
     * Unit vector {@code {ex, ez}} pointing local geographic East in block {@code (x, z)} space.
     * For Web Mercator this is always {@code {1, 0}}; on rotated Dymaxion faces it tracks the
     * face's orientation so compass bearings and ocean currents align with true East.
     */
    double[] eastUnit(double x, double z);

    /** Fast check for Web Mercator so hot paths can stay linear and trig-free. */
    default boolean isWebMercator() { return this instanceof MercatorGrid; }

    /** Whether {@code id} names a supported projection. */
    static boolean supported(String id) {
        return MercatorGrid.PROJECTION_ID.equals(id) || BteDymaxionProjection.PROJECTION_ID.equals(id);
    }

    /** Constructs the projection for a saved {@code id} and {@code metresPerBlock}. */
    static GlobeProjection of(String id, double metresPerBlock) {
        if (id == null) throw new IllegalArgumentException("Missing projection id");
        return switch (id) {
            case MercatorGrid.PROJECTION_ID -> new MercatorGrid(metresPerBlock);
            case BteDymaxionProjection.PROJECTION_ID -> new BteDymaxionProjection(metresPerBlock);
            default -> throw new IllegalArgumentException("Unsupported Earth projection: " + id);
        };
    }
}
