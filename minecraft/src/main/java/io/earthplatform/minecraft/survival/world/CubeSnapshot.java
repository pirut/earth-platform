package io.earthplatform.minecraft.survival.world;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import java.util.Objects;
import java.util.UUID;

/** Immutable owner value; durable revisions count checkpoints, simulation revisions count changes. */
public record CubeSnapshot(WorldIdentity world, UUID ownerEpoch, CubePos position, long incarnation, long simulationRevision,
        long durableRevision, long durableSimulationRevision, ImmutableCube blocks) {
    public CubeSnapshot {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(ownerEpoch, "ownerEpoch");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blocks, "blocks");
        if (incarnation <= 0 || simulationRevision < 0 || durableRevision < 0
                || durableSimulationRevision < 0 || durableSimulationRevision > simulationRevision)
            throw new IllegalArgumentException("Invalid cube snapshot revision");
    }
    public NativeSurvivalWorld.CubeStamp stamp() {
        return new NativeSurvivalWorld.CubeStamp(world, ownerEpoch, incarnation, simulationRevision);
    }
    public boolean dirty() { return simulationRevision != durableSimulationRevision; }
}
