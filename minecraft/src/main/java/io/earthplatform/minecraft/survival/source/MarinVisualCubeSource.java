package io.earthplatform.minecraft.survival.source;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.geography.WorldManifest;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.geography.survival.RegisteredBlockState;
import io.earthplatform.geography.survival.SurvivalMaterials;
import io.earthplatform.minecraft.survival.render.SurvivalRenderPalette;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Certified read-only source for selected native pages and their exact halo dependencies.
 * No owner, mutable palette, admission, edits, persistence, scheduler, or distance clamp is retained.
 * Every dependency must be validated/generated independently; missing source never becomes AIR.
 */
public final class MarinVisualCubeSource {
    private final MarinSurvivalDefinition definition;
    private final SurvivalRenderPalette palette;
    private final Map<RegisteredBlockState, Integer> nativeIds;
    private final String identity;

    public MarinVisualCubeSource(MarinSurvivalDefinition definition, SurvivalRenderPalette palette) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.palette = Objects.requireNonNull(palette, "palette");
        var states = new HashMap<RegisteredBlockState, Integer>();
        for (RegisteredBlockState descriptor : SurvivalMaterials.supportedStates())
            states.put(descriptor, palette.id(MarinNativeCubeSource.resolve(descriptor)));
        nativeIds = Map.copyOf(states);
        identity = WorldManifest.sha256((definition.visualIdentity() + "\n" + palette.identity()
                + "\n" + palette.policyIdentity()).getBytes(StandardCharsets.UTF_8));
    }
    public MarinSurvivalDefinition definition() { return definition; }
    public SurvivalRenderPalette palette() { return palette; }
    public String identity() { return identity; }
    public boolean permits(CubePos position) { return definition.permitsVisualCube(position); }
    public void validate(CubePos position) { definition.validateVisualCube(position); }
    public ImmutableCube generate(CubePos position, CancellationToken cancellation) {
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        RegisteredBlockState[] generated = definition.generateVisualCube(position);
        int[] values = new int[CubePos.VOLUME];
        for (int i = 0; i < values.length; i++) {
            if ((i & 255) == 0) cancellation.throwIfCancelled();
            Integer id = nativeIds.get(generated[i]);
            if (id == null) throw new IllegalStateException("Generated state has no exact read-only native render mapping: "
                    + generated[i].canonical());
            values[i] = id;
        }
        cancellation.throwIfCancelled();
        return ImmutableCube.copyOf(values);
    }
}
