package io.earthplatform.minecraft.cubic.earth;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The world's ecoregions (RESOLVE Ecoregions 2017), baked to a 0.05 degree grid of ecoregion numbers that ships
 * with the mod, with each ecoregion's biome. Rows are held as runs, so the whole map takes a few megabytes.
 */
public final class Ecoregions {
    public static final String ATTRIBUTION = "Ecoregions: RESOLVE Ecoregions 2017, Dinerstein et al. 2017, BioScience 67:534-545 (CC BY 4.0)";
    /** RESOLVE biome numbers. */
    public static final int NONE = 0, TROPICAL_MOIST = 1, TROPICAL_DRY = 2, TROPICAL_CONIFER = 3, TEMPERATE_BROADLEAF = 4,
            TEMPERATE_CONIFER = 5, BOREAL = 6, TROPICAL_GRASSLAND = 7, TEMPERATE_GRASSLAND = 8, FLOODED_GRASSLAND = 9,
            MONTANE_GRASSLAND = 10, TUNDRA = 11, MEDITERRANEAN = 12, DESERT = 13, MANGROVE = 14;

    private static final Logger LOG = LoggerFactory.getLogger(Ecoregions.class);
    private static final int WIDTH = 7200, HEIGHT = 3600, PER_DEGREE = 20;
    private static volatile Ecoregions instance;
    private static volatile boolean failed;

    private final short[][] starts, values;
    private final byte[] biomes;

    private Ecoregions(short[][] starts, short[][] values, byte[] biomes) { this.starts = starts; this.values = values; this.biomes = biomes; }

    /** The map, read once from the mod's resources; null if it could not be read. */
    public static Ecoregions get() {
        Ecoregions map = instance;
        if (map != null || failed) return map;
        synchronized (Ecoregions.class) {
            if (instance == null && !failed) {
                try {
                    instance = load();
                } catch (IOException | RuntimeException e) {
                    failed = true;
                    LOG.warn("Ecoregion map unavailable; biomes follow climate and land cover alone", e);
                }
            }
            return instance;
        }
    }

    private static Ecoregions load() throws IOException {
        BufferedImage image;
        try (InputStream in = Ecoregions.class.getResourceAsStream("/earthplatform/ecoregions.png")) {
            if (in == null) throw new IOException("missing /earthplatform/ecoregions.png");
            image = ImageIO.read(in);
        }
        if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) throw new IOException("unexpected ecoregion map size");
        Raster raster = image.getRaster();
        short[][] starts = new short[HEIGHT][], values = new short[HEIGHT][];
        int[] row = new int[WIDTH];
        short[] s = new short[WIDTH], v = new short[WIDTH];
        for (int y = 0; y < HEIGHT; y++) {
            raster.getSamples(0, y, WIDTH, 1, 0, row);
            int runs = 0;
            for (int x = 0; x < WIDTH; x++) {
                if (x == 0 || row[x] != row[x - 1]) { s[runs] = (short) x; v[runs] = (short) row[x]; runs++; }
            }
            starts[y] = Arrays.copyOf(s, runs);
            values[y] = Arrays.copyOf(v, runs);
        }
        byte[] biomes = new byte[1 << 12];
        try (InputStream in = Ecoregions.class.getResourceAsStream("/earthplatform/ecoregions.tsv")) {
            if (in == null) throw new IOException("missing /earthplatform/ecoregions.tsv");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                String[] cells = line.split("\t");
                int id = Integer.parseInt(cells[0]);
                if (id > 0 && id < biomes.length) biomes[id] = (byte) Integer.parseInt(cells[1]);
            }
        }
        return new Ecoregions(starts, values, biomes);
    }

    /** The ecoregion at a place, or 0 where there is none (sea, rock and ice). */
    public int ecoregion(double latitude, double longitude) {
        int y = Math.min(HEIGHT - 1, Math.max(0, (int) Math.floor((90 - latitude) * PER_DEGREE)));
        int x = Math.floorMod((int) Math.floor((longitude + 180) * PER_DEGREE), WIDTH);
        short[] s = starts[y];
        int i = Arrays.binarySearch(s, (short) x);
        if (i < 0) i = -i - 2;
        return values[y][i] & 0xFFFF;
    }

    /** The RESOLVE biome of an ecoregion. */
    public int biome(int ecoregion) { return ecoregion > 0 && ecoregion < biomes.length ? biomes[ecoregion] : NONE; }
}
