package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.compat.dh.InstalledDhResolver;
import io.earthplatform.compat.dh.InstalledDhTerrainRuntime;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.compat.dh.shader.InstalledSurvivalShaderCarrier;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.lod.SurvivalDhGpuLedger;
import io.earthplatform.minecraft.lod.SurvivalDhGpuPages;
import io.earthplatform.minecraft.lod.SurvivalDhPageCompiler;
import io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay;
import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.survival.render.SurvivalDhMaterialPalette;
import io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler;
import io.earthplatform.minecraft.survival.render.SurvivalSceneAuthorizationMailbox;
import io.earthplatform.minecraft.survival.render.SurvivalSceneDepth;
import io.earthplatform.minecraft.survival.source.SurvivalSceneSourceAssets;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import org.joml.Matrix4f;

/**
 * Opt-in fixed-source whole-plan assembly. No host registration, setting change, gameplay readiness,
 * ordinary-height mirror, automatic generation replacement, world/player read or GUI action.
 *
 * Retain this wrapper BEFORE start, and pump advance only on its actual render owner. Pump dhWork()
 * on the host's one source worker and authorization().authorize(...) on the mutable source owner.
 * Native CPU inputs are page-local; EVERY GPU page remains retained until the whole generation
 * finishes or physically drains. A prior/current/failed scheduler shares the same global ledgers.
 */
public final class SurvivalSceneScheduler implements AutoCloseable {
    public enum Phase { NEW, NATIVE_INPUTS, NATIVE_COMPILE, NATIVE_UPLOAD, DH_WAIT, DH_UPLOAD, ASSEMBLED, ATTACHED, REFUSED, CLOSING, CLOSED }
    /** Bounded strong roots, including partially started and cleanup-failed generations. */
    private static final List<SurvivalSceneScheduler> RETAINED = new ArrayList<>(2);
    private final Thread owner = Thread.currentThread();
    private final NativeDistanceCpuBudget cpu = NativeDistanceCpuBudget.global();
    private final NativeVisualBudget gpu;
    private final CancellationToken sourceCancellation = new CancellationToken();
    private NativeDistanceCpuBudget.Reservation metadata;
    private final SurvivalSceneOwnerRoster<SurvivalNativeGpuPage> pages;
    private final List<SurvivalNativeGpuPage.Completion> completions;
    private SurvivalCompleteOverlay.PlanInput plan;
    private SurvivalCompleteOverlay.CurrentLease current, pendingLease, retiredLease;
    private SurvivalCompleteOverlay.FrameLatch validationLatch;
    private SurvivalSceneAuthorizationMailbox authorization;
    private SurvivalSceneSourceAssets assets;
    private WorldIdentity world;
    private InstalledDhResolver.Runtime installedDh;
    private InstalledIrisRuntime iris;
    private SurvivalSceneResources resources;
    private SurvivalDhCoverage coverage;
    private SurvivalSceneDhWork dhWork;
    private SurvivalSceneNativeInputs inputs;
    private SurvivalNativePageCompiler.Build build;
    private SurvivalNativePageCompiler.AuthenticatedStart authenticatedStart;
    private SurvivalNativePageCompiler.CompiledPage compiled;
    private SurvivalNativeGpuPage uploading;
    private SurvivalDhPageCompiler.CompiledSet dhCompiled;
    private SurvivalDhGpuPages dhGpu;
    private SurvivalSceneFrameJoin join;
    private InstalledSurvivalShaderCarrier carrier;
    private Phase phase = Phase.NEW;
    private int nextPage;
    private boolean operating, authorizationPending;
    private String failure = "";
    private int observedRegistryStates, observedBiomeEntries;
    private long advanceNanos, advanceOvershootNanos;
    private long dependencyStartNanos, dependencyStartOvershootNanos;
    private long dependencySetupNanos, dependencyOvershootNanos, nativeSliceNanos, nativeOvershootNanos, uploadSliceNanos, uploadOvershootNanos;

    /** No input/physical resource transfer here. Global root precedes every fallible start. */
    public SurvivalSceneScheduler() {
        RenderSystem.assertOnRenderThread();
        synchronized (RETAINED) {
            if (RETAINED.size() >= 2) throw new IllegalStateException("Two retained scene generations already own their allowances");
            gpu = NativeVisualBudget.global();
            // Complete bounded page/completion vectors, preview masks, state and transient list copies.
            metadata = cpu.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, 1L << 20);
            try {
                pages = new SurvivalSceneOwnerRoster<>(NativeVisualBudget.MAX_PIECES);
                completions = new ArrayList<>(NativeVisualBudget.MAX_PIECES);
                RETAINED.add(this);
            } catch (RuntimeException | Error error) { metadata.releaseAfterPhysicalDrain(); throw error; }
        }
    }
    public static List<SurvivalSceneScheduler> retainedOwners() {
        RenderSystem.assertOnRenderThread();
        synchronized (RETAINED) { return List.copyOf(RETAINED); }
    }
    /**
     * Matrices/camera are provisional clipping CONTENT only. Actual private render hooks must later
     * reproduce every mask; they do not use these caller objects to mint a frame or draw permit.
     */
    public void start(SurvivalCompleteOverlay.PlanInput exactPlan, SurvivalSceneSourceAssets sourceAssets,
            WorldIdentity exactWorld, InstalledDhResolver.Runtime dh, InstalledDhTerrainRuntime terrain,
            InstalledIrisRuntime iris, Matrix4f provisionalView, Matrix4f provisionalProjection,
            double cameraX, double cameraY, double cameraZ) {
        owner(); if (operating || phase != Phase.NEW) throw new IllegalStateException("Whole-scene start is single-use");
        operating = true;
        try {
            Objects.requireNonNull(exactPlan).requireBudget(cpu);
            int count = exactPlan.pageCount();
            if (count < 1 || count > NativeVisualBudget.MAX_PIECES || !exactPlan.distance().selectedDistanceEffective())
                throw new IllegalStateException("Complete selected-distance native plan exceeds unchanged admission");
            plan = exactPlan.retain(); assets = Objects.requireNonNull(sourceAssets); world = Objects.requireNonNull(exactWorld);
            installedDh = Objects.requireNonNull(dh); this.iris = Objects.requireNonNull(iris);
            Objects.requireNonNull(terrain);
            try (var content = plan.retainContent()) {
                content.requireSource(assets.source());
            }
            var lease = iris.leaseDistance();
            var distance = plan.distance();
            if (distance.selectedChunks() != lease.selectedRenderDistance() || distance.effectiveChunks() != lease.effectiveRenderDistance()
                    || distance.effectiveCapChunks() != Math.max(0, lease.serverRenderDistanceLimit()))
                throw new IllegalStateException("Plan differs from actual unchanged selected/effective distance");
            resources = SurvivalSceneResources.capture(lease, cpu); // BEFORE native compilation.
            var view = new Matrix4f(Objects.requireNonNull(provisionalView));
            var projection = new Matrix4f(Objects.requireNonNull(provisionalProjection));
            var depth = SurvivalSceneDepth.inspect(distance, plan.bounds(), projection, view, cameraX, cameraY, cameraZ);
            var masks = new TreeMap<CubePos, Long>();
            for (int i = 0; i < count; i++) {
                var page = plan.page(i);
                long mask = SurvivalSceneDepth.eligibleMask(page, view, cameraX, cameraY, cameraZ, depth);
                if (mask != 0) masks.put(page.address().minimum(), mask);
            }
            coverage = SurvivalDhCoverage.capture(plan, masks, cpu);
            authorization = new SurvivalSceneAuthorizationMailbox(plan, cpu);
            var bounds = plan.bounds();
            int focusX = Math.toIntExact(Math.floorDiv(bounds.minX() + bounds.maxXExclusive() - 16, 32));
            int focusZ = Math.toIntExact(Math.floorDiv(bounds.minZ() + bounds.maxZExclusive() - 16, 32));
            try (var material = new SurvivalDhMaterialPalette(assets.source().palette(), cpu)) {
                dhWork = new SurvivalSceneDhWork(assets, coverage, material,
                        MarinSurvivalDefinition.snapCubeCenter(focusX), MarinSurvivalDefinition.snapCubeCenter(focusZ), terrain, cpu);
            }
            authorizationPending = true; phase = Phase.NATIVE_INPUTS;
        } catch (RuntimeException | Error error) { refuse(error); throw error; }
        finally { operating = false; }
    }
    /** Pure exact-plan mailbox. No simulation owner is retained by this renderer. */
    public SurvivalSceneAuthorizationMailbox authorization() { owner(); return Objects.requireNonNull(authorization); }
    /** Immutable source job; caller must continue its source pump through requested cleanup. */
    public SurvivalSceneDhWork dhWork() { owner(); return Objects.requireNonNull(dhWork); }

    public record Slice(int maxDependencyCubes, int maxBlocks, int maxUploadPieces, long maxNanos) {
        public Slice {
            if (maxDependencyCubes < 1 || maxDependencyCubes > 216 || maxBlocks < 1 || maxBlocks > 262144
                    || maxUploadPieces < 1 || maxUploadPieces > 1536 || maxNanos < 1)
                throw new IllegalArgumentException("Explicit bounded survival render slice required");
        }
    }
    /** One indivisible setup/model/upload may overshoot maxNanos; evidence reports the actual overshoot. */
    public void advance(Slice slice) {
        owner(); Objects.requireNonNull(slice);
        if (operating || phase == Phase.NEW || phase == Phase.REFUSED || phase == Phase.CLOSING || phase == Phase.CLOSED)
            throw new IllegalStateException("Scene scheduler is not resumable: " + phase);
        operating = true;
        long advanceBegin = System.nanoTime();
        try {
            resources.requireCurrent();
            receiveAuthorization();
            if (dhWork.evidence().failure().length() != 0) throw new IllegalStateException("DH source refused: " + dhWork.evidence().failure());
            switch (phase) {
                case NATIVE_INPUTS -> {
                    long dependencyBegin = System.nanoTime();
                    if (inputs == null) {
                        if (!haveCurrentAuthorization()) return;
                        inputs = new SurvivalSceneNativeInputs(cpu);
                        try { inputs.start(plan, nextPage, assets, world); }
                        finally {
                            long setup = Math.max(0, System.nanoTime() - dependencyBegin);
                            dependencyStartNanos = Math.max(dependencyStartNanos, setup);
                            dependencyStartOvershootNanos = Math.max(dependencyStartOvershootNanos, Math.max(0, setup - slice.maxNanos()));
                            observedRegistryStates = inputs.registryStates(); observedBiomeEntries = inputs.biomeEntries();
                        }
                        if (Math.max(0, System.nanoTime() - dependencyBegin) >= slice.maxNanos()) return;
                    }
                    long remaining = Math.max(1, slice.maxNanos() - Math.max(0, System.nanoTime() - dependencyBegin));
                    var step = inputs.step(slice.maxDependencyCubes(), remaining, sourceCancellation);
                    dependencySetupNanos = Math.max(dependencySetupNanos, step.maxCubeSetupNanos());
                    dependencyOvershootNanos = Math.max(dependencyOvershootNanos,
                            Math.max(0, Math.max(0, System.nanoTime() - dependencyBegin) - slice.maxNanos()));
                    if (step.complete()) phase = Phase.NATIVE_COMPILE;
                }
                case NATIVE_COMPILE -> {
                    if (build == null) {
                        if (!haveCurrentAuthorization()) return;
                        var capture = resources.nativeCapture();
                        authenticatedStart = SurvivalNativePageCompiler.prepareAuthenticated(current, plan, nextPage, inputs.cubes(),
                                inputs.guard(), SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,
                                capture.values().disableDirectionalShading(), capture, cpu);
                        build = authenticatedStart.tryBeginAuthenticated();
                        authenticatedStart.close(); authenticatedStart = null;
                        if (build == null) { authorizationPending = true; authorization.request(); return; }
                    }
                    var step = build.step(slice.maxBlocks(), slice.maxNanos());
                    nativeSliceNanos = Math.max(nativeSliceNanos, step.elapsedNanos());
                    nativeOvershootNanos = Math.max(nativeOvershootNanos, step.overshootNanos());
                    if (step.complete()) {
                        compiled = build.finish(); build = null;
                        uploading = new SurvivalNativeGpuPage(gpu, cpu);
                        pages.add(uploading); // Strongly retained BEFORE claim/reservation/GL.
                        try { uploading.start(compiled); }
                        finally { if (!compiled.callerOwnsCleanup()) compiled = null; }
                        phase = Phase.NATIVE_UPLOAD;
                    }
                }
                case NATIVE_UPLOAD -> {
                    var step = uploading.step(slice.maxUploadPieces(), slice.maxNanos());
                    uploadSliceNanos = Math.max(uploadSliceNanos, step.elapsedNanos());
                    uploadOvershootNanos = Math.max(uploadOvershootNanos, step.overshootNanos());
                    if (step.complete()) {
                        var completed = uploading.completion();
                        var capture = resources.nativeCapture();
                        completed.requireAuthenticated(plan, capture, SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,
                                capture.values().disableDirectionalShading());
                        if (completed.page() != plan.page(nextPage)) throw new IllegalStateException("Different completed native page");
                        completions.add(completed); uploading = null;
                        inputs.close(); inputs = null;
                        nextPage++;
                        phase = nextPage == plan.pageCount() ? Phase.DH_WAIT : Phase.NATIVE_INPUTS;
                    }
                }
                case DH_WAIT -> {
                    if (dhWork.evidence().phase() != SurvivalSceneDhWork.Phase.READY) return;
                    dhCompiled = dhWork.take();
                    dhGpu = new SurvivalDhGpuPages(cpu); // Retain BEFORE start.
                    dhGpu.start(dhCompiled); dhCompiled = null;
                    phase = Phase.DH_UPLOAD;
                }
                case DH_UPLOAD -> {
                    var step = dhGpu.step(slice.maxUploadPieces(), slice.maxNanos());
                    uploadSliceNanos = Math.max(uploadSliceNanos, step.elapsedNanos());
                    uploadOvershootNanos = Math.max(uploadOvershootNanos, step.overshootNanos());
                    if (step.complete()) {
                        requireWholePhysicalGeneration();
                        phase = Phase.ASSEMBLED; // No frame, draw, collision or gameplay authority.
                    }
                }
                case ATTACHED -> {
                    var evidence = join.evidence();
                    if (evidence.failed()) throw new IllegalStateException("Actual frame rejected: " + evidence.failure());
                    if (evidence.contentPending()) { authorizationPending = true; authorization.request(); }
                }
                case ASSEMBLED -> requireWholePhysicalGeneration();
                default -> throw new IllegalStateException("Unhandled scheduler phase " + phase);
            }
        } catch (RuntimeException | Error error) { refuse(error); throw error; }
        finally {
            long elapsed = Math.max(0, System.nanoTime() - advanceBegin);
            advanceNanos = Math.max(advanceNanos, elapsed);
            advanceOvershootNanos = Math.max(advanceOvershootNanos, Math.max(0, elapsed - slice.maxNanos()));
            operating = false;
        }
    }
    private void receiveAuthorization() {
        if (authorization.refusal() != null) throw new IllegalStateException("Exact source owner refused: " + authorization.refusal());
        if (pendingLease == null) pendingLease = authorization.take();
        if (pendingLease == null) return;
        if (!validateLease(pendingLease)) { discardUnacceptedLease(); return; }
        if (join != null) {
            retiredLease = join.rebindCurrent(pendingLease);
            if (retiredLease == null) { discardUnacceptedLease(); return; }
        } else retiredLease = current;
        current = pendingLease; pendingLease = null; authorizationPending = false;
        if (retiredLease != null) { retiredLease.close(); retiredLease = null; }
    }
    private void discardUnacceptedLease() {
        pendingLease.close(); pendingLease = null;
        authorizationPending = true; authorization.request();
    }
    private boolean validateLease(SurvivalCompleteOverlay.CurrentLease lease) {
        if (validationLatch != null) throw new IllegalStateException("Prior content validation cleanup remains");
        validationLatch = lease.tryLatchCurrent(plan);
        if (validationLatch == null) return false; // Only the private exact owner CAS can produce null.
        try { coverage.requireExactPlan(validationLatch); }
        finally { validationLatch.close(); validationLatch = null; }
        return true;
    }
    private boolean haveCurrentAuthorization() {
        if (current != null && validateLease(current)) { authorizationPending = false; return true; }
        authorizationPending = true; authorization.request(); return false;
    }
    private void requireWholePhysicalGeneration() {
        resources.requireCurrent();
        if (nextPage != plan.pageCount() || pages.size() != plan.pageCount() || completions.size() != plan.pageCount())
            throw new IllegalStateException("Whole selected native roster is not simultaneously retained");
        var capture = resources.nativeCapture();
        for (int i = 0; i < pages.size(); i++) {
            var actual = pages.get(i).completion();
            if (actual != completions.get(i) || actual.page() != plan.page(i)) throw new IllegalStateException("Native physical roster changed");
            actual.requireAuthenticated(plan, capture, SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,
                    capture.values().disableDirectionalShading());
        }
        dhGpu.completion().requireAuthenticated(coverage, plan);
        dhGpu.completion().requireCurrentConfiguration();
    }
    /** Attempts attachment only; successful real hooks and typed draw completion are separate evidence. */
    public boolean attach() {
        owner();
        if (operating || phase != Phase.ASSEMBLED) throw new IllegalStateException("No assembled candidate to attach");
        operating = true;
        try {
            receiveAuthorization();
            if (!haveCurrentAuthorization()) return false;
            requireWholePhysicalGeneration();
            join = new SurvivalSceneFrameJoin(cpu);
            join.initialize(plan, current, coverage, completions, dhGpu.completion(), resources);
            join.attach();
            carrier = new InstalledSurvivalShaderCarrier(join, resources, installedDh, iris, cpu);
            carrier.start(); phase = Phase.ATTACHED; return true;
        } catch (RuntimeException | Error error) { refuse(error); throw error; }
        finally { operating = false; }
    }
    /** Source owner must authorize the exact retained plan before an effect-only rebind can occur. */
    public void requestAuthorization() { owner(); authorization.request(); authorizationPending = true; }
    private void refuse(Throwable error) {
        phase = Phase.REFUSED;
        if (failure.isEmpty()) {
            String text = error.getClass().getSimpleName() + ": " + error.getMessage();
            failure = text.substring(0, Math.min(256, text.length()));
        }
    }
    public record Evidence(Phase phase, int completedNativePages, int plannedNativePages,
            long retainedNativeGpuBytes, long retainedDhGpuBytes, boolean authorizationPending,
            long maxAdvanceNanos, long maxAdvanceOvershootNanos,
            int nativeRegistryStates, int biomeRegistryEntries,
            long maxDependencyStartNanos, long maxDependencyStartOvershootNanos,
            long maxDependencySetupNanos, long maxDependencyOvershootNanos,
            long maxNativeSliceNanos, long maxNativeOvershootNanos, long maxUploadSliceNanos, long maxUploadOvershootNanos,
            NativeVisualBudget.Evidence globalNative, SurvivalDhGpuLedger.Evidence globalDh,
            NativeDistanceCpuBudget.Evidence globalCpu, SurvivalSceneFrameJoin.Evidence actualFrames, String failure) {}
    public Evidence evidence() {
        owner(); long bytes = 0;
        for (int i=0;i<pages.size();i++) { var page=pages.get(i); if(page!=null)bytes=Math.addExact(bytes,page.chargedGpuBytes()); }
        return new Evidence(phase, nextPage, plan == null ? 0 : plan.pageCount(), bytes,
                dhGpu == null ? 0 : dhGpu.chargedGpuBytes(), authorizationPending,
                advanceNanos, advanceOvershootNanos, observedRegistryStates, observedBiomeEntries, dependencyStartNanos, dependencyStartOvershootNanos,
                dependencySetupNanos, dependencyOvershootNanos, nativeSliceNanos, nativeOvershootNanos,
                uploadSliceNanos, uploadOvershootNanos, gpu.evidence(), SurvivalDhGpuLedger.global().evidence(),
                cpu.evidence(), join == null ? null : join.evidence(), failure);
    }
    /** Retry only this cleanup, never an upload/compile that failed. Uncertain physical owners stay rooted. */
    @Override public void close() {
        owner(); if (phase == Phase.CLOSED) return;
        if (operating) throw new IllegalStateException("Scene operation has not returned");
        operating = true;
        try {
        phase = Phase.CLOSING;
        if (resources != null) resources.retire();
        if (dhWork != null) dhWork.requestCancel();
        // Borrowed bindings must restore before native/DH deletion or upstream resource release.
        if (carrier != null) { carrier.close(); carrier = null; }
        if (join != null) { join.close(); join = null; }
        completions.clear();
        Throwable problem = null;
        try { pages.drain(page -> { page.close(); return page.closeComplete(); }); }
        catch (RuntimeException | Error error) { problem = append(problem, error); }
        if (dhGpu != null) try {
            dhGpu.close(); if (!dhGpu.closeComplete()) throw new IllegalStateException("DH physical ownership remains"); dhGpu = null;
        } catch (RuntimeException | Error error) { problem = append(problem, error); }
        if (compiled != null) try { compiled.close(); if (!compiled.closeComplete()) throw new IllegalStateException("Native CPU output remains"); compiled = null; }
            catch (RuntimeException | Error error) { problem = append(problem, error); }
        if (build != null) try { build.close(); if (!build.closeComplete()) throw new IllegalStateException("Native build remains"); build = null; }
            catch (RuntimeException | Error error) { problem = append(problem, error); }
        if (authenticatedStart != null) try {
            authenticatedStart.close(); if (!authenticatedStart.closeComplete()) throw new IllegalStateException("Authenticated start remains");
            authenticatedStart = null;
        } catch (RuntimeException | Error error) { problem = append(problem, error); }
        if (dhCompiled != null && dhGpu == null) try { dhCompiled.close(); dhCompiled = null; }
            catch (RuntimeException | Error error) { problem = append(problem, error); }
        if (problem != null) rethrow(problem); // Retain caller haloes until all overlapping producers drain.
        uploading = null;
        if (inputs != null) { inputs.close(); inputs = null; }
        if (dhWork != null && !dhWork.closeComplete()) throw new IllegalStateException("Source worker must finish/cancel and drain its exact DH job");
        dhWork = null;
        if (authorization != null) { authorization.close(); authorization = null; }
        if (validationLatch != null) { validationLatch.close(); validationLatch = null; }
        if (pendingLease != null) { pendingLease.close(); pendingLease = null; }
        if (retiredLease != null) { retiredLease.close(); retiredLease = null; }
        if (current != null) { current.close(); current = null; }
        if (resources != null) { resources.close(); resources = null; }
        if (coverage != null) { coverage.close(); coverage = null; }
        if (plan != null) { plan.close(); plan = null; }
        pages.clearAfterDrain(); assets = null; world = null; iris = null; installedDh = null;
        metadata.releaseAfterPhysicalDrain(); metadata = null;
        synchronized (RETAINED) { RETAINED.remove(this); }
        phase = Phase.CLOSED;
        } finally { operating = false; }
    }
    public boolean closeComplete() { owner(); return phase == Phase.CLOSED; }
    private void owner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Whole-scene scheduler requires exact render owner");
        RenderSystem.assertOnRenderThread();
    }
    private static Throwable append(Throwable prior, Throwable next) { if (prior == null) return next; if (prior != next) prior.addSuppressed(next); return prior; }
    private static void rethrow(Throwable error) { if (error instanceof Error fatal) throw fatal; if (error instanceof RuntimeException runtime) throw runtime; }
}
