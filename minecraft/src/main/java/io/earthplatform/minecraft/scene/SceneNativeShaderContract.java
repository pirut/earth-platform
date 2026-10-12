package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.block.state.BlockState;

/** Owner-bound mesh provenance. Iris linkage is isolated from every default-path entry point. */
public final class SceneNativeShaderContract {
    private SceneNativeShaderContract() {}

    /**
     * The host calls invalidate BEFORE actual Iris reload/destruction/material setters, resource
     * reload, resize, disconnect, or dimension replacement. Identity comparisons alone cannot
     * detect mutation in a retained material-map object. This is not a frame/pass counter.
     */
    public static final class Lifecycle {
        private final Thread owner = Thread.currentThread();
        private long epoch = 1;
        private boolean failed;
        public long epoch() { checkOwner(); if (failed) throw new IllegalStateException("Shader epoch exhausted"); return epoch; }
        public void invalidate() {
            checkOwner();
            try { epoch = Math.incrementExact(epoch); }
            catch (ArithmeticException error) { failed = true; throw error; }
        }
        private void checkOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Shader lifecycle requires its owner thread");
        }
    }

    /** Object identities are intentionally compared with ==, including pack and mutable maps. */
    public record Values(boolean shaders, Object pipeline, Object pack, Object stateIds, Object typeIds,
                         Object format, int stride, long resourceEpoch, int ambientOcclusionBits,
                         boolean separateAo, boolean disableDirectionalShading) {
        public Values {
            Objects.requireNonNull(format, "effective format");
            if (stride < 1 || stride > SceneMesh.MAX_VERTEX_BYTES || resourceEpoch < 1)
                throw new IllegalArgumentException("Invalid native shader format or epoch");
            if (shaders) {
                Objects.requireNonNull(pipeline, "active pipeline"); Objects.requireNonNull(pack, "active pack");
                Objects.requireNonNull(stateIds, "block-state IDs");
            }
        }
        boolean same(Values other) {
            return other != null && shaders == other.shaders && pipeline == other.pipeline && pack == other.pack
                    && stateIds == other.stateIds && typeIds == other.typeIds && format == other.format
                    && stride == other.stride && resourceEpoch == other.resourceEpoch
                    && ambientOcclusionBits == other.ambientOcclusionBits && separateAo == other.separateAo
                    && disableDirectionalShading == other.disableDirectionalShading;
        }
    }

    interface MaterialContext {
        void begin(BufferBuilder builder, BlockState state, int localX, int localY, int localZ);
        void end(BufferBuilder builder);
        default boolean isCurrent() { return true; }
        default long metadataBytes() { return 0; }
    }
    private static final MaterialContext NONE = new MaterialContext() {
        public void begin(BufferBuilder builder, BlockState state, int x, int y, int z) {}
        public void end(BufferBuilder builder) {}
    };

    public static final class Capture {
        private final Thread owner = Thread.currentThread();
        private final Values values;
        private final Supplier<Values> source;
        private final MaterialContext materials;
        private boolean invalidated;
        private final BooleanSupplier resourceCurrent;
        Capture(Values values, Supplier<Values> source, MaterialContext materials) {
            this(values, source, materials, null);
        }
        // Package-only resource guard. It can revoke an existing capture, never grant frame authority.
        Capture(Values values, Supplier<Values> source, MaterialContext materials, BooleanSupplier resourceCurrent) {
            this.values = Objects.requireNonNull(values); this.source = Objects.requireNonNull(source);
            this.materials = Objects.requireNonNull(materials); this.resourceCurrent = resourceCurrent;
        }
        private boolean resourcesCurrent() { return resourceCurrent == null || resourceCurrent.getAsBoolean(); }
        public Values values() { checkOwner(); return values; }
        public boolean isCurrent() {
            checkOwner();
            if (invalidated) return false;
            try {
                if (!resourcesCurrent()) { invalidated = true; return false; }
                Values live = source.get();
                if (!resourcesCurrent() || !values.same(live) || !materials.isCurrent() || !resourcesCurrent()) invalidated = true;
            }
            catch (RuntimeException | Error error) { invalidated = true; throw error; }
            return !invalidated;
        }
        public long metadataBytes() { checkOwner(); return Math.addExact(materials.metadataBytes(), resourceCurrent == null ? 0 : 128); }
        public boolean matches(Capture other) {
            checkOwner(); return other != null && other.isCurrent() && isCurrent() && values.same(other.values);
        }
        void requireCurrent() { if (!isCurrent()) throw new IllegalStateException("Native shader mesh provenance changed"); }
        void requireFormat(Object format, int stride) {
            requireCurrent();
            if (values.format != format || values.stride != stride) {
                invalidated = true;
                throw new IllegalStateException("Actual native builder format does not match its captured shader contract");
            }
        }
        void beginBlock(BufferBuilder builder, BlockState state, int x, int y, int z) {
            requireCurrent();
            if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15)
                throw new IllegalArgumentException("Material position must be cube-local");
            materials.begin(builder, state, x, y, z);
        }
        void endBlock(BufferBuilder builder) { checkOwner(); materials.end(builder); }
        private void checkOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Native shader capture requires its owner thread");
        }
    }

    /** No Iris classes are resolved by this path. Unexpected transformed formats fail before emission. */
    public static Capture defaultPath() {
        var values = new Values(false, null, null, null, null, DefaultVertexFormat.BLOCK,
                DefaultVertexFormat.BLOCK.getVertexSize(), 1, 0, false, false);
        return new Capture(values, () -> values, NONE);
    }

    /** Host must first verify the separately installed, exact pinned Iris/Sodium runtime. */
    public static Capture captureIris(Lifecycle lifecycle) { return IrisAdapter.capture(Objects.requireNonNull(lifecycle)); }

    /** The survival resource owner supplies only its small epoch/retirement token. Public callers
     * cannot attach a predicate; retained token/supplier bytes follow the Capture's own accounting. */
    static Capture captureIris(Lifecycle lifecycle, BooleanSupplier resourceCurrent) {
        Objects.requireNonNull(lifecycle); Objects.requireNonNull(resourceCurrent);
        if (!resourceCurrent.getAsBoolean()) throw new IllegalStateException("Resources retired before native capture");
        Capture captured = IrisAdapter.capture(lifecycle);
        if (!resourceCurrent.getAsBoolean()) throw new IllegalStateException("Resources retired during native capture");
        return new Capture(captured.values, captured.source, captured.materials, resourceCurrent);
    }


    /**
     * One real host terrain/shadow invocation. The host owns stage, program/FBO, frame ledger,
     * projection, live lightmap and lifecycle validation. captureState must only capture and return
     * an idempotent restoration action before setup mutates anything. Its action must restore Iris
     * FBO/viewport/blend/sampler/image state as well as GL caches, including exceptional exits.
     */
    public interface ShaderPass {
        Capture capture();
        RenderType layer();
        ShaderInstance shader();
        boolean isCurrent();
        Runnable captureState();
        void setup();
    }

    private static final class IrisAdapter {
        private static Values read(Lifecycle lifecycle) {
            long epoch = lifecycle.epoch(); // Owner check precedes every live Iris read.
            var settings = net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE;
            var pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
            var pack = net.irisshaders.iris.Iris.getCurrentPack();
            if (!net.irisshaders.iris.Iris.isPackInUseQuick()
                    || !(pipeline instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline)
                    || pack.isEmpty() || settings.getBlockStateIds() == null)
                return new Values(false, null, null, null, null, DefaultVertexFormat.BLOCK,
                        DefaultVertexFormat.BLOCK.getVertexSize(), epoch, 0, false, false);
            var format = net.irisshaders.iris.vertices.IrisVertexFormats.TERRAIN;
            if (format.getVertexSize() != 52) throw new IllegalStateException("Unsupported Iris terrain stride");
            return new Values(true, pipeline, pack.orElseThrow(),
                    settings.getBlockStateIds(), settings.getBlockTypeIds(), format, format.getVertexSize(), epoch,
                    Float.floatToRawIntBits(settings.getAmbientOcclusionLevel()), settings.shouldUseSeparateAo(),
                    settings.shouldDisableDirectionalShading());
        }
        private static Capture capture(Lifecycle lifecycle) {
            var values = read(lifecycle);
            if (!values.shaders()) throw new IllegalStateException("An active pinned Iris terrain pipeline is required");
            @SuppressWarnings("unchecked")
            var ids = (it.unimi.dsi.fastutil.objects.Object2IntMap<BlockState>) values.stateIds();
            @SuppressWarnings("unchecked")
            var types = (java.util.Map<net.minecraft.world.level.block.Block,
                    net.irisshaders.iris.shaderpack.materialmap.BlockRenderType>) values.typeIds();
            var materials = new MaterialContext() {
                // The current SceneCube whitelist has at most thirteen states. A small hard bound
                // rejects an expanded material surface instead of retaining an entire pack map.
                private final BlockState[] states = new BlockState[64];
                private final int[] capturedIds = new int[64];
                private final Object[] capturedTypes = new Object[64];
                private int count;
                private Object type(BlockState state) { return types == null ? null : types.get(state.getBlock()); }
                public boolean isCurrent() {
                    for (int i=0;i<count;i++) if (ids.getOrDefault(states[i],-1)!=capturedIds[i]
                            || type(states[i])!=capturedTypes[i]) return false;
                    return true;
                }
                public long metadataBytes() { return 64L * (Long.BYTES * 2 + Integer.BYTES) + 192; }
                public void begin(BufferBuilder builder, BlockState state, int x, int y, int z) {
                    if (!(builder instanceof net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder sensitive))
                        throw new IllegalStateException("Iris material-context mixin is absent");
                    int id=ids.getOrDefault(state,-1), index=0;
                    while(index<count && states[index]!=state) index++;
                    if(index==count) {
                        if(count==states.length) throw new IllegalStateException("Native shader material read budget exceeded");
                        states[count]=state; capturedIds[count]=id; capturedTypes[count]=type(state); count++;
                    } else if(capturedIds[index]!=id || capturedTypes[index]!=type(state)) {
                        throw new IllegalStateException("Native shader material mapping mutated during compilation");
                    }
                    // The pinned Iris BLOCK path ignores emission in midBlock's fourth byte. Keep its
                    // API input truthful, but do not claim emissive voxel metadata support from it.
                    sensitive.beginBlock(id, (byte) 0, (byte) state.getLightEmission(), x, y, z);
                }
                public void end(BufferBuilder builder) {
                    if (builder instanceof net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder sensitive) sensitive.endBlock();
                }
            };
            return new Capture(values, () -> read(lifecycle), materials);
        }
    }

    /** Accounting is checked before any native reserve, never inferred from a finished mesh. */
    static final class Budget {
        int vertices, bytes;
        long reservationCalls, bulkQuadReservationCalls;
        void preflight(int count, int stride) {
            if (count < 0 || stride < 1 || count > SceneMesh.MAX_VERTICES - vertices
                    || (long) count * stride > SceneMesh.MAX_VERTEX_BYTES - bytes)
                throw new io.earthplatform.minecraft.residency.SparseFacadeException("scene mesh vertex/byte budget exceeded");
        }
        void reserve(int stride) { reserve(1, stride); }
        void reserve(int count, int stride) {
            if (count < 1) throw new IllegalArgumentException("Positive native reservation vertex count required");
            preflight(count, stride);
            vertices += count; bytes += Math.multiplyExact(count, stride);
            reservationCalls++;
            if (count == 4) bulkQuadReservationCalls++;
        }
    }
}
