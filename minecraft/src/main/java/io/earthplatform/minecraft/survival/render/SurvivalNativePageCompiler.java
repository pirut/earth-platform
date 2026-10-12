package io.earthplatform.minecraft.survival.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.mixin.SceneBufferBuilderAccessor;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.scene.SceneNativeShaderContract;
import io.earthplatform.minecraft.scene.SceneNeighborhood;
import io.earthplatform.minecraft.scene.SurvivalNativeCompilerBridge;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.TreeMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.lwjgl.system.MemoryUtil;

/**
 * Genuine native model CPU compilation for a sparse signed 64 m page. All nonempty native terrain
 * passes batch across members; whole quads split only at the existing 1 MiB physical payload limit.
 * No capsule, material whitelist, fallback faces, GPU allocation, upload, publication, DH ownership,
 * propagated lighting, or selected-distance qualification is supplied by this class.
 *
 * Ownership: retain Build before step(), retain its CompiledPage before releasing Build, and retain
 * either after a failed close until closeComplete(). Every input retention and native allocation is
 * charged to the existing shared WORKER_UPLOAD pool. Authenticated compilation separately admits
 * bounded SOURCE verification scratch and retained OWNER_METADATA provenance. Admission can refuse a dense page; no truncation
 * or smaller effective distance is invented. Actual pieces/bytes must pass the unchanged GPU ledger.
 */
public final class SurvivalNativePageCompiler {
    private static final long BASE_METADATA_BYTES = 32 * 1024L;
    private static final long DEPENDENCY_METADATA_BYTES = 1024;
    private static final long PIECE_METADATA_BYTES = 8192;
    private SurvivalNativePageCompiler() {}

    public enum Layer {
        SOLID, CUTOUT_MIPPED, CUTOUT;
        public RenderType nativeType() {
            return switch (this) {
                case SOLID -> RenderType.solid();
                case CUTOUT_MIPPED -> RenderType.cutoutMipped();
                case CUTOUT -> RenderType.cutout();
            };
        }
        static Layer of(RenderType layer) {
            if (layer == RenderType.solid()) return SOLID;
            if (layer == RenderType.cutoutMipped()) return CUTOUT_MIPPED;
            if (layer == RenderType.cutout()) return CUTOUT;
            throw new IllegalArgumentException("Unsupported native page terrain render layer: " + layer);
        }
    }
    public record StepStats(int blocks, int completedBlocks, int totalBlocks, long elapsedNanos,
            long budgetNanos, long overshootNanos, long maxBlockNanos, boolean complete) {}
    public record LayerStats(Layer layer, int pieces, long vertices, long vertexBytes, long indexBytes) {}
    /** Separate source verification work; these cells are never reported as compiled native blocks. */
    public record VerificationStats(int cells, int verifiedCells, int totalCells, int verifiedDependencies,
            int totalDependencies, int generatedCubes, long elapsedNanos, long budgetNanos,
            long overshootNanos, long maxDependencySetupNanos, boolean complete) {}

    /** The dependency map must be precisely Page.dependencies(), including unselected interior halo. */
    public static Build begin(SurvivalDistancePagePlan.Page page, Map<CubePos, SceneCube> exactDependencies,
            RenderPublicationGuard guard, SceneNeighborhood.LightingPolicy lighting, boolean directionalShadingDisabled,
            SceneNativeShaderContract.Capture capture, NativeDistanceCpuBudget cpuBudget) {
        RenderSystem.assertOnRenderThread();
        return new Build(page, exactDependencies, guard, lighting, directionalShadingDisabled, capture, cpuBudget, null, null, -1, null);
    }

    /**
     * Complete-source authenticated path. begin only admits/copies bounded inputs; verification is
     * sliced by step BEFORE any model callback. Diagnostic begin remains intentionally unproved.
     * Authority is sampled into a historical content latch, not retained as a gameplay/current lease.
     */
    public static Build beginAuthenticated(SurvivalCompleteOverlay.CurrentLease authority,
            SurvivalCompleteOverlay.PlanInput exactPlan, int pageIndex, Map<CubePos, SceneCube> exactDependencies,
            RenderPublicationGuard guard, SceneNeighborhood.LightingPolicy lighting, boolean directionalShadingDisabled,
            SceneNativeShaderContract.Capture capture, NativeDistanceCpuBudget cpuBudget) {
        RenderSystem.assertOnRenderThread(); Objects.requireNonNull(authority); Objects.requireNonNull(exactPlan);
        return new Build(exactPlan.page(pageIndex), exactDependencies, guard, lighting, directionalShadingDisabled,
                capture, cpuBudget, authority, exactPlan, pageIndex, null);
    }

    /**
     * Retained staging wrapper before a nullable owner-CAS attempt. It never accepts a caller latch,
     * raw proof or completion value. Caller keeps its charged dependency owner until this closes.
     */
    public static AuthenticatedStart prepareAuthenticated(SurvivalCompleteOverlay.CurrentLease authority,
            SurvivalCompleteOverlay.PlanInput exactPlan, int pageIndex, Map<CubePos, SceneCube> dependencies,
            RenderPublicationGuard guard, SceneNeighborhood.LightingPolicy lighting, boolean directional,
            SceneNativeShaderContract.Capture capture, NativeDistanceCpuBudget budget) {
        RenderSystem.assertOnRenderThread();
        return new AuthenticatedStart(authority, exactPlan, pageIndex, dependencies, guard, lighting, directional, capture, budget);
    }
    public static final class AuthenticatedStart implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final NativeDistanceCpuBudget budget;
        private final int pageIndex;
        private final SceneNeighborhood.LightingPolicy lighting;
        private final boolean directional;
        private SurvivalCompleteOverlay.CurrentLease authority;
        private SurvivalCompleteOverlay.PlanInput plan;
        private Map<CubePos, SceneCube> dependencies;
        private RenderPublicationGuard guard;
        private SceneNativeShaderContract.Capture capture;
        private SurvivalCompleteOverlay.FrameLatch pending;
        private NativeDistanceCpuBudget.Reservation metadata;
        private boolean operating, finished, closed;
        private AuthenticatedStart(SurvivalCompleteOverlay.CurrentLease authority, SurvivalCompleteOverlay.PlanInput exactPlan,
                int index, Map<CubePos, SceneCube> dependencies, RenderPublicationGuard guard,
                SceneNeighborhood.LightingPolicy lighting, boolean directional, SceneNativeShaderContract.Capture capture,
                NativeDistanceCpuBudget budget) {
            this.budget=Objects.requireNonNull(budget);this.pageIndex=index;
            this.lighting=Objects.requireNonNull(lighting);this.directional=directional;
            exactPlan.requireBudget(budget);
            metadata=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,4096);
            try {
                plan=exactPlan.retain();plan.page(index);
                this.authority=Objects.requireNonNull(authority);this.dependencies=Objects.requireNonNull(dependencies);
                this.guard=Objects.requireNonNull(guard);this.capture=Objects.requireNonNull(capture);
            } catch(RuntimeException|Error error){close();throw error;}
        }
        public Build tryBeginAuthenticated() {
            check();if(closed||finished||operating||pending!=null)throw new IllegalStateException("Authenticated start is not reusable");
            operating=true;finished=true; // One attempt; a stale lease can never become current again.
            try {
                pending=authority.tryLatchCurrent(plan);
                if(pending==null)return null;
                var result=new Build(plan.page(pageIndex),dependencies,guard,lighting,directional,capture,budget,null,plan,pageIndex,pending);
                pending=null;finished=true;return result;
            } finally {operating=false;}
        }
        @Override public void close(){
            check();if(closed)return;if(operating)throw new IllegalStateException("Authenticated start is operating");
            if(pending!=null){pending.close();pending=null;}
            dependencies=null;guard=null;capture=null;authority=null;
            if(plan!=null){plan.close();plan=null;}metadata.releaseAfterPhysicalDrain();metadata=null;closed=true;
        }
        public boolean closeComplete(){check();return closed;}
        private void check(){if(owner!=Thread.currentThread())throw new IllegalStateException("Authenticated start requires render owner");RenderSystem.assertOnRenderThread();}
    }

    /** Exact aggregate input charge; authenticated copies reserve each immutable alias before retention. */
    private static final class InputOwnership {
        private final NativeDistanceCpuBudget budget;
        private final SurvivalPageCpuLease base;
        private List<SurvivalPageCpuLease> additional;
        InputOwnership(NativeDistanceCpuBudget budget, long bytes) {
            this.budget = budget; base = new SurvivalPageCpuLease(budget, bytes);
        }
        void reserve(long bytes) {
            var next = new SurvivalPageCpuLease(budget, bytes);
            try {
                if (additional == null) additional = new ArrayList<>();
                additional.add(next);
            } catch (RuntimeException | Error failure) { next.drain(() -> {}); throw failure; }
        }
        long bytes() {
            long result = base.bytes();
            if (additional != null) for (var lease : additional) result = Math.addExact(result, lease.bytes());
            return result;
        }
        void drain(Runnable physicalClose) {
            physicalClose.run();
            if (additional != null) {
                for (var lease : additional) lease.drain(() -> {});
                additional.clear(); additional = null;
            }
            base.drain(() -> {});
        }
    }
    private static Map<CubePos, SceneCube> copyAuthenticated(Map<CubePos, SceneCube> caller,
            Set<CubePos> expected, InputOwnership inputs) {
        var result = new TreeMap<CubePos, SceneCube>();
        var iterator = caller.entrySet().iterator();
        while (iterator.hasNext()) {
            // In particular, do not read an excess entry's value before refusing its retention.
            if (result.size() >= expected.size()) throw new IllegalArgumentException("Authenticated input exceeds exact dependency count");
            var entry = Objects.requireNonNull(iterator.next());
            CubePos key = Objects.requireNonNull(entry.getKey());
            if (!expected.contains(key) || result.containsKey(key))
                throw new IllegalArgumentException("Authenticated input has an unplanned or repeated dependency");
            SceneCube cube = Objects.requireNonNull(entry.getValue());
            if (!key.equals(cube.snapshot().position())) throw new IllegalArgumentException("Authenticated dependency address mismatch");
            inputs.reserve(Math.addExact(CubePos.VOLUME * 8L, cube.snapshot().sectionBytes()));
            result.put(key, cube);
        }
        if (result.size() != expected.size()) throw new IllegalArgumentException("Authenticated input omitted a planned dependency");
        return Collections.unmodifiableMap(result);
    }

    public static final class Build implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final SurvivalDistancePagePlan.Page page;
        private final NativeDistanceCpuBudget budget;
        private final PoseStack pose = new PoseStack();
        private final RandomSource random = RandomSource.create(0);
        private final List<CubePos> members;
        private final int totalBlocks;
        private final EnumMap<Layer, Writer> writers = new EnumMap<>(Layer.class);
        private final List<Piece> pieces = new ArrayList<>();
        private Map<CubePos, SceneCube> cubes;
        private SurvivalNativeCompilerBridge bridge;
        private InputOwnership inputs;
        private Verification verification;
        private AuthenticatedContent authenticated;
        private VerificationStats lastVerification;
        private int completed, callbackBytes;
        private long completeMemberMask;
        private boolean failed, stepping, closed, finished, closeComplete;
        private StepStats last;

        private Build(SurvivalDistancePagePlan.Page page, Map<CubePos, SceneCube> dependencies,
                RenderPublicationGuard guard, SceneNeighborhood.LightingPolicy lighting, boolean directional,
                SceneNativeShaderContract.Capture capture, NativeDistanceCpuBudget budget,
                SurvivalCompleteOverlay.CurrentLease authority, SurvivalCompleteOverlay.PlanInput exactPlan, int pageIndex,
                SurvivalCompleteOverlay.FrameLatch prelatched) {
            this.page = Objects.requireNonNull(page); this.budget = Objects.requireNonNull(budget);
            Objects.requireNonNull(dependencies); Objects.requireNonNull(capture);
            long retained = Math.addExact(BASE_METADATA_BYTES, capture.metadataBytes());
            if (authority == null && prelatched == null) {
                // Preserve the existing diagnostic entry point and its accounting exactly.
                if (dependencies.size() > SurvivalVisualPage.MAX_DEPENDENCIES
                        || !page.dependencies().equals(dependencies.keySet()))
                    throw new IllegalArgumentException("Native page requires its exact complete dependency union");
                for (var entry : dependencies.entrySet()) {
                    var cube = Objects.requireNonNull(entry.getValue());
                    if (!entry.getKey().equals(cube.snapshot().position()))
                        throw new IllegalArgumentException("Native page dependency key differs from its snapshot");
                    retained = Math.addExact(retained, DEPENDENCY_METADATA_BYTES + CubePos.VOLUME * 8L
                            + cube.snapshot().sectionBytes());
                }
            }
            inputs = new InputOwnership(budget, retained); // Base reservation precedes every owned copy/list.
            try {
                if (authority == null && prelatched == null) cubes = Map.copyOf(dependencies);
                else {
                    // page.dependencies is bounded by 216 entries; its transient set fits the
                    // already admitted 32 KiB base. Never preflight untrusted Map.size/keySet first.
                    var expected = page.dependencies();
                    if (expected.size() > SurvivalVisualPage.MAX_DEPENDENCIES)
                        throw new IllegalArgumentException("Authenticated page dependency bound exceeded");
                    inputs.reserve(Math.multiplyExact(DEPENDENCY_METADATA_BYTES, expected.size()));
                    cubes = copyAuthenticated(dependencies, expected, inputs);
                }
                members = page.members().stream().sorted(Comparator.comparingInt(p ->
                        SurvivalPageGeometry.memberIndex(page.address(), p))).toList();
                totalBlocks = Math.multiplyExact(members.size(), CubePos.VOLUME);
                if (authority != null || prelatched != null) {
                    verification = prelatched == null
                            ? new Verification(authority, exactPlan, pageIndex, cubes, capture, lighting, directional, budget)
                            : new Verification(prelatched, exactPlan, pageIndex, cubes, capture, lighting, directional, budget);
                    if (verification.page != page) throw new IllegalStateException("Authenticated compiler page is not the exact planned object");
                    lastVerification = verification.last;
                }
                bridge = new SurvivalNativeCompilerBridge(guard, cubes, lighting, directional, capture);
                last = new StepStats(0, 0, totalBlocks, 0, 0, 0, 0, false);
            } catch (RuntimeException | Error failure) {
                // Construction allocated no native writer; dropping aliases physically drains this reservation.
                if (bridge != null) bridge.close(); bridge = null; cubes = null;
                if (verification != null) { verification.close(); verification = null; }
                inputs.drain(() -> {}); throw failure;
            }
        }
        /** Count is hard; time is soft because one arbitrary native model callback cannot be interrupted. */
        public StepStats step(int maxBlocks, long maxNanos) {
            checkOpen();
            if (maxBlocks < 1 || maxNanos < 1) throw new IllegalArgumentException("Positive page step limits required");
            if (stepping || completed == totalBlocks) throw new IllegalStateException("Native page is not resumable");
            if (verification != null) return verifySlice(maxBlocks, maxNanos);
            stepping = true;
            long start = System.nanoTime(), longest = 0;
            int initial = completed;
            Throwable failure = null;
            try {
                bridge.clearCaches(); requireCurrent();
                do {
                    long callbackStart = System.nanoTime();
                    try { compileBlock(completed); }
                    finally { longest = Math.max(longest, elapsed(callbackStart)); }
                    requireEmissionValid();
                    completed++;
                    if (completed % CubePos.VOLUME == 0) completeMemberMask |=
                            1L << SurvivalPageGeometry.memberIndex(page.address(), members.get(completed / CubePos.VOLUME - 1));
                } while (completed < totalBlocks && completed - initial < maxBlocks && elapsed(start) < maxNanos);
            } catch (RuntimeException | Error error) { failure = error; failed = true; }
            finally {
                try { bridge.clearCaches(); }
                catch (RuntimeException | Error error) { failure = append(failure, error); failed = true; }
                if (!failed && !closed) try { requireCurrent(); }
                catch (RuntimeException | Error error) { failure = append(failure, error); failed = true; }
                long duration = elapsed(start);
                last = new StepStats(completed - initial, completed, totalBlocks, duration, maxNanos,
                        Math.max(0, duration - maxNanos), longest, completed == totalBlocks && !failed && !closed);
                stepping = false;
            }
            rethrow(failure); return last;
        }
        private StepStats verifySlice(int maxCells, long maxNanos) {
            stepping = true;
            long start = System.nanoTime();
            try {
                bridge.requireCurrent();
                lastVerification = verification.step(maxCells, maxNanos);
                bridge.requireCurrent();
                if (lastVerification.complete()) {
                    authenticated = verification.finish();
                    verification = null;
                }
                long duration = elapsed(start);
                last = new StepStats(0, completed, totalBlocks, duration, maxNanos,
                        Math.max(0, duration - maxNanos), 0, false);
                return last;
            } catch (RuntimeException | Error failure) {
                failed = true;
                if (verification != null) lastVerification = verification.last;
                long duration = elapsed(start);
                last = new StepStats(0, completed, totalBlocks, duration, maxNanos,
                        Math.max(0, duration - maxNanos), 0, false);
                throw failure;
            } finally { stepping = false; }
        }
        /** Null for diagnostic compilation; otherwise source validation counters remain separate. */
        public VerificationStats lastVerificationStep() { checkOwner(); return lastVerification; }

        private void compileBlock(int ordinal) {
            requireEmissionValid(); callbackBytes = 0;
            CubePos member = members.get(ordinal / CubePos.VOLUME);
            int local = ordinal % CubePos.VOLUME, x = local & 15, z = local >>> 4 & 15, y = local >>> 8;
            try {
            bridge.enterMember(member);
            var state = cubes.get(member).state(x, y, z);
            if (state.isAir()) { bridge.requireCallbackCurrent(); return; }
            var position = new BlockPos(Math.toIntExact(member.minBlockX() + x), Math.toIntExact(member.minBlockY() + y),
                    Math.toIntExact(member.minBlockZ() + z));
            random.setSeed(state.getSeed(position));
            var renderer = bridge.renderer();
            var layers = renderer.getBlockModel(state).getRenderTypes(state, random, ModelData.EMPTY);
            int seen = 0;
            for (var nativeLayer : layers) {
                Layer layer = Layer.of(nativeLayer);
                int bit = 1 << layer.ordinal();
                if ((seen & bit) != 0) throw poison("Duplicate native terrain layer in one model callback");
                seen |= bit;
                Writer writer = writers.computeIfAbsent(layer, Writer::new);
                writer.start(state, SurvivalPageGeometry.memberIndex(page.address(), member), x, y, z);
                pose.pushPose();
                Throwable failure = null;
                try {
                    // Preserve native mid-block/tangent generation in cube-local coordinates.
                    pose.translate(x, y, z);
                    renderer.renderBatched(state, position, bridge.view(), pose, writer, true, random, ModelData.EMPTY, nativeLayer);
                } catch (RuntimeException | Error error) { failure = error; }
                finally {
                    try { writer.end(); }
                    catch (RuntimeException | Error error) { failure = append(failure, error); }
                    try { pose.popPose(); }
                    catch (RuntimeException | Error error) { failure = append(failure, error); }
                }
                rethrow(failure);
            }
            requireEmissionValid(); bridge.requireCallbackCurrent();
            } finally { bridge.leaveMember(); }
        }
        /** Transfers CPU ownership only. All selected members, including known-empty ones, must finish. */
        public CompiledPage finish() {
            checkOpen();
            if (stepping || completed != totalBlocks || completeMemberMask != page.memberMask())
                throw new IllegalStateException("Every selected native member must be fully compiled before finish");
            try {
                requireCurrent();
                for (var writer : writers.values()) writer.seal();
                requireCurrent();
                var result = new CompiledPage(page, completeMemberMask, List.copyOf(pieces), bridge, inputs, authenticated);
                // CompiledPage now holds every physical CPU owner and its exact validity fence.
                pieces.clear(); writers.clear(); bridge = null; cubes = null; inputs = null; authenticated = null;
                finished = true; closeComplete = true; return result;
            } catch (RuntimeException | Error error) { failed = true; throw error; }
        }
        public boolean complete() { checkOwner(); return !failed && !closed && !finished && completed == totalBlocks; }
        public boolean closeComplete() { checkOwner(); return closeComplete; }
        public StepStats lastStep() { checkOwner(); return last; }
        public int blocksCompleted() { checkOwner(); return completed; }
        public int totalBlocks() { return totalBlocks; }
        public long retainedCpuBytes() {
            checkOwner(); long result = inputs == null ? 0 : inputs.bytes();
            if (verification != null) result = Math.addExact(result, verification.retainedBytes());
            if (authenticated != null) result = Math.addExact(result, authenticated.retainedMetadataBytes());
            for (var piece : pieces) result = Math.addExact(result, piece.retainedCpuBytes());
            return result;
        }
        private void requireCurrent() { requireEmissionValid(); bridge.requireCurrent(); }
        private void requireEmissionValid() {
            checkOwner();
            if (failed || closed || finished) throw new IllegalStateException("Native page emission is no longer valid");
        }
        private IllegalStateException poison(String message) { failed = true; return new IllegalStateException(message); }
        private void preflightVertexBytes(int bytes) {
            requireEmissionValid(); bridge.requireCallbackCurrent();
            if (bytes < 1 || bytes > SurvivalPageGeometry.MAX_CALLBACK_BYTES - callbackBytes)
                throw poison("Native model exceeded its bounded callback vertex budget");
        }
        private void reserveVertex(int bytes) {
            preflightVertexBytes(bytes);
            callbackBytes += bytes;
        }
        private void checkOpen() { requireEmissionValid(); }
        private void checkOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Native page compiler requires its render owner");
            RenderSystem.assertOnRenderThread();
        }
        @Override public void close() {
            checkOwner(); if (closeComplete) return;
            closed = true;
            if (stepping) throw new IllegalStateException("Retain page cleanup owner until active native callback returns");
            Throwable failure = null;
            if (bridge != null) try { bridge.clearCaches(); }
            catch (RuntimeException | Error error) { failure = error; }
            for (var writer : writers.values()) try { writer.end(); }
            catch (RuntimeException | Error error) { failure = append(failure, error); }
            for (var piece : pieces) try { piece.close(); }
            catch (RuntimeException | Error error) { failure = append(failure, error); }
            if (failure == null) {
                pieces.clear(); writers.clear(); cubes = null;
                if (bridge != null) bridge.close(); bridge = null;
                if (verification != null) { verification.close(); verification = null; }
                if (authenticated != null) { authenticated.close(); authenticated = null; }
                if (inputs != null) inputs.drain(() -> {}); inputs = null;
                closeComplete = true;
            }
            rethrow(failure);
        }

        /** One state/layer callback may roll over at complete quads, preserving native builder dispatch. */
        private final class Writer implements VertexConsumer {
            private final Layer layer;
            private Piece piece;
            private BlockState state;
            private int member, x, y, z, nextOrdinal;
            private boolean materialActive, bulk;
            Writer(Layer layer) { this.layer = layer; }
            void start(BlockState state, int member, int x, int y, int z) {
                if (this.state != null) throw poison("Nested native block callback");
                this.state = state; this.member = member; this.x = x; this.y = y; this.z = z;
            }
            private void ensureQuad() {
                requireEmissionValid();
                if (state == null) throw poison("Native model retained its vertex consumer outside a callback");
                if (piece != null && piece.vertices % 4 != 0) throw poison("Native model emitted an incomplete quad");
                // Reject a whole quad before allocating another physical piece or writing any
                // part of a callback that cannot fit the unchanged callback byte cap.
                preflightVertexBytes(Math.multiplyExact(4, bridge.capture().values().stride()));
                if (piece == null || piece.vertices == piece.capacityVertices) {
                    seal();
                    piece = new Piece(layer, nextOrdinal++);
                    pieces.add(piece); // Retain cleanup owner before any direct allocation or builder initialization.
                    piece.initialize();
                }
                if (!materialActive) {
                    materialActive = true; // Register cleanup before a potentially partial beginBlock hook.
                    bridge.beginBlock(piece.builder, state, x, y, z);
                }
            }
            private BufferBuilder delegate() {
                requireEmissionValid();
                if (piece == null || state == null) throw poison("Native vertex attributes require an active vertex");
                return piece.builder;
            }
            void end() {
                Throwable failure = null;
                if (materialActive) try { bridge.endBlock(piece.builder); materialActive = false; }
                catch (RuntimeException | Error error) { failure = error; }
                state = null;
                if (!closed && piece != null && piece.vertices % 4 != 0)
                    failure = append(failure, poison("Native block callback ended with an incomplete quad"));
                rethrow(failure);
            }
            void seal() {
                if (piece == null) return;
                if (materialActive) { bridge.endBlock(piece.builder); materialActive = false; }
                piece.seal(); piece = null;
            }
            private void emitted(int count) { piece.record(member, count); }
            @Override public VertexConsumer addVertex(float x, float y, float z) {
                try {
                    if (bulk) throw poison("Unexpected reentrant generic vertex during native bulk emission");
                    if (piece == null || piece.vertices % 4 == 0) ensureQuad();
                    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) throw poison("Non-finite native vertex");
                    delegate().addVertex(x, y, z); emitted(1); return this;
                } catch (RuntimeException | Error error) { failed = true; throw error; }
            }
            private void bulk(BakedQuad quad, Runnable emit) {
                try {
                    if (quad == null || quad.getVertices().length != 32 || bulk)
                        throw poison("Native baked quad must have four eight-int vertices without reentrancy");
                    ensureQuad();
                    int before = piece.storage.vertices;
                    piece.storage.beginBulk();
                    bulk = true; boolean success = false;
                    try { emit.run(); success = true; }
                    finally { bulk = false; piece.storage.endBulk(success); }
                    if (piece.storage.vertices - before != 4) throw poison("Native bulk callback did not emit exactly four vertices");
                    emitted(4);
                } catch (RuntimeException | Error error) { failed = true; throw error; }
            }
            @Override public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float r, float g, float b, float a,
                    int light, int overlay) {
                bulk(quad, () -> delegate().putBulkData(pose, quad, r, g, b, a, light, overlay));
            }
            @Override public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness,
                    float r, float g, float b, float a, int[] lights, int overlay, boolean readColor) {
                if (brightness == null || brightness.length != 4 || lights == null || lights.length != 4)
                    throw poison("Native bulk lighting must have exactly four entries");
                bulk(quad, () -> delegate().putBulkData(pose, quad, brightness, r, g, b, a, lights, overlay, readColor));
            }
            private VertexConsumer attribute(Runnable call) {
                try { call.run(); return this; }
                catch (RuntimeException | Error error) { failed = true; throw error; }
            }
            @Override public VertexConsumer setColor(int r, int g, int b, int a) { return attribute(() -> delegate().setColor(r,g,b,a)); }
            @Override public VertexConsumer setUv(float u, float v) { return attribute(() -> delegate().setUv(u,v)); }
            @Override public VertexConsumer setUv1(int u, int v) { return attribute(() -> delegate().setUv1(u,v)); }
            @Override public VertexConsumer setUv2(int u, int v) { return attribute(() -> delegate().setUv2(u,v)); }
            @Override public VertexConsumer setNormal(float x, float y, float z) { return attribute(() -> delegate().setNormal(x,y,z)); }
        }

        private final class Storage extends ByteBufferBuilder {
            private final int stride, capacityVertices;
            private int vertices, bulkRemaining = -1;
            private long reservationCalls, bulkQuadReservationCalls;
            Storage(int stride) {
                super(SurvivalPageGeometry.MAX_PIECE_BYTES);
                this.stride = stride; capacityVertices = SurvivalPageGeometry.capacityVertices(stride);
            }
            void beginBulk() {
                requireEmissionValid();
                if (bulkRemaining != -1 || vertices % 4 != 0 || capacityVertices - vertices < 4)
                    throw poison("Native page bulk output must fit one complete quad");
                preflightVertexBytes(Math.multiplyExact(4, stride));
                bulkRemaining = 4;
            }
            void endBulk(boolean success) {
                checkOwner();
                int remaining = bulkRemaining; bulkRemaining = -1;
                if (success && remaining != 0) throw poison("Native page bulk output did not reserve exactly four vertices");
            }
            @Override public long reserve(int bytes) {
                requireEmissionValid();
                if (bytes <= 0 || bytes % stride != 0)
                    throw poison("Native page reservation must contain complete captured-stride vertices");
                int count = bytes / stride;
                if ((count != 1 && count != 4) || (bulkRemaining < 0 && count != 1)
                        || (bulkRemaining >= 0 && count > bulkRemaining) || count > capacityVertices - vertices)
                    throw poison("Native page storage exceeded its fixed format/capacity/quad scope");
                reserveVertex(bytes); // All requested bytes are charged before native allocation/copy.
                long pointer = super.reserve(bytes); vertices += count;
                reservationCalls++;
                if (count == 4) bulkQuadReservationCalls++;
                if (bulkRemaining >= 0) bulkRemaining -= count;
                MemoryUtil.memSet(pointer, 0, bytes); // Stable padding; genuine emitted attributes remain untouched.
                return pointer;
            }
        }

        /** Registered mutable owner; it remains retained even if initialize() or seal() fails. */
        private final class Piece implements AutoCloseable {
            private final Layer layer;
            private final int ordinal;
            private final List<SurvivalPageGeometry.MemberRange> ranges = new ArrayList<>(64);
            private SurvivalPageCpuLease lease;
            private Storage storage;
            private BufferBuilder builder;
            private MeshData mesh;
            private VertexFormat format;
            private int stride, capacityVertices, vertices;
            private boolean sealed, closeComplete;
            Piece(Layer layer, int ordinal) { this.layer = layer; this.ordinal = ordinal; }
            void initialize() {
                lease = new SurvivalPageCpuLease(budget, SurvivalPageGeometry.MAX_PIECE_BYTES + PIECE_METADATA_BYTES);
                var captured = bridge.capture().values();
                stride = captured.stride(); capacityVertices = SurvivalPageGeometry.capacityVertices(stride);
                storage = new Storage(stride);
                builder = new BufferBuilder(storage, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                if (!(builder instanceof SceneBufferBuilderAccessor actual))
                    throw poison("Pinned native BufferBuilder format accessor is absent");
                format = actual.earthplatform$format();
                bridge.requireFormat(format, actual.earthplatform$vertexSize());
                if (actual.earthplatform$vertexSize() != stride || format.getVertexSize() != stride)
                    throw poison("Native builder changed captured stride");
            }
            void record(int member, int count) {
                vertices = Math.addExact(vertices, count);
                // Generic addVertex supplies one vertex at a time; publish metadata only for complete quads.
                if (vertices % 4 != 0) return;
                if (ranges.isEmpty() || ranges.getLast().member() != member)
                    ranges.add(new SurvivalPageGeometry.MemberRange(member, vertices - 4, 4));
                else {
                    var previous = ranges.removeLast();
                    ranges.add(new SurvivalPageGeometry.MemberRange(member, previous.firstVertex(), previous.vertices() + 4));
                }
            }
            void seal() {
                if (sealed) return;
                if (vertices == 0 || vertices % 4 != 0 || vertices != storage.vertices)
                    throw poison("Native page piece must contain exact complete emitted quads");
                mesh = builder.build();
                if (mesh == null) throw poison("Nonempty native page produced no mesh");
                var actual = mesh.drawState();
                bridge.requireFormat(actual.format(), actual.format().getVertexSize());
                if (actual.mode() != VertexFormat.Mode.QUADS || actual.vertexCount() != vertices || actual.format() != format
                        || mesh.vertexBuffer().remaining() != Math.multiplyExact(vertices, stride))
                    throw poison("Native page mesh differs from captured format/emission accounting");
                SurvivalPageGeometry.requireRanges(ranges, vertices);
                SurvivalPageGeometry.relocatePositions(mesh.vertexBuffer(), stride, ranges);
                sealed = true;
            }
            long retainedCpuBytes() { return lease == null ? 0 : lease.bytes(); }
            @Override public void close() {
                if (closeComplete) return;
                Throwable failure = null;
                if (mesh != null) try { mesh.close(); mesh = null; }
                catch (RuntimeException | Error error) { failure = error; }
                // MeshData results must be closed before their backing native builder can be freed.
                if (mesh == null && storage != null) try { storage.close(); storage = null; }
                catch (RuntimeException | Error error) { failure = append(failure, error); }
                if (failure == null) {
                    builder = null; ranges.clear();
                    if (lease != null) lease.drain(() -> {});
                    closeComplete = true;
                }
                rethrow(failure);
            }
        }
    }

    /**
     * Privately minted exact historical compile provenance, independent of legacy jobs/Build.
     * No constructor or public GPU attachment path exists. It proves actual immutable source input
     * and exact compilation resources/policies, never currentness, DH replacement or draw readiness.
     */
    public static final class AuthenticatedContent implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private SurvivalCompleteOverlay.FrameLatch frame;
        private SceneNativeShaderContract.Capture capture;
        private final SurvivalDistancePagePlan.Page page;
        private final SceneNeighborhood.LightingPolicy lighting;
        private final boolean directional;
        private final NativeDistanceCpuBudget.Reservation charge;
        private boolean closed;
        private AuthenticatedContent(SurvivalCompleteOverlay.FrameLatch frame, SurvivalDistancePagePlan.Page page,
                SceneNativeShaderContract.Capture capture, SceneNeighborhood.LightingPolicy lighting,
                boolean directional, NativeDistanceCpuBudget.Reservation charge) {
            this.frame = frame; this.page = page; this.capture = capture;
            this.lighting = lighting; this.directional = directional; this.charge = charge;
        }
        private void requireOpen() {
            if (owner != Thread.currentThread() || closed) throw new IllegalStateException("Authenticated content owner is closed or on another thread");
        }
        /** Internal transfer consistency. Numeric page equality alone never authenticates content. */
        public void requireExactPage(SurvivalDistancePagePlan.Page expected) {
            requireOpen(); if (page != expected) throw new IllegalStateException("Physical page is not the exact authenticated compiler page");
        }
        /** The live GPU wrapper must also authenticate its own completion before calling this. */
        public void requireMatches(SurvivalCompleteOverlay.PlanInput exactPlan, SceneNativeShaderContract.Capture exactCapture,
                SceneNeighborhood.LightingPolicy expectedLighting, boolean expectedDirectional) {
            requireOpen(); frame.requireExactPlan(exactPlan);
            if (capture != exactCapture || lighting != expectedLighting || directional != expectedDirectional)
                throw new IllegalStateException("Compilation resources or lighting differ from required provenance");
            if (!capture.isCurrent()) throw new IllegalStateException("Authenticated compilation resource capture was retired");
        }
        /** Direct ticket charge; shared SOURCE/PLAN and retained frame/handle charges remain in the global ledger. */
        public long retainedMetadataBytes() { requireOpen(); return charge.bytes(); }
        @Override public void close() {
            if (owner != Thread.currentThread()) throw new IllegalStateException("Authenticated content requires its compilation owner");
            if (closed) return;
            frame.close(); frame = null; capture = null; closed = true;
            charge.releaseAfterPhysicalDrain();
        }
    }

    /**
     * Bounded internal stage; production supplies Build's admitted immutable map, not caller data.
     * A source cube setup is soft-budget work: generation/decode cannot be interrupted halfway, but
     * each is limited to one 4096-cell cube and explicitly timed. No model callback runs here.
     */
    static final class Verification implements AutoCloseable {
        static final long SCRATCH_BYTES = 4L << 20;
        static final long METADATA_BYTES = 4096;
        private final Thread owner = Thread.currentThread();
        private SurvivalCompleteOverlay.FrameLatch frame;
        private Map<CubePos, SceneCube> cubes;
        private List<CubePos> dependencies;
        private SurvivalCompleteOverlay.DependencyCheck checking;
        private SceneNativeShaderContract.Capture capture;
        private final SurvivalDistancePagePlan.Page page;
        private final SceneNeighborhood.LightingPolicy lighting;
        private final boolean directional;
        private NativeDistanceCpuBudget.Reservation metadata, scratch;
        private final int totalCells, totalDependencies;
        private int checkedCells, checkedDependencies, generated;
        private boolean failed, closed, transferred;
        private VerificationStats last;
        Verification(SurvivalCompleteOverlay.CurrentLease authority, SurvivalCompleteOverlay.PlanInput exactPlan,
                int pageIndex, Map<CubePos, SceneCube> ownedImmutableCubes, SceneNativeShaderContract.Capture capture,
                SceneNeighborhood.LightingPolicy lighting, boolean directional, NativeDistanceCpuBudget budget) {
            this.capture = Objects.requireNonNull(capture); this.lighting = Objects.requireNonNull(lighting);
            this.directional = directional; Objects.requireNonNull(ownedImmutableCubes);
            if (!capture.isCurrent()) throw new IllegalStateException("Verification requires its exact live resource capture");
            metadata = budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,
                    Math.addExact(METADATA_BYTES, capture.metadataBytes()));
            try {
                scratch = budget.reserve(NativeDistanceCpuBudget.Pool.SOURCE, SCRATCH_BYTES);
                frame = authority.latch(exactPlan); page = frame.page(pageIndex);
                if (ownedImmutableCubes.size() > SurvivalVisualPage.MAX_DEPENDENCIES
                        || !page.dependencies().equals(ownedImmutableCubes.keySet()))
                    throw new IllegalArgumentException("Authentication requires the complete exact planned dependency union");
                cubes = ownedImmutableCubes;
                dependencies = cubes.keySet().stream().sorted().toList();
                totalDependencies = dependencies.size(); totalCells = Math.multiplyExact(totalDependencies, CubePos.VOLUME);
                last = new VerificationStats(0, 0, totalCells, 0, totalDependencies, 0, 0, 0, 0, 0, false);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        /** Only the private retained-start path supplies this already owner-minted latch. */
        private Verification(SurvivalCompleteOverlay.FrameLatch prelatched, SurvivalCompleteOverlay.PlanInput exactPlan,
                int pageIndex, Map<CubePos, SceneCube> ownedImmutableCubes, SceneNativeShaderContract.Capture capture,
                SceneNeighborhood.LightingPolicy lighting, boolean directional, NativeDistanceCpuBudget budget) {
            this.capture=Objects.requireNonNull(capture);this.lighting=Objects.requireNonNull(lighting);this.directional=directional;
            frame=Objects.requireNonNull(prelatched);
            try {
                frame.requireExactPlan(exactPlan);
                if(!capture.isCurrent())throw new IllegalStateException("Verification requires its exact live resource capture");
                metadata=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,Math.addExact(METADATA_BYTES,capture.metadataBytes()));
                scratch=budget.reserve(NativeDistanceCpuBudget.Pool.SOURCE,SCRATCH_BYTES);
                page=frame.page(pageIndex);
                if(ownedImmutableCubes.size()>SurvivalVisualPage.MAX_DEPENDENCIES
                        ||!page.dependencies().equals(ownedImmutableCubes.keySet()))
                    throw new IllegalArgumentException("Authentication requires the complete exact planned dependency union");
                cubes=ownedImmutableCubes;dependencies=cubes.keySet().stream().sorted().toList();
                totalDependencies=dependencies.size();totalCells=Math.multiplyExact(totalDependencies,CubePos.VOLUME);
                last=new VerificationStats(0,0,totalCells,0,totalDependencies,0,0,0,0,0,false);
            } catch(RuntimeException|Error error){close();throw error;}
        }
        private void requireOpen() {
            if (owner != Thread.currentThread() || closed || transferred || failed)
                throw new IllegalStateException("Dependency verification is not resumable");
        }
        VerificationStats step(int maxCells, long maxNanos) {
            requireOpen();
            if (maxCells < 1 || maxNanos < 1 || checkedCells == totalCells)
                throw new IllegalArgumentException("Positive unfinished verification slice required");
            long start = System.nanoTime(), maxSetup = 0; int initial = checkedCells;
            try {
                if (!capture.isCurrent()) throw new IllegalStateException("Resources changed during dependency verification");
                do {
                    if (checking == null) {
                        long setup = System.nanoTime();
                        CubePos at = dependencies.get(checkedDependencies);
                        try { checking = frame.beginDependency(at, cubes.get(at)); }
                        finally { maxSetup = Math.max(maxSetup, elapsed(setup)); }
                        if (checking.generated()) generated++;
                    }
                    checking.verifyCell(); checkedCells++;
                    if (checking.complete()) { checking.close(); checking = null; checkedDependencies++; }
                } while (checkedCells < totalCells && checkedCells - initial < maxCells && elapsed(start) < maxNanos);
                if (!capture.isCurrent()) throw new IllegalStateException("Resources changed during dependency verification");
            } catch (RuntimeException | Error failure) { failed = true; throw failure; }
            finally {
                long duration = elapsed(start);
                last = new VerificationStats(checkedCells - initial, checkedCells, totalCells, checkedDependencies,
                        totalDependencies, generated, duration, maxNanos, Math.max(0, duration - maxNanos), maxSetup,
                        checkedCells == totalCells && !failed);
            }
            return last;
        }
        AuthenticatedContent finish() {
            requireOpen();
            if (checkedCells != totalCells || checking != null || !capture.isCurrent())
                throw new IllegalStateException("Complete exact dependency verification and live resources are required");
            // Drop every decoded/source scratch/map/list alias before returning the SOURCE charge.
            cubes = null; dependencies = null;
            scratch.releaseAfterPhysicalDrain(); scratch = null;
            var result = new AuthenticatedContent(frame, page, capture, lighting, directional, metadata);
            frame = null; capture = null; metadata = null; transferred = true;
            return result;
        }
        long retainedBytes() {
            if (owner != Thread.currentThread()) throw new IllegalStateException("Verification requires its compilation owner");
            return (metadata == null ? 0 : metadata.bytes()) + (scratch == null ? 0 : scratch.bytes());
        }
        @Override public void close() {
            if (owner != Thread.currentThread()) throw new IllegalStateException("Verification requires its compilation owner");
            if (closed) return;
            if (checking != null) { checking.close(); checking = null; }
            cubes = null; dependencies = null; capture = null;
            if (frame != null) { frame.close(); frame = null; }
            if (scratch != null) { scratch.releaseAfterPhysicalDrain(); scratch = null; }
            if (metadata != null) { metadata.releaseAfterPhysicalDrain(); metadata = null; }
            closed = true;
        }
    }

    /**
     * Private-minted CPU evidence. Diagnostic raw buffers live until close; obtaining one permanently
     * forbids upload transfer. transferToUpload() revokes every caller and PassPiece accessor, including
     * caller close, and hands one exclusive owner to the retained physical uploader.
     */
    public static final class CompiledPage implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final SurvivalDistancePagePlan.Page page;
        private final long completedMemberMask;
        private final List<Build.Piece> owners;
        private List<PassPiece> views;
        private final SurvivalPageTransferGate ownership = new SurvivalPageTransferGate();
        private SurvivalNativeCompilerBridge bridge;
        private InputOwnership inputs;
        private AuthenticatedContent authenticated;
        private boolean authenticatedTransferred, closeComplete;
        private CompiledPage(SurvivalDistancePagePlan.Page page, long completedMemberMask, List<Build.Piece> owners,
                SurvivalNativeCompilerBridge bridge, InputOwnership inputs, AuthenticatedContent authenticated) {
            this.page = page; this.completedMemberMask = completedMemberMask; this.owners = new ArrayList<>(owners);
            this.bridge = bridge; this.inputs = inputs; this.authenticated = authenticated;
            views = owners.stream().sorted(Comparator.comparing((Build.Piece p) -> p.layer).thenComparingInt(p -> p.ordinal))
                    .map(PassPiece::new).toList();
        }
        public SurvivalDistancePagePlan.Page page() { checkOpen(); return page; }
        public long completedMemberMask() { checkOpen(); return completedMemberMask; }
        public List<PassPiece> pieces() { checkOpen(); return views; }
        public List<RenderPublicationGuard.Job> dependencyJobs() { checkOpen(); return bridge.dependencyJobs(); }
        public Set<CubePos> observedOwners() { checkOpen(); return bridge.observedOwners(); }
        public SceneNativeShaderContract.Capture shaderCapture() { checkOpen(); return bridge.capture(); }
        /** Actual allocator calls, not inferred from completed vertex counts or model shape. */
        public long reservationCalls() {
            checkOpen(); long count = 0;
            for (var owner : owners) count = Math.addExact(count, owner.storage.reservationCalls);
            return count;
        }
        public long bulkQuadReservationCalls() {
            checkOpen(); long count = 0;
            for (var owner : owners) count = Math.addExact(count, owner.storage.bulkQuadReservationCalls);
            return count;
        }
        public void requireCurrent() { checkOpen(); bridge.requireCurrent(); }
        public List<LayerStats> layerStats() {
            checkOpen(); var stats = new ArrayList<LayerStats>(3);
            for (Layer layer : Layer.values()) {
                int pieces = 0; long vertices = 0, bytes = 0, indices = 0;
                for (var piece : views) if (piece.layer() == layer) {
                    pieces++; vertices += piece.vertices(); bytes += piece.vertexBytes(); indices += piece.indexBytes();
                }
                stats.add(new LayerStats(layer, pieces, vertices, bytes, indices));
            }
            return List.copyOf(stats);
        }
        public long retainedCpuBytes() { checkOwner(); ownership.requireOwner(null); return retainedBytes(); }
        private long retainedBytes() {
            long bytes = inputs == null ? 0 : inputs.bytes();
            if (authenticated != null) bytes = Math.addExact(bytes, authenticated.retainedMetadataBytes());
            for (var piece : owners) bytes = Math.addExact(bytes, piece.retainedCpuBytes());
            return bytes;
        }
        /** Cleanup ownership observation only; never compiler, physical or frame completion. */
        public boolean callerOwnsCleanup() { checkOwner(); return ownership.callerOwnsCleanup(); }
        public boolean closeComplete() { checkOwner(); ownership.requireOwner(null); return closeComplete; }
        private void checkOpen() { checkOwner(); ownership.requireOpen(null); }
        private void checkOwner() {
            if (owner != Thread.currentThread()) throw new IllegalStateException("Native CPU page requires its render owner");
            RenderSystem.assertOnRenderThread();
        }
        /** Construct and retain the GPU wrapper BEFORE calling its start(this) method. */
        public UploadOwner transferToUpload() {
            checkOpen();
            var result = new UploadOwner(); // Allocate before revoking the old owner.
            ownership.transfer(result);
            return result;
        }
        @Override public void close() { closeOwned(null); }
        private void closeOwned(Object token) {
            checkOwner(); ownership.beginClose(token); if (closeComplete) return;
            Throwable failure = null;
            for (var piece : owners) try { piece.close(); }
            catch (RuntimeException | Error error) { failure = append(failure, error); }
            if (failure == null) {
                // Drop every Piece/Build alias before draining the input metadata charge. The bridge
                // releases legacy admission jobs; a completed GPU page retains no legacy guard jobs.
                owners.clear(); views = List.of();
                if (bridge != null) { bridge.close(); bridge = null; }
                if (authenticated != null) { authenticated.close(); authenticated = null; }
                if (inputs != null) { inputs.drain(() -> {}); inputs = null; }
                closeComplete = true;
            }
            rethrow(failure);
        }
        /** Pure read-only content metadata; it confers no source, resource or publication authority. */
        public record PieceMetadata(Layer layer, int ordinal, int stride, int vertices,
                List<SurvivalPageGeometry.MemberRange> memberRanges) {
            public PieceMetadata { memberRanges = List.copyOf(memberRanges); }
            public int vertexBytes() { return Math.multiplyExact(vertices, stride); }
            public int indexBytes() { return Math.multiplyExact(vertices / 4, 6 * Integer.BYTES); }
        }
        @FunctionalInterface public interface VertexUpload {
            /** Synchronous borrowed native bytes. Do not retain bytes, format or aliases to either. */
            void upload(ByteBuffer vertices, VertexFormat format);
        }
        /** Only CompiledPage can mint this transfer capability; no public constructor or boolean proof. */
        public final class UploadOwner implements AutoCloseable {
            private UploadOwner() {}
            private void check() { checkOwner(); ownership.requireOpen(this); }
            private Build.Piece piece(int index) { check(); return views.get(index).piece; }
            public SurvivalDistancePagePlan.Page page() { check(); return page; }
            /**
             * Exclusive producer-to-GPU transfer. No public GPU API accepts a detached proof. Once
             * taken, CPU close cannot release it; the retained physical GPU owner must close it only
             * after every storage/restoration/CPU cleanup obligation drains. Diagnostic input is null.
             */
            public AuthenticatedContent takeAuthenticatedContent() {
                check();
                if (authenticatedTransferred) throw new IllegalStateException("Authenticated compiler provenance already transferred");
                authenticatedTransferred = true;
                var result = authenticated; authenticated = null; return result;
            }
            public long completedMemberMask() { check(); return completedMemberMask; }
            public int pieceCount() { check(); return views.size(); }
            public int rangeCount(int index) { return piece(index).ranges.size(); }
            public int vertexBytes(int index) { var p = piece(index); return Math.multiplyExact(p.vertices, p.stride); }
            public int indexBytes(int index) { return Math.multiplyExact(piece(index).vertices / 4, 6 * Integer.BYTES); }
            /** Caller must reserve bounded OWNER_METADATA before requesting these copied ranges. */
            public PieceMetadata metadata(int index) {
                var p = piece(index);
                return new PieceMetadata(p.layer, p.ordinal, p.stride, p.vertices, p.ranges);
            }
            public void withVertices(int index, VertexUpload upload) {
                check(); Objects.requireNonNull(upload);
                ownership.beginBorrow(this);
                try {
                    var p = views.get(index).piece;
                    // This is a local compile/input fence, never a surviving full-scene publication proof.
                    bridge.requireCurrent();
                    upload.upload(p.mesh.vertexBuffer().asReadOnlyBuffer(), p.format);
                    bridge.requireCurrent();
                } finally { ownership.endBorrow(this); }
            }
            public long retainedCpuBytes() { checkOwner(); ownership.requireOwner(this); return retainedBytes(); }
            public boolean closeComplete() { checkOwner(); ownership.requireOwner(this); return closeComplete; }
            @Override public void close() { closeOwned(this); }
        }
        public final class PassPiece {
            private final Build.Piece piece;
            private PassPiece(Build.Piece piece) { this.piece = piece; }
            public Layer layer() { checkOpen(); return piece.layer; }
            /** Zero-based within this page and layer; layer plus ordinal is the piece key. */
            public int ordinal() { checkOpen(); return piece.ordinal; }
            public VertexFormat format() { checkOpen(); return piece.format; }
            public int stride() { checkOpen(); return piece.stride; }
            public int vertices() { checkOpen(); return piece.vertices; }
            public int vertexBytes() { checkOpen(); return Math.multiplyExact(piece.vertices, piece.stride); }
            public int indexBytes() { checkOpen(); return Math.multiplyExact(piece.vertices / 4, 6 * Integer.BYTES); }
            public List<SurvivalPageGeometry.MemberRange> memberRanges() { checkOpen(); return List.copyOf(piece.ranges); }
            public ByteBuffer vertexBuffer() { checkOpen(); ownership.diagnosticBorrow(); return piece.mesh.vertexBuffer().asReadOnlyBuffer(); }
        }
    }
    private static long elapsed(long start) { return Math.max(0, System.nanoTime() - start); }
    private static Throwable append(Throwable prior, Throwable next) {
        if (prior == null) return next;
        if (prior != next) prior.addSuppressed(next);
        return prior;
    }
    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
