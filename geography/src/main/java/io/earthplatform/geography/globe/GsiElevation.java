package io.earthplatform.geography.globe;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Japan from the Geospatial Information Authority of Japan's elevation tiles, laid over the
 * Terrarium composite, dataset id {@value #DATASET_ID}. The composite carries little better than
 * SRTM there; GSI's national model is 10 m everywhere and 5 m from aerial lidar over most of the
 * lowlands and many mountains, in heights above mean sea level (Tokyo Bay), the composite's datum.
 *
 * <p>GSI serves it as PNG tiles on the composite's own Web Mercator grid: the 10 m model
 * ({@code dem_png}) to zoom 14 and the 5 m lidar ({@code dem5a_png}) to zoom 15. Tiles of zoom 12 to
 * 14 read the 10 m model at their own zoom; zoom 15, the ground a player stands on, reads the lidar
 * and fills its gaps from the 10 m model. Coarser tiles keep the composite, so far terrain downloads
 * nothing more. Pixels with no data (the sea, past the coast) keep the composite.
 */
public final class GsiElevation extends MercatorTileLayer {
    public static final String DATASET_ID = "gsi-dem-v1";
    public static final String ATTRIBUTION = "Japan: source, Geospatial Information Authority of Japan (GSI) website, "
            + "elevation tiles (Public Data License 1.0), processed by Earth Platform";
    public static final URI GSI_TILES = URI.create("https://cyberjapandata.gsi.go.jp/xyz/");
    public static final int MIN_ZOOM = 12;
    /** The 10 m model's finest zoom; finer tiles read the 5 m lidar first. */
    static final int MODEL_ZOOM = 14;
    /** A pixel with no data. */
    private static final int NO_DATA = 1 << 23;
    private static final TileCoverage COVERAGE = TileCoverage.load("gsi-dem-v1.txt");

    private final URI base;

    /**
     * @param base             URI prefix the tile products are named under
     * @param cacheRoot        optional disk cache directory, or null for memory only
     * @param allowNetwork     false reads only the disk cache
     * @param maxTilesInMemory decoded source tiles kept (256 KiB each)
     */
    public GsiElevation(URI base, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        super(DATASET_ID, "GSI elevation", "earth-gsi-io", COVERAGE, MIN_ZOOM, cacheRoot, allowNetwork, maxTilesInMemory, maxConcurrentTransfers, timeout);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException("GSI base URI must end with /");
        this.base = base;
    }

    @Override List<Piece> pieces(int zoom, int x, int y) {
        if (zoom <= MODEL_ZOOM) return List.of(piece("dem_png", zoom, x, y));
        int shift = zoom - MODEL_ZOOM;
        return List.of(piece("dem5a_png", zoom, x, y), piece("dem_png", MODEL_ZOOM, x >> shift, y >> shift));
    }

    private Piece piece(String product, int zoom, int x, int y) {
        return new Piece(product, zoom, x, y, base.resolve(product + "/" + zoom + "/" + x + "/" + y + ".png"));
    }

    @Override float[] decode(Piece piece, byte[] bytes) throws IOException { return decode(bytes); }

    /** GSI's PNG elevation: 24 bits of red, green and blue in hundredths of a metre, two's complement; 2^23 is no data. */
    static float[] decode(byte[] png) throws IOException {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed GSI tile", malformed);
        }
        if (image == null) throw new IOException("Not a readable GSI tile");
        if (image.getWidth() != SIZE || image.getHeight() != SIZE) throw new IOException("Unexpected GSI tile size " + image.getWidth() + "x" + image.getHeight());
        int[] rgb = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        float[] metres = new float[SIZE * SIZE];
        for (int i = 0; i < rgb.length; i++) metres[i] = metres(rgb[i] & 0xFFFFFF);
        return metres;
    }

    static float metres(int code) {
        if (code == NO_DATA) return Float.NaN;
        return (float) ((code < NO_DATA ? code : code - (1 << 24)) * 0.01);
    }
}
