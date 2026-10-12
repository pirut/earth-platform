package io.earthplatform.minecraft.scene;

import io.earthplatform.compat.dh.InstalledDhTerrainRuntime;
import io.earthplatform.minecraft.lod.SurvivalDhPageCompiler;
import io.earthplatform.minecraft.lod.SurvivalDhTerrainData;
import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.survival.render.SurvivalDhMaterialPalette;
import io.earthplatform.minecraft.survival.source.SurvivalSceneSourceAssets;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One externally pumped immutable-source job. It stores no world/player/client/resource object.
 * All jobs use one exact process source-worker thread; no executor or unbounded queue is created.
 * Scalar generation has bounded work limits but is not resumable; its actual setup time is reported.
 */
public final class SurvivalSceneDhWork {
    public enum Phase { SOURCE, COMPILE, READY, TRANSFERRED, CLEANUP, DRAINED }
    private static final AtomicReference<Thread> SOURCE_WORKER = new AtomicReference<>();
    private final NativeDistanceCpuBudget budget;
    private final InstalledDhTerrainRuntime runtime;
    private final SurvivalSceneSourceAssets assets;
    private final long centerX, centerZ;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private SurvivalDhCoverage coverage;
    private SurvivalDhMaterialPalette materials;
    private NativeDistanceCpuBudget.Reservation charge;
    private SurvivalDhTerrainData.Result result;
    private SurvivalDhPageCompiler compiler;
    private SurvivalDhPageCompiler.CompiledSet output;
    private Thread worker;
    private Phase phase = Phase.SOURCE;
    private boolean operating;
    private String failure = "";
    private long sourceNanos, maxSliceNanos, overshootNanos;
    private int compiledTiles;
    SurvivalSceneDhWork(SurvivalSceneSourceAssets assets, SurvivalDhCoverage coverage,
            SurvivalDhMaterialPalette materials, long centerX, long centerZ,
            InstalledDhTerrainRuntime runtime, NativeDistanceCpuBudget budget) {
        this.assets = assets; this.runtime = runtime; this.budget = budget;
        this.centerX = centerX; this.centerZ = centerZ;
        charge = budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, 8192);
        try { this.coverage = coverage.retain(); this.materials = materials.retain(); }
        catch (RuntimeException | Error error) { releaseInputs(); charge.releaseAfterPhysicalDrain(); charge = null; throw error; }
    }
    /** Run from the host's one source worker. A genuine official helper has its separate owned worker. */
    public void pump(int maxTiles, long maxNanos) {
        if (maxTiles < 1 || maxTiles > 256 || maxNanos < 1) throw new IllegalArgumentException("Bounded DH source slice required");
        synchronized (this) {
            if (phase == Phase.DRAINED || phase == Phase.TRANSFERRED) return;
            if (operating) throw new IllegalStateException("DH source job is already operating");
            Thread here = Thread.currentThread();
            SOURCE_WORKER.compareAndSet(null, here);
            if (SOURCE_WORKER.get() != here || worker != null && worker != here)
                throw new IllegalStateException("Exact single source-worker thread required");
            worker = here; operating = true;
        }
        long begin = System.nanoTime();
        try {
            if (cancelled.get() || phase == Phase.CLEANUP) { drainOnWorker(); return; }
            if (phase == Phase.READY) return;
            if (phase == Phase.SOURCE) {
                result = SurvivalDhTerrainData.build(assets.source(), materials, coverage, centerX, centerZ,
                        SurvivalDhTerrainData.Limits.standard(), budget, this::checkCancellation);
                synchronized (this) { sourceNanos = Math.max(0, System.nanoTime() - begin); }
                compiler = new SurvivalDhPageCompiler(runtime, budget); // Retained BEFORE start.
                compiler.start(result);
                result = null; // Exclusive compiler owner was successfully installed.
                synchronized (this) { phase = Phase.COMPILE; }
                return;
            }
            var step = compiler.step(maxTiles, maxNanos, this::checkCancellation);
            synchronized (this) { compiledTiles = step.completedTiles(); }
            if (step.complete()) {
                output = compiler.finish();
                compiler = null; // Successful exclusive finish: transferred shell cannot be closed.
                releaseInputs();
                synchronized (this) { phase = Phase.READY; }
            }
        } catch (RuntimeException | Error error) {
            synchronized (this) { if (failure.isEmpty()) failure = shortFailure(error); phase = Phase.CLEANUP; }
            try { drainOnWorker(); } catch (RuntimeException | Error cleanup) { error.addSuppressed(cleanup); }
            throw error;
        } finally {
            long elapsed = Math.max(0, System.nanoTime() - begin);
            synchronized (this) {
                maxSliceNanos = Math.max(maxSliceNanos, elapsed);
                overshootNanos = Math.max(overshootNanos, Math.max(0, elapsed - maxNanos));
                operating = false;
            }
        }
    }
    private void checkCancellation() { if (cancelled.get()) throw new IllegalStateException("DH source job cancelled"); }
    /** Safe cancellation never pretends a running helper or retained output has drained. */
    public synchronized void requestCancel() {
        cancelled.set(true);
        if (!operating && worker == null && phase == Phase.SOURCE) {
            releaseInputs(); releaseCharge(); phase = Phase.DRAINED;
        }
    }
    synchronized SurvivalDhPageCompiler.CompiledSet take() {
        if (operating || phase != Phase.READY || cancelled.get() || output == null)
            throw new IllegalStateException("No complete safely published DH output");
        var taken = output; output = null; releaseCharge(); phase = Phase.TRANSFERRED; return taken;
    }
    private void drainOnWorker() {
        if (worker != Thread.currentThread()) throw new IllegalStateException("DH cleanup requires its source worker");
        synchronized (this) { phase = Phase.CLEANUP; }
        // On partial start, compiler/result aliases are both retained until exact cleanup succeeds.
        if (compiler != null) { compiler.close(); if (!compiler.closeComplete()) throw new IllegalStateException("DH compiler cleanup remains"); compiler = null; }
        if (result != null) { result.close(); result = null; }
        if (output != null) { output.close(); output = null; }
        releaseInputs(); releaseCharge();
        synchronized (this) { phase = Phase.DRAINED; }
    }
    private void releaseInputs() {
        if (coverage != null) { coverage.close(); coverage = null; }
        if (materials != null) { materials.close(); materials = null; }
    }
    private void releaseCharge() { if (charge != null) { charge.releaseAfterPhysicalDrain(); charge = null; } }
    public record Evidence(Phase phase, boolean operating, boolean cancelled, int compiledTiles,
            long sourceSetupNanos, long maxSliceNanos, long maxOvershootNanos, String failure) {}
    public synchronized Evidence evidence() {
        return new Evidence(phase, operating, cancelled.get(), compiledTiles, sourceNanos, maxSliceNanos, overshootNanos, failure);
    }
    synchronized boolean closeComplete() { return !operating && (phase == Phase.DRAINED || phase == Phase.TRANSFERRED); }
    private static String shortFailure(Throwable error) {
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        return text.substring(0, Math.min(256, text.length()));
    }
}
