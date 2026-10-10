package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.enginebridge.AnalyticNativeCubeProvider;
import io.earthplatform.minecraft.network.EnginePaletteContext;

final class CoreGateFixture {
    static boolean collision() { return Boolean.getBoolean(CoreCollisionBinding.OPT_IN); }
    static AnalyticNativeCubeProvider provider(EnginePaletteContext palette) {
        return new AnalyticNativeCubeProvider(0, collision()
                ? AnalyticNativeCubeProvider.Config.boundedCollision(128)
                : AnalyticNativeCubeProvider.Config.flat(128), palette);
    }
    static String directory() { return collision() ? "earthplatform-core-collision-gate" : "earthplatform-core-gate"; }
}
