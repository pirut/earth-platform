package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.platform.GlStateManager;
import io.earthplatform.minecraft.lod.RenderSubmissionProbe;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.minecraft.lod.RenderSubmissionProbe.Stage;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.mixin.SceneBufferBuilderAccessor;
import net.minecraft.client.renderer.block.model.BakedQuad;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.renderprobe.CameraRelativeOrigin;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.lighting.LightPipelineAwareModelBlockRenderer;
import net.neoforged.neoforge.common.NeoForgeConfig;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL33C.*;

/** Native baked models and native VBOs, compiled on their owner with bounded, revocable provenance. */
public final class SceneMesh implements AutoCloseable {
    public static final int MAX_QUADS = 8192;
    public static final int MAX_VERTICES = MAX_QUADS * 4;
    public static final int MAX_VERTEX_BYTES = MAX_VERTICES * 32;
    private final RenderPublicationGuard guard;
    private final RenderPublicationGuard.Job job;
    private Binding binding;
    private final SceneReadFootprint footprint;
    private final SceneRenderEnvelope envelope;
    private final SceneNeighborhood.LightingPolicy lightingPolicy;
    private final boolean directionalShadingDisabled;
    private Reuse pendingReuse;
    private int lastReuseComparedValues;
    private long cumulativeReuseComparedValues, successfulReusePreparations;
    private boolean appearanceInvalidated;
    private final List<Pass> passes;
    private final SceneAppearanceSignature appearance;
    private final SceneNativeShaderContract.Capture shaderContract;
    private record Binding(List<RenderPublicationGuard.Job> dependencies, RenderPublicationGuard.PresentationSource source) {}
    private final int vertices, bytes;
    private boolean closed, closeComplete, drawAttempted;
    private long drawAttempts;
    private final String drawDetail, firstDrawDetail;

    private static final class Pass {
        private final RenderType layer; private final VertexBuffer buffer;
        private final String detail, firstDetail;
        private final int vertices, layerId;
        private long drawAttempts;
        private boolean submitAttempted;
        Pass(RenderType layer, VertexBuffer buffer, CubePos position, int vertices) {
            this.layer=layer; this.buffer=buffer; this.vertices=vertices; this.layerId=layerId(layer);
            detail=drawDetail(position,layerName(layer),vertices,false);
            firstDetail=drawDetail(position,layerName(layer),vertices,true);
        }
        RenderType layer(){return layer;} VertexBuffer buffer(){return buffer;}
        String detail(){return submitAttempted?detail:firstDetail;}
    }

    /** Registered before native allocation, so every successfully returned VBO already has a cleanup owner. */
    private static final class GpuOwner implements SceneBuildResources.Resource {
        VertexBuffer buffer;
        @Override public void close() { if (buffer != null) buffer.close(); }
    }

    private SceneMesh(RenderPublicationGuard guard, RenderPublicationGuard.Job job,
                      List<RenderPublicationGuard.Job> dependencies, List<Pass> passes, int vertices, int bytes,
                      SceneAppearanceSignature appearance, SceneReadFootprint footprint, SceneRenderEnvelope envelope,
                      SceneNativeShaderContract.Capture shaderContract, SceneNeighborhood.LightingPolicy lightingPolicy,
                      boolean directionalShadingDisabled) {
        this.guard = guard;
        this.job = job;

        this.passes = List.copyOf(passes);
        this.vertices = vertices;
        this.bytes = bytes;
        this.appearance = Objects.requireNonNull(appearance);
        this.shaderContract = Objects.requireNonNull(shaderContract);
        drawDetail = drawDetail(job.position(), "all", vertices, false);
        firstDrawDetail = drawDetail(job.position(), "all", vertices, true);
        this.footprint = Objects.requireNonNull(footprint);
        this.envelope = Objects.requireNonNull(envelope);
        this.lightingPolicy = Objects.requireNonNull(lightingPolicy);
        this.directionalShadingDisabled = directionalShadingDisabled;
        var immutable = List.copyOf(dependencies);
        this.binding = new Binding(immutable, guard.presentationSource(job, immutable, this::presentationUsable));
    }

    /** Unknown neighbors defer; CPU candidates remain private until their final owner-side upload. */
    public static Build begin(RenderPublicationGuard guard, SceneCube cube, SceneNeighborhood neighborhood) {
        RenderSystem.assertOnRenderThread();
        return begin(guard, cube, neighborhood, SceneNativeShaderContract.defaultPath());
    }

    /** Explicit capture for the isolated, pinned installed shader path. */
    public static Build begin(RenderPublicationGuard guard, SceneCube cube, SceneNeighborhood neighborhood,
                              SceneNativeShaderContract.Capture shaderContract) {
        RenderSystem.assertOnRenderThread();
        return new Build(guard, cube, neighborhood, shaderContract);
    }

    /** Backward-compatible synchronous composition for independent callers and fixtures. */
    public static SceneMesh build(RenderPublicationGuard guard, SceneCube cube, SceneNeighborhood neighborhood) {
        RenderSystem.assertOnRenderThread();
        return build(guard, cube, neighborhood, SceneNativeShaderContract.defaultPath());
    }

    public static SceneMesh build(RenderPublicationGuard guard, SceneCube cube, SceneNeighborhood neighborhood,
                                  SceneNativeShaderContract.Capture shaderContract) {
        Build candidate = begin(guard, cube, neighborhood, shaderContract);
        try {
            while (!candidate.isComplete()) candidate.step(CubePos.VOLUME, Long.MAX_VALUE);
            return candidate.finish(); // finish transfers ownership only after all CPU cleanup succeeds.
        } catch (RuntimeException | Error failure) {
            try { candidate.close(); }
            catch (RuntimeException | Error cleanup) {
                // A cleanup owner must never be hidden as suppressed on a recoverable sparse-halo deferral.
                CleanupException retained = cleanup instanceof CleanupException existing
                        && existing.cleanupOwner() == candidate ? existing : new CleanupException(candidate, cleanup);
                if (failure != retained) retained.addSuppressed(failure);
                throw retained;
            }
            throw failure;
        }
    }

    /** A soft time slice cannot interrupt one native model callback. All durations are measured, not guarantees. */
    public record StepStats(int blocks, int completedBlocks, int totalBlocks, long elapsedNanos,
                            long budgetNanos, long overshootNanos, long maxBlockNanos, boolean complete) {}

    /** Retains the cleanup owner even if a synchronous build fails and its close also fails. */
    public static final class CleanupException extends RuntimeException {
        private final Build build;
        private CleanupException(Build build, Throwable cause) {
            super("Scene build cleanup failed; retain cleanupOwner() and retry close() on its render owner", cause);
            this.build = build;
        }
        public Build cleanupOwner() { return build; }
    }

    /**
     * Owner-exclusive retained CPU work. A failed/cancelled candidate can only be closed, never resumed.
     * Callers must retain a candidate whose close fails until isCloseComplete() becomes true.
     */
    public static final class Build implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final RenderPublicationGuard guard;
        private final RenderPublicationGuard.Job job;
        private final SceneCube cube;
        private final SceneNeighborhood reads;
        private final Map<CubePos, RenderPublicationGuard.Job> dependencies = new LinkedHashMap<>();
        private final SceneReadFootprint.Recorder footprint = new SceneReadFootprint.Recorder();
        private final Map<RenderType, CpuPass> cpu = new LinkedHashMap<>();
        private final List<Pass> gpu = new ArrayList<>();
        private final SceneNativeShaderContract.Budget budget = new SceneNativeShaderContract.Budget();
        private final SceneBuildResources cpuResources = new SceneBuildResources();
        private final SceneBuildResources gpuResources = new SceneBuildResources();
        private final BlockRenderDispatcher renderer;
        private final PoseStack pose = new PoseStack();
        private final RandomSource random = RandomSource.create(0);
        private final SceneAppearanceSignature appearance;
        private final SceneNativeShaderContract.Capture shaderContract;
        private final int originX, originY, originZ;
        private final SceneBuildStepper stepper;
        private boolean failed, closed, closeComplete, finished;
        private long finishNanos;
        private SceneGlRestoreOwner pendingUploadRestore;

        private Build(RenderPublicationGuard guard, SceneCube cube, SceneNeighborhood neighborhood,
                      SceneNativeShaderContract.Capture shaderContract) {
            this.guard = Objects.requireNonNull(guard);
            this.cube = Objects.requireNonNull(cube);
            Objects.requireNonNull(neighborhood);
            this.shaderContract = Objects.requireNonNull(shaderContract);
            shaderContract.requireCurrent();
            if (shaderContract.values().shaders()
                    && (neighborhood.lightingPolicy() != SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE
                    || neighborhood.directionalShadingDisabled() != shaderContract.values().disableDirectionalShading()))
                throw new IllegalArgumentException("Shader meshes require the captured outdoor lighting and directional-shade policy");
            if (neighborhood.cube(cube.snapshot().position()) != cube) {
                throw new IllegalArgumentException("The exact immutable cube must belong to its compilation neighborhood");
            }
            job = guard.prepare(cube.snapshot());
            dependencies.put(job.position(), job);
            // Only actual reads create dependencies. The immutable neighborhood keeps its original snapshots:
            // first prepare on a later slice therefore rejects a replaced source rather than blessing stale data.
            reads = neighborhood.tracking(dependency -> {
                checkOwner();
                // Reject before preparing/retaining provenance; the facade poisons even a caught failure.
                SceneReadFootprint.requireHaloOwner(job.position(), dependency.snapshot().position());
                var captured = dependencies.computeIfAbsent(dependency.snapshot().position(),
                        ignored -> guard.prepare(dependency.snapshot()));
                if (!guard.isCurrent(captured)) throw new IllegalStateException("Scene read dependency became stale");
            }, footprint);
            var capturedAppearance = readAppearance();
            renderer = (BlockRenderDispatcher) capturedAppearance.renderer();
            appearance = new SceneAppearanceSignature(capturedAppearance);
            originX = Math.toIntExact(job.position().minBlockX());
            originY = Math.toIntExact(job.position().minBlockY());
            originZ = Math.toIntExact(job.position().minBlockZ());
            stepper = new SceneBuildStepper(CubePos.VOLUME, this::requireCallbackValid, this::compileBlock,
                    this::sliceBoundary, this::sliceBoundary, System::nanoTime);
        }

        public StepStats step(int maxBlocks, long maxNanos) {
            checkOpen();
            try { return stats(stepper.step(maxBlocks, maxNanos)); }
            catch (RuntimeException error) { failed = true; throw scopedFailure(error); }
            catch (Error error) { failed = true; throw error; }
        }

        private void compileBlock(int index) {
            int x = index & 15, z = (index >>> 4) & 15, y = index >>> 8;
            var state = cube.state(x, y, z);
            if (state.isAir()) return;
            var pos = new BlockPos(originX + x, originY + y, originZ + z);
            random.setSeed(state.getSeed(pos));
            var layers = renderer.getBlockModel(state).getRenderTypes(state, random, ModelData.EMPTY);
            for (var layer : layers) {
                if (layer != RenderType.solid() && layer != RenderType.cutoutMipped() && layer != RenderType.cutout()) {
                    throw new SparseFacadeException("scene model requests an unsupported render layer: " + layer);
                }
                // Register ownership before any native allocation or fallible builder initialization.
                CpuPass pass = cpu.computeIfAbsent(layer, ignored -> {
                    var allocated = new CpuPass();
                    cpuResources.retain(allocated::close);
                    return allocated;
                });
                pass.initialize(budget, shaderContract);
                pose.pushPose();
                SceneDrawPhases.restoring(() -> {
                    // Cleanup is installed before beginBlock: even a partially throwing hook is ended.
                    shaderContract.beginBlock(pass.builder, state, x, y, z);
                    pose.translate(x, y, z);
                    renderer.renderBatched(state, pos, reads, pose, pass.consumer, true, random, ModelData.EMPTY, layer);
                    return null;
                }, () -> SceneDrawPhases.cleanupAll(() -> shaderContract.endBlock(pass.builder), pose::popPose));
            }
        }

        /** Finalization/upload is separately timed; it is not covered by the CPU slice's soft budget. */
        public SceneMesh finish() {
            checkOpen();
            if (!stepper.complete()) throw new IllegalStateException("All native block callbacks must finish before upload");
            long start = System.nanoTime();
            try {
                requireValid();
                int vertices = 0, bytes = 0;
                for (var pass : cpu.values()) {
                    pass.mesh = pass.builder.build();
                    if (pass.mesh == null) continue;
                    int count = pass.mesh.drawState().vertexCount();
                    int length = pass.mesh.vertexBuffer().remaining();
                    var actual = pass.mesh.drawState();
                    shaderContract.requireFormat(actual.format(), actual.format().getVertexSize());
                    if (actual.mode() != VertexFormat.Mode.QUADS || count % 4 != 0
                            || actual.format() != pass.format || actual.format().getVertexSize() != pass.stride
                            || length != Math.multiplyExact(count, pass.stride)) {
                        throw new IllegalStateException("Native scene mesh must contain complete, captured-format quads");
                    }
                    vertices = Math.addExact(vertices, count);
                    bytes = Math.addExact(bytes, length);
                }
                if (vertices != budget.vertices || bytes != budget.bytes || vertices > MAX_VERTICES || bytes > MAX_VERTEX_BYTES) {
                    throw new IllegalStateException("Native scene geometry exceeded its bounded vertex budget");
                }
                requireValid();
                boolean strictGl = shaderContract.values().shaders();
                if (strictGl) StrictGlErrors.check("native.upload.entry");
                var bindings = new BufferBindings();
                if (strictGl) StrictGlErrors.check("native.upload.capture");
                pendingUploadRestore = new SceneGlRestoreOwner(bindings::restore,
                        () -> { if (strictGl) StrictGlErrors.check("native.upload.restore.entry"); },
                        () -> { if (strictGl) StrictGlErrors.check("native.upload.restore.exit"); });
                Throwable uploadFailure = null;
                try {
                    // Empty geometry crosses exactly the same fence; no dummy quad/VBO represents known air.
                    if (!guard.publish(job, () -> {
                        requireValid();
                        for (var layer : List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout())) {
                            CpuPass pass = cpu.get(layer);
                            if (pass == null || pass.mesh == null) continue;
                            // Register a mutable owner BEFORE constructing the VBO or allocating its Pass.
                            // The upstream constructor itself still exposes no rollback hook if it throws.
                            var allocated = new GpuOwner();
                            gpuResources.retain(allocated);
                            allocated.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                            if (strictGl) StrictGlErrors.check("native.upload.allocate");
                            gpu.add(new Pass(layer, allocated.buffer, job.position(), pass.mesh.drawState().vertexCount()));
                            allocated.buffer.bind();
                            if (strictGl) StrictGlErrors.check("native.upload.bind");
                            allocated.buffer.upload(pass.mesh);
                            if (strictGl) StrictGlErrors.check("native.upload.data");
                        }
                        requireValid();
                    })) throw new IllegalStateException("Scene mesh became stale before native publication");
                } catch (RuntimeException | Error error) { uploadFailure = error; }
                finally {
                    try { restoreUploadBindings(); }
                    catch (RuntimeException | Error error) { uploadFailure = SceneBuildStepper.append(uploadFailure, error); }
                }
                SceneBuildStepper.rethrow(uploadFailure);
                // Do not transfer GPU ownership or return a mesh until every CPU close succeeds.
                closeCpu();
                requireValid();
                var result = new SceneMesh(guard, job, new ArrayList<>(dependencies.values()), gpu, vertices, bytes,
                        appearance, footprint.seal(), reads.envelope(), shaderContract, reads.lightingPolicy(), reads.directionalShadingDisabled());
                gpuResources.releaseToPublishedOwner();
                gpu.clear();
                finished = true;
                closeComplete = true;
                return result;
            } catch (RuntimeException error) { failed = true; throw scopedFailure(error); }
            catch (Error error) { failed = true; throw error; }
            finally { finishNanos = Math.max(0, System.nanoTime() - start); }
        }

        private void clearModelCaches() {
            // Native BlockPos packs Y in 12 bits. Never enable the aliasing light/AO cache between slices.
            Throwable failure = null;
            try { ModelBlockRenderer.clearCache(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            if (renderer.getModelRenderer() instanceof LightPipelineAwareModelBlockRenderer nativeRenderer) {
                try { nativeRenderer.getQuadLighter(false).reset(); }
                catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
                try { nativeRenderer.getQuadLighter(true).reset(); }
                catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            }
            SceneBuildStepper.rethrow(failure);
        }
        private void sliceBoundary() {
            Throwable failure = null;
            try { clearModelCaches(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            try { requireValid(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            SceneBuildStepper.rethrow(failure);
        }
        private void requireCallbackValid() {
            checkOpen();
            // Cheap per-voxel root/reload check. Every actual neighbor read validates that owner immediately.
            // Full actual dependency + appearance checks bracket each slice, including its final callback.
            if (!guard.isCurrent(job)) throw new IllegalStateException("Scene build owner became stale or cancelled");
        }
        private void requireValid() {
            checkOpen();
            footprint.requireValid();
            shaderContract.requireCurrent();
            requireCurrent(guard, dependencies.values());
            if (!appearance.isCurrent(SceneMesh::readAppearance)) {
                throw new IllegalStateException("Native renderer appearance changed during scene compilation");
            }
        }
        public boolean isCurrent() {
            checkOwner();
            if (failed || closed || finished) return false;
            try { requireValid(); return true; }
            catch (IllegalStateException stale) { return false; }
        }
        public boolean isComplete() { checkOwner(); return !failed && !closed && stepper.complete(); }
        public boolean isCloseComplete() { checkOwner(); return closeComplete; }
        public int blocksCompleted() { return stepper.completed(); }
        public int totalBlocks() { return stepper.total(); }
        public int steps() { return stepper.steps(); }
        public long totalNanos() { return stepper.totalNanos(); }
        public long maxStepNanos() { return stepper.maxStepNanos(); }
        public long maxOvershootNanos() { return stepper.maxOvershootNanos(); }
        public long maxBlockNanos() { return stepper.maxBlockNanos(); }
        public long finishNanos() { checkOwner(); return finishNanos; }
        /** Actual live native CPU storage capacity, allocated lazily per encountered render layer. */
        public long retainedCpuBytes() {
            checkOwner();
            return cpu.values().stream().filter(pass -> pass.storage != null).count() * MAX_VERTEX_BYTES;
        }
        public long footprintMetadataBytes() { checkOwner(); return footprint.accountingBytes() + shaderContract.metadataBytes(); }
        public List<EngineCubeSnapshot> retainedSnapshots() {
            checkOwner();
            var identities = new java.util.IdentityHashMap<EngineCubeSnapshot, Boolean>();
            for (var snapshot : reads.retainedSnapshots()) identities.put(snapshot, Boolean.TRUE);
            identities.put(cube.snapshot(), Boolean.TRUE);
            for (var dependency : dependencies.values()) identities.put(dependency.snapshot(), Boolean.TRUE);
            return List.copyOf(identities.keySet());
        }
        public int ownedGpuBuffers() { checkOwner(); return gpuResources.size(); }
        public StepStats lastStep() { return stats(stepper.last()); }
        public EngineCubeSnapshot snapshot() { return job.snapshot(); }
        public RenderPublicationGuard.Job job() { return job; }
        private static StepStats stats(SceneBuildStepper.StepStats value) {
            return new StepStats(value.blocks(), value.completedBlocks(), value.totalBlocks(), value.elapsedNanos(),
                    value.budgetNanos(), value.overshootNanos(), value.maxBlockNanos(), value.complete());
        }
        private void checkOpen() {
            checkOwner();
            if (failed || closed || finished) throw new IllegalStateException("Scene build is not open");
        }
        private void checkOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Scene build requires its render owner thread");
            RenderSystem.assertOnRenderThread();
        }
        private void closeCpu() { cpuResources.close(); }
        private void restoreUploadBindings() {
            if (pendingUploadRestore == null) return;
            pendingUploadRestore.restore();
            pendingUploadRestore = null;
        }
        @Override public void close() {
            checkOwner();
            if (closeComplete) return;
            closed = true;
            footprint.cancel();
            stepper.cancel();
            // Reentrant callbacks may request cancellation, but must not free their active vertex writer.
            if (stepper.stepping()) throw new CleanupException(this,
                    new IllegalStateException("Retry cleanup after the active native callback returns"));
            Throwable failure = null;
            try { clearModelCaches(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            try { closeCpu(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            try { restoreUploadBindings(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            try { gpuResources.close(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            if (failure != null) throw new CleanupException(this, failure);
            closeComplete = true;
        }
    }

    private static SceneAppearanceSignature.Values readAppearance() {
        RenderSystem.assertOnRenderThread();
        var minecraft = Minecraft.getInstance();
        return new SceneAppearanceSignature.Values(minecraft.getBlockRenderer(), Minecraft.useAmbientOcclusion(),
                minecraft.options.graphicsMode().get(), NeoForgeConfig.CLIENT.experimentalForgeLightPipelineEnabled.get());
    }

    private static RuntimeException scopedFailure(RuntimeException failure) {
        // Native dispatch wraps unknown reads in crash reports; retain the useful scoped deferral signal.
        Throwable cause = failure;
        for (int i = 0; cause != null && i < 32; i++, cause = cause.getCause()) {
            if (cause instanceof SparseFacadeException unknown) {
                if (unknown != failure) for (var cleanup : failure.getSuppressed()) {
                    if (cleanup != unknown) unknown.addSuppressed(cleanup);
                }
                return unknown;
            }
        }
        return failure;
    }

    public EngineCubeSnapshot snapshot() { return job.snapshot(); }
    public RenderPublicationGuard.Job job() { return job; }
    public int vertices() { return vertices; }
    public int bytes() { return bytes; }
    public boolean isEmpty() { return vertices == 0; }
    /** Exact physical-mesh identity for bounded frozen-presentation capture, never a readiness stamp. */
    public RenderPublicationGuard.PresentationSource presentationSource() { return binding.source(); }
    private boolean presentationUsable() {
        RenderSystem.assertOnRenderThread();
        if (closed || appearanceInvalidated) return false;
        if (!appearance.isCurrent(SceneMesh::readAppearance) || !shaderContract.isCurrent()) appearanceInvalidated = true;
        return !appearanceInvalidated;
    }

    /** Includes every sampled culling/AO neighbor, not merely the rendered cube's own lease. */
    public boolean isCurrent() {
        RenderSystem.assertOnRenderThread();
        if (!presentationUsable()) return false;
        for (var dependency : binding.dependencies()) if (!guard.isCurrent(dependency)) return false;
        return true;
    }

    public static final int MAX_FOOTPRINT_OBSERVATIONS = SceneReadFootprint.MAX_READS;
    public boolean footprintRevalidatable() { return footprint.reusable(); }
    public static final int MAX_REUSE_VALUE_COMPARISONS = SceneReadFootprint.MAX_READS;
    public int footprintReadCount() { return footprint.readCount(); }
    public int footprintPositionCount() { return footprint.positionCount(); }
    public int footprintOwnerCount() { return footprint.ownerCount(); }
    public long footprintMetadataBytes() { return footprint.accountingBytes() + shaderContract.metadataBytes(); }
    /** Diagnostics-only identity list, including private new bindings; no cloned cubes or persistent storage. */
    public List<EngineCubeSnapshot> retainedSnapshots() {
        RenderSystem.assertOnRenderThread();
        var identities = new java.util.IdentityHashMap<EngineCubeSnapshot, Boolean>();
        identities.put(job.snapshot(), Boolean.TRUE);
        for (var dependency : binding.dependencies()) identities.put(dependency.snapshot(), Boolean.TRUE);
        if (pendingReuse != null && !pendingReuse.closed) {
            for (var dependency : pendingReuse.expected.dependencies()) identities.put(dependency.snapshot(), Boolean.TRUE);
            for (var dependency : pendingReuse.dependencies) identities.put(dependency.snapshot(), Boolean.TRUE);
        }
        return List.copyOf(identities.keySet());
    }
    public int lastReuseComparedValues() { return lastReuseComparedValues; }
    public long cumulativeReuseComparedValues() { return cumulativeReuseComparedValues; }
    public long successfulReusePreparations() { return successfulReusePreparations; }

    /**
     * Private revision-only reuse preparation. No source, Job, VBO, or published currency is modified.
     * The exact primary snapshot stays strict; only actual neighboring getter inputs may be revalidated.
     */
    public Reuse prepareReuse(SceneNeighborhood current) {
        RenderSystem.assertOnRenderThread(); Objects.requireNonNull(current);
        lastReuseComparedValues = 0;
        if (pendingReuse != null) throw new IllegalStateException("One pending reuse is allowed per physical scene mesh");
        if (!presentationUsable() || !footprint.reusable() || !guard.isCurrent(job) || envelope != current.envelope()
                || lightingPolicy != current.lightingPolicy()
                || directionalShadingDisabled != current.directionalShadingDisabled()) return null;
        SceneCube primary = current.cube(job.position());
        if (primary == null || primary.snapshot() != job.snapshot()) return null;
        Binding original = binding;
        var replacement = new ArrayList<RenderPublicationGuard.Job>(original.dependencies().size());
        var changed = new java.util.LinkedHashSet<CubePos>();
        try {
            for (var prior : original.dependencies()) {
                SceneCube cube = current.cube(prior.position());
                if (cube == null) return null;
                if (guard.isCurrent(prior)) {
                    // Matching metadata cannot substitute a different immutable source payload.
                    if (cube.snapshot() != prior.snapshot()) return null;
                    replacement.add(prior);
                } else {
                    if (prior == job) return null;
                    var next = guard.prepare(cube.snapshot());
                    if (!guard.isSameLiveLease(prior, next)) return null;
                    replacement.add(next); changed.add(prior.position());
                }
            }
            var validation = footprint.validate(changed, current::replay);
            lastReuseComparedValues = validation.compared();
            cumulativeReuseComparedValues = saturatingAdd(cumulativeReuseComparedValues, validation.compared());
            if (!validation.matches() || !presentationUsable() || binding != original) return null;
            var dependencies = List.copyOf(replacement);
            if (!guard.drawIfCurrent(dependencies, () -> presentationUsable() && binding == original)) return null;
            var result = new Reuse(original, dependencies);
            pendingReuse = result;
            successfulReusePreparations = saturatingAdd(successfulReusePreparations, 1);
            return result;
        } catch (IllegalStateException staleOrUnsupported) { return null; }
    }

    /** Bounded metadata-only candidate. Commit is permitted only after historical presentation retirement. */
    public final class Reuse implements AutoCloseable {
        private Binding expected;
        private List<RenderPublicationGuard.Job> dependencies;
        private boolean closed, invalidated;
        private Reuse(Binding expected, List<RenderPublicationGuard.Job> dependencies) {
            this.expected = expected; this.dependencies = dependencies;
        }
        public boolean isCurrent() {
            RenderSystem.assertOnRenderThread();
            if (closed || invalidated) return false;
            if (pendingReuse != this || binding != expected || !presentationUsable() || !guard.isCurrent(job)) {
                invalidated = true; return false;
            }
            for (var dependency : dependencies) if (!guard.isCurrent(dependency)) { invalidated = true; return false; }
            return true;
        }
        public void commit() {
            RenderSystem.assertOnRenderThread();
            if (!isCurrent()) throw new IllegalStateException("Scene reuse is stale or cancelled");
            if (guard.isRetained(expected.source())) throw new IllegalStateException("Retire the old presentation before committing scene reuse");
            if (!guard.drawIfCurrent(dependencies, () -> {
                if (!isCurrent() || guard.isRetained(expected.source())) return false;
                var source = guard.presentationSource(job, dependencies, SceneMesh.this::presentationUsable);
                var previous = expected.source();
                binding = new Binding(dependencies, source);
                previous.close();
                return true;
            })) throw new IllegalStateException("Scene reuse changed before commit");
            close();
        }
        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            if (closed) return;
            closed = true; expected = null; dependencies = List.of();
            if (pendingReuse == this) pendingReuse = null;
        }
    }
    private static long saturatingAdd(long current, long increment) {
        return current > Long.MAX_VALUE - increment ? Long.MAX_VALUE : current + increment;
    }

    /** True means current native coverage, including a successfully published empty mesh. */
    public boolean draw(Matrix4f modelViewWithoutOrigin, Matrix4f projection,
                        double cameraX, double cameraY, double cameraZ, int fixedLightTexture) {
        return draw(modelViewWithoutOrigin,projection,cameraX,cameraY,cameraZ,fixedLightTexture,null);
    }

    /** Optional bounded owner-clock phases; the exact current admission fence remains unchanged. */
    public boolean draw(Matrix4f modelViewWithoutOrigin, Matrix4f projection,
                        double cameraX, double cameraY, double cameraZ, int fixedLightTexture,
                        RenderSubmissionProbe probe) {
        RenderSystem.assertOnRenderThread();
        if (closed || shaderContract.values().shaders()) return false;
        drawAttempts=saturatingAdd(drawAttempts,1);
        var phases = phases(probe); String detail = drawAttempted ? drawDetail : firstDrawDetail; drawAttempted=true;
        return phases.run(() -> phases.capture(Stage.NATIVE_OWNER_CALL, detail,
                () -> guard.drawIfCurrent(binding.dependencies(),
                        () -> phases.capture(Stage.NATIVE_VALIDATED_BODY, detail,
                                () -> drawValidated(modelViewWithoutOrigin, projection,cameraX,cameraY,cameraZ,
                                        fixedLightTexture,this::isCurrent,phases,detail)))));
    }

    /** Frozen visual presentation only. This cannot make isCurrent(), collision, or edit readiness true. */
    public boolean drawPresented(RenderPublicationGuard.PresentationToken token,
                                 Matrix4f modelViewWithoutOrigin, Matrix4f projection,
                                 double cameraX, double cameraY, double cameraZ, int fixedLightTexture) {
        return drawPresented(token,modelViewWithoutOrigin,projection,cameraX,cameraY,cameraZ,fixedLightTexture,null);
    }

    /** Current and historical calls share instrumentation, never their distinct admission predicates. */
    public boolean drawPresented(RenderPublicationGuard.PresentationToken token,
                                 Matrix4f modelViewWithoutOrigin, Matrix4f projection,
                                 double cameraX, double cameraY, double cameraZ, int fixedLightTexture,
                                 RenderSubmissionProbe probe) {
        RenderSystem.assertOnRenderThread();
        if (closed || shaderContract.values().shaders()) return false;
        drawAttempts=saturatingAdd(drawAttempts,1);
        var phases = phases(probe); String detail = drawAttempted ? drawDetail : firstDrawDetail; drawAttempted=true;
        return phases.run(() -> phases.capture(Stage.NATIVE_OWNER_CALL, detail,
                () -> guard.drawIfPresented(token,binding.source(),
                        () -> phases.capture(Stage.NATIVE_VALIDATED_BODY, detail,
                                () -> drawValidated(modelViewWithoutOrigin, projection,cameraX,cameraY,cameraZ,
                                        fixedLightTexture,() -> guard.isPresented(token,binding.source()),phases,detail)))));
    }

    /** Submit only the current real terrain/shadow layer; no diagnostic texture or fog overrides. */
    public boolean drawLayer(SceneNativeShaderContract.ShaderPass shaderPass,
                             Matrix4f modelViewWithoutOrigin, Matrix4f projection,
                             double cameraX, double cameraY, double cameraZ, RenderSubmissionProbe probe) {
        RenderSystem.assertOnRenderThread();
        if (closed || !shaderContract.values().shaders()) return false;
        Objects.requireNonNull(shaderPass);
        drawAttempts=saturatingAdd(drawAttempts,1);
        var phases=phases(probe); String detail=drawAttempted?drawDetail:firstDrawDetail; drawAttempted=true;
        return phases.run(() -> phases.capture(Stage.NATIVE_OWNER_CALL,detail,
                () -> guard.drawIfCurrent(binding.dependencies(),
                        () -> phases.capture(Stage.NATIVE_VALIDATED_BODY,detail,
                                () -> drawShaderValidated(shaderPass,modelViewWithoutOrigin,projection,cameraX,cameraY,cameraZ,
                                        this::isCurrent,phases,detail)))));
    }

    /** Historical display capability is still checked independently from current readiness. */
    public boolean drawPresentedLayer(RenderPublicationGuard.PresentationToken token,
                                      SceneNativeShaderContract.ShaderPass shaderPass,
                                      Matrix4f modelViewWithoutOrigin, Matrix4f projection,
                                      double cameraX, double cameraY, double cameraZ, RenderSubmissionProbe probe) {
        RenderSystem.assertOnRenderThread();
        if (closed || !shaderContract.values().shaders()) return false;
        Objects.requireNonNull(shaderPass);
        drawAttempts=saturatingAdd(drawAttempts,1);
        var phases=phases(probe); String detail=drawAttempted?drawDetail:firstDrawDetail; drawAttempted=true;
        return phases.run(() -> phases.capture(Stage.NATIVE_OWNER_CALL,detail,
                () -> guard.drawIfPresented(token,binding.source(),
                        () -> phases.capture(Stage.NATIVE_VALIDATED_BODY,detail,
                                () -> drawShaderValidated(shaderPass,modelViewWithoutOrigin,projection,cameraX,cameraY,cameraZ,
                                        () -> guard.isPresented(token,binding.source()),phases,detail)))));
    }

    private boolean drawShaderValidated(SceneNativeShaderContract.ShaderPass host,
                                         Matrix4f view, Matrix4f projection, double cameraX, double cameraY, double cameraZ,
                                         java.util.function.BooleanSupplier valid, SceneDrawPhases<Stage,Stage> phases, String detail) {
        java.util.function.BooleanSupplier admitted=() -> valid.getAsBoolean() && host.isCurrent()
                && shaderContract.matches(host.capture());
        if (!phases.capture(Stage.NATIVE_VALIDITY_CHECK,detail,admitted::getAsBoolean)) return false;
        Objects.requireNonNull(view); Objects.requireNonNull(projection);
        RenderType layer=Objects.requireNonNull(host.layer()); layerId(layer);
        Pass selected=null;
        for (var pass:passes) if (pass.layer()==layer) { selected=pass; break; }
        if (selected==null) return phases.capture(Stage.NATIVE_VALIDITY_CHECK,detail,admitted::getAsBoolean);
        final Pass pass=selected;
        final ShaderInstance selectedShader=Objects.requireNonNull(host.shader());
        shaderContract.requireFormat(selectedShader.getVertexFormat(), selectedShader.getVertexFormat().getVertexSize());
        var origin=CameraRelativeOrigin.of(job.position(),cameraX,cameraY,cameraZ);
        var modelView=new Matrix4f(view).translate(origin.x(),origin.y(),origin.z());
        pass.drawAttempts=saturatingAdd(pass.drawAttempts,1); String passDetail=pass.detail();
        // The host snapshot owns all Iris FBO/blend/sampler/image state beyond the vanilla snapshot.
        Runnable restoreHost=phases.capture(Stage.NATIVE_STATE_CAPTURE,passDetail,
                () -> Objects.requireNonNull(host.captureState(),"shader host restoration owner"));
        return SceneDrawPhases.restoring(() -> {
            var previous=phases.capture(Stage.NATIVE_STATE_CAPTURE,passDetail,DrawState::new);
            var draw=new PassDraw(pass,previous,selectedShader);
            return SceneDrawPhases.restoring(() -> {
                phases.phase(Stage.NATIVE_RENDER_SETUP,passDetail,() -> { host.setup(); draw.setup(); });
                phases.phase(Stage.NATIVE_VBO_BIND,passDetail,() -> pass.buffer().bind());
                if (!phases.capture(Stage.NATIVE_VALIDITY_CHECK,passDetail,admitted::getAsBoolean)) return false;
                phases.phase(Stage.NATIVE_DRAW_WITH_SHADER,passDetail,() -> {
                    pass.submitAttempted=true; pass.buffer().drawWithShader(modelView,projection,selectedShader);
                });
                return phases.capture(Stage.NATIVE_VALIDITY_CHECK,passDetail,admitted::getAsBoolean);
            },() -> SceneDrawPhases.cleanupAll(
                    () -> phases.phase(Stage.NATIVE_SHADER_RESTORE,passDetail,draw::clear),
                    () -> phases.phase(Stage.NATIVE_STATE_RESTORE,passDetail,previous::restore)));
        },() -> phases.phase(Stage.NATIVE_STATE_RESTORE,passDetail,restoreHost::run));
    }

    private SceneDrawPhases<Stage,Stage> phases(RenderSubmissionProbe probe) {
        return new SceneDrawPhases<>(probe==null?null:new NativeTiming(probe,job.position(),vertices,drawAttempts,passes,drawDetail,firstDrawDetail));
    }
    /** Reuses enum tokens rather than allocating one token record for every native phase. */
    private static final class NativeTiming implements SceneDrawPhases.Timing<Stage,Stage> {
        private final RenderSubmissionProbe probe;
        private final long[] tokens = new long[9];
        private final CubePos position; private final int vertices; private final long drawAttempt;
        private final List<Pass> passes; private final String meshDetail,firstMeshDetail;
        NativeTiming(RenderSubmissionProbe probe,CubePos position,int vertices,long drawAttempt,List<Pass> passes,String meshDetail,String firstMeshDetail){
            this.probe=probe;this.position=position;this.vertices=vertices;this.drawAttempt=drawAttempt;
            this.passes=passes;this.meshDetail=meshDetail;this.firstMeshDetail=firstMeshDetail;
        }
        private static int index(Stage stage){return switch(stage){
            case NATIVE_OWNER_CALL -> 0; case NATIVE_VALIDATED_BODY -> 1; case NATIVE_VALIDITY_CHECK -> 2;
            case NATIVE_STATE_CAPTURE -> 3; case NATIVE_RENDER_SETUP -> 4; case NATIVE_VBO_BIND -> 5;
            case NATIVE_DRAW_WITH_SHADER -> 6; case NATIVE_SHADER_RESTORE -> 7; case NATIVE_STATE_RESTORE -> 8;
            default -> throw new IllegalArgumentException("Native phase required");};}
        public Stage begin(Stage stage,String detail){
            int layer=-1,count=vertices;long attempt=drawAttempt;
            if(detail!=meshDetail&&detail!=firstMeshDetail){
                boolean found=false;
                for(var pass:passes)if(detail==pass.detail||detail==pass.firstDetail){layer=pass.layerId;count=pass.vertices;attempt=pass.drawAttempts;found=true;break;}
                if(!found)throw new IllegalArgumentException("Unknown precomputed native timing identity");
            }
            long token=probe.startNativePhase(stage,detail,position.x(),position.y(),position.z(),layer,count,attempt);
            tokens[index(stage)]=token;return stage;
        }
        public void end(Stage stage){probe.finish(stage,tokens[index(stage)]);}
    }

    private boolean drawValidated(Matrix4f modelViewWithoutOrigin, Matrix4f projection,
                                  double cameraX, double cameraY, double cameraZ, int fixedLightTexture,
                                  java.util.function.BooleanSupplier valid,SceneDrawPhases<Stage,Stage> phases,String detail) {
        if (!phases.capture(Stage.NATIVE_VALIDITY_CHECK,detail,valid::getAsBoolean)) return false;
        Objects.requireNonNull(modelViewWithoutOrigin);
        Objects.requireNonNull(projection);
        var origin = CameraRelativeOrigin.of(job.position(), cameraX, cameraY, cameraZ);
        if (isEmpty()) return phases.capture(Stage.NATIVE_VALIDITY_CHECK,detail,valid::getAsBoolean);
        if (fixedLightTexture <= 0) throw new IllegalArgumentException("A live fixed diagnostic light texture is required");
        var modelView = new Matrix4f(modelViewWithoutOrigin).translate(origin.x(), origin.y(), origin.z());
        var previous = phases.capture(Stage.NATIVE_STATE_CAPTURE,detail,DrawState::new);
        return SceneDrawPhases.restoring(() -> {
            phases.phase(Stage.NATIVE_RENDER_SETUP,detail,() -> {
                RenderSystem.disableBlend(); RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL_LEQUAL);
                RenderSystem.depthMask(true); RenderSystem.enableCull(); RenderSystem.colorMask(true,true,true,true);
                RenderSystem.setShaderColor(1,1,1,1); RenderSystem.setShaderFogStart(1_000_000); RenderSystem.setShaderFogEnd(2_000_000);
                RenderSystem.setShaderTexture(0,TextureAtlas.LOCATION_BLOCKS); RenderSystem.setShaderTexture(2,fixedLightTexture);
            });
            for (var pass : passes) {
                pass.drawAttempts=saturatingAdd(pass.drawAttempts,1);String passDetail=pass.detail();
                if (!phases.capture(Stage.NATIVE_VALIDITY_CHECK,passDetail,valid::getAsBoolean)) return false;
                var draw = new PassDraw(pass,previous);
                boolean accepted = SceneDrawPhases.restoring(() -> {
                    phases.phase(Stage.NATIVE_RENDER_SETUP,passDetail,draw::setup);
                    phases.phase(Stage.NATIVE_VBO_BIND,passDetail,() -> pass.buffer().bind());
                    if (!phases.capture(Stage.NATIVE_VALIDITY_CHECK,passDetail,valid::getAsBoolean)) return false;
                    phases.phase(Stage.NATIVE_DRAW_WITH_SHADER,passDetail,() -> {
                        // First use means first VBO submission attempt, never inferred driver compilation.
                        pass.submitAttempted=true; pass.buffer().drawWithShader(modelView,projection,draw.shader);
                    });
                    return true;
                },() -> phases.phase(Stage.NATIVE_SHADER_RESTORE,passDetail,draw::clear));
                if (!accepted) return false;
            }
            return phases.capture(Stage.NATIVE_VALIDITY_CHECK,detail,valid::getAsBoolean);
        },() -> phases.phase(Stage.NATIVE_STATE_RESTORE,detail,previous::restore));
    }

    /** Retained before shader/uniform mutation; timing-end faults cannot discard this cleanup owner. */
    private static final class PassDraw {
        private final Pass pass; private final DrawState previous;
        private final ShaderInstance selectedShader;
        private ShaderInstance shader; private ShaderUniforms uniforms;
        PassDraw(Pass pass,DrawState previous){this(pass,previous,null);}
        PassDraw(Pass pass,DrawState previous,ShaderInstance selectedShader){
            this.pass=pass;this.previous=previous;this.selectedShader=selectedShader;
        }
        void setup(){
            shader=selectedShader==null?shader(pass.layer()):selectedShader; uniforms=new ShaderUniforms(shader);
            RenderSystem.setShader(() -> shader);
            if(shader.CHUNK_OFFSET!=null)shader.CHUNK_OFFSET.set(0.0f,0.0f,0.0f);
        }
        void clear(){
            if(uniforms==null)return;
            SceneDrawPhases.cleanupAll(shader::clear,() -> uniforms.restore(shader),this::restoreSamplers);
        }
        private void restoreSamplers(){
            Throwable failure=null;
            for(int i=0;i<previous.shaderTextures.length;i++){
                try{shader.setSampler("Sampler"+i,previous.shaderTextures[i]);}
                catch(RuntimeException|Error error){failure=SceneDrawPhases.append(failure,error);}
            }
            SceneDrawPhases.rethrow(failure);
        }
    }
    private static String drawDetail(CubePos position,String layer,int vertices,boolean firstUse){
        return "cube="+position.x()+","+position.y()+","+position.z()+" layer="+layer+" vertices="+vertices+" firstUse="+firstUse;
    }
    private static int layerId(RenderType layer){
        if(layer==RenderType.solid())return 0;
        if(layer==RenderType.cutoutMipped())return 1;
        if(layer==RenderType.cutout())return 2;
        throw new IllegalArgumentException("Unsupported compiled layer");
    }
    private static String layerName(RenderType layer){
        if(layer==RenderType.solid())return "solid";
        if(layer==RenderType.cutoutMipped())return "cutout_mipped";
        if(layer==RenderType.cutout())return "cutout";
        throw new IllegalArgumentException("Unsupported compiled layer");
    }

    private static ShaderInstance shader(RenderType layer) {
        if (layer == RenderType.solid()) return Objects.requireNonNull(GameRenderer.getRendertypeSolidShader());
        if (layer == RenderType.cutoutMipped()) return Objects.requireNonNull(GameRenderer.getRendertypeCutoutMippedShader());
        if (layer == RenderType.cutout()) return Objects.requireNonNull(GameRenderer.getRendertypeCutoutShader());
        throw new IllegalStateException("Unsupported compiled layer");
    }

    private static void requireCurrent(RenderPublicationGuard guard, Iterable<RenderPublicationGuard.Job> jobs) {
        for (var dependency : jobs) if (!guard.isCurrent(dependency)) {
            throw new IllegalStateException("Scene compilation dependency became stale or cancelled");
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closeComplete) return;
        closed = true;
        if (pendingReuse != null) pendingReuse.close();
        binding.source().close();
        Throwable failure = null;
        for (var pass : passes) {
            try { pass.buffer().close(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        closeComplete = true;
    }

    private static final class CpuPass implements AutoCloseable {
        BoundedStorage storage;
        BufferBuilder builder;
        VertexFormat format;
        int stride;
        VertexConsumer consumer;
        MeshData mesh;
        void initialize(SceneNativeShaderContract.Budget budget, SceneNativeShaderContract.Capture contract) {
            if (builder != null) return;
            // The pinned ByteBufferBuilder resize overwrites its pointer before checking malloc failure.
            // Fixed maximum allocation avoids that lost-pointer path; three supported passes bound storage to 3 MiB.
            storage = new BoundedStorage(budget);
            builder = new BufferBuilder(storage, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            if (!(builder instanceof SceneBufferBuilderAccessor actual))
                throw new IllegalStateException("Pinned native BufferBuilder accessor is absent");
            format = actual.earthplatform$format(); stride = actual.earthplatform$vertexSize();
            if (format == null || stride != format.getVertexSize())
                throw new IllegalStateException("Native builder format/stride disagree");
            contract.requireFormat(format, stride);
            storage.configure(stride);
            consumer = new BoundedConsumer(builder, storage);
        }
        @Override public void close() {
            Throwable failure = null;
            if (mesh != null) {
                try { mesh.close(); mesh = null; }
                catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            }
            if (storage != null) {
                try { storage.close(); storage = null; }
                catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            }
            SceneBuildStepper.rethrow(failure);
        }
    }

    /** The actual allocator fence covers every native write path, including delegated bulk quads. */
    static final class BoundedStorage extends ByteBufferBuilder {
        private final SceneNativeShaderContract.Budget budget;
        private final Thread owner = Thread.currentThread();
        private int stride, retainedBytes, bulkRemaining = -1;
        BoundedStorage(SceneNativeShaderContract.Budget budget) { super(MAX_VERTEX_BYTES); this.budget = budget; }
        void configure(int stride) {
            checkOwner();
            if (this.stride != 0 || stride <= 0 || stride > MAX_VERTEX_BYTES)
                throw new IllegalStateException("Invalid fixed native storage stride");
            this.stride = stride;
        }
        void beginBulk() {
            checkOwner();
            if (bulkRemaining != -1 || stride == 0) throw new IllegalStateException("Nested/unconfigured native bulk output");
            budget.preflight(4, stride);
            if ((long) retainedBytes + 4L * stride > MAX_VERTEX_BYTES)
                throw new SparseFacadeException("Native pass storage budget exceeded before bulk emission");
            bulkRemaining = 4;
        }
        void endBulk(boolean success) {
            checkOwner();
            int remaining = bulkRemaining; bulkRemaining = -1;
            if (success && remaining != 0) throw new IllegalStateException("Native bulk quad did not emit exactly four vertices");
        }
        @Override public long reserve(int bytes) {
            checkOwner();
            if (stride == 0 || bytes <= 0 || bytes % stride != 0)
                throw new IllegalStateException("Unexpected native vertex reservation");
            int count = bytes / stride;
            // Sodium push reserves the complete destination quad at once. Generic writers reserve
            // one vertex. Neither path may escape the wrapper's exact four-vertex bulk scope.
            if ((count != 1 && count != 4) || (bulkRemaining < 0 && count != 1)
                    || (bulkRemaining >= 0 && count > bulkRemaining))
                throw new IllegalStateException("Native reservation exceeds its active vertex/quad scope");
            if (bytes > MAX_VERTEX_BYTES - retainedBytes)
                throw new SparseFacadeException("Native pass storage budget exceeded before allocation");
            budget.reserve(count, stride); // Charge actual vertices and bytes before native allocation/write.
            retainedBytes += bytes;
            if (bulkRemaining >= 0) bulkRemaining -= count;
            return super.reserve(bytes);
        }
        private void checkOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Native storage requires its render owner thread");
        }
    }

    /** Keep the real builder's Iris bulk/AO dispatch; storage independently fences every reservation. */
    static final class BoundedConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final BoundedStorage storage;
        BoundedConsumer(VertexConsumer delegate, BoundedStorage storage) { this.delegate = delegate; this.storage = storage; }
        @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x, y, z); return this; }
        @Override public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float r, float g, float b, float a, int light, int overlay) {
            requireQuad(quad); storage.beginBulk(); boolean success = false;
            try { delegate.putBulkData(pose, quad, r, g, b, a, light, overlay); success = true; }
            finally { storage.endBulk(success); }
        }
        @Override public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness,
                                          float r, float g, float b, float a, int[] lights, int overlay, boolean readColor) {
            requireQuad(quad);
            if (brightness == null || brightness.length != 4 || lights == null || lights.length != 4)
                throw new IllegalArgumentException("Native bulk lighting must describe exactly four vertices");
            storage.beginBulk(); boolean success = false;
            try { delegate.putBulkData(pose, quad, brightness, r, g, b, a, lights, overlay, readColor); success = true; }
            finally { storage.endBulk(success); }
        }
        private static void requireQuad(BakedQuad quad) {
            if (quad == null || quad.getVertices().length != 32)
                throw new IllegalArgumentException("Native baked quad must have exactly four eight-int vertices");
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
    }

    static class BufferBindings {
        final int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        void restore() {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        }
    }

    /** Restore both actual GL state and RenderSystem/GlStateManager's cached representation. */
    static final class DrawState extends BufferBindings {
        final int program = glGetInteger(GL_CURRENT_PROGRAM);
        final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        final int depthFunction = glGetInteger(GL_DEPTH_FUNC);
        final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        final boolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE);
        final boolean[] colorMask = new boolean[4];
        final ShaderInstance shader = RenderSystem.getShader();
        final int[] textures = new int[12], shaderTextures = new int[12];
        final float[] color = RenderSystem.getShaderColor().clone();
        final float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        DrawState() {
            try (var stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                glGetBooleanv(GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
            }
            for (int i = 0; i < textures.length; i++) {
                GlStateManager._activeTexture(GL_TEXTURE0 + i);
                textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
                shaderTextures[i] = RenderSystem.getShaderTexture(i);
            }
            GlStateManager._activeTexture(activeTexture);
        }
        @Override void restore() {
            super.restore();
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            for (int i = 0; i < textures.length; i++) {
                RenderSystem.setShaderTexture(i, shaderTextures[i]);
                GlStateManager._activeTexture(GL_TEXTURE0 + i);
                GlStateManager._bindTexture(textures[i]);
            }
            GlStateManager._activeTexture(activeTexture);
            GlStateManager._glUseProgram(program);
            RenderSystem.depthFunc(depthFunction);
            RenderSystem.depthMask(depthMask);
            RenderSystem.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        }
    }

    /** A VBO draw changes native shader uniform objects as well as RenderSystem state. */
    static final class ShaderUniforms {
        private static final String[] NAMES = {"ModelViewMat", "ProjMat", "TextureMat", "ScreenSize", "ColorModulator",
                "Light0_Direction", "Light1_Direction", "GlintAlpha", "FogStart", "FogEnd", "FogColor", "FogShape",
                "LineWidth", "GameTime", "ChunkOffset"};
        private final Map<Uniform, float[]> floats = new LinkedHashMap<>();
        private final Map<Uniform, int[]> ints = new LinkedHashMap<>();
        ShaderUniforms(ShaderInstance shader) {
            for (var name : NAMES) {
                Uniform uniform = shader.getUniform(name);
                if (uniform == null) continue;
                if (uniform.getType() <= 3) {
                    int[] values = new int[uniform.getCount()];
                    for (int i = 0; i < values.length; i++) values[i] = uniform.getIntBuffer().get(i);
                    ints.put(uniform, values);
                } else {
                    float[] values = new float[uniform.getCount()];
                    for (int i = 0; i < values.length; i++) values[i] = uniform.getFloatBuffer().get(i);
                    floats.put(uniform, values);
                }
            }
        }
        void restore(ShaderInstance shader) {
            GlStateManager._glUseProgram(shader.getId());
            for (var entry : floats.entrySet()) { entry.getKey().set(entry.getValue()); entry.getKey().upload(); }
            for (var entry : ints.entrySet()) {
                int[] values = entry.getValue();
                entry.getKey().setSafe(values[0], values.length > 1 ? values[1] : 0,
                        values.length > 2 ? values[2] : 0, values.length > 3 ? values[3] : 0);
                entry.getKey().upload();
            }
        }
    }
}
