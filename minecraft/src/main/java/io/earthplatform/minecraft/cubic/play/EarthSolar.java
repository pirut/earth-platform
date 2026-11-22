package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.GlobeProjection;
import javax.annotation.Nullable;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.platform.Platform;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Localized solar ephemeris for Earth worlds: computes Minecraft's {@code timeOfDay} in {@code [0, 1)}
 * from world {@code dayTime}, geographic longitude, latitude and seasonal solar declination.
 *
 * <p>Minecraft's {@code timeOfDay} convention places local solar noon at {@code 0.0}, sunset at
 * {@code 0.25}, midnight at {@code 0.5}, and sunrise at {@code 0.75}. East longitudes are ahead in
 * solar time relative to the prime meridian ({@code dayTime = 6000} is noon at {@code 0°} longitude),
 * while latitude and seasonal axial tilt ({@value #AXIAL_TILT_DEG}°) lengthen summer days, shorten
 * winter days, flip seasons between northern and southern hemispheres, and produce polar day and
 * polar night beyond the polar circles.
 */
public final class EarthSolar {
    /** Default length of a full seasonal year in in-game days (24 days per season). */
    public static final int DEFAULT_YEAR_DAYS = 96;
    /** Earth's axial tilt (obliquity of the ecliptic), in degrees. */
    public static final double AXIAL_TILT_DEG = 23.44;
    /** Minecraft ticks in one solar day. */
    public static final double TICKS_PER_DAY = 24000.0;
    /** Margin inside {@code |cos(H0)| = 1} to switch cleanly to polar day/night without dividing by zero. */
    private static final double POLAR_EPS = 0.005;
    private static final double MAX_LATITUDE_DEG = 89.999;

    /** Server-to-client sync of the localized day/night settings so multiplayer clients match the server. */
    public record Payload(boolean enabled, int yearDays) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth_solar"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeBoolean(p.enabled); buf.writeVarInt(p.yearDays); },
                buf -> new Payload(buf.readBoolean(), buf.readVarInt()));
        @Override public Type<Payload> type() { return TYPE; }
    }

    private EarthSolar() {}

    /**
     * Solar declination in degrees for a continuous world day count over a seasonal year of
     * {@code yearLengthDays} days. Day 0 is the northern spring equinox, {@code yearLengthDays / 4}
     * is the northern summer solstice ({@code +23.44°}), and {@code 3 * yearLengthDays / 4} is the
     * southern summer solstice ({@code -23.44°}). A non-positive year length keeps perpetual equinox.
     */
    public static double solarDeclinationDeg(double totalDays, int yearLengthDays) {
        if (yearLengthDays <= 0 || !Double.isFinite(totalDays)) return 0.0;
        double seasonFraction = frac(totalDays / yearLengthDays);
        return AXIAL_TILT_DEG * Math.sin(2.0 * Math.PI * seasonFraction);
    }

    /**
     * Local solar hour angle in {@code [-π, π)} radians, where {@code 0} is local solar noon,
     * {@code +π/2} is 18:00, {@code -π/2} is 06:00, and {@code ±π} is local midnight.
     */
    public static double localSolarHourAngle(double totalDays, double longitudeDeg) {
        double localFraction = frac(totalDays - 0.25 + longitudeDeg / 360.0);
        double signedFraction = localFraction >= 0.5 ? localFraction - 1.0 : localFraction;
        return signedFraction * (2.0 * Math.PI);
    }

    /** Sine of the true solar elevation angle {@code sin(α) = sin(φ)sin(δ) + cos(φ)cos(δ)cos(H)}. */
    public static double solarElevationSin(double latitudeDeg, double declinationDeg, double hourAngleRad) {
        double phi = Math.toRadians(Math.clamp(latitudeDeg, -90.0, 90.0));
        double delta = Math.toRadians(declinationDeg);
        return Math.clamp(Math.sin(phi) * Math.sin(delta) + Math.cos(phi) * Math.cos(delta) * Math.cos(hourAngleRad), -1.0, 1.0);
    }

    /** Astronomical daylight duration in hours {@code [0, 24]} for a latitude and solar declination. */
    public static double dayLengthHours(double latitudeDeg, double declinationDeg) {
        double phi = Math.toRadians(Math.clamp(latitudeDeg, -MAX_LATITUDE_DEG, MAX_LATITUDE_DEG));
        double delta = Math.toRadians(declinationDeg);
        double cosH0 = -Math.tan(phi) * Math.tan(delta);
        if (cosH0 <= -1.0) return 24.0;
        if (cosH0 >= 1.0) return 0.0;
        return 24.0 * Math.acos(cosH0) / Math.PI;
    }

    /** Convenience overload at tick boundary using {@link #DEFAULT_YEAR_DAYS}. */
    public static float timeOfDay(long dayTime, double latitudeDeg, double longitudeDeg) {
        return timeOfDay(dayTime, 0.0F, latitudeDeg, longitudeDeg, DEFAULT_YEAR_DAYS);
    }

    /**
     * Localized Minecraft {@code timeOfDay} in {@code [0, 1)} for a world {@code dayTime}, frame
     * {@code partialTick}, geographic coordinates, and seasonal year length.
     */
    public static float timeOfDay(long dayTime, float partialTick, double latitudeDeg, double longitudeDeg, int yearLengthDays) {
        double safeLat = Double.isFinite(latitudeDeg) ? Math.clamp(latitudeDeg, -MAX_LATITUDE_DEG, MAX_LATITUDE_DEG) : 0.0;
        double safeLon = Double.isFinite(longitudeDeg) ? longitudeDeg : 0.0;
        double safePartial = Float.isFinite(partialTick) ? partialTick : 0.0;
        double totalDays = (dayTime + safePartial) / TICKS_PER_DAY;
        double declinationDeg = solarDeclinationDeg(totalDays, yearLengthDays);
        double hourAngle = localSolarHourAngle(totalDays, safeLon);
        return timeOfDayForDeclination(hourAngle, safeLat, declinationDeg);
    }

    /** Maps a local solar hour angle {@code [-π, π]}, latitude and solar declination onto {@code [0, 1)}. */
    static float timeOfDayForDeclination(double hourAngle, double latitudeDeg, double declinationDeg) {
        double safeLat = Double.isFinite(latitudeDeg) ? Math.clamp(latitudeDeg, -MAX_LATITUDE_DEG, MAX_LATITUDE_DEG) : 0.0;
        double phi = Math.toRadians(safeLat);
        double delta = Math.toRadians(declinationDeg);
        double cosH0 = -Math.tan(phi) * Math.tan(delta);

        if (cosH0 <= -1.0 + POLAR_EPS) {
            // Polar day (midnight sun): stays above horizon all 24h, lowest near horizon at midnight.
            double sinAlpha = solarElevationSin(safeLat, declinationDeg, hourAngle);
            double t = Math.min(Math.acos(Math.clamp(sinAlpha, 0.0, 1.0)) / (2.0 * Math.PI), 0.245);
            return clampTimeOfDay(t);
        }
        if (cosH0 >= 1.0 - POLAR_EPS) {
            // Polar night: stays below horizon all 24h, brightening slightly toward twilight at noon.
            double sinAlpha = solarElevationSin(safeLat, declinationDeg, hourAngle);
            double t = Math.max(Math.acos(Math.clamp(sinAlpha, -1.0, 0.0)) / (2.0 * Math.PI), 0.255);
            return clampTimeOfDay(t);
        }

        double halfDay = Math.acos(cosH0);
        double signedTime;
        if (Math.abs(hourAngle) <= halfDay) {
            signedTime = 0.25 * (hourAngle / halfDay);
        } else if (hourAngle > halfDay) {
            signedTime = 0.25 + 0.25 * ((hourAngle - halfDay) / (Math.PI - halfDay));
        } else {
            signedTime = -0.25 + 0.25 * ((hourAngle + halfDay) / (Math.PI - halfDay));
        }
        return clampTimeOfDay(signedTime);
    }

    /** Whether a Minecraft {@code timeOfDay} in {@code [0, 1)} has the sun at or above the horizon. */
    public static boolean isDay(float timeOfDay) {
        return timeOfDay < 0.25F || timeOfDay >= 0.75F;
    }

    /**
     * Vanilla sky-darkening level {@code [0, 11]} for a given {@code timeOfDay} and weather, matching
     * {@link Level#updateSkyBrightness()}.
     */
    public static int skyDarken(float timeOfDay, float rainLevel, float thunderLevel) {
        double rain = 1.0 - (double) (rainLevel * 5.0F) / 16.0;
        double thunder = 1.0 - (double) (thunderLevel * 5.0F) / 16.0;
        double sun = 0.5 + 2.0 * Math.clamp(Math.cos(timeOfDay * (Math.PI * 2.0)), -0.25, 0.25);
        return (int) ((1.0 - sun * rain * thunder) * 11.0);
    }

    /** Converts continuous block coordinates to WGS84, clamping Z inside Web Mercator coverage or returning null outside bounded projections. */
    @Nullable
    public static GeoPoint geographic(GlobeProjection grid, double x, double z) {
        if (grid.isWebMercator()) {
            double half = grid.worldWidthBlocks() / 2.0;
            double clampedZ = Math.clamp(z, -half, half);
            return grid.geographic(x, clampedZ);
        }
        return grid.tryGeographic(x, z);
    }

    /** Sends the server's current localized day/night settings to one player. */
    public static void sync(ServerPlayer player) {
        Platform.INSTANCE.sendToPlayer(player, new Payload(EarthConfig.LOCALIZED_DAY_NIGHT.get(), EarthConfig.YEAR_LENGTH_DAYS.get()));
    }

    /** Broadcasts the server's localized day/night settings to every connected player. */
    public static void syncAll(MinecraftServer server) {
        Payload payload = new Payload(EarthConfig.LOCALIZED_DAY_NIGHT.get(), EarthConfig.YEAR_LENGTH_DAYS.get());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) Platform.INSTANCE.sendToPlayer(player, payload);
    }

    /**
     * Localized {@code timeOfDay} at a server-side block position in an Earth world, or
     * {@link Float#NaN} when localized day/night is off or the level is not a server Earth world.
     */
    public static float serverTimeOfDay(Level level, double x, double z) {
        if (level.isClientSide || !EarthConfig.LOCALIZED_DAY_NIGHT.get() || level.dimensionType().hasFixedTime()) return Float.NaN;
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld cubes) || !(cubes.generator() instanceof EarthGenerator earth)) return Float.NaN;
        GeoPoint point = geographic(earth.grid(), x, z);
        if (point == null) return Float.NaN;
        return timeOfDay(level.getDayTime(), 0.0F, point.latitude(), point.longitude(), EarthConfig.YEAR_LENGTH_DAYS.get());
    }

    private static float clampTimeOfDay(double value) {
        float wrapped = (float) frac(value);
        return wrapped >= 1.0F || wrapped == 0.0F ? 0.0F : wrapped;
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }
}
