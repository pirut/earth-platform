package io.earthplatform.minecraft.cubic.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import io.earthplatform.minecraft.EarthPlatformMod;

/**
 * Distant Horizons draws its own LODs and fog over the whole screen. In the cubic world it only sees
 * the empty carrier, so it would hide the Earth's own far terrain behind nothing. While the player
 * is in the cubic world its rendering and distant generation are paused through its API (an API
 * override, never written to DH's config file); everywhere else DH runs as the player set it up.
 * Only loaded when DH is installed.
 */
final class DhCompat {
    private static final String CALLER = EarthPlatformMod.MOD_ID;
    private static boolean paused;

    private DhCompat() {}

    /** Returns true once the pause state matches; false while DH's API is not ready yet. */
    static boolean update(boolean inCubicWorld) {
        if (inCubicWorld == paused) return true;
        IDhApiConfig configs = DhApi.Delayed.configs;
        if (configs == null) return false;
        if (inCubicWorld) {
            configs.graphics().renderingEnabled().setValue(false, CALLER);
            configs.worldGenerator().enableDistantWorldGeneration().setValue(false, CALLER);
        } else {
            configs.graphics().renderingEnabled().clearValue();
            configs.worldGenerator().enableDistantWorldGeneration().clearValue();
        }
        paused = inCubicWorld;
        EarthPlatformMod.LOGGER.info("Distant Horizons {} for the Earth world", inCubicWorld ? "paused" : "resumed");
        return true;
    }
}
