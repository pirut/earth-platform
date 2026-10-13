package io.earthplatform.minecraft.cubic.gen;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Rock strata of layered sedimentary country, such as the Colorado Plateau's. Each region has its
 * real sequence of formations, top down, with their thickness, colours and how they weather: cliff
 * makers, ledgy beds and soft slopes. The layers lie level under a datum that follows the region's
 * highest plateau surfaces, falling at most {@link #DIP} metres per metre away from them, so a
 * canyon's two rims carry their own tops while its floor sits deep in the sequence. Columns show
 * the layers in their cliff faces, near and far, and their ground steps into ledges and benches
 * where the rock does.
 */
public final class Strata {
    /** What the strata need from the world: a coarse elevation model and the map projection. */
    public interface Globe {
        /** Ground height in metres above the sea at a block position, from a coarse model. */
        double metres(double x, double z);
        GeoPoint geographic(double x, double z);
        double metresPerBlock(double z);
    }

    /** How a formation weathers. */
    enum Weathering { SLOPE, LEDGES, CLIFF }

    /** One formation: thickness in metres, beds of about {@code bed} metres each, coloured from {@code palette}. */
    record Formation(String name, int thickness, Weathering weathering, int bed, Block... palette) {}

    /**
     * A region of layered rock between latitudes and longitudes, its formations top down, the
     * rock under them, and the loose ground that covers its flats in place of plain sand, if any.
     */
    record Region(String name, double south, double west, double north, double east, @Nullable Block ground,
                  Block basement, List<Formation> formations) {
        boolean contains(double lat, double lon) { return lat >= south && lat <= north && lon >= west && lon <= east; }
    }

    /**
     * Ground tops that stay as the land cover put them on gentle slopes down in the rock; anything
     * else turns to the rock beneath. Grass is not among them: the benches of a dry canyon are
     * bare rock and scrub, so grass gives way to the region's loose ground, or to the rock.
     */
    private static final List<Block> SOIL = List.of(Blocks.SAND, Blocks.RED_SAND, Blocks.SNOW_BLOCK,
            Blocks.POWDER_SNOW, Blocks.PODZOL, Blocks.MUD, Blocks.MOSS_BLOCK);

    private static final Block WT = Blocks.WHITE_TERRACOTTA, LGT = Blocks.LIGHT_GRAY_TERRACOTTA, T = Blocks.TERRACOTTA,
            RT = Blocks.RED_TERRACOTTA, OT = Blocks.ORANGE_TERRACOTTA, BT = Blocks.BROWN_TERRACOTTA, YT = Blocks.YELLOW_TERRACOTTA,
            GT = Blocks.GREEN_TERRACOTTA, CT = Blocks.CYAN_TERRACOTTA, PT = Blocks.PINK_TERRACOTTA, PU = Blocks.PURPLE_TERRACOTTA,
            SS = Blocks.SANDSTONE, RSS = Blocks.RED_SANDSTONE;

    private static Formation f(String name, int thickness, Weathering weathering, int bed, Block... palette) {
        return new Formation(name, thickness, weathering, bed, palette);
    }

    /**
     * The regions, with their sequences from published stratigraphic sections, rounded. Palettes
     * repeat a block to weight it.
     */
    static final List<Region> REGIONS = List.of(
            // The Grand Canyon, Marble Canyon to the Grand Wash Cliffs.
            new Region("Grand Canyon", 35.65, -114.1, 36.75, -111.55, null, Blocks.DEEPSLATE, List.of(
                    f("Kaibab Limestone", 90, Weathering.LEDGES, 5, WT, WT, WT, LGT),
                    f("Toroweap Formation", 70, Weathering.SLOPE, 4, LGT, WT, WT, YT),
                    f("Coconino Sandstone", 100, Weathering.CLIFF, 6, SS, SS, WT),
                    f("Hermit Formation", 100, Weathering.SLOPE, 4, RT, RT, T),
                    f("Supai Group", 300, Weathering.LEDGES, 6, T, T, RT, RT, OT, BT),
                    f("Redwall Limestone", 150, Weathering.CLIFF, 8, T, T, T, LGT),
                    f("Muav Limestone", 120, Weathering.LEDGES, 4, LGT, LGT, WT, BT),
                    f("Bright Angel Shale", 100, Weathering.SLOPE, 3, LGT, CT, LGT, GT),
                    f("Tapeats Sandstone", 70, Weathering.LEDGES, 3, BT, BT, T),
                    // Vishnu Schist with streaks of Zoroaster Granite, above the basement's plain deepslate.
                    f("Vishnu Schist", 600, Weathering.CLIFF, 4, Blocks.DEEPSLATE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.GRANITE, Blocks.DEEPSLATE, Blocks.GRANITE))),
            // Zion Canyon and the Kolob terraces.
            new Region("Zion", 37.05, -113.35, 37.6, -112.75, null, Blocks.STONE, List.of(
                    f("Carmel Formation", 40, Weathering.SLOPE, 3, LGT, WT),
                    f("Navajo Sandstone, upper", 250, Weathering.CLIFF, 7, WT, WT, SS),
                    f("Navajo Sandstone, lower", 400, Weathering.CLIFF, 7, OT, OT, T, RSS),
                    f("Kayenta Formation", 180, Weathering.LEDGES, 4, RT, BT, T),
                    f("Moenave Formation", 110, Weathering.LEDGES, 4, OT, RT, WT),
                    f("Chinle Formation", 120, Weathering.SLOPE, 3, LGT, PU, LGT, RT),
                    f("Moenkopi Formation", 300, Weathering.LEDGES, 3, RT, RT, WT, BT),
                    f("Kaibab Limestone", 90, Weathering.LEDGES, 5, WT, LGT))),
            // Bryce Canyon's Pink Cliffs and the Paunsaugunt Plateau.
            new Region("Bryce Canyon", 37.35, -112.4, 37.8, -111.95, null, Blocks.STONE, List.of(
                    f("Claron Formation, white", 60, Weathering.LEDGES, 3, WT, WT, PT),
                    f("Claron Formation, pink", 200, Weathering.LEDGES, 3, OT, PT, OT, WT, T),
                    f("Kaiparowits Formation", 250, Weathering.SLOPE, 4, LGT, WT, YT),
                    f("Straight Cliffs Formation", 300, Weathering.CLIFF, 5, YT, WT, LGT))),
            // Monument Valley's buttes on their red sand.
            new Region("Monument Valley", 36.75, -110.45, 37.25, -109.75, Blocks.RED_SAND, Blocks.STONE, List.of(
                    f("Shinarump and Moenkopi", 50, Weathering.LEDGES, 4, BT, LGT, RT),
                    f("De Chelly Sandstone", 250, Weathering.CLIFF, 8, OT, OT, T, RSS),
                    f("Organ Rock Formation", 200, Weathering.LEDGES, 4, RT, RT, T),
                    f("Cedar Mesa Sandstone", 200, Weathering.CLIFF, 6, WT, SS, OT))),
            // Canyonlands and Arches, around Moab.
            new Region("Canyonlands", 37.9, -110.3, 38.85, -109.3, null, Blocks.STONE, List.of(
                    f("Entrada Sandstone", 100, Weathering.CLIFF, 6, OT, OT, RSS, T),
                    f("Navajo Sandstone", 120, Weathering.CLIFF, 7, WT, SS, WT),
                    f("Kayenta Formation", 70, Weathering.LEDGES, 4, RT, BT, T),
                    f("Wingate Sandstone", 110, Weathering.CLIFF, 8, OT, T, OT),
                    f("Chinle Formation", 100, Weathering.SLOPE, 3, LGT, PU, RT),
                    f("Moenkopi Formation", 120, Weathering.LEDGES, 3, RT, BT, RT),
                    f("White Rim Sandstone", 60, Weathering.CLIFF, 5, WT, SS),
                    f("Organ Rock Formation", 100, Weathering.SLOPE, 4, RT, T),
                    f("Cedar Mesa Sandstone", 200, Weathering.CLIFF, 6, WT, SS, OT))));

    /** How fast the datum falls away from a plateau surface, metres per metre: about the dip of the beds. */
    static final double DIP = 0.02;
    /** How far a plateau surface reaches when setting the datum, in metres. */
    static final double REACH = 10_000;
    /** Datum cells, in blocks. */
    private static final int CELL = 512;
    /** Metres under the datum before the ground's own cover gives way to rock and ledges; plateau tops keep theirs. */
    static final double RIM = 12;

    private final Globe globe;
    private final Holder<Biome> badlands;
    private final SimplexNoise wiggle;
    private final Built[] built;
    private final ConcurrentHashMap<Long, Float> highest = new ConcurrentHashMap<>(), datum = new ConcurrentHashMap<>();

    public Strata(long seed, Globe globe, Holder<Biome> badlands) {
        this.globe = globe;
        this.badlands = badlands;
        wiggle = new SimplexNoise(new LegacyRandomSource(seed ^ 0x5354524154414CL));
        built = new Built[REGIONS.size()];
        for (int i = 0; i < built.length; i++) built[i] = new Built(REGIONS.get(i), i);
    }

    /** A region's rock by whole metres under its datum, and where each formation starts. */
    private static final class Built {
        final Region region;
        final BlockState[] rock;
        final int[] start, formation;
        final BlockState basement;

        Built(Region region, int index) {
            this.region = region;
            int total = region.formations().stream().mapToInt(Formation::thickness).sum();
            rock = new BlockState[total];
            formation = new int[total];
            start = new int[region.formations().size() + 1];
            int d = 0;
            for (int k = 0; k < region.formations().size(); k++) {
                Formation f = region.formations().get(k);
                start[k] = d;
                int end = d + f.thickness(), bed = 0;
                while (d < end) {
                    long h = hash(index, k, bed++);
                    // Beds a metre either side of the formation's usual thickness.
                    int thick = Math.max(1, f.bed() + (int) Math.floorMod(h >>> 8, 3) - 1);
                    BlockState state = f.palette()[(int) Math.floorMod(h, f.palette().length)].defaultBlockState();
                    for (int i = 0; i < thick && d < end; i++, d++) { rock[d] = state; formation[d] = k; }
                }
            }
            start[region.formations().size()] = total;
            basement = region.basement().defaultBlockState();
        }
    }

    private static long hash(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ c * 0x165667B19E3779F9L ^ 0x5354524154414CL;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return h;
    }

    /** A column's strata: its region and its datum, in metres and as the block the datum's metre falls in. */
    public final class Layers {
        private final Built built;
        private final double datum, sea;

        private Layers(Built built, double datum, double sea) { this.built = built; this.datum = datum; this.sea = sea; }

        /** The rock at block height {@code y}. */
        public BlockState at(int y) {
            int d = Mth.floor(datum - (y - sea));
            if (d < 0) return built.rock[0];
            return d < built.rock.length ? built.rock[d] : built.basement;
        }

        /** Whether a block {@code depth} under the ground of column {@code i} of an 18-wide height grid shows in a cliff face, or lies just under the ground. */
        public boolean shows(int depth, int[] height, int i) {
            int h = height[i];
            int low = Math.min(Math.min(height[i - 1], height[i + 1]), Math.min(height[i - 18], height[i + 18]));
            return depth > 0 && depth < Math.max(4, h - low + 2);
        }

        /** How much of the ground's small-scale noise to keep at {@code metres}: little down in the layered rock, where beds keep it level. */
        public double calm(double metres) { return 1 - 0.8 * below(metres); }

        /**
         * Ground metres stepped by the rock: ledgy beds into benches and risers, cliff makers into
         * steep faces with a rounded brink and foot. Every formation's top and base stay where
         * they are, so the layers still meet the ground at their true heights.
         */
        public double terrace(double metres) {
            double w = below(metres);
            if (w <= 0) return metres;
            double d = datum - metres;
            int i = Mth.floor(d);
            if (i >= built.rock.length) return metres;
            int k = built.formation[i];
            Formation f = built.region.formations().get(k);
            int top = built.start[k], base = built.start[k + 1];
            double stepped;
            if (f.weathering() == Weathering.SLOPE) return metres;
            if (f.weathering() == Weathering.CLIFF) {
                double t = (d - top) / (base - top);
                stepped = top + (base - top) * Mth.lerp(0.7, t, t * t * (3 - 2 * t));
            } else {
                // Ledges a few beds thick: a flat bench at the top of each, then a riser.
                int period = Math.max(3, f.bed() * 2), cell = top + (int) ((d - top) / period) * period;
                int length = Math.min(period, base - cell);
                double t = (d - cell) / length;
                stepped = cell + length * Mth.lerp(0.75, t, t * t * t);
            }
            return metres + w * (d - stepped);
        }

        /** 0 on the plateau tops, rising to 1 a little under the datum. */
        private double below(double metres) { return Mth.clamp((datum - metres - RIM) / 20, 0, 1); }

        /**
         * A column's cover with the rock showing: on cliffs and wherever the cover is only rock or
         * loose stone, under the plateau tops. Flats keep their soil, or the region's own loose ground.
         */
        public Surface dress(Surface surface, int y, double steep) {
            if (below(y - sea) <= 0) return surface;
            BlockState top = surface.top();
            Block ground = built.region.ground();
            boolean grass = top.is(Blocks.GRASS_BLOCK) && ground != null, soil = grass;
            for (Block b : SOIL) if (top.is(b)) { soil = true; break; }
            if (!soil || steep > 0.75) {
                BlockState rock = at(y);
                return new Surface(badlands, rock, rock, 0, 0, 0, 0, 0, false);
            }
            if (ground != null && (grass || top.is(Blocks.SAND))) {
                BlockState loose = ground.defaultBlockState();
                return new Surface(surface.biome(), loose, loose, surface.trees(), surface.spruce(), surface.birch(),
                        surface.grass(), surface.flowers(), surface.ferns(), surface.broadleaf());
            }
            return surface;
        }
    }

    /**
     * The strata of a block column, or null outside layered country. {@code sea} is the block
     * height of 0 m, so metres + sea is a block height.
     */
    @Nullable public Layers layers(double x, double z, double sea) {
        GeoPoint p = globe.geographic(x + 0.5, z + 0.5);
        for (Built b : built) {
            if (!b.region.contains(p.latitude(), p.longitude())) continue;
            double datum = datum(x, z) + 3 * wiggle.getValue(x / 700.0, z / 700.0);
            return new Layers(b, datum, sea);
        }
        return null;
    }

    /** The datum at a block position: bilinear between the cell corners'. */
    double datum(double x, double z) {
        double gx = x / CELL, gz = z / CELL;
        long i = (long) Math.floor(gx), j = (long) Math.floor(gz);
        double fx = gx - i, fz = gz - j;
        return Mth.lerp(fz, Mth.lerp(fx, corner(i, j), corner(i + 1, j)), Mth.lerp(fx, corner(i, j + 1), corner(i + 1, j + 1)));
    }

    /** The datum at a cell corner: the highest of the cells around less the fall over the distance to them. */
    private float corner(long i, long j) {
        long key = i << 32 ^ (j & 0xFFFFFFFFL);
        Float known = datum.get(key);
        if (known != null) return known;
        double metresPerBlock = globe.metresPerBlock(j * (double) CELL);
        double cellMetres = CELL * metresPerBlock;
        int reach = (int) Math.ceil(REACH / cellMetres);
        float best = -Float.MAX_VALUE;
        for (int dj = -reach; dj < reach; dj++) for (int di = -reach; di < reach; di++) {
            // Distance from the corner to the nearest point of the cell.
            double nx = di >= 0 ? di : di + 1, nz = dj >= 0 ? dj : dj + 1;
            double distance = Math.sqrt(nx * nx + nz * nz) * cellMetres;
            if (distance > REACH) continue;
            best = (float) Math.max(best, highest(i + di, j + dj) - DIP * distance);
        }
        if (datum.size() > 65_536) datum.clear();
        datum.put(key, best);
        return best;
    }

    /** The highest of a few coarse elevation samples across a cell. */
    private float highest(long i, long j) {
        long key = i << 32 ^ (j & 0xFFFFFFFFL);
        Float known = highest.get(key);
        if (known != null) return known;
        double best = -Double.MAX_VALUE;
        for (int b = 0; b < 4; b++) for (int a = 0; a < 4; a++)
            best = Math.max(best, globe.metres((i + (a + 0.5) / 4) * CELL, (j + (b + 0.5) / 4) * CELL));
        if (highest.size() > 262_144) highest.clear();
        highest.put(key, (float) best);
        return (float) best;
    }
}
