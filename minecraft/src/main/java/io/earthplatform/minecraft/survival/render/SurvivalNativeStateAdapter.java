package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.scene.SceneRenderEnvelope;
import io.earthplatform.minecraft.survival.world.CubeSnapshot;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import java.util.Objects;

/** Exact pinned native IDs, including every admitted log axis/leaf property; no material relabeling. */
public final class SurvivalNativeStateAdapter {
    private final SurvivalPalette palette;
    private final NativeCubeCodec codec;
    public SurvivalNativeStateAdapter(WorldIdentity world, SurvivalPalette palette) {
        this.palette = Objects.requireNonNull(palette, "palette");
        codec = new NativeCubeCodec(world, palette.context());
    }
    public SceneCube materialize(CubeSnapshot source, long transportEpoch, long leaseGeneration) {
        Objects.requireNonNull(source, "source");
        if (!codec.world().equals(source.world())) throw new IllegalArgumentException("Foreign survival snapshot world");
        palette.validate(source.blocks());
        var snapshot = codec.materialize(source.position(), source.blocks(), transportEpoch, leaseGeneration,
                source.simulationRevision());
        return new SceneCube(snapshot, palette.context(), SceneRenderEnvelope.NATIVE, state -> {
            palette.id(state); // Exact registered identity; also refuses waterlogged leaves/falling blocks/entities.
            return true;
        });
    }
    void validate(CubeSnapshot cube) { palette.validate(cube.blocks()); }
}
