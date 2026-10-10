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
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
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
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL33C.*;

/** Native baked models and native VBOs, compiled synchronously with bounded, revocable provenance. */
public final class SceneMesh implements AutoCloseable {
    public static final int MAX_QUADS = 8192;
    public static final int MAX_VERTICES = MAX_QUADS * 4;
    public static final int MAX_VERTEX_BYTES = MAX_VERTICES * 32;
    private final RenderPublicationGuard guard;
    private final RenderPublicationGuard.Job job;
    private final List<RenderPublicationGuard.Job> dependencies;
    private final List<Pass> passes;
    private final int vertices, bytes;
    private boolean closed, closeComplete;

    private record Pass(RenderType layer, VertexBuffer buffer) {}

    private SceneMesh(RenderPublicationGuard guard, RenderPublicationGuard.Job job,
                      List<RenderPublicationGuard.Job> dependencies, List<Pass> passes, int vertices, int bytes) {
        this.guard = guard;
        this.job = job;
        this.dependencies = List.copyOf(dependencies);
        this.passes = List.copyOf(passes);
        this.vertices = vertices;
        this.bytes = bytes;
    }

    /** Unknown neighbors defer by throwing SparseFacadeException; no partial candidate is published. */
    public static SceneMesh build(RenderPublicationGuard guard, SceneCube cube, SceneNeighborhood neighborhood) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(guard);
        Objects.requireNonNull(cube);
        Objects.requireNonNull(neighborhood);
        if (neighborhood.cube(cube.snapshot().position()) != cube) {
            throw new IllegalArgumentException("The exact immutable cube must belong to its compilation neighborhood");
        }
        var job = guard.prepare(cube.snapshot());
        Map<CubePos, RenderPublicationGuard.Job> dependencies = new LinkedHashMap<>();
        dependencies.put(job.position(), job);
        SceneNeighborhood reads = neighborhood.tracking(dependency -> dependencies.computeIfAbsent(
                dependency.snapshot().position(), ignored -> guard.prepare(dependency.snapshot())));
        Map<RenderType, CpuPass> cpu = new LinkedHashMap<>();
        List<Pass> gpu = new ArrayList<>();
        Budget budget = new Budget();
        boolean installed = false;
        // Vanilla's light/AO cache keys use packed BlockPos, which aliases native Y every 4096 blocks.
        // Keep that cache disabled, including between successful, empty, and failed compilations.
        ModelBlockRenderer.clearCache();
        try {
            var renderer = Minecraft.getInstance().getBlockRenderer();
            var pose = new PoseStack();
            var random = RandomSource.create(0);
            int originX = Math.toIntExact(job.position().minBlockX());
            int originY = Math.toIntExact(job.position().minBlockY());
            int originZ = Math.toIntExact(job.position().minBlockZ());
            for (int y = 0; y < 16; y++) {
                requireCurrent(guard, dependencies.values());
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var state = cube.state(x, y, z);
                    if (state.isAir()) continue;
                    var pos = new BlockPos(originX + x, originY + y, originZ + z);
                    random.setSeed(state.getSeed(pos));
                    var layers = renderer.getBlockModel(state).getRenderTypes(state, random, ModelData.EMPTY);
                    for (var layer : layers) {
                        if (layer != RenderType.solid() && layer != RenderType.cutoutMipped() && layer != RenderType.cutout()) {
                            throw new SparseFacadeException("scene model requests an unsupported render layer: " + layer);
                        }
                        CpuPass pass = cpu.computeIfAbsent(layer, ignored -> new CpuPass(budget));
                        pose.pushPose();
                        try {
                            pose.translate(x, y, z);
                            renderer.renderBatched(state, pos, reads, pose, pass.consumer, true, random, ModelData.EMPTY, layer);
                        } finally { pose.popPose(); }
                    }
                }
            }
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
            requireCurrent(guard, dependencies.values());
            var bindings = new BufferBindings();
            try {
                // Empty geometry still crosses the same publication fence and becomes near-ready.
                // No dummy quad or dummy VBO is manufactured to represent known empty coverage.
                if (!guard.publish(job, () -> {
                    requireCurrent(guard, dependencies.values());
                    for (var layer : List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout())) {
                        CpuPass pass = cpu.get(layer);
                        if (pass == null || pass.mesh == null) continue;
                        var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                        gpu.add(new Pass(layer, buffer));
                        buffer.bind();
                        pass.consumed = true; // VertexBuffer.upload closes MeshData even if upload throws.
                        buffer.upload(pass.mesh);
                    }
                    requireCurrent(guard, dependencies.values());
                })) throw new IllegalStateException("Scene mesh became stale before native publication");
            } finally { bindings.restore(); }
            var result = new SceneMesh(guard, job, new ArrayList<>(dependencies.values()), gpu, vertices, bytes);
            installed = true;
            return result;
        } catch (RuntimeException failure) {
            // Native tessellation wraps exceptions in crash reports. Preserve a useful scoped
            // deferral result, instead of making an unknown halo look like a renderer crash.
            Throwable cause = failure;
            for (int i = 0; cause != null && i < 32; i++, cause = cause.getCause()) {
                if (cause instanceof SparseFacadeException unknown) throw unknown;
            }
            throw failure;
        } finally {
            ModelBlockRenderer.clearCache();
            for (var pass : cpu.values()) pass.close();
            if (!installed) for (var pass : gpu) pass.buffer().close();
        }
    }

    public EngineCubeSnapshot snapshot() { return job.snapshot(); }
    public RenderPublicationGuard.Job job() { return job; }
    public int vertices() { return vertices; }
    public int bytes() { return bytes; }
    public boolean isEmpty() { return vertices == 0; }

    /** Includes every sampled culling/AO neighbor, not merely the rendered cube's own lease. */
    public boolean isCurrent() {
        RenderSystem.assertOnRenderThread();
        if (closed) return false;
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
            catch (RuntimeException | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
        closeComplete = true;
    }

    private static final class CpuPass implements AutoCloseable {
        final ByteBufferBuilder storage = new ByteBufferBuilder(4096);
        final BufferBuilder builder = new BufferBuilder(storage, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        final VertexConsumer consumer;
        MeshData mesh;
        boolean consumed;
        CpuPass(Budget budget) { consumer = new BoundedConsumer(builder, budget); }
        @Override public void close() {
            try { if (mesh != null && !consumed) mesh.close(); }
            finally { storage.close(); }
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
