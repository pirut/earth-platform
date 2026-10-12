package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay;
import io.earthplatform.minecraft.survival.render.SurvivalDistancePagePlan;
import io.earthplatform.minecraft.survival.source.SurvivalSceneSourceAssets;
import io.earthplatform.minecraft.survival.source.MarinVisualCubeSource;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.block.Block;

/** Bounded, precharged exact caller inputs. Native compiler accounting does not backdate this charge. */
final class SurvivalSceneNativeInputs implements AutoCloseable {
    private static final AtomicLong TRANSPORTS = new AtomicLong();
    // Incremental EnginePaletteContext fingerprinting retains one descriptor at a time;
    // this also covers one source 4096-cell descriptor/int copy and native section decode/encode.
    // The full codec reference array is admitted separately from this fixed transient workspace.
    private static final long SCRATCH_BYTES = 2L << 20;
    private static final long CUBE_BYTES = CubePos.VOLUME * 8L + EngineCubeSnapshot.MAX_SECTION_BYTES + 4096;
    private final Thread owner = Thread.currentThread();
    private final NativeDistanceCpuBudget budget;
    private NativeDistanceCpuBudget.Reservation scratch, retained, codecCharge;
    private SurvivalCompleteOverlay.Content content;
    private SurvivalDistancePagePlan.Page page;
    private MarinVisualCubeSource source;
    private NativeCubeCodec codec;
    private CubeStreamAdmission admission;
    private RenderPublicationGuard guard;
    private List<CubePos> dependencies;
    private Map<CubePos, CubeStreamAdmission.Lease> leases;
    private Map<CubePos, SceneCube> cubes;
    private int next, registryStates, biomeEntries;
    private boolean started, closed, failed;
    record Step(int cubes, int completed, int total, long elapsedNanos, long budgetNanos,
            long overshootNanos, long maxCubeSetupNanos, boolean complete) {}
    SurvivalSceneNativeInputs(NativeDistanceCpuBudget budget) { this.budget = budget; }
    /** Retain wrapper before this method. Every map, codec, generated cube and section is precharged. */
    void start(SurvivalCompleteOverlay.PlanInput plan, int index, SurvivalSceneSourceAssets assets, WorldIdentity world) {
        owner();
        if (started || closed) throw new IllegalStateException("Native dependency setup is single-use");
        started = true;
        try {
            plan.requireBudget(budget);
            scratch = budget.reserve(NativeDistanceCpuBudget.Pool.SOURCE, SCRATCH_BYTES);
            page = plan.page(index);
            var expected = page.dependencies(); // Bounded set is covered by setup scratch.
            if (expected.isEmpty() || expected.size() > 216) throw new IllegalStateException("Exact dependency bound changed");
            retained = budget.reserve(NativeDistanceCpuBudget.Pool.SOURCE,
                    Math.addExact(65536L, Math.multiplyExact(CUBE_BYTES, expected.size())));
            dependencies = expected.stream().sorted().toList();
            content = plan.retainContent(); source = assets.source();
            content.requireSource(source);
            registryStates = Block.BLOCK_STATE_REGISTRY.size();
            biomeEntries = source.palette().context().biomes().size();
            codecCharge = budget.reserve(NativeDistanceCpuBudget.Pool.SOURCE,
                    Math.addExact(256L, Math.multiplyExact(8L, registryStates)));
            codec = new NativeCubeCodec(world, source.palette().context());
            long epoch = TRANSPORTS.updateAndGet(Math::incrementExact);
            admission = new CubeStreamAdmission(world, epoch, dependencies.size());
            guard = new RenderPublicationGuard(admission, dependencies.size());
            leases = new TreeMap<>(); cubes = new TreeMap<>();
            for (var lease : admission.replaceInterest(dependencies).requested()) leases.put(lease.position(), lease);
        } catch (RuntimeException | Error failure) { failed = true; throw failure; }
    }
    Step step(int maxCubes, long maxNanos, CancellationToken cancellation) {
        owner();
        if (!started || closed || failed || next == dependencies.size()) throw new IllegalStateException("Native dependencies are not resumable");
        if (maxCubes < 1 || maxNanos < 1) throw new IllegalArgumentException("Positive bounded dependency slice required");
        long begin = System.nanoTime(), longest = 0; int initial = next;
        try {
            do {
                cancellation.throwIfCancelled();
                long setup = System.nanoTime();
                try { materialize(dependencies.get(next), cancellation); }
                finally { longest = Math.max(longest, elapsed(setup)); }
                next++;
            } while (next < dependencies.size() && next - initial < maxCubes && elapsed(begin) < maxNanos);
            cancellation.throwIfCancelled();
            long elapsed = elapsed(begin);
            return new Step(next - initial, next, dependencies.size(), elapsed, maxNanos,
                    Math.max(0, elapsed - maxNanos), longest, next == dependencies.size());
        } catch (RuntimeException | Error failure) { failed = true; throw failure; }
    }
    private void materialize(CubePos position, CancellationToken cancellation) {
        source.validate(position);
        ImmutableCube blocks;
        if (content.hasOverride(position)) {
            int[] values = new int[CubePos.VOLUME];
            for (int i = 0; i < values.length; i++) {
                if ((i & 255) == 0) cancellation.throwIfCancelled();
                values[i] = content.overrideStateId(position, i); // Explicit AIR remains present.
            }
            blocks = ImmutableCube.copyOf(values);
        } else blocks = source.generate(position, cancellation);
        var snapshot = codec.materialize(position, blocks, admission.connectionEpoch(),
                leases.get(position).generation(), content.terrainContentSequence());
        var cube = new SceneCube(snapshot, source.palette().context(), SceneRenderEnvelope.NATIVE,
                state -> { source.palette().id(state); return true; });
        var header = new CubeStreamAdmission.Header(snapshot.world(), snapshot.epoch(), position,
                snapshot.leaseGeneration(), snapshot.revision());
        var allowed = admission.admit(header);
        if (!allowed.accepted() || admission.commit(allowed.token(), () -> cubes.put(position, cube)) != CubeStreamAdmission.Rejection.NONE)
            throw new IllegalStateException("Local exact native dependency admission failed");
    }
    int registryStates() { owner(); return registryStates; }
    int biomeEntries() { owner(); return biomeEntries; }
    Map<CubePos, SceneCube> cubes() { complete(); return cubes; }
    RenderPublicationGuard guard() { complete(); return guard; }
    private void complete() {
        owner(); if (closed || failed || dependencies == null || next != dependencies.size())
            throw new IllegalStateException("Native dependency roster is incomplete");
    }
    @Override public void close() {
        owner(); if (closed) return;
        if (guard != null) { guard.close(); guard = null; }
        if (admission != null) { admission.close(); admission = null; }
        cubes = null; leases = null; dependencies = null; codec = null; source = null; page = null;
        if (content != null) { content.close(); content = null; }
        if (codecCharge != null) { codecCharge.releaseAfterPhysicalDrain(); codecCharge = null; }
        if (retained != null) { retained.releaseAfterPhysicalDrain(); retained = null; }
        if (scratch != null) { scratch.releaseAfterPhysicalDrain(); scratch = null; }
        closed = true;
    }
    boolean closeComplete() { owner(); return closed; }
    private void owner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native dependency materializer requires render owner");
        RenderSystem.assertOnRenderThread();
    }
    private static long elapsed(long begin) { return Math.max(0, System.nanoTime() - begin); }
}
