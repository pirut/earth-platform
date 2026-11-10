package io.earthplatform.minecraft.cubic.earth;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small maps of the whole world that ship with the mod and say what the ground is made of where
 * climate and land cover cannot: the rock under it, how red its bare soil is and how much peat and
 * humus lies in it. Each is baked from an open dataset to a grid of a twentieth or a tenth of a
 * degree by {@code minecraft/scripts/earthmaps.py}; rows are held as runs, so all three take a
 * megabyte or two of memory.
 *
 * <ul>
 *   <li>Rock: the lithological class that covers most of each river sub-basin, from the Global
 *       Lithological Map (GLiM) as HydroATLAS carries it.</li>
 *   <li>Red ground: how far the land's colour from orbit leans from pale sand to red, from NASA's
 *       Blue Marble: Next Generation.</li>
 *   <li>Soil carbon: organic carbon in the top 5 cm of soil, from ISRIC SoilGrids 2.0.</li>
 * </ul>
 */
public final class EarthMaps {
    /** The dataset pin of worlds whose rock, lava, red ground and soil follow these maps. */
    public static final String ID = "earth-maps-v1";
    public static final String ATTRIBUTION = "Rock: Global Lithological Map (Hartmann & Moosdorf 2012) via HydroATLAS v1.0, Linke et al. 2019 (CC BY 4.0). "
            + "Ground colour: NASA Blue Marble: Next Generation, NASA Earth Observatory, served by NASA GIBS. "
            + "Soil: ISRIC SoilGrids 2.0, Poggio et al. 2021 (CC BY 4.0)";
    /** GLiM's rock classes, by the numbers HydroATLAS gives them. */
    public static final int NO_ROCK = 0, SEDIMENT = 1, BASIC_VOLCANIC = 2, SILICICLASTIC = 3, BASIC_PLUTONIC = 4, MIXED_SEDIMENTARY = 5,
            CARBONATE = 6, ACID_VOLCANIC = 7, METAMORPHIC = 8, ACID_PLUTONIC = 9, INTERMEDIATE_VOLCANIC = 10, WATER = 11, PYROCLASTIC = 12,
            INTERMEDIATE_PLUTONIC = 13, EVAPORITE = 14, NO_DATA = 15, ICE = 16;

    private static final Logger LOG = LoggerFactory.getLogger(EarthMaps.class);
    private static volatile EarthMaps instance;
    private static volatile boolean failed;

    private final Grid rock, red, carbon;

    private EarthMaps(Grid rock, Grid red, Grid carbon) { this.rock = rock; this.red = red; this.carbon = carbon; }

    /** The maps, read once from the mod's resources; null if they could not be read. */
    public static EarthMaps get() {
        EarthMaps maps = instance;
        if (maps != null || failed) return maps;
        synchronized (EarthMaps.class) {
            if (instance == null && !failed) {
                try {
                    instance = new EarthMaps(Grid.load("/earthplatform/rock.png"), Grid.load("/earthplatform/redground.png"), Grid.load("/earthplatform/soilcarbon.png"));
                } catch (IOException | RuntimeException e) {
                    failed = true;
                    LOG.warn("Rock and soil maps unavailable; the ground follows climate and land cover alone", e);
                }
            }
            return instance;
        }
    }

    /** The rock class at a place, or {@link #NO_ROCK} at sea and where the map has none. */
    public int rock(double latitude, double longitude) { return rock.nearest(latitude, longitude); }

    /** How red the bare ground is, 0 for pale sand and grey rock to 1 for the red of Australia's centre. */
    public double red(double latitude, double longitude) { return red.smooth(latitude, longitude) / 255.0; }

    /** Organic carbon in the topsoil, 0 for none to 1 for peat, about a sixth in an ordinary forest soil. */
    public double carbon(double latitude, double longitude) { return carbon.smooth(latitude, longitude) / 255.0; }

    /** Whether a rock class is lava or ash: basalt, andesite and their pyroclastics. */
    public static boolean volcanic(int rock) { return rock == BASIC_VOLCANIC || rock == INTERMEDIATE_VOLCANIC || rock == PYROCLASTIC; }

    /** Whether a rock class is granite or its kin: pale, coarse rock that cooled at depth. */
    public static boolean granitic(int rock) { return rock == ACID_PLUTONIC || rock == INTERMEDIATE_PLUTONIC; }

    /** One 8-bit map of the world, north up from 90 degrees and east from -180, its rows held as runs. */
    static final class Grid {
        private final int width, height;
        private final short[][] starts;
        private final byte[][] values;

        Grid(int width, int height, short[][] starts, byte[][] values) { this.width = width; this.height = height; this.starts = starts; this.values = values; }

        static Grid load(String resource) throws IOException {
            BufferedImage image;
            try (InputStream in = EarthMaps.class.getResourceAsStream(resource)) {
                if (in == null) throw new IOException("missing " + resource);
                image = ImageIO.read(in);
            }
            int width = image.getWidth(), height = image.getHeight();
            if (width != 2 * height || width > Short.MAX_VALUE) throw new IOException("unexpected size of " + resource);
            Raster raster = image.getRaster();
            if (raster.getNumBands() != 1) throw new IOException("unexpected bands in " + resource);
            short[][] starts = new short[height][];
            byte[][] values = new byte[height][];
            int[] row = new int[width];
            short[] s = new short[width];
            byte[] v = new byte[width];
            for (int y = 0; y < height; y++) {
                raster.getSamples(0, y, width, 1, 0, row);
                int runs = 0;
                for (int x = 0; x < width; x++) {
                    if (x == 0 || row[x] != row[x - 1]) { s[runs] = (short) x; v[runs] = (byte) row[x]; runs++; }
                }
                starts[y] = Arrays.copyOf(s, runs);
                values[y] = Arrays.copyOf(v, runs);
            }
            return new Grid(width, height, starts, values);
        }

        private int cell(int x, int y) {
            short[] s = starts[y];
            int i = Arrays.binarySearch(s, (short) x);
            if (i < 0) i = -i - 2;
            return values[y][i] & 0xFF;
        }

        /** The value of the cell a place lies in. */
        int nearest(double latitude, double longitude) {
            double perDegree = width / 360.0;
            int y = Math.min(height - 1, Math.max(0, (int) Math.floor((90 - latitude) * perDegree)));
            return cell(Math.floorMod((int) Math.floor((longitude + 180) * perDegree), width), y);
        }

        /** The value at a place, blended between the four cell centres round it. */
        double smooth(double latitude, double longitude) {
            double perDegree = width / 360.0, fx = (longitude + 180) * perDegree - 0.5, fy = (90 - latitude) * perDegree - 0.5;
            int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
            double tx = fx - x0, ty = fy - y0;
            int ya = Math.min(height - 1, Math.max(0, y0)), yb = Math.min(height - 1, Math.max(0, y0 + 1));
            int xa = Math.floorMod(x0, width), xb = Math.floorMod(x0 + 1, width);
            double north = cell(xa, ya) * (1 - tx) + cell(xb, ya) * tx, south = cell(xa, yb) * (1 - tx) + cell(xb, yb) * tx;
            return north * (1 - ty) + south * ty;
        }
    }
}
