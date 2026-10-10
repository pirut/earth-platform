package io.earthplatform.minecraft.residency;

/** Immutable diagnostics only, never exposes a native mutable array. */
public interface SparseFacadeDiagnostics {
    int earthplatform$legacySectionSlots();
    int earthplatform$legacyPostprocessingSlots();
}
