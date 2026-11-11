package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.far.Far;

/** Visual transition above local terrain; never changes collision, saves or coverage. */
final class FarAtmosphere {
    private FarAtmosphere() {}

    static int smoothFrom(int configured, boolean cruise) {
        // High flight can soften coarse scenery, never the levels that show block details/builds.
        int firstDistant = Far.EDIT_LEVELS + 1;
        return cruise ? firstDistant : Math.clamp(configured, firstDistant, Far.MAX_LEVEL);
    }

    static boolean cruise(double height, boolean current, boolean zoomed) {
        return Boolean.parseBoolean(System.getProperty("earthplatform.far.cruiseSmooth", "true"))
                && Double.isFinite(height) && height > (current ? 1600 : 2200);
    }

    static float blend(double height) {
        if (!Boolean.parseBoolean(System.getProperty("earthplatform.far.atmosphere", "true")) || !Double.isFinite(height)) return 0;
        double t = Math.clamp((height - 2000) / 4000, 0, 1);
        return (float) (t * t * (3 - 2 * t));
    }
}
