package io.earthplatform.minecraft.survival.render;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.util.Objects;

/**
 * One bounded safe-publication slot. The simulation owner supplies CaptureOwner only to authorize();
 * no mutable world/player/action/checkpoint object is stored or made available to the renderer.
 * Holds exact PlanData; effect-only reauthorization cannot substitute equal-valued fresh planning.
 */
public final class SurvivalSceneAuthorizationMailbox implements AutoCloseable {
    private SurvivalCompleteOverlay.PlanInput plan;
    private SurvivalCompleteOverlay.CurrentLease pending;
    private NativeDistanceCpuBudget.Reservation charge;
    private boolean requested = true, authorizing, closed;
    private String refusal;
    public SurvivalSceneAuthorizationMailbox(SurvivalCompleteOverlay.PlanInput plan,
            NativeDistanceCpuBudget budget) {
        Objects.requireNonNull(plan).requireBudget(Objects.requireNonNull(budget));
        charge = budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, 4096);
        try { this.plan = plan.retain(); }
        catch (RuntimeException | Error failure) { charge.releaseAfterPhysicalDrain(); throw failure; }
    }
    /** A level/source pump can check this without accessing renderer state. */
    public synchronized boolean requested() { return !closed && requested && !authorizing && pending == null && refusal == null; }
    public synchronized void request() {
        open();
        if (pending == null && !authorizing && refusal == null) requested = true;
    }
    /** Called ONLY by the exact mutable source owner. authorize itself enforces its owner thread. */
    public void authorize(SurvivalCompleteOverlay.CaptureOwner owner) {
        Objects.requireNonNull(owner);
        SurvivalCompleteOverlay.PlanInput exact;
        synchronized (this) {
            open();
            if (!requested()) throw new IllegalStateException("No empty requested authorization slot");
            authorizing = true; exact = plan;
        }
        SurvivalCompleteOverlay.CurrentLease lease = null;
        try {
            lease = owner.authorize(exact);
            synchronized (this) {
                // close refuses an operating slot, so this original exact plan is still retained.
                pending = lease; lease = null; requested = false;
            }
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                String text = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                refusal = text.substring(0, Math.min(256, text.length())); requested = false;
            }
            throw failure;
        } finally {
            if (lease != null) lease.close();
            synchronized (this) { authorizing = false; }
        }
    }
    /** Caller must retain the returned wrapper before any operation; null means still pending. */
    public synchronized SurvivalCompleteOverlay.CurrentLease take() {
        open();
        var result = pending; pending = null; return result;
    }
    public synchronized String refusal() { open(); return refusal; }
    public synchronized void requirePlan(SurvivalCompleteOverlay.PlanInput exact) { open(); plan.requireSame(exact); }
    public synchronized boolean closeComplete() { return closed; }
    @Override public synchronized void close() {
        if (closed) return;
        if (authorizing) throw new IllegalStateException("Authorization remains on its source owner");
        if (pending != null) { pending.close(); pending = null; }
        plan.close(); plan = null; charge.releaseAfterPhysicalDrain(); charge = null; closed = true;
    }
    private void open() { if (closed) throw new IllegalStateException("Source authorization slot is closed"); }
}
