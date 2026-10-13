package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.BareEarth;
import io.earthplatform.geography.globe.GlobalElevation;
import io.earthplatform.geography.globe.ImagerySource;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.RiverMap;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.gen.Strata;
import io.earthplatform.minecraft.cubic.gen.Towers;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
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
            SANDSTONE = Blocks.SANDSTONE.defaultBlockState(), PACKED_ICE = Blocks.PACKED_ICE.defaultBlockState(), BLUE_ICE = Blocks.BLUE_ICE.defaultBlockState(),
            CLAY = Blocks.CLAY.defaultBlockState(), MOSS = Blocks.MOSS_BLOCK.defaultBlockState(), PODZOL = Blocks.PODZOL.defaultBlockState(),
            ANDESITE = Blocks.ANDESITE.defaultBlockState(), RED_SAND = Blocks.RED_SAND.defaultBlockState(),
            TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState(), RED_SANDSTONE = Blocks.RED_SANDSTONE.defaultBlockState(),
            CALCITE = Blocks.CALCITE.defaultBlockState(), POWDER_SNOW = Blocks.POWDER_SNOW.defaultBlockState(),
            ORANGE_TERRACOTTA = Blocks.ORANGE_TERRACOTTA.defaultBlockState(), DIORITE = Blocks.DIORITE.defaultBlockState(),
            POOL = Blocks.WATER.defaultBlockState(), BASALT = Blocks.SMOOTH_BASALT.defaultBlockState(), BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState(), TUFF = Blocks.TUFF.defaultBlockState(),
            RED_TERRACOTTA = Blocks.RED_TERRACOTTA.defaultBlockState(), LIGHT_GRAY_TERRACOTTA = Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState();
    /** Badlands rock colours, plain and orange the most common. */
    private static final BlockState[] BANDS = {TERRACOTTA, Blocks.ORANGE_TERRACOTTA.defaultBlockState(), Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
            Blocks.BROWN_TERRACOTTA.defaultBlockState(), Blocks.RED_TERRACOTTA.defaultBlockState(), Blocks.WHITE_TERRACOTTA.defaultBlockState(),
            Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState()};

    private final EarthSettings settings;
    private final MercatorGrid grid;
    private final TerrariumTileSource tiles;
    /** The tiles every height is read from: with {@link BareEarth}, the ground under towns in place of their roofs. */
    private final GlobalElevation.Tiles ground;
    private final GlobalElevation[] elevation = new GlobalElevation[TerrariumTileSource.MAX_ZOOM + 1];
    /** The coarse bathymetry the detail tiles' open sea is filled from. */
    private final GlobalElevation bathymetry;
    /** Rock layers of layered sedimentary country, such as the Grand Canyon's. */
    private final Strata strata;
    /** Tower karst and pillar forests, steeper than the elevation holds them. */
    private final Towers towers;
    @Nullable private final WorldCoverSource cover;
    @Nullable private final ClimateSource climate;
    @Nullable private final ImagerySource imagery;
    @Nullable private final ImageryGround imageryGround;
    /** Narrow rivers the land cover misses, or null in worlds made before them. */
    @Nullable private final RiverMap rivers;
    private final SimplexNoise relief, crags, wetBroad, wetLocal, warmth, warpX, warpZ, patches;
    private final Holder<Biome> ocean, deepOcean, coldOcean, deepColdOcean, frozenOcean, lukewarmOcean, deepLukewarmOcean, warmOcean,
            beach, snowyBeach, stonyShore, snowySlopes, frozenPeaks, stonyPeaks, meadow, taiga, oldTaiga, forest,
            plains, jungle, desert, savanna, river, frozenRiver, swamp, mangroveSwamp, snowyPlains, birchForest,
            badlands, sparseJungle, windsweptSavanna, snowyTaiga, grove, darkForest, flowerForest, cherryGrove, bambooJungle, sunflowerPlains, windsweptHills, glacialLake;

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
        this(seed, biomes, settings, tiles, cover, climate, imagery, null);
    }

    /** @param rivers narrow rivers, used with land cover to carve the channels it misses, or null for a world made before them */
    public EarthGenerator(long seed, Registry<Biome> biomes, EarthSettings settings, TerrariumTileSource tiles,
                          @Nullable WorldCoverSource cover, @Nullable ClimateSource climate, @Nullable ImagerySource imagery, @Nullable RiverMap rivers) {
        this(seed, biomes, settings, tiles, cover, climate, imagery, rivers, false);
    }

    /** @param bareEarth whether towns' ground is brought down to the land under their roofs ({@link BareEarth}); needs land cover */
    public EarthGenerator(long seed, Registry<Biome> biomes, EarthSettings settings, TerrariumTileSource tiles,
                          @Nullable WorldCoverSource cover, @Nullable ClimateSource climate, @Nullable ImagerySource imagery, @Nullable RiverMap rivers,
                          boolean bareEarth) {
        this(seed, biomes, settings, tiles, cover, climate, imagery, rivers, bareEarth, Noises.of(seed));
    }

    private EarthGenerator(long seed, Registry<Biome> biomes, EarthSettings settings, TerrariumTileSource tiles,
                           @Nullable WorldCoverSource cover, @Nullable ClimateSource climate, @Nullable ImagerySource imagery,
                           @Nullable RiverMap rivers, boolean bareEarth, Noises noises) {
        super(seed, biomes, noises.random);
        this.settings = settings;
        this.grid = new MercatorGrid(settings.metresPerBlock());
        this.tiles = tiles;
        this.ground = bareEarth && cover != null ? new BareEarth(tiles, cover) : tiles::tile;
        this.cover = cover;
        this.climate = climate;
        this.imagery = cover == null ? null : imagery;
        this.imageryGround = this.imagery == null ? null : new ImageryGround(this.imagery, grid, seed);
        this.rivers = cover == null ? null : rivers;
        // Drawn from their own random, after the shared one, so caves stay as they were.
        RandomSource own = new LegacyRandomSource(seed ^ 0x434F564552L);
        warpX = new SimplexNoise(own); warpZ = new SimplexNoise(own); patches = new SimplexNoise(own);
        for (int zoom = COARSEST_ZOOM; zoom <= TerrariumTileSource.MAX_ZOOM; zoom++) elevation[zoom] = new GlobalElevation(grid, ground, zoom);
        bathymetry = new GlobalElevation(grid, ground, GlobalElevation.BATHYMETRY_ZOOM);
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
        snowyTaiga = holder(biomes, Biomes.SNOWY_TAIGA); grove = holder(biomes, Biomes.GROVE); windsweptHills = holder(biomes, Biomes.WINDSWEPT_HILLS);
        darkForest = holder(biomes, Biomes.DARK_FOREST); flowerForest = holder(biomes, Biomes.FLOWER_FOREST);
        cherryGrove = holder(biomes, Biomes.CHERRY_GROVE); bambooJungle = holder(biomes, Biomes.BAMBOO_JUNGLE);
        sunflowerPlains = holder(biomes, Biomes.SUNFLOWER_PLAINS);
        glacialLake = holder(biomes, ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("earthplatform", "glacial_lake")));
        strata = new Strata(seed, new Strata.Globe() {
            @Override public double metres(double x, double z) { return elevation[FAR_ZOOM].metres(x, z); }
            @Override public GeoPoint geographic(double x, double z) { return grid.geographic(x, z); }
            @Override public double metresPerBlock(double z) { return grid.groundMetresPerBlock(z); }
        }, badlands);
        towers = new Towers(seed, new Towers.Globe() {
            @Override public double metres(double x, double z) { return elevation[TOWER_ZOOM].metres(x, z); }
            @Override public double metresPerBlock(double z) { return grid.groundMetresPerBlock(z); }
        });
    }

    @Override protected Strata.Layers layers(int x, int z) { return strata.layers(x, z, SEA + 0.5); }

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
                + (imagery == null ? "" : ". Imagery: " + ImagerySource.ATTRIBUTION)
                + (cover == null ? "" : ". " + Ecoregions.ATTRIBUTION)
                + (rivers == null ? "" : ". Rivers: " + io.earthplatform.geography.globe.SurfaceWaterSource.ATTRIBUTION)
                + (climate == null ? "" : ". Climate: " + ClimateSource.ATTRIBUTION);
    }

    // Height -------------------------------------------------------------------------------------

    /** Zoom of the elevation the far terrain's surveys (strata, salt pans) read. */
    private static final int FAR_ZOOM = 11;
    /** Zoom the towers find hills in, near and far alike: pixels about 20 metres across. */
    private static final int TOWER_ZOOM = 13;
    /** Zoom of the coarsest elevation the far levels read. */
    private static final int COARSEST_ZOOM = 9;

    /**
     * Source zoom for a far level: about one source pixel per voxel, by the world's own scale. A
     * world's blocks are metres at its home, so the farther a home is from the equator, the more
     * pixels a block spans: a Svalbard home would read sixteen times the tiles a Yosemite one
     * does at the same zoom, for detail the sources don't have.
     */
    private int zoom(int level) {
        double zoom = Math.log(grid.worldWidthBlocks() / (256.0 * (1 << level))) / Math.log(2);
        return Math.clamp(Math.round(zoom), COARSEST_ZOOM, TerrariumTileSource.MAX_ZOOM);
    }

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
        surveyed(dem, x, z, metres, sample, voxel, out);
    }

    private void surveyed(GlobalElevation dem, int x, int z, double metres, double[] sample, int voxel, int[] out) {
        if (sample[2] > 0.5 || offshore(x, z, metres, voxel)) {
            // Water the land cover maps behind the coast, and the detail tiles as land, is a lagoon or bay however
            // the detail reads its surface, a little over sea level or a little under: read either way, half of Lake
            // Worth Lagoon was eight blocks deep and half over twenty, parted by a hard edge.
            boolean lagoon = sample[2] <= 0.5 && cover(x, z, voxel) == WorldCoverSource.WATER;
            out[0] = lagoon ? lagoonBed(x, z, metres, voxel) : seabed(x, z, metres, voxel);
            out[1] = SEA;
            return;
        }
        double steep = Math.min(2.5, Math.sqrt(sample[0] * sample[0] + sample[1] * sample[1]));
        CoverMemo memo = cover == null ? null : coverMemo.get();
        if (voxel >= BRIDGE_VOXEL && cover != null && cover(memo, x, z, voxel) == WorldCoverSource.BUILT_UP) {
            int surface = bridged(dem, memo, x, z, voxel);
            if (surface != DRY) {
                out[0] = surface - lakeDepth(x, z, voxel);
                out[1] = surface;
                return;
            }
        }
        if (cover != null && cover(memo, x, z, voxel) == WorldCoverSource.WATER) {
            // Water joined through water to the open sea at sea level is the sea, though the survey under it reads far
            // above it, as where a coarse ocean grid fills a lagoon with its island's slopes; but water mapped on a
            // steep slope of real ground, as a cliff's shadow, is not.
            if (seaWater(x, z) && (steep <= 0.7 || coarse(dem, x + 0.5, z + 0.5, metres, Math.max(24, 3.0 * voxel)))) {
                out[0] = lagoonBed(x, z, metres, voxel);
                out[1] = SEA;
                return;
            }
            int surface = rivers != null && rawCover(memo, x, z, voxel) == RIVER ? riverSurface(x, z) : lakeSurface(dem, x, z, voxel);
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
                // Over drowned ground, as a reservoir newer than the survey, the bed is the survey's, far below.
                int bed = surface - lakeDepth(x, z, voxel), surveyedBed = Mth.floor(SEA + 0.5 + metres);
                out[0] = surface > surveyedBed + BANK ? Math.min(bed, surveyedBed) : bed;
                out[1] = surface;
                return;
            }
        }
        metres = towers.shape(x, z, metres, voxel);
        double amplitude = Mth.clamp((metres - 0.5) / 4, 0, 1) * (0.6 + 1.8 * steep), detail = 0;
        if (voxel <= 8) detail += fbm(relief, x / 27.0, z / 27.0, 3) * amplitude;
        if (voxel <= 2) detail += (0.5 - Math.abs(crags.getValue(x / 9.0, z / 9.0))) * amplitude * 0.6 * Math.min(1, steep);
        // Layered rock steps into ledges and cliffs, with little noise to break its level beds.
        Strata.Layers rock = strata.layers(x, z, SEA + 0.5);
        if (rock != null) { detail *= rock.calm(metres); metres = rock.terrace(metres); }
        int y = Mth.floor(SEA + 0.5 + metres + detail);
        // Ground beside the sea never dips under its level, so the sea stays where it is.
        if (sample[2] > 0.02 && y < SEA) y = SEA;
        // Nor does ground beside a lake or river, unless it lies well below it, as under a dam.
        if (cover != null) {
            int shore = shore(dem, memo, x, z, voxel);
            // In layered rock, terraces and the rock's noise may cut further below it, as along the Colorado.
            if (shore != DRY && y < shore && y >= shore - (rock != null ? 12 : 4)) y = shore;
            // A speck of land in a lagoon filled with coarse blocks, as a pier or a boat, takes the fill's height, tens
            // of metres up, and stood as a stone pillar out of the sea: it lies just above the water instead. Karst
            // towers out of the sea, as in Ha Long Bay, are real pillars and stay.
            if (y > SEA + 1 && !towers.covers(x, z) && speck(dem, x, z, metres, voxel)) y = SEA + 1;
            // Low ground slopes down to the water beside it rather than standing over it in a wall.
            if (y > SEA) y = bank(dem, x, z, y, voxel);
        }
        out[0] = y;
        out[1] = DRY;
    }

    /** Narrowest far voxel that reads a bridge's deck as the water under it, and blocks a deck's voxels may reach to either side. */
    private static final int BRIDGE_VOXEL = 8, BRIDGE_REACH = 32;

    /**
     * Surface of the water a built-up voxel bridges, or {@link #DRY}. Land cover maps a bridge's deck
     * as built-up, and in the coarse overviews far levels read, a deck a few pixels wide fills whole
     * voxels across a river, which stood at the deck's height as a dry line from bank to bank, as
     * the Natchez-Vidalia bridges did over the Mississippi. A built-up voxel that has water on
     * opposite sides within {@value #BRIDGE_REACH} blocks across built-up voxels, along a row, a
     * column or a diagonal, the two level within a block, is that water. A dam has its water higher
     * on one side than the other, and stands.
     */
    private int bridged(GlobalElevation dem, CoverMemo memo, int x, int z, int voxel) {
        int steps = Math.max(1, BRIDGE_REACH / voxel);
        for (int k = 0; k < 4; k++) {
            int dx = RING_STEP_X[k] * voxel, dz = RING_STEP_Z[k] * voxel;
            int a = waterAcross(dem, memo, x, z, -dx, -dz, steps, voxel);
            if (a == DRY) continue;
            int b = waterAcross(dem, memo, x, z, dx, dz, steps, voxel);
            if (b != DRY && Math.abs(a - b) <= 1) return Math.min(a, b);
        }
        return DRY;
    }

    /** Unit voxel steps along a row, a column and the two diagonals. */
    private static final int[] RING_STEP_X = {1, 0, 1, 1}, RING_STEP_Z = {0, 1, 1, -1};

    /** Surface of the first water from a column out along a step past built-up voxels, or {@link #DRY}. */
    private int waterAcross(GlobalElevation dem, CoverMemo memo, int x, int z, int dx, int dz, int steps, int voxel) {
        for (int i = 1; i <= steps; i++) {
            int px = x + i * dx, pz = z + i * dz, code = cover(memo, px, pz, voxel);
            if (code == WorldCoverSource.BUILT_UP) continue;
            return waterLevel(dem, memo, px, pz, voxel);
        }
        return DRY;
    }

    /** Surface of the water a column's land cover maps, or {@link #DRY} where it maps none. */
    private int waterLevel(GlobalElevation dem, CoverMemo memo, int x, int z, int voxel) {
        int code = cover(memo, x, z, voxel);
        if (code == WorldCoverSource.NO_DATA) return SEA;
        if (code != WorldCoverSource.WATER) return DRY;
        return seaWater(x, z) ? SEA : waterSurface(dem, x, z, voxel);
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
        if (lowest == DRY) lowest = riverBank(x, z, voxel);
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
                int water = code == WorldCoverSource.NO_DATA || seaWater(px, pz) ? SEA : waterSurface(dem, px, pz, voxel);
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
     * bathymetry counts as land, it shelves down from the shore as a lake's bed does, to at most
     * {@value #LAGOON_DEPTH} blocks.
     */
    private int seabed(int x, int z, double metres, int voxel) {
        int bed = Math.min(SEA - 1, Mth.floor(SEA + 0.5 + Math.min(metres, Math.min(0, bathymetry.metres(x + 0.5, z + 0.5)))));
        // It shelves no deeper than a lagoon: shelving on toward a lake's forty blocks, lagoon water the detail marks
        // as open sea lay three times as deep as the rest of the lagoon, behind a hard edge.
        if (bed > SEA - LAGOON_DEPTH && cover != null) bed = Math.min(bed, SEA - Math.min(LAGOON_DEPTH, lakeDepth(x, z, voxel)));
        return bed;
    }

    /** How deep sea water mapped at or above sea level may lie, as a lagoon or an estuary behind the coast. */
    private static final int LAGOON_DEPTH = 8;

    /**
     * Bed of sea water the land cover maps behind the coast, as a lagoon or estuary, where the
     * detail tiles read its surface near sea level rather than a depth. The coarse bathymetry
     * there reads the sea outside, or bad pixels, and put Lake Worth Lagoon a dozen blocks deep
     * where it is two or three metres; the bed shelves from the shore as a lake's does, never
     * deeper than {@value #LAGOON_DEPTH} blocks or the bathymetry.
     */
    private int lagoonBed(int x, int z, double metres, int voxel) {
        int bed = seabed(x, z, metres, voxel);
        // Where the detail holds real depths deeper than a lagoon lies, as in the North Sea, they stand.
        return metres < -LAGOON_DEPTH ? bed : Math.max(bed, SEA - Math.min(LAGOON_DEPTH, lakeDepth(x, z, voxel)));
    }

    // Rivers ---------------------------------------------------------------------------------------

    /**
     * Cover code of a column only the river map has water at. Land cover loses rivers narrower than
     * a few of its pixels, and those under forest, and the elevation, a surface model, holds no
     * channel for them either: the Amazon's tributaries were scattered ponds in the forest. The
     * river map ({@link RiverMap}) traces them from thirty-eight years of satellite water sightings,
     * joining the stretches the trees hide, and they are carved here as channels.
     */
    private static final int RIVER = 1000;
    /** Largest voxel, in blocks, that shows narrow rivers; past it a river is narrower than a voxel or two. */
    private static final int RIVER_VOXEL = 8;
    /**
     * Spacing, in blocks, of the points a river's level is read at; the radii each looks round for
     * the lowest ground near by and along the river; and how far below the lowest ground near by a
     * river may lie for its level to stay that of the river along it.
     */
    private static final int RIVER_NODE = 32, RIVER_REACH = 40, RIVER_RUN = 128, RIVER_SINK = 3;
    /** River levels at their points, in metres, as far as they have been read. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Float> riverNodes = new java.util.concurrent.ConcurrentHashMap<>();

    /** Top water block of the lake, river or narrow river at a water column. */
    private int waterSurface(GlobalElevation dem, double x, double z, int voxel) {
        return rivers != null && rawCover(x, z, voxel) == RIVER ? riverSurface(x, z) : lakeSurface(dem, x, z, voxel);
    }

    /**
     * Top water block of a narrow river. Each point of a {@value #RIVER_NODE}-block lattice takes the
     * lowest ground within {@value #RIVER_REACH} blocks of it, which by a river is the river. The
     * elevation over forest is the canopy's, a few metres up or down from one spot to the next, so
     * a river taken that way stepped every hundred blocks across flat country: where the lowest
     * ground within {@value #RIVER_RUN} blocks lies no more than {@value #RIVER_SINK} below it,
     * the river takes that, which holds level for long stretches. Surveyed water close by, if
     * lower, sets it too, so a stream meets its river or lake at the river's level. Columns blend
     * the four points round them. So the water never stands above the ground beside it, lies level
     * along flat stretches, and steps down along wavering lines where the land falls, the same in
     * cubes and every far level.
     */
    private int riverSurface(double x, double z) {
        double fx = x / RIVER_NODE, fz = z / RIVER_NODE;
        int ix = Mth.floor(fx), iz = Mth.floor(fz);
        double tx = fx - ix, tz = fz - iz;
        double level = Mth.lerp(tz, Mth.lerp(tx, riverNode(ix, iz), riverNode(ix + 1, iz)), Mth.lerp(tx, riverNode(ix, iz + 1), riverNode(ix + 1, iz + 1)));
        // Blended levels cross whole blocks along straight lines; a slow wobble, only ever
        // downward so the water stays below its banks, makes those steps wander.
        double wobble = 0.25 * (1 + patches.getValue(x / 23.0 + 311.7, z / 23.0 - 97.3));
        return Mth.floor(SEA + 0.5 + level - wobble);
    }

    private float riverNode(int i, int j) {
        long key = (long) i << 32 | j & 0xFFFFFFFFL;
        Float known = riverNodes.get(key);
        if (known != null) return known;
        GlobalElevation dem = elevation[TerrariumTileSource.MAX_ZOOM];
        double cx = (double) i * RIVER_NODE, cz = (double) j * RIVER_NODE, near = Double.MAX_VALUE, run = Double.MAX_VALUE;
        for (int dz = -RIVER_RUN; dz <= RIVER_RUN; dz += 8) for (int dx = -RIVER_RUN; dx <= RIVER_RUN; dx += 8) {
            int d2 = dx * dx + dz * dz;
            if (d2 > RIVER_RUN * RIVER_RUN) continue;
            double metres = dem.metres(cx + dx, cz + dz);
            run = Math.min(run, metres);
            if (d2 <= RIVER_REACH * RIVER_REACH) near = Math.min(near, metres);
        }
        double lowest = Math.max(run, near - RIVER_SINK);
        // Surveyed water nearby: its level, where it is lower.
        double spacing = (double) WATER_CELL / WATER_SAMPLES, sx = cx / spacing - 0.5, sz = cz / spacing - 0.5;
        int bx = Mth.floor(sx), bz = Mth.floor(sz);
        for (int dj = 0; dj <= 1; dj++) for (int di = 0; di <= 1; di++) {
            int si = bx + di, sj = bz + dj;
            float height = reachLevels(Math.floorDiv(si, WATER_SAMPLES), Math.floorDiv(sj, WATER_SAMPLES))
                    .samples[Math.floorMod(sj, WATER_SAMPLES) * WATER_SAMPLES + Math.floorMod(si, WATER_SAMPLES)];
            if (!Float.isNaN(height)) lowest = Math.min(lowest, height);
        }
        float level = (float) lowest;
        if (riverNodes.size() > 1 << 20) riverNodes.clear();
        riverNodes.putIfAbsent(key, level);
        return level;
    }

    /**
     * The lowest water a bank by a narrow river could lead down to, as {@link #bank} takes it from a
     * cell's lakes and rivers, or {@link #DRY} with no narrow river near.
     */
    private int riverBank(int x, int z, int voxel) {
        if (rivers == null || voxel > RIVER_VOXEL) return DRY;
        GeoPoint point = grid.geographic(x + 0.5, z + 0.5);
        if (rivers.near(point.latitude(), point.longitude()) < RiverMap.EDGE) return DRY;
        return riverSurface(x, z) - BANK;
    }

    // Land cover ----------------------------------------------------------------------------------

    /** Ground metres of a WorldCover pixel north to south. */
    private static final double PIXEL_METRES = 2 * Math.PI * 6_371_008.8 / 360 / WorldCoverSource.PIXELS_PER_DEGREE;
    /** Unit steps toward eight directions. */
    private static final double[] RING_X = {1, 0.7071, 0, -0.7071, -1, -0.7071, 0, 0.7071}, RING_Z = {0, 0.7071, 1, 0.7071, 0, -0.7071, -1, -0.7071};

    /**
     * The WorldCover class of a column, read at an overview whose pixels are no bigger than a
     * voxel. Up close the read point wanders by about half a pixel, so class borders curve. Water
     * on a glacier with only snow and ice round it is more of the ice ({@link #iceWater}).
     */
    private int cover(double x, double z, int voxel) { return water(rawCover(x, z, voxel)); }

    /** The cover of a column, or {@link #RIVER} where only the river map has water. */
    private int rawCover(double x, double z, int voxel) {
        int ix = (int) x, iz = (int) z;
        return ix == x && iz == z ? rawCover(coverMemo.get(), ix, iz, voxel) : coverAt(x, z, voxel);
    }

    /** A cover code as the land cover's: a river is water. */
    private static int water(int code) { return code == RIVER ? WorldCoverSource.WATER : code; }

    /**
     * Neighbouring columns probe the same points around them for shores and lakes, each about
     * five times, and every probe costs a map projection: this remembers recent answers.
     */
    private int cover(CoverMemo memo, int ix, int iz, int voxel) { return water(rawCover(memo, ix, iz, voxel)); }

    private int rawCover(CoverMemo memo, int ix, int iz, int voxel) {
        long key = (long) (ix & 0x7FFFFFF) << 31 | (long) (iz & 0x7FFFFFF) << 4 | Integer.numberOfTrailingZeros(voxel) & 15;
        int slot = (int) (key * 0x9E3779B97F4A7C15L >>> 52);
        if (memo.keys[slot] == key) return memo.codes[slot];
        int code = coverAt(ix, iz, voxel);
        memo.keys[slot] = key;
        memo.codes[slot] = code;
        return code;
    }

    /** Recent land cover answers on one thread, by column and voxel size. */
    private static final class CoverMemo {
        final long[] keys = new long[4096];
        final int[] codes = new int[4096];
        CoverMemo() { Arrays.fill(keys, -1L); }
    }
    private final ThreadLocal<CoverMemo> coverMemo = ThreadLocal.withInitial(CoverMemo::new);

    private int coverAt(double x, double z, int voxel) {
        double metresPerBlock = grid.groundMetresPerBlock(z);
        if (voxel <= 8) {
            double amplitude = 0.55 * PIXEL_METRES / metresPerBlock;
            double nx = x / 13.0, nz = z / 13.0;
            x += (warpX.getValue(nx, nz) + 0.5 * warpX.getValue(nx * 2.7, nz * 2.7)) * amplitude;
            z += (warpZ.getValue(nx, nz) + 0.5 * warpZ.getValue(nx * 2.7, nz * 2.7)) * amplitude;
        }
        int overview = Mth.clamp(Mth.floor(Math.log(voxel * metresPerBlock / PIXEL_METRES) / Math.log(2)), 0, WorldCoverSource.MAX_OVERVIEW);
        GeoPoint point = grid.geographic(x + 0.5, z + 0.5);
        int code = cover.code(point.latitude(), point.longitude(), overview);
        if (code == WorldCoverSource.WATER) {
            if (iceWater(x, z, overview, metresPerBlock)) return WorldCoverSource.SNOW_AND_ICE;
            return voxel <= STRAY_VOXEL ? strayWater(x, z, point) : code;
        }
        // Narrow rivers the land cover lost, read at the same wandering point so their banks curve too.
        if (rivers != null && voxel <= RIVER_VOXEL && code != WorldCoverSource.NO_DATA && code != WorldCoverSource.SNOW_AND_ICE
                && rivers.strength(point.latitude(), point.longitude()) >= RiverMap.EDGE) return RIVER;
        return code;
    }

    /** Most WorldCover pixels a patch of water mapped on a slope ({@link #strayWater}) holds: about fifty hectares. */
    private static final int STRAY_PIXELS = 5000;
    /** Pixels round a water cell a patch may reach out to and still be judged whole there. */
    private static final int STRAY_MARGIN = 40;
    /**
     * How far the ground under a stray patch rises across its middle half, in metres; how far across
     * most of it, per metre of its width; and how far the ground falls away below it, in metres.
     */
    private static final double STRAY_RISE = 6, STRAY_GRADE = 0.1, STRAY_FALL = 15;
    /** Rings round a patch's lowest pixel, in metres, along which {@link #strayCell} looks for the ground falling away. */
    private static final double[] STRAY_RINGS = {30, 60, 100};
    /** Widest voxel that looks for stray patches; wider ones read coarse overviews that blur them away. */
    private static final int STRAY_VOXEL = 8;
    /** Least range of a water cell's ground, in metres, for it to hold stray patches: flat country has no slopes to hang them on. */
    private static final double STRAY_RELIEF = 30;
    /** Water cells, keyed as {@link #waterCell}'s, as far as {@link #strayCell} has found their stray patches. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long2IntOpenHashMap> strayCells =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The cover of water mapped at a point: the land round it where the point lies in a small patch
     * of water hanging on a slope, else water. The map reads dark rock as water: the basalt that
     * poured over the Grand Canyon's walls at Lava Falls mapped as dozens of small patches all down
     * the slopes, each stood as a pool cut into the hillside. Water lies level, and the elevation
     * under a pond, a lake or a reach of river is level or nearly so, while under these it climbs
     * the slope with the rock, which carries on falling away below them.
     */
    private int strayWater(double x, double z, GeoPoint point) {
        int cx = Math.floorDiv(Mth.floor(x), WATER_CELL), cz = Math.floorDiv(Mth.floor(z), WATER_CELL);
        if (waterCell(cx, cz).relief() < STRAY_RELIEF) return WorldCoverSource.WATER;
        Long2IntOpenHashMap stray = strayCell(cx, cz);
        if (stray.isEmpty()) return WorldCoverSource.WATER;
        return stray.getOrDefault(pixelKey(row(point.latitude()), column(point.longitude())), WorldCoverSource.WATER);
    }

    private static long row(double latitude) { return (long) Math.floor((90 - latitude) * WorldCoverSource.PIXELS_PER_DEGREE); }
    private static long column(double longitude) { return (long) Math.floor((longitude + 180) * WorldCoverSource.PIXELS_PER_DEGREE); }
    private static long pixelKey(long row, long column) {
        return row << 32 | Math.floorMod(column, 360L * WorldCoverSource.PIXELS_PER_DEGREE);
    }

    /**
     * A water cell's stray patches, found once: every patch of full-resolution water pixels that
     * lies wholly within the cell and a margin round it, holds no more than {@value #STRAY_PIXELS}
     * pixels, and whose ground rises at least {@value #STRAY_RISE} m across its middle half and a
     * tenth of its width across most of it, then falls more than {@value #STRAY_FALL} m below its
     * lowest pixel within a hundred metres of it. Each of its pixels takes the class most of the
     * land pixels touching the patch have. Larger water, as rivers and the sea, always stays; so do
     * a creek's reach at a falls' lip, mostly level, and a river's below a falls, at the bottom.
     */
    private Long2IntOpenHashMap strayCell(int cx, int cz) {
        long key = (long) cx << 32 | cz & 0xFFFFFFFFL;
        Long2IntOpenHashMap known = strayCells.get(key);
        if (known != null) return known;
        GeoPoint a = grid.geographic(cx * (double) WATER_CELL, cz * (double) WATER_CELL);
        GeoPoint b = grid.geographic((cx + 1) * (double) WATER_CELL, (cz + 1) * (double) WATER_CELL);
        long rows = 180L * WorldCoverSource.PIXELS_PER_DEGREE;
        long top = Math.max(0, row(Math.max(a.latitude(), b.latitude())) - STRAY_MARGIN), left = column(Math.min(a.longitude(), b.longitude())) - STRAY_MARGIN;
        int height = (int) (Math.min(rows - 1, row(Math.min(a.latitude(), b.latitude())) + STRAY_MARGIN) - top + 1);
        int width = (int) (column(Math.max(a.longitude(), b.longitude())) + STRAY_MARGIN - left + 1);
        double ppd = WorldCoverSource.PIXELS_PER_DEGREE;
        byte[] codes = new byte[width * height];
        for (int j = 0; j < height; j++) for (int i = 0; i < width; i++)
            codes[j * width + i] = (byte) cover.code(90 - (top + j + 0.5) / ppd, (left + i + 0.5) / ppd - 180, 0);
        Long2IntOpenHashMap stray = new Long2IntOpenHashMap();
        boolean[] seen = new boolean[codes.length];
        int[] patch = new int[STRAY_PIXELS + 1];
        int[] queue = new int[codes.length];
        GlobalElevation dem = elevation[TOWER_ZOOM];
        for (int start = 0; start < codes.length; start++) {
            if (seen[start] || codes[start] != (byte) WorldCoverSource.WATER) continue;
            // The patch through this pixel, joined at edges and corners.
            int head = 0, tail = 0, size = 0;
            boolean whole = true;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int p = queue[head++], i = p % width, j = p / width;
                if (size <= STRAY_PIXELS) patch[size] = p;
                size++;
                if (i == 0 || j == 0 || i == width - 1 || j == height - 1) whole = false;
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                    int ni = i + di, nj = j + dj, n = nj * width + ni;
                    if (ni < 0 || nj < 0 || ni >= width || nj >= height || seen[n] || codes[n] != (byte) WorldCoverSource.WATER) continue;
                    seen[n] = true;
                    queue[tail++] = n;
                }
            }
            if (!whole || size > STRAY_PIXELS) continue;
            double[] ground = new double[size];
            int north = height, south = 0, west = width, east = 0;
            double lowest = Double.MAX_VALUE, lowX = 0, lowZ = 0;
            for (int k = 0; k < size; k++) {
                int i = patch[k] % width, j = patch[k] / width;
                GeoPoint p = new GeoPoint(90 - (top + j + 0.5) / ppd, (left + i + 0.5) / ppd - 180);
                double bx = grid.blockX(p), bz = grid.blockZ(p);
                ground[k] = dem.metres(bx, bz);
                if (ground[k] < lowest) { lowest = ground[k]; lowX = bx; lowZ = bz; }
                north = Math.min(north, j); south = Math.max(south, j); west = Math.min(west, i); east = Math.max(east, i);
            }
            Arrays.sort(ground);
            double pixel = 1 / ppd * Math.PI / 180 * 6_371_008.8, latitude = 90 - (top + (north + south) / 2.0) / ppd;
            double across = Math.hypot((south - north + 1) * pixel, (east - west + 1) * pixel * Math.cos(Math.toRadians(latitude)));
            if (ground[size * 3 / 4] - ground[size / 4] < STRAY_RISE || ground[size * 9 / 10] - ground[size / 10] < STRAY_GRADE * across) continue;
            double metresPerBlock = grid.groundMetresPerBlock(lowZ), below = lowest;
            for (double ring : STRAY_RINGS) for (int d = 0; d < 8; d++)
                below = Math.min(below, dem.metres(lowX + RING_X[d] * ring / metresPerBlock, lowZ + RING_Z[d] * ring / metresPerBlock));
            if (below > lowest - STRAY_FALL) continue;
            // The land round it decides what it is.
            int[] votes = new int[256];
            int best = WorldCoverSource.NO_DATA;
            for (int k = 0; k < size; k++) {
                int i = patch[k] % width, j = patch[k] / width;
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                    int ni = i + di, nj = j + dj;
                    if (ni < 0 || nj < 0 || ni >= width || nj >= height) continue;
                    int c = codes[nj * width + ni] & 0xFF;
                    if (c == WorldCoverSource.WATER || c == WorldCoverSource.NO_DATA) continue;
                    if (++votes[c] > votes[best]) best = c;
                }
            }
            if (best == WorldCoverSource.NO_DATA) continue;
            for (int k = 0; k < size; k++) stray.put(pixelKey(top + patch[k] / width, left + patch[k] % width), best);
        }
        if (strayCells.size() > 1 << 14) strayCells.clear();
        strayCells.putIfAbsent(key, stray);
        return stray;
    }

    /** Rings round water, in metres, that {@link #iceWater} looks along, out past a pond's far shore; none nearer than a pixel. */
    private static final double[] ICE_RINGS = {15, 30, 60, 120};
    /** Water cells, keyed as {@link #waterCell}'s, as far as {@link #icyCell} has found whether they hold snow or ice. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> icyCells = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether water mapped on a glacier or snowfield is more of the ice. The map reads melt streaks,
     * shadowed crevasses, slush and ponds on glaciers as water, where the elevation, older than the
     * map, still has the ice's sloping surface: they stood as dark blue pools all over ones such as
     * the Khumbu, and up close as stairs of ice down the slope. It is when rings round it, out past
     * a pond's far shore, find snow and ice, and three times more of it than of any land. A lake
     * beside a glacier has moraine or rock along most of its other shores, and a big one has water
     * all round, so they stay.
     */
    private boolean iceWater(double x, double z, int overview, double metresPerBlock) {
        if (!icyCell(Math.floorDiv(Mth.floor(x), WATER_CELL), Math.floorDiv(Mth.floor(z), WATER_CELL))) return false;
        int snow = 0, other = 0;
        for (double ring : ICE_RINGS) {
            double r = Math.max(ring, PIXEL_METRES * (1 << overview)) / metresPerBlock;
            for (int i = 0; i < 8; i++) {
                GeoPoint point = grid.geographic(x + 0.5 + RING_X[i] * r, z + 0.5 + RING_Z[i] * r);
                int code = cover.code(point.latitude(), point.longitude(), overview);
                if (code == WorldCoverSource.SNOW_AND_ICE) snow++;
                else if (code != WorldCoverSource.WATER) other++;
            }
        }
        return snow >= 4 && snow >= 3 * other;
    }

    /** Whether a water cell holds any snow or ice, read on a coarse grid at the water survey's overview. */
    private boolean icyCell(int cx, int cz) {
        long key = (long) cx << 32 | cz & 0xFFFFFFFFL;
        Boolean known = icyCells.get(key);
        if (known != null) return known;
        boolean icy = false;
        double spacing = WATER_CELL / 8.0;
        for (int k = 0; k < 64 && !icy; k++) {
            GeoPoint point = grid.geographic(cx * (double) WATER_CELL + (k % 8 + 0.5) * spacing, cz * (double) WATER_CELL + (k / 8 + 0.5) * spacing);
            icy = cover.code(point.latitude(), point.longitude(), WATER_OVERVIEW) == WorldCoverSource.SNOW_AND_ICE;
        }
        if (icyCells.size() > 1 << 16) icyCells.clear();
        icyCells.putIfAbsent(key, icy);
        return icy;
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
            // A town in the woods becomes open parkland, not a dense forest the satellite never shows there.
            if (found > 0) return best == WorldCoverSource.TREE_COVER ? PARKLAND : best;
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
     * comes from one place for cubes and every far level alike: the {@link #reachLevels} levels of
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
        // Levels are read a little to one side or the other, so where water steps down its edge wanders, rather
        // than running in straight lines along the cells and samples.
        double wx = x + (warpX.getValue(x / 70.0, z / 70.0) + 0.5 * warpX.getValue(x / 23.0, z / 23.0)) * WANDER,
                wz = z + (warpZ.getValue(x / 70.0, z / 70.0) + 0.5 * warpZ.getValue(x / 23.0, z / 23.0)) * WANDER;
        double fx = wx / WATER_CELL - 0.5, fz = wz / WATER_CELL - 0.5;
        int ix = Mth.floor(fx), iz = Mth.floor(fz);
        double tx = fx - ix, tz = fz - iz, sum = 0, weight = 0, relief = 0, top = Double.NEGATIVE_INFINITY;
        // Each cell offers its lowest reach that lies no more than a drop below the ground here, or else its
        // highest; the column takes the highest reach on offer that the ground leaves room for, blended with the
        // cells offering it within a drop of it. So a river above a falls keeps its level beside the gorge below it in the same cell, and the
        // gorge keeps its own, while a creek's mouth still meets the river it joins at the river's level.
        // The ground is taken from the survey's own zoom too, which still holds narrow chasms, as Victoria Falls'
        // first gorge, that cleaning the finest tiles filled; and a little wider, so a gorge's narrow water keeps
        // its level to its walls.
        GlobalElevation survey = elevation[WATER_ZOOM];
        double r2 = Math.max(24, voxel), floor = Math.min(lowest, survey.metres(x + 0.5, z + 0.5));
        floor = Math.min(floor, Math.min(survey.metres(x + 0.5 + r2, z + 0.5), survey.metres(x + 0.5 - r2, z + 0.5)));
        floor = Math.min(floor, Math.min(survey.metres(x + 0.5, z + 0.5 + r2), survey.metres(x + 0.5, z + 0.5 - r2)));
        double[] offered = new double[4];
        boolean[] drownedOffer = new boolean[4];
        for (int c = 0; c < 4; c++) {
            offered[c] = Double.NaN;
            WaterCell cell = waterCell(ix + (c & 1), iz + (c >> 1));
            if (cell.samples == 0) continue;
            relief = Math.max(relief, cell.relief);
            Reaches reaches = reachLevels(ix + (c & 1), iz + (c >> 1));
            int pick = reaches.levels.length - 1;
            for (int k = 0; k < reaches.levels.length; k++) if (reaches.levels[k] >= floor - DROP) { pick = k; break; }
            offered[c] = reaches.levels[pick];
            drownedOffer[c] = reaches.drownedLevels[pick];
        }
        // Offers the ground here leaves no room for give way to any that it does, so a chasm's water is not lifted
        // to the river above it by a cell that holds no water down in it.
        boolean room = false;
        for (double level : offered) room |= level <= floor + BANK;
        for (double level : offered) if (!Double.isNaN(level) && (!room || level <= floor + BANK)) top = Math.max(top, level);
        for (int c = 0; c < 4; c++) {
            if (Double.isNaN(offered[c]) || offered[c] < top - DROP || offered[c] > top) continue;
            int i = c & 1, j = c >> 1;
            WaterCell cell = waterCell(ix + i, iz + j);
            double w = ((i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - tz : tz) + 1e-6) * cell.samples;
            sum += offered[c] * w;
            weight += w;
        }
        double level = weight == 0 ? Double.NaN : sum / weight;
        boolean drowned = false;
        for (int c = 0; c < 4; c++) drowned |= drownedOffer[c] && offered[c] == top;
        // Near surveyed water, the column takes the reach of the nearest sample, blended with the samples close by
        // of reaches within a drop of it: a falls' lip lies between the samples above and below it, wherever the
        // ground under them reads, as over Victoria Falls' chasm, which the finest tiles fill.
        double spacing = (double) WATER_CELL / WATER_SAMPLES, sx = wx / spacing - 0.5, sz = wz / spacing - 0.5;
        int bx = Mth.floor(sx), bz = Mth.floor(sz), found = 0;
        double nearest = NEAR * NEAR, near = Double.NaN;
        double[] heights = new double[16], distances = new double[16];
        boolean drownedNear = false, fallingNear = false;
        for (int dj = -1; dj <= 2; dj++) for (int di = -1; di <= 2; di++) {
            int si = bx + di, sj = bz + dj, k = Math.floorMod(sj, WATER_SAMPLES) * WATER_SAMPLES + Math.floorMod(si, WATER_SAMPLES);
            Reaches reaches = reachLevels(Math.floorDiv(si, WATER_SAMPLES), Math.floorDiv(sj, WATER_SAMPLES));
            float height = reaches.samples[k];
            double d2 = (sx - si) * (sx - si) + (sz - sj) * (sz - sj);
            if (Float.isNaN(height) || d2 >= NEAR * NEAR) continue;
            heights[found] = height;
            distances[found++] = d2;
            if (d2 < nearest) {
                nearest = d2;
                near = height;
                drownedNear = (reaches.drowned[k >> 6] >>> (k & 63) & 1) != 0;
                fallingNear = (reaches.falling[k >> 6] >>> (k & 63) & 1) != 0;
            }
        }
        // Drowned ground's level is its reach's across the cells around, blended smoothly between them, rather
        // than stepping at the edges of each cell's samples.
        if (found > 0 && !(drownedNear && drowned && !Double.isNaN(level))) {
            // The cells' level joins in a little, so the water meets it smoothly where the samples run out.
            sum = 0;
            weight = 0;
            if (!Double.isNaN(level) && Math.abs(level - near) <= DROP) { sum = level * 0.05; weight = 0.05; }
            for (int k = 0; k < found; k++) {
                if (Math.abs(heights[k] - near) > DROP) continue;
                double w = (NEAR * NEAR - distances[k]) * (NEAR * NEAR - distances[k]);
                sum += heights[k] * w;
                weight += w;
            }
            level = sum / weight;
            drowned = drownedNear;
        } else fallingNear = false;
        if (Double.isNaN(level)) return local;
        int surface = Mth.floor(SEA + 0.5 + level);
        // A falls' face stands at the level of the reach above it or the pool below, whatever the ground reads.
        if (fallingNear) return surface;
        if (relief > MOUNTAINS) surface = Math.max(surface, local - DIP);
        // Drowned ground lies far under its water, which stands at its shores' level.
        return drowned ? surface : Math.min(surface, local + BANK);
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
    private final java.util.concurrent.ConcurrentHashMap<Long, Reaches> reaches = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A cell's reach levels, lowest first, and which of them are drowned ground's; the level of the
     * reach each of its own samples belongs to, or NaN; which of its samples are drowned; and which
     * lie on a falls' face ({@link #fallingWater}).
     */
    private record Reaches(double[] levels, boolean[] drownedLevels, float[] samples, long[] drowned, long[] falling) {}
    /** Fewest of a cell's own samples a reach needs to keep its own level there, rather than count as a stray reading. */
    private static final int REACH_SAMPLES = 3;
    /** How far, in samples, a column takes its level from the surveyed water samples near it. */
    private static final double NEAR = 1.5;
    /** How far, in blocks, the edges where water steps down wander either way. */
    private static final double WANDER = 14;

    /** Drops of more than {@link #DROP} between wide water samples a reach must cross to count as drowned ground. */
    private static final int DROWNED_CROSSINGS = 3;
    /** Fewest samples of water joined within {@link #DROP} that stand as a reach either side of a falls; most samples of pockets between. */
    private static final int FALL_POOL = 8, FACE = 64;
    /** Least drop, in metres, between the reaches either side of a falls' face. */
    private static final double FALL = 20;

    /**
     * Drowned ground in a reach window ({@link #reachLevels}): for each sample, the level of the
     * drowned water it lies under, or NaN; or null if there is none. Each body of wide water is
     * flooded through water within {@value #DROP} metres and across greater drops between wide
     * samples; with {@value #DROWNED_CROSSINGS} such drops or more it is drowned, at its shores'
     * level, and so is all water joined to it that lies below that level, as the arms it backs up
     * into side valleys.
     */
    @Nullable
    private float[] drownedGround(int cx, int cz, int span, float[] water, float[] ground, boolean[] seaAt, it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue queue) {
        float[] sunk = null;
        boolean[] seen = new boolean[span * span];
        it.unimi.dsi.fastutil.ints.IntArrayList body = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.doubles.DoubleArrayList shores = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        for (int start = 0; start < span * span; start++) {
            if (seen[start] || Float.isNaN(water[start]) || seaAt[start] || !wideWater(water, span, start % span, start / span)) continue;
            seen[start] = true;
            queue.enqueue(start);
            body.clear();
            int crossings = 0;
            while (!queue.isEmpty()) {
                int at = queue.dequeueInt(), i = at % span, j = at / span;
                body.add(at);
                if (!wideWater(water, span, i, j)) continue;
                for (int d = 0; d < 8; d += 2) {
                    int ni = i + (int) RING_X[d], nj = j + (int) RING_Z[d], n = nj * span + ni;
                    if (ni < 0 || nj < 0 || ni >= span || nj >= span || seen[n] || Float.isNaN(water[n]) || seaAt[n]) continue;
                    if (Math.abs(water[n] - water[at]) > DROP) {
                        if (!wideWater(water, span, ni, nj)) continue;
                        crossings++;
                    }
                    seen[n] = true;
                    queue.enqueue(n);
                }
            }
            if (crossings < DROWNED_CROSSINGS) continue;
            shores.clear();
            for (int m = 0; m < body.size(); m++) {
                int at = body.getInt(m), i = at % span, j = at / span;
                for (int d = 0; d < 8; d += 2) {
                    int ni = i + (int) RING_X[d], nj = j + (int) RING_Z[d];
                    if (ni >= 0 && nj >= 0 && ni < span && nj < span && !Float.isNaN(ground[nj * span + ni])) shores.add(shoreHeight(cx, cz, i, j, ni, nj));
                }
            }
            if (shores.isEmpty()) continue;
            double[] around = shores.toDoubleArray();
            java.util.Arrays.sort(around);
            float level = (float) around[around.length / 2];
            if (sunk == null) { sunk = new float[span * span]; java.util.Arrays.fill(sunk, Float.NaN); }
            for (int m = 0; m < body.size(); m++) { sunk[body.getInt(m)] = level; queue.enqueue(body.getInt(m)); }
            while (!queue.isEmpty()) {
                int at = queue.dequeueInt(), i = at % span, j = at / span;
                for (int d = 0; d < 8; d += 2) {
                    int ni = i + (int) RING_X[d], nj = j + (int) RING_Z[d], n = nj * span + ni;
                    if (ni < 0 || nj < 0 || ni >= span || nj >= span || Float.isNaN(water[n]) || seaAt[n] || !Float.isNaN(sunk[n]) || water[n] > level + BANK) continue;
                    sunk[n] = level;
                    seen[n] = true;
                    queue.enqueue(n);
                }
            }
        }
        return sunk;
    }

    /**
     * Water on a falls' face in a reach window ({@link #reachLevels}): for each sample, the level
     * it takes, or NaN; or null if there is none. Where water pours over a falls, its spray and
     * the rapids above and below it map as water down the face, which the survey reads at every
     * height between the reach above and the pool below, a few samples each, as at Iguazu's Devil's
     * Throat or Gullfoss; taken as they read, they stood in a staircase of ledges down the falls.
     * Water is level but where it falls, so each pocket of such water, joined to fewer than
     * {@value #FALL_POOL} samples within {@value #DROP} metres, lying between reaches at least
     * {@value #FALL} metres apart, takes the level of whichever of them reads nearest its own: the
     * falls drops once, from the reach above to the pool below. Pockets of more than
     * {@value #FACE} samples are a river's own course down its valley, and keep their heights.
     */
    @Nullable
    private static float[] fallingWater(int span, float[] water, @Nullable float[] sunk, boolean[] seaAt, it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue queue) {
        int count = span * span;
        float[] height = new float[count];
        for (int at = 0; at < count; at++) height[at] = seaAt[at] ? Float.NaN : sunk != null && !Float.isNaN(sunk[at]) ? sunk[at] : water[at];
        // Reaches: water joined within a drop, each with its size and the lowest quarter of its heights.
        int[] piece = new int[count];
        java.util.Arrays.fill(piece, -1);
        it.unimi.dsi.fastutil.ints.IntArrayList sizes = new it.unimi.dsi.fastutil.ints.IntArrayList(), members = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.floats.FloatArrayList levels = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        float[] heights = new float[count];
        for (int start = 0; start < count; start++) {
            if (piece[start] >= 0 || Float.isNaN(height[start])) continue;
            int id = sizes.size(), size = 0;
            piece[start] = id;
            queue.enqueue(start);
            while (!queue.isEmpty()) {
                int at = queue.dequeueInt(), i = at % span, j = at / span;
                heights[size++] = height[at];
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                    int ni = i + di, nj = j + dj, n = nj * span + ni;
                    if (ni < 0 || nj < 0 || ni >= span || nj >= span || piece[n] >= 0 || Float.isNaN(height[n]) || Math.abs(height[n] - height[at]) > DROP) continue;
                    piece[n] = id;
                    queue.enqueue(n);
                }
            }
            java.util.Arrays.sort(heights, 0, size);
            sizes.add(size);
            levels.add(heights[size / 4]);
        }
        // Pockets: small reaches joined to each other, and the big reaches beside them.
        float[] falling = null;
        boolean[] seen = new boolean[count];
        it.unimi.dsi.fastutil.floats.FloatArrayList beside = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        for (int start = 0; start < count; start++) {
            if (seen[start] || piece[start] < 0 || sizes.getInt(piece[start]) >= FALL_POOL) continue;
            seen[start] = true;
            queue.enqueue(start);
            members.clear();
            beside.clear();
            while (!queue.isEmpty()) {
                int at = queue.dequeueInt(), i = at % span, j = at / span;
                members.add(at);
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                    int ni = i + di, nj = j + dj, n = nj * span + ni;
                    if (ni < 0 || nj < 0 || ni >= span || nj >= span || piece[n] < 0) continue;
                    if (sizes.getInt(piece[n]) >= FALL_POOL) {
                        if (!beside.contains(levels.getFloat(piece[n]))) beside.add(levels.getFloat(piece[n]));
                    } else if (!seen[n]) {
                        seen[n] = true;
                        queue.enqueue(n);
                    }
                }
            }
            if (members.size() > FACE || beside.size() < 2) continue;
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            for (int k = 0; k < beside.size(); k++) { low = Math.min(low, beside.getFloat(k)); high = Math.max(high, beside.getFloat(k)); }
            if (high - low < FALL) continue;
            for (int m = 0; m < members.size(); m++) {
                int at = members.getInt(m);
                if (height[at] < low - BANK || height[at] > high + BANK) continue;
                float nearest = Float.NaN;
                for (int k = 0; k < beside.size(); k++) if (Float.isNaN(nearest) || Math.abs(beside.getFloat(k) - height[at]) < Math.abs(nearest - height[at])) nearest = beside.getFloat(k);
                if (falling == null) { falling = new float[count]; java.util.Arrays.fill(falling, Float.NaN); }
                falling[at] = nearest;
            }
        }
        return falling;
    }

    /**
     * Surveyed height, in metres, where the land cover's shore lies between a water sample and the
     * dry sample beside it in a reach window around a cell: found by halving the gap four times.
     */
    private double shoreHeight(int cx, int cz, int wi, int wj, int di, int dj) {
        double spacing = (double) WATER_CELL / WATER_SAMPLES, ox = (cx - REACH) * (double) WATER_CELL, oz = (cz - REACH) * (double) WATER_CELL;
        double wet = 0, dry = 1, x0 = ox + (wi + 0.5) * spacing, z0 = oz + (wj + 0.5) * spacing, dx = (di - wi) * spacing, dz = (dj - wj) * spacing;
        for (int step = 0; step < 4; step++) {
            double t = (wet + dry) / 2;
            GeoPoint point = grid.geographic(x0 + dx * t, z0 + dz * t);
            if (cover.code(point.latitude(), point.longitude(), WATER_OVERVIEW) == WorldCoverSource.WATER) wet = t; else dry = t;
        }
        double t = (wet + dry) / 2;
        return elevation[WATER_ZOOM].metres(x0 + dx * t, z0 + dz * t);
    }

    /**
     * The water levels of a cell's reaches, in metres, lowest first: for each reach, the lowest
     * quarter of the surveyed heights of the water joined to it through wide water, within
     * {@value #REACH} cells. A big river's or lake's survey wanders metres up and down from one
     * cell to the next, as over the Rio Negro; taken cell by cell, its level climbed and fell a
     * block every few hundred blocks, in rings of steps across the water. Over the reach it is one
     * level. Water joined only through narrow water, as a creek or a canal, keeps its own level,
     * and so does water past a drop of more than {@value #DROP} metres between neighbouring
     * samples, as at a dam or falls. A cell's own samples join the water next to them however
     * narrow they are, so a sliver of a river at a cell's edge, which reads the banks blurred into
     * it, takes the river's level; but not across such a drop, so a cell holding a river above
     * and below its falls, as at Victoria Falls, has a level for each.
     *
     * <p>Wide water whose survey falls away under it again and again, at least
     * {@value #DROWNED_CROSSINGS} drops of more than {@value #DROP} metres between samples with
     * water all round them, is drowned ground: a reservoir filled after the survey, as the Three
     * Gorges', whose elevation is the valley before the dam. A real falls or dam is one drop, with
     * level water either side. Taken drop by drop, the reservoir stood in dozens of levels up the
     * old valley's slopes, in tiers of stone shelves and sheer walls. It is one reach, at its
     * shores' level: the middle of the heights where its water meets dry land, half a sample
     * either side of the shore.
     */
    private Reaches reachLevels(int cx, int cz) {
        long key = (long) cx << 32 | cz & 0xFFFFFFFFL;
        Reaches known = reaches.get(key);
        if (known != null) return known;
        int span = (2 * REACH + 1) * WATER_SAMPLES, first = REACH * WATER_SAMPLES, last = first + WATER_SAMPLES;
        float[] water = new float[span * span], ground = new float[span * span];
        boolean[] seaAt = new boolean[span * span];
        for (int wj = 0; wj <= 2 * REACH; wj++) for (int wi = 0; wi <= 2 * REACH; wi++) {
            Survey survey = survey(cx - REACH + wi, cz - REACH + wj);
            long[] sea = waterCell(cx - REACH + wi, cz - REACH + wj).sea;
            for (int k = 0; k < survey.metres.length; k++) {
                boolean raisedSea = (sea[k >> 6] >>> (k & 63) & 1) != 0 && survey.metres[k] > SEA_METRES;
                int at = (wj * WATER_SAMPLES + k / WATER_SAMPLES) * span + wi * WATER_SAMPLES + k % WATER_SAMPLES;
                water[at] = (survey.kind[k] == WET || survey.kind[k] == FLAT) && !raisedSea ? survey.metres[k] : Float.NaN;
                ground[at] = survey.kind[k] == DRY_LAND ? survey.metres[k] : Float.NaN;
                seaAt[at] = survey.kind[k] == OPEN || (sea[k >> 6] >>> (k & 63) & 1) != 0;
            }
        }
        it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue queue = new it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue();
        float[] sunk = drownedGround(cx, cz, span, water, ground, seaAt, queue);
        float[] falls = fallingWater(span, water, sunk, seaAt, queue);
        boolean[] reached = new boolean[span * span];
        double[] heights = new double[span * span];
        it.unimi.dsi.fastutil.doubles.DoubleArrayList levels = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        it.unimi.dsi.fastutil.booleans.BooleanArrayList drownedLevels = new it.unimi.dsi.fastutil.booleans.BooleanArrayList();
        float[] samples = new float[WATER_SAMPLES * WATER_SAMPLES];
        long[] drowned = new long[NO_SEA.length], falling = new long[NO_SEA.length];
        java.util.Arrays.fill(samples, Float.NaN);
        it.unimi.dsi.fastutil.ints.IntArrayList members = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int sj = first; sj < last; sj++) for (int si = first; si < last; si++) {
            int start = sj * span + si, k = (sj - first) * WATER_SAMPLES + si - first;
            if (sunk != null && !Float.isNaN(sunk[start])) {
                samples[k] = sunk[start];
                drowned[k >> 6] |= 1L << (k & 63);
                if (!levels.contains(sunk[start])) { levels.add(sunk[start]); drownedLevels.add(true); }
                continue;
            }
            if (falls != null && !Float.isNaN(falls[start])) {
                samples[k] = falls[start];
                falling[k >> 6] |= 1L << (k & 63);
                if (!levels.contains(falls[start])) { levels.add(falls[start]); drownedLevels.add(false); }
                continue;
            }
            if (reached[start] || Float.isNaN(water[start])) continue;
            // Each reach floods from a sample of the cell's own that no other reach has reached.
            boolean[] joined = new boolean[span * span];
            joined[start] = true;
            queue.enqueue(start);
            int count = 0, own = 0;
            members.clear();
            while (!queue.isEmpty()) {
                int at = queue.dequeueInt(), i = at % span, j = at / span;
                heights[count++] = water[at];
                boolean inside = i >= first && i < last && j >= first && j < last;
                if (inside && !reached[at]) { reached[at] = true; own++; members.add((j - first) * WATER_SAMPLES + i - first); }
                if (!inside && !wideWater(water, span, i, j)) continue;
                for (int d = 0; d < 8; d += 2) {
                    int ni = i + (int) RING_X[d], nj = j + (int) RING_Z[d], n = nj * span + ni;
                    if (ni < 0 || nj < 0 || ni >= span || nj >= span || joined[n] || Float.isNaN(water[n])) continue;
                    if (Math.abs(water[n] - water[at]) > DROP || sunk != null && !Float.isNaN(sunk[n]) || falls != null && !Float.isNaN(falls[n])) continue;
                    joined[n] = true;
                    queue.enqueue(n);
                }
            }
            if (own < REACH_SAMPLES || count < 4) continue;
            java.util.Arrays.sort(heights, 0, count);
            levels.add(heights[count / 4]);
            drownedLevels.add(false);
            for (int m = 0; m < members.size(); m++) samples[members.getInt(m)] = (float) heights[count / 4];
        }
        Integer[] order = new Integer[levels.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        java.util.Arrays.sort(order, (p, q) -> Double.compare(levels.getDouble(p), levels.getDouble(q)));
        double[] sorted = new double[Math.max(1, order.length)];
        boolean[] sunkSorted = new boolean[sorted.length];
        if (order.length == 0) sorted[0] = waterCell(cx, cz).level;
        for (int k = 0; k < order.length; k++) { sorted[k] = levels.getDouble(order[k]); sunkSorted[k] = drownedLevels.getBoolean(order[k]); }
        Reaches found = new Reaches(sorted, sunkSorted, samples, drowned, falling);
        if (reaches.size() > 1 << 20) reaches.clear();
        reaches.putIfAbsent(key, found);
        return found;
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
    private int shore(GlobalElevation dem, CoverMemo memo, int x, int z, int voxel) {
        int best = DRY;
        double near = Math.max(3, voxel), far = Math.max(8, 2.5 * voxel);
        for (int k = 0; k < 8; k += 2) {
            for (double r : new double[]{near, far}) {
                double px = x + RING_X[k] * r, pz = z + RING_Z[k] * r;
                if (cover(memo, (int) px, (int) pz, voxel) == WorldCoverSource.WATER && !seaWater(px, pz)) best = Math.max(best, waterSurface(dem, px, pz, voxel));
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

    /** Blocks above the sea and in from it that bare ground counts as beach. */
    private static final int BEACH_RISE = 8, BEACH_REACH = 64;

    /** Whether sea water lies within {@code r} blocks of a column, looking out at half and full reach. */
    private boolean seaWithin(int x, int z, int r) {
        for (int d = r / 2; d <= r; d += r / 2)
            for (int k = 0; k < 8; k++) if (waterY(x + (int) (RING_X[k] * d), z + (int) (RING_Z[k] * d)) == SEA) return true;
        return false;
    }

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

    /**
     * Blocks each way over which a slope must hold for bare rock. The 1-5 m elevation data carry
     * roads' cuts and embankments: steep for a few blocks, then flat, so they drew straight lines
     * of stone along every road. Cliffs and rocky mountainsides stay steep over this reach.
     */
    private static final int BROAD = 12;

    /** The rise per run over {@link #BROAD} blocks each way, in the units of a column's steepness. */
    private double broadSteep(int x, int z) {
        GlobalElevation dem = elevation[zoom(1)];
        double dx = dem.metres(x + 0.5 + BROAD, z + 0.5) - dem.metres(x + 0.5 - BROAD, z + 0.5);
        double dz = dem.metres(x + 0.5, z + 0.5 + BROAD) - dem.metres(x + 0.5, z + 0.5 - BROAD);
        return (Math.abs(dx) + Math.abs(dz)) / BROAD / 2.4;
    }

    /** A column's surface with the given imagery, or none, having the last word; for {@link ImageryCalibration}. */
    Surface surface(int x, int z, int y, int water, int slope, int voxel, @Nullable ImageryGround imageryGround) {
        Local local = cover == null ? null : local(x, z, y);
        double t = local == null ? temperature(x, z, y) : local.temperature();
        if (water != DRY && water != SEA && cover != null) return lake(x, z, y, water, t);
        if (water != DRY) {
            int depth = water - y;
            // Warm seas are turquoise only over reefs and shallows; deeper water is dark blue, as from above.
            // The depths that part them wander by several blocks, and warm shallows turn lukewarm before they
            // turn deep: cut at one depth, the bed's steps drew hard-edged navy boxes in turquoise lagoons.
            // No polar bears in the south: the Southern Ocean is cold ocean, iced over by iced() as frozen ocean is.
            double deep = depth + seaWobble(x, z);
            Holder<Biome> b = t < -2 && latitude(z) > -50 ? frozenOcean : t < 6 ? (deep > 40 ? deepColdOcean : coldOcean)
                    : t > 15 ? (deep > (t > 21 ? 2 * SHALLOWS : 40) ? deepLukewarmOcean : t > 21 && deep <= SHALLOWS ? warmOcean : lukewarmOcean)
                    : deep > 40 ? deepOcean : ocean;
            BlockState floor = depth < 30 ? SAND : GRAVEL;
            return new Surface(b, floor, floor, 0, 0, 0, 0, 0, false);
        }
        // Summed block steps over two blocks each way, as a rough rise per run.
        double steep = slope / 2.4;
        if (steep > 0.6 && voxel < BROAD && broadSteep(x, z) < 0.55) {
            // Steep only over a few blocks, as at a road cut, embankment or ditch: ground, not bare rock.
            steep = 0.6;
            slope = 1;
        }
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
            Surface ground = imageryGround == null || built ? covered
                    : imageryGround.apply(covered, code, x, z, local.zone(), steep, voxel, (mix(x, 0x494D47L, z) >>> 11) * 0x1.0p-53);
            Strata.Layers rock = strata.layers(x, z, SEA + 0.5);
            return rock == null ? ground : rock.dress(ground, y, steep);
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

    /**
     * Lakes and rivers freeze over wherever their biome freezes; the sea freezes in floes, more of
     * it the colder the coast, until it is closed pack ice in the high Arctic and off Antarctica.
     */
    @Override protected boolean iced(Surface surface, int x, int z) {
        boolean southern = (surface.biome() == coldOcean || surface.biome() == deepColdOcean) && latitude(z) < -50;
        if (!southern && !super.iced(surface, x, z)) return false;
        if (surface.biome() != frozenOcean && !southern) return true;
        double t = cover == null ? temperature(x, z, SEA) : local(x, z, SEA).temperature();
        double share = Mth.clamp((-t - 1) / 10, 0, 1);
        double floes = patches.getValue(x / 53.0 + 91.3, z / 53.0 - 17.7) * 0.75 + patches.getValue(x / 13.0 - 40.1, z / 13.0 + 8.3) * 0.25;
        return floes < share * 2 - 1;
    }

    /** Depth of warm sea, in blocks, to which it is reef and shallows, before it turns lukewarm and then, at twice this, deep. */
    private static final int SHALLOWS = 13;

    /** Blocks added to a sea column's depth before its biome is picked, so depth bands wander rather than follow the bed's steps. */
    private double seaWobble(int x, int z) {
        return (warpX.getValue(x / 97.0, z / 97.0) + 0.4 * warpZ.getValue(x / 41.0, z / 41.0)) * 4;
    }

    /** A lake or river bed: sand by the shore, then gravel, clay or mud further out. */
    private Surface lake(int x, int z, int y, int water, double t) {
        int depth = water - y;
        long r = mix(x, 11, z);
        // Shallow tropical ponds, such as the Mekong delta's, are mostly muddy rather than rimmed with bright sand.
        BlockState floor = depth <= 2 ? (t < 4 ? GRAVEL : t > 20 && patches.getValue(x / 90.0 + 3.3, z / 90.0 - 8.1) > -0.3 ? MUD : SAND) : switch ((int) Math.floorMod(r, 5)) {
            case 0 -> CLAY; case 1 -> SAND; default -> GRAVEL; };
        if (depth > 8 && patches.getValue(x / 19.0, z / 19.0) > 0.2) floor = MUD;
        // Tropical lagoons behind a reef, as at Bora Bora, are clear turquoise sea over sand, not river water.
        if (t > 20 && water <= SEA + 24 && lagoon(x, z)) return new Surface(warmOcean, SAND, SAND, 0, 0, 0, 0, 0, false);
        if (t < -2) return new Surface(frozenRiver, floor, floor, 0, 0, 0, 0, 0, false);
        return new Surface(glacial(x, z, water, t) ? glacialLake : river, floor, floor, 0, 0, 0, 0, 0, false);
    }

    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> glacierFed = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether a lake is milky with glacial rock flour: one with glaciers or snowfields within about
     * eight kilometres, as Lake Louise, Moraine Lake and Pukaki have, or a cold lake high on a plateau,
     * as Namtso and the Tibetan lakes are. Judged for 128-block cells from the land cover map.
     */
    private boolean glacial(int x, int z, int water, double t) {
        if (t > 9) return false;
        if (water > SEA + 3500 && t < 3) return true;
        return glacierFed(x, z);
    }

    /** Whether glaciers or snowfields lie within about eight kilometres, judged for 128-block cells. */
    private boolean glacierFed(int x, int z) {
        if (cover == null) return false;
        long key = (long) (x >> 7) << 32 | (z >> 7) & 0xFFFFFFFFL;
        Boolean known = glacierFed.get(key);
        if (known != null) return known;
        int cx = (x & ~127) + 64, cz = (z & ~127) + 64;
        boolean fed = false;
        for (int radius = 2000; radius <= 8000 && !fed; radius *= 2)
            for (int k = 0; k < 8 && !fed; k++)
                fed = cover(cx + RING_X[k] * radius, cz + RING_Z[k] * radius, 64) == WorldCoverSource.SNOW_AND_ICE;
        if (glacierFed.size() > 1 << 16) glacierFed.clear();
        glacierFed.putIfAbsent(key, fed);
        return fed;
    }

    /** Whether water lies close to the open sea, as a lagoon inside a reef or a barrier island does. */
    private boolean lagoon(int x, int z) {
        // A lagoon is wide water: ponds and fish farms on a delta are not.
        int level = waterY(x, z), wide = 0;
        int[] at = new int[2];
        for (int k = 0; k < 8; k++) {
            ground(x + (int) (RING_X[k] * 150), z + (int) (RING_Z[k] * 150), at);
            if (at[1] == level && at[0] < level) wide++;
        }
        if (wide < 5) return false;
        int sea = 0;
        for (int radius = 1500; radius <= 3000; radius += 1500)
            for (int k = 0; k < 8; k++) if (waterY(x + (int) (RING_X[k] * radius), z + (int) (RING_Z[k] * radius)) == SEA && ++sea >= 2) return true;
        return false;
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

    /** Not a WorldCover class: open parkland with scattered trees, where a town stood in the woods. */
    private static final int PARKLAND = 1;

    /**
     * What a WorldCover class puts on dry ground in this climate, or null for classes that are not
     * ground cover. Steep ground stays bare rock whatever is mapped on it, and sheds its snow.
     */
    @Nullable private Surface covered(int code, int x, int z, int y, Local local, double steep, int roll, int slope) {
        double t = local.temperature();
        ClimateZone zone = local.zone();
        boolean cliff = steep > 1.25;
        // Rock breaks out of steep slopes in outcrops and ledges a few metres across, more of it the steeper they are,
        // rather than in single blocks speckled through the cover.
        double crag = patches.getValue(x / 11.0 + 3.3, z / 11.0 - 9.1);
        BlockState rocky = steep > 0.8 && crag < (steep - 0.8) * 1.6 - 0.2 ? STONE : null;
        // Patches tens of metres across: clearings, rock and scree that the 10 m map averages away.
        // Warped, with a finer layer and a grain-by-grain jitter, so the patches are ragged and their edges break up,
        // rather than one smooth noise whose contours read as a maze of hard-edged blobs from above.
        double wx = x / 31.0 + 0.7 * patches.getValue(x / 70.0 + 41.3, z / 70.0 - 7.7), wz = z / 31.0 + 0.7 * patches.getValue(x / 70.0 - 13.9, z / 70.0 + 28.1);
        double patch = 0.8 * patches.getValue(wx, wz) + 0.25 * patches.getValue(x / 7.0 + 5.5, z / 7.0 - 2.2)
                + ((mix(x, 0x50415443L, z) & 1023) / 511.5 - 1) * 0.18;
        boolean dry = zone == ClimateZone.COLD_DESERT || zone == ClimateZone.COLD_STEPPE;
        // Heather moorland where summers are cool and the rain never stops, as in Scotland, Ireland and Norway's coast.
        boolean moor = (zone == ClimateZone.OCEANIC || zone == ClimateZone.SUBPOLAR_OCEANIC) && local.warmest() < 14 && local.rain() > 800;
        // Young lava: black basalt flows and cinders, as on Hawaii and Iceland, where WorldCover maps bare ground.
        if (code == WorldCoverSource.MOSS_AND_LICHEN && volcanic(x, z) && !(patch > 0.3)) {
            // Moss on old lava: grey-green moss only in its hollows, black cinders and brown tuff between.
            BlockState cinder = !weathered(x, z, t) && roll < 50 ? BLACKSTONE : roll < 75 ? COARSE_DIRT : BASALT;
            return new Surface(stonyPeaks, patch > 0 ? MOSS : patch < -0.4 ? TUFF : cinder, BASALT, 0, 0, 0, 0, 0, false);
        }
        if (code == WorldCoverSource.BARE && volcanic(x, z)) {
            // Fresh flows, as on Hawaii, are black; Iceland's older lava and ash deserts are grey and brown.
            BlockState top = weathered(x, z, t)
                    ? patch < -0.3 ? TUFF : roll < 12 ? BLACKSTONE : roll < 60 ? BASALT : roll < 80 ? TUFF : COARSE_DIRT
                    : patch < -0.55 && roll < 60 ? TUFF : roll < 55 ? BLACKSTONE : BASALT;
            return new Surface(stonyPeaks, top, BASALT, 0, 0, 0, 0, 0, false);
        }
        // Australia's red centre: red sand under the spinifex and saltbush, and red rock wherever it stands bare, as Uluru does.
        boolean outback = (zone == ClimateZone.HOT_DESERT || zone == ClimateZone.HOT_STEPPE) && outback(x, z);
        if (outback && code != WorldCoverSource.WATER && code != WorldCoverSource.WETLAND) {
            if (steep > 0.9) return new Surface(badlands, roll < 65 ? ORANGE_TERRACOTTA : Math.floorMod(y, 7) < 2 ? RED_TERRACOTTA : RED_SANDSTONE,
                    RED_SANDSTONE, 0, 0, 0, 0, 0, false);
            if (code == WorldCoverSource.SHRUBLAND || code == WorldCoverSource.BARE || code == WorldCoverSource.BUILT_UP)
                return new Surface(desert, RED_SAND, RED_SANDSTONE, 0, 0, 0, 40, 40, false);
            if (code == WorldCoverSource.GRASSLAND && (patch > 0.1 || roll < 40)) return new Surface(savanna, RED_SAND, RED_SANDSTONE, 0, 0, 0, 60, 60, false);
        }
        // The ecoregion, where it knows more than climate and land cover: flooded grasslands such as the Everglades and the
        // Pantanal, mangrove coasts, and the American South's bayous and pine flatwoods.
        int eco = ecoregion(x, z);
        // Puna, páramo and Afro-alpine heath above 3,000 m, as on the Altiplano and Kilimanjaro's Shira plateau:
        // treeless tussock grass, bare dust and scree.
        if (eco != 0 && y > SEA + 3000 && code != WorldCoverSource.SNOW_AND_ICE && code != WorldCoverSource.WATER
                && code != WorldCoverSource.CROPLAND && Ecoregions.get().biome(eco) == Ecoregions.MONTANE_GRASSLAND) {
            if (cliff) return rock();
            // Mostly grass, with dust and scree in patches whose edges break up grain by grain.
            BlockState top = rocky != null ? rocky : patch > 0.45 + roll * 0.003 ? GRAVEL : patch < -0.3 - roll * 0.003 || roll < 6 ? COARSE_DIRT : GRASS;
            return new Surface(windsweptHills, top, top == GRASS ? DIRT : STONE, 0, 0, 0, 260, 262, false);
        }
        boolean ground = code == WorldCoverSource.TREE_COVER || code == WorldCoverSource.GRASSLAND || code == WorldCoverSource.SHRUBLAND
                || code == WorldCoverSource.WETLAND || code == PARKLAND;
        if (eco != 0 && ground && !cliff && steep < 0.4 && t >= 10) {
            int biome = Ecoregions.get().biome(eco);
            if (biome == Ecoregions.MANGROVE && y <= SEA + 4) return covered(WorldCoverSource.MANGROVES, x, z, y, local, steep, roll, slope);
            if (biome == Ecoregions.FLOODED_GRASSLAND) {
                // A frost-free marsh coast, as the Everglades' Florida Bay and Ten Thousand Islands: mangrove islands among the creeks.
                if (mangroveClimate(local) && y <= SEA + 1 && patch > 0.4 && lagoon(x, z))
                    return new Surface(mangroveSwamp, MUD, MUD, 45, 0, 0, 0, 0, false, Tree.MANGROVE);
                // Sloughs and ponds: standing water among the sawgrass, the more of it the wetter the climate, so that the
                // Everglades are a river of grass and water, the Pantanal less so, and the Okavango's dry edges little.
                double wet = patches.getValue(x / 90.0 + 41.7, z / 90.0 - 8.3) + patches.getValue(x / 23.0 - 5.1, z / 23.0 + 17.9) * 0.3;
                if (steep < 0.25 && wet > Mth.clamp(0.3 - (local.rain() - 800) / 2400, 0.05, 0.4))
                    return new Surface(swamp, POOL, MUD, 0, 0, 0, 0, 0, false);
                // Open marsh with tree islands where the map has trees.
                if (code == WorldCoverSource.TREE_COVER) return new Surface(swamp, patch > 0.45 ? MUD : GRASS, DIRT, 12, 0, 0, 400, 420, false);
                if (code != WorldCoverSource.WETLAND) return covered(WorldCoverSource.WETLAND, x, z, y, local, steep, roll, slope);
            }
            boolean temperate = biome == Ecoregions.TEMPERATE_BROADLEAF || biome == Ecoregions.TEMPERATE_GRASSLAND;
            if (code == WorldCoverSource.TREE_COVER && temperate && t >= 16 && local.rain() > 1100) {
                // Warm, wet temperate lowlands' broad flat floodplains are cypress and tupelo swamp, as on the Mississippi,
                // in the Carolinas' low country and on the Paraná delta.
                if (y < SEA + 40 && steep < 0.25 && patch > -0.2 && floodplain(x, z))
                    return new Surface(swamp, patch > 0.5 ? MUD : GRASS, DIRT, 45, 0, 0, 260, 280, false);
                // Warm temperate savanna woods: tall pines standing apart over grass, as in the South's longleaf flatwoods.
                if (biome == Ecoregions.TEMPERATE_GRASSLAND) return new Surface(plains, GRASS, DIRT, 30, 10, 0, 300, 320, false);
            }
        }
        switch (code) {
            case WorldCoverSource.TREE_COVER -> {
                // Forest climbs steep slopes, rooted in duff and stony soil; only the steepest faces are bare.
                if (steep > 1.7) return rock();
                Surface s = forest(local, patch, x, z, y);
                if (steep > 1.3 && crag < (steep - 1.3) * 2 - 0.3) return new Surface(s.biome(), STONE, STONE, 0, 0, 0, 0, 0, false);
                // Duff in patches, grass between, so from afar the slope stays green rather than speckled brown.
                if (steep > 0.8) return new Surface(s.biome(), patch > 0.35 ? PODZOL : GRASS, DIRT, s.trees() * 3 / 4, s.spruce(), s.birch(),
                        s.grass() / 2, s.flowers() / 2, s.ferns(), s.broadleaf());
                return s;
            }
            case PARKLAND -> {
                if (cliff) return rock();
                Surface s = forest(local, patch, x, z, y);
                if (rocky != null) return new Surface(stonyPeaks, rocky, STONE, 0, 0, 0, 0, 0, false);
                return new Surface(s.biome(), GRASS, DIRT, Math.min(s.trees(), 12), s.spruce(), s.birch(), 330, 360, s.ferns(), s.broadleaf());
            }
            case WorldCoverSource.SHRUBLAND -> {
                if (cliff || rocky != null && t < -3) return rock();
                BlockState top = rocky != null ? rocky : patch > 0.4 || roll < 20 ? COARSE_DIRT : GRASS;
                int trees = slope > 6 || top != GRASS ? 0 : 1;
                if (moor) return moor(patch, roll, rocky);
                // Krummholz and dwarf pine below the treeline of temperate mountains, as in the Japanese Alps.
                if (subalpine(y, local) && !montaneGrassland(x, z)) return new Surface(taiga, top, DIRT, 40 * trees, 10, 0, 250, 270, true);
                return switch (zone) {
                    case RAINFOREST -> new Surface(sparseJungle, top, DIRT, 15 * trees, 0, 0, 320, 340, true, Tree.JUNGLE);
                    case TROPICAL_DRY, HOT_STEPPE -> new Surface(patch > 0.5 ? windsweptSavanna : savanna, top, DIRT, 7 * trees, 0, 0, 320, 330, false, Tree.ACACIA);
                    // Desert scrub: bare sand and gravel with dead bushes, and cacti where they grow wild, in the Americas.
                    case HOT_DESERT -> new Surface(desert, rocky != null ? rocky : patch > 0.2 ? COARSE_DIRT : SAND, SANDSTONE, 0, 0, 0, 25, americas(x, z) ? 31 : 25, false);
                    // Sagebrush and saltbush: mostly bare tan ground with low grey shrubs, grass only in patches.
                    case COLD_DESERT, COLD_STEPPE -> t < -3 ? new Surface(snowyPlains, top, DIRT, 0, 0, 0, 200, 210, false)
                            : sagebrush(rocky != null ? rocky : patch > 0.3 || roll < 55 ? scrubGround(x, z) : GRASS, trees);
                    // Chaparral and maquis: dry where summers are hot.
                    // Maquis and garrigue: oaks and pines among the scrub, never acacias.
                    case MEDITERRANEAN -> new Surface(plains, top, DIRT, 9 * trees, t < 10 ? 5 : 0, 0, 330, 350, false);
                    case SUBPOLAR_OCEANIC -> new Surface(t < -3 ? snowyPlains : plains, top, DIRT, 6 * trees, 4, 5, 250, 260, false);
                    // Boreal scrub and bog: dark taiga ground, moss and podzol, stunted spruce.
                    case SUBARCTIC -> t < -3 ? new Surface(snowyPlains, top, DIRT, 0, 0, 0, 250, 260, false) : boreal(top, patch, trees);
                    case TUNDRA, ICE -> new Surface(t < -3 ? snowyPlains : meadow, top, DIRT, 0, 0, 0, 250, 300, false);
                    // Heath with azaleas in half of the humid temperate scrub.
                    default -> new Surface(plains, top, DIRT, 9 * trees, t < 6 ? 6 : 0, 1, 330, 350, false, patch < 0 ? Tree.AZALEA : Tree.OAK);
                };
            }
            case WorldCoverSource.CROPLAND -> {
                if (cliff) return rock();
                // Farmland in a dry climate is watered, as along the Nile, the Indus and in the Imperial Valley: lush and green.
                if (dry || zone == ClimateZone.HOT_DESERT || zone == ClimateZone.HOT_STEPPE)
                    return new Surface(plains, rocky != null ? rocky : GRASS, DIRT, 2, 0, 0, 340, 360, false);
                // Fields lying fallow or ploughed, in broad patches a few hundred metres across with ragged edges, never in straight lines.
                double fallow = patches.getValue(x / 260.0 + 31.7, z / 260.0 - 12.3) + patches.getValue(x / 17.0 - 5.1, z / 17.0 + 77.9) * 0.2;
                // On dry-farmed plains, such as Kansas, much of the land is stubble and bare soil; in wetter farmland little,
                // and in monsoon rice country, such as the Mekong delta, none: the paddies are green or flooded.
                double bare = local.rain() < 900 || zone == ClimateZone.MEDITERRANEAN ? -0.25 : local.rain() > 1500 && t > 18 ? 9 : 0.62;
                BlockState top = rocky != null ? rocky : fallow > bare ? COARSE_DIRT : GRASS;
                if (t < -3) return new Surface(snowyPlains, top, DIRT, 0, 0, 0, 160, 170, false);
                // Monsoon rice country is green, not savanna olive with acacias.
                if (bare > 1) return new Surface(plains, top, DIRT, 1, 0, 0, 330, 360, false);
                return grassland(top, x, z, local, patch, moor, roll, rocky);
            }
            case WorldCoverSource.GRASSLAND -> {
                if (cliff || rocky != null && t < -3) return rock();
                BlockState top = rocky != null ? rocky : GRASS;
                if (t < -3) return new Surface(snowyPlains, top, DIRT, 0, 0, 0, 160, 170, false);
                // Cold steppe, as in Mongolia and the Great Basin: tufts of grass on bare ground, about a third grass.
                if (dry && top == GRASS && (patch > 0.35 || roll < 60)) return sagebrush(scrubGround(x, z), 0);
                return grassland(top, x, z, local, patch, moor, roll, rocky);
            }
            case WorldCoverSource.BARE -> {
                // Bare ground low by the sea is beach and dune, as Whitehaven's silica sand, not rock: the beach rule
                // above reaches only two blocks up and four in from the water.
                if (!cliff && steep < 0.6 && y <= SEA + BEACH_RISE && !volcanic(x, z) && seaWithin(x, z, BEACH_REACH))
                    return new Surface(t < 0 ? snowyBeach : beach, SAND, SAND, 0, 0, 0, 0, 0, false);
                boolean hot = zone == ClimateZone.HOT_DESERT || zone == ClimateZone.HOT_STEPPE || zone == ClimateZone.TROPICAL_DRY;
                // Dry lands' cliffs show their rock in coloured bands, as the Colorado Plateau's and the Sahara's do.
                boolean arid = hot || dry;
                // Bare ground in wet lowlands is cleared land (quarries, airfields, building sites): it goes back to grass.
                if (!arid && !cliff && steep < 0.7 && local.rain() > 700 && y < SEA + 300 && t > 0)
                    return covered(WorldCoverSource.GRASSLAND, x, z, y, local, steep, roll, slope);
                if (arid && (cliff || steep > 0.9)) return new Surface(badlands, band(y), TERRACOTTA, 0, 0, 0, 0, 0, false);
                // The Colorado Plateau's red rock: slickrock and red sand on the flats, as in Monument Valley.
                if (arid && redRock(x, z)) {
                    if (steep > 0.4) return new Surface(badlands, patch > 0.1 ? ORANGE_TERRACOTTA : TERRACOTTA, TERRACOTTA, 0, 0, 0, 0, 0, false);
                    return new Surface(desert, RED_SAND, RED_SANDSTONE, 0, 0, 0, 0, 0, false);
                }
                // Salt pans on the floors of dry basins, such as Badwater and the Bonneville flats: white crust.
                if (arid && steep < 0.2 && saltPan(x, z)) return new Surface(desert, roll < 12 ? DIORITE : CALCITE, CALCITE, 0, 0, 0, 0, 0, false);
                if (hot) {
                    // Ergs: stretches of orange sand and of pale a few hundred metres across, as much of each as the region
                    // has, mixed grain by grain where they meet so there are no hard edges.
                    double ochre = patches.getValue(x / 160.0 + 57.1, z / 160.0 - 23.9);
                    double share = redShare(x, z), red = Mth.clamp(share + ochre * Math.min(1.4, share * 2.8), 0, 1);
                    BlockState sand = roll < red * 100 ? RED_SAND : SAND;
                    return new Surface(desert, sand, sand == RED_SAND ? RED_SANDSTONE : SANDSTONE, 0, 0, 0, 0, 0, false);
                }
                // Up to the steepness of a one-block step, so terrace edges in the elevation data don't outline the flats in stone.
                if (dry && !cliff && steep <= 0.9) {
                    // Cold deserts and steppe beaches: gravel plains and dunes.
                    // Stretches of sand, gravel and dust a few hundred metres across, mixed grain by grain where they meet,
                    // so the desert reads as soft-edged washes rather than a maze of blobs.
                    double ux = x / 380.0 + 0.8 * patches.getValue(x / 900.0 - 2.1, z / 900.0 + 6.6), uz = z / 380.0 + 0.8 * patches.getValue(x / 900.0 + 9.4, z / 900.0 - 3.8);
                    double wash = 0.8 * patches.getValue(ux + 7.7, uz - 19.3) + patches.getValue(x / 60.0 - 3.9, z / 60.0 + 4.4) * 0.3;
                    int grain = (int) Math.floorMod(mix(x, 0x57415348L, z), 100);
                    BlockState top = grain < Mth.clamp(50 + wash * 120, 0, 100) ? SAND : grain < Mth.clamp(80 + wash * 60, 0, 100) ? GRAVEL : COARSE_DIRT;
                    return new Surface(t < -3 && local.warmest() < 5 ? snowyPlains : desert, top, top == SAND ? SANDSTONE : STONE, 0, 0, 0, 0, 0, false);
                }
                // High mountains' rock, moraine and scree are bare: WorldCover maps their glaciers and snowfields apart.
                // Only far above the snow line do gentle slopes keep snow between the rocks.
                if (local.warmest() < -6 && steep < 0.5 && patch > 0.1) return new Surface(snowySlopes, SNOW_BLOCK, STONE, 0, 0, 0, 0, 0, false);
                boolean scree = local.rain() < 600 || t < 0;
                BlockState top = cliff || steep > 0.7 ? STONE : patch > 0.55 ? (scree ? GRAVEL : CALCITE) : patch > 0.3 ? GRAVEL
                        : patch < -0.3 ? ANDESITE : roll < (scree ? 50 : 70) ? STONE : roll < 85 ? COARSE_DIRT : GRAVEL;
                if (top == STONE && paleRock(x, z)) top = roll < 55 ? DIORITE : ANDESITE;
                return new Surface(stonyPeaks, top, STONE, 0, 0, 0, 0, 0, false);
            }
            case WorldCoverSource.SNOW_AND_ICE -> {
                // The map's snow and ice is snow: only faces too steep to hold it show their rock.
                // Drifts of powder snow lie on the gentler slopes.
                // Judged on the survey's slope over a few blocks, not the block steps, which detail roughens.
                double face = surveySlope(x, z);
                if (face > 1.6 || face > 1.3 && patch > 0.3) {
                    // A glacier's calving front and icefalls low down are blue-white ice; high faces are rock.
                    if (y < SEA + 600) return new Surface(frozenPeaks, BLUE_ICE, PACKED_ICE, 0, 0, 0, 0, 0, false);
                    return rock(x, z, roll);
                }
                BlockState top = steep < 0.5 && patch > 0.5 ? POWDER_SNOW : SNOW_BLOCK;
                return new Surface(steep > 0.75 ? frozenPeaks : snowySlopes, top, PACKED_ICE, 0, 0, 0, 0, 0, false);
            }
            case WorldCoverSource.WETLAND -> {
                if (t < 0) return new Surface(snowyPlains, patch > 0.25 ? MOSS : GRASS, DIRT, 2, 10, 0, 300, 310, true);
                // Warm marshes, such as the Pantanal and the Mississippi delta, are open grass and reeds with a few trees.
                if (t >= 18) return new Surface(swamp, patch > 0.45 ? MUD : GRASS, DIRT, 2, 0, 0, 450, 470, false);
                return new Surface(swamp, patch > 0.25 ? MUD : GRASS, DIRT, 10, 0, 0, 300, 320, true);
            }
            case WorldCoverSource.MANGROVES -> {
                // Mangroves grow only on tidal coasts; inland swamps mapped as mangrove, as in the Okavango, are freshwater marsh.
                // Nor north or south of the mangroves' range, as on the Mississippi delta, unless the ecoregion is mangrove.
                if (y > SEA + 12 || !mangroveClimate(local) && !mangroveEcoregion(x, z))
                    return covered(WorldCoverSource.WETLAND, x, z, y, local, steep, roll, slope);
                return new Surface(mangroveSwamp, MUD, MUD, 45, 0, 0, 0, 0, false, Tree.MANGROVE);
            }
            case WorldCoverSource.MOSS_AND_LICHEN -> {
                if (cliff || rocky != null && t < -2) return rock();
                if (moor) return moor(patch, roll, rocky);
                BlockState top = rocky != null ? rocky : patch > 0.2 ? MOSS : roll < 30 ? GRAVEL : GRASS;
                // Arctic tundra lies under snow; high mountain lichen fields, warm for a few weeks each summer, mostly do not.
                boolean snowy = t < -2 && (local.warmest() < 2 || Math.abs(latitude(z)) > 55);
                return new Surface(snowy ? snowyPlains : meadow, top, DIRT, 0, 0, 0, top == GRASS ? 200 : 0, top == GRASS ? 230 : 0, false);
            }
            default -> { return null; }
        }
    }

    /**
     * Whether open ground is in a temperate mountain's subalpine belt, where conifers grow up to the
     * treeline: cool summers but warm enough for trees, in a climate that is not dry. A height of -1
     * skips the height test.
     */
    private boolean subalpine(int y, Local local) {
        ClimateZone zone = local.zone();
        boolean humid = zone == ClimateZone.SUBTROPICAL || zone == ClimateZone.OCEANIC || zone == ClimateZone.CONTINENTAL
                || zone == ClimateZone.MEDITERRANEAN || zone == ClimateZone.RAINFOREST;
        return humid && local.warmest() >= ClimateZone.TUNDRA_WARMEST && local.warmest() < 14 && local.temperature() < 6 && (y < 0 || y > SEA + 1000);
    }

    /** Bare rock in a biome where snow does not lie, for faces too steep to hold it. */
    private Surface rock() { return new Surface(stonyPeaks, STONE, STONE, 0, 0, 0, 0, 0, false); }

    /** Rise per run of the elevation survey across eight blocks either way. */
    private double surveySlope(int x, int z) {
        GlobalElevation dem = elevation[WATER_ZOOM];
        double dx = dem.metres(x + 8.5, z + 0.5) - dem.metres(x - 7.5, z + 0.5), dz = dem.metres(x + 0.5, z + 8.5) - dem.metres(x + 0.5, z - 7.5);
        return Math.sqrt(dx * dx + dz * dz) / (16 * grid.groundMetresPerBlock(z));
    }

    /** Bare rock, pale granite where the mountains are granite. */
    private Surface rock(int x, int z, int roll) {
        return paleRock(x, z) ? new Surface(stonyPeaks, roll < 55 ? DIORITE : ANDESITE, STONE, 0, 0, 0, 0, 0, false) : rock();
    }

    /** Open grass in a climate: savanna, steppe, meadow, prairie or moor. */
    private Surface grassland(BlockState top, int x, int z, Local local, double patch, boolean moor, int roll, @Nullable BlockState rocky) {
        if (moor) return moor(patch, roll, rocky);
        // Subalpine meadows among conifers.
        if (subalpine(-1, local) && top == GRASS && !montaneGrassland(x, z)) return new Surface(taiga, top, DIRT, 12, 10, 0, 300, 340, true);
        return switch (local.zone()) {
            // Savanna and steppe grass, dry for much of the year.
            // Where the dry season is long, as on the Serengeti, the grass is grazed and burnt down to bare earth in patches.
            case TROPICAL_DRY, HOT_DESERT, HOT_STEPPE -> new Surface(savanna, top == GRASS && local.rain() < 1100 && patch > 0.15 ? COARSE_DIRT : top,
                    DIRT, 2, 0, 0, 380, 390, false, Tree.ACACIA);
            // Junipers on the cold steppes.
            case COLD_DESERT, COLD_STEPPE -> new Surface(savanna, top, DIRT, 2, 10, 0, 380, 390, false);
            // Mediterranean meadows are parched brown by summer: as much bare earth as grass.
            case MEDITERRANEAN -> new Surface(plains, top == GRASS && patch > -0.1 ? COARSE_DIRT : top, DIRT, 1, 0, 0, 300, 320, false);
            // Rainforest clearings and floodplain meadows: jungle ground among scattered trees, not open prairie.
            case RAINFOREST -> new Surface(sparseJungle, top, DIRT, 5, 0, 0, 380, 400, true, Tree.JUNGLE);
            // Alpine and arctic meadows.
            case TUNDRA, ICE -> new Surface(meadow, top, DIRT, 0, 0, 0, 300, 360, false);
            case SUBPOLAR_OCEANIC -> new Surface(plains, top, DIRT, 1, 10, 0, 330, 350, false);
            case SUBARCTIC -> boreal(top, patch, top == GRASS ? 1 : 0);
            // Sunflower fields on the continental steppes and farmland.
            case CONTINENTAL -> patch > 0.45 ? new Surface(sunflowerPlains, top, DIRT, 1, 0, 1, 330, 420, false)
                    : new Surface(plains, top, DIRT, 1, 0, 1, 330, 380, false);
            default -> new Surface(plains, top, DIRT, 1, 0, 1, 330, 380, false);
        };
    }

    /** Open boreal ground, between the forests of the taiga: moss and podzol among dark grass, a few spruces. */
    private Surface boreal(BlockState top, double patch, int trees) {
        BlockState ground = top != GRASS ? top : patch > 0.35 ? MOSS : patch < -0.4 ? PODZOL : GRASS;
        return new Surface(taiga, ground, DIRT, 8 * trees, 10, 1, 300, 320, true);
    }

    /** Heather moor: dark, brown ground of peat and podzol among the grass, with ferns, few flowers and fewer trees. */
    private Surface moor(double patch, int roll, @Nullable BlockState rocky) {
        BlockState top = rocky != null ? rocky : patch > 0.55 || roll < 15 ? PODZOL : patch < -0.6 && roll < 50 ? COARSE_DIRT : GRASS;
        return new Surface(taiga, top, DIRT, 1, 10, 0, 330, 345, true);
    }

    /** Sagebrush steppe: dead bushes and tufts on bare dirt, a few junipers. */
    private Surface sagebrush(BlockState top, int trees) { return new Surface(savanna, top, DIRT, 2 * trees, 10, 0, 300, 310, false); }

    /** Bare ground between the shrubs of a dry plain: red sand on the Colorado Plateau, else tan and grey dirt. */
    private BlockState scrubGround(int x, int z) {
        if (redRock(x, z)) return RED_SAND;
        // Pale alkaline soil among the dirt, so from afar the ground reads the tan grey it is.
        return Math.floorMod(mix(x, 0x53414745L, z), 100) < 40 ? LIGHT_GRAY_TERRACOTTA : COARSE_DIRT;
    }

    /** Salt pan cells, in blocks, and whether each is a salt pan. */
    private static final int SALT_CELL = 64;
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> saltPans = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether a column lies on a salt pan: dead flat for a few hundred metres and the lowest ground
     * of its basin, where water gathers and dries away. Plains that only look flat slope away
     * somewhere, and dunes are never flat.
     */
    private boolean saltPan(int x, int z) {
        // Cells blended into their neighbours along a wandering line, so a pan's shore is never a grid of squares.
        double fx = x / (double) SALT_CELL - 0.5 + warpX.getValue(x / 47.0, z / 47.0) * 0.45;
        double fz = z / (double) SALT_CELL - 0.5 + warpZ.getValue(x / 47.0, z / 47.0) * 0.45;
        int ix = Mth.floor(fx), iz = Mth.floor(fz);
        double tx = fx - ix, tz = fz - iz, share = 0;
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++)
            if (panCell(ix + i, iz + j)) share += (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - tz : tz);
        return share > 0.5;
    }

    /** Whether a salt pan cell is pan: flat, and the bottom of its basin. */
    private boolean panCell(int cx, int cz) {
        long key = (long) cx << 32 | cz & 0xFFFFFFFFL;
        Boolean known = saltPans.get(key);
        if (known != null) return known;
        // The far terrain's own survey, whose tiles are loaded anyway: a finer one would fetch tiles kilometres around
        // every far desert cell and hold up the far terrain while they download.
        GlobalElevation dem = elevation[FAR_ZOOM];
        double px = (cx + 0.5) * SALT_CELL, pz = (cz + 0.5) * SALT_CELL;
        // The survey is speckled a few metres up and down even on a dead flat pan, so flatness is judged by the spread.
        double sum = dem.metres(px, pz), squares = sum * sum;
        for (int k = 0; k < 8; k++) {
            double m = dem.metres(px + RING_X[k] * 200, pz + RING_Z[k] * 200);
            sum += m;
            squares += m * m;
        }
        double mean = sum / 9, spread = Math.sqrt(Math.max(0, squares / 9 - mean * mean));
        boolean pan = spread < 1.8;
        // Nothing round about lies lower: the pan is the bottom of its basin, where no water drains away.
        for (int k = 0; k < 8 && pan; k++) if (dem.metres(px + RING_X[k] * 3000, pz + RING_Z[k] * 3000) < mean - 3) pan = false;
        if (saltPans.size() > 1 << 18) saltPans.clear();
        saltPans.putIfAbsent(key, pan);
        return pan;
    }

    /**
     * The world's great young lava fields, which are broad and low and so not told by their shape: Hawaii, Iceland,
     * the Canaries, the Galápagos, Etna and Réunion. Volcanic cones anywhere are found by their shape instead.
     */
    private static final double[][] LAVA = {
            {18.8, 22.3, -160.5, -154.7}, {63.2, 66.6, -24.6, -13.4}, {27.6, 29.5, -18.2, -13.3},
            {-1.5, 0.7, -92.0, -89.0}, {37.6, 37.9, 14.8, 15.2}, {-21.4, -20.8, 55.2, 55.9}};

    private boolean volcanic(int x, int z) {
        GeoPoint p = grid.geographic(x, z);
        for (double[] box : LAVA) if (p.latitude() > box[0] && p.latitude() < box[1] && p.longitude() > box[2] && p.longitude() < box[3]) return true;
        return cone(x, z);
    }

    /** Summits of volcanic cones, by 1024-block cell: x, z and the mean height of the ring round them, or none. */
    private final java.util.concurrent.ConcurrentHashMap<Long, double[]> cones = new java.util.concurrent.ConcurrentHashMap<>();
    private static final double[] NO_CONE = {};

    /**
     * Whether a column is on the upper slopes of a volcanic cone, such as Fuji, Kilimanjaro, Rainier, Etna, Ararat or
     * Popocatépetl: a lone summit standing well over a kilometre above the land five kilometres round it on every side,
     * with no other high ground in that ring, falling on to its foot ten kilometres out. Peaks in ranges, such as
     * the Matterhorn, Everest and Mont Blanc, have high neighbours and ridges that rise again, and fail the test.
     */
    private boolean cone(int x, int z) {
        long key = (long) (x >> 10) << 32 | (z >> 10) & 0xFFFFFFFFL;
        double[] c = cones.get(key);
        if (c == null) {
            double cx = (x & ~1023) + 512, cz = (z & ~1023) + 512, px = cx, pz = cz, top = -1e9;
            for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
                double m = elevation[FAR_ZOOM].metres(cx + i * 2000, cz + j * 2000);
                if (m > top) { top = m; px = cx + i * 2000; pz = cz + j * 2000; }
            }
            double sum = 0, sq = 0, high = -1e9;
            int down = 0;
            for (int k = 0; k < 8; k++) {
                double m = elevation[FAR_ZOOM].metres(px + RING_X[k] * 5000, pz + RING_Z[k] * 5000);
                sum += m; sq += m * m; high = Math.max(high, m);
                // A cone's flanks go on falling to its foot; a range's peaks stand on ridges that rise again.
                if (elevation[FAR_ZOOM].metres(px + RING_X[k] * 10000, pz + RING_Z[k] * 10000) < m) down++;
            }
            double mean = sum / 8, spread = Math.sqrt(Math.max(0, sq / 8 - mean * mean)), drop = top - mean;
            c = drop > 1000 && spread < 0.3 * drop && high < top - 600 && down == 8 ? new double[] {px, pz, mean} : NO_CONE;
            if (cones.size() > 1 << 14) cones.clear();
            cones.putIfAbsent(key, c);
        }
        if (c.length == 0) return false;
        double dx = x - c[0], dz = z - c[1];
        return dx * dx + dz * dz < 5000.0 * 5000.0 && elevation[FAR_ZOOM].metres(x, z) > c[2];
    }

    /** The ecoregion at a column, or 0 off the real Earth or where the map has none. */
    private int ecoregion(int x, int z) {
        Ecoregions map = cover == null ? null : Ecoregions.get();
        if (map == null) return 0;
        GeoPoint p = grid.geographic(x, z);
        return map.ecoregion(p.latitude(), p.longitude());
    }

    /** Whether a column's ecoregion is tropical forest, or unknown. */
    private boolean tropicalForest(int x, int z) {
        int eco = ecoregion(x, z);
        if (eco == 0) return true;
        int biome = Ecoregions.get().biome(eco);
        return biome == Ecoregions.TROPICAL_MOIST || biome == Ecoregions.TROPICAL_DRY || biome == Ecoregions.MANGROVE;
    }

    /**
     * Whether a volcanic column's lava is old and weathered rather than fresh and black: in cold climates, as in
     * Iceland, where frost and moss break it down, and on the cones' scoria and ash slopes, as on Fuji.
     */
    private boolean weathered(int x, int z, double t) {
        return t < 4 || cone(x, z);
    }

    private boolean montaneGrassland(int x, int z) {
        int eco = ecoregion(x, z);
        return eco != 0 && Ecoregions.get().biome(eco) == Ecoregions.MONTANE_GRASSLAND;
    }

    /**
     * Whether winters are mild enough for mangroves: the coldest quarter, estimated from the year's mean and the
     * warmest quarter, at 17 degrees or more: Tampa Bay and south Florida, not the Mississippi delta or Cedar Key, where frost kills them.
     */
    private static boolean mangroveClimate(Local local) {
        return 2 * local.temperature() - local.warmest() >= 17;
    }

    /** Whether ground lies on a broad, level floodplain: the land a few kilometres round is within a few metres of it. */
    private boolean floodplain(int x, int z) {
        double here = elevation[FAR_ZOOM].metres(x, z);
        for (int k = 0; k < 8; k += 2)
            if (Math.abs(elevation[FAR_ZOOM].metres(x + RING_X[k] * 3000, z + RING_Z[k] * 3000) - here) > 8) return false;
        return true;
    }

    private boolean mangroveEcoregion(int x, int z) {
        int eco = ecoregion(x, z);
        return eco != 0 && Ecoregions.get().biome(eco) == Ecoregions.MANGROVE;
    }

    /**
     * Whether a mountain's bare rock is pale, scoured clean by ice, as in the Torres del Paine and the Alps:
     * near glaciers and snowfields, where the rock is fresh and unweathered rather than dark with lichen and stain.
     */
    private boolean paleRock(int x, int z) {
        return glacierFed(x, z);
    }

    /** Whether a column is in Australia's arid interior, the red centre. */
    private boolean outback(int x, int z) {
        GeoPoint p = grid.geographic(x, z);
        return p.latitude() > -32 && p.latitude() < -14 && p.longitude() > 114 && p.longitude() < 150;
    }

    /** Whether a column is on the Colorado Plateau, whose bare ground is red rock and red sand. */
    private boolean redRock(int x, int z) {
        GeoPoint p = grid.geographic(x, z);
        // The Grand Canyon and its rims keep the canyon strata's own colours.
        if (p.latitude() > 35.8 && p.latitude() < 36.7 && p.longitude() < -111.5) return false;
        return p.latitude() > 35 && p.latitude() < 38.5 && p.longitude() > -113 && p.longitude() < -108;
    }

    /** About how much of a hot desert's sand is orange: most in Australia, much in the Sahara and Arabia, little in the Americas. */
    private double redShare(int x, int z) {
        GeoPoint p = grid.geographic(x, z);
        double lat = p.latitude(), lon = p.longitude();
        if (lon > 110 && lon < 155 && lat < -10) return 0.7;
        // Orange ergs: Morocco's and the western Sahara's, the Namib's and Arabia's. Algeria's and Libya's are pale.
        if (lon > -20 && lon < 0 && lat > 10) return 0.5;
        if (lon > 10 && lon < 20 && lat < -15) return 0.5;
        if (lon > 35 && lon < 60 && lat < 32) return 0.5;
        return 0.05;
    }

    /**
     * Temperate broadleaf woods: mostly the given forest, with birch stands, flowery glades and dark,
     * dense old groves, and cherry groves where cherries grow wild, in East Asia and the Himalaya.
     */
    private Surface groves(double patch, int x, int z, Surface usual) {
        if (patch > 0.35) return new Surface(birchForest, GRASS, DIRT, 82, 0, 8, 90, 130, false);
        if (patch > 0.22) return new Surface(flowerForest, GRASS, DIRT, 45, 0, 1, 100, 320, false);
        if (patch < -0.45) return new Surface(darkForest, GRASS, DIRT, 92, 0, 1, 80, 100, false, Tree.DARK_OAK);
        // Cherry groves stand together in a few large stands, with clearings, not speckled through every wood.
        if (patch < 0 && cherries(x, z) && patches.getValue(x / 450.0 + 13.7, z / 450.0 - 71.3) > 0.6) return new Surface(cherryGrove, GRASS, DIRT, 35, 0, 0, 200, 420, false, Tree.CHERRY);
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
        // A tropical climate over an ecoregion that is not tropical forest, as in south Florida: no jungle there.
        if (local.zone() == ClimateZone.RAINFOREST || local.zone() == ClimateZone.TROPICAL_DRY) {
            int eco = ecoregion(x, z), biome = eco == 0 ? Ecoregions.NONE : Ecoregions.get().biome(eco);
            if (biome == Ecoregions.FLOODED_GRASSLAND) return new Surface(swamp, GRASS, DIRT, 40, 0, 0, 260, 280, false);
            if (biome == Ecoregions.TEMPERATE_BROADLEAF || biome == Ecoregions.TEMPERATE_CONIFER || biome == Ecoregions.TEMPERATE_GRASSLAND)
                return new Surface(forest, GRASS, DIRT, 70, 4, 0, 180, 200, false);
        }
        return switch (local.zone()) {
            case RAINFOREST -> patch < -0.5 && bamboo(x, z) ? bambooForest(patch) : new Surface(jungle, GRASS, DIRT, 90, 0, 0, 160, 170, true, Tree.JUNGLE);
            // Monsoon forest where the wet season is long; open woodland with grass between the trees, as in the
            // cerrado and the Pantanal's tree islands, where it is not; acacia savanna woodland where drier still.
            case TROPICAL_DRY -> local.rain() > 1800 ? new Surface(sparseJungle, GRASS, DIRT, 70, 0, 0, 150, 160, true, Tree.JUNGLE)
                    : local.rain() > 1000 ? new Surface(sparseJungle, GRASS, DIRT, 10, 0, 0, 300, 320, false, Tree.JUNGLE)
                    : new Surface(savanna, GRASS, DIRT, 35, 0, 0, 300, 310, false, Tree.ACACIA);
            case HOT_DESERT, HOT_STEPPE -> new Surface(savanna, GRASS, DIRT, 30, 0, 0, 260, 270, false, Tree.ACACIA);
            // Pinyon, juniper and pine on dry uplands.
            case COLD_DESERT, COLD_STEPPE -> t < 6 ? new Surface(taiga, GRASS, DIRT, 45, 10, 0, 200, 210, false)
                    : new Surface(savanna, GRASS, DIRT, 30, 4, 0, 260, 270, false);
            // Conifers on cooler, wetter Mediterranean mountains (the Sierra, the Cascades), oak below.
            case MEDITERRANEAN -> t < 12 ? new Surface(local.rain() > 900 ? oldTaiga : taiga, local.rain() > 900 && patch > 0.3 ? PODZOL : GRASS, DIRT, 80, 8, 0, 110, 120, true)
                    : new Surface(forest, GRASS, DIRT, 70, 1, 0, 140, 170, false);
            case SUBTROPICAL -> patch < -0.62 && bamboo(x, z) ? bambooForest(patch)
                    // Jungle only where the ecoregion is tropical forest, as in south China; Florida's warm woods are oak and pine.
                    : t > 20 && tropicalForest(x, z) ? new Surface(sparseJungle, GRASS, DIRT, 80, 0, 0, 140, 160, true, Tree.JUNGLE)
                    : t > 20 ? new Surface(forest, GRASS, DIRT, 70, 4, 0, 180, 200, false)
                    : groves(patch, x, z, new Surface(forest, GRASS, DIRT, 85, 0, 1, 100, 130, false));
            case OCEANIC -> groves(patch, x, z, new Surface(forest, GRASS, DIRT, 82, t < 8 ? 3 : 0, 2, 90, 130, true));
            case SUBPOLAR_OCEANIC -> new Surface(taiga, GRASS, DIRT, 70, 10, 0, 110, 120, true);
            // Broadleaf in the warmer continental interiors, mixed with birch and spruce further north.
            case CONTINENTAL -> t > 7 ? groves(patch, x, z, new Surface(forest, GRASS, DIRT, 85, 0, 2, 90, 130, false))
                    : patch > 0.25 ? new Surface(birchForest, GRASS, DIRT, 82, 1, 8, 90, 130, false)
                    : patch < -0.25 ? new Surface(taiga, GRASS, DIRT, 82, 10, 0, 110, 120, true)
                    : new Surface(forest, GRASS, DIRT, 82, 5, 2, 90, 120, true);
            // Snowy taiga only where the cold is extreme, so summer taiga is not dotted with snow-field patches.
            case SUBARCTIC -> t < -10 ? new Surface(snowyTaiga, GRASS, DIRT, 65, 10, 0, 90, 100, true)
                    : new Surface(patch > 0.45 && local.rain() > 500 ? oldTaiga : taiga, patch > 0.45 ? PODZOL : GRASS, DIRT, 75, 9, 1, 110, 120, true);
            // Stunted trees at the treeline.
            case TUNDRA, ICE -> new Surface(t < -5 ? snowyTaiga : grove, GRASS, DIRT, 30, 10, 0, 60, 70, false);
        };
    }

    // Data ---------------------------------------------------------------------------------------

    /**
     * Fetches the elevation tiles that cubes and every far level around a point read, several at a
     * time, so generation there never waits on the network. Failures are logged and left for the
     * generator's own retries. Elevation tiles, climate and land cover all load at once rather
     * than one after another, as each spends most of its time waiting on the network.
     */
    @Override public void prepare(int x, int z) {
        record Tile(int zoom, int x, int y) {}
        List<Runnable> loads = new ArrayList<>();
        // Each level's zoom, out to about three quarters of the level's square, as far as level 6 (17.6 km of
        // blocks); the levels beyond load as they come. The water survey reads its own zoom 8.8 km around.
        Map<Integer, Double> zooms = new TreeMap<>();
        for (int level = 1; level <= 6; level++) zooms.merge(zoom(level), 275.0 * (1 << level), Math::max);
        zooms.merge(WATER_ZOOM, 8800.0, Math::max);
        for (Map.Entry<Integer, Double> entry : zooms.entrySet()) {
            int zoom = entry.getKey();
            double reach = entry.getValue();
            int tilesAcross = 1 << zoom;
            int x0 = (int) Math.floor(grid.pixelX(x - reach, zoom) / 256), x1 = (int) Math.floor(grid.pixelX(x + reach, zoom) / 256);
            int y0 = (int) Math.floor(grid.pixelY(z - reach, zoom) / 256), y1 = (int) Math.floor(grid.pixelY(z + reach, zoom) / 256);
            for (int ty = Math.max(0, y0); ty <= Math.min(tilesAcross - 1, y1); ty++) {
                for (int tx = x0; tx <= x1; tx++) {
                    Tile tile = new Tile(zoom, Math.floorMod(tx, tilesAcross), ty);
                    loads.add(() -> {
                        try { ground.tile(tile.zoom, tile.x, tile.y); }
                        catch (RuntimeException failure) { EarthPlatformMod.LOGGER.warn("Could not prefetch elevation tile {}/{}/{}: {}", tile.zoom, tile.x, tile.y, failure.getMessage()); }
                    });
                }
            }
        }
        if (climate != null) {
            GeoPoint a = grid.geographic(x - 24000, z + 24000), b = grid.geographic(x + 24000, z - 24000);
            loads.add(() -> {
                try { climate.prefetch(a.latitude(), a.longitude(), b.latitude(), b.longitude()); }
                catch (RuntimeException failure) { EarthPlatformMod.LOGGER.warn("Could not prefetch climate: {}", failure.getMessage()); }
            });
        }
        if (rivers != null) {
            GeoPoint a = grid.geographic(x - 3500, z + 3500), b = grid.geographic(x + 3500, z - 3500);
            loads.add(() -> {
                try { rivers.prefetch(a.latitude(), a.longitude(), b.latitude(), b.longitude()); }
                catch (RuntimeException failure) { EarthPlatformMod.LOGGER.warn("Could not prefetch rivers: {}", failure.getMessage()); }
            });
        }
        if (imagery != null) {
            // Imagery: full detail near by, coarser zooms further out, as the far levels read them.
            double[] imageryReaches = {3500, 7000, 14000, 24000};
            for (int k = 0; k < imageryReaches.length; k++) {
                double reach = imageryReaches[k];
                int zoom = ImagerySource.MAX_ZOOM - k;
                loads.add(() -> {
                    try { imagery.prefetch(grid, x - reach, z - reach, x + reach, z + reach, zoom); }
                    catch (RuntimeException failure) { EarthPlatformMod.LOGGER.warn("Could not prefetch imagery: {}", failure.getMessage()); }
                });
            }
        }
        if (cover != null) {
            // Land cover: full detail near by, overviews further out, as the far levels read them.
            double[] reaches = {3500, 7000, 24000};
            for (int overview = 0; overview < reaches.length; overview++) {
                double reach = reaches[overview];
                int level = overview;
                GeoPoint a = grid.geographic(x - reach, z + reach), b = grid.geographic(x + reach, z - reach);
                loads.add(() -> {
                    try { cover.prefetch(a.latitude(), a.longitude(), b.latitude(), b.longitude(), level); }
                    catch (RuntimeException failure) { EarthPlatformMod.LOGGER.warn("Could not prefetch land cover: {}", failure.getMessage()); }
                });
            }
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Runnable load : loads) pool.execute(load);
        }
    }
}
