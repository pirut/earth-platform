package io.earthplatform.minecraft.lod;

import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import java.util.List;

/** Immutable Marin sampling policy only; never an Earth replacement or draw capability. */
public final class SurvivalDhLayout {
    public static final int RADIUS = 2048, ALIGNMENT = 128;
    public static final String IDENTITY = "marin-dh-complement-039-v1;radius2048;align128;"
            + "rings128/16,256/16,512/32,1024/64,2048/128;unit-native-and-overlay-boundary;"
            + "representative-column-solid-runs;generated-tree-proxies;opaque-lod-leaves;"
            + "source-footprint-required;native-xyz-member-clip;no-unknown-fill;no-bottom-cap";
    public record Ring(int radius, int cellSize) {}
    public static final List<Ring> RINGS = List.of(new Ring(128,16), new Ring(256,16),
            new Ring(512,32), new Ring(1024,64), new Ring(2048,128));
    private SurvivalDhLayout() {}
    public static void validate(MarinSurvivalDefinition source, long x, long z) {
        if (source == null || !MarinSurvivalDefinition.VISUAL_DEFINITION_IDENTITY.equals(source.visualIdentity()))
            throw new IllegalArgumentException("Exact Marin visual source required");
        source.validateFarFootprint(x,z);
    }
}
