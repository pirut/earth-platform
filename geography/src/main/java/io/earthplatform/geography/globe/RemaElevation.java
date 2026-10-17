package io.earthplatform.geography.globe;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Antarctica from {@link RemaSource}, laid over the Terrarium composite, dataset id {@value #DATASET_ID}.
 * The composite carries only coarse global models there, hundreds of metres to two kilometres low
 * in the mountains (Mount Vinson reads 2,970 m against 4,892 m); REMA is a 2 m surface model of the
 * whole continent.
 *
 * <p>REMA heights are above the WGS 84 ellipsoid; the EGM96 geoid, as the composite uses, is
 * subtracted (on the open ice sheet the two then agree to within a metre). Each tile pixel takes REMA
 * read at the overview nearest its own size: the 2 m mosaic and its overviews for pixels under 32 m, the 32 m
 * mosaic beyond. Where REMA has no data, over the sea and at the edges of its coverage, the tile
 * keeps its own values, blended over about 64 m. Open sea (exactly 0) is left as it is.
 */
public final class RemaElevation implements ElevationLayer, AutoCloseable {
    public static final String DATASET_ID = "rema-v2.0";
    public static final String ATTRIBUTION = "Antarctica: Reference Elevation Model of Antarctica (REMA) v2.0, Polar Geospatial Center, "
            + "University of Minnesota (CC BY 4.0), with the EGM96 geoid (NGA); DEMs provided by the Byrd Polar and Climate Research Center "
            + "and the Polar Geospatial Center under NSF-OPP awards 1043681, 1542736, 1543501, 1559691, 1810976, and 2129685";
    /** Coarser tiles cover so much ground that they are left to the composite. */
    public static final int MIN_ZOOM = 8;
    private static final double EQUATOR = 40_075_016.685578488;
    /** REMA ends north of here. */
    private static final double NORTH_LIMIT = -50;
    // EPSG:3031: WGS 84, standard parallel 71° S, central meridian 0.
    private static final double A = 6_378_137.0, E = Math.sqrt(0.00669437999014);
    private static final double SCALE;
    static {
        double phiC = Math.toRadians(71);
        double mC = Math.cos(phiC) / Math.sqrt(1 - E * E * Math.sin(phiC) * Math.sin(phiC));
        SCALE = A * mC / t(phiC);
    }
    /** EGM96 heights in decimetres, every half degree from 50° S (row 0) to 90° S, from 180° W. */
    private static final short[] GEOID = geoid();
    private static final int GEOID_ROWS = 81, GEOID_COLUMNS = 720;

    private final RemaSource rema;

    public RemaElevation(RemaSource rema) { this.rema = rema; }

    @Override public String id() { return DATASET_ID; }

    @Override public void refine(int zoom, int x, int y, float[] values) {
        if (zoom < MIN_ZOOM) return;
        int size = TerrariumTileSource.TILE_SIZE;
        double world = (double) size * (1L << zoom);
        if (Mercator.latitude((double) y * size, world) > NORTH_LIMIT) {
            // The tile's north edge is north of REMA; skip it unless its south edge reaches in.
            if (Mercator.latitude((double) (y + 1) * size, world) > NORTH_LIMIT) return;
        }
        // Read REMA at about the tile's pixel size, measured at its centre.
        double centre = Mercator.latitude((y + 0.5) * size, world);
        double pixel = EQUATOR * Math.cos(Math.toRadians(centre)) / world;
        // The nearest overview, not the next finer one: a finer one is up to four times the download for
        // detail the tile's pixels cannot show.
        int product = pixel < 32 ? RemaSource.FINE : RemaSource.COARSE, edge = product == RemaSource.FINE ? 5 : 1;
        int overview = Math.max(0, Math.min(RemaSource.MAX_OVERVIEW[product], (int) Math.round(Math.log(pixel / RemaSource.RESOLUTION[product]) / Math.log(2))));
        double[] sin = new double[size], cos = new double[size], geoidColumn = new double[size];
        for (int i = 0; i < size; i++) {
            double lambda = Math.toRadians(((double) x * size + i + 0.5) / world * 360 - 180);
            sin[i] = Math.sin(lambda);
            cos[i] = Math.cos(lambda);
            geoidColumn[i] = Math.toDegrees(lambda);
        }
        for (int j = 0; j < size; j++) {
            double lat = Mercator.latitude((double) y * size + j + 0.5, world);
            if (lat > NORTH_LIMIT) continue;
            double rho = SCALE * t(Math.toRadians(-lat));
            for (int i = 0; i < size; i++) {
                int at = j * size + i;
                float own = values[at];
                if (own == 0) continue;
                double px = rho * sin[i], py = rho * cos[i];
                double share = rema.coverage(product, edge, px, py);
                if (share <= 0.5) continue;
                double metres = rema.metres(product, overview, px, py);
                // A gap at the finer overview takes the coarser data around it.
                for (int coarser = overview + 1; Double.isNaN(metres) && coarser <= RemaSource.MAX_OVERVIEW[product]; coarser++)
                    metres = rema.metres(product, coarser, px, py);
                if (Double.isNaN(metres)) continue;
                double s = Math.min(1, (share - 0.5) * 2), weight = s * s * (3 - 2 * s);
                float out = (float) (own + weight * (metres - geoid(lat, geoidColumn[i]) - own));
                // Exactly 0 means open sea to the generator.
                values[at] = out == 0 ? Float.MIN_VALUE : out;
            }
        }
    }

    /** Snyder's t for the north-polar form, applied to the mirrored southern latitude. */
    private static double t(double phi) {
        double s = E * Math.sin(phi);
        return Math.tan(Math.PI / 4 - phi / 2) / Math.pow((1 - s) / (1 + s), E / 2);
    }

    /** Antarctic Polar Stereographic (EPSG:3031) metres of a latitude and longitude: {x, y}. */
    static double[] project(double latitude, double longitude) {
        double rho = SCALE * t(Math.toRadians(-latitude)), lambda = Math.toRadians(longitude);
        return new double[]{rho * Math.sin(lambda), rho * Math.cos(lambda)};
    }

    /** EGM96 geoid height in metres at a latitude south of 50° S, bilinear on the half-degree grid. */
    static double geoid(double latitude, double longitude) {
        double fr = Math.max(0, Math.min(GEOID_ROWS - 1, (NORTH_LIMIT - latitude) * 2));
        double fc = ((longitude + 180) * 2 % GEOID_COLUMNS + GEOID_COLUMNS) % GEOID_COLUMNS;
        int r = Math.min(GEOID_ROWS - 2, (int) fr), c = (int) fc;
        double tr = fr - r, tc = fc - c;
        int c1 = (c + 1) % GEOID_COLUMNS;
        double top = GEOID[r * GEOID_COLUMNS + c] * (1 - tc) + GEOID[r * GEOID_COLUMNS + c1] * tc;
        double bottom = GEOID[(r + 1) * GEOID_COLUMNS + c] * (1 - tc) + GEOID[(r + 1) * GEOID_COLUMNS + c1] * tc;
        return (top * (1 - tr) + bottom * tr) / 10;
    }

    private static short[] geoid() {
        // Sampled from NGA's EGM96 15' grid (public domain), as PROJ distributes it (us_nga_egm96_15.tif).
        try (InputStream raw = RemaElevation.class.getResourceAsStream("egm96-south.bin")) {
            if (raw == null) throw new IOException("Missing egm96-south.bin");
            DataInputStream in = new DataInputStream(raw);
            short[] grid = new short[81 * 720];
            for (int i = 0; i < grid.length; i++) grid[i] = in.readShort();
            return grid;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read the EGM96 geoid", failure);
        }
    }

    @Override public void close() { rema.close(); }
}
