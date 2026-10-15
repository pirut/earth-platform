package io.earthplatform.geography.globe;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * France from IGN's RGE ALTI, laid over the Terrarium composite, dataset id {@value #DATASET_ID}.
 * The composite carries the 25 m EU-DEM there; RGE ALTI is a 1 to 5 m bare-earth model of
 * metropolitan France, Corsica and the overseas departments, from lidar in the mountains, the
 * forests and along the coasts and rivers, in heights above mean sea level (NGF-IGN69 and the
 * overseas datums).
 *
 * <p>Read from IGN's Geoplateforme raster map service, which resamples it to any grid on request:
 * each tile asks for its own 256 pixels on the composite's Web Mercator grid as 32-bit floats. Only
 * tiles of zoom 12 and finer read it; far terrain keeps the composite. Pixels with no data (the sea,
 * past the border) keep the composite.
 */
public final class IgnElevation extends MercatorTileLayer {
    public static final String DATASET_ID = "ign-rgealti-v1";
    public static final String ATTRIBUTION = "France: IGN RGE ALTI (Licence Ouverte / Etalab Open Licence 2.0)";
    public static final URI IGN_WMS = URI.create("https://data.geopf.fr/wms-r/wms");
    static final String LAYER = "ELEVATION.ELEVATIONGRIDCOVERAGE.HIGHRES";
    public static final int MIN_ZOOM = 12;
    /** IGN's no-data value; anything this low is no data. */
    private static final float NO_DATA_BELOW = -1000;
    private static final double HALF_WORLD = 20_037_508.342789244;
    private static final TileCoverage COVERAGE = TileCoverage.load("ign-rgealti-v1.txt");

    private final URI service;

    /**
     * @param service          the map service's URI, without a query
     * @param cacheRoot        optional disk cache directory, or null for memory only
     * @param allowNetwork     false reads only the disk cache
     * @param maxTilesInMemory decoded source tiles kept (256 KiB each)
     */
    public IgnElevation(URI service, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        super(DATASET_ID, "IGN elevation", "earth-ign-io", COVERAGE, MIN_ZOOM, cacheRoot, allowNetwork, maxTilesInMemory, maxConcurrentTransfers, timeout);
        this.service = service;
    }

    @Override List<Piece> pieces(int zoom, int x, int y) {
        double span = 2 * HALF_WORLD / (1L << zoom);
        String box = String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f",
                -HALF_WORLD + x * span, HALF_WORLD - (y + 1) * span, -HALF_WORLD + (x + 1) * span, HALF_WORLD - y * span);
        URI uri = URI.create(service + "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=" + LAYER + "&STYLES=&CRS=EPSG:3857&BBOX=" + box
                + "&WIDTH=" + SIZE + "&HEIGHT=" + SIZE + "&FORMAT=image/x-bil;bits=32");
        return List.of(new Piece("rgealti", zoom, x, y, uri));
    }

    /** Raw floats are large and compress well; they are kept on disk gzipped. */
    @Override boolean gzip() { return true; }

    @Override float[] decode(Piece piece, byte[] bytes) throws IOException { return decode(bytes); }

    /** The service's BIL: 256 by 256 little-endian floats, row by row from the north. */
    static float[] decode(byte[] bil) throws IOException {
        if (bil.length != SIZE * SIZE * 4) throw new IOException("IGN elevation answered " + bil.length + " bytes, not a tile: "
                + new String(bil, 0, Math.min(200, bil.length), java.nio.charset.StandardCharsets.UTF_8));
        float[] metres = new float[SIZE * SIZE];
        ByteBuffer.wrap(bil).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(metres);
        for (int i = 0; i < metres.length; i++) if (!(metres[i] > NO_DATA_BELOW)) metres[i] = Float.NaN;
        return metres;
    }
}
