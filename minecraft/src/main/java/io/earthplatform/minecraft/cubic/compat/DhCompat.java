package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.Cubic;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Distant Horizons draws its own LODs and fog over the whole screen. In the cubic world it only sees
 * the empty carrier, so it would hide the Earth's own far terrain behind nothing. While the player
 * is in the cubic world its rendering and distant generation are paused through its public API (an
 * API override, never written to DH's config file); everywhere else DH runs as the player set it up.
 * The API is reached by reflection, so any DH version can be installed; one whose API lacks these
 * calls is left running, with a note in the log and in chat. Only used when DH is installed.
 */
final class DhCompat {
    private static final String[][] OPTIONS = {{"graphics", "renderingEnabled"}, {"worldGenerator", "enableDistantWorldGeneration"}};
    private static boolean paused, unsupported;

    private DhCompat() {}

    /** Returns true once the pause state matches, or DH can't be paused; false while DH's API is not ready yet. */
    static boolean update(boolean inCubicWorld) {
        if (inCubicWorld == paused || unsupported) return true;
        try {
            Field field = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs");
            Object configs = field.get(null);
            if (configs == null) return false;
            for (String[] path : OPTIONS) {
                Method group = field.getType().getMethod(path[0]), option = group.getReturnType().getMethod(path[1]);
                Object value = option.invoke(group.invoke(configs));
                if (inCubicWorld) option.getReturnType().getMethod("setValue", Object.class).invoke(value, false);
                else option.getReturnType().getMethod("clearValue").invoke(value);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            unsupported = true;
            Cubic.LOGGER.warn("This Distant Horizons version can't be paused through its API, so it keeps drawing in cubic worlds", failure);
            return true;
        }
        paused = inCubicWorld;
        Cubic.LOGGER.info("Distant Horizons {} for a cubic world", inCubicWorld ? "paused" : "resumed");
        return true;
    }

    static boolean paused() {
        return paused;
    }
}
