package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.GlobeProjection;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import io.earthplatform.minecraft.cubic.play.EarthSolar;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;

/** Client-side localized solar time, driven by camera/player coordinates and synced server settings. */
public final class EarthSolarClient {
    @Nullable private static volatile EarthSolar.Payload synced;

    private EarthSolarClient() {}

    /** Stores the server's localized day/night settings on join or when changed with {@code /earth settings}. */
    public static void received(EarthSolar.Payload payload) {
        synced = payload;
    }

    /** Clears server-synced settings when leaving a world. */
    public static void loggedOut() {
        synced = null;
    }

    /** Whether localized day/night is enabled for the active session. */
    public static boolean enabled() {
        EarthSolar.Payload current = synced;
        return current != null ? current.enabled() : EarthConfig.LOCALIZED_DAY_NIGHT.get();
    }

    /** Length of a seasonal year in in-game days for the active session. */
    public static int yearDays() {
        EarthSolar.Payload current = synced;
        return current != null ? current.yearDays() : EarthConfig.YEAR_LENGTH_DAYS.get();
    }

    /** Whether {@code level} is currently an Earth world with localized day/night enabled. */
    public static boolean active(ClientLevel level) {
        return enabled() && !level.dimensionType().hasFixedTime() && grid(level) != null;
    }

    /**
     * Localized {@code timeOfDay} in {@code [0, 1)} for the active camera or player position, or
     * {@link Float#NaN} when localized day/night does not apply to {@code level}.
     */
    public static float timeOfDay(ClientLevel level, float partialTick) {
        if (!enabled() || level.dimensionType().hasFixedTime()) return Float.NaN;
        GlobeProjection grid = grid(level);
        if (grid == null) return Float.NaN;
        Vec3 pos = position();
        if (pos == null) return Float.NaN;
        float smooth = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT) && level.tickRateManager().runsNormally()
                ? Math.clamp(partialTick, 0.0F, 1.0F)
                : 0.0F;
        GeoPoint point = EarthSolar.geographic(grid, pos.x, pos.z);
        if (point == null) return Float.NaN;
        return EarthSolar.timeOfDay(level.getDayTime(), smooth, point.latitude(), point.longitude(), yearDays());
    }

    @Nullable
    private static GlobeProjection grid(ClientLevel level) {
        if (CubicLevel.of(level) == null) return null;
        ClientCubeWorld world = ClientCubeWorld.current();
        return world == null ? null : world.far().projection();
    }

    @Nullable
    private static Vec3 position() {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer == null ? null : minecraft.gameRenderer.getMainCamera();
        if (camera != null && camera.isInitialized()) return camera.getPosition();
        return minecraft.player == null ? null : minecraft.player.position();
    }
}
