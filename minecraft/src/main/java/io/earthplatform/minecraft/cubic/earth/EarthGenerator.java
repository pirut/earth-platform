package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.GlobalElevation;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * The real planet at true height: one block is one metre up, and one metre across at the world's
 * home latitude (the Web Mercator grid keeps shapes true everywhere, with sizes growing toward the
 * poles). Sea level is Y 63: the sea's top water block is Y 62, so ground at 0 m is level with
 * it. Elevation streams from AWS Terrain Tiles; small relief that the source is too coarse to hold
 * is added by noise, more on steep ground, none on beaches.
 *
 * <p>Worlds made with land cover read ESA WorldCover (10 m classes) for what covers the ground and
 * CHELSA (1 km climatologies) for the climate: its Köppen-Geiger zone, mean and warmest-season
 * temperatures carried to each column's own height, and rainfall, which pick each class's biome,
 * trees and plants, so a forest is rainforest, oak, pine or taiga where it is on Earth. Classes:
 * forests, shrubs, grass, bare rock and sand, snow and ice, wetlands and mangroves, and lakes and
 * rivers, whose water sits at the surveyed surface with a bed that deepens away from the shore. The
 * world is wild land only: towns take the cover their climate gives and farm fields are grassland.
 * The 10 m pixel edges are broken up by a small warp so borders look natural. Worlds
 * made before land cover keep a temperature from latitude and height, broad noise for wetness and
 * no inland water.
 */
public final class EarthGenerator extends SurfaceGenerator {
    public static final String ID = "earth-v1";
    /** Top water block of the sea. */
    public static final int SEA = 62;
    private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState(),
            SANDSTONE = Blocks.SANDSTONE.defaultBlockState(), PACKED_ICE = Blocks.PACKED_ICE.defaultBlockState(),
            CLAY = Blocks.CLAY.defaultBlockState(), MOSS = Blocks.MOSS_BLOCK.defaultBlockState(), PODZOL = Blocks.PODZOL.defaultBlockState(),
            ANDESITE = Blocks.ANDESITE.defaultBlockState(), RED_SAND = Blocks.RED_SAND.defaultBlockState(),
            TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState(), RED_SANDSTONE = Blocks.RED_SANDSTONE.defaultBlockState(),
            CALCITE = Blocks.CALCITE.defaultBlockState(), POWDER_SNOW = Blocks.POWDER_SNOW.defaultBlockState();
    /** Badlands rock colours, plain and orange the most common. */
    private static final BlockState[] BANDS = {TERRACOTTA, Blocks.ORANGE_TERRACOTTA.defaultBlockState(), Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
            Blocks.BROWN_TERRACOTTA.defaultBlockState(), Blocks.RED_TERRACOTTA.defaultBlockState(), Blocks.WHITE_TERRACOTTA.defaultBlockState(),
            Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState()};

    private final EarthSettings settings;
    private final MercatorGrid grid;
    private final TerrariumTileSource tiles;
    private final GlobalElevation[] elevation = new GlobalElevation[TerrariumTileSource.MAX_ZOOM + 1];
    @Nullable private final WorldCoverSource cover;
    @Nullable private final ClimateSource climate;
    private final SimplexNoise relief, crags, wetBroad, wetLocal, warmth, warpX, warpZ, patches;
    private final Holder<Biome> ocean, deepOcean, coldOcean, deepColdOcean, frozenOcean, lukewarmOcean, warmOcean,
            beach, snowyBeach, stonyShore, snowySlopes, frozenPeaks, stonyPeaks, meadow, taiga, oldTaiga, forest,
            plains, jungle, desert, savanna, river, frozenRiver, swamp, mangroveSwamp, snowyPlains, birchForest,
            badlands, sparseJungle, windsweptSavanna, snowyTaiga, grove, darkForest, flowerForest, cherryGrove, bambooJungle, sunflowerPlains;

    private record Noises(RandomSource random, SimplexNoise[] noise) {
        static Noises of(long seed) {
            RandomSource random = new LegacyRandomSource(seed ^ 0x45415254485631L);
            SimplexNoise[] noise = new SimplexNoise[5];
            for (int i = 0; i < noise.length; i++) noise[i] = new SimplexNoise(random);
            return new Noises(random, noise);
        }
    }

    /**
     * @param cover   land cover, or null for a world made before land cover, which keeps noise for wetness
     * @param climate real climate, used with land cover; without it the climate comes from latitude and height
     */
    public EarthGenerator(long seed, Registry<Biome> biomes, EarthSettings settings, TerrariumTileSource tiles,
                          @Nullable WorldCoverSource cover, @Nullable ClimateSource climate) {
        this(seed, biomes, settings, tiles, cover, climate, Noises.of(seed));
    }

    private EarthGenerator(long seed, Registry<Biome> biomes, EarthSettings settings, TerrariumTileSource tiles,
                           @Nullable WorldCoverSource cover, @Nullable ClimateSource climate, Noises noises) {
        super(seed, biomes, noises.random);
        this.settings = settings;
        this.grid = new MercatorGrid(settings.metresPerBlock());
        this.tiles = tiles;
        this.cover = cover;
        this.climate = climate;
        // Drawn from their own random, after the shared one, so caves stay as they were.
        RandomSource own = new LegacyRandomSource(seed ^ 0x434F564552L);
        warpX = new SimplexNoise(own); warpZ = new SimplexNoise(own); patches = new SimplexNoise(own);
        for (int zoom = FAR_ZOOM; zoom <= TerrariumTileSource.MAX_ZOOM; zoom++) elevation[zoom] = new GlobalElevation(grid, tiles, zoom);
        SimplexNoise[] n = noises.noise;
        relief = n[0]; crags = n[1]; wetBroad = n[2]; wetLocal = n[3]; warmth = n[4];
        ocean = holder(biomes, Biomes.OCEAN); deepOcean = holder(biomes, Biomes.DEEP_OCEAN); coldOcean = holder(biomes, Biomes.COLD_OCEAN);
        deepColdOcean = holder(biomes, Biomes.DEEP_COLD_OCEAN); frozenOcean = holder(biomes, Biomes.FROZEN_OCEAN);
        lukewarmOcean = holder(biomes, Biomes.LUKEWARM_OCEAN); warmOcean = holder(biomes, Biomes.WARM_OCEAN);
        beach = holder(biomes, Biomes.BEACH); snowyBeach = holder(biomes, Biomes.SNOWY_BEACH); stonyShore = holder(biomes, Biomes.STONY_SHORE);
        snowySlopes = holder(biomes, Biomes.SNOWY_SLOPES); frozenPeaks = holder(biomes, Biomes.FROZEN_PEAKS); stonyPeaks = holder(biomes, Biomes.STONY_PEAKS);
        meadow = holder(biomes, Biomes.MEADOW); taiga = holder(biomes, Biomes.TAIGA); oldTaiga = holder(biomes, Biomes.OLD_GROWTH_SPRUCE_TAIGA);
        forest = holder(biomes, Biomes.FOREST); plains = holder(biomes, Biomes.PLAINS); jungle = holder(biomes, Biomes.JUNGLE);
        desert = holder(biomes, Biomes.DESERT); savanna = holder(biomes, Biomes.SAVANNA);
        river = holder(biomes, Biomes.RIVER); frozenRiver = holder(biomes, Biomes.FROZEN_RIVER); swamp = holder(biomes, Biomes.SWAMP);
        mangroveSwamp = holder(biomes, Biomes.MANGROVE_SWAMP); snowyPlains = holder(biomes, Biomes.SNOWY_PLAINS);
        birchForest = holder(biomes, Biomes.BIRCH_FOREST); badlands = holder(biomes, Biomes.BADLANDS);
        sparseJungle = holder(biomes, Biomes.SPARSE_JUNGLE); windsweptSavanna = holder(biomes, Biomes.WINDSWEPT_SAVANNA);
        snowyTaiga = holder(biomes, Biomes.SNOWY_TAIGA); grove = holder(biomes, Biomes.GROVE);
        darkForest = holder(biomes, Biomes.DARK_FOREST); flowerForest = holder(biomes, Biomes.FLOWER_FOREST);
        cherryGrove = holder(biomes, Biomes.CHERRY_GROVE); bambooJungle = holder(biomes, Biomes.BAMBOO_JUNGLE);
        sunflowerPlains = holder(biomes, Biomes.SUNFLOWER_PLAINS);
    }

    @Override public String id() { return ID; }
    @Override public int seaLevel() { return SEA; }
    @Override protected int shoreLevel() { return SEA; }
    @Override protected boolean emeralds(int maxGround) { return maxGround > SEA + 1800; }
    @Override public boolean barren(int x, int z, int y) { return (cover == null ? temperature(x, z, y) : local(x, z, y).temperature()) < -3; }
    public EarthSettings settings() { return settings; }
    public MercatorGrid grid() { return grid; }

    @Override public int[] arrival() {
        GeoPoint home = new GeoPoint(settings.latitude(), settings.longitude());
        return new int[]{Mth.floor(grid.blockX(home)), Mth.floor(grid.blockZ(home))};
    }

    /** Block X and Z of a latitude and longitude. */
    public int[] block(double latitude, double longitude) {
        GeoPoint point = new GeoPoint(latitude, longitude);
        return new int[]{Mth.floor(grid.blockX(point)), Mth.floor(grid.blockZ(point))};
    }

    @Override public String place(int x, int z) {
        GeoPoint point = grid.geographic(x + 0.5, z + 0.5);
        return String.format(Locale.ROOT, "%.5f, %.5f", point.latitude(), point.longitude());
    }

    @Override public String credits() {
        return "Elevation: AWS Terrain Tiles (USGS 3DEP, SRTM, GMTED2010, ETOPO1 and others)"
                + (cover == null ? "" : ". Land cover: " + WorldCoverSource.ATTRIBUTION)
                + (climate == null ? "" : ". Climate: " + ClimateSource.ATTRIBUTION);
    }

    // Height -------------------------------------------------------------------------------------

    /** Zoom of the coarsest elevation the far levels read. */
    private static final int FAR_ZOOM = 11;
    /** Source zoom for a far level: about one source pixel per voxel. */
    private static int zoom(int level) { return level <= 2 ? TerrariumTileSource.MAX_ZOOM : Math.max(FAR_ZOOM, 17 - level); }

    @Override protected void ground(int x, int z, int[] out) { farGround(0, x, z, out); }

    /** Level 0 stands for cubes: one-block voxels, read like far level 1. */
    @Override protected void farGround(int level, int x, int z, int[] out) {
        double[] sample = new double[3];
        GlobalElevation dem = elevation[zoom(Math.max(1, level))];
        double metres = dem.sample(x + 0.5, z + 0.5, sample);
        place(dem, x, z, metres, sample, 1 << level, out);
    }

    /** Ground and water of a column from its elevation, slope, sea share and land cover. */
    private void place(GlobalElevation dem, int x, int z, double metres, double[] sample, int voxel, int[] out) {
        if (sample[2] > 0.5 || offshore(x, z, metres, voxel)) {
            out[0] = Math.min(SEA - 1, Mth.floor(SEA + 0.5 + metres));
            out[1] = SEA;
            return;
        }
        double steep = Math.min(2.5, Math.sqrt(sample[0] * sample[0] + sample[1] * sample[1]));
        if (cover != null && cover(x, z, voxel) == WorldCoverSource.WATER) {
            int surface = lakeSurface(dem, x, z, voxel);
            // Shadowed cliffs can map as water; water never lies on a steep slope well above its own level.
            boolean shadow = steep > 0.7 && SEA + 0.5 + metres - surface > 4;
            if (!shadow && sample[2] > 0.02 && surface <= SEA) {
                // Water by the coast is the sea.
                out[0] = Math.min(SEA - 1, Mth.floor(SEA + 0.5 + metres));
                out[1] = SEA;
                return;
            }
            if (!shadow) {
                out[0] = surface - lakeDepth(x, z, voxel);
                out[1] = surface;
                return;
            }
        }
        double amplitude = Mth.clamp((metres - 0.5) / 4, 0, 1) * (0.6 + 1.8 * steep), detail = 0;
        if (voxel <= 8) detail += fbm(relief, x / 27.0, z / 27.0, 3) * amplitude;
        if (voxel <= 2) detail += (0.5 - Math.abs(crags.getValue(x / 9.0, z / 9.0))) * amplitude * 0.6 * Math.min(1, steep);
        int y = Mth.floor(SEA + 0.5 + metres + detail);
        // Ground beside the sea never dips under its level, so the sea stays where it is.
        if (sample[2] > 0.02 && y < SEA) y = SEA;
        // Nor does ground beside a lake or river, unless it lies well below it, as under a dam.
        if (cover != null) {
            int shore = shore(dem, x, z, voxel);
            if (shore != DRY && y < shore && y >= shore - 4) y = shore;
        }
        out[0] = y;
        out[1] = DRY;
    }

    // Land cover ----------------------------------------------------------------------------------

    /** Ground metres of a WorldCover pixel north to south. */
    private static final double PIXEL_METRES = 2 * Math.PI * 6_371_008.8 / 360 / WorldCoverSource.PIXELS_PER_DEGREE;
    /** Unit steps toward eight directions. */
    private static final double[] RING_X = {1, 0.7071, 0, -0.7071, -1, -0.7071, 0, 0.7071}, RING_Z = {0, 0.7071, 1, 0.7071, 0, -0.7071, -1, -0.7071};

    /**
     * The WorldCover class of a column, read at an overview whose pixels are no bigger than a
     * voxel. Up close the read point wanders by about half a pixel, so class borders curve.
     */
    private int cover(double x, double z, int voxel) {
        double metresPerBlock = grid.groundMetresPerBlock(z);
        if (voxel <= 8) {
            double amplitude = 0.55 * PIXEL_METRES / metresPerBlock;
            double nx = x / 13.0, nz = z / 13.0;
            x += (warpX.getValue(nx, nz) + 0.5 * warpX.getValue(nx * 2.7, nz * 2.7)) * amplitude;
            z += (warpZ.getValue(nx, nz) + 0.5 * warpZ.getValue(nx * 2.7, nz * 2.7)) * amplitude;
        }
        int overview = Mth.clamp(Mth.floor(Math.log(voxel * metresPerBlock / PIXEL_METRES) / Math.log(2)), 0, WorldCoverSource.MAX_OVERVIEW);
        GeoPoint point = grid.geographic(x + 0.5, z + 0.5);
        return cover.code(point.latitude(), point.longitude(), overview);
    }

    /** Ring radii in blocks for {@link #wild}, each three times the last. */
    private static final int[] WILD_RINGS = {300, 900, 2700, 8100};

    /**
     * The wild cover around built-up ground, so a town's site looks like the land about it: the
     * class most of eight points on the nearest ring with any wild land agree on, or no data for
     * the climate to decide when every ring is town or water. Bare ground only counts on a ring
     * with nothing else, as a town's own lots and yards map as bare.
     */
    private int wild(double x, double z, int voxel) {
        int[] votes = new int[8];
        for (int radius : WILD_RINGS) {
            int found = 0, best = WorldCoverSource.NO_DATA, most = 0;
            boolean bare = false;
            for (int i = 0; i < 8; i++) {
                int code = cover(x + RING_X[i] * radius, z + RING_Z[i] * radius, Math.max(voxel, radius / 16));
                if (code == WorldCoverSource.BARE) bare = true;
                if (code == WorldCoverSource.BUILT_UP || code == WorldCoverSource.WATER || code == WorldCoverSource.NO_DATA
                        || code == WorldCoverSource.BARE) continue;
                int same = 1;
                for (int j = 0; j < found; j++) if (votes[j] == code) same++;
                votes[found++] = code;
                if (same > most) { most = same; best = code; }
            }
            if (found > 0) return best;
            if (bare) return WorldCoverSource.BARE;
        }
        return WorldCoverSource.NO_DATA;
    }

    /** Whether bare ground lies among buildings, where it is a lot or yard rather than wild rock or sand. */
    private boolean inTown(double x, double z, int voxel) {
        int reach = Math.max(40, voxel * 2), built = 0;
        for (int i = 0; i < 8; i += 2) if (cover(x + RING_X[i] * reach, z + RING_Z[i] * reach, voxel) == WorldCoverSource.BUILT_UP) built++;
        return built >= 2;
    }

    /** Cells, in blocks, for finding whether water joins the open sea, and how many cells out a search may go. */
    private static final int SEA_CELL = 256, SEA_REACH = 512;
    /** Whether each cell's water joins the open sea, as far as searches have found. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> openSea = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether a column under 0 m is sea though the elevation tiles mark no open sea there, as where
     * they carry real depths (the North Sea, the Baltic). WorldCover leaves the open sea without
     * data and marks coastal water, tidal flats and marsh beside it; those are sea when they join
     * the open sea. Water that never reaches it, such as the Dead Sea or the Caspian, stays a lake
     * at its own level, and other land under 0 m, such as a polder or Death Valley, stays dry.
     */
    private boolean offshore(int x, int z, double metres, int voxel) {
        if (cover == null || metres >= 0) return false;
        int code = cover(x, z, voxel);
        if (code == WorldCoverSource.NO_DATA) return true;
        boolean wet = code == WorldCoverSource.WATER
                || metres < -1 && (code == WorldCoverSource.WETLAND || code == WorldCoverSource.BARE || code == WorldCoverSource.MANGROVES);
        return wet && joinsOpenSea(x, z);
    }

    /**
     * Whether the cell holding a column is water that joins the open sea through cells of water,
     * within {@value #SEA_REACH} cells. A search that ends at the open sea answers for every water
     * cell it crossed; one that runs out of water answers for all of them too, as a closed lake or
     * sea such as the Caspian; one that reaches its limit answers only for the cells within half
     * its reach, as open sea may lie just past the rest.
     */
    private boolean joinsOpenSea(int x, int z) {
        long start = (long) Math.floorDiv(x, SEA_CELL) << 32 | Math.floorDiv(z, SEA_CELL) & 0xFFFFFFFFL;
        Boolean known = openSea.get(start);
        if (known != null) return known;
        it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue queue = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap steps = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        it.unimi.dsi.fastutil.longs.LongArrayList crossed = new it.unimi.dsi.fastutil.longs.LongArrayList();
        queue.enqueue(start);
        steps.put(start, 0);
        boolean found = false, cut = false;
        while (!queue.isEmpty() && !found) {
            long cell = queue.dequeueLong();
            int cx = (int) (cell >> 32), cz = (int) cell, step = steps.get(cell);
            int code = cover((double) cx * SEA_CELL + SEA_CELL / 2.0, (double) cz * SEA_CELL + SEA_CELL / 2.0, SEA_CELL);
            if (code != WorldCoverSource.WATER && code != WorldCoverSource.NO_DATA) continue;
            crossed.add(cell);
            if (code == WorldCoverSource.NO_DATA) { found = true; break; }
            if (step >= SEA_REACH) { cut = true; continue; }
            for (int k = 0; k < 8 && !found; k += 2) {
                long next = (long) (cx + (int) RING_X[k]) << 32 | (cz + (int) RING_Z[k]) & 0xFFFFFFFFL;
                if (steps.containsKey(next)) continue;
                Boolean result = openSea.get(next);
                if (result != null && result) found = true;
                else { steps.put(next, step + 1); queue.enqueue(next); }
            }
        }
        if (openSea.size() > 1 << 20) openSea.clear();
        if (crossed.isEmpty()) openSea.put(start, false);
        for (int i = 0; i < crossed.size(); i++) {
            long cell = crossed.getLong(i);
            if (found || !cut || steps.get(cell) <= SEA_REACH / 2) openSea.put(cell, found);
        }
        return found && !crossed.isEmpty();
    }

    /**
     * Top water block of a lake or river at a column: the lowest surveyed height around it, so a
     * lake is level and its water never stands above its shore.
     */
    private int lakeSurface(GlobalElevation dem, double x, double z, int voxel) {
        double r = Math.max(8, voxel);
        double lowest = dem.metres(x + 0.5, z + 0.5);
        lowest = Math.min(lowest, Math.min(dem.metres(x + 0.5 + r, z + 0.5), dem.metres(x + 0.5 - r, z + 0.5)));
        lowest = Math.min(lowest, Math.min(dem.metres(x + 0.5, z + 0.5 + r), dem.metres(x + 0.5, z + 0.5 - r)));
        return Mth.floor(SEA + 0.5 + lowest);
    }

    /** Ring radii, in blocks, for judging how far a lake column is from its shore. */
    private static final int[] RINGS = {5, 14, 36, 90};

    /** Water depth: shallow by the shore, deepening by about one block in six away from it. */
    private int lakeDepth(int x, int z, int voxel) {
        double scale = Math.max(1, voxel / 6.0), inner = 0, distance = -1;
        for (int radius : RINGS) {
            double r = radius * scale;
            int land = 0;
            for (int k = 0; k < 8; k++) if (cover(x + RING_X[k] * r, z + RING_Z[k] * r, voxel) != WorldCoverSource.WATER) land++;
            if (land > 0) { distance = inner + (r - inner) * (1 - land / 8.0); break; }
            inner = r;
        }
        if (distance < 0) distance = inner * 1.4;
        double depth = 1.2 + distance / 6.0 + patches.getValue(x / 23.0, z / 23.0) * Math.min(3, distance / 8.0);
        return Mth.clamp((int) Math.round(depth), 1, 40);
    }

    /**
     * The highest lake or river surface just beside a land column, or {@link #DRY} when there is
     * none, so the shore can be kept from dipping under it.
     */
    private int shore(GlobalElevation dem, int x, int z, int voxel) {
        int best = DRY;
        double near = Math.max(3, voxel), far = Math.max(8, 2.5 * voxel);
        for (int k = 0; k < 8; k += 2) {
            for (double r : new double[]{near, far}) {
                double px = x + RING_X[k] * r, pz = z + RING_Z[k] * r;
                if (cover(px, pz, voxel) == WorldCoverSource.WATER) best = Math.max(best, lakeSurface(dem, px, pz, voxel));
            }
        }
        return best;
    }

    private static double fbm(SimplexNoise noise, double x, double z, int octaves) {
        double sum = 0, amplitude = 1, norm = 0;
        for (int i = 0; i < octaves; i++) { sum += noise.getValue(x, z) * amplitude; norm += amplitude; amplitude *= 0.5; x *= 2.03; z *= 2.03; }
        return sum / norm;
    }

    // Climate and cover --------------------------------------------------------------------------

    private double latitude(int z) {
        return Math.toDegrees(2 * Math.atan(Math.exp(-(z + 0.5) * settings.metresPerBlock() / MercatorGrid.RADIUS)) - Math.PI / 2);
    }

    /** Rough mean yearly temperature in °C: warm at the equator, cooling toward the poles and with height. */
    private double temperature(int x, int z, int y) {
        double lat = latitude(z);
        return 27 - 0.0074 * lat * lat - 0.0065 * Math.max(0, y - SEA) + warmth.getValue(x / 700.0, z / 700.0) * 1.5;
    }

    /** A column's climate at height y: its zone, mean and warmest-quarter temperatures (°C) and rainfall (mm a year). */
    private record Local(ClimateZone zone, double temperature, double warmest, double rain) {}

    /** The real climate at a column and height, or one from latitude and height where there is none. */
    private Local local(int x, int z, int y) {
        int metres = Math.max(0, y - SEA);
        if (climate != null) {
            // Zone borders follow the 1 km grid; a gentle warp of a few hundred metres bends them.
            double amplitude = 350 / grid.groundMetresPerBlock(z);
            GeoPoint point = grid.geographic(x + 0.5 + warpX.getValue(x / 900.0, z / 900.0) * amplitude,
                    z + 0.5 + warpZ.getValue(x / 900.0, z / 900.0) * amplitude);
            ClimateSource.Climate c = climate.at(point.latitude(), point.longitude(), metres);
            if (c != null) return new Local(ClimateZone.of(c.koppen(), c.meanTemperature(), c.warmestQuarter()),
                    c.meanTemperature(), c.warmestQuarter(), c.precipitation());
        }
        double t = temperature(x, z, y);
        return new Local(ClimateZone.of(ClimateSource.NO_DATA, t, t + 10), t, t + 10, 800);
    }

    private double wetness(int x, int z) { return fbm(wetBroad, x / 9000.0, z / 9000.0, 2) * 0.7 + wetLocal.getValue(x / 1400.0, z / 1400.0) * 0.3; }

    private boolean nearSea(int x, int z) {
        return waterY(x + 4, z) == SEA || waterY(x - 4, z) == SEA || waterY(x, z + 4) == SEA || waterY(x, z - 4) == SEA;
    }

    @Override protected Surface surface(int x, int z, int y, int water, int slope) { return surface(x, z, y, water, slope, 1); }

    @Override protected Surface farSurface(int level, int x, int z, int ground, int water, int slope) {
        return surface(x, z, ground, water, slope, 1 << level);
    }

    private Surface surface(int x, int z, int y, int water, int slope, int voxel) {
        Local local = cover == null ? null : local(x, z, y);
        double t = local == null ? temperature(x, z, y) : local.temperature();
        if (water != DRY && water != SEA && cover != null) return lake(x, z, y, water, t);
        if (water != DRY) {
            int depth = water - y;
            Holder<Biome> b = t < -2 ? frozenOcean : t < 6 ? (depth > 40 ? deepColdOcean : coldOcean)
                    : t > 21 ? warmOcean : t > 15 ? lukewarmOcean : depth > 40 ? deepOcean : ocean;
            BlockState floor = depth < 30 ? SAND : GRAVEL;
            return new Surface(b, floor, floor, 0, 0, 0, 0, 0, false);
        }
        // Summed block steps over two blocks each way, as a rough rise per run.
        double steep = slope / 2.4;
        long r = mix(x, 7, z);
        int roll = (int) Math.floorMod(r, 100);
        if (y <= SEA + 2 && nearSea(x, z)) {
            if (steep > 1) return new Surface(stonyShore, STONE, STONE, 0, 0, 0, 0, 0, false);
            return new Surface(t < 0 ? snowyBeach : beach, SAND, SAND, 0, 0, 0, 0, 0, false);
        }
        if (local != null) {
            int code = cover(x, z, voxel);
            if (code == WorldCoverSource.BUILT_UP || code == WorldCoverSource.BARE && inTown(x, z, voxel)) code = wild(x, z, voxel);
            Surface covered = code == WorldCoverSource.NO_DATA ? null : covered(code, x, z, y, local, steep, roll, slope);
            return covered != null ? covered : covered(typical(local.zone()), x, z, y, local, steep, roll, slope);
        }
        if (t < -8) {
            BlockState top = steep > 1.4 ? STONE : SNOW_BLOCK;
            return new Surface(steep > 0.9 ? frozenPeaks : snowySlopes, top, STONE, 0, 0, 0, 0, 0, false);
        }
        if (t < -3) {
            BlockState top = steep > 0.9 ? STONE : steep > 0.6 && roll < 50 ? GRAVEL : t < -6 && roll < 30 ? SNOW_BLOCK : GRASS;
            return new Surface(steep > 0.9 ? stonyPeaks : meadow, top, top == GRASS ? DIRT : STONE, 0, 0, 0, 220, 300, false);
        }
        double wet = wetness(x, z);
        BlockState top = steep > 1.15 ? STONE : steep > 0.8 ? (roll < 50 ? STONE : roll < 70 ? COARSE_DIRT : GRASS) : GRASS;
        Holder<Biome> b;
        int trees, spruce, birch, grass, flowers;
        boolean ferns = false;
        if (t < 3) { b = wet > 0.35 ? oldTaiga : taiga; trees = 75; spruce = 10; birch = 0; grass = 120; flowers = 125; ferns = true; }
        else if (t < 11) {
            if (wet < -0.45) { b = plains; trees = 8; spruce = 3; birch = 1; grass = 220; flowers = 240; }
            else { b = forest; trees = wet > 0.2 ? 80 : 65; spruce = wet > 0.2 ? 6 : 5; birch = 2; grass = 90; flowers = 130; }
        } else if (t < 19) {
            if (wet < -0.45) { b = plains; trees = 10; spruce = 0; birch = 1; grass = 220; flowers = 240; }
            else if (wet < -0.1) { b = plains; trees = 25; spruce = 0; birch = 2; grass = 200; flowers = 230; }
            else { b = forest; trees = wet > 0.3 ? 85 : 75; spruce = 0; birch = wet > 0.3 ? 3 : 2; grass = 90; flowers = 130; }
        } else {
            if (wet < -0.5) {
                BlockState sand = steep > 1.15 ? SANDSTONE : SAND;
                return new Surface(desert, sand, SANDSTONE, 0, 0, 0, 0, 0, false);
            }
            if (wet < -0.15) { b = savanna; trees = 8; spruce = 0; birch = 0; grass = 260; flowers = 262; }
            else if (wet > 0.35) { b = jungle; trees = 85; spruce = 0; birch = 0; grass = 150; flowers = 160; }
            else { b = plains; trees = 25; spruce = 0; birch = 1; grass = 220; flowers = 240; }
        }
        if (top != GRASS || slope > 6) trees = 0;
        return new Surface(b, top, top == GRASS || top == COARSE_DIRT ? DIRT : STONE, trees, spruce, birch, grass, flowers, ferns);
    }

    /** A lake or river bed: sand by the shore, then gravel, clay or mud further out. */
    private Surface lake(int x, int z, int y, int water, double t) {
        int depth = water - y;
        long r = mix(x, 11, z);
        BlockState floor = depth <= 2 ? (t < 4 ? GRAVEL : SAND) : switch ((int) Math.floorMod(r, 5)) {
            case 0 -> CLAY; case 1 -> SAND; default -> GRAVEL; };
        if (depth > 8 && patches.getValue(x / 19.0, z / 19.0) > 0.2) floor = MUD;
        return new Surface(t < -2 ? frozenRiver : river, floor, floor, 0, 0, 0, 0, 0, false);
    }

    /** The cover a zone has where the map has none: forest wherever forest grows, else its open land. */
    private static int typical(ClimateZone zone) {
        return switch (zone) {
            case HOT_DESERT, COLD_DESERT -> WorldCoverSource.BARE;
            case TROPICAL_DRY, HOT_STEPPE, MEDITERRANEAN -> WorldCoverSource.SHRUBLAND;
            case COLD_STEPPE, TUNDRA -> WorldCoverSource.GRASSLAND;
            case ICE -> WorldCoverSource.SNOW_AND_ICE;
            default -> WorldCoverSource.TREE_COVER;
        };
    }

    /**
     * What a WorldCover class puts on dry ground in this climate, or null for classes that are not
     * ground cover. Steep ground stays bare rock whatever is mapped on it.
     */
    @Nullable private Surface covered(int code, int x, int z, int y, Local local, double steep, int roll, int slope) {
        double t = local.temperature();
        ClimateZone zone = local.zone();
        boolean cliff = steep > 1.25;
        BlockState rocky = steep > 0.8 && roll < 60 ? STONE : null;
        // Patches tens of metres across: clearings, rock and scree that the 10 m map averages away.
        double patch = patches.getValue(x / 31.0, z / 31.0);
        switch (code) {
            case WorldCoverSource.TREE_COVER -> {
                if (cliff) return new Surface(t < 3 ? grove : stonyPeaks, STONE, STONE, 0, 0, 0, 0, 0, false);
                Surface s = forest(local, patch, x, z, y);
                if (rocky != null || slope > 6) return new Surface(s.biome(), rocky != null ? rocky : COARSE_DIRT, STONE, 0, 0, 0, 0, 0, false);
                return s;
            }
            case WorldCoverSource.SHRUBLAND -> {
                if (cliff) return new Surface(stonyPeaks, STONE, STONE, 0, 0, 0, 0, 0, false);
                BlockState top = rocky != null ? rocky : patch > 0.4 || roll < 20 ? COARSE_DIRT : GRASS;
                int trees = slope > 6 || top != GRASS ? 0 : 1;
                return switch (zone) {
                    case RAINFOREST -> new Surface(sparseJungle, top, DIRT, 15 * trees, 0, 0, 320, 340, true, Tree.JUNGLE);
                    case TROPICAL_DRY, HOT_STEPPE -> new Surface(patch > 0.5 ? windsweptSavanna : savanna, top, DIRT, 7 * trees, 0, 0, 320, 330, false, Tree.ACACIA);
                    // Desert scrub: bare sand and gravel with dead bushes, and cacti where they grow wild, in the Americas.
                    case HOT_DESERT -> new Surface(desert, rocky != null ? rocky : patch > 0.2 ? COARSE_DIRT : SAND, SANDSTONE, 0, 0, 0, 25, americas(x, z) ? 31 : 25, false);
                    case COLD_DESERT, COLD_STEPPE -> t < -3 ? new Surface(snowyPlains, top, DIRT, 0, 0, 0, 200, 210, false)
                            : new Surface(savanna, top, DIRT, 2 * trees, 3, 0, 260, 270, false);
                    // Chaparral and maquis: dry where summers are hot.
                    case MEDITERRANEAN -> new Surface(local.warmest() >= 21 ? savanna : plains, top, DIRT, 9 * trees, t < 10 ? 5 : 0, 0, 330, 350, false);
                    case SUBPOLAR_OCEANIC, SUBARCTIC -> new Surface(t < -3 ? snowyPlains : plains, top, DIRT, 6 * trees, 4, 5, 250, 260, false);
                    case TUNDRA, ICE -> new Surface(t < -3 ? snowyPlains : meadow, top, DIRT, 0, 0, 0, 250, 300, false);
                    // Heath with azaleas in half of the humid temperate scrub.
                    default -> new Surface(plains, top, DIRT, 9 * trees, t < 6 ? 6 : 0, 1, 330, 350, false, patch < 0 ? Tree.AZALEA : Tree.OAK);
                };
            }
            case WorldCoverSource.GRASSLAND, WorldCoverSource.CROPLAND -> {
                if (cliff) return new Surface(stonyPeaks, STONE, STONE, 0, 0, 0, 0, 0, false);
                BlockState top = rocky != null ? rocky : GRASS;
                if (t < -3) return new Surface(snowyPlains, top, DIRT, 0, 0, 0, 160, 170, false);
                return switch (zone) {
                    // Savanna and steppe grass, dry for much of the year.
                    case TROPICAL_DRY, HOT_DESERT, HOT_STEPPE -> new Surface(savanna, top, DIRT, 2, 0, 0, 380, 390, false, Tree.ACACIA);
                    // Junipers on the cold steppes.
                    case COLD_DESERT, COLD_STEPPE -> new Surface(savanna, top, DIRT, 2, 10, 0, 380, 390, false);
                    case MEDITERRANEAN -> new Surface(local.warmest() >= 21 ? savanna : plains, top, DIRT, 1, 0, 0, 360, 380, false);
                    // Alpine and arctic meadows.
                    case TUNDRA, ICE -> new Surface(meadow, top, DIRT, 0, 0, 0, 300, 360, false);
                    case SUBARCTIC, SUBPOLAR_OCEANIC -> new Surface(plains, top, DIRT, 1, 10, 0, 330, 350, false);
                    // Sunflower fields on the continental steppes and farmland.
                    case CONTINENTAL -> patch > 0.45 ? new Surface(sunflowerPlains, top, DIRT, 1, 0, 1, 330, 420, false)
                            : new Surface(plains, top, DIRT, 1, 0, 1, 330, 380, false);
                    default -> new Surface(plains, top, DIRT, 1, 0, 1, 330, 380, false);
                };
            }
            case WorldCoverSource.BARE -> {
                boolean hot = zone == ClimateZone.HOT_DESERT || zone == ClimateZone.HOT_STEPPE || zone == ClimateZone.TROPICAL_DRY;
                // Dry lands' cliffs show their rock in coloured bands, as the Colorado Plateau's and the Sahara's do.
                boolean arid = hot || zone == ClimateZone.COLD_DESERT || zone == ClimateZone.COLD_STEPPE;
                if (arid && (cliff || steep > 0.9)) return new Surface(badlands, band(y), TERRACOTTA, 0, 0, 0, 0, 0, false);
                if (hot) {
                    BlockState sand = patch > 0.45 && t > 18 ? RED_SAND : SAND;
                    return new Surface(desert, sand, sand == RED_SAND ? RED_SANDSTONE : SANDSTONE, 0, 0, 0, 0, 0, false);
                }
                if (zone == ClimateZone.COLD_DESERT && !cliff && steep <= 0.7) {
                    // Cold deserts: gravel plains and dunes.
                    BlockState top = patch > 0.15 ? SAND : patch < -0.25 ? COARSE_DIRT : GRAVEL;
                    return new Surface(t < -3 ? snowyPlains : desert, top, top == SAND ? SANDSTONE : STONE, 0, 0, 0, 0, 0, false);
                }
                BlockState top = cliff || steep > 0.7 ? STONE : patch > 0.55 ? CALCITE : patch > 0.3 ? GRAVEL : patch < -0.3 ? ANDESITE : roll < 70 ? STONE : COARSE_DIRT;
                return new Surface(t < 0 ? frozenPeaks : stonyPeaks, top, STONE, 0, 0, 0, 0, 0, false);
            }
            case WorldCoverSource.SNOW_AND_ICE -> {
                // Drifts of powder snow on the gentler slopes.
                BlockState top = steep > 1.4 ? STONE : steep < 0.5 && patch > 0.5 ? POWDER_SNOW : SNOW_BLOCK;
                return new Surface(steep > 0.9 ? frozenPeaks : snowySlopes, top, steep < 0.6 ? PACKED_ICE : STONE, 0, 0, 0, 0, 0, false);
            }
            case WorldCoverSource.WETLAND -> {
                BlockState top = patch > 0.25 ? MUD : GRASS;
                if (t < 0) return new Surface(snowyPlains, top == MUD ? MOSS : GRASS, DIRT, 2, 10, 0, 300, 310, true);
                return new Surface(swamp, top, DIRT, 10, 0, 0, 300, 320, true);
            }
            case WorldCoverSource.MANGROVES -> {
                return new Surface(mangroveSwamp, MUD, MUD, 45, 0, 0, 0, 0, false, Tree.MANGROVE);
            }
            case WorldCoverSource.MOSS_AND_LICHEN -> {
                BlockState top = rocky != null ? rocky : patch > 0.2 ? MOSS : roll < 30 ? GRAVEL : GRASS;
                return new Surface(t < -2 ? snowyPlains : meadow, top, DIRT, 0, 0, 0, top == GRASS ? 200 : 0, top == GRASS ? 230 : 0, false);
            }
            default -> { return null; }
        }
    }

    /**
     * Temperate broadleaf woods: mostly the given forest, with birch stands, flowery glades and dark,
     * dense old groves, and cherry groves where cherries grow wild, in East Asia and the Himalaya.
     */
    private Surface groves(double patch, int x, int z, Surface usual) {
        if (patch > 0.35) return new Surface(birchForest, GRASS, DIRT, 82, 0, 8, 90, 130, false);
        if (patch > 0.22) return new Surface(flowerForest, GRASS, DIRT, 45, 0, 1, 100, 320, false);
        if (patch < -0.45) return new Surface(darkForest, GRASS, DIRT, 92, 0, 1, 80, 100, false, Tree.DARK_OAK);
        if (patch < -0.2 && cherries(x, z)) return new Surface(cherryGrove, GRASS, DIRT, 35, 0, 0, 200, 420, false, Tree.CHERRY);
        return usual;
    }

    /** Bamboo forest: dense stands of bamboo among a few jungle trees, on podzol at its heart. */
    private Surface bambooForest(double patch) {
        return new Surface(bambooJungle, patch < -0.6 ? PODZOL : GRASS, DIRT, 25, 0, 0, 160, 170, true, Tree.JUNGLE);
    }

    /** Whether a column is where bamboo forests grow, in South and East Asia. */
    private boolean bamboo(int x, int z) {
        GeoPoint p = grid.geographic(x, z);
        return p.longitude() > 68 && p.longitude() < 150 && p.latitude() > -10 && p.latitude() < 40;
    }

    /** Whether a column is where cherries grow wild: Japan, Korea, China and the Himalaya. */
    private boolean cherries(int x, int z) {
        GeoPoint p = grid.geographic(x, z);
        double lat = p.latitude(), lon = p.longitude();
        return lon > 100 && lon < 146 && lat > 24 && lat < 46 || lon > 74 && lon <= 100 && lat > 25 && lat < 36;
    }

    /** Badlands rock in level bands a few blocks thick, one colour all along a cliff. */
    private BlockState band(int y) {
        int k = (int) Math.floorMod(mix(Math.floorDiv(y, 3), 0x42414E44L, 0), 100);
        return BANDS[k < 35 ? 0 : k < 55 ? 1 : k < 65 ? 2 : k < 75 ? 3 : k < 85 ? 4 : k < 93 ? 5 : 6];
    }

    /** Whether a column is in the Americas, the only place cacti grow wild. */
    private boolean americas(int x, int z) {
        double longitude = grid.geographic(x, z).longitude();
        return longitude < -30 && longitude > -170;
    }

    /**
     * Giant sequoia groves: the larger real groves on the Sierra Nevada's western slope, as
     * latitude, longitude and radius in metres (centres and sizes approximate).
     */
    private static final double[][] GROVES = {
            {38.2777, -120.3080, 500},   // Calaveras North Grove
            {38.2460, -120.2480, 1200},  // Calaveras South Grove
            {37.7590, -119.8060, 400},   // Tuolumne Grove
            {37.7480, -119.8410, 400},   // Merced Grove
            {37.5110, -119.6000, 1100},  // Mariposa Grove
            {37.4300, -119.5850, 900},   // Nelder Grove
            {37.0230, -119.1080, 400},   // McKinley Grove
            {36.8150, -118.9550, 1500},  // Converse Basin
            {36.7470, -118.9720, 700},   // Grant Grove
            {36.6950, -118.9200, 1900},  // Redwood Mountain Grove
            {36.6380, -118.8170, 500},   // Muir Grove
            {36.5700, -118.7600, 2000},  // Giant Forest
            {36.4650, -118.6750, 1300},  // Atwell Grove
            {36.2400, -118.6750, 2000},  // Mountain Home Grove
            {36.1350, -118.5150, 1500},  // Freeman Creek Grove
    };

    /** The Sierra Nevada's sequoia belt, round all the groves. */
    @Override protected boolean giants(int x, int z) {
        GeoPoint p = grid.geographic(x, z);
        return p.latitude() > 36.05 && p.latitude() < 38.33 && p.longitude() > -120.36 && p.longitude() < -118.45;
    }

    /** Whether a forest column is in a giant sequoia grove: inside a real grove, between 1,200 and 2,600 m up. */
    private boolean sequoias(int x, int z, int y) {
        int metres = y - SEA;
        if (metres < 1200 || metres > 2600 || !giants(x, z)) return false;
        GeoPoint p = grid.geographic(x, z);
        double east = 111320 * Math.cos(Math.toRadians(p.latitude()));
        for (double[] grove : GROVES) {
            double dx = (p.longitude() - grove[1]) * east, dz = (p.latitude() - grove[0]) * 110540;
            if (dx * dx + dz * dz < grove[2] * grove[2]) return true;
        }
        return false;
    }

    /** The forest of a climate: rainforest, bamboo, dry tropical woodland, pine, oak, birch, dark oak, cherry or taiga. */
    private Surface forest(Local local, double patch, int x, int z, int y) {
        // Giant sequoias among old conifers, on duff, in the real groves.
        if (sequoias(x, z, y)) return new Surface(oldTaiga, patch > -0.2 ? PODZOL : GRASS, DIRT, 70, 7, 0, 90, 100, true, Tree.SEQUOIA);
        double t = local.temperature();
        return switch (local.zone()) {
            case RAINFOREST -> patch < -0.4 && bamboo(x, z) ? bambooForest(patch) : new Surface(jungle, GRASS, DIRT, 90, 0, 0, 160, 170, true, Tree.JUNGLE);
            case TROPICAL_DRY -> local.rain() > 1000 ? new Surface(sparseJungle, GRASS, DIRT, 70, 0, 0, 150, 160, true, Tree.JUNGLE)
                    : new Surface(savanna, GRASS, DIRT, 35, 0, 0, 300, 310, false, Tree.ACACIA);
            case HOT_DESERT, HOT_STEPPE -> new Surface(savanna, GRASS, DIRT, 30, 0, 0, 260, 270, false, Tree.ACACIA);
            // Pinyon, juniper and pine on dry uplands.
            case COLD_DESERT, COLD_STEPPE -> t < 6 ? new Surface(taiga, GRASS, DIRT, 45, 10, 0, 200, 210, false)
                    : new Surface(savanna, GRASS, DIRT, 30, 4, 0, 260, 270, false);
            // Conifers on cooler, wetter Mediterranean mountains (the Sierra, the Cascades), oak below.
            case MEDITERRANEAN -> t < 12 ? new Surface(local.rain() > 900 ? oldTaiga : taiga, local.rain() > 900 && patch > 0.3 ? PODZOL : GRASS, DIRT, 80, 8, 0, 110, 120, true)
                    : new Surface(forest, GRASS, DIRT, 70, 1, 0, 140, 170, false);
            case SUBTROPICAL -> patch < -0.45 && bamboo(x, z) ? bambooForest(patch)
                    : t > 20 ? new Surface(sparseJungle, GRASS, DIRT, 80, 0, 0, 140, 160, true, Tree.JUNGLE)
                    : groves(patch, x, z, new Surface(forest, GRASS, DIRT, 85, 0, 1, 100, 130, false));
            case OCEANIC -> groves(patch, x, z, new Surface(forest, GRASS, DIRT, 82, t < 8 ? 3 : 0, 2, 90, 130, true));
            case SUBPOLAR_OCEANIC -> new Surface(taiga, GRASS, DIRT, 70, 10, 0, 110, 120, true);
            // Broadleaf in the warmer continental interiors, mixed with birch and spruce further north.
            case CONTINENTAL -> t > 7 ? groves(patch, x, z, new Surface(forest, GRASS, DIRT, 85, 0, 2, 90, 130, false))
                    : patch > 0.25 ? new Surface(birchForest, GRASS, DIRT, 82, 1, 8, 90, 130, false)
                    : patch < -0.25 ? new Surface(taiga, GRASS, DIRT, 82, 10, 0, 110, 120, true)
                    : new Surface(forest, GRASS, DIRT, 82, 5, 2, 90, 120, true);
            case SUBARCTIC -> t < -5 ? new Surface(snowyTaiga, GRASS, DIRT, 65, 10, 0, 90, 100, true)
                    : new Surface(patch > 0.45 && local.rain() > 500 ? oldTaiga : taiga, patch > 0.45 ? PODZOL : GRASS, DIRT, 75, 9, 1, 110, 120, true);
            // Stunted trees at the treeline.
            case TUNDRA, ICE -> new Surface(t < -5 ? snowyTaiga : grove, GRASS, DIRT, 30, 10, 0, 60, 70, false);
        };
    }

    // Data ---------------------------------------------------------------------------------------

    /**
     * Fetches the elevation tiles that cubes and every far level around a point read, several at a
     * time, so generation there never waits on the network. Failures are logged and left for the
     * generator's own retries.
     */
    @Override public void prepare(int x, int z) {
        record Tile(int zoom, int x, int y) {}
        List<Tile> wanted = new ArrayList<>();
        for (int zoom = FAR_ZOOM; zoom <= TerrariumTileSource.MAX_ZOOM; zoom++) {
            // Each zoom serves levels whose reach doubles as the zoom halves.
            double reach = 1100.0 * (1 << (TerrariumTileSource.MAX_ZOOM - zoom));
            int tilesAcross = 1 << zoom;
            int x0 = (int) Math.floor(grid.pixelX(x - reach, zoom) / 256), x1 = (int) Math.floor(grid.pixelX(x + reach, zoom) / 256);
            int y0 = (int) Math.floor(grid.pixelY(z - reach, zoom) / 256), y1 = (int) Math.floor(grid.pixelY(z + reach, zoom) / 256);
            for (int ty = Math.max(0, y0); ty <= Math.min(tilesAcross - 1, y1); ty++)
                for (int tx = x0; tx <= x1; tx++) wanted.add(new Tile(zoom, Math.floorMod(tx, tilesAcross), ty));
        }
        wanted.parallelStream().forEach(tile -> {
            try { tiles.tile(tile.zoom, tile.x, tile.y); }
            catch (RuntimeException failure) { EarthPlatformMod.LOGGER.warn("Could not prefetch elevation tile {}/{}/{}: {}", tile.zoom, tile.x, tile.y, failure.getMessage()); }
        });
        if (climate != null) {
            GeoPoint a = grid.geographic(x - 24000, z + 24000), b = grid.geographic(x + 24000, z - 24000);
            try { climate.prefetch(a.latitude(), a.longitude(), b.latitude(), b.longitude()); }
            catch (RuntimeException failure) { EarthPlatformMod.LOGGER.warn("Could not prefetch climate: {}", failure.getMessage()); }
        }
        if (cover == null) return;
        // Land cover: full detail near by, overviews further out, as the far levels read them.
        double[] reaches = {3500, 7000, 24000};
        for (int overview = 0; overview < reaches.length; overview++) {
            double reach = reaches[overview];
            GeoPoint a = grid.geographic(x - reach, z + reach), b = grid.geographic(x + reach, z - reach);
            try { cover.prefetch(a.latitude(), a.longitude(), b.latitude(), b.longitude(), overview); }
            catch (RuntimeException failure) { EarthPlatformMod.LOGGER.warn("Could not prefetch land cover: {}", failure.getMessage()); }
        }
    }
}
