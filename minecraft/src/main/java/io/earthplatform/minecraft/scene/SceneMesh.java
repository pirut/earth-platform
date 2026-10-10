package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.platform.GlStateManager;
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
    private final List<RenderPublicationGuard.Job> dependencies;
    private final List<Pass> passes;
    private final SceneAppearanceSignature appearance;
    private final int vertices, bytes;
    private boolean closed, closeComplete;

    private record Pass(RenderType layer, VertexBuffer buffer) {}

    /** Registered before native allocation, so every successfully returned VBO already has a cleanup owner. */
    private static final class GpuOwner implements SceneBuildResources.Resource {
        VertexBuffer buffer;
        @Override public void close() { if (buffer != null) buffer.close(); }
    }

    private SceneMesh(RenderPublicationGuard guard, RenderPublicationGuard.Job job,
                      List<RenderPublicationGuard.Job> dependencies, List<Pass> passes, int vertices, int bytes,
                      SceneAppearanceSignature appearance) {
        this.guard = guard;
        this.job = job;
        this.dependencies = List.copyOf(dependencies);
        this.passes = List.copyOf(passes);
        this.vertices = vertices;
        this.bytes = bytes;
        this.appearance = Objects.requireNonNull(appearance);
    }

    /** Unknown neighbors defer; CPU candidates remain private until their final owner-side upload. */
    public static Build begin(RenderPublicationGuard guard, SceneCube cube, SceneNeighborhood neighborhood) {
        RenderSystem.assertOnRenderThread();
        return new Build(guard, cube, neighborhood);
    }

    /** Backward-compatible synchronous composition for independent callers and fixtures. */
    public static SceneMesh build(RenderPublicationGuard guard, SceneCube cube, SceneNeighborhood neighborhood) {
        Build candidate = begin(guard, cube, neighborhood);
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
        private final Map<RenderType, CpuPass> cpu = new LinkedHashMap<>();
        private final List<Pass> gpu = new ArrayList<>();
        private final Budget budget = new Budget();
        private final SceneBuildResources cpuResources = new SceneBuildResources();
        private final SceneBuildResources gpuResources = new SceneBuildResources();
        private final BlockRenderDispatcher renderer;
        private final PoseStack pose = new PoseStack();
        private final RandomSource random = RandomSource.create(0);
        private final SceneAppearanceSignature appearance;
        private final int originX, originY, originZ;
        private final SceneBuildStepper stepper;
        private boolean failed, closed, closeComplete, finished;
        private long finishNanos;

        private Build(RenderPublicationGuard guard, SceneCube cube, SceneNeighborhood neighborhood) {
            this.guard = Objects.requireNonNull(guard);
            this.cube = Objects.requireNonNull(cube);
            Objects.requireNonNull(neighborhood);
            if (neighborhood.cube(cube.snapshot().position()) != cube) {
                throw new IllegalArgumentException("The exact immutable cube must belong to its compilation neighborhood");
            }
            job = guard.prepare(cube.snapshot());
            dependencies.put(job.position(), job);
            // Capture all present one-block AO/culling halo owners now, including ones first read in a later slice.
            // Missing owners still fail on actual read. Unusual farther model reads are also tracked and validated.
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                SceneCube neighbor = neighborhood.cube(job.position().offset(x, y, z));
                if (neighbor != null) dependencies.computeIfAbsent(neighbor.snapshot().position(),
                        ignored -> guard.prepare(neighbor.snapshot()));
            }
            reads = neighborhood.tracking(dependency -> {
                checkOwner();
                var captured = dependencies.computeIfAbsent(dependency.snapshot().position(),
                        ignored -> guard.prepare(dependency.snapshot()));
                if (!guard.isCurrent(captured)) throw new IllegalStateException("Scene read dependency became stale");
            });
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
                pass.initialize(budget);
                pose.pushPose();
                try {
                    pose.translate(x, y, z);
                    renderer.renderBatched(state, pos, reads, pose, pass.consumer, true, random, ModelData.EMPTY, layer);
                } finally { pose.popPose(); }
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
                    if (count % 4 != 0 || length != count * DefaultVertexFormat.BLOCK.getVertexSize()) {
                        throw new IllegalStateException("Native scene mesh must contain complete BLOCK-format quads");
                    }
                    vertices = Math.addExact(vertices, count);
                    bytes = Math.addExact(bytes, length);
                }
                if (vertices != budget.vertices || vertices > MAX_VERTICES || bytes > MAX_VERTEX_BYTES) {
                    throw new IllegalStateException("Native scene geometry exceeded its bounded vertex budget");
                }
                requireValid();
                var bindings = new BufferBindings();
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
                            gpu.add(new Pass(layer, allocated.buffer));
                            allocated.buffer.bind();
                            allocated.buffer.upload(pass.mesh);
                        }
                        requireValid();
                    })) throw new IllegalStateException("Scene mesh became stale before native publication");
                } catch (RuntimeException | Error error) { uploadFailure = error; }
                finally {
                    try { bindings.restore(); }
                    catch (RuntimeException | Error error) { uploadFailure = SceneBuildStepper.append(uploadFailure, error); }
                }
                SceneBuildStepper.rethrow(uploadFailure);
                // Do not transfer GPU ownership or return a mesh until every CPU close succeeds.
                closeCpu();
                requireValid();
                var result = new SceneMesh(guard, job, new ArrayList<>(dependencies.values()), gpu, vertices, bytes,
                        appearance);
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
            // Full captured halo + appearance checks bracket each slice, including its final callback.
            if (!guard.isCurrent(job)) throw new IllegalStateException("Scene build owner became stale or cancelled");
        }
        private void requireValid() {
            checkOpen();
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
        @Override public void close() {
            checkOwner();
            if (closeComplete) return;
            closed = true;
            stepper.cancel();
            // Reentrant callbacks may request cancellation, but must not free their active vertex writer.
            if (stepper.stepping()) throw new CleanupException(this,
                    new IllegalStateException("Retry cleanup after the active native callback returns"));
            Throwable failure = null;
            try { clearModelCaches(); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); }
            try { closeCpu(); }
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

    /** Includes every sampled culling/AO neighbor, not merely the rendered cube's own lease. */
    public boolean isCurrent() {
        RenderSystem.assertOnRenderThread();
        if (closed || !appearance.isCurrent(SceneMesh::readAppearance)) return false;
        for (var dependency : dependencies) if (!guard.isCurrent(dependency)) return false;
        return true;
    }

    /** True means current native coverage, including a successfully published empty mesh. */
    public boolean draw(Matrix4f modelViewWithoutOrigin, Matrix4f projection,
                        double cameraX, double cameraY, double cameraZ, int fixedLightTexture) {
        RenderSystem.assertOnRenderThread();
        if (closed) return false;
        // The same admission lock covers dependency validation, every native GL submission,
        // and restoration. Off-thread retirement/reload cannot interleave with this draw.
        return guard.drawIfCurrent(dependencies, () -> drawCurrent(modelViewWithoutOrigin, projection,
                cameraX, cameraY, cameraZ, fixedLightTexture));
    }

    private boolean drawCurrent(Matrix4f modelViewWithoutOrigin, Matrix4f projection,
                                double cameraX, double cameraY, double cameraZ, int fixedLightTexture) {
        if (!isCurrent()) return false;
        Objects.requireNonNull(modelViewWithoutOrigin);
        Objects.requireNonNull(projection);
        var origin = CameraRelativeOrigin.of(job.position(), cameraX, cameraY, cameraZ);
        if (isEmpty()) return isCurrent();
        if (fixedLightTexture <= 0) throw new IllegalArgumentException("A live fixed diagnostic light texture is required");
        var modelView = new Matrix4f(modelViewWithoutOrigin).translate(origin.x(), origin.y(), origin.z());
        var previous = new DrawState();
        try {
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShaderFogStart(1_000_000);
            RenderSystem.setShaderFogEnd(2_000_000);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            RenderSystem.setShaderTexture(2, fixedLightTexture);
            for (var pass : passes) {
                if (!isCurrent()) return false;
                ShaderInstance shader = shader(pass.layer());
                var uniforms = new ShaderUniforms(shader);
                try {
                    RenderSystem.setShader(() -> shader);
                    if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0.0f, 0.0f, 0.0f);
                    pass.buffer().bind();
                    if (!isCurrent()) return false;
                    pass.buffer().drawWithShader(modelView, projection, shader);
                } finally {
                    // Also resets ShaderInstance's internal last-program cache after exceptional draws.
                    shader.clear();
                    uniforms.restore(shader);
                    for (int i = 0; i < previous.shaderTextures.length; i++) {
                        shader.setSampler("Sampler" + i, previous.shaderTextures[i]);
                    }
                }
            }
            return isCurrent();
        } finally { previous.restore(); }
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
        ByteBufferBuilder storage;
        BufferBuilder builder;
        VertexConsumer consumer;
        MeshData mesh;
        void initialize(Budget budget) {
            if (builder != null) return;
            // The pinned ByteBufferBuilder resize overwrites its pointer before checking malloc failure.
            // Fixed maximum allocation avoids that lost-pointer path; three supported passes bound storage to 3 MiB.
            storage = new ByteBufferBuilder(MAX_VERTEX_BYTES);
            builder = new BufferBuilder(storage, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            consumer = new BoundedConsumer(builder, budget);
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

    private static final class Budget { int vertices; }

    /** The counter is before allocation, including VertexConsumer's bulk-quad default path. */
    private static final class BoundedConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Budget budget;
        BoundedConsumer(VertexConsumer delegate, Budget budget) { this.delegate = delegate; this.budget = budget; }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (budget.vertices >= MAX_VERTICES) throw new SparseFacadeException("scene mesh face/vertex budget exceeded");
            budget.vertices++;
            delegate.addVertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
    }

    private static class BufferBindings {
        final int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        void restore() {
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        }
    }

    /** Restore both actual GL state and RenderSystem/GlStateManager's cached representation. */
    private static final class DrawState extends BufferBindings {
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
    private static final class ShaderUniforms {
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
