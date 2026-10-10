package io.earthplatform.minecraft.scene;

import java.util.Objects;
import java.util.function.Supplier;

/** Immutable owner-bound appearance provenance, retained unchanged from private build through published mesh. */
final class SceneAppearanceSignature {
    record Values(Object renderer, boolean ambientOcclusion, Object graphicsMode, boolean experimentalLighting) {
        Values {
            Objects.requireNonNull(renderer, "renderer");
            Objects.requireNonNull(graphicsMode, "graphicsMode");
        }
    }
    private final Thread owner = Thread.currentThread();
    private final Values captured;

    SceneAppearanceSignature(Values captured) { this.captured = Objects.requireNonNull(captured); }

    /** The affinity check precedes even sampling live options/model state through the supplier. */
    boolean isCurrent(Supplier<Values> readCurrent) {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Scene appearance requires its owner thread");
        Values current = Objects.requireNonNull(readCurrent.get());
        return captured.renderer() == current.renderer()
                && captured.ambientOcclusion() == current.ambientOcclusion()
                && captured.graphicsMode() == current.graphicsMode()
                && captured.experimentalLighting() == current.experimentalLighting();
    }
}
