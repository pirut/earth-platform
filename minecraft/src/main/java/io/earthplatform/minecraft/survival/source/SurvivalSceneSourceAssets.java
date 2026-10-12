package io.earthplatform.minecraft.survival.source;

import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.render.SurvivalRenderPalette;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One checksum-pinned survival visual source, reserved BEFORE setup and retained for process life.
 * No close or counter-based release exists: CaptureOwner/Content may keep immutable source aliases.
 * Load on the setup/source owner. This is not a dimension, simulation or world admission capability.
 */
public final class SurvivalSceneSourceAssets {
    private static SurvivalSceneSourceAssets installed;
    private final Path root;
    private final MarinVisualCubeSource source;
    private final NativeDistanceCpuBudget.Reservation charge;
    private SurvivalSceneSourceAssets(Path root, MarinVisualCubeSource source,
            NativeDistanceCpuBudget.Reservation charge) {
        this.root = root; this.source = source; this.charge = charge;
    }
    public static synchronized SurvivalSceneSourceAssets load(Path root, EnginePaletteContext context)
            throws IOException {
        Objects.requireNonNull(context);
        Path exact = Objects.requireNonNull(root).toAbsolutePath().normalize();
        if (installed != null) {
            if (!installed.root.equals(exact) || installed.source.palette().context() != context)
                throw new IllegalStateException("Different survival source root or exact registry owner");
            return installed;
        }
        var charge = NativeDistanceCpuBudget.global().reserve(
                NativeDistanceCpuBudget.Pool.SOURCE_ASSETS, 64L << 20);
        try {
            var definition = MarinSurvivalResources.installAndLoad(exact);
            var source = new MarinVisualCubeSource(definition, new SurvivalRenderPalette(context));
            installed = new SurvivalSceneSourceAssets(exact, source, charge);
            return installed;
        } catch (IOException | RuntimeException | Error failure) {
            // Setup returned no source aliases and owns no ongoing worker/native resource.
            charge.releaseAfterPhysicalDrain();
            throw failure;
        }
    }
    public MarinVisualCubeSource source() {
        if (charge.released()) throw new IllegalStateException("Source asset lifetime was retired");
        return source;
    }
    public long retainedBytes() { return charge.bytes(); }
}
