package io.earthplatform.minecraft.fixture;

/** Read-only constructor allocation evidence; never exposes mutable cache or section arrays. */
public interface NativeSparseCacheDiagnostics {
    int earthplatform$emptySectionSlots();
    int earthplatform$emptyPostprocessingSlots();
}
