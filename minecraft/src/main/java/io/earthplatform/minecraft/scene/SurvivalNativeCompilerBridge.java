package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.lighting.LightPipelineAwareModelBlockRenderer;
import net.neoforged.neoforge.common.NeoForgeConfig;

/**
 * Narrow package bridge for the survival CPU page compiler. It owns no GPU objects and cannot mint
 * publication or readiness. Only an actual owner-bound Capture is accepted, never caller hooks.
 * Complete exact halo jobs are strict; getter-value reuse is intentionally not offered.
 */
public final class SurvivalNativeCompilerBridge implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final RenderPublicationGuard guard;
    private final SceneNativeShaderContract.Capture capture;
    private final BlockRenderDispatcher renderer;
    private final SceneAppearanceSignature appearance;
    // Only the sticky invalidation is needed. Overflow disables optional value reuse, not poisoning.
    private final SceneReadFootprint.Recorder poison = new SceneReadFootprint.Recorder(1);
    private final Map<CubePos, RenderPublicationGuard.Job> jobs = new TreeMap<>();
    private final Set<CubePos> observed = new TreeSet<>();
    private SceneNeighborhood view;
    private CubePos activeMember;
    private boolean closed, invalidated;

    public SurvivalNativeCompilerBridge(RenderPublicationGuard guard, Map<CubePos, SceneCube> exactDependencies,
            SceneNeighborhood.LightingPolicy lighting, boolean directionalShadingDisabled,
            SceneNativeShaderContract.Capture capture) {
        RenderSystem.assertOnRenderThread();
        this.guard = Objects.requireNonNull(guard);
        this.capture = Objects.requireNonNull(capture);
        capture.requireCurrent();
        if (capture.values().shaders() && (lighting != SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE
                || directionalShadingDisabled != capture.values().disableDirectionalShading()))
            throw new IllegalArgumentException("Page lighting differs from its captured shader policy");
        var base = new SceneNeighborhood(exactDependencies, SceneRenderEnvelope.NATIVE, lighting,
                directionalShadingDisabled);
        for (var entry : exactDependencies.entrySet()) jobs.put(entry.getKey(), guard.prepare(entry.getValue().snapshot()));
        var values = readAppearance();
        renderer = (BlockRenderDispatcher) values.renderer();
        appearance = new SceneAppearanceSignature(values);
        view = base.tracking(cube -> {
            checkOpen();
            if (activeMember == null) throw new IllegalStateException("Native read outside an active member callback");
            SceneReadFootprint.requireHaloOwner(activeMember, cube.snapshot().position());
            var job = jobs.get(cube.snapshot().position());
            if (job == null || job.snapshot() != cube.snapshot() || !guard.isCurrent(job))
                throw new IllegalStateException("Native page dependency changed during a callback");
            observed.add(job.position());
        }, poison);
        requireCurrent();
    }

    public BlockRenderDispatcher renderer() { checkOpen(); return renderer; }
    public SceneNeighborhood view() { checkOpen(); return view; }
    public SceneNativeShaderContract.Capture capture() { checkOpen(); return capture; }
    public void enterMember(CubePos position) {
        checkOpen();
        if (!jobs.containsKey(Objects.requireNonNull(position))) throw new IllegalArgumentException("Unknown native member");
        if (activeMember != null) {
            poison.invalidate();
            throw new IllegalStateException("Nested native member scope");
        }
        activeMember = position;
        requireCallbackCurrent();
    }
    /** Scope cleanup must also run after a failed/poisoned callback. */
    public void leaveMember() { checkOwner(); activeMember = null; }
    public void requireCallbackCurrent() {
        checkOpen(); poison.requireValid(); capture.requireCurrent();
        if (activeMember == null || !guard.isCurrent(jobs.get(activeMember)))
            throw new IllegalStateException("Native page member became stale");
    }
    public void requireCurrent() {
        checkOpen();
        try {
            poison.requireValid(); capture.requireCurrent();
            for (var job : jobs.values()) if (!guard.isCurrent(job))
                throw new IllegalStateException("Native page halo became stale");
            if (!appearance.isCurrent(SurvivalNativeCompilerBridge::readAppearance))
                throw new IllegalStateException("Native page renderer appearance changed");
        } catch (RuntimeException | Error failure) { invalidated = true; throw failure; }
    }
    public void requireFormat(VertexFormat format, int stride) {
        checkOpen(); capture.requireFormat(format, stride);
        var names = format.getElementAttributeNames();
        if (names.isEmpty() || !names.getFirst().equals("Position") || format.getElements().isEmpty())
            throw new IllegalStateException("Native page format must start with Position");
        var position = format.getElements().getFirst();
        if (format.getOffset(position) != 0 || position.byteSize() != 12 || !position.type().name().equals("FLOAT"))
            throw new IllegalStateException("Native page requires the exact three-float position element");
    }
    /** Material coordinates remain cube-local. Position relocation happens only after native emission. */
    public void beginBlock(BufferBuilder builder, BlockState state, int x, int y, int z) {
        checkOpen(); capture.beginBlock(builder, state, x, y, z);
    }
    /** Cleanup is permitted after invalidation so partially entered native contexts still end. */
    public void endBlock(BufferBuilder builder) { checkOwner(); capture.endBlock(builder); }
    public List<RenderPublicationGuard.Job> dependencyJobs() { checkOpen(); return List.copyOf(jobs.values()); }
    public Set<CubePos> observedOwners() { checkOpen(); return Set.copyOf(observed); }
    public void clearCaches() {
        checkOwner();
        Throwable failure = null;
        try { ModelBlockRenderer.clearCache(); }
        catch (RuntimeException | Error error) { failure = error; }
        if (renderer.getModelRenderer() instanceof LightPipelineAwareModelBlockRenderer nativeRenderer) {
            try { nativeRenderer.getQuadLighter(false).reset(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            try { nativeRenderer.getQuadLighter(true).reset(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
        }
        SceneBuildStepper.rethrow(failure);
    }
    private static SceneAppearanceSignature.Values readAppearance() {
        RenderSystem.assertOnRenderThread();
        var mc = Minecraft.getInstance();
        return new SceneAppearanceSignature.Values(mc.getBlockRenderer(), Minecraft.useAmbientOcclusion(),
                mc.options.graphicsMode().get(), NeoForgeConfig.CLIENT.experimentalForgeLightPipelineEnabled.get());
    }
    private void checkOpen() {
        checkOwner();
        if (closed || invalidated) throw new IllegalStateException("Native page bridge is closed or invalidated");
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native page bridge requires its render owner");
        RenderSystem.assertOnRenderThread();
    }
    @Override public void close() {
        checkOwner();
        if (closed) return;
        poison.cancel(); jobs.clear(); observed.clear(); view = null; activeMember = null; closed = true;
    }
}
