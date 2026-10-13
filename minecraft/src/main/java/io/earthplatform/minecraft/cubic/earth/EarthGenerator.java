package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.GlobalElevation;
import io.earthplatform.geography.globe.ImagerySource;
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
 * rivers, whose water lies level at the surveyed surface with a bed that deepens away from the shore. The
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
    /** The coarse bathymetry the detail tiles' open sea is filled from. */
    private final GlobalElevation bathymetry;
    @Nullable private final WorldCoverSource cover;
    @Nullable private final ClimateSource climate;
    @Nullable private final ImagerySource imagery;
    @Nullable private final ImageryGround imageryGround;
    private final SimplexNoise relief, crags, wetBroad, wetLocal, warmth, warpX, warpZ, patches;
    private final Holder<Biome> ocean, deepOcean, coldOcean, deepColdOcean, frozenOcean, lukewarmOcean, deepLukewarmOcean, warmOcean,
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
     * @param imagery satellite imagery, used with land cover to match bare ground and plant cover to the place, or null
     */
    public EarthGenerator(long seed, Registry<Biome> biomes, EarthSettings settings, TerrariumTileSource tiles,
                          @Nullable WorldCoverSource cover, @Nullable ClimateSource climate, @Nullable ImagerySource imagery) {
        this(seed, biomes, settings, tiles, cover, climate, imagery, Noises.of(seed));
    }

    private EarthGenerator(long seed, Registry<Biome> biomes, EarthSettings settings, TerrariumTileSource tiles,
                           @Nullable WorldCoverSource cover, @Nullable ClimateSource climate, @Nullable ImagerySource imagery, Noises noises) {
        super(seed, biomes, noises.random);
        this.settings = settings;
        this.grid = new MercatorGrid(settings.metresPerBlock());
        this.tiles = tiles;
        this.cover = cover;
        this.climate = climate;
        this.imagery = cover == null ? null : imagery;
        this.imageryGround = this.imagery == null ? null : new ImageryGround(this.imagery, grid, seed);
        // Drawn from their own random, after the shared one, so caves stay as they were.
        RandomSource own = new LegacyRandomSource(seed ^ 0x434F564552L);
        warpX = new SimplexNoise(own); warpZ = new SimplexNoise(own); patches = new SimplexNoise(own);
        for (int zoom = FAR_ZOOM; zoom <= TerrariumTileSource.MAX_ZOOM; zoom++) elevation[zoom] = new GlobalElevation(grid, tiles, zoom);
        bathymetry = new GlobalElevation(grid, tiles, GlobalElevation.BATHYMETRY_ZOOM);
        SimplexNoise[] n = noises.noise;
        relief = n[0]; crags = n[1]; wetBroad = n[2]; wetLocal = n[3]; warmth = n[4];
        ocean = holder(biomes, Biomes.OCEAN); deepOcean = holder(biomes, Biomes.DEEP_OCEAN); coldOcean = holder(biomes, Biomes.COLD_OCEAN);
        deepColdOcean = holder(biomes, Biomes.DEEP_COLD_OCEAN); frozenOcean = holder(biomes, Biomes.FROZEN_OCEAN);
        lukewarmOcean = holder(biomes, Biomes.LUKEWARM_OCEAN); deepLukewarmOcean = holder(biomes, Biomes.DEEP_LUKEWARM_OCEAN);
        warmOcean = holder(biomes, Biomes.WARM_OCEAN);
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
                + (climate == null ? "" : ". Climate: " + ClimateSource.ATTRIBUTION)
                + (imagery == null ? "" : ". Imagery: " + ImagerySource.ATTRIBUTION);
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
            out[0] = seabed(x, z, metres, voxel);
            out[1] = SEA;
            return;
        }
        double steep = Math.min(2.5, Math.sqrt(sample[0] * sample[0] + sample[1] * sample[1]));
        if (cover != null && cover(x, z, voxel) == WorldCoverSource.WATER) {
            // Water joined through water to the open sea at sea level is the sea, though the survey under it reads far
            // above it, as where a coarse ocean grid fills a lagoon with its island's slopes; but water mapped on a
            // steep slope of real ground, as a cliff's shadow, is not.
            if (seaWater(x, z) && (steep <= 0.7 || coarse(dem, x + 0.5, z + 0.5, metres, Math.max(24, 3.0 * voxel)))) {
                out[0] = lagoonBed(x, z, metres, voxel);
                out[1] = SEA;
                return;
            }
            int surface = lakeSurface(dem, x, z, voxel);
            // Shadowed cliffs can map as water; water never lies on a steep slope well above its own level. Only
            // mountains cast such shadows: in flat country a steep rise under water is a seam between surveys, and
            // keeping it as land stood thin stone walls in rivers.
            boolean shadow = steep > 0.7 && SEA + 0.5 + metres - surface > 4 && relief(x, z) > MOUNTAINS;
            // Water by the coast is the sea, and so is water at sea level that joins it, as estuaries and lagoons do,
            // which otherwise stood a block above the sea around it wherever the survey read a little over 0 m.
            if (!shadow && (sample[2] > 0.02 && surface <= SEA || surface <= SEA + 2 && joinsOpenSea(x, z))) {
                out[0] = lagoonBed(x, z, metres, voxel);
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
            // A speck of land in a lagoon filled with coarse blocks, as a pier or a boat, takes the fill's height, tens
            // of metres up, and stood as a stone pillar out of the sea: it lies just above the water instead.
            if (y > SEA + 1 && speck(dem, x, z, metres, voxel)) y = SEA + 1;
            // Low ground slopes down to the water beside it rather than standing over it in a wall.
            if (y > SEA) y = bank(dem, x, z, y, voxel);
        }
        out[0] = y;
        out[1] = DRY;
    }

    /** Ring radii, in voxels, for how far a low bank is from its water. */
    private static final int[] BANK_RINGS = {1, 2, 3, 4, 6, 8, 11, 15};
    /** Rise of a low bank per block across, and the heights over its water up to which ground eases fully, and at all. */
    private static final double BANK_SLOPE = 0.5;
    private static final int LOW_BANK = 6, HIGH_BANK = 14;

    /**
     * A land column's height eased down toward the water just beside it. The elevation tiles blur a
     * low shore's few metres out to the water's edge, and the water is level, so low coasts, lagoons
     * and river banks stood over their water in sheer walls of four to eight blocks, as all round
     * Florida's Lake Worth Lagoon, where real shores are beaches and low banks. Ground up to
     * {@value #LOW_BANK} blocks over the water nearest it now climbs from the water's edge by one
     * block in two; higher ground eases less, and from {@value #HIGH_BANK} up, as a cliff, not at
     * all. Mountain country, where water lies in steep valleys, keeps its shores as they are.
     */
    private int bank(GlobalElevation dem, int x, int z, int y, int voxel) {
        WaterCell cell = waterCell(Math.floorDiv(x, WATER_CELL), Math.floorDiv(z, WATER_CELL));
        if (cell.relief > MOUNTAINS) return y;
        // The lowest water a bank here could lead down to: the sea, or this cell's lakes and rivers.
        int lowest = y <= SEA + HIGH_BANK ? SEA : cell.samples > 0 ? Mth.floor(SEA + 0.5 + cell.level) - BANK : DRY;
        if (lowest == DRY || y - lowest >= HIGH_BANK) return y;
        for (int r : BANK_RINGS) {
            double radius = (double) r * voxel;
            if (lowest + radius * BANK_SLOPE >= y) return y;
            // The first water found on the ring sets the level: reading every one's costs more than it changes.
            int level = DRY;
            for (int k = 0; k < 8 && level == DRY; k++) {
                int px = x + (int) Math.round(RING_X[k] * radius), pz = z + (int) Math.round(RING_Z[k] * radius);
                int code = cover(px, pz, voxel);
                if (code != WorldCoverSource.NO_DATA && code != WorldCoverSource.WATER) continue;
                int water = code == WorldCoverSource.NO_DATA || seaWater(px, pz) ? SEA : lakeSurface(dem, px, pz, voxel);
                if (water > SEA && water <= SEA + 2 && joinsOpenSea(px, pz)) water = SEA;
                if (water < y) level = water;
            }
            if (level == DRY) continue;
            int rise = y - level;
            double ease = Mth.clamp((double) (HIGH_BANK - rise) / (HIGH_BANK - LOW_BANK), 0, 1);
            int cap = level + Mth.floor(radius * BANK_SLOPE);
            return cap >= y ? y : y - (int) Math.round((y - cap) * ease);
        }
        return y;
    }

    /** Whether a dry column is a speck of land on {@link #coarse} ground with the sea on most sides. */
    private boolean speck(GlobalElevation dem, int x, int z, double metres, int voxel) {
        if (waterCell(Math.floorDiv(x, WATER_CELL), Math.floorDiv(z, WATER_CELL)).sea == NO_SEA) return false;
        double r = Math.max(16, 2.0 * voxel);
        int sea = 0;
        for (int k = 0; k < 8; k++) {
            double px = x + RING_X[k] * r, pz = z + RING_Z[k] * r;
            int code = cover(px, pz, voxel);
            if (code == WorldCoverSource.NO_DATA || code == WorldCoverSource.WATER && seaWater(px, pz)) sea++;
        }
        return sea >= 5 && coarse(dem, x + 0.5, z + 0.5, metres, Math.max(24, 3.0 * voxel));
    }

    /**
     * Top seabed block of a sea column. Where the detail tiles hold no depths they read the sea
     * as exactly 0 m, filled from the coarse bathymetry, or as a few tenths of a metre either side
     * of it, which left those spots a block deep: estuaries and coasts were a patchwork of shallows
     * and deeps a few survey pixels across. The bed takes the deeper of the detail and the coarse
     * bathymetry, which is smooth, never above 0 m, and close to the detail wherever the detail
     * holds real depths, as in the North Sea. Where both are shallow, as in estuaries the coarse
     * bathymetry counts as land, it shelves down from the shore as a lake's bed does.
     */
    private int seabed(int x, int z, double metres, int voxel) {
        int bed = Math.min(SEA - 1, Mth.floor(SEA + 0.5 + Math.min(metres, Math.min(0, bathymetry.metres(x + 0.5, z + 0.5)))));
        if (bed > SEA - 8 && cover != null) bed = Math.min(bed, SEA - lakeDepth(x, z, voxel));
        return bed;
    }

    /** How deep sea water mapped at or above sea level may lie, as a lagoon or an estuary behind the coast. */
    private static final int LAGOON_DEPTH = 8;

    /**
     * Bed of sea water the detail tiles map at or above sea level, as a lagoon or estuary behind the
     * coast. The coarse bathymetry there reads the sea outside, or bad pixels, and put Lake Worth
     * Lagoon a dozen blocks deep where it is two or three metres; the bed shelves from the shore as
     * a lake's does, never deeper than {@value #LAGOON_DEPTH} blocks or the bathymetry.
     */
    private int lagoonBed(int x, int z, double metres, int voxel) {
        int bed = seabed(x, z, metres, voxel);
        return metres < 0 ? bed : Math.max(bed, SEA - Math.min(LAGOON_DEPTH, lakeDepth(x, z, voxel)));
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
     * Top water block of a lake or river at a column. Real lakes and rivers are level or nearly,
     * but the elevation tiles' water is not: radar speckle, patches from different surveys and
     * banks blurred into the water put it metres up or down from one spot to the next, and each
     * zoom differently. Taken spot by spot, water stepped up and down in terraces and stood at
     * another height in every far level, leaving dark slits where the levels met. So the height
     * comes from one place for cubes and every far level alike: the {@link #reachLevel} levels of
     * the four cells around, blended by distance and by how much water each holds. It never stands
     * more than {@value #BANK} blocks above the lowest surveyed height close by, which the shore
     * climbs to meet, and in steep country, where one cell holds lakes and streams at many heights,
     * never more than {@value #DIP} below it either.
     */
    private int lakeSurface(GlobalElevation dem, double x, double z, int voxel) {
        double r = Math.max(8, voxel);
        double lowest = dem.metres(x + 0.5, z + 0.5);
        lowest = Math.min(lowest, Math.min(dem.metres(x + 0.5 + r, z + 0.5), dem.metres(x + 0.5 - r, z + 0.5)));
        lowest = Math.min(lowest, Math.min(dem.metres(x + 0.5, z + 0.5 + r), dem.metres(x + 0.5, z + 0.5 - r)));
        int local = Mth.floor(SEA + 0.5 + lowest);
        double fx = x / WATER_CELL - 0.5, fz = z / WATER_CELL - 0.5;
        int ix = Mth.floor(fx), iz = Mth.floor(fz);
        double tx = fx - ix, tz = fz - iz, sum = 0, weight = 0, relief = 0;
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
            WaterCell cell = waterCell(ix + i, iz + j);
            if (cell.samples == 0) continue;
            double w = ((i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - tz : tz) + 1e-6) * cell.samples;
            sum += reachLevel(ix + i, iz + j) * w;
            weight += w;
            relief = Math.max(relief, cell.relief);
        }
        if (weight == 0) return local;
        int surface = Mth.floor(SEA + 0.5 + sum / weight);
        if (relief > MOUNTAINS) surface = Math.max(surface, local - DIP);
        return Math.min(surface, local + BANK);
    }

    /** The ground's height range over the water cells around a column, as {@link #lakeSurface} weighs them. */
    private double relief(double x, double z) {
        int ix = Mth.floor(x / WATER_CELL - 0.5), iz = Mth.floor(z / WATER_CELL - 0.5);
        double relief = 0;
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) relief = Math.max(relief, waterCell(ix + i, iz + j).relief);
        return relief;
    }

    /** Water cells' size in blocks, the elevation zoom and land cover overview (both about 40 m) they read, and their samples across. */
    private static final int WATER_CELL = 1024, WATER_ZOOM = 12, WATER_OVERVIEW = 2, WATER_SAMPLES = 16;
    /** How far water may stand above the lowest surveyed height near it, and, in steep country, below it. */
    private static final int BANK = 4, DIP = 2;
    /** Height range (metres, middle 80% of a cell's ground) above which a cell counts as steep country. */
    private static final double MOUNTAINS = 100;

    /**
     * A cell's water level in metres: the lowest quarter of its surveyed water heights, under the
     * banks blurred into it; how many of its samples are water; the range of its ground; and which
     * of its samples are the sea ({@link #seaSamples}). The level leaves out the sea's samples that
     * read above sea level, which are coarse fill rather than any lake's or river's height.
     */
    private record WaterCell(double level, int samples, double relief, long[] sea) {}
    private final java.util.concurrent.ConcurrentHashMap<Long, WaterCell> waterCells = new java.util.concurrent.ConcurrentHashMap<>();

    private WaterCell waterCell(int cx, int cz) {
        long key = (long) cx << 32 | cz & 0xFFFFFFFFL;
        WaterCell known = waterCells.get(key);
        if (known != null) return known;
        Survey survey = survey(cx, cz);
        long[] sea = seaSamples(cx, cz, survey);
        double[] water = new double[survey.metres.length];
        int count = 0;
        for (int k = 0; k < water.length; k++) {
            boolean raisedSea = (sea[k >> 6] >>> (k & 63) & 1) != 0 && survey.metres[k] > SEA_METRES;
            if ((survey.kind[k] == WET || survey.kind[k] == FLAT) && !raisedSea) water[count++] = survey.metres[k];
        }
        WaterCell cell;
        if (count < 4) cell = new WaterCell(0, 0, survey.relief, sea);
        else {
            java.util.Arrays.sort(water, 0, count);
            cell = new WaterCell(water[count / 4], count, survey.relief, sea);
        }
        if (waterCells.size() > 1 << 20) waterCells.clear();
        waterCells.putIfAbsent(key, cell);
        return cell;
    }

    /** Cells each way whose water a reach's level is taken over, and the most two neighbouring water samples of one reach differ by. */
    private static final int REACH = 3;
    private static final double DROP = 8;
    /** Reach levels as far as they have been found. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Double> reaches = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The water level of a cell's reach, in metres: the lowest quarter of the surveyed heights of
     * the water joined to the cell's own through wide water, within {@value #REACH} cells. A big
     * river's or lake's survey wanders metres up and down from one cell to the next, as over the
     * Rio Negro; taken cell by cell, its level climbed and fell a block every few hundred blocks,
     * in rings of steps across the water. Over the reach it is one level. Water joined only
     * through narrow water, as a creek or a canal, keeps its own level, and so does water past a
     * drop of more than {@value #DROP} metres between neighbouring samples, as at a dam or falls.
     * A cell's own samples join the water next to them however narrow they are, so a sliver of a
     * river at a cell's edge, which reads the banks blurred into it, takes the river's level.
     */
    private double reachLevel(int cx, int cz) {
        long key = (long) cx << 32 | cz & 0xFFFFFFFFL;
        Double known = reaches.get(key);
        if (known != null) return known;
        int span = (2 * REACH + 1) * WATER_SAMPLES;
        float[] water = new float[span * span];
        it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue queue = new it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue();
        boolean[] joined = new boolean[span * span];
        for (int wj = 0; wj <= 2 * REACH; wj++) for (int wi = 0; wi <= 2 * REACH; wi++) {
            Survey survey = survey(cx - REACH + wi, cz - REACH + wj);
            long[] sea = waterCell(cx - REACH + wi, cz - REACH + wj).sea;
            for (int k = 0; k < survey.metres.length; k++) {
                boolean raisedSea = (sea[k >> 6] >>> (k & 63) & 1) != 0 && survey.metres[k] > SEA_METRES;
                int at = (wj * WATER_SAMPLES + k / WATER_SAMPLES) * span + wi * WATER_SAMPLES + k % WATER_SAMPLES;
                water[at] = (survey.kind[k] == WET || survey.kind[k] == FLAT) && !raisedSea ? survey.metres[k] : Float.NaN;
                if (wi == REACH && wj == REACH && !Float.isNaN(water[at])) {
                    joined[at] = true;
                    queue.enqueue(at);
                }
            }
        }
        double[] heights = new double[span * span];
        int count = 0, own = queue.size();
        while (!queue.isEmpty()) {
            int at = queue.dequeueInt(), i = at % span, j = at / span;
            heights[count++] = water[at];
            if (count > own && !wideWater(water, span, i, j)) continue;
            for (int d = 0; d < 8; d += 2) {
                int ni = i + (int) RING_X[d], nj = j + (int) RING_Z[d], n = nj * span + ni;
                if (ni < 0 || nj < 0 || ni >= span || nj >= span || joined[n] || Float.isNaN(water[n])) continue;
                if (Math.abs(water[n] - water[at]) > DROP) continue;
                joined[n] = true;
                queue.enqueue(n);
            }
        }
        double level = waterCell(cx, cz).level;
        if (count >= 4) {
            java.util.Arrays.sort(heights, 0, count);
            level = heights[count / 4];
        }
        if (reaches.size() > 1 << 20) reaches.clear();
        reaches.putIfAbsent(key, level);
        return level;
    }

    /** Whether a water sample has water all round it, so it is no river's or creek's narrow water. */
    private static boolean wideWater(float[] water, int span, int i, int j) {
        if (i == 0 || j == 0 || i == span - 1 || j == span - 1) return false;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) if (Float.isNaN(water[(j + dj) * span + i + di])) return false;
        return true;
    }

    /**
     * A water cell's samples as surveyed: each one's height, and whether it is land, open sea,
     * water on real ground, or water on {@link #coarse} ground or at sea level, which may be the
     * sea; and the range of the cell's ground (middle 80%).
     */
    private record Survey(float[] metres, byte[] kind, double relief) {}
    private static final byte DRY_LAND = 0, WET = 1, FLAT = 2, OPEN = 3;
    /** Surveys kept for neighbouring cells' floods; only a few thousand are needed at once. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Survey> surveys = new java.util.concurrent.ConcurrentHashMap<>();
    /** Heights a water sample may read and still lie at sea level. */
    private static final double SEA_METRES = 0.5;

    private Survey survey(int cx, int cz) {
        long key = (long) cx << 32 | cz & 0xFFFFFFFFL;
        Survey known = surveys.get(key);
        if (known != null) return known;
        GlobalElevation dem = elevation[WATER_ZOOM];
        float[] metres = new float[WATER_SAMPLES * WATER_SAMPLES];
        double[] ground = new double[metres.length];
        byte[] kind = new byte[metres.length];
        double spacing = (double) WATER_CELL / WATER_SAMPLES;
        for (int j = 0; j < WATER_SAMPLES; j++) for (int i = 0; i < WATER_SAMPLES; i++) {
            double x = cx * (double) WATER_CELL + (i + 0.5) * spacing, z = cz * (double) WATER_CELL + (j + 0.5) * spacing;
            double m = dem.metres(x, z);
            int k = j * WATER_SAMPLES + i;
            metres[k] = (float) m;
            ground[k] = m;
            GeoPoint point = grid.geographic(x, z);
            int code = cover.code(point.latitude(), point.longitude(), WATER_OVERVIEW);
            kind[k] = code == WorldCoverSource.NO_DATA ? OPEN : code != WorldCoverSource.WATER ? DRY_LAND
                    : m <= SEA_METRES || coarse(dem, x, z, m, 48) ? FLAT : WET;
        }
        java.util.Arrays.sort(ground);
        Survey survey = new Survey(metres, kind, ground[ground.length * 9 / 10] - ground[ground.length / 10]);
        if (surveys.size() > 1 << 12) surveys.clear();
        surveys.putIfAbsent(key, survey);
        return survey;
    }

    /** No samples of a cell are the sea. */
    private static final long[] NO_SEA = new long[WATER_SAMPLES * WATER_SAMPLES / 64];

    /**
     * Which of a cell's samples are the sea: water reached through water from the open sea or from
     * water at sea level that joins it, within the cell and the cells around it. Coarse ocean grids
     * fill lagoons and small islands' bays with the island's slopes carried out over the water, tens
     * of metres up, which otherwise stood as lakes in walled boxes above the sea beside them. The
     * flood crosses water on {@link #coarse} ground or at sea level, and water on any ground where it
     * is wide, as a lagoon is; a river mouth narrower than that is sea only where it meets the sea,
     * so the river still climbs inland. Land between samples, as a reef islet or a dam, stops it.
     * Within a cell or two of the sea, only cells holding raised water need it.
     */
    private long[] seaSamples(int cx, int cz, Survey centre) {
        boolean raised = false;
        for (int k = 0; k < centre.kind.length && !raised; k++) raised = centre.kind[k] == WET || centre.kind[k] == FLAT && centre.metres[k] > SEA_METRES;
        if (!raised) return NO_SEA;
        int span = 3 * WATER_SAMPLES;
        Survey[] window = new Survey[9];
        for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) window[j * 3 + i] = i == 1 && j == 1 ? centre : survey(cx + i - 1, cz + j - 1);
        double spacing = (double) WATER_CELL / WATER_SAMPLES, ox = (cx - 1) * (double) WATER_CELL, oz = (cz - 1) * (double) WATER_CELL;
        byte[] kinds = new byte[span * span], state = new byte[span * span];
        it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue queue = new it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue();
        for (int j = 0; j < span; j++) for (int i = 0; i < span; i++) {
            Survey survey = window[j / WATER_SAMPLES * 3 + i / WATER_SAMPLES];
            int k = j % WATER_SAMPLES * WATER_SAMPLES + i % WATER_SAMPLES;
            byte kind = kinds[j * span + i] = survey.kind[k];
            if (kind == DRY_LAND) continue;
            state[j * span + i] = 1;
            if (kind == OPEN || kind == FLAT && survey.metres[k] <= SEA_METRES
                    && joinsOpenSea(Mth.floor(ox + (i + 0.5) * spacing), Mth.floor(oz + (j + 0.5) * spacing))) {
                state[j * span + i] = 2;
                queue.enqueue(j * span + i);
            }
        }
        if (queue.isEmpty()) return NO_SEA;
        while (!queue.isEmpty()) {
            int at = queue.dequeueInt(), i = at % span, j = at / span;
            for (int d = 0; d < 8; d += 2) {
                int ni = i + (int) RING_X[d], nj = j + (int) RING_Z[d], n = nj * span + ni;
                if (ni < 0 || nj < 0 || ni >= span || nj >= span || state[n] != 1) continue;
                if (!waterBetween(ox + (i + 0.5) * spacing, oz + (j + 0.5) * spacing, ox + (ni + 0.5) * spacing, oz + (nj + 0.5) * spacing)) continue;
                state[n] = 2;
                if (kinds[n] != WET || wide(kinds, span, ni, nj)) queue.enqueue(n);
            }
        }
        long[] sea = new long[NO_SEA.length];
        boolean any = false;
        for (int j = 0; j < WATER_SAMPLES; j++) for (int i = 0; i < WATER_SAMPLES; i++) {
            if (state[(j + WATER_SAMPLES) * span + i + WATER_SAMPLES] != 2) continue;
            int k = j * WATER_SAMPLES + i;
            sea[k >> 6] |= 1L << (k & 63);
            any = true;
        }
        return any ? sea : NO_SEA;
    }

    /** Whether a sample's water spans all eight samples around it, so it is no river or creek. */
    private static boolean wide(byte[] kinds, int span, int i, int j) {
        if (i == 0 || j == 0 || i == span - 1 || j == span - 1) return false;
        for (int d = 0; d < 8; d++) if (kinds[(j + Math.round((float) RING_Z[d])) * span + i + Math.round((float) RING_X[d])] == DRY_LAND) return false;
        return true;
    }

    /** Whether the land cover between two neighbouring water samples is all water, so no reef islet or dam parts them. */
    private boolean waterBetween(double x0, double z0, double x1, double z1) {
        for (int step = 1; step < 4; step++) {
            GeoPoint point = grid.geographic(x0 + (x1 - x0) * step / 4, z0 + (z1 - z0) * step / 4);
            int code = cover.code(point.latitude(), point.longitude(), WATER_OVERVIEW);
            if (code != WorldCoverSource.WATER && code != WorldCoverSource.NO_DATA) return false;
        }
        return true;
    }

    /**
     * Whether a column's water joins one of the four water samples around it that is the sea
     * ({@link #seaSamples}), so a lake behind a beach beside a lagoon stays a lake.
     */
    private boolean seaWater(double x, double z) {
        double spacing = (double) WATER_CELL / WATER_SAMPLES;
        int ix = Mth.floor(x / spacing - 0.5), iz = Mth.floor(z / spacing - 0.5);
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
            int sx = ix + i, sz = iz + j;
            long[] sea = waterCell(Math.floorDiv(sx, WATER_SAMPLES), Math.floorDiv(sz, WATER_SAMPLES)).sea;
            if (sea == NO_SEA) continue;
            int k = Math.floorMod(sz, WATER_SAMPLES) * WATER_SAMPLES + Math.floorMod(sx, WATER_SAMPLES);
            if ((sea[k >> 6] >>> (k & 63) & 1) != 0 && waterBetween(x + 0.5, z + 0.5, (sx + 0.5) * spacing, (sz + 0.5) * spacing)) return true;
        }
        return false;
    }

    /**
     * Whether the survey at a point is flat blocks of coarse data, as an ocean grid's, rather than
     * real ground: level there, or on the step down from a level block within {@code reach}. The
     * smooth sampling overshoots such steps, most where block corners meet, so the point may stand
     * up to a quarter of the step above the block. Real ground, even the foot of a cliff, is never
     * level to a hundredth of a metre three blocks apart.
     */
    private static boolean coarse(GlobalElevation dem, double x, double z, double metres, double reach) {
        if (level(dem, x, z, metres)) return true;
        double[] ring = new double[8];
        double low = metres;
        for (int k = 0; k < 8; k++) low = Math.min(low, ring[k] = dem.metres(x + RING_X[k] * reach, z + RING_Z[k] * reach));
        double overshoot = Math.max(1, (metres - low) / 4);
        for (int k = 0; k < 8; k++) {
            if (ring[k] >= metres - overshoot && level(dem, x + RING_X[k] * reach, z + RING_Z[k] * reach, ring[k])) return true;
        }
        return false;
    }

    private static boolean level(GlobalElevation dem, double x, double z, double metres) {
        return Math.abs(dem.metres(x + 3, z) - metres) < 0.01 && Math.abs(dem.metres(x, z + 3) - metres) < 0.01;
    }

    /** Ring radii, in blocks, for judging how far a lake column is from its shore. */
    private static final int[] RINGS = {5, 14, 36, 90};

    /** Water depth: shallow by the shore, deepening by about one block in six away from it. The open sea is not shore. */
    private int lakeDepth(int x, int z, int voxel) {
        double scale = Math.max(1, voxel / 6.0), inner = 0, distance = -1;
        for (int radius : RINGS) {
            double r = radius * scale;
            int land = 0;
            for (int k = 0; k < 8; k++) {
                int code = cover(x + RING_X[k] * r, z + RING_Z[k] * r, voxel);
                if (code != WorldCoverSource.WATER && code != WorldCoverSource.NO_DATA) land++;
            }
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
                if (cover(px, pz, voxel) == WorldCoverSource.WATER && !seaWater(px, pz)) best = Math.max(best, lakeSurface(dem, px, pz, voxel));
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

    private Surface surface(int x, int z, int y, int water, int slope, int voxel) { return surface(x, z, y, water, slope, voxel, imageryGround); }

    /** Imagery ground rules for this world's grid and seed, reading from any imagery source; for {@link ImageryCalibration}. */
    ImageryGround imageryGround(ImagerySource source) { return new ImageryGround(source, grid, seed); }

    /** A column's surface with the given imagery, or none, having the last word; for {@link ImageryCalibration}. */
    Surface surface(int x, int z, int y, int water, int slope, int voxel, @Nullable ImageryGround imageryGround) {
        Local local = cover == null ? null : local(x, z, y);
        double t = local == null ? temperature(x, z, y) : local.temperature();
        if (water != DRY && water != SEA && cover != null) return lake(x, z, y, water, t);
        if (water != DRY) {
            int depth = water - y;
            // Warm seas are turquoise only over reefs and shallows; deeper water is dark blue, as from above.
            Holder<Biome> b = t < -2 ? frozenOcean : t < 6 ? (depth > 40 ? deepColdOcean : coldOcean)
                    : t > 15 ? (depth > (t > 21 ? 15 : 40) ? deepLukewarmOcean : t > 21 ? warmOcean : lukewarmOcean)
                    : depth > 40 ? deepOcean : ocean;
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
            boolean built = code == WorldCoverSource.BUILT_UP || code == WorldCoverSource.BARE && inTown(x, z, voxel);
            if (built) code = wild(x, z, voxel);
            Surface covered = code == WorldCoverSource.NO_DATA ? null : covered(code, x, z, y, local, steep, roll, slope);
            if (covered == null) {
                code = typical(local.zone());
                covered = covered(code, x, z, y, local, steep, roll, slope);
            }
            // Imagery has the last word on bare ground and how thick plants grow, except where towns and
            // roads were taken out: there it shows buildings and asphalt, not the wild land put in their place.
            return imageryGround == null || built ? covered
                    : imageryGround.apply(covered, code, x, z, local.zone(), steep, voxel, (mix(x, 0x494D47L, z) >>> 11) * 0x1.0p-53);
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
        if (imagery != null) {
            // Imagery: full detail near by, coarser zooms further out, as the far levels read them.
            double[] reaches = {3500, 7000, 14000, 24000};
            for (int k = 0; k < reaches.length; k++) {
                double reach = reaches[k];
                try { imagery.prefetch(grid, x - reach, z - reach, x + reach, z + reach, ImagerySource.MAX_ZOOM - k); }
                catch (RuntimeException failure) { EarthPlatformMod.LOGGER.warn("Could not prefetch imagery: {}", failure.getMessage()); }
            }
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
