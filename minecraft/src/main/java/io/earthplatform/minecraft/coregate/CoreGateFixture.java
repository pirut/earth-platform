package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.enginebridge.AnalyticNativeCubeProvider;
import io.earthplatform.minecraft.network.EnginePaletteContext;

final class CoreGateFixture {
    static final String FULL_HEIGHT_OPT_IN="earthplatform.experimental.fullHeightPlayerGate";
    static boolean fullHeight(){return Boolean.getBoolean(FULL_HEIGHT_OPT_IN);}
    static boolean collision() { return Boolean.getBoolean(CoreCollisionBinding.OPT_IN); }
    static AnalyticNativeCubeProvider provider(EnginePaletteContext palette) {return provider(palette,CoreGateProfile.BASE);}
    static AnalyticNativeCubeProvider provider(EnginePaletteContext palette,CoreGateProfile profile) {
        if(fullHeight()){if(!collision())throw new IllegalStateException("Full-height gate requires explicit collision gate");return profile.provider(palette);}
        if(profile!=CoreGateProfile.BASE)throw new IllegalArgumentException("Endpoint profile requires full-height gate");
        return new AnalyticNativeCubeProvider(0, collision()
                ? AnalyticNativeCubeProvider.Config.boundedCollision(128)
                : AnalyticNativeCubeProvider.Config.flat(128), palette);
    }
    static String directory() {return directory(CoreGateProfile.BASE);}
    static String directory(CoreGateProfile profile) { if(fullHeight())return profile.saveDirectory();return collision() ? "earthplatform-core-collision-gate" : "earthplatform-core-gate"; }
}
