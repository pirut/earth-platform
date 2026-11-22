package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.ClimateSource;
import io.earthplatform.geography.globe.GlobeProjection;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Runtime bridge between Earth Platform's cubic Earth worlds and ProtoManly's Weather mixins.
 *
 * <p>Kept free of NeoForge and client-only imports so the common module compiles cleanly for both
 * NeoForge and Fabric. Never triggers disk or network climate fetches on game ticks.
 */
public final class PMWeatherCompat {
    /** Standard troposphere lapse-rate denominator (1 / 0.0065 C per metre). */
    public static final float STANDARD_LAPSE_DENOMINATOR = 153.84616f;

    private static volatile int monthLength = 8;

    private PMWeatherCompat() {}

    /** Updated when PM Weather loads its server config so seasonal phase stays aligned. */
    public static void setMonthLength(int daysPerMonth) {
        if (daysPerMonth > 0) monthLength = daysPerMonth;
    }

    public static int monthLength() { return monthLength; }

    /**
     * Year fraction in {@code [0, 1)} aligned with PM Weather's SeasonHandler calendar
     * (day 0 begins at month 5 / May 1, with 12 months of {@link #monthLength()} days).
     */
    public static double yearFraction(Level level) {
        return yearFraction(level.getDayTime(), monthLength);
    }

    public static double yearFraction(long dayTime, int daysPerMonth) {
        int mLen = Math.max(1, daysPerMonth);
        long day = Math.floorDiv(dayTime, 24000L) + mLen * 4L;
        long yearDays = mLen * 12L;
        long dayOfYear = Math.floorMod(day, yearDays);
        return dayOfYear / (double) yearDays;
    }

    /** Latitude in degrees for a block Z in an Earth cubic world, or NaN for non-Earth levels. */
    public static double latitude(Level level, double z) {
        return latitude(level, 0.0, z);
    }

    /** Latitude in degrees for block {@code (x, z)} using the world's active projection, or NaN outside Earth coverage. */
    public static double latitude(Level level, double x, double z) {
        CubeWorld cubes = CubicLevel.of(level);
        GeoPoint point = cubes == null ? null : geographic(cubes, x, z);
        return point == null ? Double.NaN : point.latitude();
    }

    /** Returns -1 in the Southern Hemisphere of an Earth world and +1 elsewhere. */
    public static float seasonSign(Level level, double z) {
        return seasonSign(level, 0.0, z);
    }

    /** Returns -1 in the Southern Hemisphere at {@code (x, z)} under the active projection and +1 elsewhere. */
    public static float seasonSign(Level level, double x, double z) {
        double lat = latitude(level, x, z);
        return Double.isFinite(lat) && lat < 0.0 ? -1.0f : 1.0f;
    }

    @Nullable
    private static GeoPoint geographic(CubeWorld cubes, double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return null;
        GlobeProjection projection = cubes.earthProjection();
        if (projection != null) {
            if (projection.isWebMercator()) {
                double half = projection.worldWidthBlocks() / 2.0;
                return projection.geographic(x, Mth.clamp(z, -half, half));
            }
            return projection.tryGeographic(x, z);
        }
        double mpb = cubes.earthMetresPerBlock();
        if (!(mpb > 0.0) || !Double.isFinite(mpb)) return null;
        double halfWorld = Math.PI * MercatorGrid.RADIUS / mpb;
        double clampedZ = Mth.clamp(z, -halfWorld, halfWorld);
        double lat = Math.toDegrees(2.0 * Math.atan(Math.exp(-clampedZ * mpb / MercatorGrid.RADIUS)) - Math.PI / 2.0);
        double lon = wrapLongitude(Math.toDegrees(x * mpb / MercatorGrid.RADIUS));
        return new GeoPoint(lat, lon);
    }

    /**
     * Builds the regional {@link EarthWeatherProfile} for a position in an Earth cubic world, or
     * returns null for non-Earth / non-cubic dimensions. Uses only already-cached CHELSA rows so
     * it never blocks server or render threads.
     */
    @Nullable
    public static EarthWeatherProfile profile(Level level, double x, double y, double z) {
        CubeWorld cubes = CubicLevel.of(level);
        if (cubes == null) return null;
        GeoPoint point = geographic(cubes, x, z);
        if (point == null) return null;
        double lat = point.latitude();
        double lon = point.longitude();

        double sampleY = Double.isFinite(y) ? y : surfaceY(level, Mth.floor(x), Mth.floor(z));
        ClimateSource.Climate climate = null;
        if (cubes instanceof ServerCubeWorld server && server.generator() instanceof EarthGenerator earth) {
            climate = earth.climateIfLoaded(x, sampleY, z);
        }
        double elevMetres = Math.max(0.0, sampleY - EarthGenerator.SEA);
        return EarthWeatherProfile.of(lat, lon, elevMetres, yearFraction(level), climate);
    }

    /** Resolves the Level owning a ChunkAccess without relying on NeoForge-only ChunkAccess#getLevel. */
    @Nullable
    public static Level chunkLevel(ChunkAccess chunk) {
        if (chunk instanceof LevelChunk levelChunk) return levelChunk.getLevel();
        if (chunk instanceof ImposterProtoChunk imposter) return imposter.getWrapped().getLevel();
        return null;
    }

    /** Highest solid block Y in the column, using cubic heightmaps when the level is cubic. */
    public static int surfaceY(Level level, int x, int z) {
        CubeWorld cubes = CubicLevel.at(level, x, z);
        if (cubes != null) {
            int top = cubes.heightTop(Heightmap.Types.MOTION_BLOCKING, x, z);
            if (CubeWorld.inRange(top)) return top;
            return EarthGenerator.SEA;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
    }

    /**
     * Checks whether the surface cube of a column is loaded in a cubic world instead of probing a
     * fixed Y like 60, which may be deep underground in highlands or empty air in deep valleys.
     */
    public static boolean isColumnSurfaceLoaded(Level level, int x, int z) {
        CubeWorld cubes = CubicLevel.at(level, x, z);
        if (cubes == null) return level.isLoaded(new BlockPos(x, 60, z));
        int top = cubes.heightTop(Heightmap.Types.MOTION_BLOCKING, x, z);
        return CubeWorld.inRange(top) && cubes.isLoaded(new BlockPos(x, top, z));
    }

    /** Lifts PM Weather's cloud and cirrus deck altitudes above high cubic terrain. */
    public static double cloudLayerHeight(Level level, double x, double z, double baseHeight) {
        CubeWorld cubes = CubicLevel.of(level);
        if (cubes == null) return baseHeight;
        int top = cubes.heightTop(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
        if (!CubeWorld.inRange(top)) return baseHeight;
        return baseHeight + Math.max(0, top - EarthGenerator.SEA);
    }

    // PM Weather thermodynamic / cloud / storm modulation mappings ----------------------------------

    /**
     * Converts a regional sea-level mean temperature (C) to the Minecraft biome temperature scale
     * expected by PM Weather's ThermodynamicEngine (which then applies lapse rate and seasons).
     */
    public static float biomeTemperature(EarthWeatherProfile profile) {
        double target = profile.seaLevelMeanC() - 6.0;
        if (target >= 0.0) {
            double norm = Mth.clamp(target / 35.0, 0.0, 1.15);
            return (float) (0.175 + 1.85 * norm * norm);
        }
        double cold = Mth.clamp(-target / 60.0, 0.0, 1.0);
        return (float) (0.175 - Math.pow(cold, 1.0 / 1.5));
    }

    /** Regional biome downfall [0, 1] for PM Weather's dewpoint/humidity calculation. */
    public static float biomeDownfall(EarthWeatherProfile profile) {
        return (float) Mth.clamp(profile.moisture() * 1.15, 0.02, 1.0);
    }

    /** Regional sea surface temperature (C) for PM Weather's tropical cyclone engine. */
    public static float seaSurfaceTemp(EarthWeatherProfile profile) {
        return (float) profile.seaSurfaceTempC();
    }

    /** Modulates PM Weather's overcast percentage by regional cloudiness. */
    public static double overcastPercent(double baseOvercast, EarthWeatherProfile profile) {
        return Mth.clamp(baseOvercast * (0.05 + 1.35 * profile.cloudiness()), 0.0, 1.0);
    }

    /** Modulates PM Weather's precipitation rate by regional rain likelihood and intensity. */
    public static float precipitation(float originalPrecip, EarthWeatherProfile profile) {
        if (originalPrecip <= 0.0f) return originalPrecip;
        double factor = Mth.clamp(
                0.05 + 1.55 * profile.rainChance() * (0.45 + 0.85 * profile.rainIntensity()),
                0.02,
                1.85
        );
        return (float) (originalPrecip * factor);
    }

    /** Shapes PM Weather's Sounding severe-convective risk by regional tornado and storm energy. */
    public static float soundingRisk(float originalRisk, EarthWeatherProfile profile) {
        if (originalRisk <= 0.0f) return originalRisk;
        double scale = 0.08 + 1.95 * profile.tornadoPotential() + 0.35 * profile.stormEnergy();
        return (float) Math.max(0.0, originalRisk * scale);
    }

    /**
     * Adjusts PM Weather's 1-in-N cyclone roll (smaller N = more likely). Returns Integer.MAX_VALUE
     * where tropical cyclone genesis is not supported so hurricanes never spawn in high-latitude or
     * inland/winter regions.
     */
    public static int cycloneChanceInOne(int baseChanceInOne, EarthWeatherProfile profile) {
        int base = Math.max(1, baseChanceInOne);
        if (profile.hurricanePotential() < 0.12) return Integer.MAX_VALUE;
        int scaled = (int) Math.round(base / (0.40 + 2.20 * profile.hurricanePotential()));
        return Math.max(Math.max(4, base / 4), scaled);
    }

    /** Modulates PM Weather's per-minute storm spawn chance by regional convective/tropical/monsoon activity. */
    public static double stormSpawnChancePerMinute(double baseChance, EarthWeatherProfile profile) {
        if (baseChance <= 0.0) return baseChance;
        double activity = Math.max(
                profile.stormEnergy() * 0.90 + profile.tornadoPotential() * 0.60,
                Math.max(profile.hurricanePotential() * 0.90, profile.rainChance() * profile.rainIntensity() * 0.85)
        );
        double scale = Mth.clamp(0.05 + 1.55 * activity, 0.04, 1.85);
        return baseChance * scale;
    }

    private static double wrapLongitude(double lon) {
        double wrapped = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return wrapped == 180.0 ? -180.0 : wrapped;
    }
}
