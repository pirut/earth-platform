package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable evidence, never a mutation capability. It retains no host, plan, action or live entity.
 * The creating host retains at most one private lineage and must revalidate this object by identity.
 */
public final class NativeAcceptedBlockBreak {
    private final NativeSurvivalWorld.CommitReceipt receipt;
    private final CubeCell target;
    private final int beforeStateId;
    private final float observedDestroyProgress;
    private final List<UUID> dropIds;
    NativeAcceptedBlockBreak(NativeSurvivalWorld.CommitReceipt receipt, CubeCell target, int beforeStateId,
            float observedDestroyProgress, List<UUID> dropIds) {
        this.receipt = Objects.requireNonNull(receipt); this.target = Objects.requireNonNull(target);
        this.beforeStateId = beforeStateId; this.observedDestroyProgress = observedDestroyProgress;
        this.dropIds = List.copyOf(dropIds);
    }
    public NativeSurvivalWorld.CommitReceipt receipt() { return receipt; }
    public CubeCell target() { return target; }
    public int beforeStateId() { return beforeStateId; }
    public float observedDestroyProgress() { return observedDestroyProgress; }
    public List<UUID> dropIds() { return dropIds; }
}
