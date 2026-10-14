package io.earthplatform.minecraft.cubic.gen;

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
 * Rock strata of layered sedimentary country, such as the Colorado Plateau's. Layered country is
 * found from the ground and the climate, wherever they are: a dry land of broad level tops, its
 * tablelands, cut deep by canyons, as across the Colorado Plateau, the Tassili n'Ajjer or Wadi Rum.
 * It takes one of a few sequences of formations, chosen by how deep it is cut and how high its tops
 * stand, each a real one, top down, with their thickness, colours and how they weather: cliff
 * makers, ledgy beds and soft slopes. The layers lie level under a datum that follows the
 * highest plateau surfaces, falling at most {@link #DIP} metres per metre away from them, so a
 * canyon's two rims carry their own tops while its floor sits deep in the sequence. Columns show
 * the layers in their cliff faces, near and far, and their ground steps into ledges and benches
 * where the rock does.
 */
public final class Strata {
    /** What the strata need from the world: a coarse elevation model, the map's scale and the climate. */
    public interface Globe {
        /** Ground height in metres above the sea at a block position, from a coarse model. */
        double metres(double x, double z);
        double metresPerBlock(double z);
        /** Whether the climate at a block position is dry: desert or steppe. */
        boolean arid(double x, double z);
    }

    /** How a formation weathers. */
    enum Weathering { SLOPE, LEDGES, CLIFF }

    /** One formation: thickness in metres, beds of about {@code bed} metres each, coloured from {@code palette}. */
    record Formation(String name, int thickness, Weathering weathering, int bed, Block... palette) {}

    /**
     * A sequence of layered rock, after the real one it is named for: its formations top down, the
     * rock under them, and the loose ground that covers its flats in place of plain sand, if any.
     */
    record Sequence(String name, @Nullable Block ground, Block basement, List<Formation> formations) {}

    /**
     * Ground tops that stay as the land cover put them on gentle slopes down in the rock; anything
     * else turns to the rock beneath. Grass is not among them: the benches of a dry canyon are
     * bare rock and scrub, so grass gives way to the sequence's loose ground, or to the rock.
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

    private static final Sequence BRYCE = new Sequence("Bryce Canyon", null, Blocks.STONE, List.of(
            f("Claron Formation, white", 60, Weathering.LEDGES, 3, WT, WT, PT),
            f("Claron Formation, pink", 200, Weathering.LEDGES, 3, OT, PT, OT, WT, T),
            f("Kaiparowits Formation", 250, Weathering.SLOPE, 4, LGT, WT, YT),
            f("Straight Cliffs Formation", 300, Weathering.CLIFF, 5, YT, WT, LGT)));

    /**
     * The sequences, from published stratigraphic sections, rounded, in the order {@link #sequence}
     * picks them. Palettes repeat a block to weight it.
     */
    static final List<Sequence> SEQUENCES = List.of(
            // Country cut deepest, down to its crystalline basement: the Grand Canyon's.
            new Sequence("Grand Canyon", null, Blocks.DEEPSLATE, List.of(
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
            // Country cut deep through thick cliff-making sandstone: Zion's.
            new Sequence("Zion", null, Blocks.STONE, List.of(
                    f("Carmel Formation", 40, Weathering.SLOPE, 3, LGT, WT),
                    f("Navajo Sandstone, upper", 250, Weathering.CLIFF, 7, WT, WT, SS),
                    f("Navajo Sandstone, lower", 400, Weathering.CLIFF, 7, OT, OT, T, RSS),
                    f("Kayenta Formation", 180, Weathering.LEDGES, 4, RT, BT, T),
                    f("Moenave Formation", 110, Weathering.LEDGES, 4, OT, RT, WT),
                    f("Chinle Formation", 120, Weathering.SLOPE, 3, LGT, PU, LGT, RT),
                    f("Moenkopi Formation", 300, Weathering.LEDGES, 3, RT, RT, WT, BT),
                    f("Kaibab Limestone", 90, Weathering.LEDGES, 5, WT, LGT))),
            // High tablelands under their youngest soft beds, eroded into pink hoodoos: Bryce Canyon's.
            BRYCE,
            // Lone buttes and mesas standing over open flats of red sand: Monument Valley's.
            new Sequence("Monument Valley", Blocks.RED_SAND, Blocks.STONE, List.of(
                    f("Shinarump and Moenkopi", 50, Weathering.LEDGES, 4, BT, LGT, RT),
                    f("De Chelly Sandstone", 250, Weathering.CLIFF, 8, OT, OT, T, RSS),
                    f("Organ Rock Formation", 200, Weathering.LEDGES, 4, RT, RT, T),
                    f("Cedar Mesa Sandstone", 200, Weathering.CLIFF, 6, WT, SS, OT))),
            // Broad tablelands cut by a maze of canyons: Canyonlands', around Moab.
            new Sequence("Canyonlands", null, Blocks.STONE, List.of(
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
    /** Metres under the highest ground around that a column still counts among the tops, where woods keep their ground. */
    static final double TOPS = 60;
    /** The steepest ground that keeps its soil: two block steps across a column, as a single step's corner. */
    private static final double FLAT = 0.9;
    /**
     * The steepest far voxel that keeps its soil. A far voxel's ground is the average over many blocks of benches
     * and risers, so a terraced canyon side reads gentle there though up close it is mostly rock; only ground
     * near level keeps its soil, as up close.
     */
    static final double FAR_FLAT = 0.3;

    private final Globe globe;
    private final Holder<Biome> badlands;
    private final SimplexNoise wiggle;
    private final Built[] built;
    private final ConcurrentHashMap<Long, Float> highest = new ConcurrentHashMap<>(), lowest = new ConcurrentHashMap<>(), datum = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Zone> zones = new ConcurrentHashMap<>();

    public Strata(long seed, Globe globe, Holder<Biome> badlands) {
        this.globe = globe;
        this.badlands = badlands;
        wiggle = new SimplexNoise(new LegacyRandomSource(seed ^ 0x5354524154414CL));
        built = new Built[SEQUENCES.size()];
        for (int i = 0; i < built.length; i++) built[i] = new Built(SEQUENCES.get(i), i);
    }

    /** A sequence's rock by whole metres under its datum, and where each formation starts. */
    private static final class Built {
        final Sequence sequence;
        final BlockState[] rock;
        final int[] start, formation;
        final BlockState basement;

        Built(Sequence sequence, int index) {
            this.sequence = sequence;
            int total = sequence.formations().stream().mapToInt(Formation::thickness).sum();
            rock = new BlockState[total];
            formation = new int[total];
            start = new int[sequence.formations().size() + 1];
            int d = 0;
            for (int k = 0; k < sequence.formations().size(); k++) {
                Formation f = sequence.formations().get(k);
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
            start[sequence.formations().size()] = total;
            basement = sequence.basement().defaultBlockState();
        }
    }

    private static long hash(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ c * 0x165667B19E3779F9L ^ 0x5354524154414CL;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return h;
    }

    /** A column's strata: its sequence and its datum, in metres and as the block the datum's metre falls in. */
    public final class Layers {
        private final Built built;
        private final double datum, sea, x, z;

        private Layers(Built built, double datum, double sea, double x, double z) {
            this.built = built; this.datum = datum; this.sea = sea; this.x = x; this.z = z;
        }

        /** The rock at block height {@code y}. */
        public BlockState at(int y) {
            int d = Mth.floor(datum - (y - sea));
            if (d < 0) return built.rock[0];
            return d < built.rock.length ? built.rock[d] : built.basement;
        }

        /** Whether a block {@code depth} under the ground of column {@code i} of an 18-wide height grid shows in a cliff face, or lies just under the ground. */
        public boolean shows(int depth, int[] height, int i) { return face(depth, height, i); }

        /** Whether a block {@code depth} under the ground of column {@code i} of an 18-wide height grid shows in a cliff face, or lies just under the ground. */
        public static boolean face(int depth, int[] height, int i) {
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
            Formation f = built.sequence.formations().get(k);
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
            // Thick cliff makers, as the Vishnu Schist's 600 m, would move the ground tens of metres, which cut the inner
            // gorge's floor far below the Colorado and left the river standing in walls over its banks.
            return metres + w * Mth.clamp(d - stepped, -MAX_STEP, MAX_STEP);
        }

        /** The most, in metres, that terracing moves the ground up or down. */
        private static final double MAX_STEP = 8;

        /** 0 on the plateau tops, rising to 1 a little under the datum. */
        private double below(double metres) { return Mth.clamp((datum - metres - RIM) / 20, 0, 1); }

        /**
         * A column's cover with the rock showing: on cliffs and wherever the cover is only rock or
         * loose stone, under the plateau tops. Flats keep their soil, or the sequence's own loose ground.
         */
        public Surface dress(Surface surface, int y, double steep, int voxel) {
            if (below(y - sea) <= 0) return surface;
            double flat = voxel > 1 ? FAR_FLAT : FLAT;
            // Woods hold their ground on the tops: where the land cover grows trees, as the ponderosa forest on the
            // Kaibab and Coconino plateaus at the Grand Canyon's rims, gentle ground at the height of the country around
            // keeps it, though the datum stands far above it. Down in the canyons the benches and slopes show their
            // beds whatever grows on them, as do cliffs everywhere.
            if (surface.trees() > 0 && steep <= flat && y - sea >= top(x, z) - TOPS) return surface;
            BlockState top = surface.top();
            Block ground = built.sequence.ground();
            // Where the sequence has its own loose ground, as Monument Valley's red sand, its flats are all of it.
            // A flat here is anything short of three block steps across the column, so the corner of a single
            // step, where it turns diagonally, keeps the sand rather than lining every gentle contour with rock.
            boolean loose = ground != null && (top.is(Blocks.GRASS_BLOCK) || steep <= flat), soil = loose;
            for (Block b : SOIL) if (top.is(b)) { soil = true; break; }
            if (!soil || steep > flat) {
                BlockState rock = at(y);
                return new Surface(badlands, rock, rock, 0, 0, 0, 0, 0, false);
            }
            if (loose || ground != null && top.is(Blocks.SAND)) {
                BlockState sand = ground.defaultBlockState();
                return new Surface(surface.biome(), sand, sand, surface.trees(), surface.spruce(), surface.birch(),
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
        double gx = x / CELL, gz = z / CELL;
        long i = (long) Math.floor(gx), j = (long) Math.floor(gz);
        double fx = gx - i, fz = gz - j;
        double layered = Mth.lerp(fz, Mth.lerp(fx, zone(i, j).layered(), zone(i + 1, j).layered()),
                Mth.lerp(fx, zone(i, j + 1).layered(), zone(i + 1, j + 1).layered()));
        // Layered country ends along a wandering line, not along the cells'.
        if (layered + 0.3 * wiggle.getValue(x / 1500.0 + 31, z / 1500.0) < 0.5) return null;
        // Each part of it takes the sequence of the country nearest, so sequences meet along wandering lines too.
        Zone near = zone(Math.round(gx + 0.4 * wiggle.getValue(z / 2300.0, x / 2300.0 + 17)),
                Math.round(gz + 0.4 * wiggle.getValue(x / 2300.0 + 53, z / 2300.0)));
        double datum = datum(x, z) + 3 * wiggle.getValue(x / 700.0, z / 700.0);
        return new Layers(built[sequence(near)], datum, sea, x, z);
    }

    /** Least fraction of the high ground within reach whose tops stand at the datum, for broad level tops: tablelands. */
    static final double TABLELAND = 0.08;
    /** Least depth, in metres, that canyons cut the country within reach under its datum. */
    static final double INCISED = 250;
    /**
     * Least rise, in metres, across a datum cell for it to hold a wall: a cliff or a canyon's side, steeper on
     * average than one in three across the cell; and the least share of the cells within reach that hold one. Canyon country
     * has walls all through it, as do the lone buttes of Monument Valley; rolling steppe and plains, however
     * dry and level their tops, have none, and their gentle slopes would show the beds as broad stripes.
     */
    static final double WALL = 150, WALLS = 0.01;
    /** Metres a cell's top may stand under the datum, carried back over its distance at the dip, and still count among the tops. */
    private static final double TOP = 80;

    /**
     * The country around a datum cell corner, within {@link #REACH}: its datum, its lowest ground,
     * the fractions of its high cells and of all its cells whose tops reach the datum, whether it is
     * dry, and the fraction of its cells that hold a wall ({@link #WALL}).
     */
    record Zone(float datum, float floor, float tops, float share, boolean arid, float walls) {
        /** How surely the country is layered: dry, of broad level tops, cut deep, and walled. */
        double layered() {
            if (!arid) return 0;
            return Math.min(Math.min(Mth.clamp((tops - TABLELAND) / 0.1 + 0.5, 0, 1), Mth.clamp((datum - floor - INCISED) / 100 + 0.5, 0, 1)),
                    Mth.clamp((walls - WALLS) / WALLS + 0.5, 0, 1));
        }
    }

    private static final Zone WET = new Zone(0, 0, 0, 0, false, 0);

    /**
     * Which sequence a country takes: the Grand Canyon's where it is cut deepest, through the most
     * rock; Bryce Canyon's on the highest tablelands; Zion's where cut deep; Monument Valley's where
     * little of the land stands at its tops, as lone buttes over open flats; and Canyonlands' on broad cut tablelands.
     */
    static int sequence(Zone zone) {
        double cut = zone.datum() - zone.floor();
        if (!zone.arid()) return 4;
        if (cut >= DEEPEST) return 0;
        if (zone.datum() >= HIGHEST) return 2;
        if (cut >= DEEP) return 1;
        if (zone.share() < BUTTES) return 3;
        return 4;
    }

    /** Metres cut under the datum for the deepest sequence and for a deep one; tops of the highest tablelands; the most of the land at its tops among open buttes. */
    static final double DEEPEST = 1200, DEEP = 900, HIGHEST = 2500, BUTTES = 0.2;

    /** Distance in metres from a cell corner to the nearest point of the cell {@code di}, {@code dj} cells away. */
    private static double distance(int di, int dj, double cellMetres) {
        double nx = di >= 0 ? di : di + 1, nz = dj >= 0 ? dj : dj + 1;
        return Math.sqrt(nx * nx + nz * nz) * cellMetres;
    }

    /** The country around a datum cell corner. */
    Zone zone(long i, long j) {
        long key = i << 32 ^ (j & 0xFFFFFFFFL);
        Zone known = zones.get(key);
        if (known != null) return known;
        double metresPerBlock = globe.metresPerBlock(j * (double) CELL);
        double cellMetres = CELL * metresPerBlock;
        int reach = (int) Math.ceil(REACH / cellMetres);
        // Dry country first, anywhere around the corner within its reach: most of the world is not, and needs no more.
        double out = reach * CELL / 2.0, cx = i * (double) CELL, cz = j * (double) CELL;
        boolean dry = globe.arid(cx, cz);
        for (int k = 0; k < 12 && !dry; k++) {
            double angle = k * Math.PI / 6, r = k % 2 == 0 ? out : 2 * out;
            dry = globe.arid(cx + r * Math.cos(angle), cz + r * Math.sin(angle));
        }
        Zone zone = WET;
        if (dry) {
            float datum = corner(i, j), floor = Float.MAX_VALUE;
            for (int dj = -reach; dj < reach; dj++) for (int di = -reach; di < reach; di++)
                if (distance(di, dj, cellMetres) <= REACH) floor = Math.min(floor, lowest(i + di, j + dj));
            // Of the cells that rise above halfway down the cut, how many reach the datum: nearly all on a tableland, whose
            // rims stand level, but few among mountains, whose peaks and ridges stand at every height.
            double half = (datum + floor) / 2;
            int high = 0, tops = 0, cells = 0, walls = 0;
            for (int dj = -reach; dj < reach; dj++) for (int di = -reach; di < reach; di++) {
                double distance = distance(di, dj, cellMetres);
                if (distance > REACH) continue;
                cells++;
                float top = highest(i + di, j + dj);
                if (top - lowest(i + di, j + dj) >= WALL) walls++;
                if (top < half) continue;
                high++;
                if (top + DIP * distance >= datum - TOP) tops++;
            }
            zone = new Zone(datum, floor, high == 0 ? 0 : tops / (float) high, tops / (float) Math.max(1, cells), true, walls / (float) Math.max(1, cells));
        }
        if (zones.size() > 262_144) zones.clear();
        zones.put(key, zone);
        return zone;
    }

    /** The highest ground, in metres, of the datum cell holding a block position and the eight around it. */
    private double top(double x, double z) {
        long i = (long) Math.floor(x / CELL), j = (long) Math.floor(z / CELL);
        float best = -Float.MAX_VALUE;
        for (long dj = -1; dj <= 1; dj++) for (long di = -1; di <= 1; di++) best = Math.max(best, highest(i + di, j + dj));
        return best;
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
        long key = it.unimi.dsi.fastutil.HashCommon.mix(i << 32 ^ (j & 0xFFFFFFFFL));
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
        long key = it.unimi.dsi.fastutil.HashCommon.mix(i << 32 ^ (j & 0xFFFFFFFFL));
        Float known = highest.get(key);
        if (known != null) return known;
        double best = -Double.MAX_VALUE;
        for (int b = 0; b < 4; b++) for (int a = 0; a < 4; a++)
            best = Math.max(best, globe.metres((i + (a + 0.5) / 4) * CELL, (j + (b + 0.5) / 4) * CELL));
        if (highest.size() > 262_144) highest.clear();
        highest.put(key, (float) best);
        return (float) best;
    }

    /** The lowest of a few coarse elevation samples across a cell. */
    private float lowest(long i, long j) {
        long key = i << 32 ^ (j & 0xFFFFFFFFL);
        Float known = lowest.get(key);
        if (known != null) return known;
        double best = Double.MAX_VALUE;
        for (int b = 0; b < 4; b++) for (int a = 0; a < 4; a++)
            best = Math.min(best, globe.metres((i + (a + 0.5) / 4) * CELL, (j + (b + 0.5) / 4) * CELL));
        if (lowest.size() > 262_144) lowest.clear();
        lowest.put(key, (float) best);
        return (float) best;
    }
}
