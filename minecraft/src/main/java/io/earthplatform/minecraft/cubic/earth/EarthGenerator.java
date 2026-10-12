package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.GlobalElevation;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * is added by noise, more on steep ground, none on beaches. Without land cover data yet, climate
 * comes from latitude and height and wetness from broad noise.
 */
public final class EarthGenerator extends SurfaceGenerator {
    public static final String ID = "earth-v1";
    /** Top water block of the sea. */
    public static final int SEA = 62;
    private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState(),
            SANDSTONE = Blocks.SANDSTONE.defaultBlockState();

    private final EarthSettings settings;
    private final MercatorGrid grid;
    private final TerrariumTileSource tiles;
    private final GlobalElevation[] elevation = new GlobalElevation[TerrariumTileSource.MAX_ZOOM + 1];
    private final SimplexNoise relief, crags, wetBroad, wetLocal, warmth;
    private final Holder<Biome> ocean, deepOcean, coldOcean, deepColdOcean, frozenOcean, lukewarmOcean, warmOcean,
            beach, snowyBeach, stonyShore, snowySlopes, frozenPeaks, stonyPeaks, meadow, taiga, oldTaiga, forest,
            plains, jungle, desert, savanna;

    private record Noises(RandomSource random, SimplexNoise[] noise) {
        static Noises of(long seed) {
            RandomSource random = new LegacyRandomSource(seed ^ 0x45415254485631L);
            SimplexNoise[] noise = new SimplexNoise[5];
            for (int i = 0; i < noise.length; i++) noise[i] = new SimplexNoise(random);
            return new Noises(random, noise);
        }
    }

    public EarthGenerator(long seed, Registry<Biome> biomes, EarthSettings settings, TerrariumTileSource tiles) {
        this(seed, biomes, settings, tiles, Noises.of(seed));
    }

    private EarthGenerator(long seed, Registry<Biome> biomes, EarthSettings settings, TerrariumTileSource tiles, Noises noises) {
        super(seed, biomes, noises.random);
        this.settings = settings;
        this.grid = new MercatorGrid(settings.metresPerBlock());
        this.tiles = tiles;
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
    }

    @Override public String id() { return ID; }
    @Override public int seaLevel() { return SEA; }
    @Override protected int shoreLevel() { return SEA; }
    @Override protected boolean emeralds(int maxGround) { return maxGround > SEA + 1800; }
    @Override public boolean barren(int x, int z, int y) { return temperature(x, z, y) < -3; }
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

    @Override public String credits() { return "Elevation: AWS Terrain Tiles (USGS 3DEP, SRTM, GMTED2010, ETOPO1 and others)"; }

    // Height -------------------------------------------------------------------------------------

    /** Zoom of the coarsest elevation the far levels read. */
    private static final int FAR_ZOOM = 11;
    /** Source zoom for a far level: about one source pixel per voxel. */
    private static int zoom(int level) { return level <= 2 ? TerrariumTileSource.MAX_ZOOM : Math.max(FAR_ZOOM, 17 - level); }

    @Override protected void ground(int x, int z, int[] out) { farGround(1, x, z, out); }

    @Override protected void farGround(int level, int x, int z, int[] out) {
        double[] sample = new double[3];
        double metres = elevation[zoom(level)].sample(x + 0.5, z + 0.5, sample);
        place(x, z, metres, sample, 1 << level, out);
    }

    /** Ground and water of a column from its elevation, slope and sea share. */
    private void place(int x, int z, double metres, double[] sample, int voxel, int[] out) {
        if (sample[2] > 0.5) {
            out[0] = Math.min(SEA - 1, Mth.floor(SEA + 0.5 + metres));
            out[1] = SEA;
            return;
        }
        double steep = Math.min(2.5, Math.sqrt(sample[0] * sample[0] + sample[1] * sample[1]));
        double amplitude = Mth.clamp((metres - 0.5) / 4, 0, 1) * (0.6 + 1.8 * steep), detail = 0;
        if (voxel <= 8) detail += fbm(relief, x / 27.0, z / 27.0, 3) * amplitude;
        if (voxel <= 2) detail += (0.5 - Math.abs(crags.getValue(x / 9.0, z / 9.0))) * amplitude * 0.6 * Math.min(1, steep);
        int y = Mth.floor(SEA + 0.5 + metres + detail);
        // Ground beside the sea never dips under its level, so the sea stays where it is.
        if (sample[2] > 0.02 && y < SEA) y = SEA;
        out[0] = y;
        out[1] = DRY;
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

    private double wetness(int x, int z) { return fbm(wetBroad, x / 9000.0, z / 9000.0, 2) * 0.7 + wetLocal.getValue(x / 1400.0, z / 1400.0) * 0.3; }

    private boolean nearSea(int x, int z) {
        return waterY(x + 4, z) == SEA || waterY(x - 4, z) == SEA || waterY(x, z + 4) == SEA || waterY(x, z - 4) == SEA;
    }

    @Override protected Surface surface(int x, int z, int y, int water, int slope) {
        double t = temperature(x, z, y);
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
    }
}
